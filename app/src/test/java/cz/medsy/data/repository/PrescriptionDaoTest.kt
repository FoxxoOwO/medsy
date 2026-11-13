package cz.medsy.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cz.medsy.data.local.MedsyDatabase
import cz.medsy.data.local.entity.PrescriptionEntity
import cz.medsy.data.local.entity.PrescriptionItemEntity
import cz.medsy.domain.model.Prescription
import cz.medsy.domain.model.PrescriptionItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class PrescriptionDaoTest {

    private lateinit var db: MedsyDatabase
    private lateinit var repository: PrescriptionRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(context, MedsyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = PrescriptionRepository(db.prescriptionDao())
        Unit
    }

    @After
    fun tearDown() = db.close()

    private fun prescription(code: String = "ABCD1234EFGH") = Prescription(
        code = code,
        issuedAt = Instant.parse("2026-05-20T08:00:00Z"),
        validUntil = Instant.parse("2026-06-03T21:59:59Z"),
        medications = listOf(
            PrescriptionItem(suklCode = "0012345", name = "Estradiol 2 mg", quantity = 84, dosageInstruction = "1-0-1"),
            PrescriptionItem(suklCode = null, name = "Progesteron 100 mg", quantity = 30, dosageInstruction = null),
        ),
    )

    @Test
    fun `insert stores the header and both items`() = runTest {
        assertEquals(PrescriptionRepository.SaveResult.SAVED, repository.save(prescription()))

        assertEquals(1, db.prescriptionDao().count())
        val stored = db.prescriptionDao().allWithItems().single()
        assertEquals("ABCD1234EFGH", stored.prescription.code)
        assertEquals(2, stored.items.size)
        assertEquals("Estradiol 2 mg", stored.items.first().name)
        assertEquals(84, stored.items.first().quantity)
        assertEquals("1-0-1", stored.items.first().dosageInstruction)
    }

    @Test
    fun `the same code twice is reported, not duplicated`() = runTest {
        assertEquals(PrescriptionRepository.SaveResult.SAVED, repository.save(prescription()))
        assertEquals(
            PrescriptionRepository.SaveResult.ALREADY_SAVED,
            repository.save(prescription()),
        )

        assertEquals(1, db.prescriptionDao().count())
        assertEquals(2, db.prescriptionDao().allWithItems().single().items.size)
    }

    @Test
    fun `items are deleted with their prescription`() = runTest {
        repository.save(prescription())
        val id = db.prescriptionDao().allWithItems().single().prescription.id

        db.prescriptionDao().delete(id)

        assertEquals(0, db.prescriptionDao().count())
        assertNull(db.prescriptionDao().getByCode("ABCD1234EFGH"))
    }

    @Test
    fun `marking dispensed survives a reload`() = runTest {
        repository.save(prescription())
        val id = db.prescriptionDao().allWithItems().single().prescription.id

        repository.markDispensed(id)

        assertTrue(db.prescriptionDao().getByCode("ABCD1234EFGH")!!.dispensed)
        assertTrue(repository.active().isEmpty())
    }

    @Test
    fun `observeAll emits the stored prescription with its items`() = runTest {
        repository.save(prescription())

        val emitted = repository.observeAll().first()

        assertEquals(1, emitted.size)
        assertEquals(2, emitted.single().items.size)
        assertFalse(emitted.single().dispensed)
    }

    @Test
    fun `a prescription without items is still stored`() = runTest {
        repository.save(prescription().copy(medications = emptyList()))

        val stored = db.prescriptionDao().allWithItems().single()
        assertNotNull(stored.prescription)
        assertTrue(stored.items.isEmpty())
    }

    @Test
    fun `the raw dao refuses a duplicate code`() = runTest {
        val header = PrescriptionEntity(code = "ZZZZ1111YYYY", validUntil = null)

        assertNotNull(db.prescriptionDao().insertPrescription(header, emptyList()))
        assertNull(db.prescriptionDao().insertPrescription(header, emptyList()))
        assertEquals(1, db.prescriptionDao().count())
    }

    @Test
    fun `items carry the correct parent id`() = runTest {
        repository.save(prescription())
        val header = db.prescriptionDao().getByCode("ABCD1234EFGH")!!

        val items = db.prescriptionDao().allWithItems().single().items
        assertTrue(items.all { it.prescriptionId == header.id })
        assertEquals(
            listOf("0012345", null),
            items.map { it.suklCode },
        )
    }
}
