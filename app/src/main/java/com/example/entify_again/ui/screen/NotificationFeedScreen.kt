package com.example.entify_again.ui.screen

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.entify_again.data.entity.NotificationEntity
import com.example.entify_again.ui.component.DateSectionHeader
import com.example.entify_again.ui.component.FilterChipRow
import com.example.entify_again.ui.component.LightboxViewerDialog
import com.example.entify_again.ui.component.NotificationCard
import com.example.entify_again.ui.component.resolveDateGroup
import com.example.entify_again.ui.viewmodel.FeedFilter
import com.example.entify_again.ui.viewmodel.FeedViewModel
import com.example.entify_again.util.SecurityUtil

@Composable
fun NotificationFeedScreen(
    context: Context,
    onNavigateToAnalytics: () -> Unit = {},
    viewModel: FeedViewModel = viewModel(factory = FeedViewModel.Factory(context))
) {
    val uiState by viewModel.uiState.collectAsState()
    val unreadCount by viewModel.unreadCount.collectAsState()

    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showDeleteSelectionDialog by remember { mutableStateOf(false) }

    var currentPage by remember { mutableStateOf(1) }
    val pageSize = 25

    var pendingNavigateLink by remember { mutableStateOf<String?>(null) }
    var lightboxMedia by remember { mutableStateOf<Pair<String, String>?>(null) }
    var userToBlock by remember { mutableStateOf<NotificationEntity?>(null) }

    val isBlockedTab = uiState.activeFilter == FeedFilter.BLOCKED

    val allFilteredNotifications = uiState.notifications
    val totalPages = maxOf(1, (allFilteredNotifications.size + pageSize - 1) / pageSize)
    val safeCurrentPage = currentPage.coerceIn(1, totalPages)
    val pagedNotifications = remember(allFilteredNotifications, safeCurrentPage) {
        val start = (safeCurrentPage - 1) * pageSize
        allFilteredNotifications.drop(start).take(pageSize)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (isSelectionMode) {
            TopAppBar(
                title = {
                    Text(
                        text = if (selectedIds.isEmpty()) "항목 선택" else "${selectedIds.size}개 선택됨",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        isSelectionMode = false
                        selectedIds = emptySet()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "선택 모드 종료")
                    }
                },
                actions = {
                    val currentPageIds = remember(pagedNotifications) { pagedNotifications.map { it.id }.toSet() }
                    val allCurrentPageSelected = currentPageIds.isNotEmpty() && selectedIds.containsAll(currentPageIds)

                    TextButton(onClick = {
                        selectedIds = if (allCurrentPageSelected) {
                            selectedIds - currentPageIds
                        } else {
                            selectedIds + currentPageIds
                        }
                    }) {
                        Text(
                            text = if (allCurrentPageSelected) "페이지 해제" else "페이지 전체",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    IconButton(
                        onClick = { showDeleteSelectionDialog = true },
                        enabled = selectedIds.isNotEmpty()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "선택 삭제",
                            tint = if (selectedIds.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                }
            )
        } else {
            TopAppBar(
                title = {
                    Text(
                        when {
                            isBlockedTab -> "차단된 알림"
                            unreadCount > 0 -> "알림 ($unreadCount)"
                            else -> "알림"
                        }
                    )
                },
                actions = {
                    // 종합 알림 분석 화면 열기 (시계 아이콘은 전용 분석 화면으로 대체되어 제거)
                    IconButton(onClick = onNavigateToAnalytics) {
                        Icon(Icons.Default.QueryStats, contentDescription = "알림 분석")
                    }

                    if (uiState.notifications.isNotEmpty()) {
                        // 체크/선택 모드 진입 버튼
                        IconButton(onClick = { isSelectionMode = true }) {
                            Icon(Icons.Default.Checklist, contentDescription = "선택 관리")
                        }
                    }
                }
            )
        }

        FilterChipRow(
            activeFilter = uiState.activeFilter,
            onFilterSelected = {
                viewModel.setFilter(it)
                currentPage = 1
                selectedIds = emptySet()
            }
        )

        if (allFilteredNotifications.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (isBlockedTab) Icons.Default.Block else Icons.Default.NotificationsNone,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (isBlockedTab) "차단된 알림이 없습니다." else "알림이 없습니다",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isBlockedTab) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "사용자를 차단하면 해당 사용자의 알림이 이곳에 보관됩니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            // Group by date for current page
            val grouped = pagedNotifications.groupBy { resolveDateGroup(it.createdAt) }
            val dateOrder = listOf("오늘", "어제", "이번 주", "이전")

            Column(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    dateOrder.forEach { dateLabel ->
                        val items = grouped[dateLabel] ?: return@forEach
                        val dateItemIds = items.map { it.id }.toSet()
                        val allDateSelected = dateItemIds.isNotEmpty() && selectedIds.containsAll(dateItemIds)

                        item(key = "header_$dateLabel") {
                            DateSectionHeader(
                                label = dateLabel,
                                isSelectionMode = isSelectionMode,
                                allDateSelected = allDateSelected,
                                onToggleDateSelect = {
                                    selectedIds = if (allDateSelected) {
                                        selectedIds - dateItemIds
                                    } else {
                                        selectedIds + dateItemIds
                                    }
                                }
                            )
                        }

                        items(items, key = { it.id }) { notification ->
                            NotificationCard(
                                notification = notification,
                                isSelectionMode = isSelectionMode,
                                isSelected = selectedIds.contains(notification.id),
                                onToggleSelect = {
                                    selectedIds = if (selectedIds.contains(notification.id)) {
                                        selectedIds - notification.id
                                    } else {
                                        selectedIds + notification.id
                                    }
                                },
                                onCardClick = { viewModel.markRead(notification.id) },
                                onRequestNavigate = { link ->
                                    if (SecurityUtil.isValidEntryUrl(link)) {
                                        pendingNavigateLink = link
                                    }
                                },
                                onMediaClick = { url, title -> lightboxMedia = Pair(url, title) },
                                onStarToggle = { viewModel.toggleStarred(notification) },
                                onRequestBlockUser = { target -> userToBlock = target },
                                onUnblockUser = { target -> viewModel.unblockUser(target) },
                                onDeleteNotification = { viewModel.deleteNotification(notification.id) },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                // 페이지 네비게이션 바 (알림이 많을 때 부드러운 전환을 위한 페이지네이션)
                if (totalPages > 1) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { currentPage = (safeCurrentPage - 1).coerceAtLeast(1) },
                                enabled = safeCurrentPage > 1
                            ) {
                                Icon(Icons.Default.ChevronLeft, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("이전")
                            }

                            Text(
                                text = "$safeCurrentPage / $totalPages 페이지 (총 ${allFilteredNotifications.size}개)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            TextButton(
                                onClick = { currentPage = (safeCurrentPage + 1).coerceAtMost(totalPages) },
                                enabled = safeCurrentPage < totalPages
                            ) {
                                Text("다음")
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // 1. 실수 방지 바로가기 확인 다이얼로그 (Safe navigation dialog)
    pendingNavigateLink?.let { link ->
        AlertDialog(
            onDismissRequest = { pendingNavigateLink = null },
            title = { Text("엔트리로 이동") },
            text = {
                Text("선택한 게시글/작품 페이지로 이동하시겠습니까?\n\n$link")
            },
            confirmButton = {
                TextButton(onClick = {
                    val targetLink = link
                    pendingNavigateLink = null
                    SecurityUtil.launchSafeEntryUrl(context, targetLink)
                }) {
                    Text("이동")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingNavigateLink = null }) {
                    Text("취소")
                }
            }
        )
    }

    // 2. 사용자 차단 확인 다이얼로그
    userToBlock?.let { target ->
        AlertDialog(
            onDismissRequest = { userToBlock = null },
            title = { Text("사용자 차단") },
            text = {
                Text(
                    "'${target.actor}' 사용자를 차단하시겠습니까?\n\n" +
                        "차단된 사용자의 알림은 메인 피드에서 숨겨지며, '차단된 알림' 탭으로 분리됩니다."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.blockUser(target)
                    userToBlock = null
                }) {
                    Text("차단", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { userToBlock = null }) {
                    Text("취소")
                }
            }
        )
    }

    // 3. 인앱 스티커/이미지 라이트박스 뷰어
    lightboxMedia?.let { (url, title) ->
        LightboxViewerDialog(
            imageUrl = url,
            title = title,
            onDismiss = { lightboxMedia = null }
        )
    }

    // 4. 선택 항목 삭제 확인 다이얼로그
    if (showDeleteSelectionDialog && selectedIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showDeleteSelectionDialog = false },
            title = { Text("선택한 알림 삭제") },
            text = { Text("선택한 ${selectedIds.size}개의 알림을 삭제하시겠습니까?") },
            confirmButton = {
                TextButton(onClick = {
                    val targets = selectedIds
                    selectedIds = emptySet()
                    isSelectionMode = false
                    showDeleteSelectionDialog = false
                    viewModel.deleteNotifications(targets)
                }) {
                    Text("삭제", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectionDialog = false }) {
                    Text("취소")
                }
            }
        )
    }
}
