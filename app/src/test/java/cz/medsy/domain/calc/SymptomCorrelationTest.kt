package cz.medsy.domain.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * The pattern card is a clinical statement about the user's own body, so the numbers
 * behind it have to be conservative: weak or one-sided data is never reported.
 */
class SymptomCorrelationTest {

    private val base = Instant.parse("2026-11-01T08:00:00Z")

    private fun point(minutesAfterDose: Long, severity: Int, code: String = "HEADACHE") = SymptomPoint(
        at = base.plus(Duration.ofMinutes(minutesAfterDose)),
        symptomCode = code,
        severity = severity,
    )

    private val dose = DoseEvent(base)

    @Test
    fun `severity is higher right after a dose`() {
        val points = listOf(
            point(30, 5), point(60, 4), point(90, 5),          // near the dose
            point(600, 2), point(900, 1), point(1200, 2),      // hours away
        )

        val result = SymptomCorrelationCalculator.analyze(points, listOf(dose)).single()

        assertEquals("HEADACHE", result.symptomCode)
        assertEquals(3, result.inWindowCount)
        assertEquals(3, result.outOfWindowCount)
        assertEquals(4.666, result.inWindowMean, 0.001)
        assertEquals(1.666, result.outOfWindowMean, 0.001)
        assertTrue(result.isReportable)
        assertTrue(result.isWorseNearDose)
        assertEquals(2.8, result.ratio!!, 0.001)
    }

    @Test
    fun `no data produces no findings and no division by zero`() {
        assertTrue(SymptomCorrelationCalculator.analyze(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `a single record is not reported as a finding`() {
        val points = listOf(point(30, 5))

        val result = SymptomCorrelationCalculator.analyze(points, listOf(dose)).single()

        assertEquals(1, result.inWindowCount)
        assertEquals(0, result.outOfWindowCount)
        assertFalse("one entry must not become a pattern", result.isReportable)
        assertNull(result.ratio)
        assertEquals(0.0, result.outOfWindowMean, 0.0)
    }

    @Test
    fun `symptoms with no doses at all stay out of the window group`() {
        val points = listOf(point(600, 3), point(900, 3), point(1200, 3))

        val result = SymptomCorrelationCalculator.analyze(points, emptyList()).single()

        assertEquals(0, result.inWindowCount)
        assertEquals(3, result.outOfWindowCount)
        assertFalse(result.isReportable)
    }

    @Test
    fun `stronger patterns come first, weak ones last`() {
        val points = listOf(
            point(30, 5, "HEADACHE"), point(60, 5, "HEADACHE"), point(90, 5, "HEADACHE"),
            point(600, 1, "HEADACHE"), point(900, 1, "HEADACHE"), point(1200, 1, "HEADACHE"),
            point(30, 3, "ACNE"), point(60, 3, "ACNE"), point(90, 3, "ACNE"),
            point(600, 3, "ACNE"), point(900, 3, "ACNE"), point(1200, 3, "ACNE"),
        )

        val result = SymptomCorrelationCalculator.analyze(points, listOf(dose))

        assertEquals(listOf("HEADACHE", "ACNE"), result.map { it.symptomCode })
        assertFalse("a flat symptom is not a pattern", result[1].isWorseNearDose)
        assertFalse(result[1].isMilderNearDose)
    }

    @Test
    fun `a milder symptom near a dose is also reported`() {
        val points = listOf(
            point(30, 1), point(60, 1), point(90, 2),
            point(600, 4), point(900, 5), point(1200, 4),
        )

        val result = SymptomCorrelationCalculator.analyze(points, listOf(dose)).single()

        assertTrue(result.isReportable)
        assertTrue(result.isMilderNearDose)
        assertFalse(result.isWorseNearDose)
    }
    @Test
    fun `only doses that were actually taken count`() {
        val taken = cz.medsy.domain.model.DoseLog(
            medicationId = 1, scheduleId = 1,
            scheduledTimestamp = base, actualTimestamp = base.plus(Duration.ofMinutes(20)),
            status = cz.medsy.domain.model.DoseStatus.TAKEN,
            actualDoseAmount = null, actualVolumeMl = null, applicationSite = null,
        )
        val skipped = taken.copy(status = cz.medsy.domain.model.DoseStatus.SKIPPED)
        val snoozed = taken.copy(status = cz.medsy.domain.model.DoseStatus.SNOOZED)

        val events = cz.medsy.domain.calc.takenDoseEvents(listOf(taken, skipped, snoozed))

        assertEquals(1, events.size)
        assertEquals("an injected dose is timed by when it went in", taken.actualTimestamp, events.single().at)
    }

    @Test
    fun `a taken dose without an actual time falls back to the scheduled time`() {
        val taken = cz.medsy.domain.model.DoseLog(
            medicationId = 1, scheduleId = 1,
            scheduledTimestamp = base, actualTimestamp = null,
            status = cz.medsy.domain.model.DoseStatus.TAKEN,
            actualDoseAmount = null, actualVolumeMl = null, applicationSite = null,
        )

        assertEquals(base, cz.medsy.domain.calc.takenDoseEvents(listOf(taken)).single().at)
    }
    @Test
    fun `two entries on each side are still too few`() {
        val points = listOf(
            point(30, 5), point(60, 5),
            point(600, 1), point(900, 1),
        )

        val result = SymptomCorrelationCalculator.analyze(points, listOf(dose)).single()

        assertEquals(2, result.inWindowCount)
        assertEquals(2, result.outOfWindowCount)
        assertFalse(result.isReportable)
    }
}
