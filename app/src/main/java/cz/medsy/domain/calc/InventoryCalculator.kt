package cz.medsy.domain.calc

import cz.medsy.domain.message.Message
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.message.message
import cz.medsy.domain.message.messagePlural
import cz.medsy.domain.model.InventoryBatch
import cz.medsy.domain.model.MedicationForm
import cz.medsy.domain.model.Schedule
import cz.medsy.domain.model.ScheduleType
import cz.medsy.domain.model.TaperStep
import cz.medsy.domain.schedule.ScheduleEngine
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

object InventoryCalculator {

    private val MC = MathContext(20, RoundingMode.HALF_UP)

    /** A batch closer than this to its printed expiry is reported. */
    const val EXPIRY_WARNING_DAYS = 30L

    /** A punctured vial closer than this to its beyond-use date is reported. */
    const val BUD_WARNING_DAYS = 7L

    data class StockLine(
        val medicationId: Long,
        val medicationName: String,
        val unit: String,
        /** Everything physically on the shelf, including unusable stock. */
        val remaining: BigDecimal,
        /** Only stock that may be used today (not expired, not past its BUD). */
        val usableRemaining: BigDecimal,
        val perDay: BigDecimal,
        val daysRemaining: Int,
        val isLow: Boolean,
        /** Expires within [EXPIRY_WARNING_DAYS] but is still usable. */
        val expiringBatches: List<InventoryBatch>,
        /** Already past the printed expiry date. */
        val expiredBatches: List<InventoryBatch>,
        /** Punctured and past its beyond-use date, with product left. */
        val beyondUsePassed: List<InventoryBatch>,
        val beyondUseSoon: List<InventoryBatch>,
        /** Gel/spray pump count, when the stock is counted in actuations. */
        val meter: MeteredDose.Meter? = null,
        /**
         * The box that will be opened next. Carries the lot number and expiry, which is the only
         * thing a hand-added package can be checked against — the name is whatever the user typed.
         */
        val nextBatch: InventoryBatch? = null,
    )

    fun totalRemaining(batches: List<InventoryBatch>, medicationId: Long): BigDecimal =
        batches.filter { it.medicationId == medicationId }
            .fold(BigDecimal.ZERO) { acc, batch -> acc.add(batch.remainingQuantity) }

    /**
     * Average daily consumption derived from the schedules.
     *
     * [onDate] matters for tapering: the step in force *today* is what the patient
     * actually consumes. Using the first step reported the highest, long-finished dose
     * and overstated how long the remaining stock lasts.
     */
    fun dailyConsumption(schedules: List<Schedule>, onDate: LocalDate = LocalDate.now()): BigDecimal {
        if (schedules.isEmpty()) return BigDecimal.ZERO
        return schedules.filter { it.isActive }.fold(BigDecimal.ZERO) { acc, schedule ->
            acc.add(dailyFor(schedule, onDate))
        }
    }

    private fun dailyFor(schedule: Schedule, onDate: LocalDate): BigDecimal {
        val interval = schedule.intervalValue
        // A schedule with several times of day is taken that many times per day.
        val dosesPerDay = BigDecimal(schedule.timesOfDay.size.coerceAtLeast(1))
        return when (schedule.scheduleType) {
            ScheduleType.FIXED_DAILY -> schedule.doseAmount.multiply(dosesPerDay, MC)
            ScheduleType.CYCLICAL -> {
                val on = interval?.toInt()?.takeIf { it > 0 } ?: 21
                val cycle = on + 7
                schedule.doseAmount.multiply(dosesPerDay, MC)
                    .multiply(BigDecimal(on), MC)
                    .divide(BigDecimal(cycle), MC)
            }
            // Interval schedules are defined by the clock, so timesOfDay is irrelevant.
            ScheduleType.INTERVAL_HOURS -> {
                val hours = interval?.takeIf { it.signum() > 0 } ?: return BigDecimal.ZERO
                schedule.doseAmount.multiply(BigDecimal(24), MC).divide(hours, MC)
            }
            ScheduleType.INTERVAL_DAYS -> {
                val days = interval?.takeIf { it.signum() > 0 } ?: return BigDecimal.ZERO
                schedule.doseAmount.divide(days, MC)
            }
            ScheduleType.TAPERING -> currentTaperStep(schedule, onDate)
                .doseAmount.multiply(dosesPerDay, MC)
            ScheduleType.PRN -> BigDecimal.ZERO
        }
    }

