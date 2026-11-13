package cz.medsy.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cz.medsy.data.local.MedsyDatabase
import cz.medsy.data.local.entity.DoseLogEntity
import cz.medsy.data.local.entity.MedicationEntity
import cz.medsy.data.local.entity.ScheduleEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A medication can have several schedules (morning + evening). The "last taken dose"
 * must be scoped to the schedule being assessed, otherwise the trough calculation
 * sees the wrong dose or none at all.
 */
@RunWith(RobolectricTestRunner::class)
class LastDosePerScheduleTest {

    private lateinit var db: MedsyDatabase
    private lateinit var doseRepository: DoseRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MedsyDatabase::class.java,
        ).allowMainThreadQueries().build()
        doseRepository = DoseRepository(
            db.doseLogDao(),
            db.medicationDao(),
            db.scheduleDao(),
            db.inventoryDao(),
            db.consumableDao(),
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedMedication(): Pair<Long, Long> {
        val medicationId = db.medicationDao().insert(
            MedicationEntity(name = "Estradiol", form = "INJECTION_IM", isHrt = true),
        )
        val scheduleId = db.scheduleDao().insert(
            ScheduleEntity(
                medicationId = medicationId,
                scheduleType = "INTERVAL_DAYS",
                intervalValue = 3.5,
                timesOfDayJson = """["08:00"]""",
                doseAmount = 5.0,
                doseUnit = "mg",
                startDateEpochDay = 20_000L,
            ),
        )
        return medicationId to scheduleId
    }

    @Test
    fun `returns the newest dose for the schedule`() = runTest {
        val (medicationId, scheduleId) = seedMedication()
        db.doseLogDao().upsert(
            DoseLogEntity(
                medicationId = medicationId,
                scheduleId = scheduleId,
                scheduledTimestamp = 1_000L,
                actualTimestamp = 2_000L,
                status = "TAKEN",
            ),
        )
        db.doseLogDao().upsert(
            DoseLogEntity(
                medicationId = medicationId,
                scheduleId = scheduleId,
                scheduledTimestamp = 3_000L,
                actualTimestamp = 4_000L,
                status = "TAKEN",
            ),
        )

        val last = doseRepository.lastTakenForSchedule(medicationId, scheduleId)

        assertNotNull(last)
        assertEquals(4_000L, last!!.actualTimestamp!!.toEpochMilli())
    }

    @Test
    fun `ignores a newer dose belonging to another schedule`() = runTest {
        val (medicationId, morningSchedule) = seedMedication()
        val eveningSchedule = db.scheduleDao().insert(
            ScheduleEntity(
                medicationId = medicationId,
                scheduleType = "FIXED_DAILY",
                timesOfDayJson = """["20:00"]""",
                doseAmount = 2.0,
                doseUnit = "tbl",
                startDateEpochDay = 20_000L,
            ),
        )

        db.doseLogDao().upsert(
            DoseLogEntity(
                medicationId = medicationId,
                scheduleId = morningSchedule,
                scheduledTimestamp = 1_000L,
                actualTimestamp = 1_000L,
                status = "TAKEN",
            ),
        )
        // Evening dose is newer but belongs to a different schedule.
        db.doseLogDao().upsert(
            DoseLogEntity(
                medicationId = medicationId,
                scheduleId = eveningSchedule,
                scheduledTimestamp = 9_000L,
                actualTimestamp = 9_000L,
                status = "TAKEN",
            ),
        )

        val morningLast = doseRepository.lastTakenForSchedule(medicationId, morningSchedule)

        assertNotNull("the morning schedule has its own last dose", morningLast)
        assertEquals(morningSchedule, morningLast!!.scheduleId)
        assertEquals(1_000L, morningLast.actualTimestamp!!.toEpochMilli())
    }

    @Test
    fun `a schedule with no taken dose returns null`() = runTest {
        val (medicationId, scheduleId) = seedMedication()
        db.doseLogDao().upsert(
            DoseLogEntity(
                medicationId = medicationId,
                scheduleId = scheduleId,
                scheduledTimestamp = 1_000L,
                status = "SKIPPED",
            ),
        )

        assertEquals(null, doseRepository.lastTakenForSchedule(medicationId, scheduleId))
    }

    @Test
    fun `a late dose still counts as the last taken dose`() = runTest {
        val (medicationId, scheduleId) = seedMedication()
        db.doseLogDao().upsert(
            DoseLogEntity(
                medicationId = medicationId,
                scheduleId = scheduleId,
                scheduledTimestamp = 1_000L,
                actualTimestamp = 5_000L,
                status = "LATE",
            ),
        )

        assertNotNull(doseRepository.lastTakenForSchedule(medicationId, scheduleId))
    }
}
