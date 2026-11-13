package cz.medsy.domain.model

import cz.medsy.domain.message.Message
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.message.message

enum class MedicationForm {
    TABLET,
    CAPSULE,
    INJECTION_IM,
    INJECTION_SUBQ,
    PATCH,
    GEL,
    SPRAY,
    SUBLINGUAL,
    DROPS;

    val isInjectable: Boolean
        get() = this == INJECTION_IM || this == INJECTION_SUBQ

    val isTransdermal: Boolean
        get() = this == PATCH || this == GEL || this == SPRAY

    val requiresSite: Boolean
        get() = isInjectable || isTransdermal

    val displaysVolume: Boolean
        get() = isInjectable
}

enum class ScheduleType { FIXED_DAILY, INTERVAL_HOURS, INTERVAL_DAYS, CYCLICAL, TAPERING, PRN }

enum class MealRelation { NONE, FASTING_BEFORE, WITH_MEAL, AFTER_MEAL }

enum class DoseStatus { TAKEN, SKIPPED, SNOOZED, LATE }

enum class ApplicationSite(val labelKey: MessageKey) {
    LEFT_VASTUS_LATERALIS(MessageKey.SITE_LEFT_VASTUS_LATERALIS),
    RIGHT_VASTUS_LATERALIS(MessageKey.SITE_RIGHT_VASTUS_LATERALIS),
    LEFT_VENTROGLUTEAL(MessageKey.SITE_LEFT_VENTROGLUTEAL),
    RIGHT_VENTROGLUTEAL(MessageKey.SITE_RIGHT_VENTROGLUTEAL),
    LEFT_DELTOID(MessageKey.SITE_LEFT_DELTOID),
    RIGHT_DELTOID(MessageKey.SITE_RIGHT_DELTOID),
    ABDOMEN_UPPER_LEFT(MessageKey.SITE_ABDOMEN_UPPER_LEFT),
    ABDOMEN_UPPER_RIGHT(MessageKey.SITE_ABDOMEN_UPPER_RIGHT),
    ABDOMEN_LOWER_LEFT(MessageKey.SITE_ABDOMEN_LOWER_LEFT),
    ABDOMEN_LOWER_RIGHT(MessageKey.SITE_ABDOMEN_LOWER_RIGHT),
    LEFT_GLUTE(MessageKey.SITE_LEFT_GLUTE),
    RIGHT_GLUTE(MessageKey.SITE_RIGHT_GLUTE),
    LEFT_THIGH(MessageKey.SITE_LEFT_THIGH),
    RIGHT_THIGH(MessageKey.SITE_RIGHT_THIGH),
    FOREARM_LEFT(MessageKey.SITE_FOREARM_LEFT),
    FOREARM_RIGHT(MessageKey.SITE_FOREARM_RIGHT),
    SCAPULA_LEFT(MessageKey.SITE_SCAPULA_LEFT),
    SCAPULA_RIGHT(MessageKey.SITE_SCAPULA_RIGHT),
    HYPOTHENAR_LEFT(MessageKey.SITE_HYPOTHENAR_LEFT),
    HYPOTHENAR_RIGHT(MessageKey.SITE_HYPOTHENAR_RIGHT);

    /** The stored value is [name]; the readable one is always resolved in the UI. */
    val label: Message get() = message(labelKey)

    val group: SiteGroup
        get() = when (this) {
            LEFT_VASTUS_LATERALIS, RIGHT_VASTUS_LATERALIS,
            LEFT_VENTROGLUTEAL, RIGHT_VENTROGLUTEAL,
            LEFT_DELTOID, RIGHT_DELTOID -> SiteGroup.INJECTION_IM
            ABDOMEN_UPPER_LEFT, ABDOMEN_UPPER_RIGHT,
            ABDOMEN_LOWER_LEFT, ABDOMEN_LOWER_RIGHT,
            LEFT_GLUTE, RIGHT_GLUTE,
            LEFT_THIGH, RIGHT_THIGH -> SiteGroup.SUBQ
            FOREARM_LEFT, FOREARM_RIGHT,
            SCAPULA_LEFT, SCAPULA_RIGHT,
            HYPOTHENAR_LEFT, HYPOTHENAR_RIGHT -> SiteGroup.TRANSDERMAL
        }

    companion object {
        fun forForm(form: MedicationForm): List<ApplicationSite> = when (form) {
            MedicationForm.INJECTION_IM -> forGroup(SiteGroup.INJECTION_IM)
            MedicationForm.INJECTION_SUBQ -> forGroup(SiteGroup.SUBQ)
            MedicationForm.PATCH, MedicationForm.GEL -> forGroup(SiteGroup.TRANSDERMAL)
            MedicationForm.SPRAY -> listOf(FOREARM_LEFT, FOREARM_RIGHT, HYPOTHENAR_LEFT, HYPOTHENAR_RIGHT)
            else -> emptyList()
        }

        private fun forGroup(group: SiteGroup): List<ApplicationSite> =
            entries.filter { it.group == group }
    }
}

enum class SiteGroup { INJECTION_IM, SUBQ, TRANSDERMAL }

enum class ProfileType { HRT_FEM, HRT_MASC, CIS_F, CIS_M, CUSTOM, LAB_PRINTED }

enum class ChangeType { STARTED, DOSE_CHANGED, ROUTE_CHANGED, STOPPED }

enum class PatchState { APPLIED, WORN, REMOVAL_DUE, REMOVED }

enum class InteractionSeverity { MINOR, MODERATE, SEVERE }

enum class AlertKind { FOOD, SUPPLEMENT, LIFESTYLE, DRUG }

enum class LabImportSource { PDF_TEXT, OCR_IMAGE, MANUAL }
