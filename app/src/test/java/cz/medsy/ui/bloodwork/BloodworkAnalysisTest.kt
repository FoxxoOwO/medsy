package cz.medsy.ui.bloodwork

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cz.medsy.data.lab.LabImportPipeline
import cz.medsy.data.local.MedsyDatabase
import cz.medsy.data.repository.BloodTestRepository
import cz.medsy.data.repository.DoseRepository
import cz.medsy.data.repository.MilestoneRepository
import cz.medsy.data.settings.SettingsRepository
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The chosen reference profile and the trough assessment are the two things that make
 * a result meaningful. Both were previously unreachable: the profile was hardcoded and
 * the trough calculator was never called from the UI.
 */
@RunWith(RobolectricTestRunner::class)
class BloodworkAnalysisTest {

    private lateinit var db: MedsyDatabase
    private lateinit var repository: BloodTestRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var viewModel: BloodworkViewModel

    @Before
    fun setUp() {
        // viewModelScope posts to Dispatchers.Main, which Robolectric's test thread blocks.
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
        repository = BloodTestRepository(db.bloodTestDao(), db.scheduleDao(), doseRepository, db.medicationDao())
        settingsRepository = SettingsRepository(context)
    }

    private val viewModels = mutableListOf<BloodworkViewModel>()

