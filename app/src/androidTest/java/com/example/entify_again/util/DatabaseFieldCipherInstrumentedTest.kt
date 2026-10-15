package com.example.entify_again.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseFieldCipherInstrumentedTest {
    @Test
    fun encrypt_roundTripsWithAndroidKeystore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        DatabaseFieldCipher.init(context)

        val plain = "개인 알림 본문"
        val encrypted = DatabaseFieldCipher.encrypt(plain).orEmpty()

        assertNotEquals(plain, encrypted)
        assertTrue(DatabaseFieldCipher.isEncrypted(encrypted))
        assertEquals(plain, DatabaseFieldCipher.decrypt(encrypted))
    }

    @Test
    fun decrypt_rejectsTamperedCiphertext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        DatabaseFieldCipher.init(context)
        val encrypted = DatabaseFieldCipher.encrypt("변조 감지").orEmpty()
        val replacement = if (encrypted.last() == 'A') 'B' else 'A'
        val tampered = encrypted.dropLast(1) + replacement

        var rejected = false
        try {
            DatabaseFieldCipher.decrypt(tampered)
        } catch (_: IllegalStateException) {
            rejected = true
        }
        assertTrue(rejected)
    }
}
