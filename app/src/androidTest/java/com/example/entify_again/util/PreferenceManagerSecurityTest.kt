package com.example.entify_again.util

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreferenceManagerSecurityTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        clearStores()
    }

    @After
    fun tearDown() = clearStores()

    @Test
    fun legacySecrets_areMigratedAndPlaintextIsRemoved() {
        val plaintext = context.getSharedPreferences("entify_prefs", Context.MODE_PRIVATE)
        plaintext.edit()
            .putString("csrf_token", "csrf-test")
            .putString("x_token", "x-test")
            .putString("cookies", "session=test")
            .commit()

        val preferences = PreferenceManager(context)

        assertEquals("csrf-test", preferences.csrfToken)
        assertEquals("x-test", preferences.xToken)
        assertEquals("session=test", preferences.cookies)
        assertFalse(plaintext.contains("csrf_token"))
        assertFalse(plaintext.contains("x_token"))
        assertFalse(plaintext.contains("cookies"))
    }

    @Test
    fun clearAll_removesSessionAndAccountState() {
        val preferences = PreferenceManager(context)
        preferences.csrfToken = "csrf-test"
        preferences.xToken = "x-test"
        preferences.cookies = "session=test"
        preferences.username = "user-test"
        preferences.isLoggedIn = true

        preferences.clearAll()

        assertNull(preferences.csrfToken)
        assertNull(preferences.xToken)
        assertNull(preferences.cookies)
        assertNull(preferences.username)
        assertFalse(preferences.isLoggedIn)
    }

    private fun clearStores() {
        context.getSharedPreferences("entify_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("entify_secure_session", Context.MODE_PRIVATE).edit().clear().commit()
    }
}
