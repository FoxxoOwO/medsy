package cz.medsy.data.lab

import android.app.Application
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cz.medsy.data.local.MedsyDatabase
import cz.medsy.data.repository.BloodTestRepository
import cz.medsy.data.repository.DoseRepository
import cz.medsy.domain.model.LabImportSource
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

/**
 * Photo → staging rows. The recogniser is faked because ML Kit needs a device model;
 * everything downstream (line joining, parsing, unit conversion) is the real code.
 */
@RunWith(RobolectricTestRunner::class)
class OcrLabExtractorTest {

    private lateinit var db: MedsyDatabase
    private lateinit var extractor: OcrLabExtractor
    private lateinit var greeting: FakeRecognizer
    private val uri: Uri = Uri.parse("file:///tmp/lab-report.jpg")

    private class FakeRecognizer(
        var result: Result<OcrDocument> = Result.success(OcrDocument(emptyList())),
    ) : OcrRecognizer {
        var calls = 0
        override suspend fun recognize(uri: Uri): OcrDocument {
            calls++
            return result.getOrThrow()
        }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, MedsyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val doseRepository = DoseRepository(
            db.doseLogDao(),
            db.medicationDao(),
            db.scheduleDao(),
            db.inventoryDao(),
            db.consumableDao(),
        )
        val bloodwork = BloodTestRepository(
            db.bloodTestDao(),
            db.scheduleDao(),
            doseRepository,
            db.medicationDao(),
        )
        greeting = FakeRecognizer()
        extractor = OcrLabExtractor(
            recognizer = greeting,
            pipeline = LabImportPipeline(LabPdfExtractor(context), bloodwork),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun withText(vararg blocks: List<String>) {
        greeting.result = Result.success(OcrDocument(blocks.toList()))
    }

    @Test
    fun `a photographed estradiol line lands in staging with its value`() = runTest {
        withText(listOf("Laboratorní vyšetření", "Estradiol 610 pmol/L", "Prolaktin 8,4 ug/l"))

        val staging = extractor.import(uri)

        val e2 = staging.rows.first { it.code == "E2" }
        // BigDecimal equality compares scale too; the converter returns its own scale.
        assertEquals(0, e2.rawValue.compareTo(BigDecimal("610")))
        assertEquals("pmol/l", e2.rawUnit.lowercase())
        assertEquals(0, e2.siValue!!.compareTo(BigDecimal("610")))
        assertEquals("pmol/l", e2.siUnit.lowercase())
        assertTrue(e2.isValid)
        assertEquals(LabImportSource.OCR_IMAGE, staging.source)
        assertFalse(staging.requiresOcr)
        assertEquals(1, greeting.calls)
    }

    @Test
    fun `an unreadable photo produces an empty staging result instead of a crash`() = runTest {
        withText(listOf("   "), emptyList())

        val staging = extractor.import(uri)

        assertEquals(emptyList<LabImportPipeline.StagingRow>(), staging.rows)
        assertFalse(staging.requiresOcr)
        assertEquals(LabImportSource.OCR_IMAGE, staging.source)
    }

    @Test
    fun `a recogniser failure produces an empty staging result instead of a crash`() = runTest {
        greeting.result = Result.failure(IllegalStateException("no model on this device"))

        val staging = extractor.import(uri)

        assertEquals(emptyList<LabImportPipeline.StagingRow>(), staging.rows)
        assertEquals("", staging.rawText)
        assertEquals(LabImportSource.OCR_IMAGE, staging.source)
    }

    @Test
    fun `raw text is kept for review so a misread value can be checked`() = runTest {
        withText(listOf("Estradiol 610 pmol/L"))

        val staging = extractor.import(uri)

        assertEquals("Estradiol 610 pmol/L", staging.rawText)
    }
}
