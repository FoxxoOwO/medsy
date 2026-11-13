package cz.medsy.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cz.medsy.data.local.MedsyDatabase
import cz.medsy.domain.calc.InventoryCalculator
import cz.medsy.data.local.entity.ConsumableEntity
import cz.medsy.data.local.entity.MedicationEntity
import cz.medsy.data.local.entity.ScheduleEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * The travel plan reads real rows, so the mapping between free-text consumable names and
 * supply kinds has to be exercised against the database: two rows named "29G syringe" and
 * "1 ml syringe" are one supply, not two.
 */
@RunWith(RobolectricTestRunner::class)
class TravelPlanRepositoryTest {

    private lateinit var db: MedsyDatabase
    private lateinit var repository: InventoryRepository

    private val tripStart: LocalDate = LocalDate.of(2026, 5, 1)

    @Before
    fun setUp() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(context, MedsyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = InventoryRepository(
            db.inventoryDao(),
            db.consumableDao(),
            db.medicationDao(),
            db.scheduleDao(),
        )
        val medicationId = db.medicationDao().insert(
            MedicationEntity(name = "Testosterone", form = "INJECTION_SUBQ", strengthUnit = "ml"),
        )
        db.scheduleDao().insert(
            ScheduleEntity(
                medicationId = medicationId,
                scheduleType = "FIXED_DAILY",
                timesOfDayJson = """["08:00"]""",
                doseAmount = 1.0,
                doseUnit = "ml",
                startDateEpochDay = tripStart.minusDays(30).toEpochDay(),
            ),
        )
        db.consumableDao().insert(
            ConsumableEntity(name = "29G insulin syringe", remainingCount = 3),
        )
        db.consumableDao().insert(
            ConsumableEntity(name = "1 ml syringe", remainingCount = 4),
        )
        db.consumableDao().insert(
            ConsumableEntity(name = "Gauze", remainingCount = 100),
        )
        Unit
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `rows of the same supply kind are summed and unmatched rows ignored`() = runTest {
        val plan = repository.travelPlan(tripStart, tripStart.plusDays(4), bufferDays = 0)

        val syringes = plan.consumables.single { it.kind == InventoryCalculator.ConsumableKind.SYRINGE }
        assertEquals(5, plan.injectionCount)
        assertEquals(5, syringes.required)
        assertEquals(7, syringes.available)
        assertEquals(0, syringes.shortfall)
        assertTrue("only known supply kinds are listed",
            plan.consumables.all { it.kind in InventoryCalculator.ConsumableKind.entries })
    }

    @Test
    fun `supplies with no stock row report the full requirement as a shortfall`() = runTest {
        val plan = repository.travelPlan(tripStart, tripStart.plusDays(4), bufferDays = 0)

        val needles = plan.consumables.single { it.kind == InventoryCalculator.ConsumableKind.INJECTION_NEEDLE }
        assertEquals(5, needles.required)
        assertEquals(0, needles.available)
        assertEquals(5, needles.shortfall)
        assertTrue(plan.hasShortfall)
    }
}
