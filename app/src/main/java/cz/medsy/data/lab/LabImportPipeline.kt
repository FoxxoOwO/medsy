package cz.medsy.data.lab

import android.net.Uri
import cz.medsy.data.repository.BloodTestRepository
import cz.medsy.domain.calc.Biomarkers
import cz.medsy.domain.calc.UnitConverter
import cz.medsy.domain.lab.LabRangeConverter
import cz.medsy.domain.lab.LabReportParser
import cz.medsy.domain.model.LabImportSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LabImportPipeline @Inject constructor(
    private val extractor: LabPdfExtractor,
    private val repository: BloodTestRepository,
) {

    data class StagingRow(
        val code: String,
        val displayName: String,
        val rawValue: BigDecimal,
        val rawUnit: String,
        val siValue: BigDecimal?,
        val siUnit: String,
        val labMin: BigDecimal?,
        val labMax: BigDecimal?,
        val isConversionError: Boolean,
    ) {
        val isValid: Boolean get() = !isConversionError && siValue != null
    }

    data class StagingResult(
        val rows: List<StagingRow>,
        val rawText: String,
        val requiresOcr: Boolean,
        val source: LabImportSource,
    )

    suspend fun importPdf(uri: Uri): Result<StagingResult> = withContext(Dispatchers.IO) {
        extractor.extractText(uri).map { text ->
            val requiresOcr = extractor.isLikelyScanned(text)
            StagingResult(
                rows = if (requiresOcr) emptyList() else stage(text),
                rawText = text,
                requiresOcr = requiresOcr,
                source = LabImportSource.PDF_TEXT,
            )
        }
    }

    fun importOcrText(text: String): StagingResult = StagingResult(
        rows = stage(text),
        rawText = text,
        requiresOcr = false,
        source = LabImportSource.OCR_IMAGE,
    )

    fun stage(text: String): List<StagingRow> =
        LabReportParser.parse(text).map { parsed ->
            val spec = Biomarkers.find(parsed.biomarkerCode)
            val siValue = runCatching {
                spec?.let { UnitConverter.convert(it, parsed.rawValue, parsed.rawUnit) }
            }.getOrNull()
            StagingRow(
                code = parsed.biomarkerCode,
                displayName = parsed.displayName,
                rawValue = parsed.rawValue,
                rawUnit = parsed.rawUnit,
                siValue = siValue,
                siUnit = spec?.siUnit.orEmpty(),
                labMin = parsed.labMin,
                labMax = parsed.labMax,
                isConversionError = siValue == null,
            )
        }

    suspend fun save(
        rows: List<StagingRow>,
        timestamp: java.time.Instant,
        labName: String?,
        fasting: Boolean,
        isTrough: Boolean,
        hoursSinceLastDose: Double?,
        attachmentUri: String?,
        source: LabImportSource,
    ): Long {
        val valid = rows.filter { it.isValid }
        val values = valid.map { row ->
            // The printed bounds are in the lab's unit (pg/mL); the stored value is SI.
            val (labMinSi, labMaxSi) = LabRangeConverter.toSi(
                code = row.code,
                min = row.labMin,
                max = row.labMax,
                unit = row.rawUnit,
            )
            cz.medsy.domain.model.BiomarkerValue(
                sessionId = 0,
                biomarkerCode = row.code,
                rawValue = row.rawValue,
                rawUnit = row.rawUnit,
                normalizedSiValue = row.siValue ?: BigDecimal.ZERO,
                normalizedSiUnit = row.siUnit,
                labMin = labMinSi,
                labMax = labMaxSi,
            )
        }
        val session = cz.medsy.domain.model.BloodTestSession(
            timestamp = timestamp,
            labName = labName,
            fastingStatus = fasting,
            calculatedHoursSinceLastHrtDose = hoursSinceLastDose,
            isTroughLevel = isTrough,
            attachmentUri = attachmentUri,
            source = source,
        )
        return repository.saveSession(session, values)
    }
}
