package cz.medsy.domain.bodymap

import cz.medsy.domain.model.ApplicationSite
import java.time.Duration
import java.time.Instant

/**
 * Geometric placement of application sites on a stylised front-facing body.
 *
 * Coordinates are normalised (0..1, origin top-left) so the Compose canvas can scale
 * them to any size. Keeping this as data — not drawing code — is what makes "does the
 * left deltoid sit on the left?" testable; a mirrored map would otherwise suggest the
 * wrong arm and the patient would rotate into the same side twice.
 */
object BodyMap {

    data class Placement(
        val x: Float,
        val y: Float,
        /** Mirror of the patient's own left/right, for the axis label. */
        val side: Side,
        val region: Region,
    )

    enum class Side { LEFT, RIGHT, CENTRE }

    enum class Region { DELTOID, THIGH, GLUTE, ABDOMEN, FOREARM, SCAPULA, HAND }

    /**
     * The body is drawn facing the viewer, so the patient's **left** appears on the
     * right side of the screen. `x` is therefore larger for left-sided sites.
     */
    private val placements: Map<ApplicationSite, Placement> = mapOf(
        // Intramuscular: deltoid, thigh, glute.
        ApplicationSite.LEFT_DELTOID to Placement(0.66f, 0.30f, Side.LEFT, Region.DELTOID),
        ApplicationSite.RIGHT_DELTOID to Placement(0.34f, 0.30f, Side.RIGHT, Region.DELTOID),
        ApplicationSite.LEFT_VASTUS_LATERALIS to Placement(0.63f, 0.68f, Side.LEFT, Region.THIGH),
        ApplicationSite.RIGHT_VASTUS_LATERALIS to Placement(0.37f, 0.68f, Side.RIGHT, Region.THIGH),
        ApplicationSite.LEFT_VENTROGLUTEAL to Placement(0.68f, 0.52f, Side.LEFT, Region.GLUTE),
        ApplicationSite.RIGHT_VENTROGLUTEAL to Placement(0.32f, 0.52f, Side.RIGHT, Region.GLUTE),

        // Subcutaneous: abdomen quadrants, thigh, glute.
        ApplicationSite.ABDOMEN_UPPER_LEFT to Placement(0.58f, 0.48f, Side.LEFT, Region.ABDOMEN),
        ApplicationSite.ABDOMEN_UPPER_RIGHT to Placement(0.42f, 0.48f, Side.RIGHT, Region.ABDOMEN),
        ApplicationSite.ABDOMEN_LOWER_LEFT to Placement(0.58f, 0.56f, Side.LEFT, Region.ABDOMEN),
        ApplicationSite.ABDOMEN_LOWER_RIGHT to Placement(0.42f, 0.56f, Side.RIGHT, Region.ABDOMEN),
        ApplicationSite.LEFT_GLUTE to Placement(0.64f, 0.60f, Side.LEFT, Region.GLUTE),
        ApplicationSite.RIGHT_GLUTE to Placement(0.36f, 0.60f, Side.RIGHT, Region.GLUTE),
        ApplicationSite.LEFT_THIGH to Placement(0.60f, 0.72f, Side.LEFT, Region.THIGH),
        ApplicationSite.RIGHT_THIGH to Placement(0.40f, 0.72f, Side.RIGHT, Region.THIGH),

        // Transdermal: forearm, scapula, hypothenar.
        ApplicationSite.FOREARM_LEFT to Placement(0.78f, 0.60f, Side.LEFT, Region.FOREARM),
        ApplicationSite.FOREARM_RIGHT to Placement(0.22f, 0.60f, Side.RIGHT, Region.FOREARM),
        ApplicationSite.SCAPULA_LEFT to Placement(0.62f, 0.36f, Side.LEFT, Region.SCAPULA),
        ApplicationSite.SCAPULA_RIGHT to Placement(0.38f, 0.36f, Side.RIGHT, Region.SCAPULA),
        ApplicationSite.HYPOTHENAR_LEFT to Placement(0.84f, 0.70f, Side.LEFT, Region.HAND),
        ApplicationSite.HYPOTHENAR_RIGHT to Placement(0.16f, 0.70f, Side.RIGHT, Region.HAND),
    )

    fun placement(site: ApplicationSite): Placement? = placements[site]

    /** All sites that can be drawn, so the UI never silently drops one. */
    fun placedSites(): Set<ApplicationSite> = placements.keys

    fun sitesWithoutPlacement(): Set<ApplicationSite> =
        ApplicationSite.entries.toSet() - placements.keys

    /**
     * How long ago a site was used, bucketed for colouring. `null` means never used,
     * which is the best state for rotation.
     */
    enum class Freshness { NEVER, FRESH, RECENT, AGED }

    data class SiteState(
        val site: ApplicationSite,
        val lastUsed: Instant?,
        val freshness: Freshness,
        val daysAgo: Long?,
    )

    /**
     * A site used within [FRESH_DAYS] days is too recent to reuse; beyond
     * [AGED_DAYS] it is fully recovered.
     */
    const val FRESH_DAYS = 7L
    const val AGED_DAYS = 21L

    fun freshness(lastUsed: Instant?, now: Instant): Freshness {
        if (lastUsed == null) return Freshness.NEVER
        val days = Duration.between(lastUsed, now).toDays()
        return when {
            days < 0 -> Freshness.NEVER
            days <= FRESH_DAYS -> Freshness.FRESH
            days <= AGED_DAYS -> Freshness.RECENT
            else -> Freshness.AGED
        }
    }

    /**
     * Builds the drawable state for one form: only its own sites, with history merged.
     * History for sites outside the form is ignored — a transdermal forearm use says
     * nothing about where an intramuscular injection should go.
     */
    fun states(
        form: cz.medsy.domain.model.MedicationForm,
        history: List<Pair<ApplicationSite, Instant>>,
        now: Instant = Instant.now(),
    ): List<SiteState> {
        val candidates = ApplicationSite.forForm(form)
        val lastUsed = mutableMapOf<ApplicationSite, Instant>()
        for ((site, at) in history) {
            if (site !in candidates) continue
            val previous = lastUsed[site]
            if (previous == null || at.isAfter(previous)) lastUsed[site] = at
        }
        return candidates.map { site ->
            val used = lastUsed[site]
            SiteState(
                site = site,
                lastUsed = used,
                freshness = freshness(used, now),
                daysAgo = used?.let { Duration.between(it, now).toDays() },
            )
        }
    }
}
