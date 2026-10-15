package com.example.entify_again.webview

import java.net.URI
import java.util.Locale

object EntryOriginPolicy {
    private const val ROOT_HOST = "playentry.org"

    fun isTrusted(url: String?): Boolean {
        if (url == null) return false
        if (url == "about:blank") return true
        return runCatching {
            val uri = URI(url)
            val host = uri.host?.lowercase(Locale.ROOT) ?: return false
            uri.scheme.equals("https", ignoreCase = true) &&
                (host == ROOT_HOST || host.endsWith(".$ROOT_HOST"))
        }.getOrDefault(false)
    }
}
