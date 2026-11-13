package cz.medsy.data.local

import androidx.room.TypeConverter
import cz.medsy.domain.model.ApplicationSite
import cz.medsy.domain.model.ChangeType
import cz.medsy.domain.model.DoseStatus
import cz.medsy.domain.model.LabImportSource
import cz.medsy.domain.model.MealRelation
import cz.medsy.domain.model.MedicationForm
import cz.medsy.domain.model.ProfileType
import cz.medsy.domain.model.ScheduleType
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class Converters {

    @TypeConverter fun instantToLong(value: Instant?): Long? = value?.toEpochMilli()
    @TypeConverter fun longToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter fun localDateToLong(value: LocalDate?): Long? = value?.toEpochDay()
    @TypeConverter fun longToLocalDate(value: Long?): LocalDate? = value?.let(LocalDate::ofEpochDay)

    @TypeConverter fun localTimeToString(value: LocalTime?): String? = value?.toString()
    @TypeConverter fun stringToLocalTime(value: String?): LocalTime? = value?.let(LocalTime::parse)

    @TypeConverter fun medicationFormToString(value: MedicationForm?): String? = value?.name
    @TypeConverter fun stringToMedicationForm(value: String?): MedicationForm? =
        value?.let { runCatching { MedicationForm.valueOf(it) }.getOrNull() }

    @TypeConverter fun scheduleTypeToString(value: ScheduleType?): String? = value?.name
    @TypeConverter fun stringToScheduleType(value: String?): ScheduleType? =
        value?.let { runCatching { ScheduleType.valueOf(it) }.getOrNull() }

    @TypeConverter fun mealRelationToString(value: MealRelation?): String? = value?.name
    @TypeConverter fun stringToMealRelation(value: String?): MealRelation? =
        value?.let { runCatching { MealRelation.valueOf(it) }.getOrNull() }

    @TypeConverter fun doseStatusToString(value: DoseStatus?): String? = value?.name
    @TypeConverter fun stringToDoseStatus(value: String?): DoseStatus? =
        value?.let { runCatching { DoseStatus.valueOf(it) }.getOrNull() }

    @TypeConverter fun siteToString(value: ApplicationSite?): String? = value?.name
    @TypeConverter fun stringToSite(value: String?): ApplicationSite? =
        value?.let { runCatching { ApplicationSite.valueOf(it) }.getOrNull() }

    @TypeConverter fun profileTypeToString(value: ProfileType?): String? = value?.name
    @TypeConverter fun stringToProfileType(value: String?): ProfileType? =
        value?.let { runCatching { ProfileType.valueOf(it) }.getOrNull() }

    @TypeConverter fun changeTypeToString(value: ChangeType?): String? = value?.name
    @TypeConverter fun stringToChangeType(value: String?): ChangeType? =
        value?.let { runCatching { ChangeType.valueOf(it) }.getOrNull() }

    @TypeConverter fun importSourceToString(value: LabImportSource?): String? = value?.name
    @TypeConverter fun stringToImportSource(value: String?): LabImportSource? =
        value?.let { runCatching { LabImportSource.valueOf(it) }.getOrNull() }

    @TypeConverter fun timeListToJson(value: List<LocalTime>?): String =
        Json.encodeToString(value?.map { it.toString() } ?: emptyList())

    @TypeConverter fun jsonToTimeList(value: String?): List<LocalTime> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching {
            Json.decodeFromString<List<String>>(value).map(LocalTime::parse)
        }.getOrDefault(emptyList())
    }
}
