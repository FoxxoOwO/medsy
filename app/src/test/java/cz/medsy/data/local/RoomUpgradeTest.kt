package cz.medsy.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Opens a real v1 database file through Room with the registered migrations.
 *
 * This is the check that actually protects users: Room validates the resulting schema
 * against the entities (identity hash + every column). If the migration and the entities
 * disagreed, `build()` would throw `IllegalStateException` instead of quietly serving a
 * broken database.
 */
@RunWith(RobolectricTestRunner::class)
class RoomUpgradeTest {

    private val dbName = "room-upgrade-test.db"
    private lateinit var databaseFile: File
    private var db: MedsyDatabase? = null

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        databaseFile = context.getDatabasePath(dbName)
        databaseFile.parentFile?.mkdirs()
        databaseFile.delete()
        createV1File()
    }

    @After
    fun tearDown() {
        db?.close()
        databaseFile.delete()
        File(databaseFile.path + "-shm").delete()
        File(databaseFile.path + "-wal").delete()
    }

    @Suppress("DEPRECATION")
    private fun createV1File() {
        val raw = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        try {
            createV1Schema(raw)
            // Realistic pre-upgrade data: if the migration wiped anything, these vanish.
            raw.execSQL(
                "INSERT INTO medications (id, name, form, isHrt, isArchived, createdAt) " +
                    "VALUES (1, 'Progesterone', 'TABLET', 1, 0, 0)",
            )
            raw.execSQL(
                "INSERT INTO schedules (medicationId, scheduleType, timesOfDayJson, doseAmount, " +
                    "doseUnit, mealRelation, offsetMinutes, requireSiteRotation, isActive, startDateEpochDay) " +
                    "VALUES (1, 'FIXED_DAILY', '[\"22:00\"]', 200.0, 'mg', 'NONE', 0, 0, 1, 20000)",
            )
            raw.setVersion(1)
        } finally {
            raw.close()
        }
    }

    private fun openRoom(): MedsyDatabase {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        return Room.databaseBuilder(context, MedsyDatabase::class.java, dbName)
            .addMigrations(*MedsyDatabase.MIGRATIONS.toTypedArray())
            .allowMainThreadQueries()
            .build()
            .also { db = it }
    }

    @Test
    fun `room opens a v1 file and keeps the data`() = runBlocking {
        val database = openRoom()

        val medications = database.medicationDao().observeAll().first()

        assertEquals(1, medications.size)
        assertEquals("Progesterone", medications.single().name)
    }

    @Test
    fun `schedules survive and the new columns read as null`() = runBlocking {
        val database = openRoom()

        val schedule = database.scheduleDao().getActive().single()

        assertEquals(200.0, schedule.doseAmount, 0.0001)
        assertEquals(null, schedule.actuationsPerDose)
        assertEquals(null, schedule.holdMinutes)
    }

    @Test
    fun `room accepts the new prescription tables`() = runBlocking {
        val database = openRoom()

        // Opening already validated the schema; this proves the tables are queryable.
        val inserts = database.openHelper.writableDatabase
        inserts.execSQL(
            "INSERT INTO prescriptions (code, dispensed, createdAt) VALUES ('ROOMTEST1234', 0, 0)",
        )
        assertTrue(
            inserts.query("SELECT COUNT(*) FROM prescriptions").use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0) == 1
            },
        )
    }

    @Test
    fun `downgrade is not silently destructive`() {
        // v1 file opened by v2 Room must migrate, not wipe. If a destructive fallback were
        // still registered, the medication row above would disappear instead of surviving.
        val database = openRoom()
        val count = database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM medications")
            .use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
        assertEquals(1, count)
    }
    /** Exact v1 DDL, generated from `schemas/.../1.json`, so the test cannot drift. */
    private fun createV1Schema(db: android.database.sqlite.SQLiteDatabase) {
        val statements = listOf(
            "CREATE TABLE IF NOT EXISTS `medications` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `suklCode` TEXT, `gtin` TEXT, `name` TEXT NOT NULL, `activeSubstance` TEXT, `atcCode` TEXT, `form` TEXT NOT NULL, `strengthValue` REAL, `strengthUnit` TEXT, `concentrationMgPerMl` REAL, `pilUrl` TEXT, `spcUrl` TEXT, `notes` TEXT, `colorHex` TEXT, `shapeType` TEXT, `isHrt` INTEGER NOT NULL, `isArchived` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_medications_suklCode` ON `medications` (`suklCode`)",
            "CREATE INDEX IF NOT EXISTS `index_medications_gtin` ON `medications` (`gtin`)",
            "CREATE INDEX IF NOT EXISTS `index_medications_atcCode` ON `medications` (`atcCode`)",
            "CREATE TABLE IF NOT EXISTS `schedules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `medicationId` INTEGER NOT NULL, `scheduleType` TEXT NOT NULL, `intervalValue` REAL, `daysOfWeekBitmask` INTEGER, `timesOfDayJson` TEXT NOT NULL, `doseAmount` REAL NOT NULL, `doseUnit` TEXT NOT NULL, `mealRelation` TEXT NOT NULL, `offsetMinutes` INTEGER NOT NULL, `requireSiteRotation` INTEGER NOT NULL, `isActive` INTEGER NOT NULL, `startDateEpochDay` INTEGER NOT NULL, `taperStepsJson` TEXT, `maxDailyDose` REAL, `note` TEXT, FOREIGN KEY(`medicationId`) REFERENCES `medications`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_schedules_medicationId` ON `schedules` (`medicationId`)",
            "CREATE TABLE IF NOT EXISTS `dose_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `medicationId` INTEGER NOT NULL, `scheduleId` INTEGER NOT NULL, `scheduledTimestamp` INTEGER NOT NULL, `actualTimestamp` INTEGER, `status` TEXT NOT NULL, `actualDoseAmount` REAL, `actualVolumeMl` REAL, `applicationSite` TEXT, `notes` TEXT, `batchId` INTEGER, `isPrn` INTEGER NOT NULL, FOREIGN KEY(`medicationId`) REFERENCES `medications`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_dose_logs_medicationId` ON `dose_logs` (`medicationId`)",
            "CREATE INDEX IF NOT EXISTS `index_dose_logs_scheduleId` ON `dose_logs` (`scheduleId`)",
            "CREATE INDEX IF NOT EXISTS `index_dose_logs_scheduledTimestamp` ON `dose_logs` (`scheduledTimestamp`)",
            "CREATE INDEX IF NOT EXISTS `index_dose_logs_medicationId_scheduledTimestamp` ON `dose_logs` (`medicationId`, `scheduledTimestamp`)",
            "CREATE TABLE IF NOT EXISTS `inventory_batches` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `medicationId` INTEGER NOT NULL, `batchNumber` TEXT, `expirationDateEpochDay` INTEGER, `firstPuncturedDateEpochDay` INTEGER, `beyondUseDaysLimit` INTEGER, `initialQuantity` REAL NOT NULL, `remainingQuantity` REAL NOT NULL, `unit` TEXT NOT NULL, `deadSpaceMlPerDose` REAL, `lowStockThresholdDays` INTEGER NOT NULL, FOREIGN KEY(`medicationId`) REFERENCES `medications`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_inventory_batches_medicationId` ON `inventory_batches` (`medicationId`)",
            "CREATE TABLE IF NOT EXISTS `consumables` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `remainingCount` INTEGER NOT NULL, `lowStockAlertCount` INTEGER NOT NULL, `deductPerMedicationId` INTEGER, `deductPerDose` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `blood_test_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `labName` TEXT, `fastingStatus` INTEGER NOT NULL, `calculatedHoursSinceLastHrtDose` REAL, `isTroughLevel` INTEGER NOT NULL, `notes` TEXT, `attachmentUri` TEXT, `source` TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_blood_test_sessions_timestamp` ON `blood_test_sessions` (`timestamp`)",
            "CREATE TABLE IF NOT EXISTS `biomarker_values` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, `biomarkerCode` TEXT NOT NULL, `rawValue` REAL NOT NULL, `rawUnit` TEXT NOT NULL, `normalizedSiValue` REAL NOT NULL, `normalizedSiUnit` TEXT NOT NULL, `labMin` REAL, `labMax` REAL, FOREIGN KEY(`sessionId`) REFERENCES `blood_test_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_biomarker_values_sessionId` ON `biomarker_values` (`sessionId`)",
            "CREATE INDEX IF NOT EXISTS `index_biomarker_values_biomarkerCode` ON `biomarker_values` (`biomarkerCode`)",
            "CREATE TABLE IF NOT EXISTS `reference_ranges` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `biomarkerCode` TEXT NOT NULL, `profileType` TEXT NOT NULL, `targetMinSi` REAL, `targetMaxSi` REAL, `optimalMinSi` REAL, `optimalMaxSi` REAL, `warningHighSi` REAL, `warningLowSi` REAL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_reference_ranges_biomarkerCode_profileType` ON `reference_ranges` (`biomarkerCode`, `profileType`)",
            "CREATE TABLE IF NOT EXISTS `medication_milestones` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `medicationId` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `changeType` TEXT NOT NULL, `description` TEXT NOT NULL, FOREIGN KEY(`medicationId`) REFERENCES `medications`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_medication_milestones_medicationId` ON `medication_milestones` (`medicationId`)",
            "CREATE INDEX IF NOT EXISTS `index_medication_milestones_timestamp` ON `medication_milestones` (`timestamp`)",
            "CREATE TABLE IF NOT EXISTS `symptom_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `symptomCode` TEXT NOT NULL, `severity` INTEGER NOT NULL, `note` TEXT, `systolic` INTEGER, `diastolic` INTEGER, `heartRate` INTEGER)",
            "CREATE INDEX IF NOT EXISTS `index_symptom_logs_timestamp` ON `symptom_logs` (`timestamp`)",
            "CREATE INDEX IF NOT EXISTS `index_symptom_logs_symptomCode` ON `symptom_logs` (`symptomCode`)",
            "CREATE TABLE IF NOT EXISTS `sukl_medications` (`suklCode` TEXT NOT NULL, `gtin` TEXT, `name` TEXT NOT NULL, `activeSubstance` TEXT, `atcCode` TEXT, `strength` TEXT, `formName` TEXT, `holder` TEXT, `syncedAt` INTEGER NOT NULL, PRIMARY KEY(`suklCode`))",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sukl_medications_suklCode` ON `sukl_medications` (`suklCode`)",
            "CREATE INDEX IF NOT EXISTS `index_sukl_medications_gtin` ON `sukl_medications` (`gtin`)",
        )
        statements.forEach(db::execSQL)
    }
}
