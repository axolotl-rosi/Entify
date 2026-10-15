package com.example.entify_again.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream

object SafeImageDecoder {
    const val MAX_DOWNLOAD_BYTES: Long = 5L * 1024L * 1024L
    private const val MAX_SOURCE_PIXELS: Long = 40_000_000L
    private const val BUFFER_SIZE = 8 * 1024

    fun readBounded(body: ResponseBody, maxBytes: Long = MAX_DOWNLOAD_BYTES): ByteArray? {
        val declaredLength = body.contentLength()
        if (declaredLength > maxBytes) return null
        val initialCapacity = when {
            declaredLength in 1..Int.MAX_VALUE.toLong() -> declaredLength.toInt()
            else -> BUFFER_SIZE
        }
        return body.byteStream().use { input ->
            val output = ByteArrayOutputStream(initialCapacity.coerceAtMost(maxBytes.toInt()))
            val buffer = ByteArray(BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) return null
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    fun decodeRaster(bytes: ByteArray, targetSize: Int): Bitmap? {
        if (bytes.isEmpty() || bytes.size > MAX_DOWNLOAD_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        if (width.toLong() * height.toLong() > MAX_SOURCE_PIXELS) return null

        val safeTarget = targetSize.coerceIn(32, 2_048)
        var sampleSize = 1
        while (width / sampleSize > safeTarget * 2 || height / sampleSize > safeTarget * 2) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    fun isSafeSvg(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > MAX_DOWNLOAD_BYTES) return false
        val head = String(bytes, 0, minOf(bytes.size, 4_096), Charsets.UTF_8)
        if (!head.contains("<svg", ignoreCase = true)) return false
        if (head.contains("<!DOCTYPE", ignoreCase = true)) return false
        if (head.contains("<!ENTITY", ignoreCase = true)) return false
        return true
    }
}
