package com.example.entify_again.webview

import java.net.URI
import java.util.Locale

object EntryOriginPolicy {
    private const val ROOT_HOST = "playentry.org"
    private const val MAX_URL_LENGTH = 2_048

    fun isTrusted(url: String?): Boolean {
        val uri = parseTrusted(url) ?: return false
        val host = uri.host.lowercase(Locale.ROOT)
        return host == ROOT_HOST || host.endsWith(".$ROOT_HOST")
    }

    fun isTrustedMainHost(url: String?): Boolean =
        parseTrusted(url)?.host.equals(ROOT_HOST, ignoreCase = true)

    fun isTrustedHost(url: String?, expectedHost: String): Boolean =
        parseTrusted(url)?.host.equals(expectedHost, ignoreCase = true)

    fun isTrustedPath(url: String?, expectedHost: String, pathPrefix: String): Boolean {
        val uri = parseTrusted(url) ?: return false
        if (!uri.host.equals(expectedHost, ignoreCase = true)) return false
        val normalizedPrefix = when {
            pathPrefix.isBlank() -> "/"
            pathPrefix.startsWith('/') -> pathPrefix
            else -> "/$pathPrefix"
        }.trimEnd('/').ifBlank { "/" }
        val path = uri.path.orEmpty().ifBlank { "/" }
        return path == normalizedPrefix ||
            (normalizedPrefix != "/" && path.startsWith("$normalizedPrefix/"))
    }

    fun isAboutBlank(url: String?): Boolean = url == "about:blank"

    private const val SPACE_HOST = "space.playentry.org"

    fun isSpaceHost(url: String?): Boolean =
        parseTrusted(url)?.host.equals(SPACE_HOST, ignoreCase = true)

    fun isTrustedImageHost(url: String?): Boolean {
        val uri = parseTrustedImageUri(url) ?: return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return host == ROOT_HOST || host.endsWith(".$ROOT_HOST") ||
                host == "pstatic.net" || host.endsWith(".pstatic.net") ||
                host == "naver.com" || host.endsWith(".naver.com")
    }

    fun sanitizeEntryUrl(url: String?, fallback: String = "https://playentry.org/alarm"): String {
        val uri = parseTrusted(url) ?: return fallback
        return uri.toASCIIString()
    }

    private fun parseTrustedImageUri(url: String?): URI? {
        if (url.isNullOrBlank()) return null
        val candidate = url.trim()
        if (candidate.length > MAX_URL_LENGTH) return null
        if (candidate.any { it == '\u0000' || it == '\n' || it == '\r' || it == '\\' }) return null
        return runCatching {
            val uri = URI(candidate)
            if (!uri.isAbsolute || !uri.scheme.equals("https", ignoreCase = true)) return null
            if (!uri.rawUserInfo.isNullOrBlank()) return null
            if (uri.port != -1 && uri.port != 443) return null
            if (hasUnsafePath(uri)) return null
            uri
        }.getOrNull()
    }

    private fun parseTrusted(url: String?): URI? {
        if (url.isNullOrBlank()) return null
        val candidate = url.trim()
        if (candidate.length > MAX_URL_LENGTH) return null
        if (candidate.any { it == '\u0000' || it == '\n' || it == '\r' || it == '\\' }) return null
        return runCatching {
            val uri = URI(candidate)
            if (!uri.isAbsolute || !uri.scheme.equals("https", ignoreCase = true)) return null
            if (!uri.rawUserInfo.isNullOrBlank()) return null
            if (uri.port != -1 && uri.port != 443) return null
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            if (host != ROOT_HOST && !host.endsWith(".$ROOT_HOST")) return null
            if (hasUnsafePath(uri)) return null
            uri
        }.getOrNull()
    }

    private fun hasUnsafePath(uri: URI): Boolean {
        val raw = uri.rawPath.orEmpty().lowercase(Locale.ROOT)
        if (raw.contains("%2e") || raw.contains("%2f") || raw.contains("%5c") || raw.contains("%25")) {
            return true
        }
        return uri.path.orEmpty().split('/').any { it == "." || it == ".." }
    }
}
