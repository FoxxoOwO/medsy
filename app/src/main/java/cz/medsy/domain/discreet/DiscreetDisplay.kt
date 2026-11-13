package cz.medsy.domain.discreet

import cz.medsy.domain.model.Medication

/**
 * Decides what a medication row may show while discreet mode is on.
 *
 * Discreet mode exists so that someone glancing at the screen cannot infer a diagnosis.
 * A "HRT" badge is the obvious leak, but the substance name and the ATC code give it
 * away just as clearly (`G03CA` is an oestrogen), so all three are treated as sensitive
 * rather than only the badge. Strength and form are kept: they are needed to identify
 * the right box and do not name a condition.
 */
object DiscreetDisplay {

    /** Shown instead of the medication name when the row must be anonymised. */
    const val MASKED_NAME = "Medication"

    data class Row(
        val name: String,
        val detail: String,
        val showHrtBadge: Boolean,
        /** True when the name was replaced, so the UI can style it as anonymised. */
        val nameMasked: Boolean,
    )

    fun row(medication: Medication, discreet: Boolean): Row {
        val detail = listOfNotNull(
            medication.displayStrength,
            if (discreet) null else medication.activeSubstance,
            if (discreet) null else medication.atcCode?.let { "ATC $it" },
        ).joinToString(" · ")

        return if (discreet) {
            Row(
                name = MASKED_NAME,
                detail = detail,
                showHrtBadge = false,
                nameMasked = true,
            )
        } else {
            Row(
                name = medication.name,
                detail = detail,
                showHrtBadge = medication.isHrt,
                nameMasked = false,
            )
        }
    }
}
