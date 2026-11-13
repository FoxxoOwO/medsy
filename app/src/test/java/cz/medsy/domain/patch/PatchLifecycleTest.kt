package cz.medsy.domain.patch

import cz.medsy.domain.message.MessageArg
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.model.PatchState
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
 * A patch that is left on too long delivers more hormone than prescribed, and one
 * forgotten entirely means an unintended extra day. The lifecycle must therefore be
 * derivable, not guessed, and it must not nag about a patch that is long gone.
 */
class PatchLifecycleTest {

    private fun cz.medsy.domain.message.Message.nestedArg(index: Int) =
        (args[index] as MessageArg.Nested).message

    private val applied = Instant.parse("2026-11-11T08:00:00Z")

    private fun schedule(interval: String? = "84") = Schedule(
        id = 1,
        medicationId = 1,
        scheduleType = ScheduleType.INTERVAL_HOURS,
        intervalValue = interval?.let(::BigDecimal),
        timesOfDay = listOf(LocalTime.of(8, 0)),
        doseAmount = BigDecimal.ONE,
        doseUnit = "patch",
        startDate = LocalDate.of(2026, 1, 1),
    )

    // --- wear window -----------------------------------------------------------

    @Test
    fun `the wear window defaults to 84 hours`() {
        assertEquals(Duration.ofHours(84), PatchLifecycle.wearWindow(schedule(interval = null)))
    }

    @Test
    fun `a schedule interval defines the wear window`() {
        assertEquals(Duration.ofHours(96), PatchLifecycle.wearWindow(schedule(interval = "96")))
    }

    @Test
    fun `an absurd interval falls back to the default`() {
        assertEquals(
            PatchLifecycle.DEFAULT_WEAR_HOURS.toLong(),
            PatchLifecycle.wearWindow(schedule(interval = "100000")).toHours(),
        )
    }

    @Test
    fun `removal is due exactly after the wear window`() {
        val until = PatchLifecycle.wornUntil(applied, schedule())
        assertEquals(applied.plus(Duration.ofHours(84)), until)
    }

    // --- applied ---------------------------------------------------------------

    @Test
    fun `a logged patch starts as APPLIED with a removal time`() {
        val application = PatchLifecycle.onApplied(applied, schedule())

        assertEquals(PatchState.APPLIED, application.state)
        assertEquals(applied.plus(Duration.ofHours(84)), application.wornUntil)
    }

    // --- removal state ---------------------------------------------------------

    @Test
    fun `before the window closes the patch is merely WORN`() {
        val until = applied.plus(Duration.ofHours(84))
        assertEquals(
            PatchState.WORN,
            PatchLifecycle.removalState(until, applied.plus(Duration.ofHours(40))),
        )
    }

    @Test
    fun `at the exact removal time the state is REMOVAL_DUE`() {
        val until = applied.plus(Duration.ofHours(84))
        assertEquals(PatchState.REMOVAL_DUE, PatchLifecycle.removalState(until, until))
    }

    @Test
    fun `after the window the state stays REMOVAL_DUE`() {
        val until = applied.plus(Duration.ofHours(84))
        assertEquals(
            PatchState.REMOVAL_DUE,
            PatchLifecycle.removalState(until, until.plus(Duration.ofHours(2))),
        )
    }

    // --- when to ask -----------------------------------------------------------

    @Test
    fun `removal is not asked for before it is due`() {
        val until = applied.plus(Duration.ofHours(84))
        assertFalse(PatchLifecycle.shouldAskForRemoval(until, applied.plus(Duration.ofHours(80))))
    }

    @Test
    fun `removal is asked for when due`() {
        val until = applied.plus(Duration.ofHours(84))
        assertTrue(PatchLifecycle.shouldAskForRemoval(until, until))
    }

    @Test
    fun `a long forgotten patch is not asked about again`() {
        val until = applied.plus(Duration.ofHours(84))
        // Two days late: the patient has certainly noticed or replaced it.
        assertFalse(
            PatchLifecycle.shouldAskForRemoval(until, until.plus(Duration.ofDays(2))),
        )
    }

    @Test
    fun `the stale window is generous enough for a late check`() {
        val until = applied.plus(Duration.ofHours(84))
        assertTrue(
            PatchLifecycle.shouldAskForRemoval(until, until.plus(Duration.ofHours(11))),
        )
    }

    // --- removed ---------------------------------------------------------------

    @Test
    fun `confirming removal yields REMOVED`() {
        assertEquals(PatchState.REMOVED, PatchLifecycle.onRemoved())
    }

    // --- descriptions ----------------------------------------------------------

    @Test
    fun `a worn patch describes the remaining time`() {
        val until = Instant.now().plus(Duration.ofHours(30))
        val text = PatchLifecycle.describe(PatchState.WORN, until, Instant.now())
        assertEquals(MessageKey.PATCH_WORN_REMOVE_IN, text.key)
        assertEquals(MessageKey.DURATION_HOURS_MINUTES, text.nestedArg(0).key)
    }

    @Test
    fun `a due patch says removal is due`() {
        val text = PatchLifecycle.describe(PatchState.REMOVAL_DUE, Instant.now(), Instant.now())
        assertEquals(MessageKey.PATCH_REMOVAL_DUE, text.key)
    }

    @Test
    fun `an applied patch reports its wear window`() {
        val text = PatchLifecycle.describe(PatchState.APPLIED, Instant.now().plusSeconds(3600), Instant.now())
        assertEquals(MessageKey.PATCH_APPLIED_UNTIL, text.key)
        assertEquals(MessageKey.DURATION_HOURS_MINUTES, text.nestedArg(0).key)
    }
}
