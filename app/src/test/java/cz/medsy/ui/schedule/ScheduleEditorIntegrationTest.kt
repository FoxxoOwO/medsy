package cz.medsy.ui.schedule

import android.app.AlarmManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cz.medsy.data.local.MedsyDatabase
import cz.medsy.data.local.entity.MedicationEntity
import cz.medsy.data.repository.DoseRepository
import cz.medsy.data.repository.MedicationRepository
import cz.medsy.data.settings.SettingsRepository
import cz.medsy.domain.model.ScheduleType
import cz.medsy.notification.DoseAlarmScheduler
import cz.medsy.notification.DoseNotifier
import cz.medsy.notification.ReminderCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.LocalTime

/**
 * The editor's whole point is that a saved schedule exists *and* has an alarm. The
 * reminder audit found schedules that were saved but never armed, so this test asserts
 * the end-to-end effect rather than just the returned id.
 */
@RunWith(RobolectricTestRunner::class)
class ScheduleEditorIntegrationTest {

    private lateinit var db: MedsyDatabase
    private lateinit var medicationRepository: MedicationRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var alarmManager: AlarmManager
    private lateinit var coordinator: ReminderCoordinator
    private lateinit var viewModel: ScheduleEditorViewModel
    private var medicationId = 0L

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(context, MedsyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        medicationRepository = MedicationRepository(db.medicationDao(), db.scheduleDao(), db.milestoneDao())
        settingsRepository = SettingsRepository(context)
        // DataStore is process-wide, so another test class may have left reminders off.
        runBlocking { settingsRepository.setRemindersEnabled(true) }
        alarmManager = context.getSystemService(AlarmManager::class.java)
        val scheduler = DoseAlarmScheduler(context, alarmManager)
        coordinator = ReminderCoordinator(
            context,
            scheduler,
            medicationRepository,
            settingsRepository,
            DoseNotifier(context),
            cz.medsy.data.repository.PrescriptionRepository(db.prescriptionDao()),
        )
        medicationId = runBlocking {
            db.medicationDao().insert(
                MedicationEntity(name = "Estradiol Enanthate", form = "INJECTION_IM"),
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun editor(scheduleId: Long = 0) = ScheduleEditorViewModel(
        medicationRepository = medicationRepository,
        reminderCoordinator = coordinator,
        // Robolectric resolves the real resources, so the default unit is the Czech/English one
        // for the test locale rather than a hardcoded token.
        context = ApplicationProvider.getApplicationContext(),
        savedStateHandle = androidx.lifecycle.SavedStateHandle(
            mapOf("medicationId" to medicationId, "scheduleId" to scheduleId),
        ),
    )

    private fun awaitDraft(vm: ScheduleEditorViewModel): ScheduleDraft {
        repeat(100) {
            vm.state.value.draft?.let { return it }
            Thread.sleep(20)
        }
        error("draft never loaded")
    }

    @Test
    fun `creating a schedule persists it`() = runBlocking {
        val vm = editor()
        awaitDraft(vm)

        vm.setType(ScheduleType.INTERVAL_DAYS)
        vm.setInterval("3.5")
        vm.setDose("5")
        vm.setUnit("mg")
        vm.save()

        val stored = awaitSchedules(medicationId) { it.isNotEmpty() }
        assertEquals(1, stored.size)
        assertEquals(ScheduleType.INTERVAL_DAYS, stored.single().scheduleType)
        assertEquals(0, stored.single().intervalValue!!.compareTo(java.math.BigDecimal("3.5")))
        assertEquals(medicationId, stored.single().medicationId)
    }

    /**
     * Room's suspend DAO calls hop to its own executor, so a write started by save()
     * is not visible on the test thread immediately. Poll instead of assuming.
     */
    private suspend fun awaitSchedules(
        medicationId: Long,
        predicate: (List<cz.medsy.domain.model.Schedule>) -> Boolean,
    ): List<cz.medsy.domain.model.Schedule> {
        repeat(100) {
            val current = medicationRepository.observeSchedules(medicationId).first()
            if (predicate(current)) return current
            Thread.sleep(20)
        }
        error("schedule state never settled")
    }

    /** refresh() reads settings on Dispatchers.IO, so arming is not synchronous. */
    private fun awaitAlarmCount(expected: Int) {
        repeat(100) {
            if (shadowOf(alarmManager).scheduledAlarms.size == expected) return
            Thread.sleep(20)
        }
        error("expected $expected armed alarm(s), got ${shadowOf(alarmManager).scheduledAlarms.size}")
    }

    @Test
    fun `creating a schedule arms an alarm`() = runBlocking {
        val vm = editor()
        awaitDraft(vm)
        vm.setType(ScheduleType.FIXED_DAILY)
        vm.setDose("1")
        vm.setUnit("tbl")

        vm.save()

        awaitAlarmCount(1)
    }

    @Test
    fun `invalid input is rejected and nothing is persisted`() = runBlocking {
        val vm = editor()
        awaitDraft(vm)
        vm.setType(ScheduleType.INTERVAL_DAYS)
        vm.setInterval("")
        vm.setDose("0")

        vm.save()

        Thread.sleep(300)
        val stored = medicationRepository.observeSchedules(medicationId).first()
        assertTrue(stored.isEmpty())
        assertTrue(vm.state.value.errors.isNotEmpty())
    }

    @Test
    fun `editing an existing schedule updates instead of duplicating`() = runBlocking {
        val vm = editor()
        awaitDraft(vm)
        vm.setType(ScheduleType.FIXED_DAILY)
        vm.setDose("1")
        vm.save()

        val created = medicationRepository.observeSchedules(medicationId).first().single()

        val editing = editor(created.id)
        val draft = awaitDraft(editing)
        assertEquals(created.id, draft.id)

        editing.setDose("2")
        editing.save()

        val after = awaitSchedules(medicationId) { schedules ->
            schedules.size == 1 && schedules.single().doseAmount.compareTo(java.math.BigDecimal("2")) == 0
        }
        assertEquals(1, after.size)
        assertEquals(0, after.single().doseAmount.compareTo(java.math.BigDecimal("2")))
    }

    @Test
    fun `deleting a schedule removes it and its alarm`() = runBlocking {
        val vm = editor()
        awaitDraft(vm)
        vm.setType(ScheduleType.FIXED_DAILY)
        vm.save()
        val created = awaitSchedules(medicationId) { it.size == 1 }.single()

        val editing = editor(created.id)
        awaitDraft(editing)
        editing.delete()

        awaitSchedules(medicationId) { it.isEmpty() }
        awaitAlarmCount(0)
        assertTrue(medicationRepository.observeSchedules(medicationId).first().isEmpty())
    }

    @Test
    fun `pausing a schedule cancels its alarm`() = runBlocking {
        val vm = editor()
        awaitDraft(vm)
        vm.setType(ScheduleType.FIXED_DAILY)
        vm.save()
        val created = medicationRepository.observeSchedules(medicationId).first().single()

        coordinator.refreshSchedule(created.id)
        awaitAlarmCount(1)

        medicationRepository.setScheduleActive(created.id, false)
        coordinator.refreshSchedule(created.id)

        awaitAlarmCount(0)
    }

    @Test
    fun `times can be added and removed`() {
        val vm = editor()
        awaitDraft(vm)

        vm.addTime(LocalTime.of(20, 0))
        assertEquals(2, vm.state.value.draft!!.times.size)

        vm.removeTime(LocalTime.of(20, 0))
        assertEquals(1, vm.state.value.draft!!.times.size)
    }

    @Test
    fun `an injection defaults to an interval schedule with site rotation`() {
        val draft = awaitDraft(editor())
        assertEquals(ScheduleType.INTERVAL_DAYS, draft.type)
        assertNotNull(draft.intervalValue)
        assertTrue(draft.requireSiteRotation)
    }

    @Test
    fun `default unit follows the medication form`() {
        val draft = awaitDraft(editor())
        assertEquals("mg", draft.doseUnit)
    }
}
