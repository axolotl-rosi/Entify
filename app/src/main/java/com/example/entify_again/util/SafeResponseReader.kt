package com.example.entify_again.util

import okhttp3.ResponseBody

/** Bounds text responses before decoding so a compromised endpoint cannot exhaust app memory. */
object SafeResponseReader {
    const val MAX_API_BYTES: Long = 2L * 1024L * 1024L
    const val MAX_HTML_BYTES: Long = 4L * 1024L * 1024L

    fun readText(body: ResponseBody?, maxBytes: Long = MAX_API_BYTES): String? {
        if (body == null || maxBytes <= 0L || maxBytes > Int.MAX_VALUE) return null
        val bytes = SafeImageDecoder.readBounded(body, maxBytes) ?: return null
        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        return bytes.toString(charset)
    }
}
