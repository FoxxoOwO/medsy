package cz.medsy.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cz.medsy.data.local.MedsyDatabase
import cz.medsy.data.local.entity.MedicationEntity
import cz.medsy.domain.inventory.BatchDraft
import cz.medsy.domain.model.MedicationForm
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The acceptance case from the plan: a batch typed in by hand must show up on the shelf and
 * must be the batch that gets deducted when a dose is logged. A row that exists in the table
 * but not in `dispensibleBatches` would be invisible stock — the exact bug this closes.
 */
@RunWith(RobolectricTestRunner::class)
class BatchInsertTest {

    private lateinit var db: MedsyDatabase
    private lateinit var repository: InventoryRepository
    private var medicationId = 0L
    private val today: LocalDate = LocalDate.of(2026, 5, 1)

    @Before
    fun setUp() = runBlocking {
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
        medicationId = db.medicationDao().insert(
            MedicationEntity(name = "Testosterone", form = "INJECTION_SUBQ", strengthUnit = "ml"),
        )
        Unit
    }

    @After
    fun tearDown() = db.close()

    private fun draft(quantity: String = "10", expiry: LocalDate? = null) = BatchDraft(
        medicationId = medicationId,
        quantity = BigDecimal(quantity),
        unit = "ml",
        expirationDate = expiry,
        batchNumber = "A-1",
    )

    @Test
    fun `a hand-entered batch appears in stock and is dispensable`() = runTest {
        val validation = repository.addBatch(draft(), MedicationForm.INJECTION_SUBQ, today)

        assertTrue(validation.isValid)
        val line = repository.stockLines(today).single()
        assertEquals(0, line.remaining.compareTo(BigDecimal("10")))
        assertEquals("ml", line.unit)
        assertEquals(1, db.inventoryDao().dispensibleBatches(medicationId).size)
        assertEquals("A-1", db.inventoryDao().dispensibleBatches(medicationId).single().batchNumber)
    }

    @Test
    fun `an invalid draft is never written`() = runTest {
        val validation = repository.addBatch(draft(quantity = "0"), MedicationForm.INJECTION_SUBQ, today)

        assertFalse(validation.isValid)
        assertTrue(db.inventoryDao().observeAll().first().isEmpty())
        assertTrue(db.inventoryDao().dispensibleBatches(medicationId).isEmpty())
    }

    @Test
    fun `a stored batch is the one deducted by a dose`() = runTest {
        repository.addBatch(draft(quantity = "10"), MedicationForm.INJECTION_SUBQ, today)

        db.inventoryDao().deduct(medicationId, 2.0, today.toEpochDay())

        val line = repository.stockLines(today).single()
        assertEquals(0, line.remaining.compareTo(BigDecimal("8")))
        assertEquals(
            today.toEpochDay(),
            db.inventoryDao().dispensibleBatches(medicationId).single().firstPuncturedDateEpochDay,
        )
    }

    @Test
    fun `an expired batch is stored but reported as unusable`() = runTest {
        repository.addBatch(draft(expiry = today.minusDays(1)), MedicationForm.INJECTION_SUBQ, today)

        val line = repository.stockLines(today).single()
        assertEquals(0, line.remaining.compareTo(BigDecimal("10")))
        assertEquals(0, line.usableRemaining.compareTo(BigDecimal.ZERO))
        assertEquals(1, line.expiredBatches.size)
    }
}
