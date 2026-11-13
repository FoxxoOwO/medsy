package cz.medsy.work

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkScheduler @Inject constructor(
    private val workManager: WorkManager,
) {

    fun ensurePeriodicWork() {
        scheduleSuklSync()
        scheduleInventoryRecalc()
        scheduleBloodworkReminder()
    }

    fun scheduleSuklSync() {
        val request = PeriodicWorkRequestBuilder<SuklSyncWorker>(7, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        workManager.enqueueUniquePeriodicWork(
            SUKL_SYNC,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun scheduleInventoryRecalc() {
        val request = PeriodicWorkRequestBuilder<InventoryRecalcWorker>(12, TimeUnit.HOURS)
            .build()
        workManager.enqueueUniquePeriodicWork(
            INVENTORY_RECALC,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun scheduleBloodworkReminder() {
        val request = PeriodicWorkRequestBuilder<BloodworkReminderWorker>(1, TimeUnit.DAYS)
            .build()
        workManager.enqueueUniquePeriodicWork(
            BLOODWORK_REMINDER,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun syncSuklNow() {
        val request = OneTimeWorkRequestBuilder<SuklSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        workManager.enqueueUniqueWork(SUKL_SYNC_NOW, ExistingWorkPolicy.REPLACE, request)
    }

    fun recalcInventoryNow() {
        workManager.enqueue(
            OneTimeWorkRequestBuilder<InventoryRecalcWorker>()
                .setInputData(workDataOf("reason" to "manual"))
                .build(),
        )
    }

    companion object {
        const val SUKL_SYNC = "sukl_sync"
        const val SUKL_SYNC_NOW = "sukl_sync_now"
        const val INVENTORY_RECALC = "inventory_recalc"
        const val BLOODWORK_REMINDER = "bloodwork_reminder"
    }
}
