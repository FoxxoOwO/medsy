package cz.medsy

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import cz.medsy.data.repository.BloodTestRepository
import cz.medsy.notification.NotificationChannels
import cz.medsy.notification.ReminderCoordinator
import cz.medsy.work.WorkScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class MedsyApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var bloodTestRepository: BloodTestRepository

    @Inject lateinit var workScheduler: WorkScheduler

    @Inject lateinit var reminderCoordinator: ReminderCoordinator

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.createAll(this)
        // Arms dose alarms on every cold start; before this, alarms were only armed
        // by the boot receiver, so new schedules never fired until a reboot.
        reminderCoordinator.start()
        appScope.launch {
            runCatching { bloodTestRepository.seedDefaultRangesIfEmpty() }
            runCatching { workScheduler.ensurePeriodicWork() }
        }
    }
}
