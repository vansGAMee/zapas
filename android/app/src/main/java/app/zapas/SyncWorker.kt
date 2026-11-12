package app.zapas

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val repo = applicationContext.repo()
        if (!repo.paired) return Result.success()
        return try {
            withTimeout(TimeUnit.MINUTES.toMillis(8)) {
                setForeground(notification("Проверяем медиатеку на компьютере"))
                repo.sync { setForeground(notification(it)) }
            }
            Result.success()
        } catch (e: TimeoutCancellationException) {
            repo.status.value = "Синхронизация продолжится позже. Скачанное доступно офлайн."
            Result.retry()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            repo.status.value = if (e is java.net.ConnectException || e is java.net.SocketTimeoutException || e is java.net.UnknownHostException)
                "Домашний ПК недоступен. Скачанные видео остаются с тобой."
            else e.message?.take(180) ?: "ПК недоступен. Проверь домашний Wi‑Fi."
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun notification(message: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("sync", "Пополнение запаса", NotificationManager.IMPORTANCE_LOW))
        val launch = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(applicationContext, "sync").setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Запас · Пополняем видео").setContentText(message).setContentIntent(launch)
            .setOngoing(true).setSilent(true).addAction(0, "Остановить", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return if (android.os.Build.VERSION.SDK_INT >= 29) ForegroundInfo(9473, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(9473, n)
    }

    companion object {
        fun schedule(context: Context) {
            val p = context.repo().prefs
            if (!p.getBoolean("auto_sync", true)) {
                WorkManager.getInstance(context).cancelUniqueWork("zapas-periodic")
                return
            }
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresStorageNotLow(true)
                .setRequiresBatteryNotLow(true).setRequiresCharging(p.getBoolean("charging_only", false)).build()
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag("zapas-sync").build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("zapas-periodic", ExistingPeriodicWorkPolicy.UPDATE, request)
        }
        fun now(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag("zapas-sync").build()
            WorkManager.getInstance(context).enqueueUniqueWork("zapas-manual", ExistingWorkPolicy.KEEP, request)
        }
        fun cancel(context: Context) { WorkManager.getInstance(context).cancelAllWorkByTag("zapas-sync") }
    }
}
