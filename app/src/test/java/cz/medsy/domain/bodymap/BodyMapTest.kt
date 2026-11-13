package cz.medsy.domain.bodymap

import cz.medsy.domain.model.ApplicationSite
import cz.medsy.domain.model.MedicationForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * The body map is a safety feature: a mirrored drawing would send the patient back to
 * the side they just used. Geometry is therefore asserted as data.
 */
class BodyMapTest {

    private val now = Instant.parse("2026-11-11T12:00:00Z")

    // --- coverage --------------------------------------------------------------

    @Test
    fun `every application site can be drawn`() {
        assertTrue(
            "sites missing from the map: ${BodyMap.sitesWithoutPlacement()}",
            BodyMap.sitesWithoutPlacement().isEmpty(),
        )
    }

    @Test
    fun `every placement has coordinates inside the canvas`() {
        ApplicationSite.entries.forEach { site ->
            val placement = BodyMap.placement(site)!!
            assertTrue("$site x out of range: ${placement.x}", placement.x in 0f..1f)
            assertTrue("$site y out of range: ${placement.y}", placement.y in 0f..1f)
        }
    }

    // --- left / right ----------------------------------------------------------

    @Test
    fun `the patient's left sites are drawn on the right of a facing body`() {
        val left = BodyMap.placement(ApplicationSite.LEFT_DELTOID)!!
        val right = BodyMap.placement(ApplicationSite.RIGHT_DELTOID)!!

        assertTrue("left must appear right of centre", left.x > 0.5f)
        assertTrue("right must appear left of centre", right.x < 0.5f)
        assertEquals(BodyMap.Side.LEFT, left.side)
        assertEquals(BodyMap.Side.RIGHT, right.side)
    }

    @Test
    fun `every left and right pair is mirrored across the centre`() {
        val pairs = listOf(
            ApplicationSite.LEFT_DELTOID to ApplicationSite.RIGHT_DELTOID,
            ApplicationSite.LEFT_VASTUS_LATERALIS to ApplicationSite.RIGHT_VASTUS_LATERALIS,
            ApplicationSite.LEFT_VENTROGLUTEAL to ApplicationSite.RIGHT_VENTROGLUTEAL,
            ApplicationSite.FOREARM_LEFT to ApplicationSite.FOREARM_RIGHT,
            ApplicationSite.SCAPULA_LEFT to ApplicationSite.SCAPULA_RIGHT,
            ApplicationSite.HYPOTHENAR_LEFT to ApplicationSite.HYPOTHENAR_RIGHT,
            ApplicationSite.ABDOMEN_UPPER_LEFT to ApplicationSite.ABDOMEN_UPPER_RIGHT,
            ApplicationSite.ABDOMEN_LOWER_LEFT to ApplicationSite.ABDOMEN_LOWER_RIGHT,
        )

        pairs.forEach { (leftSite, rightSite) ->
            val left = BodyMap.placement(leftSite)!!
            val right = BodyMap.placement(rightSite)!!
            assertEquals("$leftSite and $rightSite must share a row", left.y, right.y, 0.0001f)
            assertEquals(
                "$leftSite and $rightSite must mirror around x=0.5",
                1f,
                left.x + right.x,
                0.0001f,
            )
        }
    }

    @Test
    fun `abdominal quadrants sit above and below one another`() {
        val upper = BodyMap.placement(ApplicationSite.ABDOMEN_UPPER_LEFT)!!
        val lower = BodyMap.placement(ApplicationSite.ABDOMEN_LOWER_LEFT)!!
        assertTrue("upper quadrant must be higher on screen", upper.y < lower.y)
    }

    @Test
    fun `a deltoid sits above a thigh`() {
        val deltoid = BodyMap.placement(ApplicationSite.LEFT_DELTOID)!!
        val thigh = BodyMap.placement(ApplicationSite.LEFT_VASTUS_LATERALIS)!!
        assertTrue(deltoid.y < thigh.y)
    }

