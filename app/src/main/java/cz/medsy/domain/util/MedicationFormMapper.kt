package cz.medsy.domain.util

import cz.medsy.domain.model.MedicationForm

/**
 * Turns a SÚKL dosage-form string (Czech, e.g. "potahovaná tableta", "injekční roztok
 * v předplněné injekční stříkačce") into the app's [MedicationForm].
 *
 * Kept in one place because three call sites need the same answer — the scanner's save flow,
 * the lookup waterfall and the package importer. Their previous private copies disagreed
 * ("sublingual" tablets were silently stored as plain tablets), which changed how the app
 * scheduled doses and computed hold times.
 */
object MedicationFormMapper {

    fun fromSuklForm(formName: String?): MedicationForm {
        val value = formName?.lowercase()?.trim().orEmpty()
        if (value.isEmpty()) return MedicationForm.TABLET
        return when {
            value.contains("subling") || value.contains("podjazykov") -> MedicationForm.SUBLINGUAL
            value.contains("náplast") || value.contains("naplast") || value.contains("patch") ->
                MedicationForm.PATCH
            value.contains("gel") || value.contains("krém") || value.contains("krem") ||
                value.contains("cream") || value.contains("emulze") || value.contains("mast") ->
                MedicationForm.GEL
            value.contains("sprej") || value.contains("spray") || value.contains("aerosol") ->
                MedicationForm.SPRAY
            value.contains("kapky") || value.contains("drop") || value.contains("guttae") ->
                MedicationForm.DROPS
            value.contains("injek") || value.contains("inj.") || value.contains("infuz") ||
                value.contains("vial") || value.contains("ampul") -> injectionForm(value)
            value.contains("cps") || value.contains("capsul") || value.contains("kapsl") ->
                MedicationForm.CAPSULE
            value.contains("tbl") || value.contains("tablet") || value.contains("potahovan") ->
                MedicationForm.TABLET
            value.contains("doplňk") || value.contains("doplnk") || value.contains("vitam") ->
                MedicationForm.SUPPLEMENT
            else -> MedicationForm.TABLET
        }
    }

    /**
     * SÚKL does not distinguish intramuscular from subcutaneous injection, so the wording is the
     * only signal. Anything explicitly subcutaneous (or an insulin/pen style presentation) is
     * SUBQ; everything else defaults to IM, which is the common case for the esters this app
     * supports. A wrong guess only affects the default application site, not the PK maths.
     */
    private fun injectionForm(value: String): MedicationForm = when {
        value.contains("subkut") || value.contains("subcut") || value.contains("s.c.") ||
            value.contains("inzulin") || value.contains("pero") -> MedicationForm.INJECTION_SUBQ
        value.contains("intramusk") || value.contains("i.m.") -> MedicationForm.INJECTION_IM
        else -> MedicationForm.INJECTION_IM
    }
}
