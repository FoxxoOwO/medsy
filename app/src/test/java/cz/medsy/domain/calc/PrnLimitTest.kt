package cz.medsy.domain.calc

import cz.medsy.domain.message.plain
import cz.medsy.domain.message.plainArgs

import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.model.DoseLog
import cz.medsy.domain.model.DoseStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A PRN limit that can be exceeded is worse than no limit: the patient relies on it.
 * These tests pin the counting rules, because the real-world failure is a dose that is
 * skipped or snoozed still counting toward the ceiling.
 */
class PrnLimitTest {

    private val zone = ZoneId.of("Europe/Prague")
    private val today = LocalDate.of(2026, 11, 11)
    private val at = today.atTime(12, 0).atZone(zone).toInstant()

    private fun log(
        dose: String = "2",
        status: DoseStatus = DoseStatus.TAKEN,
        whenAt: Instant = at,
        scheduleId: Long = 1,
    ) = DoseLog(
        medicationId = 1,
        scheduleId = scheduleId,
        scheduledTimestamp = whenAt,
        actualTimestamp = if (status == DoseStatus.SKIPPED) null else whenAt,
        status = status,
        actualDoseAmount = BigDecimal(dose),
        actualVolumeMl = null,
        applicationSite = null,
    )

    private fun check(
        logs: List<DoseLog>,
        limit: String? = "4",
        dose: String = "2",
        at: Instant = this.at,
    ) = PrnLimit.check(
        logs = logs,
        scheduleId = 1,
        limit = limit?.let(::BigDecimal),
        dose = BigDecimal(dose),
        at = at,
        zone = zone,
    )

    // --- counting --------------------------------------------------------------

    @Test
    fun `a dose within the limit is allowed`() {
        val decision = check(listOf(log("2")), limit = "4", dose = "2")
        assertEquals(PrnLimit.Decision.Allowed, decision)
    }

    @Test
    fun `exceeding the limit is reported with the numbers`() {
        val decision = check(listOf(log("2"), log("2")), limit = "4", dose = "2")

        assertTrue(decision is PrnLimit.Decision.WouldExceed)
        val result = decision as PrnLimit.Decision.WouldExceed
        assertEquals(0, result.takenToday.compareTo(BigDecimal("4")))
        assertEquals(0, result.projected.compareTo(BigDecimal("6")))
        assertEquals(0, result.limit.compareTo(BigDecimal("4")))
    }

    @Test
    fun `no prior doses allows a dose up to the limit`() {
        assertEquals(PrnLimit.Decision.Allowed, check(emptyList(), limit = "2", dose = "2"))
    }

    @Test
    fun `exactly reaching the limit is allowed`() {
        assertEquals(PrnLimit.Decision.Allowed, check(listOf(log("1"), log("1")), limit = "4", dose = "2"))
    }

    @Test
    fun `no configured limit means unconstrained`() {
        assertEquals(PrnLimit.Decision.Allowed, check(listOf(log("99")), limit = null, dose = "2"))
    }

    // --- what must not count ---------------------------------------------------

    @Test
    fun `skipped doses do not count`() {
        val logs = listOf(log("2"), log("2", status = DoseStatus.SKIPPED))
        assertEquals(PrnLimit.Decision.Allowed, check(logs, limit = "4", dose = "2"))
    }

    @Test
    fun `snoozed doses do not count`() {
        val logs = listOf(log("2"), log("2", status = DoseStatus.SNOOZED))
        assertEquals(PrnLimit.Decision.Allowed, check(logs, limit = "4", dose = "2"))
    }

    @Test
    fun `late doses do count`() {
        val logs = listOf(log("2", status = DoseStatus.LATE), log("2", status = DoseStatus.LATE))
        assertTrue(check(logs, limit = "4", dose = "1") is PrnLimit.Decision.WouldExceed)
    }

    @Test
    fun `doses from another schedule do not count`() {
        val logs = listOf(log("2", scheduleId = 1), log("2", scheduleId = 2))
        assertEquals(PrnLimit.Decision.Allowed, check(logs, limit = "4", dose = "2"))
    }

    @Test
    fun `yesterday's doses do not count`() {
        val yesterday = today.minusDays(1).atTime(12, 0).atZone(zone).toInstant()
        val logs = listOf(log("2", whenAt = yesterday))
        assertEquals(PrnLimit.Decision.Allowed, check(logs, limit = "4", dose = "2"))
    }

    @Test
    fun `the daily window follows the local date, not UTC`() {
        // 23:30 Prague is already the next day in UTC; the dose belongs to the local day.
        val lateEvening = today.atTime(23, 30).atZone(zone).toInstant()
        val nextMorning = today.plusDays(1).atTime(8, 0).atZone(zone).toInstant()

        val takenYesterday = PrnLimit.takenToday(listOf(log("2", whenAt = lateEvening)), 1, today, zone)
        assertEquals(0, takenYesterday.compareTo(BigDecimal("2")))

        val takenTodayNext = PrnLimit.takenToday(
            listOf(log("2", whenAt = lateEvening)),
            1,
            today.plusDays(1),
            zone,
        )
        assertEquals(0, takenTodayNext.compareTo(BigDecimal.ZERO))

        assertTrue(nextMorning.isAfter(lateEvening))
    }

    @Test
    fun `fractional doses accumulate`() {
        val logs = listOf(log("0.5"), log("0.5"), log("0.5"))
        assertTrue(check(logs, limit = "2", dose = "0.75") is PrnLimit.Decision.WouldExceed)
    }

    @Test
    fun `a zero limit is treated as unset rather than blocking everything`() {
        assertEquals(PrnLimit.Decision.Allowed, check(listOf(log("2")), limit = "0", dose = "2"))
    }

    @Test
    fun `a zero dose is allowed even at the limit`() {
        assertEquals(PrnLimit.Decision.Allowed, check(listOf(log("4")), limit = "4", dose = "0"))
    }

    // --- message ---------------------------------------------------------------

    @Test
    fun `the message names both the projection and the limit`() {
        val decision = check(listOf(log("2"), log("2")), limit = "4", dose = "2")
            as PrnLimit.Decision.WouldExceed

        val message = PrnLimit.describe(decision)
        assertEquals(MessageKey.PRN_WOULD_EXCEED, message.key)
        val args = message.plainArgs()
        assertEquals(listOf("6", "4", "4"), args)
    }
}
