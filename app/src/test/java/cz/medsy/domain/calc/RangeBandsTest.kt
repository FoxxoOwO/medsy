package cz.medsy.domain.calc

import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.model.ProfileType
import cz.medsy.domain.model.ReferenceRangeProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class RangeBandsTest {

    private fun band(
        type: ProfileType,
        min: String?,
        max: String?,
        code: String = "E2",
    ) = ReferenceRangeProfile(
        biomarkerCode = code,
        profileType = type,
        targetMinSi = min?.let(::BigDecimal),
        targetMaxSi = max?.let(::BigDecimal),
    )

    @Test
    fun `the primary profile comes first`() {
        val bands = RangeBands.build(
            code = "E2",
            primary = ProfileType.HRT_FEM,
            overlays = setOf(ProfileType.CIS_F),
        )

        assertEquals(2, bands.size)
        assertEquals(ProfileType.HRT_FEM, bands.first().profileType)
        assertTrue(bands.first().isPrimary)
    }

    @Test
    fun `an overlay is marked as not primary`() {
        val bands = RangeBands.build(
            code = "E2",
            primary = ProfileType.HRT_FEM,
            overlays = setOf(ProfileType.CIS_F),
        )
        assertEquals(false, bands.last().isPrimary)
    }

    @Test
    fun `no overlays yields only the primary band`() {
        val bands = RangeBands.build(
            code = "E2",
            primary = ProfileType.HRT_FEM,
            overlays = emptySet(),
        )
        assertEquals(1, bands.size)
    }

    @Test
    fun `the primary is never duplicated when also listed as an overlay`() {
        val bands = RangeBands.build(
            code = "E2",
            primary = ProfileType.HRT_FEM,
            overlays = setOf(ProfileType.HRT_FEM, ProfileType.CIS_F),
        )
        assertEquals(2, bands.size)
        assertEquals(1, bands.count { it.profileType == ProfileType.HRT_FEM })
    }

    @Test
    fun `bands are capped so the chart stays readable`() {
        val bands = RangeBands.build(
            code = "E2",
            primary = ProfileType.HRT_FEM,
            overlays = setOf(
                ProfileType.HRT_MASC,
                ProfileType.CIS_F,
                ProfileType.CIS_M,
                ProfileType.CUSTOM,
            ),
        )
        assertTrue(bands.size <= RangeBands.MAX_BANDS)
    }

    @Test
    fun `the primary survives truncation`() {
        val bands = RangeBands.build(
            code = "E2",
            primary = ProfileType.CIS_F,
            overlays = setOf(
                ProfileType.HRT_FEM,
                ProfileType.HRT_MASC,
                ProfileType.CIS_M,
            ),
        )
        assertEquals(ProfileType.CIS_F, bands.first().profileType)
        assertTrue(bands.first().isPrimary)
    }

    @Test
    fun `a custom range is included when stored`() {
        val custom = band(ProfileType.CUSTOM, "400", "600")
        val bands = RangeBands.build(
            code = "E2",
            primary = ProfileType.HRT_FEM,
            overlays = setOf(ProfileType.CUSTOM),
            customRanges = listOf(custom),
        )

        val doctor = bands.first { it.profileType == ProfileType.CUSTOM }
        assertEquals(0, doctor.min!!.compareTo(BigDecimal("400")))
        assertEquals(0, doctor.max!!.compareTo(BigDecimal("600")))
    }

    @Test
    fun `a custom profile without a stored range is skipped`() {
        val bands = RangeBands.build(
            code = "E2",
            primary = ProfileType.HRT_FEM,
            overlays = setOf(ProfileType.CUSTOM),
        )
        assertTrue(bands.none { it.profileType == ProfileType.CUSTOM })
    }

    @Test
    fun `the lab printed range is used when supplied`() {
        val printed = band(ProfileType.LAB_PRINTED, "367", "734")
        val bands = RangeBands.build(
            code = "E2",
            primary = ProfileType.LAB_PRINTED,
            overlays = emptySet(),
            labPrinted = printed,
        )
        assertEquals(1, bands.size)
        assertEquals(0, bands.first().min!!.compareTo(BigDecimal("367")))
    }

    @Test
    fun `a missing lab printed range yields no band`() {
        val bands = RangeBands.build(
            code = "E2",
            primary = ProfileType.LAB_PRINTED,
            overlays = emptySet(),
            labPrinted = null,
        )
        assertTrue(bands.isEmpty())
    }

    @Test
    fun `a profile without a known range is skipped`() {
        val bands = RangeBands.build(
            code = "NOT_A_BIOMARKER",
            primary = ProfileType.HRT_FEM,
            overlays = setOf(ProfileType.CIS_F),
        )
        assertTrue(bands.isEmpty())
    }

    @Test
    fun `every profile maps to its own label key`() {
        assertEquals(MessageKey.PROFILE_HRT_FEMINIZING, RangeBands.label(ProfileType.HRT_FEM).key)
        assertEquals(MessageKey.PROFILE_DOCTOR_TARGET, RangeBands.label(ProfileType.CUSTOM).key)
        assertEquals(MessageKey.PROFILE_LAB_PRINTED, RangeBands.label(ProfileType.LAB_PRINTED).key)
        // Distinct keys: two profiles sharing one label would be indistinguishable on the chart.
        val keys = ProfileType.entries.map { RangeBands.label(it).key }
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun `an open ended band keeps its null bound`() {
        val custom = band(ProfileType.CUSTOM, "500", null)
        val bands = RangeBands.build(
            code = "E2",
            primary = ProfileType.HRT_FEM,
            overlays = setOf(ProfileType.CUSTOM),
            customRanges = listOf(custom),
        )
        val doctor = bands.first { it.profileType == ProfileType.CUSTOM }
        assertEquals(0, doctor.min!!.compareTo(BigDecimal("500")))
        assertNull(doctor.max)
    }
}
