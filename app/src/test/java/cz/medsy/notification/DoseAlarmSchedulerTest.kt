package cz.medsy.notification

import android.app.AlarmManager
import android.content.Context
import cz.medsy.domain.model.MealRelation
import cz.medsy.domain.model.Schedule
import cz.medsy.domain.model.ScheduleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class DoseAlarmSchedulerTest {

    private lateinit var context: Context
    private lateinit var alarmManager: AlarmManager
    private lateinit var scheduler: DoseAlarmScheduler
    private val zone: ZoneId = ZoneId.systemDefault()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        scheduler = DoseAlarmScheduler(context, alarmManager)
    }

    private fun schedule(
        id: Long = 1,
        active: Boolean = true,
        type: ScheduleType = ScheduleType.FIXED_DAILY,
        interval: String? = null,
        times: List<LocalTime> = listOf(LocalTime.of(8, 0)),
        start: LocalDate = LocalDate.now().minusDays(1),
    ) = Schedule(
        id = id,
        medicationId = 10,
        scheduleType = type,
        intervalValue = interval?.let(::BigDecimal),
        timesOfDay = times,
        doseAmount = BigDecimal("1"),
        doseUnit = "tbl",
        mealRelation = MealRelation.NONE,
        isActive = active,
        startDate = start,
    )

    private fun armedCount(): Int = shadowOf(alarmManager).scheduledAlarms.size

    @Test
    fun `sync arms exactly one alarm for an active schedule`() {
        scheduler.sync(listOf(schedule()))
        assertEquals(1, armedCount())
    }

    @Test
    fun `sync does not arm an inactive schedule`() {
        scheduler.sync(listOf(schedule(active = false)))
        assertEquals(0, armedCount())
    }

    @Test
    fun `pausing a schedule cancels its previously armed alarm`() {
        scheduler.sync(listOf(schedule()))
        assertEquals(1, armedCount())

        scheduler.sync(listOf(schedule(active = false)))

        assertEquals(0, armedCount())
    }

    @Test
    fun `deleting a schedule cancels its previously armed alarm`() {
        scheduler.sync(listOf(schedule(id = 1), schedule(id = 2)))
        assertEquals(2, armedCount())

        scheduler.sync(listOf(schedule(id = 1)))

        assertEquals(1, armedCount())
    }

    @Test
    fun `sync is idempotent for repeated calls`() {
        val schedules = listOf(schedule(id = 1), schedule(id = 2))
        scheduler.sync(schedules)
        scheduler.sync(schedules)
        scheduler.sync(schedules)
        assertEquals(2, armedCount())
    }

    @Test
    fun `disabled reminders cancel everything`() {
        scheduler.sync(listOf(schedule(id = 1), schedule(id = 2)))
        assertEquals(2, armedCount())

        scheduler.sync(emptyList())

        assertEquals(0, armedCount())
    }

    @Test
    fun `lead time fires the alarm earlier than the dose time`() {
        // Pinned clock: the scheduler refuses to arm an alarm that is already in the past, so with
        // a real clock this assertion flipped to a failure every day between 07:45 and 08:00.
        scheduler.now = { java.time.Instant.parse("2026-05-06T05:30:00Z") }
        val withoutLead = schedule(id = 1, start = LocalDate.of(2026, 5, 1))
        scheduler.sync(listOf(withoutLead), zone, leadMinutes = 0)
        val triggerWithoutLead = shadowOf(alarmManager).scheduledAlarms.first().triggerAtTime

        shadowOf(alarmManager).scheduledAlarms.forEach { alarm ->
            alarm.operation?.let(alarmManager::cancel)
        }
        scheduler.sync(listOf(withoutLead), zone, leadMinutes = 15)
        val triggerWithLead = shadowOf(alarmManager).scheduledAlarms.first().triggerAtTime

        assertEquals(15 * 60_000L, triggerWithoutLead - triggerWithLead)
    }

    @Test
    fun `default lead time keeps the alarm on the scheduled minute`() {
        val target = LocalTime.of(8, 0)
        scheduler.sync(listOf(schedule(times = listOf(target))), zone, leadMinutes = 0)
        val alarm = shadowOf(alarmManager).scheduledAlarms.first()
        val local = java.time.Instant.ofEpochMilli(alarm.triggerAtTime).atZone(zone).toLocalTime()
        assertEquals(target, local)
    }

    @Test
    fun `interval schedule arms the next absolute occurrence`() {
        val patch = schedule(
            type = ScheduleType.INTERVAL_DAYS,
            interval = "3.5",
            start = LocalDate.now().minusDays(1),
        )
        scheduler.sync(listOf(patch))
        assertEquals(1, armedCount())
    }

    @Test
    fun `cancel removes both the regular and snoozed alarm`() {
        scheduler.sync(listOf(schedule()))
        val occurrence = cz.medsy.domain.schedule.ScheduleEngine.nextOccurrence(
            schedule(),
            java.time.Instant.now(),
            zone,
        )!!
        scheduler.scheduleSnooze(schedule(), occurrence, java.time.Instant.now().plusSeconds(600), zone)
        assertEquals(2, armedCount())

        scheduler.cancel(1)

        assertEquals(0, armedCount())
    }

    @Test
    fun `two schedules never share a pending intent request code`() {
        scheduler.sync(listOf(schedule(id = 1), schedule(id = 2)))
        val codes = shadowOf(alarmManager).scheduledAlarms.map { it.operation.hashCode() }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun `prn schedules produce no reminder`() {
        scheduler.sync(listOf(schedule(type = ScheduleType.PRN)))
        assertEquals(0, armedCount())
    }

    @Test
    fun `inactive schedule is not treated as armed after a sync`() {
        scheduler.sync(listOf(schedule(active = false)))
        assertFalse(scheduler.canScheduleExactAlarms() && armedCount() > 0)
        assertTrue(shadowOf(alarmManager).scheduledAlarms.isEmpty())
    }
}
