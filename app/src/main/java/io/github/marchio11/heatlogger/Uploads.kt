package io.github.marchio11.heatlogger

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlin.math.max

/** Programmazione degli invii: funzionano anche se il servizio viene chiuso, e riprovano da soli. */
object Uploads {
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        val prefs = Prefs(context)
        wm.enqueueUniquePeriodicWork(
            "qs", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<QsUploadWorker>(15, TimeUnit.MINUTES)
                .setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build(),
        )
        wm.enqueueUniquePeriodicWork(
            "drive", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<DriveUploadWorker>(max(15, prefs.driveMinutes).toLong(), TimeUnit.MINUTES)
                .setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build(),
        )
    }

    fun qsNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "qs_now", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<QsUploadWorker>()
                .setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build(),
        )
    }

    fun driveNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "drive_now", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DriveUploadWorker>().setConstraints(network).build(),
        )
    }
}
