package cz.medsy.domain.calc

import cz.medsy.domain.model.ProfileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * A doctor target is typed by hand, so the risks are unit mix-ups and inverted bounds.
 * A window that is silently wrong is worse than a rejected input: the chart draws it
 * and the patient trusts it.
 */
class CustomTargetValidatorTest {

    private fun validate(min: String, max: String, code: String = "E2") =
        CustomTargetValidator.validate(code, min, max)

    private fun errorsOf(min: String, max: String, code: String = "E2"): List<String> {
        val result = validate(min, max, code)
        assertTrue("expected rejection of $min–$max", result is CustomTargetValidator.Result.Invalid)
        return (result as CustomTargetValidator.Result.Invalid).errors
    }

    private fun validOf(min: String, max: String, code: String = "E2") =
        (validate(min, max, code) as CustomTargetValidator.Result.Valid)

    // --- accepted ---------------------------------------------------------------

    @Test
    fun `a realistic estradiol window is accepted without warnings`() {
        val result = validOf("400", "600")

        assertEquals(0, result.range.targetMinSi!!.compareTo(BigDecimal("400")))
        assertEquals(0, result.range.targetMaxSi!!.compareTo(BigDecimal("600")))
        assertEquals(ProfileType.CUSTOM, result.range.profileType)
        assertTrue("no warnings expected: ${result.warnings}", result.warnings.isEmpty())
    }

    @Test
    fun `decimal comma is accepted`() {
        val result = validOf("10,4", "34,7")
        assertEquals(0, result.range.targetMinSi!!.compareTo(BigDecimal("10.4")))
        assertEquals(0, result.range.targetMaxSi!!.compareTo(BigDecimal("34.7")))
    }

    @Test
    fun `only a minimum is allowed`() {
        val result = validOf("367", "")
        assertEquals(0, result.range.targetMinSi!!.compareTo(BigDecimal("367")))
        assertEquals(null, result.range.targetMaxSi)
    }

    @Test
    fun `only a maximum is allowed`() {
        val result = validOf("", "734")
        assertEquals(null, result.range.targetMinSi)
        assertEquals(0, result.range.targetMaxSi!!.compareTo(BigDecimal("734")))
    }

    @Test
    fun `the result is a CUSTOM profile`() {
        assertEquals(ProfileType.CUSTOM, validOf("100", "200").range.profileType)
    }

    // --- rejected ---------------------------------------------------------------

    @Test
    fun `empty input is rejected`() {
        assertTrue(errorsOf("", "").any { it.contains("at least one") })
    }

    @Test
    fun `inverted bounds are rejected`() {
        assertTrue(errorsOf("600", "400").any { it.contains("lower than maximum") })
    }

    @Test
    fun `equal bounds are rejected`() {
        assertTrue(errorsOf("500", "500").any { it.contains("lower than maximum") })
    }

    @Test
    fun `zero is rejected`() {
        assertTrue(errorsOf("0", "100").isNotEmpty())
    }

    @Test
    fun `negative value is rejected`() {
        assertTrue(errorsOf("-5", "100").isNotEmpty())
    }

    @Test
    fun `non numeric input is rejected`() {
        assertTrue(errorsOf("abc", "100").any { it.contains("number") })
    }

    @Test
    fun `unknown biomarker is rejected`() {
        assertTrue(errorsOf("1", "2", code = "NOT_A_THING").isNotEmpty())
    }

    // --- unit mix-up warnings ---------------------------------------------------

    @Test
    fun `typing pg per ml into an SI field is flagged`() {
        // 100 is plausible as pg/mL but absurd as pmol/L for estradiol.
        val result = validOf("100", "200")
        assertTrue(
            "a 3.7x-low window should warn: ${result.warnings}",
            result.warnings.any { it.contains("pg/mL") || it.contains("unusual") },
        )
    }

    @Test
    fun `typing ng per dl for testosterone is flagged`() {
        // 300–1000 ng/dL stored as nmol/L would be ~30x too high.
        val result = validOf("300", "1000", code = "TEST_TOTAL")
        assertTrue(result.warnings.any { it.contains("unusual") })
    }

    @Test
    fun `a plausible masculinizing window produces no warning`() {
        val result = validOf("10.4", "34.7", code = "TEST_TOTAL")
        assertTrue("expected no warnings: ${result.warnings}", result.warnings.isEmpty())
    }

    @Test
    fun `warnings are not duplicated`() {
        val result = validOf("10000", "20000")
        assertEquals(result.warnings.size, result.warnings.distinct().size)
    }

    // --- conversion helper ------------------------------------------------------

    @Test
    fun `conventional units convert to SI`() {
        val si = CustomTargetValidator.toSi("E2", BigDecimal("100"), "pg/mL")
        assertEquals(0, si!!.setScale(0, java.math.RoundingMode.HALF_UP).compareTo(BigDecimal("367")))
    }

    @Test
    fun `SI input is left unchanged`() {
        val si = CustomTargetValidator.toSi("E2", BigDecimal("367"), "pmol/L")
        assertEquals(0, si!!.compareTo(BigDecimal("367")))
    }

    @Test
    fun `an unknown unit returns null rather than a wrong number`() {
        assertEquals(null, CustomTargetValidator.toSi("E2", BigDecimal("1"), "furlongs"))
    }
}
