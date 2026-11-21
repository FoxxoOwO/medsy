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

    data class Row(
        val name: String,
        val detail: String,
        val showHrtBadge: Boolean,
        /** True when the name was replaced, so the UI can style it as anonymised. */
        val nameMasked: Boolean,
    )

    /**
     * @param maskedName the localised anonymous label, supplied by the UI. The domain cannot
     *   read resources, and the label must follow the phone's language just like any other copy.
     */
    fun row(medication: Medication, discreet: Boolean, maskedName: String): Row {
        val detail = listOfNotNull(
            medication.displayStrength,
            if (discreet) null else medication.activeSubstance,
            if (discreet) null else medication.atcCode?.let { "ATC $it" },
        ).joinToString(" · ")

        return if (discreet) {
            Row(
                name = maskedName,
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
