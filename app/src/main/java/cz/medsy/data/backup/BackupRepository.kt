package cz.medsy.data.backup

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import cz.medsy.data.local.MedsyDatabase
import cz.medsy.domain.message.MessageKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whole-database backup as JSON.
 *
 * Rows are read and written through raw cursors rather than through serialisable DTOs for one
 * reason: a DTO per table drifts the moment a column is added, and a backup that quietly drops a
 * new column is worse than no backup. Reading the cursor's own column list means a file written by
 * an older build restores into a newer schema (the missing columns take their defaults) and a file
 * from a newer build is rejected by [BackupFormat.VERSION] instead of being silently truncated.
 *
 * Ids are never trusted on the way back in. Every row is inserted without its `id` column, and the
 * ids handed out by SQLite are mapped onto the ids the children in the file refer to. That is what
 * makes a restore into a non-empty database possible at all — the old ids would collide with rows
 * that already exist.
 */
@Singleton
class BackupRepository @Inject constructor(
    private val db: MedsyDatabase,
) {

    enum class Mode { REPLACE, MERGE }

    /** [inserted] counts rows actually written, [skipped] rows already present or unusable. */
    data class Report(val inserted: Int, val skipped: Int)

    /**
     * @param password null or empty for a readable file. Anything else produces an
     *   [BackupCrypto] envelope.
     */
    suspend fun export(password: CharArray? = null): String = withContext(Dispatchers.IO) {
        val payload = db.withTransaction {
            buildJsonObject {
                put("format", BackupFormat.HEADER)
                put("version", BackupFormat.VERSION)
                put("createdAt", System.currentTimeMillis())
                put("tables", buildJsonObject {
                    BackupFormat.TABLES.forEach { table -> put(table.name, readTable(table.name)) }
                })
            }
        }.toString()
        if (password == null || password.isEmpty()) payload else BackupCrypto.encrypt(payload, password)
    }

    suspend fun import(text: String, password: CharArray?, mode: Mode): Report =
        withContext(Dispatchers.IO) {
            val plain = if (BackupCrypto.isEncrypted(text)) {
                val secret = password?.takeIf { it.isNotEmpty() }
                    ?: throw BackupException(MessageKey.BACKUP_PASSWORD_REQUIRED)
                BackupCrypto.decrypt(text, secret)
            } else {
                text
            }
            val root = runCatching { Json.parseToJsonElement(plain).jsonObject }
                .getOrElse { throw BackupException(MessageKey.BACKUP_UNREADABLE) }
            if (root["format"]?.jsonPrimitive?.contentOrNull != BackupFormat.HEADER) {
                throw BackupException(MessageKey.BACKUP_UNREADABLE)
            }
            val version = root["version"]?.jsonPrimitive?.intOrNull ?: 0
            if (version > BackupFormat.VERSION) throw BackupException(MessageKey.BACKUP_NEWER_VERSION)
            val tables = root["tables"] as? JsonObject
                ?: throw BackupException(MessageKey.BACKUP_UNREADABLE)

            var inserted = 0
            var skipped = 0
            db.withTransaction {
                val writable = db.openHelper.writableDatabase
                if (mode == Mode.REPLACE) {
                    // Children first: a parent delete cascades, which is right, but deleting the
                    // parent before its children would also work — this order keeps the counts
                    // honest if a cascade ever surprises us.
                    BackupFormat.TABLES.asReversed().forEach { writable.delete(it.name, null, null) }
                }
                val restoredIds = BackupFormat.TABLES.associate { it.name to HashMap<Long, Long>() }
                BackupFormat.TABLES.forEach { table ->
                    val rows = tables[table.name] as? JsonArray ?: return@forEach
                    val existing = if (mode == Mode.MERGE) existingKeys(table) else emptySet()
                    rows.forEach { element ->
                        val row = element as? JsonObject ?: return@forEach
                        val values = contentValues(row, table, restoredIds) ?: run {
                            skipped++
                            return@forEach
                        }
                        val key = keyOf(table.keys, values)
                        if (key != null && key in existing) {
                            // Same record already here; still remember the id so the children of
                            // this row attach to the copy that exists instead of being dropped.
                            row["id"]?.jsonPrimitive?.longOrNull?.let { old ->
                                existingIdFor(table, key)?.let { restoredIds.getValue(table.name)[old] = it }
                            }
                            skipped++
                            return@forEach
                        }
                        val newId = runCatching {
                            writable.insert(table.name, SQLiteDatabase.CONFLICT_ABORT, values)
                        }.getOrDefault(-1L)
                        if (newId <= 0L) {
                            skipped++
                            return@forEach
                        }
                        inserted++
                        row["id"]?.jsonPrimitive?.longOrNull?.let { old ->
                            restoredIds.getValue(table.name)[old] = newId
                        }
                    }
                }
            }
            Report(inserted = inserted, skipped = skipped)
        }

    private fun readTable(table: String): JsonArray {
        val rows = ArrayList<JsonElement>()
        db.query("SELECT * FROM $table", null).use { cursor ->
            while (cursor.moveToNext()) {
                rows += buildJsonObject {
                    for (index in 0 until cursor.columnCount) {
                        val name = cursor.getColumnName(index)
                        when (cursor.getType(index)) {
                            Cursor.FIELD_TYPE_NULL -> put(name, JsonNull)
                            Cursor.FIELD_TYPE_INTEGER -> put(name, cursor.getLong(index))
                            Cursor.FIELD_TYPE_FLOAT -> put(name, cursor.getDouble(index))
                            Cursor.FIELD_TYPE_BLOB -> put(
                                name,
                                android.util.Base64.encodeToString(
                                    cursor.getBlob(index),
                                    android.util.Base64.NO_WRAP,
                                ),
                            )
                            else -> put(name, cursor.getString(index))
                        }
                    }
                }
            }
        }
        return buildJsonArray { rows.forEach(::add) }
    }

    /**
     * @return null when a mandatory parent is missing from the file, so the row cannot be inserted
     *   without breaking a foreign key.
     */
    private fun contentValues(
        row: JsonObject,
        table: BackupFormat.Table,
        restoredIds: Map<String, Map<Long, Long>>,
    ): ContentValues? {
        val values = ContentValues()
        for ((column, element) in row) {
            if (column == "id") continue
            val parent = table.parents[column]
            if (parent != null) {
                val old = (element as? JsonPrimitive)?.longOrNull
                val mapped = old?.let { restoredIds[parent]?.get(it) }
                if (mapped == null) {
                    if (column in BackupFormat.NULLABLE_PARENTS) {
                        values.putNull(column)
                        continue
                    }
                    return null
                }
                values.put(column, mapped)
                continue
            }
            when {
                element is JsonNull -> values.putNull(column)
                element !is JsonPrimitive -> values.putNull(column)
                element.isString -> values.put(column, element.content)
                element.content == "true" -> values.put(column, 1)
                element.content == "false" -> values.put(column, 0)
                element.doubleOrNull != null && element.content.any { it == '.' || it == 'e' || it == 'E' } ->
                    values.put(column, element.double)
                else -> values.put(column, element.long)
            }
        }
        return values
    }

    private fun existingKeys(table: BackupFormat.Table): Set<String> {
        val columns = table.keys.joinToString(", ")
        val keys = HashSet<String>()
        db.query("SELECT $columns FROM ${table.name}", null).use { cursor ->
            while (cursor.moveToNext()) {
                keys += keyOf(cursorValues(cursor), table.keys.size)
            }
        }
        return keys
    }

    /** Only used to let children of an already-present row attach to it. */
    private fun existingIdFor(table: BackupFormat.Table, key: String): Long? {
        val columns = "id, " + table.keys.joinToString(", ")
        db.query("SELECT $columns FROM ${table.name}", null).use { cursor ->
            while (cursor.moveToNext()) {
                val values = (1 until cursor.columnCount).map { index -> cursorValue(cursor, index) }
                if (keyOf(values, table.keys.size) == key) return cursor.getLong(0)
            }
        }
        return null
    }

    private fun cursorValues(cursor: Cursor): List<Any?> =
        (0 until cursor.columnCount).map { index -> cursorValue(cursor, index) }

    private fun cursorValue(cursor: Cursor, index: Int): Any? = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
        else -> cursor.getString(index)
    }

    private fun keyOf(columns: List<String>, values: ContentValues): String? {
        if (values.size() == 0) return null
        val parts = columns.map { values.get(it) }
        if (parts.all { it == null }) return null
        return keyOf(parts, columns.size)
    }

    private fun keyOf(values: List<Any?>, size: Int): String =
        values.take(size).joinToString("\u0001") { normalize(it) }

    /** `1` and `1.0` are the same column value; comparing them as text would not be. */
    private fun normalize(value: Any?): String = when (value) {
        null -> ""
        is Number -> {
            val asDouble = value.toDouble()
            if (asDouble == Math.floor(asDouble) && !asDouble.isInfinite()) {
                asDouble.toLong().toString()
            } else {
                asDouble.toString()
            }
        }
        else -> value.toString()
    }
}
