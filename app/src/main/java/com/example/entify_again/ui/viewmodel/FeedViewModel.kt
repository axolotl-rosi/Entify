package com.example.entify_again.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.entify_again.data.entity.NotificationEntity
import com.example.entify_again.data.repository.NotificationRepository
import com.example.entify_again.util.PreferenceManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class FeedFilter(val label: String) {
    ALL("전체"),
    COMMENT("댓글"),
    LIKE("좋아요"),
    FOLLOW("팔로우"),
    REMAKE("리메이크"),
    SHARE("공유"),
    STARRED("즐겨찾기"),
    BLOCKED("차단된 알림")
}

data class FeedUiState(
    val notifications: List<NotificationEntity> = emptyList(),
    val activeFilter: FeedFilter = FeedFilter.ALL,
    val isLoading: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModel(
    private val repository: NotificationRepository,
    private val prefs: PreferenceManager
) : ViewModel() {

    private val _activeFilter = MutableStateFlow(FeedFilter.ALL)

    val uiState: StateFlow<FeedUiState> = _activeFilter
        .flatMapLatest { filter ->
            when (filter) {
                FeedFilter.ALL -> repository.getAllNotifications()
                FeedFilter.STARRED -> repository.getStarred()
                FeedFilter.BLOCKED -> repository.getBlocked()
                else -> repository.getByCategory(filter.name)
            }.map { notifications ->
                FeedUiState(
                    notifications = notifications,
                    activeFilter = filter
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FeedUiState())

    val allNotificationsForStats: StateFlow<List<NotificationEntity>> = repository.getAllForStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unreadCount: StateFlow<Int> = repository.getUnreadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun setFilter(filter: FeedFilter) {
        _activeFilter.value = filter
    }

    fun markRead(id: Long) {
        viewModelScope.launch {
            val entity = repository.getById(id)
            repository.markRead(id)
            entity?.topicId?.let { prefs.markTopicSeen(it) }
        }
    }

    fun markAllRead() {
        viewModelScope.launch {
            repository.markAllRead()
            val readIds = repository.getAllReadTopicIds()
            readIds.forEach { prefs.markTopicSeen(it) }
        }
    }

    fun toggleStarred(entity: NotificationEntity) {
        viewModelScope.launch { repository.setStarred(entity.id, !entity.isStarred) }
    }

    fun blockUser(entity: NotificationEntity) {
        viewModelScope.launch {
            val hex = entity.actorHexId.orEmpty().trim()
            val nick = entity.actor.trim()
            prefs.blockUser(hex, nick)
            if (hex.isNotBlank()) {
                repository.setBlockedByActorHexId(hex, true)
            }
            if (nick.isNotBlank()) {
                repository.setBlockedByActor(nick, true)
            }
            repository.setBlocked(entity.id, true)
        }
    }

    fun unblockUser(entity: NotificationEntity) {
        viewModelScope.launch {
            val hex = entity.actorHexId.orEmpty().trim()
            val nick = entity.actor.trim()
            if (hex.isNotBlank()) {
                prefs.unblockUser(hex)
                repository.setBlockedByActorHexId(hex, false)
            }
            if (nick.isNotBlank()) {
                prefs.unblockUser(nick)
                repository.setBlockedByActor(nick, false)
            }
            repository.setBlocked(entity.id, false)
        }
    }

    fun deleteNotification(id: Long) {
        viewModelScope.launch {
            repository.deleteById(id)
        }
    }

    fun deleteNotifications(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.deleteByIds(ids.toList())
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            if (_activeFilter.value == FeedFilter.BLOCKED) {
                repository.deleteByBlockedStatus(true)
            } else {
                repository.deleteByBlockedStatus(false)
            }
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FeedViewModel(
                repository = NotificationRepository(context),
                prefs = PreferenceManager(context)
            ) as T
        }
    }
}
