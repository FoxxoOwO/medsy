package cz.medsy.domain.model

/**
 * One row of the offline ATC dictionary as seen by the UI.
 *
 * The dictionary itself is a data-layer asset, but the lookup port must not expose its
 * DTO: the domain defines what an ATC suggestion is and the data layer maps into it.
 */
data class AtcSuggestion(
    val code: String,
    val latin: String,
    val czech: String,
    val english: String = "",
    val keywords: List<String> = emptyList(),
    val note: String? = null,
)
