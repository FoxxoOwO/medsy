package cz.medsy.ui.settings

import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import cz.medsy.data.settings.SuklSyncStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The SÚKL button reported nothing at all: a failed download and a successful one looked the same.
 * These tests pin down the two facts the card now shows — whether a run is pending, and what the
 * last finished run did.
 */
@RunWith(RobolectricTestRunner::class)
class SuklSyncFeedbackTest {

    private val store = SuklSyncStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `a success stores the medication count and the time`() = runBlocking {
        store.recordSuccess(medications = 12_345, at = 1_700_000_000_000)

        val record = store.record.first()

        assertEquals(12_345, record.count)
        assertEquals(1_700_000_000_000, record.finishedAt)
        assertFalse(record.failed)
    }

    @Test
    fun `a failure keeps the last successful run visible`() = runBlocking {
        store.recordSuccess(medications = 7, at = 42L)

        store.recordFailure()

        val record = store.record.first()
        assertTrue(record.failed)
        assertEquals("the last good timestamp must survive a failed retry", 42L, record.finishedAt)
        assertEquals(7, record.count)
    }

    @Test
    fun `a later success clears the failure flag`() = runBlocking {
        store.recordFailure()

        store.recordSuccess(medications = 3, at = 99L)

        val record = store.record.first()
        assertFalse(record.failed)
        assertEquals(99L, record.finishedAt)
    }

    @Test
    fun `queued and running work counts as in progress`() {
        assertTrue(suklSyncRunning(listOf(WorkInfo.State.ENQUEUED)))
        assertTrue(suklSyncRunning(listOf(WorkInfo.State.BLOCKED)))
        assertTrue(suklSyncRunning(listOf(WorkInfo.State.RUNNING)))
    }

    @Test
    fun `finished work does not count as in progress`() {
        assertFalse(suklSyncRunning(emptyList()))
        assertFalse(suklSyncRunning(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED)))
        assertFalse(suklSyncRunning(listOf(WorkInfo.State.CANCELLED)))
    }
}
