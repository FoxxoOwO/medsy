package cz.medsy.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cz.medsy.data.local.MedsyDatabase
import cz.medsy.data.local.entity.MedicationEntity
import cz.medsy.data.local.entity.ScheduleEntity
import cz.medsy.domain.model.DoseStatus
import cz.medsy.domain.schedule.Occurrence
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The PRN ceiling must hold at the persistence boundary, not only in a view model: the
 * notification action and the dashboard both go through [DoseRepository.log].
 */
@RunWith(RobolectricTestRunner::class)
class PrnLimitRepositoryTest {

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
            MedicationEntity(name = "Ibuprofen", form = "TABLET", strengthUnit = "tbl"),
        )
        scheduleId = db.scheduleDao().insert(
            ScheduleEntity(
                medicationId = medicationId,
                scheduleType = "PRN",
                timesOfDayJson = "[]",
                doseAmount = 2.0,
                doseUnit = "tbl",
                maxDailyDose = 4.0,
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
            localTime = LocalTime.of(12, 0),
            doseAmount = BigDecimal("2"),
            doseUnit = "tbl",
        )
    }

    private suspend fun takeTwo(at: Instant) =
        repository.log(occurrence(at), DoseStatus.TAKEN, actualTime = at)

    @Test
    fun `doses up to the daily limit are accepted`() = runTest {
        val noon = LocalDate.of(2026, 11, 11).atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant()

        takeTwo(noon)
        takeTwo(noon.plusSeconds(3600))

        assertEquals(2, db.doseLogDao().getFrom(0L).size)
    }

    @Test
    fun `the dose beyond the limit is rejected`() = runTest {
        val noon = LocalDate.of(2026, 11, 11).atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant()
        takeTwo(noon)
        takeTwo(noon.plusSeconds(3600))

        val error = runCatching { takeTwo(noon.plusSeconds(7200)) }.exceptionOrNull()

        assertNotNull("the third 2 tbl dose exceeds a 4 tbl limit", error)
        assertTrue(error is PrnLimitExceeded)
        assertEquals("nothing beyond the limit may be written", 2, db.doseLogDao().getFrom(0L).size)
    }

    @Test
    fun `an explicit override records the dose`() = runTest {
        val noon = LocalDate.of(2026, 11, 11).atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant()
        takeTwo(noon)
        takeTwo(noon.plusSeconds(3600))

        repository.log(
            occurrence(noon.plusSeconds(7200)),
            DoseStatus.TAKEN,
            actualTime = noon.plusSeconds(7200),
            allowOverLimit = true,
        )

        assertEquals(3, db.doseLogDao().getFrom(0L).size)
    }

    @Test
    fun `skipping never trips the limit`() = runTest {
        val noon = LocalDate.of(2026, 11, 11).atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant()
        takeTwo(noon)
        takeTwo(noon.plusSeconds(3600))

        repository.log(occurrence(noon.plusSeconds(7200)), DoseStatus.SKIPPED)

        assertEquals(3, db.doseLogDao().getFrom(0L).size)
    }

    @Test
    fun `yesterday's doses do not count against today`() = runTest {
        val yesterday = LocalDate.of(2026, 11, 10).atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant()
        takeTwo(yesterday)
        takeTwo(yesterday.plusSeconds(3600))

        val today = yesterday.plusSeconds(86_400)
        val error = runCatching { takeTwo(today) }.exceptionOrNull()

        assertEquals(null, error)
    }

    @Test
    fun `a schedule without a limit is unconstrained`() = runTest {
        db.scheduleDao().update(
            db.scheduleDao().getById(scheduleId)!!.copy(maxDailyDose = null),
        )
        val noon = LocalDate.of(2026, 11, 11).atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant()

        repeat(5) { takeTwo(noon.plusSeconds(it * 600L)) }

        assertEquals(5, db.doseLogDao().getFrom(0L).size)
    }

    @Test
    fun `a non PRN schedule is not limited`() = runTest {
        db.scheduleDao().update(
            db.scheduleDao().getById(scheduleId)!!.copy(scheduleType = "FIXED_DAILY"),
        )
        val noon = LocalDate.of(2026, 11, 11).atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant()

        repeat(4) { takeTwo(noon.plusSeconds(it * 600L)) }

        assertEquals(4, db.doseLogDao().getFrom(0L).size)
    }

    @Test
    fun `the violation message names the projected total`() = runTest {
        val noon = LocalDate.of(2026, 11, 11).atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant()
        takeTwo(noon)
        takeTwo(noon.plusSeconds(3600))

        val error = runCatching { takeTwo(noon.plusSeconds(7200)) }
            .exceptionOrNull() as PrnLimitExceeded

        assertTrue(error.message.orEmpty().contains("6"))
        assertTrue(error.message.orEmpty().contains("4"))
    }
}
