package cz.medsy.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Pressure
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real [HealthConnectWriter] backed by the platform client.
 *
 * Records are tagged `manualEntry` with our own client id, because these values are
 * typed in by the patient, not measured by a connected device. A duplicate client id
 * makes Health Connect ignore the insert, which is what prevents double-writing on a
 * repeated sync.
 */
@Singleton
class PlatformHealthConnectWriter @Inject constructor(
    @ApplicationContext private val context: Context,
) : HealthConnectWriter {

    override suspend fun write(payloads: List<HealthRecordMapper.Payload>): Int {
        if (payloads.isEmpty()) return 0
        if (!HealthConnectAvailability.isAvailable(context)) return 0

        val client = HealthConnectClient.getOrCreate(context)
        val metadata = Metadata.manualEntry()
        val records = payloads.mapNotNull { payload -> toRecord(payload, metadata) }
        if (records.isEmpty()) return 0

        client.insertRecords(records)
        return records.size
    }

    private fun toRecord(
        payload: HealthRecordMapper.Payload,
        metadata: Metadata,
    ): androidx.health.connect.client.records.Record? = when (payload) {
        is HealthRecordMapper.Payload.BloodPressure -> BloodPressureRecord(
            time = payload.timestamp,
            zoneOffset = ZoneOffset.UTC,
            metadata = metadata,
            systolic = Pressure.millimetersOfMercury(payload.systolicMmHg.toDouble()),
            diastolic = Pressure.millimetersOfMercury(payload.diastolicMmHg.toDouble()),
            measurementLocation = BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_UPPER_ARM,
        )
        is HealthRecordMapper.Payload.HeartRate -> HeartRateRecord(
            startTime = payload.timestamp,
            startZoneOffset = ZoneOffset.UTC,
            endTime = payload.timestamp,
            endZoneOffset = ZoneOffset.UTC,
            samples = listOf(
                HeartRateRecord.Sample(
                    time = payload.timestamp,
                    beatsPerMinute = payload.beatsPerMinute.toLong(),
                ),
            ),
            metadata = metadata,
        )
    }
}
