package cz.medsy.domain.milestone

import cz.medsy.domain.message.plain
import cz.medsy.domain.message.plainArgs

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cz.medsy.data.local.MedsyDatabase
import cz.medsy.data.local.entity.MedicationEntity
import cz.medsy.data.repository.MedicationRepository
import cz.medsy.domain.model.ChangeType
import cz.medsy.domain.model.Schedule
import cz.medsy.domain.model.ScheduleType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Milestones are written by the repository, not the UI, so that every caller gets them.
 * This exercises the real Room path: create → edit dose → pause → delete.
 */
@RunWith(RobolectricTestRunner::class)
class MilestoneRecordingTest {

    private lateinit var db: MedsyDatabase
    private lateinit var repository: MedicationRepository
    private var medicationId = 0L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(context, MedsyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MedicationRepository(db.medicationDao(), db.scheduleDao(), db.milestoneDao())
        medicationId = kotlinx.coroutines.runBlocking {
            db.medicationDao().insert(
                MedicationEntity(name = "Estradiol Enanthate", form = "INJECTION_IM"),
            )
        }
    }

    @After
    fun tearDown() = db.close()

    private fun schedule(dose: String = "5", active: Boolean = true) = Schedule(
        medicationId = medicationId,
        scheduleType = ScheduleType.INTERVAL_DAYS,
        intervalValue = BigDecimal("3.5"),
        timesOfDay = listOf(LocalTime.of(8, 0)),
        doseAmount = BigDecimal(dose),
        doseUnit = "mg",
        isActive = active,
        startDate = LocalDate.of(2026, 1, 1),
    )

    private suspend fun milestones() = db.milestoneDao().getAll()

    @Test
    fun `creating a schedule records STARTED`() = runTest {
        repository.saveSchedule(schedule())

        val recorded = milestones()
        assertEquals(1, recorded.size)
        assertEquals(ChangeType.STARTED.name, recorded.single().changeType)
        assertEquals(medicationId, recorded.single().medicationId)
        val stored = cz.medsy.domain.message.MessageCodec.decode(recorded.single().message)!!
        assertEquals(cz.medsy.domain.message.MessageKey.MS_STARTED, stored.key)
        val scheduleText = (stored.args[0] as cz.medsy.domain.message.MessageArg.Nested).message
        assertEquals("5 mg", scheduleText.args[0].plain())
        // New rows keep the sentence only in `message`, so a language switch also reaches history.
        assertEquals("", recorded.single().description)
    }

    @Test
    fun `changing the dose records DOSE_CHANGED`() = runTest {
        val id = repository.saveSchedule(schedule(dose = "5"))
        val stored = db.scheduleDao().getById(id)!!.let {
            repository.observeSchedules(medicationId).first().single()
        }

        repository.saveSchedule(stored.copy(doseAmount = BigDecimal("7")))

        val changes = milestones().map { it.changeType }
        assertEquals(2, changes.size)
        assertEquals(ChangeType.DOSE_CHANGED.name, changes.last())
    }

    @Test
    fun `re-saving an unchanged schedule adds no milestone`() = runTest {
        repository.saveSchedule(schedule())
        val stored = repository.observeSchedules(medicationId).first().single()

        repository.saveSchedule(stored)

        assertEquals(1, milestones().size)
    }

    @Test
    fun `pausing records STOPPED and resuming records STARTED`() = runTest {
        val id = repository.saveSchedule(schedule())

        repository.setScheduleActive(id, false)
        repository.setScheduleActive(id, true)

        val types = milestones().map { it.changeType }
        assertEquals(listOf("STARTED", "STOPPED", "STARTED"), types)
    }

    @Test
    fun `deleting records STOPPED`() = runTest {
        val id = repository.saveSchedule(schedule())
        val before = milestones().size

        repository.deleteSchedule(id)

        val after = milestones()
        assertEquals(before + 1, after.size)
        assertEquals(ChangeType.STOPPED.name, after.last().changeType)
    }

    @Test
    fun `milestones are ordered by time for the chart`() = runTest {
        val id = repository.saveSchedule(schedule(dose = "5"))
        val stored = repository.observeSchedules(medicationId).first().single()
        repository.saveSchedule(stored.copy(doseAmount = BigDecimal("6")))
        repository.setScheduleActive(id, false)

        val timestamps = milestones().map { it.timestamp }
        assertEquals(timestamps.sorted(), timestamps)
    }

    @Test
    fun `every milestone carries the medication id`() = runTest {
        repository.saveSchedule(schedule())
        assertTrue(milestones().all { it.medicationId == medicationId })
    }
}
