package cz.medsy.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cz.medsy.data.local.dao.BloodTestDao
import cz.medsy.data.local.dao.DoseLogDao
import cz.medsy.data.local.dao.InventoryDao
import cz.medsy.data.local.dao.MedicationDao
import cz.medsy.data.local.dao.ScheduleDao
import cz.medsy.data.local.entity.BiomarkerValueEntity
import cz.medsy.data.local.entity.BloodTestSessionEntity
import cz.medsy.data.local.entity.DoseLogEntity
import cz.medsy.data.local.entity.InventoryBatchEntity
import cz.medsy.data.local.entity.MedicationEntity
import cz.medsy.data.local.entity.ScheduleEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import cz.medsy.domain.scanner.Gtin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Runs the real Room DAOs against SQLite (no SQLCipher here — encryption is a
 * property of the open helper, not of the schema, so query behaviour is identical).
 */
@RunWith(RobolectricTestRunner::class)
class DaoTest {

    private lateinit var db: MedsyDatabase
    private lateinit var medicationDao: MedicationDao
    private lateinit var scheduleDao: ScheduleDao
    private lateinit var doseLogDao: DoseLogDao
    private lateinit var inventoryDao: InventoryDao
    private lateinit var bloodTestDao: BloodTestDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MedsyDatabase::class.java,
        ).allowMainThreadQueries().build()
        medicationDao = db.medicationDao()
        scheduleDao = db.scheduleDao()
        doseLogDao = db.doseLogDao()
        inventoryDao = db.inventoryDao()
        bloodTestDao = db.bloodTestDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun medication(name: String = "Estradiol", gtin: String? = null) = MedicationEntity(
        name = name,
        gtin = gtin,
        form = "INJECTION_IM",
        strengthValue = 5.0,
        strengthUnit = "mg",
        concentrationMgPerMl = 50.0,
        isHrt = true,
    )

    private fun schedule(medicationId: Long, times: String = """["08:00"]""") = ScheduleEntity(
        medicationId = medicationId,
        scheduleType = "INTERVAL_DAYS",
        intervalValue = 3.5,
        timesOfDayJson = times,
        doseAmount = 5.0,
        doseUnit = "mg",
        startDateEpochDay = 20_000L,
    )

    @Test
    fun `inserting and observing a medication round trips`() = runTest {
        val id = medicationDao.insert(medication())
        val stored = medicationDao.getById(id)
        assertNotNull(stored)
        assertEquals("Estradiol", stored!!.name)
        assertEquals(1, medicationDao.observeActive().first().size)
    }

    @Test
    fun `archived medications are excluded from the active list`() = runTest {
        val first = medicationDao.insert(medication("A"))
        medicationDao.insert(medication("B"))
        medicationDao.setArchived(first, true)

        val active = medicationDao.observeActive().first()
        assertEquals(1, active.size)
        assertEquals("B", active.first().name)
        assertEquals(2, medicationDao.observeAll().first().size)
    }

    @Test
    fun `gtin lookup finds the medication added from a scan`() = runTest {
        medicationDao.insert(medication(gtin = "08595000000126"))
        val found = medicationDao.findByGtin("08595000000126")
        assertNotNull(found)
        assertNull(medicationDao.findByGtin("00000000000000"))
    }

    /** A multipack box of the same product carries a different packaging indicator. */
    @Test
    fun `any gtin lookup ignores the packaging indicator`() = runTest {
        medicationDao.insert(medication(gtin = "08595000000126"))
        val variants = Gtin.packagingVariants("18595000000126")
        assertEquals("08595000000126", medicationDao.findByAnyGtin(variants)?.gtin)
    }

    @Test
    fun `deleting a medication cascades to its schedules and logs`() = runTest {
        val medId = medicationDao.insert(medication())
        val scheduleId = scheduleDao.insert(schedule(medId))
        doseLogDao.upsert(
            DoseLogEntity(
                medicationId = medId,
                scheduleId = scheduleId,
                scheduledTimestamp = 1_000L,
                status = "TAKEN",
            ),
        )

        medicationDao.delete(medId)

        assertTrue(scheduleDao.getActive().isEmpty())
        assertTrue(doseLogDao.getFrom(0L).isEmpty())
    }

    @Test
    fun `schedule times json survives the type converter`() = runTest {
        val medId = medicationDao.insert(medication())
        val scheduleId = scheduleDao.insert(schedule(medId, times = """["08:00","20:30"]"""))
        val stored = scheduleDao.getById(scheduleId)
        assertNotNull(stored)
        assertEquals("""["08:00","20:30"]""", stored!!.timesOfDayJson)
    }

    @Test
    fun `dose log upsert replaces the same scheduled slot`() = runTest {
        val medId = medicationDao.insert(medication())
        val scheduleId = scheduleDao.insert(schedule(medId))

        val first = DoseLogEntity(
            medicationId = medId,
            scheduleId = scheduleId,
            scheduledTimestamp = 5_000L,
            status = "SNOOZED",
        )
        val id = doseLogDao.upsert(first)
        doseLogDao.upsert(first.copy(id = id, status = "TAKEN", actualTimestamp = 6_000L))

        val logs = doseLogDao.getFrom(0L)
        assertEquals(1, logs.size)
        assertEquals("TAKEN", logs.first().status)
    }

    @Test
    fun `taken and skipped counters are scoped to a window`() = runTest {
        val medId = medicationDao.insert(medication())
        val scheduleId = scheduleDao.insert(schedule(medId))
        listOf(1_000L to "TAKEN", 2_000L to "TAKEN", 3_000L to "SKIPPED", 9_000L to "TAKEN")
            .forEach { (ts, status) ->
                doseLogDao.upsert(
                    DoseLogEntity(
                        medicationId = medId,
                        scheduleId = scheduleId,
                        scheduledTimestamp = ts,
                        status = status,
                    ),
                )
            }

        assertEquals(2, doseLogDao.countTaken(0L, 5_000L))
        assertEquals(1, doseLogDao.countSkipped(0L, 5_000L))
        assertEquals(3, doseLogDao.countTaken(0L, 10_000L))
    }

    @Test
    fun `inventory deduction consumes the oldest dispensible batch first`() = runTest {
        val medId = medicationDao.insert(medication())
        val firstBatch = inventoryDao.insert(
            InventoryBatchEntity(
                medicationId = medId,
                expirationDateEpochDay = 20_000L,
                firstPuncturedDateEpochDay = 19_900L,
                initialQuantity = 1.0,
                remainingQuantity = 1.0,
                unit = "mL",
                deadSpaceMlPerDose = 0.05,
            ),
        )
        inventoryDao.insert(
            InventoryBatchEntity(
                medicationId = medId,
                expirationDateEpochDay = 20_500L,
                initialQuantity = 3.0,
                remainingQuantity = 3.0,
                unit = "mL",
            ),
        )

        val touched = inventoryDao.deduct(medId, 0.15, puncturedEpochDay = 20_100L)

        assertEquals(firstBatch, touched)
        val batches = inventoryDao.dispensibleBatches(medId)
        assertEquals(0.85, batches.first().remainingQuantity, 0.0001)
        // An already-punctured vial keeps its original puncture date (BUD must not reset).
        assertEquals(19_900L, batches.first().firstPuncturedDateEpochDay)
    }

    @Test
    fun `inventory deduction spills into the next batch when the first runs out`() = runTest {
        val medId = medicationDao.insert(medication())
        inventoryDao.insert(
            InventoryBatchEntity(
                medicationId = medId,
                expirationDateEpochDay = 20_000L,
                initialQuantity = 1.0,
                remainingQuantity = 0.1,
                unit = "mL",
            ),
        )
        inventoryDao.insert(
            InventoryBatchEntity(
                medicationId = medId,
                expirationDateEpochDay = 20_500L,
                initialQuantity = 3.0,
                remainingQuantity = 3.0,
                unit = "mL",
            ),
        )

        inventoryDao.deduct(medId, 0.3, puncturedEpochDay = null)
        val remaining = inventoryDao.dispensibleBatches(medId).sumOf { it.remainingQuantity }
        assertEquals(2.8, remaining, 0.0001)
    }

    @Test
    fun `inventory deduction with no stock returns null`() = runTest {
        val medId = medicationDao.insert(medication())
        assertNull(inventoryDao.deduct(medId, 0.2, puncturedEpochDay = null))
    }

    @Test
    fun `blood test session and values are saved together`() = runTest {
        val sessionId = bloodTestDao.saveSessionWithValues(
            session = BloodTestSessionEntity(timestamp = 1_700_000_000_000L),
            values = listOf(
                BiomarkerValueEntity(
                    sessionId = 0,
                    biomarkerCode = "E2",
                    rawValue = 610.0,
                    rawUnit = "pmol/L",
                    normalizedSiValue = 610.0,
                    normalizedSiUnit = "pmol/L",
                ),
            ),
        )

        val values = bloodTestDao.valuesForSession(sessionId)
        assertEquals(1, values.size)
        assertEquals("E2", values.first().biomarkerCode)
        assertEquals(1, bloodTestDao.observeValuesForBiomarker("E2").first().size)
    }

    @Test
    fun `deleting a blood test session cascades to its values`() = runTest {
        val sessionId = bloodTestDao.saveSessionWithValues(
            session = BloodTestSessionEntity(timestamp = 1_000L),
            values = listOf(
                BiomarkerValueEntity(
                    sessionId = 0,
                    biomarkerCode = "TEST_TOTAL",
                    rawValue = 1.2,
                    rawUnit = "nmol/L",
                    normalizedSiValue = 1.2,
                    normalizedSiUnit = "nmol/L",
                ),
            ),
        )
        bloodTestDao.deleteSession(sessionId)
        assertTrue(bloodTestDao.valuesForSession(sessionId).isEmpty())
    }

    @Test
    fun `dose history ordered newest first per medication`() = runTest {
        val medId = medicationDao.insert(medication())
        val scheduleId = scheduleDao.insert(schedule(medId))
        listOf(1_000L, 3_000L, 2_000L).forEach { ts ->
            doseLogDao.upsert(
                DoseLogEntity(
                    medicationId = medId,
                    scheduleId = scheduleId,
                    scheduledTimestamp = ts,
                    status = "TAKEN",
                    actualTimestamp = ts,
                ),
            )
        }
        val recent = doseLogDao.getFrom(0L)
        assertEquals(listOf(1_000L, 2_000L, 3_000L), recent.map { it.scheduledTimestamp })
    }
}
