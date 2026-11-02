package app.autoclean
import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object Log {
    fun file(ctx: Context): File {
        val d = ctx.getExternalFilesDir("logs") ?: File(ctx.filesDir, "logs")
        d.mkdirs(); return File(d, "cleaner.log")
    }
    @Synchronized fun w(ctx: Context, msg: String) {
        val f = file(ctx)
        if (f.length() > 512_000) f.writeText("")
        f.appendText(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + "  " + msg + "\n")
    }
    fun tail(ctx: Context, n: Int = 40) = runCatching { file(ctx).readLines().takeLast(n).joinToString("\n") }.getOrDefault("")
}
