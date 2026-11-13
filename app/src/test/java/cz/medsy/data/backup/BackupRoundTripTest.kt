package cz.medsy.data.backup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cz.medsy.data.local.MedsyDatabase
import cz.medsy.data.local.entity.DoseLogEntity
import cz.medsy.data.local.entity.MedicationEntity
import cz.medsy.data.local.entity.ScheduleEntity
import cz.medsy.data.local.entity.SymptomLogEntity
import cz.medsy.domain.message.MessageKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Runs against the real schema in memory. SQLCipher is skipped on purpose: it changes how the file
 * is encrypted, not how rows are read or written, and a backup reads one cursor at a time.
 */
@RunWith(RobolectricTestRunner::class)
class BackupRoundTripTest {

    private lateinit var db: MedsyDatabase
    private lateinit var repository: BackupRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MedsyDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = BackupRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seed(): Long {
        val medicationId = db.medicationDao().insert(
            MedicationEntity(
                name = "Estradiol",
                form = "INJECTION_IM",
                strengthValue = 5.0,
                strengthUnit = "mg",
                concentrationMgPerMl = 50.0,
                isHrt = true,
            ),
        )
        val scheduleId = db.scheduleDao().insert(
            ScheduleEntity(
                medicationId = medicationId,
                scheduleType = "INTERVAL_DAYS",
                intervalValue = 3.5,
                timesOfDayJson = """["08:00"]""",
                doseAmount = 5.0,
                doseUnit = "mg",
                startDateEpochDay = 20_000L,
            ),
        )
        db.doseLogDao().upsert(
            DoseLogEntity(
                medicationId = medicationId,
                scheduleId = scheduleId,
                scheduledTimestamp = 1_700_000_000_000,
                status = "TAKEN",
                actualTimestamp = 1_700_000_060_000,
                applicationSite = "LEFT_DELTOID",
            ),
        )
        db.doseLogDao().upsert(
            DoseLogEntity(
                medicationId = medicationId,
                scheduleId = scheduleId,
                scheduledTimestamp = 1_700_300_000_000,
                status = "SKIPPED",
            ),
        )
        db.symptomDao().insert(
            SymptomLogEntity(
                timestamp = 1_700_000_500_000,
                symptomCode = "MOOD",
                severity = 4,
                note = "klidná",
                systolic = 118,
                diastolic = 74,
                heartRate = 68,
            ),
        )
        return medicationId
    }

