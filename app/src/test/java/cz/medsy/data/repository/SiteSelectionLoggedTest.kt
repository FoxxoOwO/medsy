package cz.medsy.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cz.medsy.data.local.MedsyDatabase
import cz.medsy.data.local.entity.MedicationEntity
import cz.medsy.data.local.entity.ScheduleEntity
import cz.medsy.domain.model.ApplicationSite
import cz.medsy.domain.model.DoseStatus
import cz.medsy.domain.schedule.Occurrence
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The site chosen in the picker has to survive a restart: the whole rotation suggestion
 * is derived from what was stored here. If the column were dropped, every site would look
 * never-used and the app would keep recommending the same spot.
 */
@RunWith(RobolectricTestRunner::class)
class SiteSelectionLoggedTest {

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
            MedicationEntity(name = "Testosterone", form = "INJECTION_IM", strengthUnit = "mg"),
        )
        scheduleId = db.scheduleDao().insert(
            ScheduleEntity(
                medicationId = medicationId,
                scheduleType = "INTERVAL_DAYS",
                intervalValue = 14.0,
                timesOfDayJson = """["08:00"]""",
                doseAmount = 1.0,
                doseUnit = "ml",
                startDateEpochDay = LocalDate.of(2026, 1, 1).toEpochDay(),
                requireSiteRotation = true,
            ),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun occurrence(at: Instant): Occurrence {
        val zoned = at.atZone(ZoneId.systemDefault())
        return Occurrence(
            scheduleId = scheduleId,
            medicationId = medicationId,
            dueAt = at,
            localDate = zoned.toLocalDate(),
            localTime = LocalTime.of(8, 0),
            doseAmount = BigDecimal.ONE,
            doseUnit = "ml",
        )
    }

    private val at: Instant =
        LocalDate.of(2026, 11, 11).atTime(8, 0).atZone(ZoneId.systemDefault()).toInstant()

    @Test
    fun `the site chosen in the picker is stored with the dose`() = runTest {
        repository.log(
            occurrence = occurrence(at),
            status = DoseStatus.TAKEN,
            actualTime = at,
            site = ApplicationSite.LEFT_DELTOID,
        )

        val stored = db.doseLogDao().getFrom(0L).single()
        assertEquals(ApplicationSite.LEFT_DELTOID.name, stored.applicationSite)

        val history = repository.recentSiteHistory(medicationId)
        assertEquals(1, history.size)
        assertEquals(ApplicationSite.LEFT_DELTOID, history.single().first)
        assertEquals(at, history.single().second)
    }

    @Test
    fun `history comes back newest first`() = runTest {
        val older = at.minusSeconds(14L * 24 * 3600)
        repository.log(occurrence(older), DoseStatus.TAKEN, actualTime = older, site = ApplicationSite.RIGHT_VASTUS_LATERALIS)
        repository.log(occurrence(at), DoseStatus.TAKEN, actualTime = at, site = ApplicationSite.LEFT_DELTOID)

        val history = repository.recentSiteHistory(medicationId)

        assertEquals(
            listOf(ApplicationSite.LEFT_DELTOID, ApplicationSite.RIGHT_VASTUS_LATERALIS),
            history.map { it.first },
        )
    }

    @Test
    fun `a dose logged without a site adds no history`() = runTest {
        repository.log(occurrence(at), DoseStatus.TAKEN, actualTime = at)

        assertNull(db.doseLogDao().getFrom(0L).single().applicationSite)
        assertEquals(emptyList<Pair<ApplicationSite, Instant>>(), repository.recentSiteHistory(medicationId))
    }

}
