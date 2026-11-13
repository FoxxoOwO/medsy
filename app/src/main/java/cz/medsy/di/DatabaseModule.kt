package cz.medsy.di

import android.content.Context
import androidx.room.Room
import cz.medsy.data.local.MedsyDatabase
import cz.medsy.data.local.SqlCipherFactory
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
import cz.medsy.data.local.dao.TranslationDao
import cz.medsy.security.DatabaseKeyStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MedsyDatabase {
        val keyStore = DatabaseKeyStore(context)
        val factory = SqlCipherFactory.create(keyStore.passphrase())
        return Room.databaseBuilder(context, MedsyDatabase::class.java, MedsyDatabase.NAME)
            .openHelperFactory(factory)
            // Never destructive: patient records must survive every upgrade (and a
            // downgrade should fail loudly rather than silently wipe the database).
            .addMigrations(*MedsyDatabase.MIGRATIONS.toTypedArray())
            .build()
    }

    @Provides fun medicationDao(db: MedsyDatabase): MedicationDao = db.medicationDao()
    @Provides fun scheduleDao(db: MedsyDatabase): ScheduleDao = db.scheduleDao()
    @Provides fun doseLogDao(db: MedsyDatabase): DoseLogDao = db.doseLogDao()
    @Provides fun inventoryDao(db: MedsyDatabase): InventoryDao = db.inventoryDao()
    @Provides fun consumableDao(db: MedsyDatabase): ConsumableDao = db.consumableDao()
    @Provides fun bloodTestDao(db: MedsyDatabase): BloodTestDao = db.bloodTestDao()
    @Provides fun milestoneDao(db: MedsyDatabase): MilestoneDao = db.milestoneDao()
    @Provides fun symptomDao(db: MedsyDatabase): SymptomDao = db.symptomDao()
    @Provides fun suklDao(db: MedsyDatabase): SuklDao = db.suklDao()

    @Provides fun prescriptionDao(db: MedsyDatabase): PrescriptionDao = db.prescriptionDao()
    @Provides fun translationDao(db: MedsyDatabase): TranslationDao = db.translationDao()
}