    @After
    fun tearDown() = runBlocking {
        // The ViewModel collects settings, sessions and milestones forever. Cancelling is
        // not enough on its own: a collection can still be resuming on Dispatchers.Main
        // when the next test resets it, which throws "Dispatchers.Main is used
        // concurrently with setting it" (or deadlocks the worker). Waiting for the jobs
        // to finish closes that race.
        viewModels.forEach { viewModel ->
            viewModel.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }
        viewModels.clear()
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `saved profile setting is applied to the analysis`() = runBlocking {
        settingsRepository.setDefaultRangeProfile("HRT_MASC")
        // DataStore is process-wide and its write is asynchronous. Wait until the value
        // is actually readable before constructing the ViewModel, otherwise the
        // ViewModel's first collect can still see the previous test's profile.
        var stored = settingsRepository.settings.first().defaultRangeProfile
        repeat(100) {
            if (stored == "HRT_MASC") return@repeat
            Thread.sleep(20)
            stored = settingsRepository.settings.first().defaultRangeProfile
        }
        assertEquals("HRT_MASC", stored)
        viewModel = newViewModel()
        val profile = await {
            viewModel.state.value.profile
                .takeIf { it == cz.medsy.domain.model.ProfileType.HRT_MASC }
        }
        assertEquals(cz.medsy.domain.model.ProfileType.HRT_MASC, profile)
    }

    @Test
    fun `unknown stored profile falls back to feminizing target`() = runBlocking {
        settingsRepository.setDefaultRangeProfile("NOT_A_PROFILE")
        viewModel = newViewModel()
        await { viewModel.state.value.profile == cz.medsy.domain.model.ProfileType.HRT_FEM }
        assertEquals(cz.medsy.domain.model.ProfileType.HRT_FEM, viewModel.state.value.profile)
    }

    @Test
    fun `trough is derived without the UI asking for it`() = runBlocking {
        val medicationId = db.medicationDao().insert(
            cz.medsy.data.local.entity.MedicationEntity(
                name = "Estradiol Enanthate",
                form = "INJECTION_IM",
                concentrationMgPerMl = 50.0,
                isHrt = true,
            ),
        )
        val scheduleId = db.scheduleDao().insert(
            cz.medsy.data.local.entity.ScheduleEntity(
                medicationId = medicationId,
                scheduleType = "INTERVAL_DAYS",
                intervalValue = 3.5,
                timesOfDayJson = """["08:00"]""",
                doseAmount = 5.0,
                doseUnit = "mg",
                startDateEpochDay = 20_000L,
            ),
        )
        val draw = java.time.Instant.now()
        db.doseLogDao().upsert(
            cz.medsy.data.local.entity.DoseLogEntity(
                medicationId = medicationId,
                scheduleId = scheduleId,
                scheduledTimestamp = draw.minusSeconds(3 * 86_400L).toEpochMilli(),
                actualTimestamp = draw.minusSeconds(3 * 86_400L).toEpochMilli(),
                status = "TAKEN",
            ),
        )
        repository.saveSession(
            cz.medsy.domain.model.BloodTestSession(timestamp = draw),
            listOf(
                cz.medsy.domain.model.BiomarkerValue(
                    sessionId = 0,
                    biomarkerCode = "E2",
                    rawValue = java.math.BigDecimal("610"),
                    rawUnit = "pmol/L",
                    normalizedSiValue = java.math.BigDecimal("610"),
                    normalizedSiUnit = "pmol/L",
                ),
            ),
        )

        viewModel = newViewModel()
        val trough = await { viewModel.state.value.trough }

        assertNotNull(trough)
        assertNotNull("hours since the last dose must be computed", trough.hoursSinceLastDose)
        assertTrue(trough.hoursSinceLastDose!! > 60)
    }

    @Test
    fun `series carries the stored SI value and unit`() = runBlocking {
        repository.saveSession(
            cz.medsy.domain.model.BloodTestSession(timestamp = java.time.Instant.now()),
            listOf(
                cz.medsy.domain.model.BiomarkerValue(
                    sessionId = 0,
                    biomarkerCode = "E2",
                    rawValue = java.math.BigDecimal("450"),
                    rawUnit = "pmol/L",
                    normalizedSiValue = java.math.BigDecimal("450"),
                    normalizedSiUnit = "pmol/L",
                ),
            ),
        )
        viewModel = newViewModel()
        val series = await { viewModel.state.value.series.firstOrNull() }

        assertEquals("E2", series.code)
        assertEquals("pmol/L", series.unit)
        assertEquals(450.0, series.lastValue!!, 0.001)
    }

    @Test
    fun `delta compares against the previous draw`() = runBlocking {
        val first = java.time.Instant.now().minusSeconds(90 * 86_400L)
        val second = java.time.Instant.now()
        listOf(first to "400", second to "600").forEach { (ts, value) ->
            repository.saveSession(
                cz.medsy.domain.model.BloodTestSession(timestamp = ts),
                listOf(
                    cz.medsy.domain.model.BiomarkerValue(
                        sessionId = 0,
                        biomarkerCode = "E2",
                        rawValue = java.math.BigDecimal(value),
                        rawUnit = "pmol/L",
                        normalizedSiValue = java.math.BigDecimal(value),
                        normalizedSiUnit = "pmol/L",
                    ),
                ),
            )
        }
        viewModel = newViewModel()
        val series = await { viewModel.state.value.series.firstOrNull { it.points.size >= 2 } }

        assertEquals(50.0, series.delta!!, 0.01)
    }

    @Test
    fun `a result inside the target band is not flagged`() = runBlocking {
        repository.saveSession(
            cz.medsy.domain.model.BloodTestSession(timestamp = java.time.Instant.now()),
            listOf(
                cz.medsy.domain.model.BiomarkerValue(
                    sessionId = 0,
                    biomarkerCode = "E2",
                    rawValue = java.math.BigDecimal("500"),
                    rawUnit = "pmol/L",
                    normalizedSiValue = java.math.BigDecimal("500"),
                    normalizedSiUnit = "pmol/L",
                ),
            ),
        )
        viewModel = newViewModel()
        val series = await { viewModel.state.value.series.firstOrNull() }

        assertEquals(cz.medsy.domain.calc.RangeStatus.IN_RANGE, series.status)
        assertFalse(series.status == cz.medsy.domain.calc.RangeStatus.HIGH)
    }

    private fun newViewModel(): BloodworkViewModel {
        val pipeline = LabImportPipeline(
            extractor = cz.medsy.data.lab.LabPdfExtractor(ApplicationProvider.getApplicationContext()),
            repository = repository,
        )
        return BloodworkViewModel(
            repository = repository,
            milestoneRepository = MilestoneRepository(db.milestoneDao()),
            importPipeline = pipeline,
            // These tests never photograph a report; the seam only has to be non-null.
            ocrExtractor = cz.medsy.data.lab.OcrLabExtractor(
                recognizer = object : cz.medsy.data.lab.OcrRecognizer {
                    override suspend fun recognize(uri: android.net.Uri) =
                        cz.medsy.data.lab.OcrDocument(emptyList())
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

    // --- overlapping ranges ----------------------------------------------------

    @Test
    fun `toggling an overlay adds it to the state`() = runBlocking {
        viewModel = newViewModel()
        await { viewModel.state.value.profile }

        viewModel.toggleOverlay(cz.medsy.domain.model.ProfileType.CIS_F)

        assertTrue(viewModel.state.value.overlayProfiles.contains(cz.medsy.domain.model.ProfileType.CIS_F))
    }

    @Test
    fun `toggling twice removes the overlay`() = runBlocking {
        viewModel = newViewModel()
        await { viewModel.state.value.profile }

        viewModel.toggleOverlay(cz.medsy.domain.model.ProfileType.CIS_F)
        viewModel.toggleOverlay(cz.medsy.domain.model.ProfileType.CIS_F)

        assertTrue(viewModel.state.value.overlayProfiles.isEmpty())
    }

    @Test
    fun `the primary profile cannot be removed by toggling`() = runBlocking {
        viewModel = newViewModel()
        val primary = await { viewModel.state.value.profile }

        viewModel.toggleOverlay(primary)

        assertTrue(viewModel.state.value.overlayProfiles.isEmpty())
        assertEquals(primary, viewModel.state.value.profile)
    }

    @Test
    fun `the chart gets one band per selected profile`() = runBlocking {
        repository.saveSession(
            cz.medsy.domain.model.BloodTestSession(timestamp = java.time.Instant.now()),
            listOf(
                cz.medsy.domain.model.BiomarkerValue(
                    sessionId = 0,
                    biomarkerCode = "E2",
                    rawValue = java.math.BigDecimal("500"),
                    rawUnit = "pmol/L",
                    normalizedSiValue = java.math.BigDecimal("500"),
                    normalizedSiUnit = "pmol/L",
                ),
            ),
        )
        viewModel = newViewModel()
        await { viewModel.state.value.series.firstOrNull() }

        viewModel.toggleOverlay(cz.medsy.domain.model.ProfileType.CIS_F)
        val series = await { viewModel.state.value.series.firstOrNull()?.takeIf { it.bands.size >= 2 } }

        assertEquals(2, series.bands.size)
        assertTrue(series.bands.first().isPrimary)
    }

    @Test
    fun `the primary band stays first after an overlay is added`() = runBlocking {
        repository.saveSession(
            cz.medsy.domain.model.BloodTestSession(timestamp = java.time.Instant.now()),
            listOf(
                cz.medsy.domain.model.BiomarkerValue(
                    sessionId = 0,
                    biomarkerCode = "E2",
                    rawValue = java.math.BigDecimal("500"),
                    rawUnit = "pmol/L",
                    normalizedSiValue = java.math.BigDecimal("500"),
                    normalizedSiUnit = "pmol/L",
                ),
            ),
        )
        viewModel = newViewModel()
        await { viewModel.state.value.series.firstOrNull() }

        viewModel.toggleOverlay(cz.medsy.domain.model.ProfileType.CIS_F)
        val series = await { viewModel.state.value.series.firstOrNull()?.takeIf { it.bands.size >= 2 } }

        assertTrue(series.bands.first().isPrimary)
        assertEquals(viewModel.state.value.profile, series.bands.first().profileType)
    }
}
