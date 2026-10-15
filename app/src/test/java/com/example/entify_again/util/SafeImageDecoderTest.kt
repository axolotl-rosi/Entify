package com.example.entify_again.util

import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeImageDecoderTest {
    @Test
    fun readBounded_acceptsPayloadWithinLimit() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(bytes, SafeImageDecoder.readBounded(bytes.toResponseBody(), 4))
    }

    @Test
    fun readBounded_rejectsDeclaredPayloadOverLimit() {
        val bytes = ByteArray(16) { it.toByte() }
        assertNull(SafeImageDecoder.readBounded(bytes.toResponseBody(), 8))
    }

    @Test
    fun safeSvg_rejectsDoctypeAndEntityDeclarations() {
        assertTrue(SafeImageDecoder.isSafeSvg("<svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray()))
        assertFalse(SafeImageDecoder.isSafeSvg("<!DOCTYPE svg><svg/>".toByteArray()))
        assertFalse(SafeImageDecoder.isSafeSvg("<!ENTITY x 'y'><svg/>".toByteArray()))
    }
}
