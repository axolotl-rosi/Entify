package com.example.entify_again.util

/**
 * No-op logger for flows that handle account, notification, or profile data.
 * Keeping these calls silent also protects locally distributed debug builds.
 */
object PrivacyLog {
    fun v(tag: String, message: String): Int = 0
    fun v(tag: String, message: String, error: Throwable): Int = 0
    fun d(tag: String, message: String): Int = 0
    fun d(tag: String, message: String, error: Throwable): Int = 0
    fun i(tag: String, message: String): Int = 0
    fun i(tag: String, message: String, error: Throwable): Int = 0
    fun w(tag: String, message: String): Int = 0
    fun w(tag: String, message: String, error: Throwable): Int = 0
    fun e(tag: String, message: String): Int = 0
    fun e(tag: String, message: String, error: Throwable): Int = 0
}
