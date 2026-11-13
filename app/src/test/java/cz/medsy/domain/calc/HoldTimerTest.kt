package cz.medsy.domain.calc

import cz.medsy.domain.model.MedicationForm
import cz.medsy.domain.model.Schedule
import cz.medsy.domain.model.ScheduleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The hold timer is advisory but must be *correct*, because a patient may rely on it to
 * know when they can swallow. Deriving the remaining time from the start instant (not a
 * decrementing counter) is what keeps it right after the screen was off.
 */
class HoldTimerTest {

    private val start = Instant.parse("2026-11-11T08:00:00Z")

    private fun schedule(minutes: Int? = 15) = Schedule(
        id = 1,
        medicationId = 1,
        scheduleType = ScheduleType.FIXED_DAILY,
        timesOfDay = listOf(LocalTime.of(8, 0)),
        doseAmount = BigDecimal.ONE,
        doseUnit = "tbl",
        startDate = LocalDate.of(2026, 1, 1),
        holdMinutes = minutes,
    )

    // --- applicability ---------------------------------------------------------

    @Test
    fun `only sublingual doses have a hold timer`() {
        assertTrue(HoldTimer.appliesTo(MedicationForm.SUBLINGUAL))
        assertFalse(HoldTimer.appliesTo(MedicationForm.TABLET))
        assertFalse(HoldTimer.appliesTo(MedicationForm.GEL))
    }

    // --- duration --------------------------------------------------------------

    @Test
    fun `the configured hold is used`() {
        assertEquals(Duration.ofMinutes(15), HoldTimer.holdDuration(schedule(15)))
    }

    @Test
    fun `an unset hold defaults to ten minutes`() {
        assertEquals(
            Duration.ofMinutes(HoldTimer.DEFAULT_HOLD_MINUTES.toLong()),
            HoldTimer.holdDuration(schedule(null)),
        )
    }

    @Test
    fun `an absurd hold falls back to the default`() {
        assertEquals(
            HoldTimer.DEFAULT_HOLD_MINUTES.toLong(),
            HoldTimer.holdDuration(schedule(5000)).toMinutes(),
        )
    }

    @Test
    fun `a zero hold falls back to the default`() {
        assertEquals(
            HoldTimer.DEFAULT_HOLD_MINUTES.toLong(),
            HoldTimer.holdDuration(schedule(0)).toMinutes(),
        )
    }

    @Test
    fun `no schedule still yields a default hold`() {
        assertEquals(HoldTimer.DEFAULT_HOLD_MINUTES.toLong(), HoldTimer.holdDuration(null).toMinutes())
    }

    // --- timing ----------------------------------------------------------------

    @Test
    fun `the end instant is start plus the hold`() {
        assertEquals(start.plus(Duration.ofMinutes(15)), HoldTimer.endsAt(start, schedule(15)))
    }

    @Test
    fun `remaining time counts down`() {
        val remaining = HoldTimer.remaining(start, schedule(15), start.plus(Duration.ofMinutes(5)))
        assertEquals(Duration.ofMinutes(10), remaining)
    }

    @Test
    fun `remaining time never goes negative`() {
        val remaining = HoldTimer.remaining(start, schedule(15), start.plus(Duration.ofHours(2)))
        assertEquals(Duration.ZERO, remaining)
    }

    @Test
    fun `the timer is not finished before the hold elapses`() {
        assertFalse(HoldTimer.isFinished(start, schedule(15), start.plus(Duration.ofMinutes(14))))
    }

    @Test
    fun `the timer is finished exactly at the end`() {
        assertTrue(HoldTimer.isFinished(start, schedule(15), start.plus(Duration.ofMinutes(15))))
    }

    @Test
    fun `the timer stays finished afterwards`() {
        assertTrue(HoldTimer.isFinished(start, schedule(15), start.plus(Duration.ofHours(3))))
    }

    // --- progress --------------------------------------------------------------

    @Test
    fun `progress is zero at the start`() {
        assertEquals(0f, HoldTimer.progress(start, schedule(10), start), 0.001f)
    }

    @Test
    fun `progress is half way at half the hold`() {
        assertEquals(
            0.5f,
            HoldTimer.progress(start, schedule(10), start.plus(Duration.ofMinutes(5))),
            0.001f,
        )
    }

    @Test
    fun `progress saturates at one`() {
        assertEquals(
            1f,
            HoldTimer.progress(start, schedule(10), start.plus(Duration.ofHours(1))),
            0.001f,
        )
    }

    @Test
    fun `a clock that went backwards does not produce negative progress`() {
        assertEquals(
            0f,
            HoldTimer.progress(start, schedule(10), start.minus(Duration.ofMinutes(5))),
            0.001f,
        )
    }

    // --- formatting ------------------------------------------------------------

    @Test
    fun `remaining time is formatted as minutes and seconds`() {
        assertEquals("9:30", HoldTimer.formatRemaining(Duration.ofSeconds(570)))
    }

    @Test
    fun `under a minute still shows seconds`() {
        assertEquals("0:45", HoldTimer.formatRemaining(Duration.ofSeconds(45)))
    }

    @Test
    fun `zero formats cleanly`() {
        assertEquals("0:00", HoldTimer.formatRemaining(Duration.ZERO))
    }

    @Test
    fun `a negative duration formats as zero rather than crashing`() {
        assertEquals("0:00", HoldTimer.formatRemaining(Duration.ofSeconds(-30)))
    }
}
