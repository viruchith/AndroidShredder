package com.viruchith.shredder.security

import androidx.biometric.BiometricPrompt
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecurityGatingInstrumentationTest {

    @Test
    fun verifyCryptoObject_nullCryptoObject_doesNotPassGate() {
        val gating = SecurityGating()
        var successCalled = false
        var errorCalled = false

        gating.verifyCryptoObject(
            cryptoObject = null,
            onSuccess = { successCalled = true },
            onError = { code, _ ->
                errorCalled = true
                assertTrue(code == BiometricPrompt.ERROR_UNABLE_TO_PROCESS)
            }
        )

        assertFalse(successCalled)
        assertTrue(errorCalled)
    }
}

