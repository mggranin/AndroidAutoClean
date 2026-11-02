package app.autoclean
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Runs shell-level (uid 2000) commands through Shizuku. No prompts once permission is granted. */
object Sh {
    fun running() = try { Shizuku.pingBinder() } catch (e: Throwable) { false }
    fun ready() = running() && try { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (e: Throwable) { false }
    fun run(cmd: String): Pair<Int, String> {
        val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", "($cmd) 2>&1"), null, null) as Process
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }
    fun lines(cmd: String) = run(cmd).second.lines().map { it.trim() }.filter { it.isNotEmpty() }
}
