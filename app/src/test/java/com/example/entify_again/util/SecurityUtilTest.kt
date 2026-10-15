package com.example.entify_again.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityUtilTest {

    @Test
    fun isValidEntryUrl_acceptsValidEntryUrls() {
        assertTrue(SecurityUtil.isValidEntryUrl("https://playentry.org/project/123"))
        assertTrue(SecurityUtil.isValidEntryUrl("https://space.playentry.org/world/abc"))
        assertTrue(SecurityUtil.isValidEntryUrl("https://playentry.org/profile/abc?tab=project"))
    }

    @Test
    fun isValidEntryUrl_rejectsInvalidOrMaliciousUrls() {
        assertFalse(SecurityUtil.isValidEntryUrl(null))
        assertFalse(SecurityUtil.isValidEntryUrl(""))
        assertFalse(SecurityUtil.isValidEntryUrl("http://playentry.org/login"))
        assertFalse(SecurityUtil.isValidEntryUrl("https://evil.com/?playentry.org"))
        assertFalse(SecurityUtil.isValidEntryUrl("https://playentry.org.evil.com/"))
        assertFalse(SecurityUtil.isValidEntryUrl("javascript:alert(1)"))
        assertFalse(SecurityUtil.isValidEntryUrl("https://playentry.org/../evil"))
        assertFalse(SecurityUtil.isValidEntryUrl("https://user:pass@playentry.org/"))
    }

    @Test
    fun isValidImageUrl_acceptsEntryAndCdnImages() {
        assertTrue(SecurityUtil.isValidImageUrl("https://playentry.org/uploads/img.png"))
        assertTrue(SecurityUtil.isValidImageUrl("https://entry-uploads.playentry.org/thumb/1.jpg"))
        assertTrue(SecurityUtil.isValidImageUrl("https://ssl.pstatic.net/entry/img.png"))
    }

    @Test
    fun isValidImageUrl_rejectsUnsafeImageUrls() {
        assertFalse(SecurityUtil.isValidImageUrl(null))
        assertFalse(SecurityUtil.isValidImageUrl("http://ssl.pstatic.net/img.png"))
        assertFalse(SecurityUtil.isValidImageUrl("https://evil.com/avatar.png"))
    }
}
