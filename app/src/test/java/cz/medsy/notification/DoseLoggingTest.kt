package cz.medsy.notification

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cz.medsy.data.local.MedsyDatabase
import cz.medsy.data.repository.DoseRepository
import cz.medsy.data.repository.MedicationRepository
import cz.medsy.domain.model.ApplicationSite
import cz.medsy.domain.model.DoseStatus
import cz.medsy.domain.model.MedicationForm
import cz.medsy.domain.schedule.Occurrence
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Regression guard for logging a dose from a notification action: the injectable
 * volume must still be derived, otherwise the vial is deducted by the *dose in mg*
 * (5 instead of 0.10 mL) — a 50x inventory error.
 */
@RunWith(RobolectricTestRunner::class)
class DoseLoggingTest {

    private lateinit var db: MedsyDatabase
    private lateinit var doseRepository: DoseRepository
    private lateinit var medicationRepository: MedicationRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MedsyDatabase::class.java,
        ).allowMainThreadQueries().build()
        doseRepository = DoseRepository(
            db.doseLogDao(),
            db.medicationDao(),
            db.scheduleDao(),
            db.inventoryDao(),
            db.consumableDao(),
        )
        medicationRepository = MedicationRepository(db.medicationDao(), db.scheduleDao(), db.milestoneDao())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedInjectable(withVial: Boolean = true): Pair<Long, Long> {
        val medicationId = db.medicationDao().insert(
            cz.medsy.data.local.entity.MedicationEntity(
                name = "Estradiol Enanthate",
                form = MedicationForm.INJECTION_IM.name,
                strengthValue = 5.0,
                strengthUnit = "mg",
                concentrationMgPerMl = 50.0,
                isHrt = true,
            ),
        )
        if (withVial) {
            db.inventoryDao().insert(
                cz.medsy.data.local.entity.InventoryBatchEntity(
                    medicationId = medicationId,
                    initialQuantity = 2.0,
                    remainingQuantity = 2.0,
                    unit = "mL",
                    deadSpaceMlPerDose = 0.05,
                ),
            )
        }
        val scheduleId = db.scheduleDao().insert(
            cz.medsy.data.local.entity.ScheduleEntity(
                medicationId = medicationId,
                scheduleType = "INTERVAL_DAYS",
                intervalValue = 3.5,
                timesOfDayJson = """["08:00"]""",
                doseAmount = 5.0,
                doseUnit = "mg",
                requireSiteRotation = true,
                startDateEpochDay = LocalDate.of(2026, 1, 1).toEpochDay(),
            ),
        )
        return medicationId to scheduleId
    }

    private fun occurrence(medicationId: Long, scheduleId: Long) = Occurrence(
        scheduleId = scheduleId,
        medicationId = medicationId,
        dueAt = Instant.parse("2026-11-11T08:00:00Z"),
        localDate = LocalDate.of(2026, 11, 11),
        localTime = LocalTime.of(8, 0),
        doseAmount = BigDecimal("5"),
        doseUnit = "mg",
    )

    @Test
    fun `injectable logged without an explicit volume still deducts the drawn volume`() = runTest {
        val (medicationId, scheduleId) = seedInjectable()

        doseRepository.log(occurrence(medicationId, scheduleId), DoseStatus.TAKEN)

        val batch = db.inventoryDao().dispensibleBatches(medicationId).single()
        // 5 mg at 50 mg/mL = 0.10 mL + 0.05 mL dead space = 0.15 mL
        assertEquals(1.85, batch.remainingQuantity, 0.0001)
    }

    @Test
    fun `explicit volume wins over the derived one`() = runTest {
        val (medicationId, scheduleId) = seedInjectable()

        doseRepository.log(
            occurrence(medicationId, scheduleId),
            DoseStatus.TAKEN,
            volumeMl = BigDecimal("0.20"),
        )

        val batch = db.inventoryDao().dispensibleBatches(medicationId).single()
        assertEquals(0.20 + 0.05, 2.0 - batch.remainingQuantity, 0.0001)
    }

    @Test
    fun `log records the volume that was actually deducted`() = runTest {
        val (medicationId, scheduleId) = seedInjectable()

        val id = doseRepository.log(occurrence(medicationId, scheduleId), DoseStatus.TAKEN)

        val stored = db.doseLogDao().getById(id)
        assertNotNull(stored)
        assertEquals(0, stored!!.actualVolumeMl!!.compareTo(0.10))
    }

    @Test
    fun `tablets deduct the dose amount not a volume`() = runTest {
        val medicationId = db.medicationDao().insert(
            cz.medsy.data.local.entity.MedicationEntity(
                name = "Progesterone",
                form = MedicationForm.TABLET.name,
                strengthUnit = "tbl",
            ),
        )
        val scheduleId = db.scheduleDao().insert(
            cz.medsy.data.local.entity.ScheduleEntity(
                medicationId = medicationId,
                scheduleType = "FIXED_DAILY",
                timesOfDayJson = """["22:00"]""",
                doseAmount = 2.0,
                doseUnit = "tbl",
                startDateEpochDay = LocalDate.of(2026, 1, 1).toEpochDay(),
            ),
        )
        db.inventoryDao().insert(
            cz.medsy.data.local.entity.InventoryBatchEntity(
                medicationId = medicationId,
                initialQuantity = 30.0,
                remainingQuantity = 30.0,
                unit = "tbl",
            ),
        )

        doseRepository.log(occurrence(medicationId, scheduleId).copy(doseAmount = BigDecimal("2")), DoseStatus.TAKEN)

        val batch = db.inventoryDao().dispensibleBatches(medicationId).single()
        assertEquals(28.0, batch.remainingQuantity, 0.0001)
    }

    @Test
    fun `skipping a dose deducts nothing`() = runTest {
        val (medicationId, scheduleId) = seedInjectable()

        doseRepository.log(occurrence(medicationId, scheduleId), DoseStatus.SKIPPED)

        val batch = db.inventoryDao().dispensibleBatches(medicationId).single()
        assertEquals(2.0, batch.remainingQuantity, 0.0001)
    }

    @Test
    fun `site is persisted for rotation`() = runTest {
        val (medicationId, scheduleId) = seedInjectable()

        doseRepository.log(
            occurrence(medicationId, scheduleId),
            DoseStatus.TAKEN,
            site = ApplicationSite.LEFT_VENTROGLUTEAL,
        )

        val history = doseRepository.recentSiteHistory(medicationId)
        assertEquals(ApplicationSite.LEFT_VENTROGLUTEAL, history.single().first)
    }
}
