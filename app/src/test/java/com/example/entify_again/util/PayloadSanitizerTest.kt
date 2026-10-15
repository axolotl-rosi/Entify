package com.example.entify_again.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadSanitizerTest {

    @Test
    fun sanitize_nullOrBlank_returnsNull() {
        assertNull(PayloadSanitizer.sanitize(null))
        assertNull(PayloadSanitizer.sanitize(""))
        assertNull(PayloadSanitizer.sanitize("   "))
    }

    @Test
    fun sanitize_stripsSensitiveKeys() {
        val raw = """{"title":"알림","csrf":"secret_csrf","x-token":"secret_x","body":"내용"}"""
        val sanitized = PayloadSanitizer.sanitize(raw)
        assertNotNull(sanitized)
        assertTrue(sanitized!!.contains("알림"))
        assertTrue(sanitized.contains("내용"))
        assertFalse(sanitized.contains("secret_csrf"))
        assertFalse(sanitized.contains("secret_x"))
        assertTrue(sanitized.contains("[REDACTED]"))
    }

    @Test
    fun sanitize_preservesSafePayloadExactly() {
        val raw = """{ "id": "topic-1", "message": "원본 공백도 유지" }"""
        assertTrue(PayloadSanitizer.sanitize(raw) == raw)
    }

    @Test
    fun sanitize_redactsNestedSensitiveKeys() {
        val raw = """{"data":{"items":[{"authorization":"Bearer secret","id":"1"}]}}"""
        val sanitized = PayloadSanitizer.sanitize(raw).orEmpty()
        assertFalse(sanitized.contains("Bearer secret"))
        assertTrue(sanitized.contains("[REDACTED]"))
    }

    @Test
    fun sanitize_boundsOverSizedPayload() {
        val hugeString = "a".repeat(10_000)
        val raw = """{"data":"$hugeString"}"""
        val sanitized = PayloadSanitizer.sanitize(raw)
        assertNotNull(sanitized)
        assertTrue(sanitized!!.length <= PayloadSanitizer.MAX_PAYLOAD_CHARS)
    }
}
