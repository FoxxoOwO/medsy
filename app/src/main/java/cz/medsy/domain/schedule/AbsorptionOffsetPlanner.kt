package cz.medsy.domain.schedule

import cz.medsy.domain.calc.InteractionEngine
import cz.medsy.domain.message.Message
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.message.message
import cz.medsy.domain.model.Schedule
import java.time.LocalTime

/**
 * Decides which reminder to move when two medications must not be taken together
 * (iron vs calcium, levothyroxine vs minerals).
 *
 * Rules, in order of importance:
 *  1. **Never override a deliberate setting.** If the user already set an offset on a
 *     schedule, that is a clinical decision and the app must not "fix" it. The conflict
 *     is reported as advice instead.
 *  2. Move the *later* dose, not the earlier one: shifting the mineral later keeps the
 *     anchor medication (levothyroxine, taken fasting on waking) where the patient
 *     expects it.
 *  3. The shift must actually clear the gap; a partial shift would look fixed while
 *     still violating the rule.
 */
object AbsorptionOffsetPlanner {

    data class Plan(
        val scheduleId: Long,
        val medicationId: Long,
        val fromOffsetMinutes: Int,
        val toOffsetMinutes: Int,
        val requiredGapMinutes: Int,
        val explanation: Message,
    )

    data class Outcome(
        val plans: List<Plan>,
        /** Conflicts that cannot be fixed automatically, with the reason. */
        val unresolved: List<Unresolved>,
    )

    data class Unresolved(
        val pair: String,
        val reason: Message,
        val detail: Message,
    )

    /**
     * @param schedules the active schedules with their times and current offsets
     * @param conflicts the conflicts from [InteractionEngine.absorptionConflicts]
     */
    fun plan(
        schedules: List<Schedule>,
        conflicts: List<InteractionEngine.AbsorptionConflict>,
    ): Outcome {
        val plans = LinkedHashMap<Long, Plan>()
        val unresolved = ArrayList<Unresolved>()

        for (conflict in conflicts) {
            val involved = schedules.filter { it.medicationId in conflict.medicationIds }
            if (involved.size < 2) {
                unresolved += Unresolved(
                    pair = conflict.pair,
                    reason = message(MessageKey.ABSORPTION_ONLY_ONE_SCHEDULE),
                    detail = message(conflict.detail),
                )
                continue
            }

            val alreadyConfigured = involved.filter { it.offsetMinutes != 0 }
            if (alreadyConfigured.isNotEmpty()) {
                unresolved += Unresolved(
                    pair = conflict.pair,
                    reason = message(MessageKey.ABSORPTION_OFFSET_ALREADY_SET),
                    detail = message(conflict.detail),
                )
                continue
            }

            val anchorTime = involved.minOfOrNull { it.earliestTime() }
            val mover = involved
                .filter { it.earliestTime() != anchorTime }
                .maxByOrNull { it.earliestTime() }
                ?: involved.maxByOrNull { it.earliestTime() }

            if (mover == null) {
                unresolved += Unresolved(conflict.pair, message(MessageKey.ABSORPTION_NO_SCHEDULE_TO_MOVE), message(conflict.detail))
                continue
            }

            plans[mover.id] = Plan(
                scheduleId = mover.id,
                medicationId = mover.medicationId,
                fromOffsetMinutes = mover.offsetMinutes,
                toOffsetMinutes = conflict.requiredGapMinutes,
                requiredGapMinutes = conflict.requiredGapMinutes,
                explanation = message(
                    MessageKey.ABSORPTION_EXPLANATION,
                    conflict.requiredGapMinutes,
                    message(conflict.detail),
                ),
            )
        }

        return Outcome(plans = plans.values.toList(), unresolved = unresolved)
    }

    private fun Schedule.earliestTime(): LocalTime =
        timesOfDay.minOrNull() ?: LocalTime.of(8, 0)
}
