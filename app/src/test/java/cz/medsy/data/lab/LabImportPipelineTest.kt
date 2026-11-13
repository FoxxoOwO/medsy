package cz.medsy.data.lab

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cz.medsy.data.local.MedsyDatabase
import cz.medsy.data.repository.BloodTestRepository
import cz.medsy.data.repository.DoseRepository
import cz.medsy.domain.model.LabImportSource
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

/**
 * End-to-end guard for the import path: a lab that prints its range in pg/mL while the
 * stored value is pmol/L must not leave the bounds in the wrong scale.
 */
@RunWith(RobolectricTestRunner::class)
class LabImportPipelineTest {

    private lateinit var db: MedsyDatabase
    private lateinit var repository: BloodTestRepository
    private lateinit var pipeline: LabImportPipeline

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(context, MedsyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = BloodTestRepository(
            db.bloodTestDao(),
            db.scheduleDao(),
            DoseRepository(
                db.doseLogDao(),
                db.medicationDao(),
                db.scheduleDao(),
                db.inventoryDao(),
                db.consumableDao(),
            ),
            db.medicationDao(),
        )
        pipeline = LabImportPipeline(LabPdfExtractor(context), repository)
    }

    @After
    fun tearDown() = db.close()

    private val report = """
        Estradiol                    110    pg/mL    100 - 200
    """.trimIndent()

    @Test
    fun `staging converts the value to SI`() {
        val row = pipeline.stage(report).single()

        assertEquals("E2", row.code)
        assertEquals("pmol/L", row.siUnit)
        // 110 pg/mL = 403.81 pmol/L
        assertEquals(0, row.siValue!!.setScale(0, java.math.RoundingMode.HALF_UP).compareTo(BigDecimal("404")))
    }

    @Test
    fun `saving converts the printed bounds to SI too`() = runTest {
        val sessionId = pipeline.save(
            rows = pipeline.stage(report),
            timestamp = Instant.parse("2026-11-11T07:30:00Z"),
            labName = "SYNLAB",
            fasting = true,
            isTrough = true,
            hoursSinceLastDose = 80.0,
            attachmentUri = null,
            source = LabImportSource.PDF_TEXT,
        )

        val stored = db.bloodTestDao().valuesForSession(sessionId).single()
        assertNotNull("bounds must be kept", stored.labMin)
        // 100 pg/mL = 367.1 pmol/L, not 100 pmol/L
        assertEquals(367, stored.labMin!!.toInt())
        // 200 pg/mL = 734.2 pmol/L
        assertEquals(734, stored.labMax!!.toInt())
    }

    @Test
    fun `a value already in SI keeps its bounds unchanged`() = runTest {
        val siReport = "Estradiol   610   pmol/L   367 - 734"
        val sessionId = pipeline.save(
            rows = pipeline.stage(siReport),
            timestamp = Instant.parse("2026-11-11T07:30:00Z"),
            labName = null,
            fasting = true,
            isTrough = false,
            hoursSinceLastDose = null,
            attachmentUri = null,
            source = LabImportSource.PDF_TEXT,
        )

        val stored = db.bloodTestDao().valuesForSession(sessionId).single()
        assertEquals(367, stored.labMin!!.toInt())
        assertEquals(734, stored.labMax!!.toInt())
    }

    @Test
    fun `rows with an unreadable unit are not saved`() = runTest {
        // ng/dL is a unit the parser recognises but estradiol has no factor for.
        val badReport = "Estradiol   110   ng/dL   100 - 200"
        val staged = pipeline.stage(badReport)

        assertEquals(true, staged.single().isConversionError)

        val sessionId = pipeline.save(
            rows = staged,
            timestamp = Instant.parse("2026-11-11T07:30:00Z"),
            labName = null,
            fasting = true,
            isTrough = false,
            hoursSinceLastDose = null,
            attachmentUri = null,
            source = LabImportSource.PDF_TEXT,
        )

        assertEquals(0, db.bloodTestDao().valuesForSession(sessionId).size)
    }
}
