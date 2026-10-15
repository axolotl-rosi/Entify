package com.example.entify_again.util

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Bounds and sanitizes raw notification payloads before storing them in local archive.
 * Prevents memory exhaustion, database bloat, and accidental storage of sensitive tokens.
 */
object PayloadSanitizer {
    const val MAX_PAYLOAD_CHARS = 128 * 1024
    private const val REDACTED = "[REDACTED]"

    private val SENSITIVE_KEYS = setOf(
        "token", "xtoken", "x-token", "csrf", "csrftoken", "cookie", "cookies",
        "password", "secret", "authorization", "auth"
    )

    fun sanitize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        if (raw.length > MAX_PAYLOAD_CHARS) {
            return "{\"truncated\":true,\"originalLength\":${raw.length}}"
        }

        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: return "{\"rejected\":true,\"reason\":\"invalid_json\"}"
        if (!containsSensitiveKey(json)) return raw
        return sanitizeJsonObject(json).toString()
    }

    private fun sanitizeJsonObject(json: JSONObject): JSONObject {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (isSensitiveKey(key)) {
                json.put(key, REDACTED)
            } else {
                when (val value = json.opt(key)) {
                    is JSONObject -> sanitizeJsonObject(value)
                    is JSONArray -> sanitizeJsonArray(value)
                }
            }
        }
        return json
    }

    private fun sanitizeJsonArray(array: JSONArray) {
        for (index in 0 until array.length()) {
            when (val value = array.opt(index)) {
                is JSONObject -> sanitizeJsonObject(value)
                is JSONArray -> sanitizeJsonArray(value)
            }
        }
    }

    private fun containsSensitiveKey(json: JSONObject): Boolean {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (isSensitiveKey(key)) return true
            when (val value = json.opt(key)) {
                is JSONObject -> if (containsSensitiveKey(value)) return true
                is JSONArray -> if (containsSensitiveKey(value)) return true
            }
        }
        return false
    }

    private fun containsSensitiveKey(array: JSONArray): Boolean {
        for (index in 0 until array.length()) {
            when (val value = array.opt(index)) {
                is JSONObject -> if (containsSensitiveKey(value)) return true
                is JSONArray -> if (containsSensitiveKey(value)) return true
            }
        }
        return false
    }

    private fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase(Locale.ROOT).replace("-", "").replace("_", "")
        return SENSITIVE_KEYS.any {
            normalized == it.lowercase(Locale.ROOT).replace("-", "").replace("_", "")
        }
    }
}
