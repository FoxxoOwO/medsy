package cz.medsy.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cz.medsy.data.repository.BloodTestRepository
import cz.medsy.data.repository.InventoryRepository
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.message.message
import cz.medsy.domain.message.messagePlural
import cz.medsy.ui.message.asText
import cz.medsy.data.repository.SuklRepository
import cz.medsy.data.settings.SettingsRepository
import cz.medsy.notification.DoseNotifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

@HiltWorker
class SuklSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: SuklRepository,
    private val store: cz.medsy.data.settings.SuklSyncStore,
) : CoroutineWorker(context, params) {

    /**
     * The outcome is written where the settings screen can read it: a retry is silence, and only
     * a run that will not be repeated ends up as a success or a failure.
     */
    override suspend fun doWork(): Result {
        if (runAttemptCount > MAX_RETRIES) return giveUp()
        return repository.sync().fold(
            onSuccess = { count ->
                if (count <= 0) return Result.retry()
                store.recordSuccess(repository.localCount())
                Result.success()
            },
            onFailure = {
                if (runAttemptCount >= MAX_RETRIES) giveUp() else Result.retry()
            },
        )
    }

    private suspend fun giveUp(): Result {
        store.recordFailure()
        return Result.failure()
    }

    private companion object {
        const val MAX_RETRIES = 3
    }
}

@HiltWorker
class InventoryRecalcWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val inventoryRepository: InventoryRepository,
    private val settingsRepository: SettingsRepository,
    private val notifier: DoseNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val discreet = settingsRepository.settings.first().discreetMode
        val lines = runCatching { inventoryRepository.stockLines() }.getOrElse { return Result.retry() }

        // The ids are index-based and the run only *posts*: a medication that was restocked
        // would keep the id of a line that is no longer flagged, so the alert has to be
        // cleared before the new set is raised. The bound covers every id this worker can use.
        for (offset in 0 until MAX_NOTIFICATIONS) notifier.cancel(NOTIFICATION_BASE + offset)

        lines.forEachIndexed { index, line ->
            when {
                // Expired stock is reported before "low stock", otherwise a fully
                // expired medication was announced as "about 0 days left".
                line.expiredBatches.isNotEmpty() -> {
                    val expiry = line.expiredBatches.first().expirationDate?.toString().orEmpty()
                    notifier.notifyStock(
                        notificationId = NOTIFICATION_BASE + index,
                        message = message(
                            MessageKey.NOTIFY_EXPIRED_DISCARD,
                            line.medicationName,
                            expiry,
                        ).asText(applicationContext),
                        discreet = discreet,
                    )
                }
                line.beyondUsePassed.isNotEmpty() -> {
                    val batch = line.beyondUsePassed.first()
                    val bud = batch.beyondUseDate()?.toString().orEmpty()
                    notifier.notifyStock(
                        notificationId = NOTIFICATION_BASE + index,
                        message = message(
                            MessageKey.NOTIFY_DISCARD_AFTER_BUD,
                            line.medicationName,
                            bud,
                        ).asText(applicationContext),
                        discreet = discreet,
                    )
                }
                line.isLow && line.perDay.signum() > 0 -> {
                    notifier.notifyStock(
                        notificationId = NOTIFICATION_BASE + index,
                        message = messagePlural(
                            MessageKey.NOTIFY_LOW_STOCK,
                            line.daysRemaining,
                            line.medicationName,
                            line.daysRemaining,
                        ).asText(applicationContext),
                        discreet = discreet,
                    )
                }
                line.expiringBatches.isNotEmpty() -> {
                    val expiry = line.expiringBatches.first().expirationDate?.toString().orEmpty()
                    notifier.notifyStock(
                        notificationId = NOTIFICATION_BASE + index,
                        message = message(
                            MessageKey.NOTIFY_EXPIRES,
                            line.medicationName,
                            expiry,
                        ).asText(applicationContext),
                        discreet = discreet,
                    )
                }
            }
        }
        return Result.success()
    }

    private companion object {
        const val NOTIFICATION_BASE = 4000

        /** Index-based ids; a larger stock list would need a different scheme. */
        const val MAX_NOTIFICATIONS = 64
    }
}

@HiltWorker
class BloodworkReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: BloodTestRepository,
    private val notifier: DoseNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val warnings = runCatching { repository.preTestWarnings() }.getOrElse { return Result.retry() }
        if (warnings.isEmpty()) {
            // The draw has happened or the test was deleted; the old warning must not survive it.
            notifier.cancel(NOTIFICATION_ID)
            return Result.success()
        }
        notifier.notifyStock(
            notificationId = NOTIFICATION_ID,
            message = warnings.first().title.asText(applicationContext) +
                " — " + warnings.first().detail.asText(applicationContext),
            discreet = false,
        )
        return Result.success()
    }

    private companion object {
        const val NOTIFICATION_ID = 5000
    }
}
