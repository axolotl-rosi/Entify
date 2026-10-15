package com.example.entify_again.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DatabaseFieldCipherTest {

    @Test
    fun nullOrBlank_returnsAsIs() {
        assertNull(DatabaseFieldCipher.encrypt(null))
        assertNull(DatabaseFieldCipher.decrypt(null))
        assertEquals("", DatabaseFieldCipher.encrypt(""))
        assertEquals("", DatabaseFieldCipher.decrypt(""))
    }

    @Test
    fun legacyPlainText_decryptsToSameString() {
        val legacy = "이전 버전에서 평문으로 저장된 알림 본문입니다."
        // If it doesn't start with enc:v1:, decrypt() must pass it through unchanged
        val result = DatabaseFieldCipher.decrypt(legacy)
        assertEquals(legacy, result)
    }
}
