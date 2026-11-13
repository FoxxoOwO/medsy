package cz.medsy.ui.message

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cz.medsy.R
import cz.medsy.domain.model.MedicationForm

/**
 * The dose unit the app pre-fills for a form.
 *
 * A unit is free text: it is written into `schedules.doseUnit` and `inventory_batches.unit` and it
 * is what the user edits, so it has to be readable in their language rather than a canonical token
 * that only the app understands. Rows written before this existed keep their old text, which is
 * why nothing ever rewrites them.
 */
@StringRes
fun defaultUnitRes(form: MedicationForm): Int = when (form) {
    MedicationForm.INJECTION_IM,
    MedicationForm.INJECTION_SUBQ,
    -> R.string.dose_unit_ml

    MedicationForm.PATCH -> R.string.dose_unit_patch
    MedicationForm.GEL,
    MedicationForm.SPRAY,
    -> R.string.dose_unit_dose

    MedicationForm.CAPSULE -> R.string.dose_unit_capsule
    MedicationForm.DROPS -> R.string.dose_unit_drop
    MedicationForm.SUBLINGUAL -> R.string.dose_unit_tablet
    MedicationForm.TABLET -> R.string.dose_unit_tablet
}

/** The same value inside a composable, where resolving a resource is allowed. */
@Composable
fun defaultUnitText(form: MedicationForm): String = stringResource(defaultUnitRes(form))
