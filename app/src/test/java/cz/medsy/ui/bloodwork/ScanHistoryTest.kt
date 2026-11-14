package cz.medsy.ui.bloodwork

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cz.medsy.data.lab.LabImportPipeline
import cz.medsy.data.lab.OcrDocument
import cz.medsy.data.lab.OcrLabExtractor
import cz.medsy.data.lab.OcrRecognizer
import cz.medsy.data.lab.LabPdfExtractor
import cz.medsy.data.local.MedsyDatabase
import cz.medsy.data.repository.BloodTestRepository
import cz.medsy.data.repository.DoseRepository
import cz.medsy.data.repository.MilestoneRepository
import cz.medsy.data.settings.SettingsRepository
import cz.medsy.domain.model.BiomarkerValue
import cz.medsy.domain.model.BloodTestSession
import cz.medsy.domain.model.LabImportSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * A report read from a PDF or a photo is a first draft: the lab prints a unit the parser may
 * transpose, and a scan can pick up the wrong draw date. History plus edit/delete is what
 * makes that draft correctable without re-importing, which is what these tests pin down.
 */
@RunWith(RobolectricTestRunner::class)
class ScanHistoryTest {

    private lateinit var db: MedsyDatabase
    private lateinit var repository: BloodTestRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var viewModel: BloodworkViewModel

