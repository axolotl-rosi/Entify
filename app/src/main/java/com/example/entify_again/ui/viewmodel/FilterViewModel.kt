package com.example.entify_again.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.entify_again.data.repository.NotificationRepository
import com.example.entify_again.util.PreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CategorySetting(
    val category: String,
    val label: String,
    val importance: String  // sound, vibrate, silent, off
)

data class FilterUiState(
    val categorySettings: List<CategorySetting> = emptyList(),
    val blockedUsers: List<PreferenceManager.BlockedUserItem> = emptyList(),
    val userFiltersJson: String = "[]",
    val highlightRulesJson: String = "[]"
)

class FilterViewModel(
    private val prefs: PreferenceManager,
    private val repository: NotificationRepository
) : ViewModel() {

    private val categories = listOf(
        "COMMENT" to "댓글",
        "LIKE" to "좋아요",
        "FOLLOW" to "팔로우",
        "SHARE" to "공유",
        "SYSTEM" to "시스템"
    )

    private val _uiState = MutableStateFlow(loadState())
    val uiState: StateFlow<FilterUiState> = _uiState

    private fun loadState(): FilterUiState {
        return FilterUiState(
            categorySettings = categories.map { (cat, label) ->
                CategorySetting(cat, label, prefs.getCategoryImportance(cat))
            },
            blockedUsers = prefs.getBlockedUsers(),
            userFiltersJson = prefs.userFiltersJson,
            highlightRulesJson = prefs.highlightRulesJson
        )
    }

    fun setCategoryImportance(category: String, importance: String) {
        prefs.setCategoryImportance(category, importance)
        _uiState.update { state ->
            state.copy(
                categorySettings = state.categorySettings.map {
                    if (it.category == category) it.copy(importance = importance) else it
                }
            )
        }
    }

    fun blockUser(hexId: String, nickname: String) {
        viewModelScope.launch {
            val cleanHex = hexId.trim()
            val cleanNick = nickname.trim()
            prefs.blockUser(cleanHex, cleanNick)
            if (cleanHex.isNotBlank()) {
                repository.setBlockedByActorHexId(cleanHex, true)
            }
            if (cleanNick.isNotBlank()) {
                repository.setBlockedByActor(cleanNick, true)
            }
            _uiState.update { it.copy(blockedUsers = prefs.getBlockedUsers()) }
        }
    }

    fun unblockUser(identifier: String) {
        viewModelScope.launch {
            val clean = identifier.trim()
            prefs.unblockUser(clean)
            repository.setBlockedByActorHexId(clean, false)
            repository.setBlockedByActor(clean, false)
            _uiState.update { it.copy(blockedUsers = prefs.getBlockedUsers()) }
        }
    }

    fun toggleUserCategory(identifier: String, category: String) {
        val user = prefs.getBlockedUsers().firstOrNull {
            it.hexId.equals(identifier, ignoreCase = true) || it.nickname.equals(identifier, ignoreCase = true)
        } ?: return
        val isCurrentlyBlocked = user.blockedCategories.contains(category.uppercase(java.util.Locale.ROOT))
        prefs.setUserCategoryFilter(identifier, category, !isCurrentlyBlocked)
        _uiState.update { it.copy(blockedUsers = prefs.getBlockedUsers()) }
    }

    fun setUserBlockAll(identifier: String, blockAll: Boolean) {
        prefs.setUserBlockAll(identifier, blockAll)
        _uiState.update { it.copy(blockedUsers = prefs.getBlockedUsers()) }
    }

    fun setUserFiltersJson(json: String) {
        prefs.userFiltersJson = json
        _uiState.update { it.copy(userFiltersJson = json, blockedUsers = prefs.getBlockedUsers()) }
    }

    fun setHighlightRulesJson(json: String) {
        prefs.highlightRulesJson = json
        _uiState.update { it.copy(highlightRulesJson = json) }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FilterViewModel(
                prefs = PreferenceManager(context),
                repository = NotificationRepository(context)
            ) as T
        }
    }
}
