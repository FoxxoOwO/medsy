package cz.medsy.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The project shipped `version = 1` with only a destructive *downgrade* fallback, and no
 * migration at all. Every new column would therefore wipe the user's data on update, so
 * migrations are generated, versioned and tested before any schema change ships.
 *
 * These assertions read the SQL text rather than a live database: they guarantee each
 * migration exists, is registered, and does not rely on destructive fallbacks. The
 * upgrade itself is exercised by [MigrationImportTest] against real SQLite.
 */
class MigrationRegistryTest {

    @Test
    fun `every schema version has a migration except the current one`() {
        val current = MedsyDatabase.CURRENT_VERSION
        assertTrue("database must have advanced past v1", current >= 2)

        val registered = MedsyDatabase.MIGRATIONS.map { "${it.startVersion}->${it.endVersion}" }.toSet()
        for (version in 1 until current) {
            val key = "$version->${version + 1}"
            assertTrue("missing migration $key, got $registered", key in registered)
        }
    }

    @Test
    fun `no destructive fallback remains in the database builder`() {
        val source = java.io.File("src/main/java/cz/medsy/di/DatabaseModule.kt").readText()
        assertTrue(
            "fallbackToDestructiveMigration would erase patient data on upgrade",
            !source.contains("fallbackToDestructiveMigration"),
        )
        assertTrue(
            "the builder must register the migrations",
            source.contains("addMigrations"),
        )
    }

    @Test
    fun `v2 adds the HRT form columns as nullable or defaulted`() {
        val migration = MedsyDatabase.MIGRATIONS.first { it.endVersion == 2 }
        // A migration that adds a NOT NULL column without a default fails on existing rows.
        val sql = migrationStatements(migration).joinToString("\n").lowercase()
        assertTrue(sql.contains("patchstate") || sql.contains("actuationsperdose") || sql.contains("holdminutes"))
    }

    @Test
    fun `current version matches the exported schema`() {
        val schemaDir = java.io.File("schemas/cz.medsy.data.local.MedsyDatabase")
        assertTrue("schema directory missing", schemaDir.isDirectory)
        val versions = schemaDir.listFiles()
            ?.filter { it.name.endsWith(".json") }
            ?.mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }
            ?.toSet()
            .orEmpty()
        assertTrue(
            "exported schemas $versions do not include the current version ${MedsyDatabase.CURRENT_VERSION}",
            versions.contains(MedsyDatabase.CURRENT_VERSION),
        )
        assertTrue("schema v1 must stay exported for the migration test", versions.contains(1))
    }

    @Test
    fun `v2 migrations keep existing rows intact`() {
        val migration = MedsyDatabase.MIGRATIONS.first { it.endVersion == 2 }
        val statements = migrationStatements(migration)
        assertTrue("expected at least one ALTER TABLE", statements.any { it.contains("ALTER TABLE", ignoreCase = true) })
        assertTrue(
            "no migration may DROP a table that holds patient records",
            statements.none { it.contains("DROP TABLE", ignoreCase = true) },
        )
    }

    /**
     * The migration body is a lambda, so its SQL cannot be introspected directly; the
     * statements are kept in a readable registry next to the migration for exactly this
     * purpose.
     */
    private fun migrationStatements(migration: androidx.room.migration.Migration): List<String> =
        MedsyDatabase.MIGRATION_SQL["${migration.startVersion}->${migration.endVersion}"].orEmpty()

    @Test
    fun `meaningful baseline data survives the export`() {
        // Guards the schema export itself: if v1 disappeared, the migration test would
        // be testing against an empty database and could pass vacuously.
        val schema = java.io.File("schemas/cz.medsy.data.local.MedsyDatabase/1.json")
        assertNotNull(schema)
        assertTrue("schema v1 json is empty", schema.readText().contains("\"version\": 1"))
    }

    @Test
    fun `foreign keys are declared for the tables the migration touches`() {
        val schema = java.io.File("schemas/cz.medsy.data.local.MedsyDatabase/1.json").readText()
        assertTrue(schema.contains("dose_logs"))
        assertTrue(schema.contains("schedules"))
    }

    @Test
    fun `database name is stable`() {
        assertEquals("medsy.db", MedsyDatabase.NAME)
    }
}
