package cz.medsy.domain.model

/**
 * The frequency bands the SPC uses, ordered from the most to the least common.
 *
 * They are spelled out rather than reduced to a `severity: Int` because the band is what the
 * leaflet actually says ("časté: >= 1/100"), and a user comparing two medicines reads the word,
 * not a number.
 */
enum class SideEffectFrequency { VERY_COMMON, COMMON, UNCOMMON, RARE, VERY_RARE }

data class SideEffect(
    val frequency: SideEffectFrequency,
    val text: String,
)

/**
 * The common undesirable effects of one substance.
 *
 * [atcCode] is the row that matched, which may be a *prefix* of the medication's own code: an
 * unknown estradiol ester falls back to the `G03CA` group, so the card can say honestly that it is
 * describing the group and not the exact product.
 */
data class SideEffectProfile(
    val atcCode: String,
    val substance: String,
    val effects: List<SideEffect>,
) {

    /** True when [atcCode] is a group code (`G03`, `G03CA`) rather than a substance (`G03CA03`). */
    val isGroupLevel: Boolean get() = atcCode.length < SUBSTANCE_CODE_LENGTH

    /** Effects grouped by band, in the SPC's order, skipping bands this substance does not have. */
    fun byFrequency(): List<Pair<SideEffectFrequency, List<String>>> =
        SideEffectFrequency.values().mapNotNull { band ->
            effects.filter { it.frequency == band }
                .map(SideEffect::text)
                .takeIf { it.isNotEmpty() }
                ?.let { band to it }
        }

    companion object {
        const val SUBSTANCE_CODE_LENGTH = 7
    }
}
