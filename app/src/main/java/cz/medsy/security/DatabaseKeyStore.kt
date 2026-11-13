package cz.medsy.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.SecureRandom
import android.util.Base64

private val Context.keyStoreData: DataStore<Preferences> by preferencesDataStore(name = "medsy_keys")

/**
 * Produces the SQLCipher passphrase. A random 32-byte passphrase is generated once,
 * wrapped with an AES/GCM key held in the Android Keystore (hardware-backed where
 * available) and persisted as ciphertext. The passphrase itself never touches disk.
 */
class DatabaseKeyStore(private val context: Context) {

    private val passphrasePref = stringPreferencesKey("db_passphrase_wrapped")
    private val ivPref = stringPreferencesKey("db_passphrase_iv")

    fun passphrase(): ByteArray {
        val prefs = runBlocking { context.keyStoreData.data.first() }
        val wrapped = prefs[passphrasePref]
        val iv = prefs[ivPref]
        if (wrapped == null || iv == null) {
            return runBlocking { createAndStore() }
        }
        return unwrap(Base64.decode(wrapped, Base64.NO_WRAP), Base64.decode(iv, Base64.NO_WRAP))
    }

    private fun createAndStore(): ByteArray {
        val passphrase = ByteArray(32).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val wrapped = cipher.doFinal(passphrase)
        val iv = cipher.iv
        runBlocking {
            context.keyStoreData.edit {
                it[passphrasePref] = Base64.encodeToString(wrapped, Base64.NO_WRAP)
                it[ivPref] = Base64.encodeToString(iv, Base64.NO_WRAP)
            }
        }
        return passphrase
    }

    private fun unwrap(wrapped: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
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
    }
}
