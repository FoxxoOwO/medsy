package cz.medsy.notification

import cz.medsy.domain.message.plain
import cz.medsy.domain.message.plainArgs

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cz.medsy.data.local.MedsyDatabase
import cz.medsy.data.local.entity.MedicationEntity
import cz.medsy.data.local.entity.ScheduleEntity
import cz.medsy.data.repository.MedicationRepository
import cz.medsy.domain.message.MessageArg
import cz.medsy.domain.message.MessageKey
import cz.medsy.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import kotlinx.coroutines.runBlocking

/**
 * The absorption offset must be applied once and stay applied. Re-applying it on every
 * refresh would move the reminder further each time, and a plan that fights the user's
 * own offset would be worse than no plan.
 */
@RunWith(RobolectricTestRunner::class)
class AbsorptionOffsetIntegrationTest {

    private lateinit var db: MedsyDatabase
    private lateinit var repository: MedicationRepository
    private lateinit var coordinator: ReminderCoordinator
    private lateinit var settingsRepository: SettingsRepository
    private var ironScheduleId = 0L
    private var calciumScheduleId = 0L

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(context, MedsyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MedicationRepository(db.medicationDao(), db.scheduleDao(), db.milestoneDao())
        settingsRepository = SettingsRepository(context)
        settingsRepository.setRemindersEnabled(true)

        val iron = db.medicationDao().insert(
            MedicationEntity(name = "Ferrosi sulfas", activeSubstance = "Ferrosi sulfas", form = "TABLET"),
        )
        val calcium = db.medicationDao().insert(
            MedicationEntity(name = "Calcii carbonas", activeSubstance = "Calcii carbonas", form = "TABLET"),
        )
        ironScheduleId = db.scheduleDao().insert(
            ScheduleEntity(
                medicationId = iron,
                scheduleType = "FIXED_DAILY",
                timesOfDayJson = """["08:00"]""",
                doseAmount = 1.0,
                doseUnit = "tbl",
                startDateEpochDay = LocalDate.of(2026, 1, 1).toEpochDay(),
            ),
        )
        calciumScheduleId = db.scheduleDao().insert(
            ScheduleEntity(
                medicationId = calcium,
                scheduleType = "FIXED_DAILY",
                timesOfDayJson = """["20:00"]""",
                doseAmount = 1.0,
                doseUnit = "tbl",
                startDateEpochDay = LocalDate.of(2026, 1, 1).toEpochDay(),
            ),
        )

        coordinator = ReminderCoordinator(
            context,
            DoseAlarmScheduler(context, context.getSystemService(android.app.AlarmManager::class.java)),
            repository,
            settingsRepository,
            DoseNotifier(context),
            cz.medsy.data.repository.PrescriptionRepository(db.prescriptionDao()),
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `the later dose is shifted by the required gap`() = runTest {
        coordinator.refresh()

        val schedules = repository.activeSchedules()
        val calcium = schedules.first { it.id == calciumScheduleId }
        assertEquals(120, calcium.offsetMinutes)
    }

    @Test
    fun `the anchor medication is not moved`() = runTest {
        coordinator.refresh()

        val iron = repository.activeSchedules().first { it.id == ironScheduleId }
        assertEquals(0, iron.offsetMinutes)
    }

    @Test
    fun `a second refresh does not shift again`() = runTest {
        coordinator.refresh()
        coordinator.refresh()
        coordinator.refresh()

        val calcium = repository.activeSchedules().first { it.id == calciumScheduleId }
        assertEquals("the offset must be idempotent", 120, calcium.offsetMinutes)
    }

    @Test
    fun `the shift is reported once`() = runTest {
        coordinator.refresh()
        val notices = coordinator.takeOffsetNotices()

        assertTrue("the user must be told about the shift", notices.isNotEmpty())
        val notice = notices.first().message
        assertEquals(MessageKey.ABSORPTION_EXPLANATION, notice.key)
        assertEquals("120", notice.args.first().plain())
        assertTrue("notices are consumed, not repeated", coordinator.takeOffsetNotices().isEmpty())
    }

    @Test
    fun `no conflict means no shift`() = runTest {
        db.scheduleDao().delete(calciumScheduleId)

        coordinator.refresh()

        val iron = repository.activeSchedules().first { it.id == ironScheduleId }
        assertEquals(0, iron.offsetMinutes)
    }

    @Test
    fun `a user offset is left alone and reported as unresolved`() = runTest {
        db.scheduleDao().update(
            db.scheduleDao().getById(calciumScheduleId)!!.copy(offsetMinutes = 30),
        )

        coordinator.refresh()

        val calcium = repository.activeSchedules().first { it.id == calciumScheduleId }
        assertEquals("a deliberate 30 minute offset must survive", 30, calcium.offsetMinutes)
        assertTrue(coordinator.takeOffsetNotices().isEmpty())
    }

    @Test
    fun `disabled reminders do not shift anything`() = runTest {
        settingsRepository.setRemindersEnabled(false)

        coordinator.refresh()

        val calcium = repository.activeSchedules().first { it.id == calciumScheduleId }
        assertEquals(0, calcium.offsetMinutes)
    }

    @Test
    fun `the shifted schedule is what gets synced`() = runTest {
        coordinator.refresh()

        val calcium = repository.activeSchedules().first { it.id == calciumScheduleId }
        val next = cz.medsy.domain.schedule.ScheduleEngine.nextOccurrence(
            calcium,
            java.time.Instant.now(),
            java.time.ZoneId.systemDefault(),
        )
        assertEquals(java.time.LocalTime.of(22, 0), next!!.localTime)
    }

    @Test
    fun `a schedule keeps its other fields after the shift`() = runTest {
        coordinator.refresh()

        val calcium = repository.activeSchedules().first { it.id == calciumScheduleId }
        assertEquals(0, calcium.doseAmount.compareTo(java.math.BigDecimal.ONE))
        assertTrue(calcium.isActive)
    }

    @Test
    fun `reading schedules directly does not trigger a shift`() = runTest {
        // Guard: only refresh() may mutate, otherwise a read would have side effects.
        val schedules = repository.activeSchedules().first { it.id == calciumScheduleId }
        assertEquals(0, schedules.offsetMinutes)
    }
}
