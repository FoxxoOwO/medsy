package cz.medsy.domain.bodymap

import cz.medsy.domain.model.ApplicationSite
import kotlin.math.hypot

/**
 * Turns a tap on the drawn body into an application site.
 *
 * This lives outside the composable for one reason: the body is drawn facing the viewer,
 * so a tap on the right-hand edge of the screen is the patient's **left** site. A mirrored
 * hit test would send the patient back into the tissue they used last time, which is
 * exactly what the rotation tracker exists to prevent. Keeping the mapping as pure data
 * makes that rule assertable without a rendering surface.
 */
object BodyMapHitTest {

    /**
     * Radius, in normalised canvas units (0..1), within which a tap still counts.
     * A finger is about 0.06–0.08 of a body-map width, so anything smaller misses.
     */
    const val TOLERANCE = 0.08f

    /**
     * Nearest site to the tap, or `null` when the tap landed on empty space.
     *
     * [candidates] is restricted to the sites the current form may use, so tapping the
     * thigh while applying a patch cannot select an injection site that will never be
     * offered it.
     */
    fun nearest(
        x: Float,
        y: Float,
        candidates: Collection<ApplicationSite> = BodyMap.placedSites(),
        tolerance: Float = TOLERANCE,
    ): ApplicationSite? {
        var best: ApplicationSite? = null
        var bestDistance = Float.MAX_VALUE
        for (site in candidates) {
            val placement = BodyMap.placement(site) ?: continue
            val distance = hypot(placement.x - x, placement.y - y)
            if (distance < bestDistance) {
                bestDistance = distance
                best = site
            }
        }
        return best?.takeIf { bestDistance <= tolerance }
    }
}
