package cz.medsy.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cz.medsy.data.local.MedsyDatabase
import cz.medsy.data.local.entity.MedicationEntity
import cz.medsy.data.local.entity.ScheduleEntity
import cz.medsy.domain.model.DoseStatus
import cz.medsy.domain.model.PatchState
import cz.medsy.domain.schedule.Occurrence
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Patch state lives on the dose log so that "is a patch still on the skin?" can be
 * answered. Without persisting it, the removal reminder has nothing to reason about.
 */
@RunWith(RobolectricTestRunner::class)
class PatchPersistenceTest {

    private lateinit var db: MedsyDatabase
    private lateinit var repository: DoseRepository
    private var medicationId = 0L
    private var scheduleId = 0L

    @Before
    fun setUp() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(context, MedsyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = DoseRepository(
            db.doseLogDao(),
            db.medicationDao(),
            db.scheduleDao(),
            db.inventoryDao(),
            db.consumableDao(),
        )
        medicationId = db.medicationDao().insert(
            MedicationEntity(name = "Estradiol patch", form = "PATCH", strengthUnit = "patch"),
        )
        scheduleId = db.scheduleDao().insert(
            ScheduleEntity(
                medicationId = medicationId,
                scheduleType = "INTERVAL_HOURS",
                intervalValue = 84.0,
                timesOfDayJson = """["08:00"]""",
                doseAmount = 1.0,
                doseUnit = "patch",
                startDateEpochDay = LocalDate.of(2026, 1, 1).toEpochDay(),
            ),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun occurrence(at: Instant): Occurrence {
        val zoned = at.atZone(java.time.ZoneId.systemDefault())
        return Occurrence(
            scheduleId = scheduleId,
            medicationId = medicationId,
            dueAt = at,
            localDate = zoned.toLocalDate(),
            localTime = LocalTime.of(8, 0),
            doseAmount = BigDecimal.ONE,
            doseUnit = "patch",
        )
    }

    private val appliedAt: Instant =
        LocalDate.of(2026, 11, 11).atTime(8, 0).atZone(java.time.ZoneId.systemDefault()).toInstant()

    @Test
    fun `applying a patch records APPLIED and the removal time`() = runTest {
        repository.log(occurrence(appliedAt), DoseStatus.TAKEN, actualTime = appliedAt)

        val stored = db.doseLogDao().getFrom(0L).single()
        assertEquals(PatchState.APPLIED.name, stored.patchState)
        assertEquals(
            appliedAt.plus(Duration.ofHours(84)).toEpochMilli(),
            stored.wornUntil,
        )
    }

    @Test
    fun `a tablet dose records no patch state`() = runTest {
        val tabletId = db.medicationDao().insert(
            MedicationEntity(name = "Progesterone", form = "TABLET", strengthUnit = "tbl"),
        )
        val tabletSchedule = db.scheduleDao().insert(
            ScheduleEntity(
                medicationId = tabletId,
                scheduleType = "FIXED_DAILY",
                timesOfDayJson = """["22:00"]""",
                doseAmount = 2.0,
                doseUnit = "tbl",
                startDateEpochDay = LocalDate.of(2026, 1, 1).toEpochDay(),
            ),
        )

        repository.log(
            occurrence(appliedAt).copy(scheduleId = tabletSchedule, medicationId = tabletId),
            DoseStatus.TAKEN,
            actualTime = appliedAt,
        )

        val stored = db.doseLogDao().getFrom(0L).single()
        assertNull("tablets have no wear window", stored.patchState)
        assertNull(stored.wornUntil)
    }

    @Test
    fun `skipping a patch does not start the wear clock`() = runTest {
        repository.log(occurrence(appliedAt), DoseStatus.SKIPPED)

        val stored = db.doseLogDao().getFrom(0L).single()
        assertNull(stored.patchState)
        assertNull(stored.wornUntil)
    }

    @Test
    fun `the patch can be marked removed`() = runTest {
        val id = repository.log(occurrence(appliedAt), DoseStatus.TAKEN, actualTime = appliedAt)

        val changed = repository.markPatchRemoved(id, appliedAt.plus(Duration.ofHours(84)))

        assertTrue(changed)
        assertEquals(PatchState.REMOVED.name, db.doseLogDao().getById(id)!!.patchState)
    }

    @Test
    fun `marking a non patch dose removed is a no-op`() = runTest {
        val tabletId = db.medicationDao().insert(
            MedicationEntity(name = "Progesterone", form = "TABLET"),
        )
        val tabletSchedule = db.scheduleDao().insert(
            ScheduleEntity(
                medicationId = tabletId,
                scheduleType = "FIXED_DAILY",
                timesOfDayJson = """["22:00"]""",
                doseAmount = 1.0,
                doseUnit = "tbl",
                startDateEpochDay = LocalDate.of(2026, 1, 1).toEpochDay(),
            ),
        )
        val id = repository.log(
            occurrence(appliedAt).copy(scheduleId = tabletSchedule, medicationId = tabletId),
            DoseStatus.TAKEN,
            actualTime = appliedAt,
        )

        assertTrue("no patch lifecycle to close", !repository.markPatchRemoved(id))
    }

    @Test
    fun `the current patch is found while it is worn`() = runTest {
        repository.log(occurrence(appliedAt), DoseStatus.TAKEN, actualTime = appliedAt)

        val current = repository.currentPatch(medicationId)

        assertNotNull(current)
        assertEquals(PatchState.APPLIED, current!!.patchState)
    }

    @Test
    fun `a removed patch is no longer current`() = runTest {
        val id = repository.log(occurrence(appliedAt), DoseStatus.TAKEN, actualTime = appliedAt)
        repository.markPatchRemoved(id)

        assertNull(repository.currentPatch(medicationId))
    }

    @Test
    fun `with no patch logged nothing is current`() = runTest {
        assertNull(repository.currentPatch(medicationId))
    }

    @Test
    fun `the wear window follows the schedule interval`() = runTest {
        db.scheduleDao().update(db.scheduleDao().getById(scheduleId)!!.copy(intervalValue = 96.0))

        repository.log(occurrence(appliedAt), DoseStatus.TAKEN, actualTime = appliedAt)

        val stored = db.doseLogDao().getFrom(0L).single()
        assertEquals(appliedAt.plus(Duration.ofHours(96)).toEpochMilli(), stored.wornUntil)
    }
}
