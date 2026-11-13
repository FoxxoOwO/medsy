package cz.medsy.ui.prescriptions

import androidx.compose.runtime.Composable
import cz.medsy.domain.prescription.PrescriptionQueue
import cz.medsy.domain.prescription.QueuedPrescription
import cz.medsy.ui.message.asText
import java.time.Instant

/**
 * The state sentence of one eRecept, as shown next to its code.
 *
 * The sentence itself is composed in the domain as a [cz.medsy.domain.message.Message], so the
 * dashboard, the queue and the notification all say the same thing and no branch is duplicated
 * here just to reach the resources.
 */
@Composable
fun describePrescription(item: QueuedPrescription, now: Instant): String =
    PrescriptionQueue.describe(item, now).asText()
