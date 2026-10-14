package com.example.entify_again.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.entify_again.data.repository.NotificationRepository
import com.example.entify_again.ui.theme.ThemeMode
import com.example.entify_again.util.PreferenceManager
import com.example.entify_again.util.UserProfileFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val username: String? = null,
    val profileImageUrl: String? = null,
    val commentUserId: String? = null,
    val isLoggedIn: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val pollingIntervalMs: Long = PreferenceManager.DEFAULT_POLLING_INTERVAL_MS,
    val notificationCount: Int = 0,
    val archiveCount: Int = 0,
    val rememberNotifications: Boolean = false,
    val rememberedNotificationCount: Int = 0,
    val remainingProfileRefreshCount: Int = 5,
    val isPushNotificationEnabled: Boolean = true,
    val isMonitoringEnabled: Boolean = true,
    val isDndEnabled: Boolean = false,
    val dndStartHour: Int = 23,
    val dndStartMinute: Int = 0,
    val dndEndHour: Int = 7,
    val dndEndMinute: Int = 0
)

class SettingsViewModel(
    private val prefs: PreferenceManager,
    private val repository: NotificationRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(loadState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    init {
        viewModelScope.launch {
            val count = repository.count()
            val archiveCount = repository.countArchive()
            _uiState.update {
                it.copy(
                    notificationCount = count,
                    archiveCount = archiveCount,
                    rememberedNotificationCount = prefs.getRememberedNotificationCount(),
                    remainingProfileRefreshCount = prefs.getRemainingProfileRefreshCount()
                )
            }
        }
        // 시작할 때 유저 프로필 조회 (아직 없거나 미반영된 경우)
        // 이미 사용자 프로필이 저장된게 있으면 사용자가 갱신 버튼을 누르지 않는 이상 냅둠
        viewModelScope.launch(Dispatchers.IO) {
            if (!prefs.username.isNullOrBlank() && !prefs.profileImageUrl.isNullOrBlank()) {
                return@launch
            }
            val candidate = prefs.commentUserId?.trim()?.takeIf { it.isNotBlank() }
                ?: prefs.username?.trim()?.takeIf { it.isNotBlank() }
            if (!candidate.isNullOrBlank()) {
                val prof = UserProfileFetcher.fetchProfile(
                    idOrNickname = candidate,
                    cookies = prefs.cookies,
                    csrfToken = prefs.csrfToken,
                    xToken = prefs.xToken
                )
                if (prof != null) {
                    prof.nickname?.let { if (prefs.username.isNullOrBlank()) prefs.username = it }
                    prof.avatarUrl?.let { if (prefs.profileImageUrl.isNullOrBlank()) prefs.profileImageUrl = it }
                    _uiState.update {
                        it.copy(
                            username = prefs.username,
                            profileImageUrl = prefs.profileImageUrl
                        )
                    }
                }
            }
        }
        // 로그인 상태 주기적 확인
        viewModelScope.launch {
            while (true) {
                val loggedIn = prefs.isLoggedIn
                _uiState.update {
                    it.copy(
                        isLoggedIn = loggedIn,
                        username = prefs.username,
                        profileImageUrl = prefs.profileImageUrl,
                        commentUserId = prefs.commentUserId,
                        archiveCount = repository.countArchive(),
                        remainingProfileRefreshCount = prefs.getRemainingProfileRefreshCount(),
                        isPushNotificationEnabled = prefs.isPushNotificationEnabled,
                        isMonitoringEnabled = prefs.isMonitoringEnabled,
                        isDndEnabled = prefs.isDndEnabled,
                        dndStartHour = prefs.dndStartHour,
                        dndStartMinute = prefs.dndStartMinute,
                        dndEndHour = prefs.dndEndHour,
                        dndEndMinute = prefs.dndEndMinute
                    )
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private fun loadState(): SettingsUiState {
        val mode = try { ThemeMode.valueOf(prefs.themeMode) } catch (_: Exception) { ThemeMode.SYSTEM }
        return SettingsUiState(
            username = prefs.username,
            profileImageUrl = prefs.profileImageUrl,
            commentUserId = prefs.commentUserId,
            isLoggedIn = prefs.isLoggedIn,
            themeMode = mode,
            pollingIntervalMs = prefs.pollingIntervalMs,
            archiveCount = 0,
            rememberNotifications = prefs.rememberNotifications,
            rememberedNotificationCount = prefs.getRememberedNotificationCount(),
            remainingProfileRefreshCount = prefs.getRemainingProfileRefreshCount(),
            isPushNotificationEnabled = prefs.isPushNotificationEnabled,
            isMonitoringEnabled = prefs.isMonitoringEnabled,
            isDndEnabled = prefs.isDndEnabled,
            dndStartHour = prefs.dndStartHour,
            dndStartMinute = prefs.dndStartMinute,
            dndEndHour = prefs.dndEndHour,
            dndEndMinute = prefs.dndEndMinute
        )
    }

    fun setMonitoringEnabled(enabled: Boolean) {
        prefs.isMonitoringEnabled = enabled
        _uiState.update { it.copy(isMonitoringEnabled = enabled) }
    }

    fun setDndEnabled(enabled: Boolean) {
        prefs.isDndEnabled = enabled
        _uiState.update { it.copy(isDndEnabled = enabled) }
    }

    fun setDndTimeRange(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int) {
        prefs.dndStartHour = startHour
        prefs.dndStartMinute = startMinute
        prefs.dndEndHour = endHour
        prefs.dndEndMinute = endMinute
        _uiState.update {
            it.copy(
                dndStartHour = startHour,
                dndStartMinute = startMinute,
                dndEndHour = endHour,
                dndEndMinute = endMinute
            )
        }
    }

    fun setPushNotificationEnabled(enabled: Boolean) {
        prefs.isPushNotificationEnabled = enabled
        _uiState.update { it.copy(isPushNotificationEnabled = enabled) }
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.themeMode = mode.name
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun setPollingInterval(ms: Long) {
        prefs.pollingIntervalMs = ms
        _uiState.update { it.copy(pollingIntervalMs = ms) }
    }

    fun setCommentUserId(hexId: String) {
        val clean = hexId.trim()
        prefs.commentUserId = clean.ifBlank { null }
        _uiState.update { it.copy(commentUserId = clean.ifBlank { null }) }
    }

    fun clearNotifications() {
        viewModelScope.launch {
            repository.deleteAll()
            _uiState.update { it.copy(notificationCount = 0) }
        }
    }

    fun setRememberNotifications(enabled: Boolean) {
        prefs.rememberNotifications = enabled
        if (!enabled) {
            prefs.clearNotificationMemory()
        }
        _uiState.update {
            it.copy(
                rememberNotifications = enabled,
                rememberedNotificationCount = prefs.getRememberedNotificationCount()
            )
        }
    }

    fun clearRememberedNotifications() {
        prefs.clearNotificationMemory()
        _uiState.update {
            it.copy(rememberedNotificationCount = prefs.getRememberedNotificationCount())
        }
    }

    fun refreshUserProfile(onResult: (String) -> Unit) {
        val remaining = prefs.getRemainingProfileRefreshCount()
        if (remaining <= 0) {
            onResult("오늘 프로필 갱신 한도(5회)를 모두 사용했습니다. 내일 다시 시도해주세요.")
            return
        }
        val targetId = prefs.commentUserId?.trim()?.takeIf { it.isNotBlank() }
            ?: prefs.username?.trim()?.takeIf { it.isNotBlank() }
        if (targetId.isNullOrBlank()) {
            onResult("등록된 유저 ID 또는 닉네임이 없습니다. 프로필의 ID 편집 버튼으로 먼저 등록해주세요.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val (allowed, remainingAfter) = prefs.consumeProfileRefresh()
            if (!allowed) {
                withContext(Dispatchers.Main) {
                    onResult("오늘 프로필 갱신 한도(5회)를 모두 사용했습니다.")
                }
                return@launch
            }
            val prof = UserProfileFetcher.fetchProfile(
                idOrNickname = targetId,
                cookies = prefs.cookies,
                csrfToken = prefs.csrfToken,
                xToken = prefs.xToken
            )
            if (prof != null) {
                prof.nickname?.let { prefs.username = it }
                prof.avatarUrl?.let { prefs.profileImageUrl = it }
                _uiState.update {
                    it.copy(
                        username = prefs.username,
                        profileImageUrl = prefs.profileImageUrl,
                        remainingProfileRefreshCount = remainingAfter
                    )
                }
                withContext(Dispatchers.Main) {
                    onResult("프로필 정보가 갱신되었습니다. (오늘 남은 횟수: ${remainingAfter}회)")
                }
            } else {
                _uiState.update { it.copy(remainingProfileRefreshCount = remainingAfter) }
                withContext(Dispatchers.Main) {
                    onResult("프로필 정보를 가져오지 못했습니다. 잠시 후 다시 시도해주세요. (오늘 남은 횟수: ${remainingAfter}회)")
                }
            }
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(
                PreferenceManager(context),
                NotificationRepository(context)
            ) as T
        }
    }
}
