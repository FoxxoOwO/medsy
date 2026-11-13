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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import javax.inject.Inject

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
    /** Suggestions from the local SÚKL copy; picked explicitly so typing is not hijacked. */
    val suklSuggestions: List<SuklMedication> = emptyList(),
    val statusMessage: Message? = null,
    val isSaving: Boolean = false,
    val savedMedicationId: Long? = null,
)

/**
 * GS1/EAN decoding is done by ML Kit; this ViewModel turns the raw payload into a
 * domain [ScannedPackage], enriches it from the local SÚKL copy, and lets the user
 * confirm or type in details manually.
 */
@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val suklRepository: SuklRepository,
    private val medicationRepository: MedicationRepository,
    private val inventoryRepository: InventoryRepository,
    private val prescriptionRepository: PrescriptionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ScannerUiState())
    val state: StateFlow<ScannerUiState> = _state.asStateFlow()

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
        parsed.gtin?.let { gtin -> resolveGtin(gtin) }
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
        resolveGtin(Ean13.toGtin14(normalized))
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

    private fun resolveGtin(gtin: String) {
        viewModelScope.launch {
            val existing = medicationRepository.findByGtin(gtin)
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
            val match = suklRepository.findByGtin(gtin)
            if (match != null) {
                _state.update {
                    it.copy(
                        matchedMedication = match,
                        manualName = match.name,
                        manualSubstance = match.activeSubstance.orEmpty(),
                        manualAtc = match.atcCode.orEmpty(),
                        manualStrength = match.strength.orEmpty(),
                        manualForm = mapForm(match.formName),
                        statusMessage = message(MessageKey.SCAN_MATCHED_SUKL, match.suklCode),
                    )
                }
            } else {
                _state.update {
                    it.copy(statusMessage = message(MessageKey.SCAN_NO_SUKL_MATCH, gtin))
                }
            }
        }
    }

    /**
     * Only fills suggestions. Auto-applying the first hit overwrote the name the user
     * was still typing, which made it impossible to enter e.g. "Estrofem".
     */
    fun searchSukl(query: String) {
        if (query.length < 3) {
            _state.update { it.copy(suklSuggestions = emptyList()) }
            return
        }
        viewModelScope.launch {
            val results = suklRepository.search(query, limit = 5)
            _state.update { it.copy(suklSuggestions = results) }
        }
    }

    /** Applies a suggestion the user explicitly tapped. */
    fun pickSukl(match: SuklMedication) {
        _state.update {
            it.copy(
                matchedMedication = match,
                manualName = match.name,
                manualSubstance = match.activeSubstance.orEmpty(),
                manualAtc = match.atcCode.orEmpty(),
                manualStrength = match.strength.orEmpty(),
                manualForm = mapForm(match.formName),
                suklSuggestions = emptyList(),
            )
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

    fun dismissSuggestions() = _state.update { it.copy(suklSuggestions = emptyList()) }

    fun save() {
        val current = _state.value
        if (current.manualName.isBlank()) {
            _state.update { it.copy(statusMessage = message(MessageKey.SCAN_NAME_REQUIRED)) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            val match = current.matchedMedication
            val medication = Medication(
                suklCode = match?.suklCode,
                gtin = current.scanned?.gtin,
                name = current.manualName.trim(),
                activeSubstance = current.manualSubstance.ifBlank { match?.activeSubstance },
                atcCode = current.manualAtc.ifBlank { match?.atcCode },
                form = current.manualForm,
                strengthValue = parseStrength(current.manualStrength),
                strengthUnit = parseStrengthUnit(current.manualStrength),
                concentrationMgPerMl = current.manualConcentration.toBigDecimalOrNull(),
                pilUrl = match?.pilUrl ?: match?.suklCode?.let(SuklConfig::pilUrl),
                spcUrl = match?.spcUrl ?: match?.suklCode?.let(SuklConfig::spcUrl),
                isHrt = isHrt(current.manualAtc.ifBlank { match?.atcCode.orEmpty() }),
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

    private fun mapForm(formName: String?): MedicationForm {
        val value = formName?.lowercase().orEmpty()
        return when {
            value.contains("tbl") || value.contains("tablet") -> MedicationForm.TABLET
            value.contains("cps") || value.contains("capsul") -> MedicationForm.CAPSULE
            value.contains("inj") || value.contains("injek") -> MedicationForm.INJECTION_IM
            value.contains("náplast") || value.contains("naplast") || value.contains("patch") -> MedicationForm.PATCH
            value.contains("gel") -> MedicationForm.GEL
            value.contains("sprej") || value.contains("spray") -> MedicationForm.SPRAY
            value.contains("kapky") || value.contains("drop") -> MedicationForm.DROPS
            else -> MedicationForm.TABLET
        }
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

