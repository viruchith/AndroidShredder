package com.viruchith.shredder.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * SecurityGating encapsulates device security and biometric checks,
 * segregating security checking and prompt orchestration from UI concerns.
 */
class SecurityGating(private val biometricManagerProvider: (Context) -> BiometricManager = { BiometricManager.from(it) }) {

    companion object {
        const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }

    /**
     * Determines if the device currently has active screen locks or biometric credentials configured.
     */
    fun isDeviceSecure(context: Context): Boolean {
        val biometricManager = biometricManagerProvider(context)
        val status = biometricManager.canAuthenticate(AUTHENTICATORS)
        return status == BiometricManager.BIOMETRIC_SUCCESS || status == BiometricManager.BIOMETRIC_STATUS_UNKNOWN
    }

    /**
     * Triggers the Android BiometricPrompt flow for user authentication.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (errorCode: Int, errString: String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                onError(errorCode, errString.toString())
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }
}
