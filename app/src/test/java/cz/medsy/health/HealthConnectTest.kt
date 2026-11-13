package cz.medsy.health

import androidx.test.core.app.ApplicationProvider
import cz.medsy.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * Health Connect was a dependency and a DataStore flag with no code behind either: the
 * toggle was absent from the UI, no permission was declared, and nothing ever wrote a
 * record. These tests pin the behaviour of the pieces that must exist.
 */
@RunWith(RobolectricTestRunner::class)
class HealthConnectTest {

    private lateinit var context: android.app.Application
    private lateinit var settingsRepository: SettingsRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        settingsRepository = SettingsRepository(context)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the toggle defaults to off`() {
        assertFalse(cz.medsy.data.settings.AppSettings().healthConnectEnabled)
    }

    @Test
    fun `the toggle persists`() = runBlocking {
        settingsRepository.setHealthConnect(true)
        assertTrue(settingsRepository.settings.first().healthConnectEnabled)

        settingsRepository.setHealthConnect(false)
        assertFalse(settingsRepository.settings.first().healthConnectEnabled)
    }

    @Test
    fun `write permissions cover the vitals the app actually records`() {
        assertTrue(HealthPermissions.writePermissions.contains(HealthPermissions.WRITE_BLOOD_PRESSURE))
        assertTrue(HealthPermissions.writePermissions.contains(HealthPermissions.WRITE_HEART_RATE))
        assertFalse(
            "the app has no weight log, so it must not ask for weight access",
            HealthPermissions.writePermissions.any { it.contains("WEIGHT") },
        )
    }

    @Test
    fun `read permissions are the minimum needed for the dashboard`() {
        assertTrue(HealthPermissions.readPermissions.contains(HealthPermissions.READ_HEART_RATE))
        assertFalse(
            "nothing reads body weight",
            HealthPermissions.readPermissions.any { it.contains("WEIGHT") },
        )
        assertFalse("no reason to read steps", HealthPermissions.readPermissions.any { it.contains("STEPS") })
    }

    @Test
    fun `availability reports unavailable when Health Connect is absent`() {
        // Robolectric has no Health Connect provider installed, so this must degrade
        // gracefully instead of throwing.
        val status = HealthConnectAvailability.status(context)
        assertNotNull(status)
        assertFalse(
            "a device without the provider must not claim availability",
            status == HealthConnectAvailability.Status.AVAILABLE,
        )
    }

    @Test
    fun `vital mapping skips logs without measurements`() {
        val log = cz.medsy.domain.model.SymptomLog(
            id = 1,
            timestamp = Instant.parse("2026-11-11T08:00:00Z"),
            symptomCode = "MOOD",
            severity = 3,
        )

        assertTrue(HealthRecordMapper.toRecords(listOf(log)).isEmpty())
    }

    @Test
    fun `blood pressure log maps to a record`() {
        val log = cz.medsy.domain.model.SymptomLog(
            id = 1,
            timestamp = Instant.parse("2026-11-11T08:00:00Z"),
            symptomCode = "BP",
            severity = 1,
            systolic = 118,
            diastolic = 76,
        )

        val payloads = HealthRecordMapper.toRecords(listOf(log))
        assertEquals(1, payloads.size)
        val bp = payloads.single() as HealthRecordMapper.Payload.BloodPressure
        assertEquals(118, bp.systolicMmHg)
        assertEquals(76, bp.diastolicMmHg)
    }

    @Test
    fun `heart rate log maps only when the value is plausible`() {
        val valid = cz.medsy.domain.model.SymptomLog(
            id = 1,
            timestamp = Instant.parse("2026-11-11T08:00:00Z"),
            symptomCode = "HR",
            severity = 1,
            heartRate = 68,
        )
        val implausible = valid.copy(id = 2, heartRate = 900)

        assertEquals(1, HealthRecordMapper.toRecords(listOf(valid)).size)
        assertTrue(HealthRecordMapper.toRecords(listOf(implausible)).isEmpty())
    }

    @Test
    fun `implausible blood pressure is rejected`() {
        val reversed = cz.medsy.domain.model.SymptomLog(
            id = 1,
            timestamp = Instant.parse("2026-11-11T08:00:00Z"),
            symptomCode = "BP",
            severity = 1,
            systolic = 70,
            diastolic = 120,
        )
        assertTrue(HealthRecordMapper.toRecords(listOf(reversed)).isEmpty())
    }

    @Test
    fun `dedupe key is stable per log and type`() {
        val log = cz.medsy.domain.model.SymptomLog(
            id = 42,
            timestamp = Instant.parse("2026-11-11T08:00:00Z"),
            symptomCode = "BP",
            severity = 1,
            systolic = 120,
            diastolic = 80,
        )

        val first = HealthRecordMapper.dedupeKeys(log)
        val second = HealthRecordMapper.dedupeKeys(log)

        assertEquals(first, second)
        assertTrue(first.isNotEmpty())
    }

    @Test
    fun `a disabled toggle means no sync is attempted`() = runBlocking {
        settingsRepository.setHealthConnect(false)
        val syncer = HealthConnectSyncer(
            settingsRepository = settingsRepository,
            writer = RecordingWriter(),
        )
        val log = cz.medsy.domain.model.SymptomLog(
            id = 1,
            timestamp = Instant.parse("2026-11-11T08:00:00Z"),
            symptomCode = "BP",
            severity = 1,
            systolic = 120,
            diastolic = 80,
        )

        val result = syncer.sync(listOf(log))

        assertEquals(0, result.written)
        assertEquals(0, (syncer.writer as RecordingWriter).written.size)
    }

    @Test
    fun `enabled toggle writes valid vitals`() = runBlocking {
        settingsRepository.setHealthConnect(true)
        val writer = RecordingWriter()
        val syncer = HealthConnectSyncer(settingsRepository, writer)
        val log = cz.medsy.domain.model.SymptomLog(
            id = 5,
            timestamp = Instant.parse("2026-11-11T08:00:00Z"),
            symptomCode = "BP",
            severity = 1,
            systolic = 118,
            diastolic = 76,
            heartRate = 68,
        )

        val result = syncer.sync(listOf(log))

        assertEquals(2, result.written)
        assertEquals(2, writer.written.size)
    }

    @Test
    fun `a write failure does not throw`() = runBlocking {
        settingsRepository.setHealthConnect(true)
        val syncer = HealthConnectSyncer(
            settingsRepository,
            object : HealthConnectWriter {
                override suspend fun write(payloads: List<HealthRecordMapper.Payload>): Int =
                    error("provider missing")
            },
        )
        val log = cz.medsy.domain.model.SymptomLog(
            id = 1,
            timestamp = Instant.parse("2026-11-11T08:00:00Z"),
            symptomCode = "HR",
            severity = 1,
            heartRate = 70,
        )

        val result = syncer.sync(listOf(log))

        assertEquals(0, result.written)
        // A failing provider must not lose the entry; it reports why it wrote nothing.
        assertEquals(cz.medsy.domain.message.MessageKey.HEALTH_SYNC_FAILED, result.message?.key)
    }

    private class RecordingWriter : HealthConnectWriter {
        val written = mutableListOf<HealthRecordMapper.Payload>()
        override suspend fun write(payloads: List<HealthRecordMapper.Payload>): Int {
            written += payloads
            return payloads.size
        }
    }
}
