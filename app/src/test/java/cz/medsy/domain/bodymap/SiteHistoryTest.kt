package cz.medsy.domain.bodymap

import cz.medsy.domain.calc.SiteRotation
import cz.medsy.domain.model.ApplicationSite
import cz.medsy.domain.model.MedicationForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * The map and the rotation suggestion must be two views of the same history. If they
 * disagree the patient is drawn to one site and told to use another, and the habit that
 * keeps injection sites healthy quietly breaks.
 */
class SiteHistoryTest {

    private val now = Instant.parse("2026-11-11T12:00:00Z")
    private val imForm = MedicationForm.INJECTION_IM

    @Test
    fun `the suggested site is the one the map colours as never used`() {
        val history = listOf(
            ApplicationSite.LEFT_DELTOID to now.minus(Duration.ofDays(1)),
            ApplicationSite.RIGHT_DELTOID to now.minus(Duration.ofDays(3)),
        )

        val suggestion = SiteRotation.recommend(imForm, history, now)!!.site
        val state = BodyMap.states(imForm, history, now).first { it.site == suggestion }

        assertEquals(BodyMap.Freshness.NEVER, state.freshness)
        assertNull(state.lastUsed)
    }

    @Test
    fun `a used site is only suggested once every candidate was used`() {
        val history = ApplicationSite.forForm(imForm)
            .map { it to now.minus(Duration.ofDays(30)) }

        val suggestion = SiteRotation.recommend(imForm, history, now)!!.site
        val state = BodyMap.states(imForm, history, now).first { it.site == suggestion }

        assertNotEquals(BodyMap.Freshness.NEVER, state.freshness)
    }

    @Test
    fun `history of another form does not move the suggestion`() {
        val transdermalUse = listOf(
            ApplicationSite.FOREARM_LEFT to now.minus(Duration.ofHours(2)),
        )

        val suggestion = SiteRotation.recommend(imForm, transdermalUse, now)!!.site

        assertTrue(
            "an injection suggestion must come from the injection list",
            suggestion in ApplicationSite.forForm(imForm),
        )
        assertEquals(ApplicationSite.forForm(imForm).first(), suggestion)
    }

    @Test
    fun `the map offers exactly the sites the rotation algorithm considers`() {
        MedicationForm.entries.forEach { form ->
            val states = BodyMap.states(form, emptyList(), now)
            assertEquals(
                "$form",
                ApplicationSite.forForm(form).toSet(),
                states.map { it.site }.toSet(),
            )
        }
    }

    @Test
    fun `using the suggested site moves the suggestion to the other side`() {
        val first = SiteRotation.recommend(imForm, emptyList(), now)!!.site
        val second = SiteRotation.recommend(imForm, listOf(first to now), now)!!.site

        assertNotEquals(first, second)
        val state = BodyMap.states(imForm, listOf(first to now), now).first { it.site == second }
        assertEquals(BodyMap.Freshness.NEVER, state.freshness)
    }
}
