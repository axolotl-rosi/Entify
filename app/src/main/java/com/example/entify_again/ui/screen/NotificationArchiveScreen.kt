package com.example.entify_again.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.entify_again.ui.component.NotificationCard
import com.example.entify_again.ui.viewmodel.ArchiveViewModel
import com.example.entify_again.util.SecurityUtil

@Composable
fun NotificationArchiveScreen(
    context: Context,
    onNavigateBack: () -> Unit,
    viewModel: ArchiveViewModel = viewModel(factory = ArchiveViewModel.Factory(context))
) {
    val archived by viewModel.notifications.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("알림 영구 보관함") },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                }
            }
        )
        Text(
            text = "수신 당시 내용 ${archived.size}개 · 일반 피드에서 삭제해도 유지됩니다",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(archived, key = { it.id }) { item ->
                val notification = item.asNotification()
                NotificationCard(
                    notification = notification,
                    onRequestNavigate = { url ->
                        if (SecurityUtil.isValidEntryUrl(url)) {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        }
                    },
                    onMediaClick = { url, _ ->
                        if (SecurityUtil.isValidImageUrl(url)) {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        }
                    },
                    onStarToggle = {},
                    showActions = false,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }
    }
}
