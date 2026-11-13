package cz.medsy.ui.scanner

import cz.medsy.domain.message.Message
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.message.message
import cz.medsy.domain.message.messagePlural

import androidx.lifecycle.ViewModel
import com.google.mlkit.vision.barcode.common.Barcode
import androidx.lifecycle.viewModelScope
import cz.medsy.data.remote.SuklConfig
import cz.medsy.data.repository.InventoryRepository
import cz.medsy.data.repository.PrescriptionRepository
import cz.medsy.data.repository.MedicationRepository
import cz.medsy.domain.repository.MedicationLookupRepository
import cz.medsy.domain.model.DataSourceOrigin
import cz.medsy.domain.model.ResolvedMedicationCandidate
import cz.medsy.domain.util.MedicationFormMapper
import cz.medsy.domain.scanner.PackageToInventory
import cz.medsy.data.repository.SuklRepository
import cz.medsy.domain.model.Medication
import cz.medsy.domain.model.MedicationForm
import cz.medsy.domain.model.ScannedPackage
import cz.medsy.domain.model.SuklMedication
import cz.medsy.domain.model.Symbology
import cz.medsy.domain.scanner.Ean13
import cz.medsy.domain.scanner.EReceptParser
import cz.medsy.domain.scanner.Gs1Parser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import javax.inject.Inject

/** One offline ATC pick, kept out of the data layer so the screen never sees asset types. */
data class AtcSuggestion(val code: String, val name: String)

data class ScannerUiState(
    val scanned: ScannedPackage? = null,
    val matchedMedication: SuklMedication? = null,
    val prescriptionValidUntil: Instant? = null,
    val manualName: String = "",
    val manualSubstance: String = "",
    val manualAtc: String = "",
    val manualStrength: String = "",
    val manualForm: MedicationForm = MedicationForm.TABLET,
    val manualConcentration: String = "",
    /** Units inside one scanned box; used to seed the inventory batch. */
    val manualQuantity: String = "",
    /**
     * Waterfall matches for the typed name. The user taps one explicitly: auto-applying the
     * first hit overwrote the name being typed and made entries like "Estrofem" impossible.
     */
    val matches: List<ResolvedMedicationCandidate> = emptyList(),
    /** Offline ATC picks, offered only when nothing in the local catalogue matched. */
    val atcSuggestions: List<AtcSuggestion> = emptyList(),
    /** The source that answered the last lookup; null when nothing is known yet. */
    val resolved: ResolvedMedicationCandidate? = null,
    val statusMessage: Message? = null,
    val isSaving: Boolean = false,
    val savedMedicationId: Long? = null,
)

/**
 * GS1/EAN decoding is done by ML Kit; this ViewModel hands the raw payload to the waterfall
 * ([MedicationLookupRepository]), which decides whether the Czech register, the curated HRT
 * catalogue, Open Food Facts or manual entry knows the package.
 */
