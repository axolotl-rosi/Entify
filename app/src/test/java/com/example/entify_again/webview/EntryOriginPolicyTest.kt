package com.example.entify_again.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryOriginPolicyTest {
    @Test
    fun trustedOrigin_acceptsHttpsEntryHosts() {
        assertTrue(EntryOriginPolicy.isTrusted("https://playentry.org/login"))
        assertTrue(EntryOriginPolicy.isTrusted("https://api.playentry.org/path"))
    }

    @Test
    fun trustedOrigin_rejectsLookalikesAndUnsafeSchemes() {
        assertFalse(EntryOriginPolicy.isTrusted("http://playentry.org/login"))
        assertFalse(EntryOriginPolicy.isTrusted("https://playentry.org.example.com/login"))
        assertFalse(EntryOriginPolicy.isTrusted("https://evilplayentry.org/login"))
        assertFalse(EntryOriginPolicy.isTrusted("https://user@playentry.org/login"))
        assertFalse(EntryOriginPolicy.isTrusted("https://playentry.org:444/login"))
        assertFalse(EntryOriginPolicy.isTrusted("https://playentry.org\\@evil.example/login"))
        assertFalse(EntryOriginPolicy.isTrusted("https://playentry.org/%2e%2e/private"))
        assertFalse(EntryOriginPolicy.isTrusted("https://playentry.org/%252e%252e/private"))
        assertFalse(EntryOriginPolicy.isTrusted("https://playentry.org/safe%2f..%2fprivate"))
        assertFalse(EntryOriginPolicy.isTrusted("javascript:alert(1)"))
        assertFalse(EntryOriginPolicy.isTrusted("about:blank"))
        assertFalse(EntryOriginPolicy.isTrusted(null))
    }

    @Test
    fun trustedPath_requiresExactHostAndPathBoundary() {
        assertTrue(EntryOriginPolicy.isTrustedPath("https://playentry.org/alarm", "playentry.org", "/alarm"))
        assertTrue(EntryOriginPolicy.isTrustedPath("https://playentry.org/profile/abc", "playentry.org", "/profile"))
        assertFalse(EntryOriginPolicy.isTrustedPath("https://space.playentry.org/alarm", "playentry.org", "/alarm"))
        assertFalse(EntryOriginPolicy.isTrustedPath("https://playentry.org/alarming", "playentry.org", "/alarm"))
    }

    @Test
    fun spaceHost_acceptsOnlySpaceSubdomain() {
        assertTrue(EntryOriginPolicy.isSpaceHost("https://space.playentry.org/world/123"))
        assertFalse(EntryOriginPolicy.isSpaceHost("https://playentry.org/world/123"))
        assertFalse(EntryOriginPolicy.isSpaceHost("https://evil.example.com/?q=space.playentry.org"))
        assertFalse(EntryOriginPolicy.isSpaceHost("https://space.playentry.org.evil.example/world/123"))
    }

    @Test
    fun trustedImageHost_acceptsEntryAndNaverCdnHosts() {
        assertTrue(EntryOriginPolicy.isTrustedImageHost("https://playentry.org/uploads/image.png"))
        assertTrue(EntryOriginPolicy.isTrustedImageHost("https://entry-uploads.playentry.org/thumb/abc.jpg"))
        assertTrue(EntryOriginPolicy.isTrustedImageHost("https://ssl.pstatic.net/static/entry/img.png"))
        assertTrue(EntryOriginPolicy.isTrustedImageHost("https://mybox.naver.com/img.jpg"))

        assertFalse(EntryOriginPolicy.isTrustedImageHost("https://evil.com/img.png"))
        assertFalse(EntryOriginPolicy.isTrustedImageHost("https://pstatic.net.evil.com/img.png"))
        assertFalse(EntryOriginPolicy.isTrustedImageHost("http://ssl.pstatic.net/static/img.png"))
    }

    @Test
    fun sanitizeEntryUrl_normalizesOrFallsBack() {
        org.junit.Assert.assertEquals(
            "https://playentry.org/profile/123",
            EntryOriginPolicy.sanitizeEntryUrl("https://playentry.org/profile/123")
        )
        org.junit.Assert.assertEquals(
            "https://playentry.org/alarm",
            EntryOriginPolicy.sanitizeEntryUrl("https://evil.com/fake")
        )
        org.junit.Assert.assertEquals(
            "https://playentry.org/alarm",
            EntryOriginPolicy.sanitizeEntryUrl("javascript:alert(1)")
        )
    }
}
