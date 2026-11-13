package cz.medsy.domain.inventory

import cz.medsy.domain.message.Message
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.message.message
import cz.medsy.domain.model.InventoryBatch
import cz.medsy.domain.model.MedicationForm
import java.math.BigDecimal
import java.time.LocalDate

/**
 * What the user typed into the “add batch” form. Kept separate from [InventoryBatch]
 * because a draft is allowed to be wrong: an expired box has to survive validation so the
 * user sees *why* it is a problem instead of the box silently disappearing.
 */
data class BatchDraft(
    val medicationId: Long,
    val quantity: BigDecimal,
    val unit: String,
    val expirationDate: LocalDate? = null,
    val batchNumber: String? = null,
    val deadSpaceMlPerDose: BigDecimal? = null,
    val beyondUseDaysLimit: Int? = null,
    val lowStockThresholdDays: Int = 7,
)

data class BatchValidation(
    val errors: List<Message> = emptyList(),
    val warnings: List<Message> = emptyList(),
) {
    val isValid: Boolean get() = errors.isEmpty()
}

/**
 * Rules for a hand-entered batch.
 *
 * Quantity and unit are errors when wrong — a batch of "0" is not stock, it is a typo that
 * would sit in the shelf list forever. An expiry date in the past is only a warning: it is
 * a legitimate way to record old stock (which then shows up as unusable), and blocking it
 * would push people to enter a fake date.
 */
object BatchDraftValidator {

    fun validate(
        draft: BatchDraft,
        form: MedicationForm,
        today: LocalDate = LocalDate.now(),
    ): BatchValidation {
        val errors = mutableListOf<Message>()
        val warnings = mutableListOf<Message>()

        if (draft.medicationId <= 0L) errors += message(MessageKey.BATCH_CHOOSE_MEDICATION)
        if (draft.quantity.signum() <= 0) errors += message(MessageKey.BATCH_QUANTITY_POSITIVE)
        if (draft.unit.isBlank()) errors += message(MessageKey.BATCH_UNIT_REQUIRED)
        if (draft.lowStockThresholdDays < 0) errors += message(MessageKey.BATCH_THRESHOLD_NEGATIVE)
        if (draft.beyondUseDaysLimit != null && draft.beyondUseDaysLimit <= 0) {
            errors += message(MessageKey.BATCH_BEYOND_USE_MIN)
        }
        draft.deadSpaceMlPerDose?.let { dead ->
            if (dead.signum() < 0) errors += message(MessageKey.BATCH_DEAD_SPACE_NEGATIVE)
            if (dead.signum() > 0 && !form.isInjectable) {
                warnings += message(MessageKey.BATCH_DEAD_SPACE_INJECTABLE_ONLY)
            }
        }

        if (draft.unit.isNotBlank()) {
            val expected = expectedUnits(form)
            if (draft.unit.trim().lowercase() !in expected) {
                warnings += message(MessageKey.BATCH_UNUSUAL_UNIT, form.label(), expected.first())
            }
        }

        draft.expirationDate?.let { expiry ->
            when {
                expiry.isBefore(today) ->
                    warnings += message(MessageKey.BATCH_EXPIRED)
                expiry == today ->
                    warnings += message(MessageKey.BATCH_EXPIRES_TODAY)
            }
        }

        return BatchValidation(errors = errors, warnings = warnings)
    }

    fun expectedUnits(form: MedicationForm): Set<String> = when (form) {
        MedicationForm.INJECTION_IM, MedicationForm.INJECTION_SUBQ -> setOf("ml")
        MedicationForm.GEL, MedicationForm.SPRAY -> setOf("dose", "pump", "spray")
        MedicationForm.PATCH -> setOf("patch")
        MedicationForm.SUBLINGUAL -> setOf("tbl", "tablet")
        MedicationForm.DROPS -> setOf("ml", "drop")
        MedicationForm.TABLET, MedicationForm.CAPSULE -> setOf("tbl", "tablet", "capsule")
    }

    private fun MedicationForm.label(): Message = message(
        when (this) {
            MedicationForm.TABLET -> MessageKey.BATCH_FORM_TABLET
            MedicationForm.CAPSULE -> MessageKey.BATCH_FORM_CAPSULE
            MedicationForm.INJECTION_IM, MedicationForm.INJECTION_SUBQ -> MessageKey.BATCH_FORM_INJECTION
            MedicationForm.PATCH -> MessageKey.BATCH_FORM_PATCH
            MedicationForm.GEL, MedicationForm.SPRAY -> MessageKey.BATCH_FORM_GEL_OR_SPRAY
            MedicationForm.SUBLINGUAL -> MessageKey.BATCH_FORM_SUBLINGUAL
            MedicationForm.DROPS -> MessageKey.BATCH_FORM_DROPS
        },
    )
}

fun BatchDraft.toBatch(): InventoryBatch = InventoryBatch(
    medicationId = medicationId,
    batchNumber = batchNumber?.trim()?.takeIf { it.isNotEmpty() },
    expirationDate = expirationDate,
    beyondUseDaysLimit = beyondUseDaysLimit,
    initialQuantity = quantity,
    remainingQuantity = quantity,
    unit = unit.trim(),
    deadSpaceMlPerDose = deadSpaceMlPerDose,
    lowStockThresholdDays = lowStockThresholdDays,
)
