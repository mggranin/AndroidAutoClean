package app.autoclean
import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.*
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var logv: TextView
    private lateinit var list: EditText
    private val pkgUri get() = Uri.parse("package:$packageName")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p, p, p, p) }
        fun btn(t: String, a: () -> Unit) = root.addView(Button(this).apply { text = t; setOnClickListener { a() } })
        status = TextView(this); root.addView(status)
        btn("1. Shizuku permission") { try { Shizuku.requestPermission(100) } catch (e: Throwable) { Toast.makeText(this, "Start Shizuku first", Toast.LENGTH_LONG).show() } }
        btn("2. All-files access") { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri)) }
        btn("3. Exact alarm (6 AM)") { if (Build.VERSION.SDK_INT >= 31) startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri)) }
        btn("4. Notifications") { if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }
        btn("5. Battery: unrestricted") { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)) }
        btn("Run full clean now") { Scheduler.clean(this, Mode.DAILY); Toast.makeText(this, "Started, see log", Toast.LENGTH_SHORT).show() }
        root.addView(TextView(this).apply { text = "\nDebloat list (edit, then Save)" })
        list = EditText(this).apply { setText(getSharedPreferences("cfg", 0).getString("debloat", Cleaner.DEFAULT_DEBLOAT)); minLines = 5 }
        root.addView(list)
        btn("Save list") { getSharedPreferences("cfg", 0).edit().putString("debloat", list.text.toString()).apply() }
        btn("Refresh log") { refresh() }
        logv = TextView(this).apply { textSize = 11f }; root.addView(logv)
        setContentView(ScrollView(this).apply { addView(root) })
        Scheduler.schedule(this)
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        fun m(ok: Boolean) = if (ok) "OK" else "NO"
        val am = getSystemService(AlarmManager::class.java)
        status.text = "Shizuku running: ${m(Sh.running())}\nShizuku permission: ${m(Sh.ready())}\n" +
            "All-files access: ${m(Environment.isExternalStorageManager())}\n" +
            "Exact alarm: ${m(Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())}\n" +
            "Battery unrestricted: ${m(getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))}\n\nDaily run: 06:00 | Auto-assess: every 30 min"
        logv.text = Log.tail(this)
    }
}
