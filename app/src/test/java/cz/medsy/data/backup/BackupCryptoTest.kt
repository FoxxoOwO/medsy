package cz.medsy.data.backup

import cz.medsy.domain.message.MessageKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupCryptoTest {

    private val plain = """{"format":"cz.medsy.backup","version":1,"tables":{}}"""

    @Test
    fun `a backup survives the round trip`() {
        val envelope = BackupCrypto.encrypt(plain, "korecek".toCharArray())
        assertEquals(plain, BackupCrypto.decrypt(envelope, "korecek".toCharArray()))
    }

    @Test
    fun `the envelope carries no readable data`() {
        val envelope = BackupCrypto.encrypt(plain, "korecek".toCharArray())
        assertFalse(envelope.contains("cz.medsy.backup\""))
        assertFalse(envelope.contains("tables"))
        assertTrue(BackupCrypto.isEncrypted(envelope))
    }

    @Test
    fun `an unencrypted file is not reported as encrypted`() {
        assertFalse(BackupCrypto.isEncrypted(plain))
        assertFalse(BackupCrypto.isEncrypted("not json at all"))
        assertFalse(BackupCrypto.isEncrypted(""))
    }

    @Test
    fun `the wrong password is reported as such, not as a corrupt file`() {
        val envelope = BackupCrypto.encrypt(plain, "korecek".toCharArray())
        val failure = runCatching { BackupCrypto.decrypt(envelope, "spatne".toCharArray()) }
            .exceptionOrNull()
        assertEquals(MessageKey.BACKUP_WRONG_PASSWORD, (failure as BackupException).key)
    }

    @Test
    fun `each export uses a fresh salt and iv`() {
        val first = BackupCrypto.encrypt(plain, "korecek".toCharArray())
        val second = BackupCrypto.encrypt(plain, "korecek".toCharArray())
        assertTrue(first != second)
    }

    @Test
    fun `a truncated envelope is unreadable, not a wrong password`() {
        val envelope = BackupCrypto.encrypt(plain, "korecek".toCharArray())
        val broken = envelope.substring(0, envelope.length / 2)
        val failure = runCatching { BackupCrypto.decrypt(broken, "korecek".toCharArray()) }
            .exceptionOrNull()
        assertTrue(failure is BackupException)
    }

    @Test
    fun `an out of range iteration count is rejected before any KDF work`() {
        val envelope = BackupCrypto.encrypt(plain, "korecek".toCharArray())
        listOf(Int.MAX_VALUE, 0, -1).forEach { hostile ->
            val tampered = Json.parseToJsonElement(envelope).jsonObject.toString()
                .replace(Regex("\"iterations\":\\d+"), "\"iterations\":$hostile")
            val failure = runCatching { BackupCrypto.decrypt(tampered, "korecek".toCharArray()) }
                .exceptionOrNull()
            assertEquals(
                "iterations=$hostile must be refused as unreadable",
                MessageKey.BACKUP_UNREADABLE,
                (failure as? BackupException)?.key,
            )
        }
    }

    @Test
    fun `tampering with the ciphertext fails authentication`() {
        val envelope = BackupCrypto.encrypt(plain, "korecek".toCharArray())
        val original = Json.parseToJsonElement(envelope).jsonObject["data"]!!.jsonPrimitive.content
        val bytes = android.util.Base64.decode(original, android.util.Base64.DEFAULT)
        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        val changed = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        // Replace in the original text so the rest of the envelope stays byte-for-byte valid.
        val tampered = envelope.replace("\"data\":\"$original\"", "\"data\":\"$changed\"")
        val failure = runCatching { BackupCrypto.decrypt(tampered, "korecek".toCharArray()) }
            .exceptionOrNull()
        assertEquals(MessageKey.BACKUP_WRONG_PASSWORD, (failure as BackupException).key)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty password is refused`() {
        BackupCrypto.encrypt(plain, charArrayOf())
    }
}
