package cz.medsy.ui.scanner

import cz.medsy.domain.message.plain
import cz.medsy.domain.message.plainArgs

import cz.medsy.domain.message.Message
import cz.medsy.domain.message.MessageKey

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cz.medsy.data.local.MedsyDatabase
import cz.medsy.data.local.datasource.AtcFallbackDataSource
import cz.medsy.data.local.datasource.HrtCuratedCatalogDataSource
import cz.medsy.data.local.datasource.SuklCatalogDataSource
import cz.medsy.data.repository.MedicationLookupRepositoryImpl
import cz.medsy.domain.model.ParsedBarcode
import cz.medsy.domain.model.ResolvedMedicationCandidate
import cz.medsy.domain.repository.SupplementLookup
import cz.medsy.data.repository.InventoryRepository
import cz.medsy.data.repository.MedicationRepository
import cz.medsy.data.repository.PrescriptionRepository
import cz.medsy.data.repository.SuklRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Scanning is the only way a prescription enters the app. The payload below is the shape the
 * Czech QR carries in practice (a URL with the 12-character code plus the printed items).
 */
@RunWith(RobolectricTestRunner::class)
class ScannerPrescriptionTest {

    private lateinit var db: MedsyDatabase
    private lateinit var viewModel: ScannerViewModel

    private val payload = """
        https://www.epreskripce.cz/qr?code=ABCD1234EFGH
        Estradiol 2 mg 84 tbl
        Progesteron 100 mg 30 tbl
    """.trimIndent()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(context, MedsyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        viewModel = ScannerViewModel(
            suklRepository = SuklRepository(db.suklDao()),
            medicationRepository = MedicationRepository(
                db.medicationDao(),
                db.scheduleDao(),
                db.milestoneDao(),
            ),
            inventoryRepository = InventoryRepository(
                db.inventoryDao(),
                db.consumableDao(),
                db.medicationDao(),
                db.scheduleDao(),
            ),
            prescriptionRepository = PrescriptionRepository(db.prescriptionDao()),
            lookupRepository = MedicationLookupRepositoryImpl(
                sukl = SuklCatalogDataSource(db.suklDao()),
                hrt = HrtCuratedCatalogDataSource(context),
                supplements = object : SupplementLookup {
                    override suspend fun lookup(parsed: ParsedBarcode): ResolvedMedicationCandidate? = null
                },
                atc = AtcFallbackDataSource(context),
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    /**
     * Room's suspend queries hop to its own executor, so the scanner's coroutine finishes
     * after `onBarcode` returns. Every test waits on the status message, which is only set
     * once the write has completed.
     */
    private fun awaitMessage(key: MessageKey): Message {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            val message = viewModel.state.value.statusMessage
            if (message?.key == key) return message
            Thread.sleep(20)
        }
        error("statusMessage never became $key: ${viewModel.state.value.statusMessage}")
    }

    private fun scanQr(raw: String) = viewModel.onBarcode(
        raw,
        com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE,
    )

    @Test
    fun `a scanned eRecept is stored with its items`() = runBlocking {
        scanQr(payload)
        awaitMessage(MessageKey.SCAN_RX_SAVED)

        val stored = db.prescriptionDao().allWithItems().single()
        assertEquals("ABCD1234EFGH", stored.prescription.code)
        assertTrue(stored.items.isNotEmpty())
    }

    @Test
    fun `scanning the same eRecept twice does not duplicate it`() = runBlocking {
        scanQr(payload)
        awaitMessage(MessageKey.SCAN_RX_SAVED)
        scanQr(payload)
        awaitMessage(MessageKey.SCAN_RX_DUPLICATE)

        assertEquals(1, db.prescriptionDao().count())
        assertEquals(2, db.prescriptionDao().allWithItems().single().items.size)
    }

    @Test
    fun `a first scan says how much was saved`() = runBlocking {
        scanQr(payload)

        val message = awaitMessage(MessageKey.SCAN_RX_SAVED)
        val args = message.plainArgs()
        assertEquals("ABCD1234EFGH", args.first())
        assertEquals(2, message.count)
    }

    @Test
    fun `a QR that is not an eRecept stores nothing`() = runBlocking {
        scanQr("hello world")

        Thread.sleep(200)
        assertEquals(0, db.prescriptionDao().count())
        assertTrue(viewModel.state.value.statusMessage?.key != MessageKey.SCAN_RX_SAVED)
    }

    @Test
    fun `the parsed items are offered for the manual editor`() = runBlocking {
        scanQr(payload)
        awaitMessage(MessageKey.SCAN_RX_SAVED)

        assertEquals(
            db.prescriptionDao().allWithItems().single().items.first().name,
            viewModel.state.value.manualName,
        )
    }

    @Test
    fun `a stored prescription survives a new repository instance`() = runBlocking {
        scanQr(payload)
        awaitMessage(MessageKey.SCAN_RX_SAVED)

        val reopened = PrescriptionRepository(db.prescriptionDao())
        assertEquals(1, reopened.observeAll().first().size)
    }
}
