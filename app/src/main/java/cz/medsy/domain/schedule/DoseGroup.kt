package cz.medsy.domain.schedule

import cz.medsy.domain.model.Schedule
import java.time.Instant
import java.time.ZoneId

/**
 * The schedules that put a dose on the very same minute as [dueAt].
 *
 * Each alarm opens the overlay on its own, so the screen has to rebuild the rest of the batch
 * itself: it asks every active schedule for its next occurrence and keeps the ones that land on
 * the same instant. Snoozed doses never match, because a snooze moves the occurrence.
 */
fun dosesDueAt(schedules: List<Schedule>, dueAt: Instant, zone: ZoneId): List<Schedule> {
    val justBefore = dueAt.minusSeconds(1)
    return schedules.filter { schedule ->
        val next = ScheduleEngine.nextOccurrence(schedule, justBefore, zone)
        next != null && !next.dueAt.isAfter(dueAt)
    }
}
