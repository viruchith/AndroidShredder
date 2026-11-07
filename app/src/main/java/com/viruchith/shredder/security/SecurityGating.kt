package com.viruchith.shredder.security

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * SecurityGating encapsulates device security and biometric checks,
 * segregating security checking and prompt orchestration from UI concerns.
 */
class SecurityGating(
    private val biometricManagerProvider: (Context) -> BiometricManager = {
        BiometricManager.from(
            it
        )
    },
    private val keyguardManagerProvider: (Context) -> KeyguardManager? = {
        it.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    }
) {

    companion object {
        const val AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "shredder_auth_gate_key"
        private const val KEY_TRANSFORMATION = "AES/GCM/NoPadding"
        private val AUTH_CHALLENGE = "SHREDDER_AUTH_CHALLENGE".toByteArray(Charsets.UTF_8)
    }

    /**
     * Determines if the device currently has active screen locks or biometric credentials configured.
     */
    fun isDeviceSecure(context: Context): Boolean {
        val keyguardManager = keyguardManagerProvider(context)
        if (keyguardManager?.isDeviceSecure == true) {
            return true
        }
        val biometricManager = biometricManagerProvider(context)
        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AUTHENTICATORS
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        }
        val status = biometricManager.canAuthenticate(authenticators)
        return status == BiometricManager.BIOMETRIC_SUCCESS || status == BiometricManager.BIOMETRIC_STATUS_UNKNOWN
    }

    /**
     * Triggers the Android BiometricPrompt flow for user authentication.
     */
    @SuppressLint("BiometricPromptWithoutCrypto")
    @Suppress("BiometricPromptWithoutCrypto")
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (errorCode: Int, errString: String) -> Unit
    ) {
        val biometricManager = biometricManagerProvider(activity)
        val canUseCrypto = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

        val cipher = if (canUseCrypto) {
            try {
                initCipherForAuthentication()
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt =
            BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    if (cipher != null) {
                        verifyCryptoObject(result.cryptoObject, onSuccess, onError)
                    } else {
                        onSuccess()
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onError(errorCode, errString.toString())
                }
            })

        val promptInfoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            promptInfoBuilder.setAllowedAuthenticators(AUTHENTICATORS)
        } else {
            promptInfoBuilder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        }

        val promptInfo = promptInfoBuilder.build()

        if (cipher != null) {
            biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
        } else {
            biometricPrompt.authenticate(promptInfo)
        }
    }

    internal fun verifyCryptoObject(
        cryptoObject: BiometricPrompt.CryptoObject?,
        onSuccess: () -> Unit,
        onError: (errorCode: Int, errString: String) -> Unit
    ) {
        val authenticatedCipher = cryptoObject?.cipher
        if (authenticatedCipher == null) {
            onError(
                BiometricPrompt.ERROR_UNABLE_TO_PROCESS,
                "Authentication was not cryptographically verified."
            )
            return
        }

        try {
            authenticatedCipher.doFinal(AUTH_CHALLENGE)
            onSuccess()
        } catch (_: Exception) {
            onError(
                BiometricPrompt.ERROR_UNABLE_TO_PROCESS,
                "Secure authentication validation failed."
            )
        }
    }

    private fun initCipherForAuthentication(): Cipher {
        return try {
            val key = getOrCreateSecretKey()
            Cipher.getInstance(KEY_TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, key)
            }
        } catch (_: KeyPermanentlyInvalidatedException) {
            deleteSecretKey()
            val regeneratedKey = getOrCreateSecretKey()
            Cipher.getInstance(KEY_TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, regeneratedKey)
            }
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val purposes = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        val builder = KeyGenParameterSpec.Builder(KEY_ALIAS, purposes)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }

        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    private fun deleteSecretKey() {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
    }
}
