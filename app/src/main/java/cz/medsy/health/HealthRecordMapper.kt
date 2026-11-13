package cz.medsy.health

import cz.medsy.domain.model.SymptomLog
import java.time.Instant

/**
 * Maps a symptom log to the Health Connect record it should produce.
 *
 * Deliberately conservative: a value that could not be a real measurement (a heart rate
 * of 900, a diastolic above the systolic) is dropped rather than written into the
 * patient's health record. Health Connect is read by other apps and by clinicians, so a
 * bad row there is worse than a missing one.
 */
object HealthRecordMapper {

    sealed interface Payload {
        val timestamp: Instant
        val clientRecordId: String

        data class BloodPressure(
            override val timestamp: Instant,
            override val clientRecordId: String,
            val systolicMmHg: Int,
            val diastolicMmHg: Int,
        ) : Payload

        data class HeartRate(
            override val timestamp: Instant,
            override val clientRecordId: String,
            val beatsPerMinute: Int,
        ) : Payload
    }

    private val plausibleHeartRate = 20..300
    private val plausibleSystolic = 50..300
    private val plausibleDiastolic = 20..200

    fun toRecords(logs: List<SymptomLog>): List<Payload> = logs.flatMap { toRecord(it) }

    fun toRecord(log: SymptomLog): List<Payload> {
        val out = ArrayList<Payload>(2)

        val systolic = log.systolic
        val diastolic = log.diastolic
        if (systolic != null && diastolic != null &&
            systolic in plausibleSystolic &&
            diastolic in plausibleDiastolic &&
            systolic > diastolic
        ) {
            out += Payload.BloodPressure(
                timestamp = log.timestamp,
                clientRecordId = key(log, "bp"),
                systolicMmHg = systolic,
                diastolicMmHg = diastolic,
            )
        }

        log.heartRate?.let { hr ->
            if (hr in plausibleHeartRate) {
                out += Payload.HeartRate(
                    timestamp = log.timestamp,
                    clientRecordId = key(log, "hr"),
                    beatsPerMinute = hr,
                )
            }
        }
        return out
    }

    /**
     * Stable per log and record type. Health Connect rejects duplicate client ids, which
     * is what keeps a re-sync from creating a second copy of the same measurement.
     */
    fun dedupeKeys(log: SymptomLog): Set<String> =
        toRecord(log).map { it.clientRecordId }.toSet()

    private fun key(log: SymptomLog, type: String): String =
        "medsy-$type-${log.id}-${log.timestamp.toEpochMilli()}"
}

/** Abstraction so the sync logic can be tested without a Health Connect provider. */
interface HealthConnectWriter {
    suspend fun write(payloads: List<HealthRecordMapper.Payload>): Int
}

data class SyncResult(
    val written: Int,
    val skipped: Int,
    /** Why nothing was written, phrased so the caller can show it in the user's language. */
    val message: cz.medsy.domain.message.Message? = null,
)
