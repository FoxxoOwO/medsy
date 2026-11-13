package cz.medsy.domain.calc

import cz.medsy.domain.message.Message
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.message.message
import cz.medsy.domain.model.ProfileType
import cz.medsy.domain.model.ReferenceRangeProfile
import java.math.BigDecimal

/**
 * Builds the shaded bands for a chart.
 *
 * The primary profile answers "am I in target" and drives the status colour; overlays
 * are additional windows drawn on top so two perspectives can be compared — e.g. the
 * clinic's HRT target next to the range the lab printed on the report.
 *
 * Bands are limited to [MAX_BANDS]; more than that turns the chart into an unreadable
 * stack of translucent rectangles.
 */
object RangeBands {

    const val MAX_BANDS = 3

    data class Band(
        val profileType: ProfileType,
        val label: Message,
        val min: BigDecimal?,
        val max: BigDecimal?,
        val isPrimary: Boolean,
    )

    /**
     * @param primary the profile used for status evaluation; always first when it has bounds
     * @param overlays additional profiles to draw, in the order the user enabled them
     */
    fun build(
        code: String,
        primary: ProfileType,
        overlays: Set<ProfileType>,
        customRanges: List<ReferenceRangeProfile> = emptyList(),
        labPrinted: ReferenceRangeProfile? = null,
    ): List<Band> {
        val requested = (listOf(primary) + overlays)
            .distinct()
            .filter { it != primary || true }

        val bands = requested.mapNotNull { profile ->
            val range = when (profile) {
                ProfileType.LAB_PRINTED -> labPrinted
                else -> ReferenceRanges.forBiomarker(code, profile, customRanges)
            } ?: return@mapNotNull null
            if (range.targetMinSi == null && range.targetMaxSi == null) return@mapNotNull null
            Band(
                profileType = profile,
                label = label(profile),
                min = range.targetMinSi,
                max = range.targetMaxSi,
                isPrimary = profile == primary,
            )
        }

        // The primary must survive truncation, otherwise the chart loses its anchor.
        val primaryBand = bands.firstOrNull { it.isPrimary }
        val others = bands.filterNot { it.isPrimary }
        val room = if (primaryBand != null) MAX_BANDS - 1 else MAX_BANDS
        return (listOfNotNull(primaryBand) + others.take(room))
    }

    fun label(profile: ProfileType): Message = message(
        when (profile) {
            ProfileType.HRT_FEM -> MessageKey.PROFILE_HRT_FEMINIZING
            ProfileType.HRT_MASC -> MessageKey.PROFILE_HRT_MASCULINIZING
            ProfileType.CIS_F -> MessageKey.PROFILE_CIS_FEMALE
            ProfileType.CIS_M -> MessageKey.PROFILE_CIS_MALE
            ProfileType.CUSTOM -> MessageKey.PROFILE_DOCTOR_TARGET
            ProfileType.LAB_PRINTED -> MessageKey.PROFILE_LAB_PRINTED
        },
    )
}
