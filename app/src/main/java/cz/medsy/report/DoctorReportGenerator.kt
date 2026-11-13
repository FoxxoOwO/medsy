package cz.medsy.report

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import cz.medsy.data.repository.BloodTestRepository
import cz.medsy.data.repository.DoseRepository
import cz.medsy.data.repository.MedicationRepository
import cz.medsy.domain.calc.Biomarkers
import cz.medsy.domain.calc.RangeEvaluator
import cz.medsy.domain.calc.ReferenceRanges
import cz.medsy.domain.model.BiomarkerValue
import cz.medsy.domain.model.BloodTestSession
import cz.medsy.domain.model.Medication
import cz.medsy.domain.model.ProfileType
import cz.medsy.domain.model.Schedule
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-page A4 report built with the platform [PdfDocument] (no extra dependency).
 * Text is ASCII-safe on purpose: PdfDocument has no font fallback, so diacritics would
 * render as missing glyphs in some viewers.
 */
@Singleton
class DoctorReportGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val medicationRepository: MedicationRepository,
    private val doseRepository: DoseRepository,
    private val bloodTestRepository: BloodTestRepository,
    private val milestoneRepository: cz.medsy.data.repository.MilestoneRepository,
) {

    private val pageWidth = 595
    private val pageHeight = 842
    private val margin = 36f

    private val titlePaint = Paint().apply {
        isAntiAlias = true
        textSize = 18f
        color = Color.BLACK
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
    }
    private val sectionPaint = Paint().apply {
        isAntiAlias = true
        textSize = 12f
        color = Color.BLACK
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
    }
    private val bodyPaint = Paint().apply {
        isAntiAlias = true
        textSize = 9.5f
        color = Color.DKGRAY
    }
    private val linePaint = Paint().apply {
        isAntiAlias = true
        strokeWidth = 0.6f
        color = Color.LTGRAY
        style = Paint.Style.STROKE
    }

    data class ReportResult(val file: File, val pageCount: Int)

    suspend fun generate(profile: ProfileType = ProfileType.HRT_FEM): ReportResult =
        withContext(Dispatchers.IO) {
            val medications = medicationRepository.activeSchedules()
            val activeMedications = medicationRepository.observeActive().first()
            val sessions = bloodTestRepository.recentSessions(ReportContent.MAX_SESSIONS + 1)
            val sessionValues = bloodTestRepository.valuesForSessions(sessions.map { it.id })
            val milestones = runCatching { milestoneRepository.all() }.getOrDefault(emptyList())
            val now = Instant.now()
            val from30 = now.minus(Duration.ofDays(30))
            val from90 = now.minus(Duration.ofDays(90))
            val adherence = adherenceFor(from30, now)
            val adherence90 = adherenceFor(from90, now)

            val document = PdfDocument()
            val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create())
            val canvas = page.canvas
            var cursor = margin

            val content = ReportContent.build(
                medications = activeMedications,
                schedules = medications,
                adherence30 = adherence,
                adherence90 = adherence90,
                milestones = milestones,
                sessions = sessions,
                values = sessionValues,
                flagFor = { value -> flagFor(value, profile) },
            )

            cursor = drawHeader(canvas, cursor)
            cursor = drawContentMedications(canvas, cursor, content)
            cursor = drawContentAdherence(canvas, cursor, content)
            cursor = drawContentMilestones(canvas, cursor, content)
            cursor = drawContentLabs(canvas, cursor, content)
            drawTrendChart(canvas, sessions, sessionValues)
            drawFooter(canvas, cursor)

            document.finishPage(page)
            val file = File(context.cacheDir, "medsy-doctor-report-${now.toEpochMilli()}.pdf")
            FileOutputStream(file).use(document::writeTo)
            val pages = document.pages.size
            document.close()
            ReportResult(file, pages)
        }

    private fun drawHeader(canvas: Canvas, startY: Float): Float {
        var y = startY
        canvas.drawText("MEDICATION & HRT SUMMARY", margin, y, titlePaint)
        y += 16f
        val generated = Instant.now()
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("d MMMM yyyy HH:mm", Locale.ENGLISH))
        canvas.drawText("Generated $generated - offline app, patient-entered data", margin, y, bodyPaint)
        y += 6f
        canvas.drawLine(margin, y, pageWidth - margin, y, linePaint)
        return y + 16f
    }

    private suspend fun adherenceFor(
        from: Instant,
        to: Instant,
    ): cz.medsy.domain.model.AdherenceSummary {
        val expected = doseRepository.expectedDoses(from, to, ZoneId.systemDefault())
        return doseRepository.adherence(from, to, expected)
    }

    private fun flagFor(value: BiomarkerValue, profile: ProfileType): String {
        val range = ReferenceRanges.forBiomarker(value.biomarkerCode, profile)
        return when (RangeEvaluator.evaluate(value.normalizedSiValue, range).status) {
            cz.medsy.domain.calc.RangeStatus.IN_RANGE -> "within target"
            cz.medsy.domain.calc.RangeStatus.LOW -> "below target"
            cz.medsy.domain.calc.RangeStatus.HIGH -> "above target"
            cz.medsy.domain.calc.RangeStatus.CRITICAL_LOW -> "LOW"
            cz.medsy.domain.calc.RangeStatus.CRITICAL_HIGH -> "HIGH"
            cz.medsy.domain.calc.RangeStatus.NO_RANGE -> "no range"
        }
    }

    private fun drawContentMedications(canvas: Canvas, startY: Float, content: ReportContent.Content): Float {
        var y = startY
        canvas.drawText("CURRENT MEDICATIONS", margin, y, sectionPaint)
        y += 14f
        if (content.medications.isEmpty()) {
            canvas.drawText("None recorded.", margin, y, bodyPaint)
            return y + 18f
        }
        content.medications.forEach { line ->
            canvas.drawText("- ${line.name}", margin, y, bodyPaint)
            y += 11f
            canvas.drawText("  ${line.schedule}", margin + 6f, y, bodyPaint)
            y += 12f
        }
        if (content.truncated) {
            canvas.drawText("(list truncated to fit one page)", margin, y, bodyPaint)
            y += 12f
        }
        return y + 8f
    }

    private fun drawContentAdherence(canvas: Canvas, startY: Float, content: ReportContent.Content): Float {
        var y = startY
        canvas.drawText("ADHERENCE", margin, y, sectionPaint)
        y += 14f
        content.adherence.forEach { line ->
            canvas.drawText(
                "Last ${line.days} days: ${line.taken} of ${line.total} doses (${line.percent}%)",
                margin,
                y,
                bodyPaint,
            )
            y += 9f
            val bar = RectF(margin, y, pageWidth - margin, y + 6f)
            canvas.drawRect(bar, linePaint)
            if (line.total > 0) {
                val width = bar.width() * (line.taken.toFloat() / line.total).coerceIn(0f, 1f)
                canvas.drawRect(
                    RectF(bar.left, bar.top, bar.left + width, bar.bottom),
                    Paint().apply { color = Color.rgb(63, 143, 99); style = Paint.Style.FILL },
                )
            }
            y += 16f
        }
        return y + 6f
    }

    private fun drawContentMilestones(canvas: Canvas, startY: Float, content: ReportContent.Content): Float {
        if (content.milestones.isEmpty()) return startY
        var y = startY
        canvas.drawText("MEDICATION CHANGES", margin, y, sectionPaint)
        y += 14f
        content.milestones.forEach { line ->
            canvas.drawText("- ${line.dateLabel} ${line.text}", margin, y, bodyPaint)
            y += 10.5f
        }
        return y + 8f
    }

    private fun drawContentLabs(canvas: Canvas, startY: Float, content: ReportContent.Content): Float {
        var y = startY
        canvas.drawText("RECENT BLOODWORK", margin, y, sectionPaint)
        y += 14f
        if (content.labs.isEmpty()) {
            canvas.drawText("No results recorded.", margin, y, bodyPaint)
            return y + 18f
        }
        content.labs.forEach { block ->
            val trough = if (block.isTrough) " - trough" else ""
            canvas.drawText("${block.dateLabel}$trough", margin, y, bodyPaint)
            y += 11f
            block.rows.forEach { row ->
                val delta = row.delta?.let { " ($it)" }.orEmpty()
                canvas.drawText(
                    "  ${row.name}: ${row.value} ${row.unit}$delta (${row.flag})",
                    margin + 4f,
                    y,
                    bodyPaint,
                )
                y += 10.5f
                if (y > pageHeight - 210f) return y
            }
            y += 5f
        }
        return y
    }

    private fun drawTrendChart(
        canvas: Canvas,
        sessions: List<BloodTestSession>,
        values: List<BiomarkerValue>,
    ) {
        val byCode = values.groupBy { it.biomarkerCode }
        val series = byCode.entries
            .mapNotNull { (code, rows) -> code to rows }
            .filter { it.second.size >= 2 }
            .maxByOrNull { it.second.size } ?: return

        val (code, rows) = series
        val spec = Biomarkers.find(code) ?: return
        val ordered = rows.sortedBy { row ->
            sessions.firstOrNull { it.id == row.sessionId }?.timestamp ?: Instant.EPOCH
        }
        val chartTop = pageHeight - 190f
        val chartBottom = pageHeight - 90f
        val chartLeft = margin + 10f
        val chartRight = pageWidth - margin - 10f
        if (ordered.size < 2 || chartBottom <= chartTop) return

        val numbers = ordered.map { it.normalizedSiValue.toDouble() }
        val min = numbers.min()
        val max = numbers.max()
        val span = (max - min).takeIf { it > 0.0 } ?: 1.0

        canvas.drawText(
            "TREND: ${spec.displayName}",
            margin,
            chartTop - 8f,
            sectionPaint,
        )

        val range = ReferenceRanges.forBiomarker(code, ProfileType.HRT_FEM)
        range?.targetMinSi?.toDouble()?.let { low ->
            range.targetMaxSi?.toDouble()?.let { high ->
                val top = chartBottom - (((high - min) / span) * (chartBottom - chartTop)).toFloat()
                val bottom = chartBottom - (((low - min) / span) * (chartBottom - chartTop)).toFloat()
                val band = RectF(chartLeft, top.coerceIn(chartTop, chartBottom), chartRight, bottom.coerceIn(chartTop, chartBottom))
                if (bottom > top) {
                    canvas.drawRect(
                        band,
                        Paint().apply {
                            color = Color.argb(40, 63, 143, 99)
                            style = Paint.Style.FILL
                        },
                    )
                }
            }
        }

        canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, linePaint)

        val path = android.graphics.Path()
        ordered.forEachIndexed { index, row ->
            val x = chartLeft + (chartRight - chartLeft) * (index.toFloat() / (ordered.size - 1))
            val y = chartBottom -
                (((row.normalizedSiValue.toDouble() - min) / span) * (chartBottom - chartTop)).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(
            path,
            Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = Color.rgb(76, 92, 146)
            },
        )
        canvas.drawText(
            "${spec.siUnit}  min ${formatNumber(min)}  max ${formatNumber(max)}",
            chartLeft,
            chartBottom + 12f,
            bodyPaint,
        )
    }

    private fun formatNumber(value: Double): String =
        if (value >= 100) value.toInt().toString()
        else String.format(Locale.ENGLISH, "%.1f", value)

    private fun drawFooter(canvas: Canvas, y: Float) {
        val lineY = pageHeight - 42f
        canvas.drawLine(margin, lineY, pageWidth - margin, lineY, linePaint)
        canvas.drawText(
            "Patient-reported data. Not a medical record. Verify against your own notes.",
            margin,
            lineY + 14f,
            bodyPaint,
        )
        canvas.drawText(
            "Medsy - offline-first medication tracker",
            margin,
            lineY + 25f,
            bodyPaint,
        )
    }
}
