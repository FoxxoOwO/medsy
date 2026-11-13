package cz.medsy.report

import cz.medsy.domain.model.AdherenceSummary
import cz.medsy.domain.model.BiomarkerValue
import cz.medsy.domain.model.BloodTestSession
import cz.medsy.domain.model.ChangeType
import cz.medsy.domain.model.Medication
import cz.medsy.domain.model.MedicationForm
import cz.medsy.domain.model.MedicationMilestone
import cz.medsy.domain.model.Schedule
import cz.medsy.domain.model.ScheduleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The PDF itself cannot be rendered under Robolectric ("document is closed!"), so the
 * content model is tested instead: deltas, both adherence windows, milestone text and
 * the truncation that keeps everything on one page.
 */
class ReportContentTest {

    private val zone = ZoneId.of("Europe/Prague")
    private val now = Instant.parse("2026-11-11T07:00:00Z")

    private fun medication(id: Long = 1, name: String = "Estradiol Enanthate") = Medication(
        id = id,
        name = name,
        form = MedicationForm.INJECTION_IM,
        strengthValue = BigDecimal("5"),
        strengthUnit = "mg",
    )

    private fun schedule(medicationId: Long = 1) = Schedule(
        id = 1,
        medicationId = medicationId,
        scheduleType = ScheduleType.INTERVAL_DAYS,
        intervalValue = BigDecimal("3.5"),
        timesOfDay = listOf(LocalTime.of(8, 0)),
        doseAmount = BigDecimal("5"),
        doseUnit = "mg",
        startDate = LocalDate.of(2026, 1, 1),
    )

    private fun session(id: Long, at: Instant, trough: Boolean = false) = BloodTestSession(
        id = id,
        timestamp = at,
        isTroughLevel = trough,
    )

    private fun value(sessionId: Long, code: String = "E2", si: String = "500") = BiomarkerValue(
        sessionId = sessionId,
        biomarkerCode = code,
        rawValue = BigDecimal(si),
        rawUnit = "pmol/L",
        normalizedSiValue = BigDecimal(si),
        normalizedSiUnit = "pmol/L",
    )

    private fun build(
        medications: List<Medication> = listOf(medication()),
        schedules: List<Schedule> = listOf(schedule()),
        sessions: List<BloodTestSession> = emptyList(),
        values: List<BiomarkerValue> = emptyList(),
        milestones: List<MedicationMilestone> = emptyList(),
        adherence30: AdherenceSummary = AdherenceSummary(28, 1, 1),
        adherence90: AdherenceSummary = AdherenceSummary(80, 4, 6),
    ) = ReportContent.build(
        medications = medications,
        schedules = schedules,
        adherence30 = adherence30,
        adherence90 = adherence90,
        milestones = milestones,
        sessions = sessions,
        values = values,
        flagFor = { "within target" },
        zone = zone,
    )

    @Test
    fun `medication line includes strength and schedule`() {
        val content = build()

        assertEquals(1, content.medications.size)
        assertEquals("Estradiol Enanthate 5 mg", content.medications.single().name)
        assertTrue(content.medications.single().schedule.contains("every 3.5 d"))
        assertTrue(content.medications.single().schedule.contains("08:00"))
    }

    @Test
    fun `medication without a schedule says so`() {
        val content = build(schedules = emptyList())
        assertEquals("no active schedule", content.medications.single().schedule)
    }

    @Test
    fun `both adherence windows are reported`() {
        val content = build()

        assertEquals(2, content.adherence.size)
        assertEquals(30, content.adherence[0].days)
        assertEquals(90, content.adherence[1].days)
        assertEquals(93, content.adherence[0].percent)
        assertEquals(80, content.adherence[1].taken)
    }

