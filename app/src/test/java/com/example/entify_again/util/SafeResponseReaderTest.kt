package com.example.entify_again.util

import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafeResponseReaderTest {
    @Test
    fun readText_acceptsResponseWithinLimit() {
        assertEquals("safe", SafeResponseReader.readText("safe".toResponseBody(), 4))
    }

    @Test
    fun readText_rejectsResponseOverLimit() {
        assertNull(SafeResponseReader.readText("oversized".toResponseBody(), 4))
    }
}
