package cz.medsy.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cz.medsy.data.local.MedsyDatabase
import cz.medsy.data.local.entity.MedicationEntity
import cz.medsy.domain.scanner.PackageToInventory
import cz.medsy.domain.model.MedicationForm
import cz.medsy.domain.model.ScannedPackage
import cz.medsy.domain.model.Symbology
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * A box nobody could identify is still a box with a lot number and an expiry date. This pins the
 * stock side of the “manually completed package” path: the values the scanner read have to survive
 * a trip through [InventoryRepository] and come back out of the database, because they — not the
 * name the user invents — are what drives expiry and beyond-use alerts.
 */
@RunWith(RobolectricTestRunner::class)
class InventoryRepositoryBatchTest {

    private lateinit var db: MedsyDatabase
    private lateinit var repository: InventoryRepository

    /** `inventory_batches.medicationId` is a real foreign key, so the medicine must exist first. */
    private var medicationId: Long = 0

    private val expiry = LocalDate.of(2027, 3, 31)

    /** What `ScannerViewModel` holds after a DataMatrix that matched no catalogue at all. */
    private fun partialUnknown(packageQuantity: String? = null) = ScannedPackage(
        gtin = "08595000000124",
        expirationDate = expiry,
        batchNumber = "LOT42",
        serialNumber = "SN0001",
        packageQuantity = packageQuantity,
        raw = "01085950000000124\u001D17270331\u001D10LOT42\u001D21SN0001",
        symbology = Symbology.GS1_DATAMATRIX,
    )

    @Before
    fun setUp() {
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
        medicationId = runBlocking {
            db.medicationDao().insert(
                MedicationEntity(
                    name = "Estradiol besins injekce",
                    gtin = "08595000000124",
                    form = MedicationForm.INJECTION_IM.name,
                ),
            )
        }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a manually completed package keeps its lot number and expiry in stock`() = runBlocking {
        val batch = requireNotNull(
            PackageToInventory.batchFrom(
                scanned = partialUnknown(),
                medicationId = medicationId,
                quantity = null,
                strength = "",
                form = MedicationForm.TABLET,
            ),
        )

        val id = repository.saveBatch(batch)
        val stored = requireNotNull(db.inventoryDao().getById(id))

        assertEquals("LOT42", stored.batchNumber)
        assertEquals(expiry.toEpochDay(), stored.expirationDateEpochDay)
        assertEquals(medicationId, stored.medicationId)
        assertEquals(1.0, stored.remainingQuantity, 0.0)
        assertEquals("tbl", stored.unit)
    }

    @Test
    fun `the quantity typed by the user wins over the printed one`() = runBlocking {
        val batch = requireNotNull(
            PackageToInventory.batchFrom(
                scanned = partialUnknown(packageQuantity = "1"),
                medicationId = medicationId,
                quantity = "3",
                strength = "",
                form = MedicationForm.TABLET,
            ),
        )

        val id = repository.saveBatch(batch)
        val stored = requireNotNull(db.inventoryDao().getById(id))

        assertEquals(3.0, stored.initialQuantity, 0.0)
        assertEquals(3.0, stored.remainingQuantity, 0.0)
    }

    @Test
    fun `a symbol without lot or expiry does not create an empty stock row`() = runBlocking {
        // An EAN-13 gives a GTIN and nothing else — recording "1 tbl, unknown expiry" would put a
        // phantom box on the shelf and hide the fact that nothing was actually learnt.
        val nothing = PackageToInventory.batchFrom(
            scanned = partialUnknown().copy(batchNumber = null, expirationDate = null),
            medicationId = medicationId,
            quantity = null,
            strength = "",
            form = MedicationForm.TABLET,
        )

        assertNull(nothing)
        assertTrue(db.inventoryDao().observeAll().first().isEmpty())
    }

    @Test
    fun `the expiry that was scanned decides which box is used first`() = runBlocking {
        val later = requireNotNull(
            PackageToInventory.batchFrom(partialUnknown(), medicationId, null, "", MedicationForm.TABLET),
        )
        val sooner = later.copy(
            batchNumber = "LOT01",
            expirationDate = LocalDate.of(2027, 1, 15),
        )
        repository.saveBatch(later)
        repository.saveBatch(sooner)

        val order = db.inventoryDao().dispensibleBatches(medicationId).mapNotNull { it.batchNumber }

        assertEquals(listOf("LOT01", "LOT42"), order)
    }
}
