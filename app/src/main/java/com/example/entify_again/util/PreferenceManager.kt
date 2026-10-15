package com.example.entify_again.util

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PreferenceManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val secureSession = SecureSessionStore(appContext)
    
    companion object {
        private const val PREF_NAME = "entify_prefs"
        
        // Keys
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_USERNAME = "username"
        private const val KEY_CSRF_TOKEN = "csrf_token"
        private const val KEY_X_TOKEN = "x_token"
        private const val KEY_AUTO_REPLY_ENABLED = "auto_reply_enabled"
        private const val KEY_REPLY_MESSAGE = "reply_message"
        private const val KEY_COOKIES = "cookies"
        private const val KEY_PROFILE_IMAGE_URL = "profile_image_url"
        private const val KEY_COMMENT_USER_ID = "comment_user_id"
        private const val KEY_LOGOUT_PENDING = "logout_pending"
        const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_POLLING_INTERVAL_MS = "polling_interval_ms"
        private const val KEY_CATEGORY_IMPORTANCE_PREFIX = "category_importance_"
        private const val KEY_USER_FILTERS_JSON = "user_filters_json"
        private const val KEY_HIGHLIGHT_RULES_JSON = "highlight_rules_json"
        private const val KEY_REMEMBER_NOTIFICATIONS = "remember_notifications"
        private const val KEY_NOTIFICATION_MEMORY_GENERATION = "notification_memory_generation"
        private const val KEY_PERMANENT_ARCHIVE_ENABLED = "permanent_archive_enabled"
        private const val KEY_PUSH_NOTIFICATION_ENABLED = "push_notification_enabled"
        private const val KEY_MONITORING_ENABLED = "monitoring_enabled"
        private const val KEY_DND_ENABLED = "dnd_enabled"
        private const val KEY_DND_START_HOUR = "dnd_start_hour"
        private const val KEY_DND_START_MINUTE = "dnd_start_minute"
        private const val KEY_DND_END_HOUR = "dnd_end_hour"
        private const val KEY_DND_END_MINUTE = "dnd_end_minute"
        private const val KEY_HAS_PROMPTED_BATTERY_OPTIMIZATION = "has_prompted_battery_optimization"

        // Reactive theme mode flow
        private val _themeModeFlow = MutableStateFlow("SYSTEM")
        val themeModeFlow: StateFlow<String> = _themeModeFlow.asStateFlow()

        // Default values
        const val DEFAULT_REPLY_MESSAGE = "댓글 감사합니다!"
        const val DEFAULT_POLLING_INTERVAL_MS = 10_000L
        private const val SEEN_PREFS_NAME = "seen_notifs"
        private const val DISMISSED_PREFS_NAME = "dismissed_notifs"
        private const val FG_POLLING_STATE_PREFS_NAME = "fg_polling_state"
        const val MAX_PROFILE_REFRESH_PER_DAY = 5
        private const val KEY_PROFILE_REFRESH_COUNT = "profile_refresh_count"
        private const val KEY_PROFILE_REFRESH_DATE = "profile_refresh_date"
    }

    val themeModeFlow: StateFlow<String> get() = Companion.themeModeFlow
    
    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_THEME_MODE) {
            _themeModeFlow.value = themeMode
        }
    }

    init {
        migrateLegacySessionSecrets()
        _themeModeFlow.value = themeMode
        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
    }

    private fun migrateLegacySessionSecrets() {
        val legacy = listOf(KEY_CSRF_TOKEN, KEY_X_TOKEN, KEY_COOKIES)
        if (secureSession.isAvailable) {
            legacy.forEach { key ->
                if (secureSession.get(key).isNullOrBlank()) {
                    secureSession.put(key, prefs.getString(key, null))
                }
            }
        }
        // Never retain a plaintext copy, even if Keystore initialization failed.
        prefs.edit().apply { legacy.forEach(::remove) }.commit()
    }

    private fun getTodayDateString(): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
    }

    fun getRemainingProfileRefreshCount(): Int {
        val today = getTodayDateString()
        val savedDate = prefs.getString(KEY_PROFILE_REFRESH_DATE, "")
        if (savedDate != today) {
            return MAX_PROFILE_REFRESH_PER_DAY
        }
        val count = prefs.getInt(KEY_PROFILE_REFRESH_COUNT, 0)
        return (MAX_PROFILE_REFRESH_PER_DAY - count).coerceAtLeast(0)
    }

    fun consumeProfileRefresh(): Pair<Boolean, Int> {
        val today = getTodayDateString()
        val savedDate = prefs.getString(KEY_PROFILE_REFRESH_DATE, "")
        val currentCount = if (savedDate == today) prefs.getInt(KEY_PROFILE_REFRESH_COUNT, 0) else 0
        if (currentCount >= MAX_PROFILE_REFRESH_PER_DAY) {
            return Pair(false, 0)
        }
        val newCount = currentCount + 1
        prefs.edit()
            .putString(KEY_PROFILE_REFRESH_DATE, today)
            .putInt(KEY_PROFILE_REFRESH_COUNT, newCount)
            .apply()
        return Pair(true, MAX_PROFILE_REFRESH_PER_DAY - newCount)
    }
    
    // 로그인 상태
    var isLoggedIn: Boolean
        get() = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_LOGGED_IN, value).apply()
    
    // 사용자 이름
    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    // 사용자 프로필 이미지 URL
    var profileImageUrl: String?
        get() = prefs.getString(KEY_PROFILE_IMAGE_URL, null)
        set(value) = prefs.edit().putString(KEY_PROFILE_IMAGE_URL, value).apply()
    
    // CSRF 토큰
    var csrfToken: String?
        get() = secureSession.get(KEY_CSRF_TOKEN)?.trim('"', '\'', ' ')
        set(value) {
            val sanitized = value?.trim('"', '\'', ' ')
            secureSession.put(KEY_CSRF_TOKEN, sanitized)
        }
    
    // X-Token (새로운 인증 토큰)
    var xToken: String?
        get() = secureSession.get(KEY_X_TOKEN)?.trim('"', '\'', ' ')
        set(value) {
            val sanitized = value?.trim('"', '\'', ' ')
            secureSession.put(KEY_X_TOKEN, sanitized)
        }

    // 세션 쿠키
    var cookies: String?
        get() = secureSession.get(KEY_COOKIES)
        set(value) = secureSession.put(KEY_COOKIES, value)

    fun clearSessionSecrets() {
        secureSession.clear()
        prefs.edit()
            .remove(KEY_CSRF_TOKEN)
            .remove(KEY_X_TOKEN)
            .remove(KEY_COOKIES)
            .apply()
    }

    // 로그아웃 대기 상태
    var logoutPending: Boolean
        get() = prefs.getBoolean(KEY_LOGOUT_PENDING, false)
        set(value) = prefs.edit().putBoolean(KEY_LOGOUT_PENDING, value).apply()

    // 자동 답글 활성화 여부
    var autoReplyEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_REPLY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_REPLY_ENABLED, value).apply()
    
    // 답글 메시지
    var replyMessage: String
        get() = prefs.getString(KEY_REPLY_MESSAGE, DEFAULT_REPLY_MESSAGE) ?: DEFAULT_REPLY_MESSAGE
        set(value) = prefs.edit().putString(KEY_REPLY_MESSAGE, value).apply()

    // 댓글 UID (작성자 UID 기반 매칭용)
    var commentUserId: String?
        get() = prefs.getString(KEY_COMMENT_USER_ID, null)
        set(value) = prefs.edit().putString(KEY_COMMENT_USER_ID, value).apply()
    
    // 테마 모드 (LIGHT, DARK, SYSTEM)
    var themeMode: String
        get() = prefs.getString(KEY_THEME_MODE, "SYSTEM") ?: "SYSTEM"
        set(value) {
            prefs.edit().putString(KEY_THEME_MODE, value).apply()
            _themeModeFlow.value = value
        }

    // 폴링 간격 (ms)
    var pollingIntervalMs: Long
        get() = prefs.getLong(KEY_POLLING_INTERVAL_MS, DEFAULT_POLLING_INTERVAL_MS)
        set(value) = prefs.edit().putLong(KEY_POLLING_INTERVAL_MS, value).apply()

    // 알림 영구 보관 설정 (절대 지워지지 않는 알림 기록 로그)
    var permanentArchiveEnabled: Boolean
        get() = true
        set(@Suppress("UNUSED_PARAMETER") value) {
            // The archive is baseline behavior, not a user-facing opt-in toggle.
            prefs.edit().remove(KEY_PERMANENT_ARCHIVE_ENABLED).apply()
        }

    // 카테고리별 중요도 (sound, vibrate, silent, off)
    fun getCategoryImportance(category: String): String =
        prefs.getString("${KEY_CATEGORY_IMPORTANCE_PREFIX}$category", "sound") ?: "sound"

    fun setCategoryImportance(category: String, importance: String) =
        prefs.edit().putString("${KEY_CATEGORY_IMPORTANCE_PREFIX}$category", importance).apply()

    data class BlockedUserItem(
        val hexId: String,
        val nickname: String,
        val blockedAt: Long = System.currentTimeMillis(),
        val blockedCategories: Set<String> = emptySet(),
        val isBlockAll: Boolean = true
    )

    // 유저 필터 JSON
    var userFiltersJson: String
        get() = prefs.getString(KEY_USER_FILTERS_JSON, "[]") ?: "[]"
        set(value) = prefs.edit().putString(KEY_USER_FILTERS_JSON, value).apply()

    fun getBlockedUsers(): List<BlockedUserItem> {
        val json = userFiltersJson
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            val list = mutableListOf<BlockedUserItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val catArr = obj.optJSONArray("categories")
                val categories = mutableSetOf<String>()
                if (catArr != null) {
                    for (c in 0 until catArr.length()) {
                        val cat = catArr.optString(c).trim().uppercase(java.util.Locale.ROOT)
                        if (cat.isNotBlank()) categories.add(cat)
                    }
                }
                list.add(
                    BlockedUserItem(
                        hexId = obj.optString("hexId", obj.optString("id", "")),
                        nickname = obj.optString("nickname", obj.optString("name", "")),
                        blockedAt = obj.optLong("blockedAt", System.currentTimeMillis()),
                        blockedCategories = categories,
                        isBlockAll = obj.optBoolean("isBlockAll", categories.isEmpty())
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun blockUser(hexId: String, nickname: String, isBlockAll: Boolean = true, blockedCategories: Set<String> = emptySet()) {
        val current = getBlockedUsers().toMutableList()
        val cleanHex = hexId.trim()
        val cleanNick = nickname.trim()
        if (cleanHex.isBlank() && cleanNick.isBlank()) return
        val existingIndex = current.indexOfFirst {
            (cleanHex.isNotBlank() && it.hexId.equals(cleanHex, ignoreCase = true)) ||
            (cleanNick.isNotBlank() && it.nickname.equals(cleanNick, ignoreCase = true))
        }
        val item = BlockedUserItem(
            hexId = cleanHex,
            nickname = cleanNick.ifBlank { cleanHex },
            blockedCategories = blockedCategories,
            isBlockAll = isBlockAll
        )
        if (existingIndex >= 0) {
            current[existingIndex] = item
        } else {
            current.add(item)
        }
        saveBlockedUsers(current)
    }

    fun setUserCategoryFilter(identifier: String, category: String, blocked: Boolean) {
        val current = getBlockedUsers().toMutableList()
        val clean = identifier.trim()
        val catUpper = category.trim().uppercase(java.util.Locale.ROOT)
        val idx = current.indexOfFirst {
            it.hexId.equals(clean, ignoreCase = true) || it.nickname.equals(clean, ignoreCase = true)
        }
        if (idx >= 0) {
            val item = current[idx]
            val newCategories = item.blockedCategories.toMutableSet()
            if (blocked) {
                newCategories.add(catUpper)
            } else {
                newCategories.remove(catUpper)
            }
            current[idx] = item.copy(
                blockedCategories = newCategories,
                isBlockAll = false
            )
            saveBlockedUsers(current)
        }
    }

    fun setUserBlockAll(identifier: String, blockAll: Boolean) {
        val current = getBlockedUsers().toMutableList()
        val clean = identifier.trim()
        val idx = current.indexOfFirst {
            it.hexId.equals(clean, ignoreCase = true) || it.nickname.equals(clean, ignoreCase = true)
        }
        if (idx >= 0) {
            val item = current[idx]
            current[idx] = item.copy(
                isBlockAll = blockAll,
                blockedCategories = if (blockAll) emptySet() else item.blockedCategories
            )
            saveBlockedUsers(current)
        }
    }

    fun unblockUser(identifier: String) {
        val current = getBlockedUsers().toMutableList()
        val clean = identifier.trim()
        current.removeAll {
            it.hexId.equals(clean, ignoreCase = true) || it.nickname.equals(clean, ignoreCase = true)
        }
        saveBlockedUsers(current)
    }

    fun isUserBlocked(hexId: String?, nickname: String?): Boolean {
        val cleanHex = hexId?.trim().orEmpty()
        val cleanNick = nickname?.trim().orEmpty()
        if (cleanHex.isBlank() && cleanNick.isBlank()) return false
        val blocked = getBlockedUsers()
        return blocked.any {
            (cleanHex.isNotBlank() && it.hexId.isNotBlank() && it.hexId.equals(cleanHex, ignoreCase = true)) ||
            (cleanNick.isNotBlank() && it.nickname.isNotBlank() && it.nickname.equals(cleanNick, ignoreCase = true))
        }
    }

    fun isNotificationFiltered(hexId: String?, nickname: String?, category: String): Boolean {
        val cleanHex = hexId?.trim().orEmpty()
        val cleanNick = nickname?.trim().orEmpty()
        if (cleanHex.isBlank() && cleanNick.isBlank()) return false
        val catUpper = category.trim().uppercase(java.util.Locale.ROOT)
        val blocked = getBlockedUsers()
        return blocked.any { item ->
            val matchUser = (cleanHex.isNotBlank() && item.hexId.isNotBlank() && item.hexId.equals(cleanHex, ignoreCase = true)) ||
                    (cleanNick.isNotBlank() && item.nickname.isNotBlank() && item.nickname.equals(cleanNick, ignoreCase = true))
            if (!matchUser) return@any false
            item.isBlockAll || item.blockedCategories.contains(catUpper)
        }
    }

    private fun saveBlockedUsers(list: List<BlockedUserItem>) {
        val arr = org.json.JSONArray()
        list.forEach {
            val obj = org.json.JSONObject()
            obj.put("hexId", it.hexId)
            obj.put("nickname", it.nickname)
            obj.put("blockedAt", it.blockedAt)
            obj.put("isBlockAll", it.isBlockAll)
            val catArr = org.json.JSONArray()
            it.blockedCategories.forEach { cat -> catArr.put(cat) }
            obj.put("categories", catArr)
            arr.put(obj)
        }
        userFiltersJson = arr.toString()
    }

    // 하이라이트 규칙 JSON
    var highlightRulesJson: String
        get() = prefs.getString(KEY_HIGHLIGHT_RULES_JSON, "[]") ?: "[]"
        set(value) = prefs.edit().putString(KEY_HIGHLIGHT_RULES_JSON, value).apply()

    // 알림 감시 전체 켜기/끄기 (마스터 토글)
    var isMonitoringEnabled: Boolean
        get() = prefs.getBoolean(KEY_MONITORING_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MONITORING_ENABLED, value).apply()

    // 방해금지 시간 설정
    var isDndEnabled: Boolean
        get() = prefs.getBoolean(KEY_DND_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_DND_ENABLED, value).apply()

    var hasPromptedBatteryOptimization: Boolean
        get() = prefs.getBoolean(KEY_HAS_PROMPTED_BATTERY_OPTIMIZATION, false)
        set(value) = prefs.edit().putBoolean(KEY_HAS_PROMPTED_BATTERY_OPTIMIZATION, value).apply()

    var dndStartHour: Int
        get() = prefs.getInt(KEY_DND_START_HOUR, 23)
        set(value) = prefs.edit().putInt(KEY_DND_START_HOUR, value).apply()

    var dndStartMinute: Int
        get() = prefs.getInt(KEY_DND_START_MINUTE, 0)
        set(value) = prefs.edit().putInt(KEY_DND_START_MINUTE, value).apply()

    var dndEndHour: Int
        get() = prefs.getInt(KEY_DND_END_HOUR, 7)
        set(value) = prefs.edit().putInt(KEY_DND_END_HOUR, value).apply()

    var dndEndMinute: Int
        get() = prefs.getInt(KEY_DND_END_MINUTE, 0)
        set(value) = prefs.edit().putInt(KEY_DND_END_MINUTE, value).apply()

    fun isDndActiveNow(): Boolean {
        if (!isDndEnabled) return false
        val now = java.util.Calendar.getInstance()
        val curMin = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        val startMin = dndStartHour * 60 + dndStartMinute
        val endMin = dndEndHour * 60 + dndEndMinute
        return if (startMin <= endMin) {
            curMin in startMin until endMin
        } else {
            curMin >= startMin || curMin < endMin
        }
    }

    // 푸시 알림 수신 여부 (토글)
    var isPushNotificationEnabled: Boolean
        get() = prefs.getBoolean(KEY_PUSH_NOTIFICATION_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_PUSH_NOTIFICATION_ENABLED, value).apply()

    // 사용자가 스와이프해서 지운 알림 기억 (재알림 방지용)
    fun markNotificationDismissed(key: String?) {
        val clean = key?.trim().orEmpty()
        if (clean.isBlank()) return
        appContext.getSharedPreferences(DISMISSED_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong("dismiss:$clean", System.currentTimeMillis())
            .apply()
    }

    fun isNotificationDismissed(key: String?): Boolean {
        val clean = key?.trim().orEmpty()
        if (clean.isBlank()) return false
        return appContext.getSharedPreferences(DISMISSED_PREFS_NAME, Context.MODE_PRIVATE)
            .contains("dismiss:$clean")
    }

    fun clearDismissedNotifications() {
        appContext.getSharedPreferences(DISMISSED_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    fun markTopicSeen(topicId: String?) {
        val clean = topicId?.trim().orEmpty()
        if (clean.isBlank()) return
        appContext.getSharedPreferences(SEEN_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong("topic:$clean", System.currentTimeMillis())
            .apply()
    }

    fun isTopicSeen(topicId: String?): Boolean {
        val clean = topicId?.trim().orEmpty()
        if (clean.isBlank()) return false
        return appContext.getSharedPreferences(SEEN_PREFS_NAME, Context.MODE_PRIVATE)
            .contains("topic:$clean")
    }

    private val recentSemanticKeys = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 동일 유형(type), 사용자(actor), 생성 시각(created) 기반 중복 검증.
     * 동일 분 단위 또는 120초 이내 중복 알림을 방지합니다.
     */
    fun isSemanticDuplicate(type: String, actor: String, created: String, windowSeconds: Long = 120L): Boolean {
        val cleanType = type.trim().uppercase(java.util.Locale.ROOT)
        val cleanActor = actor.trim()
        val cleanCreated = created.trim()
        if (cleanType.isBlank() || cleanActor.isBlank()) return false

        val normalizedTime = if (cleanCreated.length >= 16 && cleanCreated.contains("T")) {
            cleanCreated.substring(0, 16)
        } else {
            cleanCreated
        }

        val semanticKey = "$cleanType|$cleanActor|$normalizedTime"
        val now = System.currentTimeMillis()
        val windowMs = windowSeconds * 1000L

        if (recentSemanticKeys.size > 200) {
            val threshold = now - (windowMs * 2)
            recentSemanticKeys.entries.removeIf { it.value < threshold }
        }

        val lastSeen = recentSemanticKeys[semanticKey]
        if (lastSeen != null && (now - lastSeen) < windowMs) {
            return true
        }

        val prefsKey = "sem_dedup:$semanticKey"
        val seenPrefs = appContext.getSharedPreferences(SEEN_PREFS_NAME, Context.MODE_PRIVATE)
        val savedTime = seenPrefs.getLong(prefsKey, 0L)
        if (savedTime > 0L && (now - savedTime) < windowMs) {
            recentSemanticKeys[semanticKey] = savedTime
            return true
        }

        recentSemanticKeys[semanticKey] = now
        seenPrefs.edit().putLong(prefsKey, now).apply()
        return false
    }

    var rememberNotifications: Boolean
        get() = true
        set(@Suppress("UNUSED_PARAMETER") value) {
            // Notification memory and the original archive are baseline behavior,
            // not an optional toggle. Remove stale values from older releases.
            prefs.edit().remove(KEY_REMEMBER_NOTIFICATIONS).apply()
        }

    var notificationMemoryGeneration: Long
        get() = prefs.getLong(KEY_NOTIFICATION_MEMORY_GENERATION, 0L)
        set(value) = prefs.edit().putLong(KEY_NOTIFICATION_MEMORY_GENERATION, value).apply()

    fun getRememberedNotificationCount(): Int {
        val seenPrefs = appContext.getSharedPreferences(SEEN_PREFS_NAME, Context.MODE_PRIVATE)
        return seenPrefs.all.keys.count { it.startsWith("topic:") }
    }

    fun clearNotificationMemory(bumpGeneration: Boolean = true) {
        appContext.getSharedPreferences(SEEN_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        appContext.getSharedPreferences(FG_POLLING_STATE_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("seen_topic_ids_json")
            .apply()
        if (bumpGeneration) {
            notificationMemoryGeneration = notificationMemoryGeneration + 1L
        }
    }

    // 모든 데이터 삭제 (로그아웃)
    fun clearAll() {
        clearSessionSecrets()
        prefs.edit().clear().apply()
    }
}
