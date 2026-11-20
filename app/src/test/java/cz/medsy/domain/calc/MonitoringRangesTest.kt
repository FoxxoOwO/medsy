package cz.medsy.domain.calc

import cz.medsy.domain.model.ProfileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/**
 * Prolactin and the liver panel used to have a range only for some profiles, so on HRT the
 * chart drew the values with no target band at all — a line the user could not judge.
 */
class MonitoringRangesTest {

    private val liver = listOf("ALT", "AST", "GGT", "ALP", "BILI")

    private val chartableProfiles = listOf(
        ProfileType.HRT_FEM,
        ProfileType.HRT_MASC,
        ProfileType.CIS_F,
        ProfileType.CIS_M,
    )

    @Test
    fun `liver panel has a range on every profile`() {
        chartableProfiles.forEach { profile ->
            liver.forEach { code ->
                assertNotNull("$code has no range for $profile", ReferenceRanges.forBiomarker(code, profile))
            }
        }
    }

    @Test
    fun `prolactin has a range on every profile`() {
        chartableProfiles.forEach { profile ->
            assertNotNull("PRL has no range for $profile", ReferenceRanges.forBiomarker("PRL", profile))
        }
    }

    @Test
    fun `liver bounds follow the sex typical upper limit`() {
        val altF = ReferenceRanges.forBiomarker("ALT", ProfileType.HRT_FEM)
        val altM = ReferenceRanges.forBiomarker("ALT", ProfileType.HRT_MASC)

        assertEquals(0, altF!!.targetMaxSi!!.compareTo(BigDecimal("0.58")))
        assertEquals(0, altM!!.targetMaxSi!!.compareTo(BigDecimal("0.83")))
        // LAB_PRINTED is built per value from the report itself, never from defaults.
        assertNull(ReferenceRanges.forBiomarker("ALT", ProfileType.LAB_PRINTED))
    }
}
