package cz.medsy.data.update

import android.content.pm.PackageInfo
import android.content.pm.Signature
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The self-update gate: a download is only handed to the installer when it is the same
 * package and carries the same signing certificate. Without this, one tap on a poisoned
 * release asset could install a different app.
 */
@RunWith(RobolectricTestRunner::class)
class ApkSignatureTest {

    @Suppress("DEPRECATION")
    private fun info(packageName: String, certificate: ByteArray?): PackageInfo = PackageInfo().apply {
        this.packageName = packageName
        if (certificate != null) signatures = arrayOf(Signature(certificate))
    }

    @Test
    fun `the same package and certificate is accepted`() {
        val cert = byteArrayOf(1, 2, 3, 4, 5)
        assertTrue(signaturesMatch(info("cz.medsy", cert), info("cz.medsy", cert.copyOf())))
    }

    @Test
    fun `a different certificate is rejected`() {
        assertFalse(
            signaturesMatch(
                info("cz.medsy", byteArrayOf(1, 2, 3)),
                info("cz.medsy", byteArrayOf(9, 8, 7)),
            ),
        )
    }

    @Test
    fun `a foreign package name is rejected even with the same certificate`() {
        val cert = byteArrayOf(1, 2, 3)
        assertFalse(signaturesMatch(info("cz.medsy", cert), info("cz.medsy.evil", cert)))
    }

    @Test
    fun `missing package info or signature is rejected`() {
        val valid = info("cz.medsy", byteArrayOf(1, 2, 3))
        assertFalse(signaturesMatch(null, valid))
        assertFalse(signaturesMatch(valid, null))
        assertFalse(signaturesMatch(info("cz.medsy", null), valid))
        assertFalse(signaturesMatch(valid, info("cz.medsy", null)))
    }
}
