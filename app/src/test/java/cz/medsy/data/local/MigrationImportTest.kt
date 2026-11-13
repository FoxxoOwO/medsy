package cz.medsy.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Upgrade v1 → v2 against real SQLite, with a row in every table that the migration
 * touches. This is the test that proves an existing user does not lose data, which is
 * the whole risk behind adding columns.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationImportTest {

    private val dbName = "migration-test.db"
    private lateinit var databaseFile: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        databaseFile = context.getDatabasePath(dbName)
        databaseFile.parentFile?.mkdirs()
        databaseFile.delete()
    }

    @After
    fun tearDown() {
        databaseFile.delete()
    }

    /** Creates the v1 schema exactly as it shipped, then fills it with realistic rows. */
    private fun createV1WithData() {
        @Suppress("DEPRECATION")
        val db = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `medications` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `suklCode` TEXT, `gtin` TEXT, `name` TEXT NOT NULL,
                    `activeSubstance` TEXT, `atcCode` TEXT, `form` TEXT NOT NULL,
                    `strengthValue` REAL, `strengthUnit` TEXT, `concentrationMgPerMl` REAL,
                    `pilUrl` TEXT, `spcUrl` TEXT, `notes` TEXT, `colorHex` TEXT, `shapeType` TEXT,
                    `isHrt` INTEGER NOT NULL DEFAULT 0, `isArchived` INTEGER NOT NULL DEFAULT 0,
                    `createdAt` INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `schedules` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `medicationId` INTEGER NOT NULL,
                    `scheduleType` TEXT NOT NULL,
                    `intervalValue` REAL, `daysOfWeekBitmask` INTEGER,
                    `timesOfDayJson` TEXT NOT NULL,
                    `doseAmount` REAL NOT NULL, `doseUnit` TEXT NOT NULL,
                    `mealRelation` TEXT NOT NULL, `offsetMinutes` INTEGER NOT NULL DEFAULT 0,
                    `requireSiteRotation` INTEGER NOT NULL DEFAULT 0,
                    `isActive` INTEGER NOT NULL DEFAULT 1,
                    `startDateEpochDay` INTEGER NOT NULL,
                    `taperStepsJson` TEXT, `maxDailyDose` REAL, `note` TEXT,
                    FOREIGN KEY(`medicationId`) REFERENCES `medications`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `dose_logs` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `medicationId` INTEGER NOT NULL, `scheduleId` INTEGER NOT NULL,
                    `scheduledTimestamp` INTEGER NOT NULL, `actualTimestamp` INTEGER,
                    `status` TEXT NOT NULL, `actualDoseAmount` REAL, `actualVolumeMl` REAL,
                    `applicationSite` TEXT, `notes` TEXT, `batchId` INTEGER,
                    `isPrn` INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY(`medicationId`) REFERENCES `medications`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )

            db.execSQL(
                "INSERT INTO medications (id, name, form, isHrt) VALUES (1, 'Estradiol Enanthate', 'INJECTION_IM', 1)",
            )
            db.execSQL(
                """
                INSERT INTO schedules (id, medicationId, scheduleType, intervalValue, timesOfDayJson,
                    doseAmount, doseUnit, mealRelation, startDateEpochDay)
                VALUES (1, 1, 'INTERVAL_DAYS', 3.5, '["08:00"]', 5.0, 'mg', 'NONE', 20000)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO dose_logs (id, medicationId, scheduleId, scheduledTimestamp, status,
                    actualDoseAmount, applicationSite)
                VALUES (1, 1, 1, 1762758000000, 'TAKEN', 5.0, 'LEFT_VENTROGLUTEAL')
                """.trimIndent(),
            )
            db.setVersion(1)
        } finally {
            db.close()
        }
    }

    private fun migratedDatabase(): SQLiteDatabase {
        // Run only the statements of the real migration, in order, exactly as Room would.
        @Suppress("DEPRECATION")
        val db = SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        // Raw SQLite has foreign keys off by default; Room enables them per connection.
        // Without this the ON DELETE CASCADE in the new tables would silently not fire.
        db.execSQL("PRAGMA foreign_keys = ON")
        MedsyMigrations.statements1to2().forEach(db::execSQL)
        db.setVersion(2)
        return db
    }

    @Test
    fun `existing rows survive the upgrade`() {
        createV1WithData()
        val db = migratedDatabase()
        try {
            db.rawQuery("SELECT name FROM medications WHERE id = 1", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Estradiol Enanthate", cursor.getString(0))
            }
            db.rawQuery("SELECT doseAmount FROM schedules WHERE id = 1", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(5.0, cursor.getDouble(0), 0.0001)
            }
            db.rawQuery("SELECT applicationSite FROM dose_logs WHERE id = 1", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("LEFT_VENTROGLUTEAL", cursor.getString(0))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `new columns exist and default to null for old rows`() {
        createV1WithData()
        val db = migratedDatabase()
        try {
            db.rawQuery(
                "SELECT actuationsPerDose, holdMinutes FROM schedules WHERE id = 1",
                null,
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertTrue(cursor.isNull(1))
            }
            db.rawQuery("SELECT patchState, wornUntil FROM dose_logs WHERE id = 1", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertTrue(cursor.isNull(1))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `prescription tables are created and usable`() {
        createV1WithData()
        val db = migratedDatabase()
        try {
            db.execSQL(
                "INSERT INTO prescriptions (code, validUntil, dispensed, createdAt) VALUES ('ABCD1234EFGH', 1765000000000, 0, 1762758000000)",
            )
            val prescriptionId = db.rawQuery("SELECT id FROM prescriptions", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getLong(0)
            }
            db.execSQL(
                "INSERT INTO prescription_items (prescriptionId, name, quantity) VALUES ($prescriptionId, 'Estrofem 2mg', 84)",
            )
            db.rawQuery(
                "SELECT name, quantity FROM prescription_items WHERE prescriptionId = $prescriptionId",
                null,
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Estrofem 2mg", cursor.getString(0))
                assertEquals(84, cursor.getInt(1))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `deleting a prescription cascades to its items`() {
        createV1WithData()
        val db = migratedDatabase()
        try {
            db.execSQL(
                "INSERT INTO prescriptions (id, code, dispensed, createdAt) VALUES (10, 'X', 0, 0)",
            )
            db.execSQL(
                "INSERT INTO prescription_items (prescriptionId, name) VALUES (10, 'Item')",
            )
            db.execSQL("DELETE FROM prescriptions WHERE id = 10")

            db.rawQuery("SELECT COUNT(*) FROM prescription_items WHERE prescriptionId = 10", null)
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
        } finally {
            db.close()
        }
    }

    @Test
    fun `prescription codes are unique`() {
        createV1WithData()
        val db = migratedDatabase()
        try {
            db.execSQL("INSERT INTO prescriptions (code, dispensed, createdAt) VALUES ('DUPLICATE123', 0, 0)")
            var threw = false
            try {
                db.execSQL("INSERT INTO prescriptions (code, dispensed, createdAt) VALUES ('DUPLICATE123', 0, 0)")
            } catch (expected: android.database.sqlite.SQLiteConstraintException) {
                threw = true
            }
            assertTrue("a duplicate prescription code must be rejected", threw)
        } finally {
            db.close()
        }
    }

    @Test
    fun `the migrated schema version is two`() {
        createV1WithData()
        val db = migratedDatabase()
        try {
            assertEquals(2, db.version)
        } finally {
            db.close()
        }
    }

    @Test
    fun `table creation statements can be replayed but ALTER cannot`() {
        createV1WithData()
        val db = migratedDatabase()
        try {
            // Room runs a migration exactly once, so ALTER TABLE ADD COLUMN is not
            // expected to be re-runnable. The CREATE statements carry IF NOT EXISTS
            // precisely so a partially applied upgrade can be retried safely.
            val creates = MedsyMigrations.statements1to2()
                .filter { it.trimStart().startsWith("CREATE", ignoreCase = true) }
            assertTrue("expected guarded CREATE statements", creates.isNotEmpty())
            creates.forEach(db::execSQL)

            var alterThrew = false
            try {
                db.execSQL("ALTER TABLE dose_logs ADD COLUMN patchState TEXT")
            } catch (expected: android.database.sqlite.SQLiteException) {
                alterThrew = true
            }
            assertTrue("ALTER is single-use; the migration relies on that", alterThrew)
        } finally {
            db.close()
        }
    }

    @Test
    fun `an unrelated error would be louder than silence`() {
        // Sanity: a bad statement must throw, so the test above is not vacuous.
        createV1WithData()
        val db = migratedDatabase()
        try {
            var threw = false
            try {
                db.execSQL("ALTER TABLE does_not_exist ADD COLUMN x INTEGER")
            } catch (expected: android.database.sqlite.SQLiteException) {
                threw = true
            }
            assertTrue(threw)
        } finally {
            db.close()
        }
    }
}
