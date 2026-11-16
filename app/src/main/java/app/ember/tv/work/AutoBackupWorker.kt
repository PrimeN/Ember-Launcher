package app.ember.tv.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.ember.tv.data.AutoBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Settings > Back up > Automatic backups: checks twice a day and backs up when one is due. */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        AutoBackup.runIfDue(applicationContext)
        Result.success()
    }

    companion object {
        private const val NAME = "ember-auto-backup"
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(12, TimeUnit.HOURS)
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
