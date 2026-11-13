package cz.medsy.domain.calc

import cz.medsy.domain.message.Message
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.message.message
import cz.medsy.domain.model.AlertKind
import cz.medsy.domain.model.InteractionSeverity
import java.text.Normalizer
import java.util.Locale

data class InteractionAlert(
    val id: String,
    val kind: AlertKind,
    val severity: InteractionSeverity,
    val title: Message,
    val detail: Message,
    val involvesAtc: List<String> = emptyList(),
    val involvesMedications: List<Long> = emptyList(),
)

/**
 * Static, offline interaction rules. Deliberately conservative: these are
 * *information* alerts for the patient's own review, never a dosing recommendation.
 *
 * A medication is identified by its ATC code **and** its substance name, because SÚKL
 * data is inconsistent: some rows carry only an ATC code, others only a Latin
 * substance name ("Magnesii oxidum", "Calcii carbonas", "Ferri sulfas"). Requiring a
 * substance name silently dropped every ATC-only medication from the check.
 */
object InteractionEngine {

    /** A medication as it is actually stored. Either field may be empty. */
    data class ActiveSubstance(
        val name: String,
        val atcCode: String?,
        val medicationId: Long = 0,
    ) {
        val normalizedName: String get() = normalizeName(name)
    }

    private data class DrugRule(
        val a: List<String>,
        val b: List<String>,
        val severity: InteractionSeverity,
        val title: MessageKey,
        val detail: MessageKey,
    )

    private data class LifestyleRule(
        val matchAtc: List<String> = emptyList(),
        val matchSubstance: List<String> = emptyList(),
        val kind: AlertKind,
        val severity: InteractionSeverity,
        val title: MessageKey,
        val detail: MessageKey,
        val id: String,
    )

    private val drugRules = listOf(
        DrugRule(
            a = listOf("B01AA"), b = listOf("N02BA", "M01A"),
            severity = InteractionSeverity.SEVERE,
            title = MessageKey.INTERACTION_WARFARIN_ASPIRIN_NSAID_TITLE,
            detail = MessageKey.INTERACTION_WARFARIN_ASPIRIN_NSAID_DETAIL,
        ),
        DrugRule(
            a = listOf("C03DA"), b = listOf("C09A", "C09C"),
            severity = InteractionSeverity.MODERATE,
            title = MessageKey.INTERACTION_SPIRONOLACTONE_ACE_ARB_TITLE,
            detail = MessageKey.INTERACTION_SPIRONOLACTONE_ACE_ARB_DETAIL,
        ),
        DrugRule(
            a = listOf("C03DA"), b = listOf("C03CA"),
            severity = InteractionSeverity.MODERATE,
            title = MessageKey.INTERACTION_SPIRONOLACTONE_LOOP_TITLE,
            detail = MessageKey.INTERACTION_SPIRONOLACTONE_LOOP_DETAIL,
        ),
        DrugRule(
            a = listOf("G03CA"), b = listOf("C03DA"),
            severity = InteractionSeverity.MINOR,
            title = MessageKey.INTERACTION_ESTRADIOL_SPIRONOLACTONE_TITLE,
            detail = MessageKey.INTERACTION_ESTRADIOL_SPIRONOLACTONE_DETAIL,
        ),
        DrugRule(
            a = listOf("H03AA"), b = listOf("A02BC"),
            severity = InteractionSeverity.MODERATE,
            title = MessageKey.INTERACTION_LEVOTHYROXINE_PPI_TITLE,
            detail = MessageKey.INTERACTION_LEVOTHYROXINE_PPI_DETAIL,
        ),
        DrugRule(
            a = listOf("H03AA"), b = listOf("A02AA", "A12AA", "B03A"),
            severity = InteractionSeverity.MODERATE,
            title = MessageKey.INTERACTION_LEVOTHYROXINE_MINERALS_TITLE,
            detail = MessageKey.INTERACTION_LEVOTHYROXINE_MINERALS_DETAIL,
        ),
        DrugRule(
            a = listOf("B03A"), b = listOf("A02AA", "A12AA"),
            severity = InteractionSeverity.MINOR,
            title = MessageKey.INTERACTION_IRON_ANTACID_TITLE,
            detail = MessageKey.INTERACTION_IRON_ANTACID_DETAIL,
        ),
        DrugRule(
            a = listOf("G03C"), b = listOf("B01AA"),
            severity = InteractionSeverity.MODERATE,
            title = MessageKey.INTERACTION_OESTROGEN_WARFARIN_TITLE,
            detail = MessageKey.INTERACTION_OESTROGEN_WARFARIN_DETAIL,
        ),
        DrugRule(
            a = listOf("C10AA", "C10AB"), b = listOf("J02AC"),
            severity = InteractionSeverity.SEVERE,
            title = MessageKey.INTERACTION_STATIN_AZOLE_TITLE,
            detail = MessageKey.INTERACTION_STATIN_AZOLE_DETAIL,
        ),
        DrugRule(
            a = listOf("J05A"), b = listOf("H02A"),
            severity = InteractionSeverity.SEVERE,
            title = MessageKey.INTERACTION_ANTIVIRAL_STEROID_TITLE,
            detail = MessageKey.INTERACTION_ANTIVIRAL_STEROID_DETAIL,
        ),
    )