    private fun count(table: String): Long =
        db.query("SELECT COUNT(*) FROM $table", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    @Test
    fun `a restored backup keeps every row and its links`() = runTest {
        val originalId = seed()
        val json = repository.export()

        db.clearAllTables()
        assertEquals(0L, count("medications"))

        val report = repository.import(json, null, BackupRepository.Mode.REPLACE)

        assertTrue(report.inserted > 0)
        assertEquals(1L, count("medications"))
        assertEquals(1L, count("schedules"))
        assertEquals(2L, count("dose_logs"))
        assertEquals(1L, count("symptom_logs"))

        // Ids are handed out again, so the links must have been rewritten, not copied.
        val medication = db.medicationDao().observeAll().first().single()
        assertNotEquals(originalId, medication.id)
        val schedule = db.scheduleDao().getActive().single()
        assertEquals(medication.id, schedule.medicationId)

        val dose = db.doseLogDao().getFrom(0).first()
        assertEquals(medication.id, dose.medicationId)
        assertEquals(schedule.id, dose.scheduleId)
        assertEquals("LEFT_DELTOID", dose.applicationSite)
        assertEquals("TAKEN", dose.status)

        val symptom = db.symptomDao().recent().single()
        assertEquals("klidná", symptom.note)
        assertEquals(118, symptom.systolic)
        assertEquals(68, symptom.heartRate)
    }

    @Test
    fun `merging the same file twice adds nothing the second time`() = runTest {
        seed()
        val json = repository.export()
        val first = repository.import(json, null, BackupRepository.Mode.MERGE)
        val second = repository.import(json, null, BackupRepository.Mode.MERGE)

        assertEquals(0, first.inserted)
        assertEquals(0, second.inserted)
        assertTrue(first.skipped > 0)
        assertEquals(1L, count("medications"))
        assertEquals(2L, count("dose_logs"))
        assertEquals(1L, count("symptom_logs"))
    }

    @Test
    fun `merging brings in a row that is missing`() = runTest {
        seed()
        val json = repository.export()
        db.symptomDao().recent().forEach { db.symptomDao().delete(it.id) }

        val report = repository.import(json, null, BackupRepository.Mode.MERGE)

        assertEquals(1, report.inserted)
        assertEquals(1L, count("symptom_logs"))
        // The medication is already here, so its children attach to the existing row.
        assertEquals(1L, count("medications"))
    }

    @Test
    fun `merging keeps a medication that is not in the file`() = runTest {
        seed()
        val json = repository.export()
        db.medicationDao().insert(
            MedicationEntity(name = "Progesteron", form = "CAPSULE", strengthValue = 100.0),
        )

        repository.import(json, null, BackupRepository.Mode.MERGE)

        val names = db.medicationDao().observeAll().first().map { it.name }.toSet()
        assertEquals(setOf("Estradiol", "Progesteron"), names)
        val schedule = db.scheduleDao().getActive().single()
        val estradiol = db.medicationDao().observeAll().first().first { it.name == "Estradiol" }
        assertEquals(estradiol.id, schedule.medicationId)
    }

    @Test
    fun `replacing removes what the file does not contain`() = runTest {
        seed()
        val json = repository.export()
        db.symptomDao().let { dao -> dao.recent().forEach { dao.delete(it.id) } }
        db.medicationDao().insert(
            MedicationEntity(name = "Progesteron", form = "CAPSULE", strengthValue = 100.0),
        )

        repository.import(json, null, BackupRepository.Mode.REPLACE)

        val names = db.medicationDao().observeAll().first().map { it.name }
        assertEquals(listOf("Estradiol"), names)
        assertEquals(1L, count("symptom_logs"))
    }

    @Test
    fun `a password-protected backup only opens with its password`() = runTest {
        seed()
        val json = repository.export("korecek".toCharArray())
        assertTrue(BackupCrypto.isEncrypted(json))

        db.clearAllTables()
        val failure = runCatching {
            repository.import(json, "spatne".toCharArray(), BackupRepository.Mode.REPLACE)
        }.exceptionOrNull()
        assertEquals(MessageKey.BACKUP_WRONG_PASSWORD, (failure as BackupException).key)
        assertEquals(0L, count("medications"))

        val report = repository.import(json, "korecek".toCharArray(), BackupRepository.Mode.REPLACE)
        assertTrue(report.inserted > 0)
        assertEquals(1L, count("medications"))
    }

    @Test
    fun `an encrypted file without a password is refused`() = runTest {
        seed()
        val json = repository.export("korecek".toCharArray())
        val failure = runCatching {
            repository.import(json, null, BackupRepository.Mode.REPLACE)
        }.exceptionOrNull()
        assertEquals(MessageKey.BACKUP_PASSWORD_REQUIRED, (failure as BackupException).key)
        assertEquals(1L, count("medications"))
    }

    @Test
    fun `a foreign file is refused before anything is deleted`() = runTest {
        seed()
        val failure = runCatching {
            repository.import(
                """{"format":"something.else","version":1}""",
                null,
                BackupRepository.Mode.REPLACE,
            )
        }.exceptionOrNull()
        assertEquals(MessageKey.BACKUP_UNREADABLE, (failure as BackupException).key)
        assertEquals(1L, count("medications"))
    }

    @Test
    fun `a file from a newer build is refused`() = runTest {
        val failure = runCatching {
            repository.import(
                """{"format":"cz.medsy.backup","version":99,"tables":{}}""",
                null,
                BackupRepository.Mode.REPLACE,
            )
        }.exceptionOrNull()
        assertEquals(MessageKey.BACKUP_NEWER_VERSION, (failure as BackupException).key)
    }

    @Test
    fun `the catalogue is not part of the backup`() = runTest {
        seed()
        val json = repository.export()
        assertTrue(json.contains("\"medications\""))
        assertTrue(!json.contains("sukl_medications"))
    }
}
