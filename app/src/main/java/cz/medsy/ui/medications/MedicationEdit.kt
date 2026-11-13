package cz.medsy.ui.medications

import cz.medsy.domain.model.Medication
import cz.medsy.domain.model.MedicationForm
import cz.medsy.domain.util.MedicationStrength
import java.math.BigDecimal

/**
 * The editable half of a medication, as text, so the fields can be typed into directly.
 *
 * Everything the editor does not show — id, GTIN, SÚKL code, PIL/SPC links, archive flag — is
 * carried over untouched: a user renaming a scanned box must not lose the barcode that lets the
 * next scan find it, and re-deriving `isHrt` is the only field that follows the ATC code by design.
 */
data class MedicationEdit(
    val name: String = "",
    val activeSubstance: String = "",
    val atcCode: String = "",
    val strength: String = "",
    val concentration: String = "",
    val notes: String = "",
    val form: MedicationForm = MedicationForm.TABLET,
) {

    val nameMissing: Boolean get() = name.isBlank()

    /** Text that is not empty but carries no number — "mg" alone or a stray letter. */
    val strengthInvalid: Boolean
        get() = strength.isNotBlank() && MedicationStrength.valueOf(strength) == null

    val concentrationInvalid: Boolean
        get() = concentration.isNotBlank() && MedicationStrength.valueOf(concentration) == null

    val isValid: Boolean get() = !nameMissing && !strengthInvalid && !concentrationInvalid

    /** The medication as it should be stored; fields the user cleared become null, not "". */
    fun applyTo(medication: Medication): Medication {
        val substance = activeSubstance.trim().ifBlank { null }
        val atc = atcCode.trim().uppercase().ifBlank { null }
        return medication.copy(
            name = name.trim(),
            activeSubstance = substance,
            atcCode = atc,
            form = form,
            strengthValue = MedicationStrength.valueOf(strength),
            strengthUnit = MedicationStrength.unitOf(strength),
            concentrationMgPerMl = concentrationMgPerMl(concentration),
            notes = notes.trim().ifBlank { null },
            isHrt = MedicationStrength.isHrtAtc(atc),
        )
    }

    companion object {

        fun of(medication: Medication): MedicationEdit = MedicationEdit(
            name = medication.name,
            activeSubstance = medication.activeSubstance.orEmpty(),
            atcCode = medication.atcCode.orEmpty(),
            strength = MedicationStrength.format(medication.strengthValue, medication.strengthUnit),
            concentration = medication.concentrationMgPerMl?.stripTrailingZeros()?.toPlainString().orEmpty(),
            notes = medication.notes.orEmpty(),
            form = medication.form,
        )

        /** Concentrations are per mL, so a "5 mg/mL" paste keeps the number and drops the unit. */
        private fun concentrationMgPerMl(text: String): BigDecimal? = MedicationStrength.valueOf(text)
    }
}