    private val viewModels = mutableListOf<BloodworkViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
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
        repository = BloodTestRepository(
            db.bloodTestDao(),
            db.scheduleDao(),
            doseRepository,
            db.medicationDao(),
        )
        settingsRepository = SettingsRepository(context)
    }

    @After
    fun tearDown() = runBlocking {
        viewModels.forEach { model ->
            model.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        }
        viewModels.clear()
        db.close()
        Dispatchers.resetMain()
    }

    private fun newViewModel(): BloodworkViewModel {
        val pipeline = LabImportPipeline(
            extractor = LabPdfExtractor(ApplicationProvider.getApplicationContext()),
            repository = repository,
        )
        return BloodworkViewModel(
            repository = repository,
            milestoneRepository = MilestoneRepository(db.milestoneDao()),
            importPipeline = pipeline,
            ocrExtractor = OcrLabExtractor(
                recognizer = object : OcrRecognizer {
                    override suspend fun recognize(uri: android.net.Uri) = OcrDocument(emptyList())
                },
                pipeline = pipeline,
            ),
            settingsRepository = settingsRepository,
        ).also { viewModels += it }
    }

    private fun <T : Any> await(timeoutMs: Long = 3_000, condition: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            condition()?.let { return it }
            Thread.sleep(20)
        }
        error("condition not met within ${timeoutMs}ms")
    }

    /** Same wait, for conditions that have to read the database themselves. */
    private suspend fun <T : Any> awaitStored(timeoutMs: Long = 3_000, condition: suspend () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            condition()?.let { return it }
            kotlinx.coroutines.delay(20)
        }
        error("stored condition not met within ${timeoutMs}ms")
    }

    private suspend fun saveDraw(
        timestamp: Instant,
        labName: String? = null,
        source: LabImportSource = LabImportSource.PDF_TEXT,
        values: List<Pair<String, BigDecimal>>,
    ): Long = repository.saveSession(
        BloodTestSession(timestamp = timestamp, labName = labName, source = source),
        values.map { (code, raw) ->
            BiomarkerValue(
                sessionId = 0,
                biomarkerCode = code,
                rawValue = raw,
                rawUnit = "pg/mL",
                normalizedSiValue = raw.multiply(BigDecimal("3.671")),
                normalizedSiUnit = "pmol/L",
            )
        },
    )

    @Test
    fun `history lists the imports newest first with their value counts`() = runBlocking {
        val older = Instant.parse("2026-03-04T07:40:00Z")
        val newer = Instant.parse("2026-05-20T06:15:00Z")
        saveDraw(older, "Fingerland", values = listOf("E2" to BigDecimal("420")))
        saveDraw(newer, null, LabImportSource.OCR_IMAGE, values = listOf("E2" to BigDecimal("610"), "PRL" to BigDecimal("8")))

        viewModel = newViewModel()
        val history = await { viewModel.state.value.history.takeIf { it.size == 2 } }

        assertEquals(newer, history[0].timestamp)
        assertNull(history[0].labName)
        assertEquals(LabImportSource.OCR_IMAGE, history[0].source)
        assertEquals(2, history[0].valueCount)
        assertEquals("Fingerland", history[1].labName)
        assertEquals(LabImportSource.PDF_TEXT, history[1].source)
        assertEquals(1, history[1].valueCount)
    }

    @Test
    fun `an import that recognised nothing is still listed so it can be deleted`() = runBlocking {
        saveDraw(Instant.parse("2026-04-01T07:00:00Z"), values = emptyList())

        viewModel = newViewModel()
        val history = await { viewModel.state.value.history.firstOrNull() }

        assertEquals(0, history.valueCount)
        assertTrue(viewModel.state.value.series.isEmpty())
    }

    @Test
    fun `the editor shows the printed number, not the stored SI value`() = runBlocking {
        val id = saveDraw(Instant.parse("2026-04-01T07:00:00Z"), values = listOf("E2" to BigDecimal("420")))
        viewModel = newViewModel()
        await { viewModel.state.value.history.firstOrNull() }

        viewModel.editScan(id)
        val draft = await { viewModel.state.value.editing }

        assertEquals(id, draft.sessionId)
        assertEquals("420", draft.values.single().text)
        assertEquals("Estradiol", draft.values.single().displayName)
        assertEquals("pg/mL", draft.values.single().unit)
    }

    @Test
    fun `moving the draw date moves the chart point`() = runBlocking {
        val original = Instant.parse("2026-04-01T07:00:00Z")
        val id = saveDraw(original, values = listOf("E2" to BigDecimal("420")))
        viewModel = newViewModel()
        await { viewModel.state.value.history.firstOrNull() }

        val moved = Instant.parse("2026-04-09T07:00:00Z")
        viewModel.editScan(id)
        viewModel.setDraftTimestamp(moved)
        viewModel.saveScan()

        await { viewModel.state.value.editing == null }
        val session = awaitStored { repository.session(id)?.takeIf { it.timestamp == moved } }
        assertEquals(moved, session.timestamp)
        val series = await { viewModel.state.value.series.firstOrNull()?.takeIf { s -> s.points.any { it.timestamp == moved } } }
        assertEquals(moved, series.points.single().timestamp)
    }

    @Test
    fun `a corrected number is converted to SI again`() = runBlocking {
        val id = saveDraw(Instant.parse("2026-04-01T07:00:00Z"), values = listOf("E2" to BigDecimal("420")))
        viewModel = newViewModel()
        await { viewModel.state.value.history.firstOrNull() }

        viewModel.editScan(id)
        val draft = await { viewModel.state.value.editing }
        // Czech reports print a comma decimal separator.
        viewModel.setDraftValue(draft.values.single().id, "500,5")
        viewModel.saveScan()

        await { viewModel.state.value.editing == null }
        val stored = awaitStored { viewModel.state.value.values.firstOrNull()?.takeIf { it.rawValue.compareTo(BigDecimal("500.5")) == 0 } }
        assertEquals("pmol/L", stored.normalizedSiUnit)
        // 500.5 pg/mL * 3.671 = 1837.3 pmol/L
        assertEquals(1837.3, stored.normalizedSiValue.toDouble(), 0.5)
    }

    @Test
    fun `deleting one value keeps the rest of the draw`() = runBlocking {
        val id = saveDraw(
            Instant.parse("2026-04-01T07:00:00Z"),
            values = listOf("E2" to BigDecimal("420"), "PRL" to BigDecimal("8")),
        )
        viewModel = newViewModel()
        await { viewModel.state.value.history.firstOrNull() }

        viewModel.editScan(id)
        val draft = await { viewModel.state.value.editing }
        val estradiol = draft.values.first { it.code == "E2" }
        viewModel.removeDraftValue(estradiol.id)
        viewModel.saveScan()

        await { viewModel.state.value.editing == null }
        val remaining = awaitStored { viewModel.state.value.values.takeIf { it.size == 1 } }
        assertEquals("PRL", remaining.single().biomarkerCode)
        assertNotNull(repository.session(id))
    }

    @Test
    fun `a non-numeric correction is refused instead of wiping the value`() = runBlocking {
        val id = saveDraw(Instant.parse("2026-04-01T07:00:00Z"), values = listOf("E2" to BigDecimal("420")))
        viewModel = newViewModel()
        await { viewModel.state.value.history.firstOrNull() }

        viewModel.editScan(id)
        val draft = await { viewModel.state.value.editing }
        viewModel.setDraftValue(draft.values.single().id, "420 mg")

        assertTrue(viewModel.state.value.editing!!.hasInvalidValues)
        viewModel.saveScan()
        // Still open: the save is blocked rather than silently storing the old number.
        assertNotNull(viewModel.state.value.editing)
        assertEquals(BigDecimal("420.0"), viewModel.state.value.values.single().rawValue)
    }

    @Test
    fun `clearing a field is an unfinished deletion, not an error`() = runBlocking {
        val id = saveDraw(Instant.parse("2026-04-01T07:00:00Z"), values = listOf("E2" to BigDecimal("420")))
        viewModel = newViewModel()
        await { viewModel.state.value.history.firstOrNull() }

        viewModel.editScan(id)
        val draft = await { viewModel.state.value.editing }
        viewModel.setDraftValue(draft.values.single().id, "")

        assertFalse(viewModel.state.value.editing!!.hasInvalidValues)
    }

    @Test
    fun `deleting a draw takes its values and its chart point with it`() = runBlocking {
        val keep = saveDraw(Instant.parse("2026-03-01T07:00:00Z"), values = listOf("E2" to BigDecimal("300")))
        val drop = saveDraw(Instant.parse("2026-04-01T07:00:00Z"), values = listOf("PRL" to BigDecimal("9")))
        viewModel = newViewModel()
        await { viewModel.state.value.history.takeIf { it.size == 2 } }

        viewModel.askDelete(drop)
        assertEquals(drop, viewModel.state.value.pendingDelete!!.sessionId)
        viewModel.confirmDelete()

        val history = await { viewModel.state.value.history.takeIf { it.size == 1 } }
        assertEquals(keep, history.single().sessionId)
        assertTrue(viewModel.state.value.values.none { it.sessionId == drop })
        assertTrue(viewModel.state.value.series.none { it.code == "PRL" })
    }

    @Test
    fun `cancelling the deletion keeps the draw`() = runBlocking {
        val id = saveDraw(Instant.parse("2026-04-01T07:00:00Z"), values = listOf("E2" to BigDecimal("420")))
        viewModel = newViewModel()
        await { viewModel.state.value.history.firstOrNull() }

        viewModel.askDelete(id)
        viewModel.cancelDelete()

        assertNull(viewModel.state.value.pendingDelete)
        assertNotNull(repository.session(id))
    }

    @Test
    fun `picking a date keeps the local time of the draw`() {
        val zone = ZoneId.of("Europe/Prague")
        val original = LocalDate.of(2026, 4, 1).atTime(LocalTime.of(7, 40)).atZone(zone).toInstant()
        val picked = LocalDate.of(2026, 4, 9).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        val merged = mergePickedDate(original, picked, zone)

        val local = merged.atZone(zone)
        assertEquals(LocalDate.of(2026, 4, 9), local.toLocalDate())
        assertEquals(LocalTime.of(7, 40), local.toLocalTime())
    }
}
