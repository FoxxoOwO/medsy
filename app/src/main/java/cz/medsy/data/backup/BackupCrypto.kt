package cz.medsy.data.backup

import cz.medsy.domain.message.MessageKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password protection for backup files.
 *
 * A backup holds doses, blood results, symptoms and a prescription history, so an unprotected
 * export is a plain-text medical record sitting in Downloads. The envelope is deliberately boring:
 * PBKDF2-HMAC-SHA256 stretches the password, AES-GCM both encrypts and authenticates, and the
 * parameters travel in the file so a future build can raise the iteration count and still read
 * today's backups.
 *
 * The password itself is never stored and never leaves the device — there is no recovery, which is
 * why the UI asks for confirmation rather than silently encrypting.
 */
object BackupCrypto {

    private const val ENVELOPE = "cz.medsy.backup.enc"
    private const val KDF = "PBKDF2WithHmacSHA256"
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** True when the text is an encrypted envelope rather than a readable backup. */
    fun isEncrypted(text: String): Boolean =
        runCatching {
            val root = Json.parseToJsonElement(text.trim()).jsonObject
            root["format"]?.jsonPrimitive?.contentOrNull == ENVELOPE
        }.getOrDefault(false)

    fun encrypt(plain: String, password: CharArray): String {
        require(password.isNotEmpty()) { "empty password" }
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            key(password, salt, ITERATIONS),
            GCMParameterSpec(TAG_BITS, iv),
        )
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getEncoder()
        return buildJsonObject {
            put("format", ENVELOPE)
            put("version", BackupFormat.VERSION)
            put("kdf", KDF)
            put("iterations", ITERATIONS)
            put("salt", encoder.encodeToString(salt))
            put("iv", encoder.encodeToString(iv))
            put("data", encoder.encodeToString(cipherText))
        }.toString()
    }

    /**
     * @throws BackupException with [MessageKey.BACKUP_WRONG_PASSWORD] when the tag does not verify,
     *   i.e. the password is wrong or the file was edited.
     */
    fun decrypt(envelope: String, password: CharArray): String {
        val root = runCatching { Json.parseToJsonElement(envelope.trim()).jsonObject }
            .getOrElse { throw BackupException(MessageKey.BACKUP_UNREADABLE) }
        if (root["format"]?.jsonPrimitive?.contentOrNull != ENVELOPE) {
            // Not an envelope: let the caller treat it as an unencrypted backup.
            throw BackupException(MessageKey.BACKUP_UNREADABLE)
        }
        val decoder = Base64.getDecoder()
        val iterations = root["iterations"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: ITERATIONS
        val salt = root.stringOrThrow("salt", decoder)
        val iv = root.stringOrThrow("iv", decoder)
        val data = root.stringOrThrow("data", decoder)
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(password, salt, iterations),
            GCMParameterSpec(TAG_BITS, iv),
        )
        return runCatching { String(cipher.doFinal(data), Charsets.UTF_8) }
            .getOrElse { throw BackupException(MessageKey.BACKUP_WRONG_PASSWORD) }
    }

    private fun JsonObject.stringOrThrow(name: String, decoder: Base64.Decoder): ByteArray {
        val value = this[name]?.jsonPrimitive?.contentOrNull
            ?: throw BackupException(MessageKey.BACKUP_UNREADABLE)
        return runCatching { decoder.decode(value) }.getOrElse {
            throw BackupException(MessageKey.BACKUP_UNREADABLE)
        }
    }

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        return try {
            val raw = SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
            SecretKeySpec(raw, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}

/** A backup failure the UI can render in the user's language. */
class BackupException(val key: MessageKey) : Exception(key.name)
