package cz.medsy.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Builds a SQLCipher-backed open helper. The passphrase is a 32-byte key produced
 * by [cz.medsy.security.DatabaseKeyStore] and stored in the Android Keystore-backed
 * encrypted preferences, never in plaintext.
 */
object SqlCipherFactory {

    fun create(passphrase: ByteArray): SupportSQLiteOpenHelper.Factory {
        System.loadLibrary("sqlcipher")
        return SupportOpenHelperFactory(passphrase)
    }

    fun runCipherSelfTest(db: SupportSQLiteDatabase): Boolean {
        return runCatching {
            db.query("PRAGMA cipher_version").use { cursor ->
                cursor.moveToFirst() && !cursor.getString(0).isNullOrBlank()
            }
        }.getOrDefault(false)
    }

    internal fun isEncrypted(fileHeader: ByteArray): Boolean {
        if (fileHeader.size < 16) return false
        val header = String(fileHeader, 0, 16, Charsets.US_ASCII)
        return header != "SQLite format 3\u0000"
    }
}

@Suppress("unused")
internal fun SQLiteDatabase.enableForeignKeys() {
    execSQL("PRAGMA foreign_keys = ON")
}
