package cz.medsy.domain.prescription

import cz.medsy.domain.message.Message
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.message.message
import cz.medsy.domain.message.messagePlural
import cz.medsy.domain.model.PrescriptionItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * A stored eRecept together with the dates that decide whether it is still worth showing.
 *
 * "Days left" is counted in calendar days in the user's zone, not in 24-hour blocks: an
 * eRecept valid until 23:59 today is "0 days left", and it is expired tomorrow at midnight.
 * Counting whole days would hide an expiring prescription behind a rounding error.
 */
data class QueuedPrescription(
    val id: Long,
    val code: String,
    val issuedAt: Instant?,
    val validUntil: Instant?,
    val dispensed: Boolean,
    val items: List<PrescriptionItem>,
) {
    fun daysLeft(now: Instant, zone: ZoneId = ZoneId.systemDefault()): Long? =
        validUntil?.let { until ->
            ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), until.atZone(zone).toLocalDate())
        }

    fun isExpired(now: Instant): Boolean = validUntil?.let { it.isBefore(now) } ?: false

    /** True from the moment the warning window opens until the prescription expires. */
    fun isExpiringSoon(now: Instant, days: Long = PrescriptionQueue.WARNING_DAYS): Boolean {
        if (dispensed || isExpired(now)) return false
        val left = daysLeft(now) ?: return false
        return left in 0..days
    }

    fun isUsable(now: Instant): Boolean = !dispensed && !isExpired(now)
}

object PrescriptionQueue {

    /** Two days of warning: enough to get to a pharmacy without nagging for a fortnight. */
    const val WARNING_DAYS: Long = 2L

    fun active(items: List<QueuedPrescription>, now: Instant): List<QueuedPrescription> =
        items.filter { it.isUsable(now) }.sortedBy { it.validUntil }

    /** Dashboard badge: only prescriptions about to expire, never the whole queue. */
    fun badgeCount(items: List<QueuedPrescription>, now: Instant): Int =
        items.count { it.isExpiringSoon(now) }

    fun describe(item: QueuedPrescription, now: Instant): Message {
        val left = item.daysLeft(now)
        return when {
            item.dispensed -> message(MessageKey.RX_DISPENSED)
            left == null -> message(MessageKey.RX_NO_EXPIRY)
            left < 0 -> message(MessageKey.RX_EXPIRED)
            left == 0L -> message(MessageKey.RX_EXPIRES_TODAY)
            left == 1L -> message(MessageKey.RX_EXPIRES_TOMORROW)
            else -> messagePlural(MessageKey.RX_EXPIRES_IN_DAYS, left.toInt(), left)
        }
    }

    /**
     * One line for the notification, or null when nothing is about to expire. Only the
     * nearest prescription is named: the notification opens the queue, it does not list it.
     */
    fun reminderMessage(items: List<QueuedPrescription>, now: Instant): Message? {
        val soon = items.filter { it.isExpiringSoon(now) }.sortedBy { it.validUntil }
        if (soon.isEmpty()) return null
        val first = soon.first()
        val state = describe(first, now)
        return when (soon.size) {
            1 -> message(MessageKey.RX_REMINDER_ONE, first.code, state)
            else -> {
                val remaining = soon.size - 1
                messagePlural(
                    MessageKey.RX_REMINDER_MORE,
                    remaining,
                    first.code,
                    state,
                    remaining,
                )
            }
        }
    }

    /** Local date the warning window opens, used by the notification text. */
    fun expiryDate(item: QueuedPrescription, zone: ZoneId = ZoneId.systemDefault()): LocalDate? =
        item.validUntil?.atZone(zone)?.toLocalDate()
}
