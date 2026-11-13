package cz.medsy.data.backup

import cz.medsy.domain.message.MessageKey
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
}
