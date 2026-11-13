package cz.medsy.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cz.medsy.data.local.MedsyDatabase
import cz.medsy.data.local.entity.InventoryBatchEntity
import cz.medsy.data.local.entity.MedicationEntity
import cz.medsy.data.local.entity.ScheduleEntity
import cz.medsy.domain.model.DoseStatus
import cz.medsy.domain.schedule.Occurrence
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The pump count must reach the inventory deduction. A two-pump dose from a 60-pump
 * bottle has to leave 58 pumps, otherwise the bottle appears to last twice as long and
 * the low-stock warning never fires.
 */
@RunWith(RobolectricTestRunner::class)
class MeteredDoseDeductionTest {

    private lateinit var db: MedsyDatabase
    private lateinit var repository: DoseRepository
    private var medicationId = 0L
    private var scheduleId = 0L

    @Before
    fun setUp() {
        kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(context, MedsyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = DoseRepository(
            db.doseLogDao(),
            db.medicationDao(),
            db.scheduleDao(),
            db.inventoryDao(),
            db.consumableDao(),
        )
        medicationId = db.medicationDao().insert(
            MedicationEntity(name = "Estrogel", form = "GEL", strengthUnit = "dávka"),
        )
        scheduleId = db.scheduleDao().insert(
            ScheduleEntity(
                medicationId = medicationId,
                scheduleType = "FIXED_DAILY",
                timesOfDayJson = """["08:00"]""",
                doseAmount = 1.5,
                doseUnit = "dávka",
                actuationsPerDose = 2,
                startDateEpochDay = LocalDate.of(2026, 1, 1).toEpochDay(),
            ),
        )
        db.inventoryDao().insert(
            InventoryBatchEntity(
                medicationId = medicationId,
                initialQuantity = 60.0,
                remainingQuantity = 60.0,
                unit = "dávka",
            ),
        )
        }
    }

    @After
    fun tearDown() = db.close()

    private fun occurrence(at: Instant): Occurrence {
        val zoned = at.atZone(java.time.ZoneId.systemDefault())
        return Occurrence(
            scheduleId = scheduleId,
            medicationId = medicationId,
            dueAt = at,
            localDate = zoned.toLocalDate(),
            localTime = LocalTime.of(8, 0),
            doseAmount = BigDecimal("1.5"),
            doseUnit = "dávka",
        )
    }

    private val at: Instant =
        LocalDate.of(2026, 11, 11).atTime(8, 0).atZone(java.time.ZoneId.systemDefault()).toInstant()

    private suspend fun remaining(): Double =
        db.inventoryDao().dispensibleBatches(medicationId).firstOrNull()?.remainingQuantity ?: 0.0

    @Test
    fun `a two pump dose removes two pumps`() = runTest {
        repository.log(occurrence(at), DoseStatus.TAKEN, actualTime = at)

        assertEquals(58.0, remaining(), 0.0001)
    }

    @Test
    fun `several doses accumulate`() = runTest {
        repository.log(occurrence(at), DoseStatus.TAKEN, actualTime = at)
        repository.log(occurrence(at.plusSeconds(86_400)), DoseStatus.TAKEN, actualTime = at.plusSeconds(86_400))

        assertEquals(56.0, remaining(), 0.0001)
    }

    @Test
    fun `one pump per dose removes one`() = runTest {
        db.scheduleDao().update(db.scheduleDao().getById(scheduleId)!!.copy(actuationsPerDose = 1))

        repository.log(occurrence(at), DoseStatus.TAKEN, actualTime = at)

        assertEquals(59.0, remaining(), 0.0001)
    }

    @Test
    fun `an unset pump count removes one`() = runTest {
        db.scheduleDao().update(db.scheduleDao().getById(scheduleId)!!.copy(actuationsPerDose = null))

        repository.log(occurrence(at), DoseStatus.TAKEN, actualTime = at)

        assertEquals(59.0, remaining(), 0.0001)
    }

    @Test
    fun `skipping a dose removes nothing`() = runTest {
        repository.log(occurrence(at), DoseStatus.SKIPPED)

        assertEquals(60.0, remaining(), 0.0001)
    }

    @Test
    fun `the bottle can empty without going negative`() = runTest {
        db.inventoryDao().setRemaining(
            db.inventoryDao().dispensibleBatches(medicationId).first().id,
            1.0,
            null,
        )

        repository.log(occurrence(at), DoseStatus.TAKEN, actualTime = at)

        assertEquals(0.0, remaining(), 0.0001)
    }
}
