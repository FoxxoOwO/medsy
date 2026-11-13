package cz.medsy.domain.bodymap

import cz.medsy.domain.model.ApplicationSite
import cz.medsy.domain.model.MedicationForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * Hit testing decides which side of the body a tap means. A mirrored hit test would
 * record the same arm the patient just used, so the mapping is asserted directly rather
 * than through a screenshot.
 */
class SiteMapInteractionTest {

    @Test
    fun `a tap exactly on a site selects that site`() {
        BodyMap.placedSites().forEach { site ->
            val placement = BodyMap.placement(site)!!
            assertEquals(
                "tap at $site",
                site,
                BodyMapHitTest.nearest(placement.x, placement.y),
            )
        }
    }

    @Test
    fun `a tap on the right of the screen is the patient's left site`() {
        val left = BodyMap.placement(ApplicationSite.LEFT_DELTOID)!!
        assertTrue("left sites are drawn on the right", left.x > 0.5f)
        assertEquals(ApplicationSite.LEFT_DELTOID, BodyMapHitTest.nearest(0.66f, 0.30f))
        assertEquals(ApplicationSite.RIGHT_DELTOID, BodyMapHitTest.nearest(0.34f, 0.30f))
    }

    @Test
    fun `a near miss inside the touch tolerance still selects`() {
        val placement = BodyMap.placement(ApplicationSite.LEFT_DELTOID)!!
        // Offset away from the scapula, which is 0.06 below and behind the deltoid.
        assertEquals(
            ApplicationSite.LEFT_DELTOID,
            BodyMapHitTest.nearest(placement.x + 0.05f, placement.y - 0.03f),
        )
    }

    @Test
    fun `a tap far outside the tolerance selects nothing`() {
        assertNull(BodyMapHitTest.nearest(0.90f, 0.20f))
    }

    @Test
    fun `a tap on empty space selects nothing`() {
        assertNull("between the feet", BodyMapHitTest.nearest(0.5f, 0.97f))
        assertNull("above the head", BodyMapHitTest.nearest(0.5f, 0.02f))
        assertNull("off the body at the left edge", BodyMapHitTest.nearest(0.03f, 0.5f))
    }

    @Test
    fun `only sites offered for the form can be selected`() {
        val patchSites = ApplicationSite.forForm(MedicationForm.PATCH)
        val thigh = BodyMap.placement(ApplicationSite.LEFT_VASTUS_LATERALIS)!!
        assertNull(
            "a patch cannot go on a vastus lateralis",
            BodyMapHitTest.nearest(thigh.x, thigh.y, candidates = patchSites),
        )

        val forearm = BodyMap.placement(ApplicationSite.FOREARM_LEFT)!!
        assertEquals(
            ApplicationSite.FOREARM_LEFT,
            BodyMapHitTest.nearest(forearm.x, forearm.y, candidates = patchSites),
        )
    }

    @Test
    fun `no two sites share the same spot`() {
        val sites = BodyMap.placedSites().toList()
        for (i in sites.indices) {
            for (j in i + 1 until sites.size) {
                val a = BodyMap.placement(sites[i])!!
                val b = BodyMap.placement(sites[j])!!
                assertTrue(
                    "${sites[i]} and ${sites[j]} overlap exactly",
                    hypot(a.x - b.x, a.y - b.y) > 0f,
                )
            }
        }
    }
}
