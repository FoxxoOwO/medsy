package cz.medsy.notification

import android.content.Context
import android.app.AlarmManager
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cz.medsy.data.local.MedsyDatabase
import cz.medsy.data.local.entity.MedicationEntity
import cz.medsy.data.local.entity.ScheduleEntity
import cz.medsy.data.repository.DoseRepository
import cz.medsy.data.repository.MedicationRepository
import cz.medsy.domain.model.MedicationForm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
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
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The overlay rebuilds its own batch from the database, so the grouping is only correct if the
 * view model really sees the neighbouring schedule. A missing batch showed up on the device as
 * "the overlay does not appear at all" — the activity closes itself when nothing resolves.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DoseAlertViewModelTest {

    private lateinit var db: MedsyDatabase
    private lateinit var medicationRepository: MedicationRepository
    private lateinit var handler: DoseActionHandler

    private val zone: ZoneId = ZoneId.systemDefault()
    private val dueAt: Long = LocalDate.now().atTime(LocalTime.of(8, 0))
        .atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MedsyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        medicationRepository = MedicationRepository(db.medicationDao(), db.scheduleDao(), db.milestoneDao())
        handler = DoseActionHandler(
            DoseRepository(
                db.doseLogDao(),
                db.medicationDao(),
                db.scheduleDao(),
                db.inventoryDao(),
                db.consumableDao(),
            ),
            medicationRepository,
            DoseNotifier(context),
            DoseAlarmScheduler(context, context.getSystemService(AlarmManager::class.java)),
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun seed(name: String, time: String): Pair<Long, Long> {
        val medicationId = db.medicationDao().insert(
            MedicationEntity(name = name, form = MedicationForm.TABLET.name, strengthUnit = "mg"),
        )
        val scheduleId = db.scheduleDao().insert(
            ScheduleEntity(
                medicationId = medicationId,
                scheduleType = "FIXED_DAILY",
                timesOfDayJson = "[\"$time\"]",
                doseAmount = 1.0,
                doseUnit = "tbl",
                isActive = true,
                startDateEpochDay = LocalDate.now().toEpochDay(),
            ),
        )
        return medicationId to scheduleId
    }

    private fun viewModel(medicationId: Long, scheduleId: Long) = DoseAlertViewModel(
        SavedStateHandle(
            mapOf(
                DoseAlarmReceiver.EXTRA_MEDICATION_ID to medicationId,
                DoseAlarmReceiver.EXTRA_SCHEDULE_ID to scheduleId,
                DoseAlarmReceiver.EXTRA_DUE_AT to dueAt,
            ),
        ),
        medicationRepository,
        handler,
        ApplicationProvider.getApplicationContext(),
    )

    /** Room hops off the main dispatcher, so the state is polled rather than advanced. */
    private fun awaitUi(model: DoseAlertViewModel): cz.medsy.ui.alerts.DoseAlertUi {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            val ui = model.state.value
            if (!ui.loading) return ui
            Thread.sleep(20)
        }
        return model.state.value
    }

    @Test
    fun `two medications on the same minute share one overlay`() = runTest {
        val (firstMedication, firstSchedule) = seed("Estradiol", "08:00")
        seed("Progesterone", "08:00")

        val ui = awaitUi(viewModel(firstMedication, firstSchedule))

        assertFalse(ui.loading)
        assertFalse(ui.isEmpty)
        assertEquals(listOf("Estradiol", "Progesterone"), ui.doses.map { it.medicationName })
        assertTrue(ui.isGroup)
        assertEquals("08:00", ui.timeText)
    }

    @Test
    fun `a medication due at another time stays out of the batch`() = runTest {
        val (medicationId, scheduleId) = seed("Estradiol", "08:00")
        seed("Progesterone", "20:00")

        val ui = awaitUi(viewModel(medicationId, scheduleId))

        assertEquals(listOf("Estradiol"), ui.doses.map { it.medicationName })
        assertFalse(ui.isGroup)
    }

    @Test
    fun `an unknown schedule closes the overlay instead of inventing a dose`() = runTest {
        val (medicationId, _) = seed("Estradiol", "08:00")

        val ui = awaitUi(viewModel(medicationId, scheduleId = 9999L))

        assertFalse(ui.loading)
        assertTrue(ui.isEmpty)
        assertTrue(ui.doses.isEmpty())
    }
}
