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
        scheduleUpdateCheck()
    }

    /**
     * Daily is enough: a release is a rare event, and the check is one request. KEEP keeps the
     * existing window so the app does not reset it on every start.
     */
    fun scheduleUpdateCheck() {
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        workManager.enqueueUniquePeriodicWork(UPDATE_CHECK, ExistingPeriodicWorkPolicy.KEEP, request)
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

    /** "Check now" on the settings screen: a check without waiting for the daily window. */
    fun checkUpdatesNow() {
        val request = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        workManager.enqueueUniqueWork(UPDATE_CHECK_NOW, ExistingWorkPolicy.REPLACE, request)
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
        const val UPDATE_CHECK = "update_check"
        const val UPDATE_CHECK_NOW = "update_check_now"
    }
}