@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val suklRepository: SuklRepository,
    private val medicationRepository: MedicationRepository,
    private val inventoryRepository: InventoryRepository,
    private val prescriptionRepository: PrescriptionRepository,
    private val lookupRepository: MedicationLookupRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ScannerUiState())
    val state: StateFlow<ScannerUiState> = _state.asStateFlow()

    /** Cancelled on every keystroke so a slow lookup cannot overwrite a newer query. */
    private var searchJob: Job? = null

    fun onBarcode(rawValue: String, format: Int) {
        if (rawValue.isBlank()) return
        when (format) {
            Barcode.FORMAT_DATA_MATRIX -> handleDataMatrix(rawValue)
            Barcode.FORMAT_EAN_13 -> handleEan13(rawValue)
            Barcode.FORMAT_QR_CODE -> handleQr(rawValue)
            else -> {
                handleEan13(rawValue)
                handleDataMatrix(rawValue)
            }
        }
    }

    private fun handleDataMatrix(raw: String) {
        val parsed = Gs1Parser.parse(raw)
        if (parsed == null) {
            // A silent return looked like a broken camera. Say what failed.
            _state.update {
                it.copy(statusMessage = message(MessageKey.SCAN_NOT_GS1))
            }
            return
        }
        _state.update {
            it.copy(scanned = parsed, statusMessage = message(MessageKey.SCAN_DATAMATRIX_DECODED))
        }
        resolveBarcode(raw, parsed)
    }

    private fun handleEan13(raw: String) {
        val normalized = Ean13.normalize(raw)
        if (normalized == null) {
            _state.update {
                it.copy(statusMessage = message(MessageKey.SCAN_EAN_INVALID))
            }
            return
        }
        val scanned = ScannedPackage(
            gtin = Ean13.toGtin14(normalized),
            expirationDate = null,
            batchNumber = null,
            serialNumber = null,
            raw = raw,
            symbology = Symbology.EAN_13,
        )
        _state.update { it.copy(scanned = scanned, statusMessage = message(MessageKey.SCAN_EAN_DECODED)) }
        resolveBarcode(normalized, scanned)
    }

    private fun handleQr(raw: String) {
        val prescription = EReceptParser.parse(raw)
        if (prescription != null) {
            viewModelScope.launch {
                val saved = prescriptionRepository.save(prescription)
                _state.update {
                    it.copy(
                        prescriptionValidUntil = prescription.validUntil,
                        statusMessage = when (saved) {
                            PrescriptionRepository.SaveResult.SAVED -> messagePlural(
                                MessageKey.SCAN_RX_SAVED,
                                prescription.medications.size,
                                prescription.code,
                                prescription.medications.size,
                            )

                            PrescriptionRepository.SaveResult.ALREADY_SAVED ->
                                message(MessageKey.SCAN_RX_DUPLICATE, prescription.code)
                        },
                        manualName = prescription.medications.firstOrNull()?.name ?: it.manualName,
                    )
                }
            }
            prescription.medications.firstOrNull()?.suklCode?.let { code ->
                viewModelScope.launch {
                    suklRepository.findByCode(code)?.let { match ->
                        _state.update { it.copy(matchedMedication = match) }
                    }
                }
            }
            return
        }
        handleDataMatrix(raw)
    }

    /**
     * One entry point for every scan. The waterfall decides which source knows the package, and
     * a code nothing knows still comes back pre-filled with the batch and expiry the symbol
     * itself proved — that is the only information the box carries, so it must not be dropped.
     */
    private fun resolveBarcode(rawValue: String, scanned: ScannedPackage?) {
        viewModelScope.launch {
            val existing = scanned?.gtin?.let { gtin -> medicationRepository.findByGtin(gtin) }
            if (existing != null) {
                _state.update {
                    it.copy(
                        statusMessage = message(MessageKey.SCAN_ALREADY_LISTED, existing.name),
                        manualName = existing.name,
                        manualForm = existing.form,
                    )
                }
                return@launch
            }
            lookupRepository.resolveByBarcode(rawValue).fold(
                onSuccess = { candidate -> applyCandidate(candidate, announce = true) },
                onFailure = { applyUnknownFallback(rawValue, scanned) },
            )
        }
    }

    /**
     * Fills the form from whichever stage answered. The origin decides the message, because a
     * supplement found in Open Food Facts is not the same promise as a registered medicine.
     */
    private fun applyCandidate(candidate: ResolvedMedicationCandidate, announce: Boolean) {
        _state.update { state ->
            state.copy(
                resolved = candidate,
                matchedMedication = state.matchedMedication,
                // Name keeps what the user typed; everything else is the candidate's word.
                // Falling back to the previous value leaked e.g. "5 mg/mL" from an estradiol
                // pick into an iron tablet, which the device run caught.
                manualName = candidate.name.ifBlank { state.manualName },
                manualSubstance = candidate.activeSubstance.orEmpty(),
                manualAtc = candidate.atcCode.orEmpty(),
                manualStrength = formatStrength(candidate),
                manualForm = candidate.form,
                manualConcentration = formatConcentration(candidate),
                matches = emptyList(),
                atcSuggestions = emptyList(),
                statusMessage = if (!announce) state.statusMessage else when (candidate.origin) {
                    DataSourceOrigin.SUKL_CZ -> message(MessageKey.SCAN_MATCHED_SUKL, candidate.suklCode.orEmpty())
                    DataSourceOrigin.HRT_CURATED_CATALOG -> message(MessageKey.SCAN_MATCHED_CATALOG, candidate.name)
                    DataSourceOrigin.OPEN_FOOD_FACTS -> message(MessageKey.SCAN_MATCHED_SUPPLEMENT, candidate.name)
                    DataSourceOrigin.MANUAL_ATC_FALLBACK -> message(MessageKey.SCAN_PARTIAL_UNKNOWN)
                },
            )
        }
    }

    /** Nothing in the waterfall knows the code: keep what the symbol proved and ask for a name. */
    private suspend fun applyUnknownFallback(rawValue: String, scanned: ScannedPackage?) {
        val fallback = runCatching { lookupRepository.manualCandidate(rawBarcode = rawValue) }.getOrNull()
        // The manual stage never fails, so "usable" has to be judged: a payload with no GTIN and
        // no scanned symbol is not a package, it is a URL or a loyalty card.
        val usable = fallback != null && (scanned != null || fallback.gtin14 != null)
        _state.update { state ->
            if (!usable) {
                // Not a medication barcode at all (e.g. a URL in a QR code): say so instead of
                // leaving the user staring at an unchanged form.
                state.copy(statusMessage = message(MessageKey.SCAN_NOT_GS1))
            } else {
                state.copy(
                    resolved = if (usable) fallback else state.resolved,
                    manualName = fallback?.name?.takeIf { it.isNotBlank() } ?: state.manualName,
                    statusMessage = message(MessageKey.SCAN_PARTIAL_UNKNOWN),
                )
            }
        }
    }

    /**
     * Searches the waterfall for the typed name. ATC picks are only offered when the local
     * catalogue has nothing: otherwise two competing lists would fight for the same tap.
     */
    fun searchMedications(query: String) {
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _state.update { it.copy(matches = emptyList(), atcSuggestions = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            val results = lookupRepository.searchByQuery(trimmed).first()
            val suggestions = if (results.any { it.origin == DataSourceOrigin.SUKL_CZ }) {
                emptyList()
            } else {
                lookupRepository.suggestAtc(trimmed, limit = 5).map { entry ->
                    AtcSuggestion(code = entry.code, name = entry.czech.ifBlank { entry.latin })
                }
            }
            _state.update { it.copy(matches = results, atcSuggestions = suggestions) }
        }
    }

    /** Applies a suggestion the user explicitly tapped. */
    fun pickMatch(candidate: ResolvedMedicationCandidate) {
        applyCandidate(candidate, announce = false)
    }

    /** Links an offline ATC pick, which is all a truly unknown package can still be given. */
    fun pickAtc(suggestion: AtcSuggestion) {
        val current = _state.value
        viewModelScope.launch {
            val candidate = lookupRepository.manualCandidate(
                rawBarcode = current.scanned?.raw,
                atcCode = suggestion.code,
                nameHint = current.manualName.ifBlank { null },
            )
            _state.update {
                it.copy(
                    resolved = candidate,
                    manualAtc = suggestion.code,
                    manualSubstance = candidate.activeSubstance.orEmpty(),
                    manualName = it.manualName.ifBlank { suggestion.name },
                    manualStrength = formatStrength(candidate),
                    manualConcentration = formatConcentration(candidate),
                    atcSuggestions = emptyList(),
                )
            }
        }
    }

    fun updateManualName(value: String) = _state.update { it.copy(manualName = value) }
    fun updateManualSubstance(value: String) = _state.update { it.copy(manualSubstance = value) }
    fun updateManualAtc(value: String) = _state.update { it.copy(manualAtc = value) }
    fun updateManualStrength(value: String) = _state.update { it.copy(manualStrength = value) }
    fun updateManualForm(value: MedicationForm) = _state.update { it.copy(manualForm = value) }
    fun updateManualConcentration(value: String) = _state.update { it.copy(manualConcentration = value) }

    fun updateManualQuantity(value: String) = _state.update {
        it.copy(manualQuantity = value.filter(Char::isDigit))
    }

    fun dismissSuggestions() = _state.update { it.copy(matches = emptyList(), atcSuggestions = emptyList()) }

    fun save() {
        val current = _state.value
        if (current.manualName.isBlank()) {
            _state.update { it.copy(statusMessage = message(MessageKey.SCAN_NAME_REQUIRED)) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            val match = current.matchedMedication
            val resolved = current.resolved
            val suklCode = resolved?.suklCode ?: match?.suklCode
            val medication = Medication(
                suklCode = suklCode,
                gtin = current.scanned?.gtin ?: resolved?.gtin14,
                name = current.manualName.trim(),
                activeSubstance = current.manualSubstance.ifBlank {
                    resolved?.activeSubstance ?: match?.activeSubstance
                },
                atcCode = current.manualAtc.ifBlank { resolved?.atcCode ?: match?.atcCode },
                form = current.manualForm,
                strengthValue = parseStrength(current.manualStrength),
                strengthUnit = parseStrengthUnit(current.manualStrength),
                concentrationMgPerMl = current.manualConcentration.toBigDecimalOrNull(),
                pilUrl = resolved?.pilPdfUrl ?: match?.pilUrl ?: suklCode?.let(SuklConfig::pilUrl),
                spcUrl = resolved?.spcPdfUrl ?: match?.spcUrl ?: suklCode?.let(SuklConfig::spcUrl),
                isHrt = isHrt(current.manualAtc.ifBlank { resolved?.atcCode ?: match?.atcCode.orEmpty() }),
            )
            val id = medicationRepository.save(medication)

            // A scanned EU-FMD box carries (17) expiry and (10) batch. Persisting them is
            // the whole point of scanning: they drive expiry and BUD alerts. Previously
            // the values were shown on screen and discarded.
            current.scanned?.let { scanned ->
                PackageToInventory.batchFrom(
                    scanned = scanned,
                    medicationId = id,
                    quantity = current.manualQuantity.ifBlank { null },
                    strength = current.manualStrength,
                    form = current.manualForm,
                    unit = null,
                )?.let { batch -> inventoryRepository.saveBatch(batch) }
            }

            _state.update {
                it.copy(isSaving = false, savedMedicationId = id, statusMessage = message(MessageKey.SCAN_SAVED))
            }
        }
    }

    fun clear() {
        _state.value = ScannerUiState()
    }

    private fun mapForm(formName: String?): MedicationForm = MedicationFormMapper.fromSuklForm(formName)

    /** Concentrations are stored per mL, so the field never shows a stale value from a pick. */
    private fun formatConcentration(candidate: ResolvedMedicationCandidate): String =
        candidate.concentrationMgPerMl?.stripTrailingZeros()?.toPlainString().orEmpty()

    /** "2" + "mg" → "2 mg"; a candidate without a unit shows the bare number. */
    private fun formatStrength(candidate: ResolvedMedicationCandidate): String {
        val value = candidate.strengthValue ?: return ""
        val unit = candidate.strengthUnit
        return if (unit.isNullOrBlank()) value.stripTrailingZeros().toPlainString()
        else "${value.stripTrailingZeros().toPlainString()} $unit"
    }

    private fun parseStrength(value: String): BigDecimal? =
        Regex("\\d+[.,]?\\d*").find(value)?.value?.replace(',', '.')?.toBigDecimalOrNull()

    private fun parseStrengthUnit(value: String): String? =
        Regex("[a-zA-Zµ]+").find(value)?.value

    /**
     * G03 = sex hormones (estrogens, progestogens, androgens), G02 = other gynaecological
     * hormones. H02 is *corticosteroids*, which are not HRT — including it previously
     * marked every prednisone prescription as HRT and made it skew the trough analysis.
     */
    private fun isHrt(atc: String): Boolean =
        atc.startsWith("G03") || atc.startsWith("G02")


}

