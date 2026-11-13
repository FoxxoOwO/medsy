package cz.medsy.health

import cz.medsy.data.settings.SettingsRepository
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.message.message
import cz.medsy.domain.model.SymptomLog
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes recorded vitals into Health Connect, but only when the user switched it on.
 * Offline-first: a failure here must never block logging a symptom, so the caller gets
 * a result rather than an exception.
 */
@Singleton
class HealthConnectSyncer @Inject constructor(
    private val settingsRepository: SettingsRepository,
    val writer: HealthConnectWriter,
) {

    suspend fun sync(logs: List<SymptomLog>): SyncResult {
        val settings = settingsRepository.settings.first()
        if (!settings.healthConnectEnabled) {
            return SyncResult(written = 0, skipped = logs.size, message = message(MessageKey.HEALTH_SYNC_DISABLED))
        }
        val payloads = HealthRecordMapper.toRecords(logs)
        if (payloads.isEmpty()) {
            return SyncResult(written = 0, skipped = logs.size, message = message(MessageKey.HEALTH_SYNC_NOTHING))
        }
        return runCatching {
            val written = writer.write(payloads)
            SyncResult(written = written, skipped = logs.size - written)
        }.getOrElse { error ->
            SyncResult(written = 0, skipped = logs.size, message = message(MessageKey.HEALTH_SYNC_FAILED))
        }
    }
}