    private fun currentTaperStep(schedule: Schedule, onDate: LocalDate): TaperStep {
        val steps = schedule.taperSteps.ifEmpty {
            listOf(TaperStep(schedule.doseAmount, Int.MAX_VALUE))
        }
        if (onDate.isBefore(schedule.startDate)) return steps.first()
        var elapsed = ChronoUnit.DAYS.between(schedule.startDate, onDate).toInt()
        for (step in steps) {
            val duration = if (step.durationDays <= 0) Int.MAX_VALUE else step.durationDays
            if (elapsed < duration) return step
            elapsed -= duration
        }
        // The taper has finished; the last step is what remains.
        return steps.last()
    }

    fun daysRemaining(remaining: BigDecimal, perDay: BigDecimal): Int {
        if (perDay.signum() <= 0) return Int.MAX_VALUE
        if (remaining.signum() <= 0) return 0
        return remaining.divide(perDay, 2, RoundingMode.DOWN).toInt().coerceAtLeast(0)
    }

    fun buildStockLine(
        medicationId: Long,
        medicationName: String,
        unit: String,
        batches: List<InventoryBatch>,
        schedules: List<Schedule>,
        today: LocalDate,
        thresholdDays: Int = 7,
    ): StockLine {
        val own = batches.filter { it.medicationId == medicationId }
        val remaining = own.fold(BigDecimal.ZERO) { acc, b -> acc.add(b.remainingQuantity) }
        val perDay = dailyConsumption(schedules.filter { it.medicationId == medicationId }, today)

        val expired = own.filter { it.isExpired(today) }
        val budPassed = own.filter { it.isPastBeyondUse(today) && it.remainingQuantity.signum() > 0 }

        // Expired or past-BUD stock is still on the shelf but must not be counted as
        // available, otherwise the app promises days of supply that cannot be taken.
        val usable = own
            .filterNot { it.isExpired(today) || it.isPastBeyondUse(today) }
            .fold(BigDecimal.ZERO) { acc, b -> acc.add(b.remainingQuantity) }

        val days = daysRemaining(usable, perDay)
        val expiryCutoff = today.plusDays(EXPIRY_WARNING_DAYS)
        val expiring = own.filter { batch ->
            val expiry = batch.expirationDate ?: return@filter false
            expiry.isAfter(today) && !expiry.isAfter(expiryCutoff)
        }
        val meter = own.firstNotNullOfOrNull { batch ->
            MeteredDose.meterFor(
                batch,
                schedules.firstOrNull { it.medicationId == medicationId },
            )
        }
        // Earliest expiry among usable stock; when nothing carries a date, fall back to the first
        // box that is at least identifiable by its lot number.
        val usableBatches = own.filterNot { it.isExpired(today) || it.isPastBeyondUse(today) }
        val nextBatch = usableBatches.filter { it.expirationDate != null }
            .minByOrNull { requireNotNull(it.expirationDate) }
            ?: usableBatches.firstOrNull { !it.batchNumber.isNullOrBlank() }
        val budSoon = own.filter { batch ->
            val bud = batch.beyondUseDate() ?: return@filter false
            !bud.isBefore(today) && !bud.isAfter(today.plusDays(BUD_WARNING_DAYS))
        }
        return StockLine(
            medicationId = medicationId,
            medicationName = medicationName,
            unit = unit,
            remaining = remaining,
            usableRemaining = usable,
            perDay = perDay,
            daysRemaining = days,
            isLow = days != Int.MAX_VALUE && days <= thresholdDays,
            expiringBatches = expiring,
            expiredBatches = expired,
            beyondUsePassed = budPassed,
            beyondUseSoon = budSoon,
            meter = meter,
            nextBatch = nextBatch,
        )
    }

