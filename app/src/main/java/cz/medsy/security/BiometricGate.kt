package cz.medsy.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Wraps [BiometricPrompt]. Class 3 biometrics (BIOMETRIC_STRONG) are required so the
 * lock actually gates access; device-credential fallback is allowed because a user
 * who has not enrolled a fingerprint should still be able to open their own app.
 */
class BiometricGate(
    private val activity: FragmentActivity,
    private val onResult: (success: Boolean) -> Unit,
) {

    fun canAuthenticate(): Boolean {
        val manager = BiometricManager.from(activity)
        return manager.canAuthenticate(ALLOWED) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun authenticate(title: String, subtitle: String) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onResult(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onResult(false)
                }

                override fun onAuthenticationFailed() {
                    // A single bad scan is not a lockout; the system retries.
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(ALLOWED)
            .build()
        prompt.authenticate(info)
    }

    private companion object {
        const val ALLOWED =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
}

internal fun hasEnrolledBiometric(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL,
    ) == BiometricManager.BIOMETRIC_SUCCESS
