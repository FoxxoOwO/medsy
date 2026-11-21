package cz.medsy.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.legacyKeys: DataStore<Preferences> by preferencesDataStore(name = "medsy_keys")

/**
 * Produces the SQLCipher passphrase. A random 32-byte passphrase is generated once,
 * wrapped with an AES/GCM key held in the Android Keystore (hardware-backed where
 * available) and persisted as ciphertext. The passphrase itself never touches disk.
 *
 * The wrapped values live in SharedPreferences: [passphrase] is called synchronously from
 * Hilt's database provider during cold start, so a DataStore coroutine there would mean a
 * `runBlocking` on the main thread on *every* launch. Existing installs are migrated once
 * from the old DataStore before the database is opened, so their encrypted data survives.
 */
class DatabaseKeyStore(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val legacyWrappedKey = stringPreferencesKey("db_passphrase_wrapped")
    private val legacyIvKey = stringPreferencesKey("db_passphrase_iv")

    fun passphrase(): ByteArray {
        val wrapped = prefs.getString(KEY_WRAPPED, null)
        val iv = prefs.getString(KEY_IV, null)
        if (wrapped != null && iv != null) {
            return unwrap(Base64.decode(wrapped, Base64.NO_WRAP), Base64.decode(iv, Base64.NO_WRAP))
        }
        // One-time migration for installs created by the DataStore version. This is the
        // only cold-start blocking read, and it never runs on a fresh install.
        val legacy = runBlocking { context.legacyKeys.data.first() }
        val legacyWrapped = legacy[legacyWrappedKey]
        val legacyIv = legacy[legacyIvKey]
        if (legacyWrapped != null && legacyIv != null) {
            store(legacyWrapped, legacyIv)
            return unwrap(
                Base64.decode(legacyWrapped, Base64.NO_WRAP),
                Base64.decode(legacyIv, Base64.NO_WRAP),
            )
        }
        return createAndStore()
    }

    private fun createAndStore(): ByteArray {
        val passphrase = ByteArray(PASSPHRASE_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val wrapped = cipher.doFinal(passphrase)
        val iv = cipher.iv
        store(Base64.encodeToString(wrapped, Base64.NO_WRAP), Base64.encodeToString(iv, Base64.NO_WRAP))
        return passphrase
    }

    private fun store(wrapped: String, iv: String) {
        // commit(), not apply(): losing this write would generate a new key on the next
        // launch and make every encrypted row unreadable.
        check(
            prefs.edit()
                .putString(KEY_WRAPPED, wrapped)
                .putString(KEY_IV, iv)
                .commit(),
        ) { "could not persist the wrapped database passphrase" }
    }

    private fun unwrap(wrapped: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(wrapped)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "medsy_db_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFS_NAME = "medsy_keys"
        const val KEY_WRAPPED = "db_passphrase_wrapped"
        const val KEY_IV = "db_passphrase_iv"
        const val PASSPHRASE_BYTES = 32
        const val TAG_BITS = 128
    }
}
