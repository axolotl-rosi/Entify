package com.example.entify_again.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Keystore-backed storage for session material.
 *
 * This store deliberately fails closed: if Android Keystore is unavailable,
 * secrets are neither read from nor written to plaintext preferences. The user
 * may need to sign in again, but credentials are never silently downgraded.
 */
class SecureSessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val encrypted: SharedPreferences? = runCatching {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrNull()

    val isAvailable: Boolean get() = encrypted != null

    fun get(key: String): String? = encrypted?.getString(key, null)

    fun put(key: String, value: String?) {
        encrypted?.edit()?.apply {
            if (value.isNullOrBlank()) remove(key) else putString(key, value)
        }?.commit()
    }

    fun clear() {
        encrypted?.edit()?.clear()?.commit()
    }

    companion object {
        private const val FILE_NAME = "entify_secure_session"
    }
}
