package cz.medsy.domain.schedule

import cz.medsy.domain.message.plain
import cz.medsy.domain.message.plainArgs

import cz.medsy.domain.message.MessageArg
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.calc.InteractionEngine
import cz.medsy.domain.model.MealRelation
import cz.medsy.domain.model.Schedule
import cz.medsy.domain.model.ScheduleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Moving a reminder is a clinical decision, so the planner must be conservative: it may
 * only touch schedules the user has not configured, and must never silently leave a gap
 * half-applied.
 */
class AbsorptionOffsetPlannerTest {

    private fun schedule(
        id: Long,
        medicationId: Long,
        time: LocalTime,
        offset: Int = 0,
    ) = Schedule(
        id = id,
        medicationId = medicationId,
        scheduleType = ScheduleType.FIXED_DAILY,
        timesOfDay = listOf(time),
        doseAmount = BigDecimal.ONE,
        doseUnit = "tbl",
        mealRelation = MealRelation.NONE,
        offsetMinutes = offset,
        startDate = LocalDate.of(2026, 1, 1),
    )

    private fun conflict(
        pair: String = "iron/calcium-magnesium",
        ids: List<Long> = listOf(1, 2),
        gap: Int = 120,
    ) = InteractionEngine.AbsorptionConflict(
        pair = pair,
        requiredGapMinutes = gap,
        detail = MessageKey.INTERACTION_ABSORPTION_IRON_CALCIUM,
        medicationIds = ids,
    )

    @Test
    fun `the later dose is moved, not the earlier one`() {
        val schedules = listOf(
            schedule(10, medicationId = 1, time = LocalTime.of(8, 0)),
            schedule(20, medicationId = 2, time = LocalTime.of(20, 0)),
        )

        val outcome = AbsorptionOffsetPlanner.plan(schedules, listOf(conflict()))

        assertEquals(1, outcome.plans.size)
        assertEquals(20L, outcome.plans.single().scheduleId)
        assertEquals(120, outcome.plans.single().toOffsetMinutes)
    }

    @Test
    fun `two doses at the same time move one of them`() {
        val schedules = listOf(
            schedule(10, medicationId = 1, time = LocalTime.of(8, 0)),
            schedule(20, medicationId = 2, time = LocalTime.of(8, 0)),
        )

        val outcome = AbsorptionOffsetPlanner.plan(schedules, listOf(conflict()))

        assertEquals(1, outcome.plans.size)
        assertEquals(120, outcome.plans.single().toOffsetMinutes)
    }

    @Test
    fun `an existing user offset is never overridden`() {
        val schedules = listOf(
            schedule(10, medicationId = 1, time = LocalTime.of(8, 0)),
            schedule(20, medicationId = 2, time = LocalTime.of(20, 0), offset = 30),
        )

        val outcome = AbsorptionOffsetPlanner.plan(schedules, listOf(conflict()))

        assertTrue("must not move a configured schedule", outcome.plans.isEmpty())
        assertEquals(1, outcome.unresolved.size)
        assertEquals(MessageKey.ABSORPTION_OFFSET_ALREADY_SET, outcome.unresolved.single().reason.key)
    }

    @Test
    fun `a conflict with only one scheduled medication is reported, not guessed`() {
        val schedules = listOf(schedule(10, medicationId = 1, time = LocalTime.of(8, 0)))

        val outcome = AbsorptionOffsetPlanner.plan(schedules, listOf(conflict()))

        assertTrue(outcome.plans.isEmpty())
        assertEquals(1, outcome.unresolved.size)
        assertEquals(MessageKey.ABSORPTION_ONLY_ONE_SCHEDULE, outcome.unresolved.single().reason.key)
    }

    @Test
    fun `the required gap is taken from the conflict, not hardcoded`() {
        val schedules = listOf(
            schedule(10, medicationId = 1, time = LocalTime.of(7, 0)),
            schedule(20, medicationId = 2, time = LocalTime.of(9, 0)),
        )

        val outcome = AbsorptionOffsetPlanner.plan(
            schedules,
            listOf(conflict(pair = "levothyroxine/iron-calcium", gap = 240)),
        )

        assertEquals(240, outcome.plans.single().toOffsetMinutes)
        assertEquals(240, outcome.plans.single().requiredGapMinutes)
    }

    @Test
    fun `the explanation states the shift and the reason`() {
        val schedules = listOf(
            schedule(10, medicationId = 1, time = LocalTime.of(8, 0)),
            schedule(20, medicationId = 2, time = LocalTime.of(20, 0)),
        )

        val plan = AbsorptionOffsetPlanner.plan(schedules, listOf(conflict())).plans.single()

        assertEquals(MessageKey.ABSORPTION_EXPLANATION, plan.explanation.key)
        assertEquals("120", plan.explanation.plainArgs().first())
        val nested = plan.explanation.args.filterIsInstance<MessageArg.Nested>().single()
        assertEquals(MessageKey.INTERACTION_ABSORPTION_IRON_CALCIUM, nested.message.key)
    }

    @Test
    fun `no conflicts produce no plans`() {
        val outcome = AbsorptionOffsetPlanner.plan(
            listOf(schedule(10, 1, LocalTime.of(8, 0))),
            emptyList(),
        )
        assertTrue(outcome.plans.isEmpty())
        assertTrue(outcome.unresolved.isEmpty())
    }

    @Test
    fun `schedules not involved in the conflict are untouched`() {
        val schedules = listOf(
            schedule(10, medicationId = 1, time = LocalTime.of(8, 0)),
            schedule(20, medicationId = 2, time = LocalTime.of(20, 0)),
            schedule(30, medicationId = 99, time = LocalTime.of(12, 0)),
        )

        val outcome = AbsorptionOffsetPlanner.plan(schedules, listOf(conflict()))

        assertNull("an unrelated schedule must not be planned", outcome.plans.firstOrNull { it.scheduleId == 30L })
    }

    @Test
    fun `a schedule without explicit times still gets a sensible anchor`() {
        val schedules = listOf(
            schedule(10, medicationId = 1, time = LocalTime.of(8, 0)).copy(timesOfDay = emptyList()),
            schedule(20, medicationId = 2, time = LocalTime.of(20, 0)),
        )

        val outcome = AbsorptionOffsetPlanner.plan(schedules, listOf(conflict()))

        // Both anchor to 08:00, so exactly one plan is produced and it must carry a gap.
        assertEquals(1, outcome.plans.size)
        assertEquals(120, outcome.plans.single().toOffsetMinutes)
    }

    @Test
    fun `two different conflicts can produce two plans`() {
        val schedules = listOf(
            schedule(10, medicationId = 1, time = LocalTime.of(7, 0)),
            schedule(20, medicationId = 2, time = LocalTime.of(9, 0)),
            schedule(30, medicationId = 3, time = LocalTime.of(11, 0)),
        )

        val outcome = AbsorptionOffsetPlanner.plan(
            schedules,
            listOf(
                conflict(pair = "iron/calcium-magnesium", ids = listOf(1, 2), gap = 120),
                conflict(pair = "levothyroxine/iron-calcium", ids = listOf(1, 3), gap = 240),
            ),
        )

        assertTrue(outcome.plans.isNotEmpty())
        assertTrue(outcome.plans.all { it.toOffsetMinutes > 0 })
    }
}
