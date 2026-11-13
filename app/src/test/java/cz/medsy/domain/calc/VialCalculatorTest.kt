package cz.medsy.domain.calc

import cz.medsy.domain.model.ApplicationSite
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.model.MedicationForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

class VialCalculatorTest {

    @Test
    fun `five mg of fifty mg per ml is 0_10 ml`() {
        val volume = VialCalculator.doseToVolumeMl(BigDecimal("5"), BigDecimal("50"))
        assertEquals(0, volume.compareTo(BigDecimal("0.100")))
    }

    @Test
    fun `fractional concentration is not lost to floating point`() {
        // 3.5 mg at 20 mg/mL = 0.175 mL exactly
        val volume = VialCalculator.doseToVolumeMl(BigDecimal("3.5"), BigDecimal("20"))
        assertEquals(0, volume.compareTo(BigDecimal("0.175")))
    }

    @Test
    fun `volume rounds to syringe graduation of 0_001 ml`() {
        val volume = VialCalculator.doseToVolumeMl(BigDecimal("1"), BigDecimal("3"))
        assertEquals(3, volume.scale())
        assertEquals(0, volume.compareTo(BigDecimal("0.333")))
    }

    @Test
    fun `dead space is added to the deduction but not the delivered dose`() {
        val result = VialCalculator.calculate(
            doseMg = BigDecimal("5"),
            concentrationMgPerMl = BigDecimal("50"),
            deadSpaceMl = VialCalculator.STANDARD_LUER_DEAD_SPACE,
        )
        assertEquals(0, result.volumeMl.compareTo(BigDecimal("0.100")))
        assertEquals(0, result.deductVolumeMl.compareTo(BigDecimal("0.150")))
        assertEquals(0, result.deadSpaceMl.compareTo(BigDecimal("0.05")))
    }

    @Test
    fun `low dead space syringe wastes less`() {
        val standard = VialCalculator.calculate(
            BigDecimal("5"), BigDecimal("50"), VialCalculator.STANDARD_LUER_DEAD_SPACE,
        )
        val low = VialCalculator.calculate(
            BigDecimal("5"), BigDecimal("50"), VialCalculator.LOW_DEAD_SPACE_INSULIN,
        )
        assertTrue(low.deductVolumeMl < standard.deductVolumeMl)
    }

    @Test
    fun `remaining doses floors so partial doses are never promised`() {
        // 2.0 mL vial, 0.1 mL dose + 0.05 dead space = 0.15 per draw -> 13 whole doses
        val doses = VialCalculator.remainingDoses(
            remainingMl = BigDecimal("2.0"),
            doseMg = BigDecimal("5"),
            concentrationMgPerMl = BigDecimal("50"),
            deadSpaceMl = VialCalculator.STANDARD_LUER_DEAD_SPACE,
        )
        assertEquals(13, doses)
    }

    @Test
    fun `empty vial yields zero doses`() {
        val doses = VialCalculator.remainingDoses(
            BigDecimal.ZERO, BigDecimal("5"), BigDecimal("50"), VialCalculator.STANDARD_LUER_DEAD_SPACE,
        )
        assertEquals(0, doses)
    }

    @Test
    fun `volume to dose round trips`() {
        val dose = VialCalculator.volumeToDoseMg(BigDecimal("0.100"), BigDecimal("50"))
        assertEquals(0, dose.compareTo(BigDecimal("5.000")))
    }

    @Test
    fun `insulin units convert to volume`() {
        val volume = VialCalculator.unitsToVolumeMl(BigDecimal("50"), BigDecimal("100"))
        assertEquals(0, volume.compareTo(BigDecimal("0.500")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero concentration is rejected`() {
        VialCalculator.doseToVolumeMl(BigDecimal("5"), BigDecimal.ZERO)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative dose is rejected`() {
        VialCalculator.doseToVolumeMl(BigDecimal("-5"), BigDecimal("50"))
    }
}

class SiteRotationTest {

    private val now = Instant.parse("2026-11-11T12:00:00Z")

    @Test
    fun `site rotation never returns a site outside the form group`() {
        val recommendation = SiteRotation.recommend(MedicationForm.INJECTION_IM, emptyList(), now)
        assertNotNull(recommendation)
        assertTrue(recommendation!!.site in ApplicationSite.forForm(MedicationForm.INJECTION_IM))
    }

    @Test
    fun `prefers a never used site`() {
        val history = listOf(
            ApplicationSite.LEFT_VASTUS_LATERALIS to now.minus(Duration.ofDays(7)),
            ApplicationSite.RIGHT_VASTUS_LATERALIS to now.minus(Duration.ofDays(6)),
        )
        val recommendation = SiteRotation.recommend(MedicationForm.INJECTION_IM, history, now)
        assertNotNull(recommendation)
        assertTrue(recommendation!!.site !in setOf(
            ApplicationSite.LEFT_VASTUS_LATERALIS,
            ApplicationSite.RIGHT_VASTUS_LATERALIS,
        ))
    }

    @Test
    fun `picks least recently used when every site was used`() {
        val oldest = now.minus(Duration.ofDays(30))
        val sites = ApplicationSite.forForm(MedicationForm.INJECTION_SUBQ)
        val history = sites.filter { it != ApplicationSite.ABDOMEN_UPPER_LEFT }
            .mapIndexed { index, site ->
                site to now.minus(Duration.ofDays(index.toLong() + 1L))
            } + (ApplicationSite.ABDOMEN_UPPER_LEFT to oldest)
        val recommendation = SiteRotation.recommend(MedicationForm.INJECTION_SUBQ, history, now)
        assertEquals(ApplicationSite.ABDOMEN_UPPER_LEFT, recommendation!!.site)
    }

    @Test
    fun `recent use within 24h is flagged in the reason`() {
        val history = ApplicationSite.forForm(MedicationForm.PATCH).map { site ->
            site to now.minus(Duration.ofHours(2))
        }
        val recommendation = SiteRotation.recommend(MedicationForm.PATCH, history, now)
        assertNotNull(recommendation)
        assertEquals(MessageKey.VIAL_ROTATE_FURTHER, recommendation!!.reason.key)
    }

    @Test
    fun `non injectable form returns null`() {
        assertEquals(null, SiteRotation.recommend(MedicationForm.TABLET, emptyList(), now))
    }

    @Test
    fun `subcutaneous sites do not include intramuscular ones`() {
        val subq = ApplicationSite.forForm(MedicationForm.INJECTION_SUBQ).toSet()
        assertTrue(ApplicationSite.LEFT_VASTUS_LATERALIS !in subq)
    }
}