    /**
     * Food/supplement/lifestyle alerts. Keywords are matched against a diacritics-free,
     * lowercase form of the substance name and include the Latin stems SÚKL publishes.
     */
    private val lifestyleRules = listOf(
        LifestyleRule(
            matchAtc = listOf("H02AB", "H02AA", "H01BA"),
            matchSubstance = listOf("prednison", "dexamethason", "hydrocortison", "cortisol", "methylprednisolon"),
            kind = AlertKind.FOOD,
            severity = InteractionSeverity.MODERATE,
            title = MessageKey.INTERACTION_STEROID_POTASSIUM_TITLE,
            detail = MessageKey.INTERACTION_STEROID_POTASSIUM_DETAIL,
            id = "steroid-potassium",
        ),
        LifestyleRule(
            matchAtc = listOf("C03DA"),
            matchSubstance = listOf("spironolacton", "spironolactone"),
            kind = AlertKind.FOOD,
            severity = InteractionSeverity.MODERATE,
            title = MessageKey.INTERACTION_SPIRO_POTASSIUM_TITLE,
            detail = MessageKey.INTERACTION_SPIRO_POTASSIUM_DETAIL,
            id = "spiro-potassium",
        ),
        LifestyleRule(
            matchAtc = listOf("C10AA", "C10AB", "C10BA"),
            matchSubstance = listOf("simvastatin", "atorvastatin", "lovastatin", "rosuvastatin"),
            kind = AlertKind.FOOD,
            severity = InteractionSeverity.MODERATE,
            title = MessageKey.INTERACTION_STATIN_GRAPEFRUIT_TITLE,
            detail = MessageKey.INTERACTION_STATIN_GRAPEFRUIT_DETAIL,
            id = "statin-grapefruit",
        ),
        LifestyleRule(
            matchAtc = listOf("L02B", "G03H"),
            matchSubstance = listOf("cyproteron", "cyproterone", "finasterid", "bicalutamid"),
            kind = AlertKind.LIFESTYLE,
            severity = InteractionSeverity.MINOR,
            title = MessageKey.INTERACTION_ANTIANDROGEN_PHOTO_TITLE,
            detail = MessageKey.INTERACTION_ANTIANDROGEN_PHOTO_DETAIL,
            id = "antiandrogen-photo",
        ),
        LifestyleRule(
            matchAtc = listOf("N05BA", "N06AA", "N06AB"),
            matchSubstance = listOf("diazepam", "amitriptylin", "sertralin", "escitalopram", "venlafaxin", "citalopram"),
            kind = AlertKind.LIFESTYLE,
            severity = InteractionSeverity.MODERATE,
            title = MessageKey.INTERACTION_ALCOHOL_SEDATION_TITLE,
            detail = MessageKey.INTERACTION_ALCOHOL_SEDATION_DETAIL,
            id = "alcohol-sedation",
        ),
        LifestyleRule(
            matchAtc = listOf("N02BE", "N02AX"),
            matchSubstance = listOf("paracetamol", "acetaminophen", "tramadol"),
            kind = AlertKind.LIFESTYLE,
            severity = InteractionSeverity.MODERATE,
            title = MessageKey.INTERACTION_ALCOHOL_PARACETAMOL_TITLE,
            detail = MessageKey.INTERACTION_ALCOHOL_PARACETAMOL_DETAIL,
            id = "alcohol-paracetamol",
        ),
        LifestyleRule(
            matchAtc = listOf("B01AA"),
            matchSubstance = listOf("warfarin"),
            kind = AlertKind.SUPPLEMENT,
            severity = InteractionSeverity.SEVERE,
            title = MessageKey.INTERACTION_WARFARIN_STJOHNS_TITLE,
            detail = MessageKey.INTERACTION_WARFARIN_STJOHNS_DETAIL,
            id = "warfarin-stjohns",
        ),
        LifestyleRule(
            matchSubstance = listOf("kalcium", "calcium", "calcii"),
            kind = AlertKind.FOOD,
            severity = InteractionSeverity.MINOR,
            title = MessageKey.INTERACTION_CALCIUM_DAIRY_TITLE,
            detail = MessageKey.INTERACTION_CALCIUM_DAIRY_DETAIL,
            id = "calcium-dairy",
        ),
        LifestyleRule(
            matchSubstance = listOf("magnesium", "magnesii", "magnesio"),
            kind = AlertKind.SUPPLEMENT,
            severity = InteractionSeverity.MINOR,
            title = MessageKey.INTERACTION_MAGNESIUM_DAIRY_TITLE,
            detail = MessageKey.INTERACTION_MAGNESIUM_DAIRY_DETAIL,
            id = "magnesium-dairy",
        ),
        LifestyleRule(
            matchSubstance = listOf("ferro", "ferri", "ferrosi", "zelezo"),
            kind = AlertKind.SUPPLEMENT,
            severity = InteractionSeverity.MINOR,
            title = MessageKey.INTERACTION_IRON_COFFEE_TITLE,
            detail = MessageKey.INTERACTION_IRON_COFFEE_DETAIL,
            id = "iron-coffee",
        ),
        LifestyleRule(
            matchAtc = listOf("G03CA", "G03FA"),
            kind = AlertKind.SUPPLEMENT,
            severity = InteractionSeverity.MINOR,
            title = MessageKey.INTERACTION_OESTROGEN_STJOHNS_TITLE,
            detail = MessageKey.INTERACTION_OESTROGEN_STJOHNS_DETAIL,
            id = "oestrogen-stjohns",
        ),
        LifestyleRule(
            matchAtc = listOf("G03CA", "G03FA"),
            kind = AlertKind.LIFESTYLE,
            severity = InteractionSeverity.MINOR,
            title = MessageKey.INTERACTION_OESTROGEN_SMOKING_TITLE,
            detail = MessageKey.INTERACTION_OESTROGEN_SMOKING_DETAIL,
            id = "oestrogen-smoking",
        ),
    )

