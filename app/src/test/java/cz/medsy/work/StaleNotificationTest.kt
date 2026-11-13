package cz.medsy.work

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Notification ids in the workers are derived from a row index, and a worker run only *posts*.
 *
 * That combination leaves the previous run's alert on the shade forever: a medication that was
 * restocked keeps the id of a line that is no longer flagged, and a blood test whose warning
 * disappeared keeps its "draw is due" card. The workers therefore clear the ids first, which is
 * what these checks pin -- the worker itself needs a device, the source does not.
 */
class StaleNotificationTest {

    private val workers: String by lazy {
        File("src/main/java/cz/medsy/work/Workers.kt").readText()
    }

    @Test
    fun `the inventory worker clears its index based ids before posting`() {
        assertTrue(
            "a cancel loop is missing, so a restocked medication keeps its low-stock alert",
            workers.contains("for (offset in 0 until MAX_NOTIFICATIONS) notifier.cancel(NOTIFICATION_BASE + offset)"),
        )
        assertTrue(
            "the bound must be defined next to the base id",
            workers.contains("const val MAX_NOTIFICATIONS = 64"),
        )
    }

    @Test
    fun `the bloodwork worker clears the warning when there is none`() {
        val emptyBranch = workers
            .substringAfter("if (warnings.isEmpty()) {")
            .substringBefore("return Result.success()")
        assertTrue(
            "an empty warning list leaves the old notification behind",
            emptyBranch.contains("notifier.cancel(NOTIFICATION_ID)"),
        )
    }
}