    @Test
    fun `delta is signed and relative to the previous draw`() {
        val older = session(1, now.minusSeconds(90L * 86_400))
        val newer = session(2, now)

        val content = build(
            sessions = listOf(newer, older),
            values = listOf(value(1, si = "400"), value(2, si = "600")),
        )

        val newestBlock = content.labs.first()
        assertEquals("+200", newestBlock.rows.single().delta)

        val oldestBlock = content.labs.last()
        assertNull("the first draw has nothing to compare against", oldestBlock.rows.single().delta)
    }

    @Test
    fun `negative delta keeps its sign`() {
        val older = session(1, now.minusSeconds(90L * 86_400))
        val newer = session(2, now)

        val content = build(
            sessions = listOf(newer, older),
            values = listOf(value(1, si = "700"), value(2, si = "500")),
        )

        assertEquals("-200", content.labs.first().rows.single().delta)
    }

    @Test
    fun `labs are ordered newest first`() {
        val older = session(1, now.minusSeconds(90L * 86_400))
        val newer = session(2, now)

        val content = build(
            sessions = listOf(older, newer),
            values = listOf(value(1), value(2)),
        )

        assertTrue(content.labs.first().dateLabel.startsWith("11"))
        assertTrue(content.labs.last().dateLabel.startsWith("13"))
    }

    @Test
    fun `trough flag is carried into the block`() {
        val content = build(
            sessions = listOf(session(1, now, trough = true)),
            values = listOf(value(1)),
        )
        assertTrue(content.labs.single().isTrough)
    }

    @Test
    fun `milestones are newest first and limited`() {
        val milestones = (1..10).map { index ->
            MedicationMilestone(
                medicationId = 1,
                timestamp = now.minusSeconds(index.toLong() * 86_400),
                changeType = ChangeType.DOSE_CHANGED,
                description = "change $index",
            )
        }

        val content = build(milestones = milestones)

        assertEquals(ReportContent.MAX_MILESTONES, content.milestones.size)
        assertTrue(content.milestones.first().text.contains("change 1"))
    }

    @Test
    fun `row count per session is capped`() {
        val codes = listOf("E2", "TEST_TOTAL", "PRL", "SHBG", "LH", "FSH", "HCT", "HGB", "ALT", "AST", "K", "NA")
        val content = build(
            sessions = listOf(session(1, now)),
            values = codes.mapIndexed { index, code -> value(1, code = code, si = "${100 + index}") },
        )

        assertEquals(ReportContent.MAX_ROWS_PER_SESSION, content.labs.single().rows.size)
    }

    @Test
    fun `session count is capped and truncation is reported`() {
        val sessions = (1..5).map { index ->
            session(index.toLong(), now.minusSeconds(index.toLong() * 86_400))
        }
        val values = sessions.map { value(it.id) }

        val content = build(sessions = sessions, values = values)

        assertEquals(ReportContent.MAX_SESSIONS, content.labs.size)
        assertTrue(content.truncated)
    }

    @Test
    fun `a short report is not marked truncated`() {
        val content = build(sessions = listOf(session(1, now)), values = listOf(value(1)))
        assertEquals(false, content.truncated)
    }

    @Test
    fun `empty data still produces a renderable document`() {
        val content = ReportContent.build(
            medications = emptyList(),
            schedules = emptyList(),
            adherence30 = AdherenceSummary(0, 0, 0),
            adherence90 = AdherenceSummary(0, 0, 0),
            milestones = emptyList(),
            sessions = emptyList(),
            values = emptyList(),
            flagFor = { "no range" },
        )

        assertTrue(content.medications.isEmpty())
        assertTrue(content.labs.isEmpty())
        assertEquals(2, content.adherence.size)
    }

    @Test
    fun `delta helper returns null without a previous value`() {
        assertNull(ReportContent.deltaTo(null, BigDecimal("5")))
        assertEquals("0", ReportContent.deltaTo(BigDecimal("5"), BigDecimal("5")))
        assertEquals("-1.5", ReportContent.deltaTo(BigDecimal("2.5"), BigDecimal("1")))
    }
}
