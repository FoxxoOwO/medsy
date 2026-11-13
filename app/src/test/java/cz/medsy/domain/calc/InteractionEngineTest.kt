package cz.medsy.domain.calc

import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.model.AlertKind
import cz.medsy.domain.model.InteractionSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractionEngineTest {

    private fun sub(name: String = "", atc: String? = null, id: Long = 0) =
        InteractionEngine.ActiveSubstance(name, atc, id)

    // --- identification: ATC-only and name-only must both work -------------------

    @Test
    fun `medication with only an atc code is still evaluated`() {
        val alerts = InteractionEngine.evaluate(listOf(sub(atc = "C03DA01"), sub(atc = "C09AA05")))
        assertTrue(
            alerts.any { it.title.key == MessageKey.INTERACTION_SPIRONOLACTONE_ACE_ARB_TITLE },
        )
    }

    @Test
    fun `medication with only a substance name is still evaluated`() {
        val alerts = InteractionEngine.evaluate(listOf(sub("spironolacton"), sub("ramipril")))
        assertTrue(alerts.isNotEmpty())
    }

    @Test
    fun `blank medication is ignored`() {
        assertTrue(InteractionEngine.evaluate(listOf(sub(""), sub("  "))).isEmpty())
    }

    @Test
    fun `atc matching is case insensitive`() {
        val alerts = InteractionEngine.evaluate(listOf(sub(atc = "c03da01"), sub(atc = "c09aa05")))
        assertTrue(alerts.isNotEmpty())
    }

    // --- drug-drug rules --------------------------------------------------------

    @Test
    fun `warfarin plus nsaid is severe`() {
        val alerts = InteractionEngine.evaluate(listOf(sub(atc = "B01AA03"), sub(atc = "M01AE01")))
        assertEquals(InteractionSeverity.SEVERE, alerts.first().severity)
    }

    @Test
    fun `rule fires regardless of argument order`() {
        val forward = InteractionEngine.evaluate(listOf(sub(atc = "C03DA01"), sub(atc = "C09AA05")))
        val backward = InteractionEngine.evaluate(listOf(sub(atc = "C09AA05"), sub(atc = "C03DA01")))
        assertEquals(forward.map { it.id }.toSet(), backward.map { it.id }.toSet())
    }

    @Test
    fun `levothyroxine plus iron is flagged`() {
        val alerts = InteractionEngine.evaluate(listOf(sub(atc = "H03AA01"), sub(atc = "B03AA07")))
        assertTrue(
            alerts.any { it.title.key == MessageKey.INTERACTION_LEVOTHYROXINE_MINERALS_TITLE },
        )
    }

    @Test
    fun `duplicate rule hits are collapsed`() {
        val alerts = InteractionEngine.evaluate(
            listOf(sub("warfarin", "B01AA03"), sub("warfarin", "B01AA03"), sub("ibuprofen", "M01AE01")),
        )
        assertEquals(alerts.size, alerts.distinctBy { it.id }.size)
    }

    @Test
    fun `alerts are sorted by severity descending`() {
        val alerts = InteractionEngine.evaluate(
            listOf(sub(atc = "C03DA01"), sub(atc = "C09AA05"), sub(atc = "B01AA03"), sub(atc = "M01AE01")),
        )
        val severities = alerts.map { it.severity.ordinal }
        assertEquals(severities.sortedDescending(), severities)
    }

    // --- lifestyle / food -------------------------------------------------------

    @Test
    fun `sukl latin names match lifestyle rules`() {
        val alerts = InteractionEngine.evaluate(
            listOf(sub("Magnesii oxidum"), sub("Calcii carbonas"), sub("Ferri sulfas")),
        )
        assertTrue(alerts.isNotEmpty())
    }

    @Test
    fun `czech names with diacritics match`() {
        val alerts = InteractionEngine.evaluate(listOf(sub("železo")))
        assertTrue(alerts.any { it.kind == AlertKind.SUPPLEMENT })
    }

    @Test
    fun `spironolactone gets a potassium warning`() {
        val alerts = InteractionEngine.evaluate(listOf(sub(atc = "C03DA01")))
        assertTrue(
            alerts.any { it.title.key == MessageKey.INTERACTION_SPIRO_POTASSIUM_TITLE },
        )
    }

    @Test
    fun `statin gets grapefruit advice`() {
        val alerts = InteractionEngine.evaluate(listOf(sub(atc = "C10AA05")))
        assertTrue(
            alerts.any {
                it.kind == AlertKind.FOOD && it.title.key == MessageKey.INTERACTION_STATIN_GRAPEFRUIT_TITLE
            },
        )
    }

    @Test
    fun `oestrogen gets a smoking warning`() {
        val alerts = InteractionEngine.evaluate(listOf(sub(atc = "G03CA03")))
        assertTrue(alerts.any { it.title.key == MessageKey.INTERACTION_OESTROGEN_SMOKING_TITLE })
    }

    @Test
    fun `unproblematic drug produces no severe alert`() {
        val alerts = InteractionEngine.evaluate(listOf(sub("paracetamol", "N02BE01")))
        assertFalse(alerts.any { it.severity == InteractionSeverity.SEVERE })
    }

    // --- absorption conflicts surfaced as alerts --------------------------------

    @Test
    fun `iron plus calcium absorption conflict is reported as an alert`() {
        val alerts = InteractionEngine.evaluate(
            listOf(sub("Ferrosi sulfas", "B03AA07"), sub("Calcii carbonas", "A12AA04")),
        )
        assertTrue("absorption conflict must reach the UI", alerts.any { it.id.startsWith("absorption:") })
    }

    @Test
    fun `absorption conflict requires at least two hours`() {
        val conflicts = InteractionEngine.absorptionConflicts(
            listOf(sub("Ferrosi sulfas"), sub("Calcii carbonas")),
        )
        assertTrue(conflicts.any { it.requiredGapMinutes == 120 })
    }

    @Test
    fun `levothyroxine requires four hours from calcium`() {
        val conflicts = InteractionEngine.absorptionConflicts(
            listOf(sub("Levothyroxinum"), sub("Calcii carbonas")),
        )
        assertTrue(conflicts.any { it.requiredGapMinutes == 240 })
    }

    @Test
    fun `absorption conflict carries medication ids for scheduling`() {
        val conflicts = InteractionEngine.absorptionConflicts(
            listOf(sub("Ferrosi sulfas", id = 7), sub("Calcii carbonas", id = 9)),
        )
        assertEquals(setOf(7L, 9L), conflicts.first().medicationIds.toSet())
    }

    @Test
    fun `single mineral produces no conflict`() {
        assertTrue(InteractionEngine.absorptionConflicts(listOf(sub("Ferrosi sulfas"))).isEmpty())
    }

    // --- helpers ----------------------------------------------------------------

    @Test
    fun `highest severity picks the worst alert`() {
        val alerts = InteractionEngine.evaluate(
            listOf(sub(atc = "C03DA01"), sub(atc = "C09AA05"), sub(atc = "B01AA03"), sub(atc = "M01AE01")),
        )
        assertEquals(InteractionSeverity.SEVERE, InteractionEngine.highestSeverity(alerts))
    }

    @Test
    fun `name normalisation strips diacritics and case`() {
        assertEquals("zelezo", InteractionEngine.normalizeName("Železo"))
        assertEquals("magnesii oxidum", InteractionEngine.normalizeName("Magnesii   Oxidum"))
    }
}