    @Test
    fun `regions are assigned to the right body parts`() {
        assertEquals(BodyMap.Region.DELTOID, BodyMap.placement(ApplicationSite.LEFT_DELTOID)!!.region)
        assertEquals(BodyMap.Region.THIGH, BodyMap.placement(ApplicationSite.RIGHT_THIGH)!!.region)
        assertEquals(BodyMap.Region.FOREARM, BodyMap.placement(ApplicationSite.FOREARM_LEFT)!!.region)
        assertEquals(BodyMap.Region.ABDOMEN, BodyMap.placement(ApplicationSite.ABDOMEN_LOWER_RIGHT)!!.region)
    }

    // --- freshness -------------------------------------------------------------

    @Test
    fun `a never used site is the best state`() {
        assertEquals(BodyMap.Freshness.NEVER, BodyMap.freshness(null, now))
    }

    @Test
    fun `a site used today is fresh`() {
        assertEquals(
            BodyMap.Freshness.FRESH,
            BodyMap.freshness(now.minus(Duration.ofHours(2)), now),
        )
    }

    @Test
    fun `a site used a week ago is still fresh`() {
        assertEquals(
            BodyMap.Freshness.FRESH,
            BodyMap.freshness(now.minus(Duration.ofDays(BodyMap.FRESH_DAYS)), now),
        )
    }

    @Test
    fun `a site used two weeks ago is recent`() {
        assertEquals(
            BodyMap.Freshness.RECENT,
            BodyMap.freshness(now.minus(Duration.ofDays(14)), now),
        )
    }

    @Test
    fun `a site used a month ago is aged`() {
        assertEquals(
            BodyMap.Freshness.AGED,
            BodyMap.freshness(now.minus(Duration.ofDays(40)), now),
        )
    }

    @Test
    fun `a future timestamp is not treated as very fresh history`() {
        assertEquals(BodyMap.Freshness.NEVER, BodyMap.freshness(now.plus(Duration.ofDays(1)), now))
    }

    // --- per-form state --------------------------------------------------------

    @Test
    fun `states only include sites of the given form`() {
        val states = BodyMap.states(MedicationForm.INJECTION_IM, emptyList(), now)
        assertEquals(ApplicationSite.forForm(MedicationForm.INJECTION_IM).toSet(), states.map { it.site }.toSet())
    }

    @Test
    fun `history for other forms is ignored`() {
        val states = BodyMap.states(
            form = MedicationForm.INJECTION_IM,
            history = listOf(ApplicationSite.FOREARM_LEFT to now.minus(Duration.ofDays(2))),
            now = now,
        )
        assertTrue("a forearm use must not mark any injection site", states.all { it.lastUsed == null })
    }

    @Test
    fun `the newest use of a site wins`() {
        val old = now.minus(Duration.ofDays(30))
        val recent = now.minus(Duration.ofDays(1))
        val states = BodyMap.states(
            form = MedicationForm.INJECTION_IM,
            history = listOf(
                ApplicationSite.LEFT_DELTOID to old,
                ApplicationSite.LEFT_DELTOID to recent,
            ),
            now = now,
        )

        val deltoid = states.first { it.site == ApplicationSite.LEFT_DELTOID }
        assertEquals(BodyMap.Freshness.FRESH, deltoid.freshness)
        assertEquals(1L, deltoid.daysAgo)
    }

    @Test
    fun `days ago is reported for each used site`() {
        val states = BodyMap.states(
            form = MedicationForm.INJECTION_SUBQ,
            history = listOf(ApplicationSite.ABDOMEN_UPPER_LEFT to now.minus(Duration.ofDays(5))),
            now = now,
        )
        val abdomen = states.first { it.site == ApplicationSite.ABDOMEN_UPPER_LEFT }
        assertEquals(5L, abdomen.daysAgo)
    }

    @Test
    fun `a tablet form has no drawable sites`() {
        assertTrue(BodyMap.states(MedicationForm.TABLET, emptyList(), now).isEmpty())
        assertNull(BodyMap.placement(ApplicationSite.LEFT_DELTOID)?.takeIf { false })
    }
}
