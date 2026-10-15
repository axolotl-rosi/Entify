package com.example.entify_again.util

import android.content.Context
import android.util.Base64
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-backed AES-256-GCM cipher for encrypting sensitive database fields
 * (e.g. notification title and body).
 *
 * Ensures that offline database dumps or backups do not expose notification contents.
 * Designed with a version header ("enc:v1:") for seamless backward compatibility
 * with existing plaintext rows.
 */
object DatabaseFieldCipher {
    private const val PREFIX = "enc:v1:"
    private const val GCM_TAG_LENGTH = 128
    private const val IV_LENGTH = 12
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "entify_db_field_key"

    @Volatile
    private var isInitialized = false
    private var cachedKey: SecretKey? = null

    fun init(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            val key = runCatching {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                if (!keyStore.containsAlias(KEY_ALIAS)) {
                    MasterKey.Builder(context.applicationContext, KEY_ALIAS)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build()
                }
                keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            }.getOrElse { error ->
                throw IllegalStateException("Notification database encryption is unavailable", error)
            } ?: throw IllegalStateException("Notification database encryption key is unavailable")
            cachedKey = key
            isInitialized = true
        }
    }

    fun encrypt(plainText: String?): String? {
        if (plainText.isNullOrBlank()) return plainText
        if (plainText.startsWith(PREFIX)) return plainText
        val key = requireKey()

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

            val combined = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)

            PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (error: Exception) {
            throw IllegalStateException("Notification field encryption failed", error)
        }
    }

    fun decrypt(cipherOrPlain: String?): String? {
        if (cipherOrPlain.isNullOrBlank()) return cipherOrPlain
        if (!cipherOrPlain.startsWith(PREFIX)) {
            // Unencrypted legacy row: return as-is
            return cipherOrPlain
        }

        val key = requireKey()
        return try {
            val base64Data = cipherOrPlain.removePrefix(PREFIX)
            val combined = Base64.decode(base64Data, Base64.NO_WRAP)
            if (combined.size <= IV_LENGTH + 16) {
                throw IllegalArgumentException("Encrypted notification field is truncated")
            }

            val iv = combined.copyOfRange(0, IV_LENGTH)
            val cipherText = combined.copyOfRange(IV_LENGTH, combined.size)

            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            val plainBytes = cipher.doFinal(cipherText)
            String(plainBytes, Charsets.UTF_8)
        } catch (error: Exception) {
            throw IllegalStateException("Notification field decryption failed", error)
        }
    }

    fun isEncrypted(value: String?): Boolean = value?.startsWith(PREFIX) == true

    private fun requireKey(): SecretKey = cachedKey
        ?: throw IllegalStateException("DatabaseFieldCipher.init(context) must succeed before use")
}
