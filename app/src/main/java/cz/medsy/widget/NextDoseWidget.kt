package cz.medsy.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import cz.medsy.data.repository.DoseRepository
import cz.medsy.data.repository.MedicationRepository
import cz.medsy.data.settings.SettingsRepository
import cz.medsy.domain.schedule.ScheduleEngine
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class NextDoseWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetEntryPoint::class.java,
        )
        val medicationRepository = entryPoint.medicationRepository()
        val settingsRepository = entryPoint.settingsRepository()
        val doseRepository = entryPoint.doseRepository()

        val discreet = runCatching { settingsRepository.settings.first().discreetMode }
            .getOrDefault(false)
        val schedules = runCatching { medicationRepository.activeSchedules() }
            .getOrDefault(emptyList())
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val next = schedules
            .mapNotNull { schedule -> ScheduleEngine.nextOccurrence(schedule, now, zone) }
            .minByOrNull { it.dueAt }
        val lastTaken = runCatching { doseRepository.lastTaken() }.getOrNull()

        val medicationName = when {
            next == null -> null
            discreet -> "Next routine"
            else -> runCatching { medicationRepository.getById(next.medicationId)?.name }.getOrNull()
        }

        provideContent {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(Color(0xFF1C1B1D))
                    .padding(14.dp),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                Text(
                    text = "NEXT DOSE",
                    style = TextStyle(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = ColorProvider(Color(0xFFB6C4FF)),
                    ),
                )
                Spacer(modifier = GlanceModifier.height(4.dp))
                if (next == null) {
                    Text(
                        text = "Nothing scheduled",
                        style = TextStyle(
                            fontSize = 16.sp,
                            color = ColorProvider(Color(0xFFE6E1E4)),
                        ),
                    )
                } else {
                    val due = next.dueAt.atZone(zone)
                    val countdown = formatCountdown(Duration.between(now, next.dueAt))
                    Text(
                        text = medicationName ?: "Scheduled dose",
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = ColorProvider(Color(0xFFE6E1E4)),
                        ),
                    )
                    Text(
                        text = "$countdown · ${due.format(DateTimeFormatter.ofPattern("HH:mm"))}",
                        style = TextStyle(
                            fontSize = 13.sp,
                            color = ColorProvider(Color(0xFFCAC5CD)),
                        ),
                    )
                }
                lastTaken?.actualTimestamp?.let { at ->
                    Spacer(modifier = GlanceModifier.height(2.dp))
                    Text(
                        text = "Last logged ${formatAgo(at)}",
                        style = TextStyle(
                            fontSize = 11.sp,
                            color = ColorProvider(Color(0xFF948F97)),
                        ),
                    )
                }
            }
        }
    }

    private fun formatCountdown(duration: Duration): String {
        if (duration.isNegative) return "due now"
        val hours = duration.toHours()
        val days = hours / 24
        return when {
            days > 0 -> "${days}d ${hours % 24}h"
            hours > 0 -> "${hours}h ${duration.toMinutes() % 60}m"
            else -> "${duration.toMinutes()}m"
        }
    }

    private fun formatAgo(at: Instant): String {
        val hours = Duration.between(at, Instant.now()).toHours()
        return if (hours >= 24) "${hours / 24}d ago" else "${hours}h ago"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun medicationRepository(): MedicationRepository
    fun settingsRepository(): SettingsRepository
    fun doseRepository(): DoseRepository
}

class NextDoseWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextDoseWidget()
}
