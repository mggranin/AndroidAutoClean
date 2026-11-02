package app.autoclean
import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

enum class Mode { DAILY, STORAGE, RAM }

object Cleaner {
    const val DEFAULT_DEBLOAT = "# one package per line. Reversible: cmd package install-existing <pkg>\ncom.facebook.appmanager\ncom.facebook.services\ncom.facebook.system\ncom.netflix.partner.activation"
    private val lock = Any()
    private val root: File = Environment.getExternalStorageDirectory()
    private val day = SimpleDateFormat("yyyyMMdd", Locale.US)
    private fun trashRoot() = File(root, ".CleanerTrash")
    private fun trashToday() = File(trashRoot(), day.format(Date()))
    private const val TRASH_DAYS = 7

    private fun freePct(): Pair<Long, Int> {
        val s = StatFs(Environment.getDataDirectory().path)
        return s.availableBytes to (s.availableBytes * 100 / s.totalBytes).toInt()
    }
    private fun ramPct(ctx: Context): Int {
        val mi = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        return (mi.availMem * 100 / mi.totalMem).toInt()
    }

    /** Auto-assess: returns the mode to run, or null if the phone is healthy / ran recently. */
    fun assess(ctx: Context): Mode? {
        val prefs = ctx.getSharedPreferences("cfg", 0)
        if (System.currentTimeMillis() - prefs.getLong("last", 0) < 2 * 3600_000L) return null
        val (_, sp) = freePct(); val rp = ramPct(ctx)
        Log.w(ctx, "assess: storage free $sp%, RAM free $rp%")
        return when { sp < 10 -> Mode.STORAGE; sp < 15 || rp < 15 -> Mode.RAM; else -> null }
    }

    fun run(ctx: Context, mode: Mode) = synchronized(lock) {
        fun l(m: String) = Log.w(ctx, m)
        fun step(name: String, b: () -> String) = try { l("$name: ${b()}") } catch (e: Throwable) { l("$name FAILED: ${e.message}") }
        l("=== START $mode ===")
        val (f0, _) = freePct(); val r0 = ramPct(ctx)
        val priv = Sh.ready()
        if (!priv) l("Shizuku not running/granted: privileged steps skipped")
        val all = mode == Mode.DAILY; val storage = mode != Mode.RAM
        if (priv) step("kill-background") { killBackground(ctx) }
        if (priv) step("trim-caches") { Sh.run("pm trim-caches 999G"); "done" }
        if (priv && storage) step("app-leftovers") { leftovers() }
        if (priv && all) step("debloat") { debloat(ctx) }
        if (storage && Environment.isExternalStorageManager()) {
            step("junk") { junk() }
            step("duplicates") { duplicates() }
            step("purge-trash") { purgeTrash(priv) }
        } else if (storage) l("All-files access missing: junk/duplicates skipped")
        if (priv && all) step("tune") { tune() }
        val (f1, _) = freePct()
        l("storage freed ${(f1 - f0) / 1048576} MB | RAM free ${r0}% -> ${ramPct(ctx)}%")
        ctx.getSharedPreferences("cfg", 0).edit().putLong("last", System.currentTimeMillis()).apply()
        l("=== END ===")
    }

    private fun killBackground(ctx: Context): String {
        val pk = Sh.lines("pm list packages -3").map { it.removePrefix("package:") }.filter { it != ctx.packageName }
        // 'am kill' only stops safe-to-kill background processes: alarms/notifications keep working.
        Sh.run(pk.joinToString(";") { "am kill $it" } + ";am kill-all")
        return "${pk.size} apps"
    }

    private fun leftovers(): String {
        val known = Sh.lines("pm list packages -u").map { it.removePrefix("package:") }.toSet()
        if (known.size < 20) return "skipped (package list unreliable)"
        var n = 0
        for (b in listOf("data", "obb", "media")) {
            val dir = "/storage/emulated/0/Android/$b"
            for (name in Sh.lines("ls -1 $dir")) {
                if (!name.contains('.') || name in known || !Regex("[A-Za-z0-9_.]+").matches(name)) continue
                val dest = "${trashToday().path}/android_$b"
                Sh.run("mkdir -p '$dest' && mv '$dir/$name' '$dest/'"); n++
            }
        }
        return "$n folders moved to trash"
    }

