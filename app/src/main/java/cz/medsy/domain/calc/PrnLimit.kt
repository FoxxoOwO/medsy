package cz.medsy.domain.calc

import cz.medsy.domain.message.Message
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.message.message
import cz.medsy.domain.model.DoseLog
import cz.medsy.domain.model.DoseStatus
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Enforces the PRN ("as needed") daily ceiling.
 *
 * A PRN schedule has no fixed times, so the only guard against accidental over-use is a
 * daily maximum. The limit is checked *before* writing the dose, because a limit that is
 * only displayed — or only reported afterwards — is not a limit.
 */
object PrnLimit {

    sealed interface Decision {
        data object Allowed : Decision

        data class WouldExceed(
            val takenToday: BigDecimal,
            val limit: BigDecimal,
            val dose: BigDecimal,
            val projected: BigDecimal,
        ) : Decision
    }

    /**
     * Only doses that were actually consumed count. A skipped or merely snoozed dose has
     * not entered the body, so counting it would make the limit fire too early.
     */
    fun takenToday(
        logs: List<DoseLog>,
        scheduleId: Long,
        onDate: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): BigDecimal = logs
        .filter { it.scheduleId == scheduleId }
        .filter { it.status == DoseStatus.TAKEN || it.status == DoseStatus.LATE }
        .filter { log ->
            val at = log.actualTimestamp ?: log.scheduledTimestamp
            at.atZone(zone).toLocalDate() == onDate
        }
        .fold(BigDecimal.ZERO) { acc, log ->
            acc.add(log.actualDoseAmount ?: BigDecimal.ZERO)
        }

    fun check(
        logs: List<DoseLog>,
        scheduleId: Long,
        limit: BigDecimal?,
        dose: BigDecimal,
        at: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Decision {
        // No limit configured means PRN is unconstrained, which is the documented default.
        if (limit == null) return Decision.Allowed
        if (limit.signum() <= 0) return Decision.Allowed
        if (dose.signum() <= 0) return Decision.Allowed

        val taken = takenToday(logs, scheduleId, at.atZone(zone).toLocalDate(), zone)
        val projected = taken.add(dose)
        return if (projected > limit) {
            Decision.WouldExceed(
                takenToday = taken,
                limit = limit,
                dose = dose,
                projected = projected,
            )
        } else {
            Decision.Allowed
        }
    }

    /** Human-readable message for the confirmation prompt. */
    fun describe(decision: Decision.WouldExceed): Message = message(
        MessageKey.PRN_WOULD_EXCEED,
        decision.projected.stripTrailingZeros().toPlainString(),
        decision.limit.stripTrailingZeros().toPlainString(),
        decision.takenToday.stripTrailingZeros().toPlainString(),
    )
}
