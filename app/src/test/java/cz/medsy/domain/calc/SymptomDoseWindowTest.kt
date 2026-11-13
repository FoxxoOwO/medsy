package cz.medsy.domain.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Everything here is stored as an instant, so the window must be a pure duration:
 * whether the phone sits in Prague or Auckland, the same dose and the same symptom
 * have to land in the same bucket.
 */
class SymptomDoseWindowTest {

    private val doseAt = Instant.parse("2026-11-01T08:00:00Z")
    private val dose = DoseEvent(doseAt)

    @Test
    fun `the window boundary is inclusive`() {
        val exactly = SymptomPoint(doseAt.plus(Duration.ofHours(2)), "HEADACHE", 5)
        val justOutside = SymptomPoint(doseAt.plus(Duration.ofHours(2)).plusMillis(1), "HEADACHE", 5)

        assertNotNull(SymptomCorrelationCalculator.nearestDose(exactly.at, listOf(dose)))
        assertNull(SymptomCorrelationCalculator.nearestDose(justOutside.at, listOf(dose)))
    }

    @Test
    fun `the window also looks backwards, before the dose`() {
        val before = SymptomPoint(doseAt.minus(Duration.ofMinutes(90)), "HEADACHE", 4)

        assertNotNull(SymptomCorrelationCalculator.nearestDose(before.at, listOf(dose)))
    }

    @Test
    fun `the nearest dose wins when two are in range`() {
        val earlier = DoseEvent(doseAt.minus(Duration.ofHours(1)))
        val later = DoseEvent(doseAt.plus(Duration.ofHours(1)))
        val point = SymptomPoint(doseAt.plus(Duration.ofMinutes(10)), "HEADACHE", 3)

        val nearest = SymptomCorrelationCalculator.nearestDose(point.at, listOf(later, earlier))

        assertEquals(later.at, nearest?.at)
    }

    @Test
    fun `a custom window moves the boundary`() {
        val point = SymptomPoint(doseAt.plus(Duration.ofHours(3)), "HEADACHE", 3)

        assertNull(SymptomCorrelationCalculator.nearestDose(point.at, listOf(dose)))
        assertNotNull(
            SymptomCorrelationCalculator.nearestDose(
                point.at,
                listOf(dose),
                window = Duration.ofHours(4),
            ),
        )
    }

    @Test
    fun `only the absolute instant matters, not the display zone`() {
        val at = doseAt.plus(Duration.ofMinutes(45))

        // The same moment rendered in two zones is still the same moment; the analyser
        // never sees a zone at all, so the bucket cannot depend on the phone's setting.
        val asPrague = at.atZone(ZoneId.of("Europe/Prague")).toInstant()
        val asAuckland = at.atZone(ZoneId.of("Pacific/Auckland")).toInstant()

        assertEquals(at, asPrague)
        assertEquals(at, asAuckland)
        assertNotNull(SymptomCorrelationCalculator.nearestDose(asPrague, listOf(dose)))
        assertNotNull(SymptomCorrelationCalculator.nearestDose(asAuckland, listOf(dose)))
    }
}
