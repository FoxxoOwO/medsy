package cz.medsy.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import cz.medsy.data.local.dao.BloodTestDao
import cz.medsy.data.local.dao.ConsumableDao
import cz.medsy.data.local.dao.DoseLogDao
import cz.medsy.data.local.dao.InventoryDao
import cz.medsy.data.local.dao.MedicationDao
import cz.medsy.data.local.dao.MilestoneDao
import cz.medsy.data.local.dao.ScheduleDao
import cz.medsy.data.local.dao.PrescriptionDao
import cz.medsy.data.local.dao.SuklDao
import cz.medsy.data.local.dao.SymptomDao
import cz.medsy.data.local.entity.BiomarkerValueEntity
import cz.medsy.data.local.entity.BloodTestSessionEntity
import cz.medsy.data.local.entity.ConsumableEntity
import cz.medsy.data.local.entity.DoseLogEntity
import cz.medsy.data.local.entity.InventoryBatchEntity
import cz.medsy.data.local.entity.MedicationEntity
import cz.medsy.data.local.entity.MedicationMilestoneEntity
import cz.medsy.data.local.entity.PrescriptionEntity
import cz.medsy.data.local.entity.PrescriptionItemEntity
import cz.medsy.data.local.entity.ReferenceRangeProfileEntity
import cz.medsy.data.local.entity.ScheduleEntity
import cz.medsy.data.local.entity.SuklMedicationEntity
import cz.medsy.data.local.entity.SymptomLogEntity

@Database(
    entities = [
        MedicationEntity::class,
        ScheduleEntity::class,
        DoseLogEntity::class,
        InventoryBatchEntity::class,
        ConsumableEntity::class,
        BloodTestSessionEntity::class,
        BiomarkerValueEntity::class,
        ReferenceRangeProfileEntity::class,
        MedicationMilestoneEntity::class,
        SymptomLogEntity::class,
        SuklMedicationEntity::class,
        PrescriptionEntity::class,
        PrescriptionItemEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MedsyDatabase : RoomDatabase() {

    abstract fun medicationDao(): MedicationDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun doseLogDao(): DoseLogDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun consumableDao(): ConsumableDao
    abstract fun bloodTestDao(): BloodTestDao
    abstract fun milestoneDao(): MilestoneDao
    abstract fun symptomDao(): SymptomDao
    abstract fun suklDao(): SuklDao
    abstract fun prescriptionDao(): PrescriptionDao

    companion object {
        const val NAME = "medsy.db"

        /** Bump together with `version` above. */
        const val CURRENT_VERSION = 3

        val MIGRATIONS = MedsyMigrations.ALL

        val MIGRATION_SQL = MedsyMigrations.SQL
    }
}