    fun InventoryBatch.isExpired(today: LocalDate): Boolean =
        expirationDate?.isBefore(today) == true

    fun InventoryBatch.isPastBeyondUse(today: LocalDate): Boolean =
        beyondUseDate()?.isBefore(today) == true

    data class TravelItem(
        val medicationId: Long,
        val medicationName: String,
        val unit: String,
        val required: BigDecimal,
        val available: BigDecimal,
        val shortfall: BigDecimal,
        val suggestion: Message,
    )

    data class TravelConsumable(
        val kind: ConsumableKind,
        val label: Message,
        val required: Int,
        val available: Int,
        val shortfall: Int,
    )

    data class TravelPlan(
        val tripDays: Int,
        val items: List<TravelItem>,
        val hasShortfall: Boolean,
        /** Injection supplies for the planned injections; empty when nothing is injected. */
        val consumables: List<TravelConsumable> = emptyList(),
        val injectionCount: Int = 0,
    )

    /**
     * Trip quantity = ceil(perDay × (tripDays + buffer)), because partial tablets
     * still have to be packed as whole tablets.
     */
    fun travelPlan(
        schedulesByMedication: Map<Long, List<Schedule>>,
        names: Map<Long, String>,
        units: Map<Long, String>,
        availableByMedication: Map<Long, BigDecimal>,
        tripDays: Int,
        bufferDays: Int,
        tripStart: LocalDate = LocalDate.now(),
        formsByMedication: Map<Long, MedicationForm> = emptyMap(),
        consumableStock: Map<ConsumableKind, Int> = emptyMap(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): TravelPlan {
        val totalDays = (tripDays + bufferDays).coerceAtLeast(0)
        val items = schedulesByMedication.map { (medicationId, schedules) ->
            val perDay = dailyConsumption(schedules, tripStart)
            val required = ceilToUnit(perDay.multiply(BigDecimal(totalDays), MC))
            val available = availableByMedication[medicationId] ?: BigDecimal.ZERO
            val shortfall = required.subtract(available).max(BigDecimal.ZERO)
            val suggestion = when {
                perDay.signum() <= 0 -> message(MessageKey.INVENTORY_PRN_MANUAL)
                shortfall.signum() <= 0 ->
                    messagePlural(MessageKey.INVENTORY_ENOUGH_DAYS, totalDays, totalDays)
                else -> message(
                    MessageKey.INVENTORY_GET_MORE,
                    shortfall.stripTrailingZeros().toPlainString(),
                    units[medicationId].orEmpty(),
                )
            }
            TravelItem(
                medicationId = medicationId,
                medicationName = names[medicationId] ?: "Medication $medicationId",
                unit = units[medicationId].orEmpty(),
                required = required,
                available = available,
                shortfall = shortfall,
                suggestion = suggestion,
            )
        }
        val injections = injectionCount(
            schedulesByMedication = schedulesByMedication,
            formsByMedication = formsByMedication,
            totalDays = totalDays,
            tripStart = tripStart,
            zone = zone,
        )
        val consumables = if (injections <= 0) {
            emptyList()
        } else {
            consumablesPerInjection().map { (kind, perInjection) ->
                val required = perInjection * injections
                // Unrecorded stock is unknown, not plentiful: the user is told to check.
                val available = consumableStock[kind] ?: 0
                TravelConsumable(
                    kind = kind,
                    label = kind.label(),
                    required = required,
                    available = available,
                    shortfall = (required - available).coerceAtLeast(0),
                )
            }.sortedBy { it.kind.ordinal }
        }
        return TravelPlan(
            tripDays = totalDays,
            items = items,
            hasShortfall = items.any { it.shortfall.signum() > 0 } ||
                consumables.any { it.shortfall > 0 },
            consumables = consumables,
            injectionCount = injections,
        )
    }

    /**
     * Counts the injections actually scheduled inside the trip window. PRN schedules are
     * excluded on purpose: they are not "necessary doses" and would otherwise inflate the
     * needle count by a guess.
     */
    fun injectionCount(
        schedulesByMedication: Map<Long, List<Schedule>>,
        formsByMedication: Map<Long, MedicationForm>,
        totalDays: Int,
        tripStart: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int {
        if (totalDays <= 0) return 0
        val from = tripStart.atStartOfDay(zone).toInstant()
        val until = tripStart.plusDays(totalDays.toLong()).atStartOfDay(zone).toInstant()
        return schedulesByMedication.entries.sumOf { (medicationId, schedules) ->
            val form = formsByMedication[medicationId] ?: return@sumOf 0
            if (!form.isInjectable) return@sumOf 0
            schedules.sumOf { ScheduleEngine.occurrences(it, from, until, zone).size }
        }
    }

    /**
     * Consumable rows are free-text names, so the kind has to be sniffed from the name.
     * "Draw needle" is checked before "needle" — otherwise every draw needle counts as an
     * injection needle.
     */
    fun consumableKind(name: String): ConsumableKind? = when {
        name.contains("swab", ignoreCase = true) -> ConsumableKind.ALCOHOL_SWAB
        name.contains("draw", ignoreCase = true) -> ConsumableKind.DRAW_NEEDLE
        name.contains("needle", ignoreCase = true) -> ConsumableKind.INJECTION_NEEDLE
        name.contains("syringe", ignoreCase = true) -> ConsumableKind.SYRINGE
        else -> null
    }

    /** Whole units only: half a tablet still has to be packed as one tablet. */
    fun ceilToUnit(value: BigDecimal): BigDecimal =
        if (value.signum() <= 0) BigDecimal.ZERO else value.setScale(0, RoundingMode.CEILING)

    fun daysBetween(start: LocalDate, end: LocalDate): Int =
        ChronoUnit.DAYS.between(start, end).toInt().coerceAtLeast(0) + 1

    /**
     * Consumables consumed per injection: 1 syringe, 1 draw needle, 1 injection
     * needle, 2 alcohol swabs.
     */
    fun consumablesPerInjection(): Map<ConsumableKind, Int> = mapOf(
        ConsumableKind.SYRINGE to 1,
        ConsumableKind.DRAW_NEEDLE to 1,
        ConsumableKind.INJECTION_NEEDLE to 1,
        ConsumableKind.ALCOHOL_SWAB to 2,
    )

    enum class ConsumableKind { SYRINGE, DRAW_NEEDLE, INJECTION_NEEDLE, ALCOHOL_SWAB }
}

object AdherenceCalculator {

    fun summarize(taken: Int, skipped: Int, expected: Int): cz.medsy.domain.model.AdherenceSummary {
        val missed = (expected - taken - skipped).coerceAtLeast(0)
        return cz.medsy.domain.model.AdherenceSummary(taken = taken, skipped = skipped, missed = missed)
    }

    fun percent(taken: Int, expected: Int): Double =
        if (expected <= 0) 0.0 else (taken * 100.0 / expected).coerceIn(0.0, 100.0)
}

/** Plural, user-facing names for the supply rows in the travel plan. */
fun InventoryCalculator.ConsumableKind.label(): Message = message(
    when (this) {
        InventoryCalculator.ConsumableKind.SYRINGE -> MessageKey.CONSUMABLE_SYRINGES
        InventoryCalculator.ConsumableKind.DRAW_NEEDLE -> MessageKey.CONSUMABLE_DRAW_NEEDLES
        InventoryCalculator.ConsumableKind.INJECTION_NEEDLE -> MessageKey.CONSUMABLE_INJECTION_NEEDLES
        InventoryCalculator.ConsumableKind.ALCOHOL_SWAB -> MessageKey.CONSUMABLE_ALCOHOL_SWABS
    },
)