    private fun debloat(ctx: Context): String {
        val list = ctx.getSharedPreferences("cfg", 0).getString("debloat", DEFAULT_DEBLOAT)!!
            .lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val installed = Sh.lines("pm list packages").map { it.removePrefix("package:") }.toSet()
        var n = 0
        for (p in list) if (p in installed) { if (Sh.run("pm uninstall -k --user 0 $p").second.contains("Success")) n++ }
        return "$n removed (data kept, restorable)"
    }

    private fun tune(): String {
        Sh.run("settings put global window_animation_scale 0.5;settings put global transition_animation_scale 0.5;settings put global animator_duration_scale 0.5")
        Sh.run("logcat -c; cmd package bg-dexopt-job; sm idle-maint run")
        return "animations 0.5x, logs cleared, dexopt/trim triggered"
    }

    private fun toTrash(f: File): Boolean {
        val dest = File(trashToday(), f.relativeTo(root).path)
        dest.parentFile?.mkdirs()
        File(trashRoot(), ".nomedia").let { if (!it.exists()) runCatching { it.createNewFile() } }
        return f.renameTo(dest)
    }

    private fun junk(): String {
        val trash = trashRoot(); val cut = System.currentTimeMillis() - 30L * 86400_000
        val files = root.walkTopDown().onEnter { it != trash && !(it.parentFile == root && it.name == "Android") }
            .filter { it.isFile && !it.name.startsWith(".nomedia") }.filter {
                val n = it.name.lowercase()
                n.endsWith(".tmp") || n.endsWith(".temp") || n.endsWith(".log") || n.startsWith(".thumbdata") ||
                    it.parentFile?.name in setOf(".thumbnails", "LOST.DIR") || it.length() == 0L ||
                    (n.endsWith(".apk") && it.parentFile?.name == "Download" && it.lastModified() < cut)
            }.toList()
        var bytes = 0L; var c = 0
        for (f in files) { val s = f.length(); if (toTrash(f)) { bytes += s; c++ } }
        var dirs = 0
        root.walkBottomUp().onEnter { it != trash && !(it.parentFile == root && it.name == "Android") }
            .filter { it.isDirectory && it.parentFile != root && it.list()?.isEmpty() == true }
            .toList().forEach { if (it.delete()) dirs++ }
        return "$c files (${bytes / 1048576} MB) to trash, $dirs empty folders removed"
    }

    private fun sha(f: File): String {
        val md = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(1 shl 20)
        f.inputStream().use { i -> while (true) { val r = i.read(buf); if (r < 0) break; md.update(buf, 0, r) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun duplicates(): String {
        val dirs = listOf("DCIM", "Pictures", "Download", "Documents", "Movies", "Music")
        val files = dirs.flatMap { File(root, it).walkTopDown().filter { f -> f.isFile && f.length() >= 51200 && !f.name.startsWith(".") && f.parentFile?.name != ".thumbnails" }.toList() }
        var c = 0; var bytes = 0L
        for (grp in files.groupBy { it.length() }.values.filter { it.size > 1 }) {
            for (same in grp.groupBy { runCatching { sha(it) }.getOrDefault(it.path) }.values.filter { it.size > 1 }) {
                val keep = same.minWith(compareBy({ it.lastModified() }, { it.path.length }))
                for (f in same) if (f != keep) { val s = f.length(); if (toTrash(f)) { c++; bytes += s } }
            }
        }
        return "$c duplicates (${bytes / 1048576} MB) to trash"
    }

    private fun purgeTrash(priv: Boolean): String {
        val cutoff = day.format(Date(System.currentTimeMillis() - TRASH_DAYS * 86400_000L)); var n = 0
        trashRoot().listFiles()?.filter { it.isDirectory && Regex("\\d{8}").matches(it.name) && it.name < cutoff }?.forEach {
            if (priv) Sh.run("rm -rf '${it.path}'") else it.deleteRecursively(); n++
        }
        return "$n old trash folders purged (>${TRASH_DAYS} days)"
    }
}