    fun evaluate(substances: List<ActiveSubstance>): List<InteractionAlert> {
        val alerts = ArrayList<InteractionAlert>()
        val normalized = substances
            .filter { it.name.isNotBlank() || !it.atcCode.isNullOrBlank() }
            .map { it.copy(atcCode = it.atcCode?.uppercase(Locale.ROOT)?.trim()?.takeIf(String::isNotEmpty)) }

        for (i in normalized.indices) {
            for (j in i + 1 until normalized.size) {
                val a = normalized[i]
                val b = normalized[j]
                for (rule in drugRules) {
                    val forward = matchesAny(rule.a, a) && matchesAny(rule.b, b)
                    val backward = matchesAny(rule.a, b) && matchesAny(rule.b, a)
                    if (!forward && !backward) continue
                    alerts += InteractionAlert(
                        id = "drug:${rule.title}",
                        kind = AlertKind.DRUG,
                        severity = rule.severity,
                        title = message(rule.title),
                        detail = message(rule.detail),
                        involvesAtc = listOfNotNull(a.atcCode, b.atcCode),
                        involvesMedications = listOfNotNull(a.medicationId, b.medicationId)
                            .filter { it != 0L },
                    )
                }
            }
        }

        for (substance in normalized) {
            for (rule in lifestyleRules) {
                if (rule.matches(substance)) {
                    alerts += InteractionAlert(
                        id = "life:${rule.id}:${substance.medicationId}",
                        kind = rule.kind,
                        severity = rule.severity,
                        title = message(rule.title),
                        detail = message(rule.detail),
                        involvesAtc = listOfNotNull(substance.atcCode),
                        involvesMedications = listOfNotNull(substance.medicationId).filter { it != 0L },
                    )
                }
            }
        }

        // Absorption collisions are the same clinical problem as drug interactions, so
        // they are reported here instead of living in a method nothing ever called.
        for (conflict in absorptionConflicts(normalized)) {
            alerts += InteractionAlert(
                id = "absorption:${conflict.pair}",
                kind = AlertKind.DRUG,
                severity = InteractionSeverity.MODERATE,
                title = message(
                    MessageKey.INTERACTION_SEPARATE_INTAKE,
                    conflict.pair.replace('-', ' '),
                ),
                detail = message(conflict.detail),
                involvesMedications = conflict.medicationIds,
            )
        }

        return alerts
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<InteractionAlert> { it.severity.ordinal }
                    .thenBy { it.title.key.name },
            )
    }

    private fun matchesAny(prefixes: List<String>, substance: ActiveSubstance): Boolean =
        prefixes.any { prefix -> substance.atcCode?.startsWith(prefix) == true }

    private fun LifestyleRule.matches(substance: ActiveSubstance): Boolean {
        val atcMatch = matchAtc.any { prefix -> substance.atcCode?.startsWith(prefix) == true }
        val nameMatch = matchSubstance.any { keyword -> substance.normalizedName.contains(keyword) }
        return atcMatch || nameMatch
    }

    fun highestSeverity(alerts: List<InteractionAlert>): InteractionSeverity? =
        alerts.maxByOrNull { it.severity.ordinal }?.severity

    /**
     * Absorption collisions that the scheduler can resolve by offsetting alarms.
     * Matching uses the normalised substance name so Latin SÚKL names ("Ferri sulfas")
     * and Czech names ("železo") both work.
     */
    fun absorptionConflicts(substances: List<ActiveSubstance>): List<AbsorptionConflict> {
        val out = ArrayList<AbsorptionConflict>()
        val named = substances.filter { it.normalizedName.isNotBlank() }

        fun find(vararg keywords: String): List<ActiveSubstance> =
            named.filter { substance -> keywords.any { substance.normalizedName.contains(it) } }

        val iron = find("ferro", "ferri", "ferrosi", "zelezo", "iron")
        val calcium = find("kalcium", "calcii", "calcium", "vapnik")
        val magnesium = find("magnesium", "magnesii", "magnesio", "horcik")
        val thyroxine = find("levothyrox", "thyrox")

        if (iron.isNotEmpty() && (calcium.isNotEmpty() || magnesium.isNotEmpty())) {
            out += AbsorptionConflict(
                pair = "iron/calcium-magnesium",
                requiredGapMinutes = 120,
                detail = MessageKey.INTERACTION_ABSORPTION_IRON_CALCIUM,
                medicationIds = (iron + calcium + magnesium).map { it.medicationId }.filter { it != 0L }.distinct(),
            )
        }
        if (thyroxine.isNotEmpty() && (iron.isNotEmpty() || calcium.isNotEmpty())) {
            out += AbsorptionConflict(
                pair = "levothyroxine/iron-calcium",
                requiredGapMinutes = 240,
                detail = MessageKey.INTERACTION_ABSORPTION_LEVOTHYROXINE,
                medicationIds = (thyroxine + iron + calcium).map { it.medicationId }.filter { it != 0L }.distinct(),
            )
        }
        return out
    }

    data class AbsorptionConflict(
        val pair: String,
        val requiredGapMinutes: Int,
        val detail: MessageKey,
        val medicationIds: List<Long> = emptyList(),
    )

    /**
     * Diacritics-free lowercase form so "železo" == "zelezo" and "Magnesii oxidum"
     * contains the "magnesii" stem.
     */
    internal fun normalizeName(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trim()
}
