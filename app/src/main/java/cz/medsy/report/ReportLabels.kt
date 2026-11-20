package cz.medsy.report

import android.content.res.Resources
import cz.medsy.R
import cz.medsy.domain.model.ChangeType
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The words a report prints. English is the default so the content model stays testable
 * without a Context; the generator hands in [Android] so the document follows the app
 * language.
 *
 * The Czech labels are written without diacritics on purpose: [DoctorReportGenerator]
 * notes that PdfDocument has no font fallback, so "Posledních" would print as missing
 * glyphs while "Poslednich" always prints.
 */
interface ReportLabels {

    val dateTime: DateTimeFormatter
    val date: DateTimeFormatter

    fun noActiveSchedule(): String
    fun daily(): String
    fun everyDays(days: String): String
    fun everyHours(hours: String): String
    fun cyclical(): String
    fun tapering(): String
    fun prn(): String
    fun milestone(type: ChangeType): String

    object English : ReportLabels {
        override val dateTime = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH)
        override val date = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

        override fun noActiveSchedule() = "no active schedule"
        override fun daily() = "daily"
        override fun everyDays(days: String) = "every $days d"
        override fun everyHours(hours: String) = "every $hours h"
        override fun cyclical() = "cyclical"
        override fun tapering() = "tapering"
        override fun prn() = "PRN"
        override fun milestone(type: ChangeType) = type.name.lowercase(Locale.ENGLISH)
    }

    class Android(private val resources: Resources) : ReportLabels {
        private val locale: Locale = resources.configuration.locales[0]

        // One numeric pattern reads correctly in both languages.
        override val dateTime = DateTimeFormatter.ofPattern("d. M. yyyy HH:mm", locale)
        override val date = DateTimeFormatter.ofPattern("d. M. yyyy", locale)

        override fun noActiveSchedule() = resources.getString(R.string.report_no_schedule)
        override fun daily() = resources.getString(R.string.report_daily)
        override fun everyDays(days: String) = resources.getString(R.string.report_every_days, days)
        override fun everyHours(hours: String) = resources.getString(R.string.report_every_hours, hours)
        override fun cyclical() = resources.getString(R.string.report_cyclical)
        override fun tapering() = resources.getString(R.string.report_tapering)
        override fun prn() = resources.getString(R.string.report_prn)
        override fun milestone(type: ChangeType) = resources.getString(
            when (type) {
                ChangeType.STARTED -> R.string.report_change_started
                ChangeType.DOSE_CHANGED -> R.string.report_change_dose
                ChangeType.ROUTE_CHANGED -> R.string.report_change_route
                ChangeType.STOPPED -> R.string.report_change_stopped
            },
        )
    }
}
