package cz.medsy.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations.
 *
 * v1 shipped without any migration, so the first schema change would have destroyed
 * every patient record. From v2 on, each change gets an explicit migration here, and
 * `MIGRATION_SQL` mirrors the statements so tests can assert on them without running
 * the database.
 *
 * Rules for new columns:
 *  - nullable or with a DEFAULT, never a bare NOT NULL (existing rows would fail);
 *  - never DROP a table that holds patient data;
 *  - version the exported schema under `app/schemas`.
 */
object MedsyMigrations {

    /**
     * v2 — HRT administration forms and the prescription queue.
     * All columns are nullable or defaulted so existing rows are valid.
     */
    val MIGRATION_1_2 = Migration(1, 2) { db ->
        statements1to2().forEach(db::execSQL)
    }

    fun statements1to2(): List<String> = listOf(
        // Patches: state machine + wear window.
        "ALTER TABLE dose_logs ADD COLUMN patchState TEXT",
        "ALTER TABLE dose_logs ADD COLUMN wornUntil INTEGER",

        // Gels/sprays: how many actuations one dose consumes.
        "ALTER TABLE schedules ADD COLUMN actuationsPerDose INTEGER",

        // Sublingual/buccal: how long the dose must be held.
        "ALTER TABLE schedules ADD COLUMN holdMinutes INTEGER",

        // Prescription queue (eRecept).
        """
        CREATE TABLE IF NOT EXISTS `prescriptions` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `code` TEXT NOT NULL,
            `issuedAt` INTEGER,
            `validUntil` INTEGER,
            `dispensed` INTEGER NOT NULL DEFAULT 0,
            `createdAt` INTEGER NOT NULL DEFAULT 0
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_prescriptions_code` ON `prescriptions` (`code`)",
        """
        CREATE TABLE IF NOT EXISTS `prescription_items` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `prescriptionId` INTEGER NOT NULL,
            `suklCode` TEXT,
            `name` TEXT NOT NULL,
            `quantity` INTEGER,
            `dosageInstruction` TEXT,
            FOREIGN KEY(`prescriptionId`) REFERENCES `prescriptions`(`id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_prescription_items_prescriptionId` " +
            "ON `prescription_items` (`prescriptionId`)",
    )

    /**
     * v3 — milestones keep their sentence as a message key + arguments, so a switch to
     * Czech also localises the history. `description` stays for rows written before v3.
     */
    val MIGRATION_2_3 = Migration(2, 3) { db ->
        statements2to3().forEach(db::execSQL)
    }

    fun statements2to3(): List<String> = listOf(
        "ALTER TABLE medication_milestones ADD COLUMN message TEXT",
    )

    /** Every migration, oldest first. Registered in `DatabaseModule`. */
    val ALL: List<Migration> = listOf(MIGRATION_1_2, MIGRATION_2_3)

    /** Human-readable mirror used by `MigrationRegistryTest`. */
    val SQL: Map<String, List<String>> = mapOf(
        "1->2" to statements1to2(),
        "2->3" to statements2to3(),
    )
}
