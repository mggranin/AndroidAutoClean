package app.autoclean
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.*
import java.util.Calendar
import java.util.concurrent.TimeUnit

class CleanWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        Cleaner.run(applicationContext, Mode.valueOf(inputData.getString("mode") ?: "DAILY")); return Result.success()
    }
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("clean", "Cleaning", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(applicationContext, "clean").setSmallIcon(android.R.drawable.ic_menu_delete).setContentTitle("AutoClean running").build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(1, n)
    }
}

class AssessWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result { Cleaner.assess(applicationContext)?.let { Cleaner.run(applicationContext, it) }; return Result.success() }
}

object Scheduler {
    fun clean(ctx: Context, mode: Mode) {
        val req = OneTimeWorkRequestBuilder<CleanWorker>().setInputData(workDataOf("mode" to mode.name))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("clean", ExistingWorkPolicy.KEEP, req)
    }
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 6); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1) }
        val pi = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, AlarmReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("assess", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AssessWorker>(30, TimeUnit.MINUTES).build())
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { Scheduler.schedule(c); Scheduler.clean(c, Mode.DAILY) }
}
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { Scheduler.schedule(c) }
}
