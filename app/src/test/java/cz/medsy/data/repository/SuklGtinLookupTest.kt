package cz.medsy.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cz.medsy.data.local.MedsyDatabase
import cz.medsy.data.local.entity.SuklMedicationEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * GTIN (GS1, assigned by the manufacturer) and the SÚKL code (Czech registry number)
 * are unrelated identifiers. No arithmetic turns one into the other, so a lookup may
 * only succeed when the SÚKL dataset itself carries the GTIN.
 */
@RunWith(RobolectricTestRunner::class)
class SuklGtinLookupTest {

    private lateinit var db: MedsyDatabase
    private lateinit var repository: SuklRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MedsyDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = SuklRepository(db.suklDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `finds a medication by the gtin stored in the dataset`() = runTest {
        db.suklDao().upsertAll(
            listOf(
                SuklMedicationEntity(
                    suklCode = "0213070",
                    name = "Estrofem 2mg",
                    gtin = "08595000000126",
                ),
            ),
        )

        val found = repository.findByGtin("08595000000126")

        assertNotNull("GTIN present in the dataset must be matched", found)
        assertEquals("0213070", found!!.suklCode)
    }

    @Test
    fun `gtin with a different packaging indicator still matches`() = runTest {
        db.suklDao().upsertAll(
            listOf(SuklMedicationEntity(suklCode = "0213070", name = "Estrofem", gtin = "08595000000126")),
        )

        // 1 = multipack indicator; same 13-digit item reference and check digit.
        assertNotNull(repository.findByGtin("18595000000126"))
    }

    @Test
    fun `does not fabricate a match from a gtin alone`() = runTest {
        db.suklDao().upsertAll(
            listOf(SuklMedicationEntity(suklCode = "0213070", name = "Estrofem", gtin = null)),
        )

        assertNull(
            "A GTIN must never be converted to a SÚKL code by arithmetic",
            repository.findByGtin("08595000000126"),
        )
    }

    @Test
    fun `lookup by sukl code still works`() = runTest {
        db.suklDao().upsertAll(listOf(SuklMedicationEntity(suklCode = "0213070", name = "Estrofem")))
        assertNotNull(repository.findByCode("213070"))
    }
}
