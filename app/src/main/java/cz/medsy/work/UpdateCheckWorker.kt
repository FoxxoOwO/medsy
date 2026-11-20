package cz.medsy.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cz.medsy.data.settings.SettingsRepository
import cz.medsy.data.update.UpdateChecker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * One silent check a day. It only writes what it found into settings; the user is told in the
 * app, not through a notification, because an update is never urgent.
 */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val checker: UpdateChecker,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!settings.settings.first().updateCheckEnabled) return Result.success()
        return runCatching { checker.check() }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }
}
