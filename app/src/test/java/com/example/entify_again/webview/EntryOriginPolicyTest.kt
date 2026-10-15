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
        assertFalse(EntryOriginPolicy.isTrusted("javascript:alert(1)"))
        assertFalse(EntryOriginPolicy.isTrusted(null))
    }
}
