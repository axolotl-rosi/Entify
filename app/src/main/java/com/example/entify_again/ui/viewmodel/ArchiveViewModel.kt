package com.example.entify_again.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.entify_again.data.entity.NotificationArchiveEntity
import com.example.entify_again.data.repository.NotificationRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class ArchiveViewModel(repository: NotificationRepository) : ViewModel() {
    val notifications: StateFlow<List<NotificationArchiveEntity>> = repository.getArchive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ArchiveViewModel(NotificationRepository(context)) as T
    }
}
