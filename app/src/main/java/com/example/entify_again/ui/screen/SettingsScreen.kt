package com.example.entify_again.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.example.entify_again.util.BatteryOptimizationHelper
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.SubcomposeAsyncImage
import com.example.entify_again.data.entity.NotificationEntity
import com.example.entify_again.ui.component.NotificationCard
import com.example.entify_again.ui.theme.ThemeMode
import com.example.entify_again.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    context: Context,
    onLogout: () -> Unit,
    onNavigateToLogin: () -> Unit = {},
    onNavigateToArchive: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(context))
) {
    val uiState by viewModel.uiState.collectAsState()
    var showThemeDialog by remember { mutableStateOf(false) }
    var showPollingDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showClearMemoryDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showEditHexIdDialog by remember { mutableStateOf(false) }
    var showDndDialog by remember { mutableStateOf(false) }
    var tempHexId by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        TopAppBar(title = { Text("설정") })

        // Account / Profile section
        SectionHeader("프로필")
        if (uiState.isLoggedIn) {
            SnsProfileCard(
                username = uiState.username ?: "엔트리 유저",
                profileImageUrl = uiState.profileImageUrl,
                userId = uiState.commentUserId,
                notificationCount = uiState.notificationCount,
                pollingIntervalSec = uiState.pollingIntervalMs / 1000,
                rememberedCount = uiState.rememberedNotificationCount,
                onLogoutClick = { showLogoutDialog = true },
                onEditHexIdClick = {
                    tempHexId = uiState.commentUserId.orEmpty()
                    showEditHexIdDialog = true
                },
                onOpenProfileClick = {
                    val targetUser = uiState.commentUserId?.takeIf { it.isNotBlank() } ?: uiState.username.orEmpty()
                    if (targetUser.isNotBlank()) {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://playentry.org/profile/$targetUser")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try { context.startActivity(intent) } catch (_: Exception) {}
                    }
                },
                remainingRefreshCount = uiState.remainingProfileRefreshCount,
                onRefreshProfileClick = {
                    viewModel.refreshUserProfile { msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            )
        } else {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "로그인이 필요합니다",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "알림을 수신하려면 PlayEntry에 로그인하세요",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { onNavigateToLogin() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("엔트리 로그인")
                    }
                }
            }
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        // Appearance section
        SectionHeader("앱 설정")
        SettingsToggleItem(
            icon = Icons.Default.NotificationsActive,
            title = "알림 감시 (백그라운드)",
            subtitle = if (uiState.isMonitoringEnabled) "새 알림을 백그라운드에서 주기적으로 확인 중" else "알림 감시 꺼짐 (새 알림 확인 안 함)",
            checked = uiState.isMonitoringEnabled,
            onCheckedChange = { viewModel.setMonitoringEnabled(it) }
        )
        SettingsToggleItem(
            icon = Icons.Default.Notifications,
            title = "푸시 알림 받기",
            subtitle = if (uiState.isPushNotificationEnabled) "새 알림 수신 시 상단 푸시 알림 표시" else "푸시 알림 끔 (인앱 로그에만 기록)",
            checked = uiState.isPushNotificationEnabled,
            onCheckedChange = { viewModel.setPushNotificationEnabled(it) }
        )
        SettingsItem(
            icon = Icons.Default.Bedtime,
            title = "방해금지 시간대",
            subtitle = if (uiState.isDndEnabled) {
                String.format(
                    java.util.Locale.getDefault(),
                    "%02d:%02d ~ %02d:%02d (이 시간 동안 감지 안 함)",
                    uiState.dndStartHour, uiState.dndStartMinute,
                    uiState.dndEndHour, uiState.dndEndMinute
                )
            } else {
                "사용 안 함 (탭하여 시간대 설정)"
            },
            onClick = { showDndDialog = true }
        )
        SettingsItem(
            icon = Icons.Default.DarkMode,
            title = "테마",
            subtitle = when (uiState.themeMode) {
                ThemeMode.LIGHT -> "라이트"
                ThemeMode.DARK -> "다크"
                ThemeMode.SYSTEM -> "시스템 설정"
            },
            onClick = { showThemeDialog = true }
        )
        SettingsItem(
            icon = Icons.Default.Schedule,
            title = "폴링 간격",
            subtitle = "${uiState.pollingIntervalMs / 1000}초",
            onClick = { showPollingDialog = true }
        )
        val isBatteryIgnored = BatteryOptimizationHelper.isBatteryOptimizationIgnored(context)
        SettingsItem(
            icon = Icons.Default.BatteryChargingFull,
            title = "배터리 사용량 제한",
            subtitle = if (isBatteryIgnored) {
                "설정 상태: 제한 없음 (화면 꺼짐 시에도 정상 수신)"
            } else {
                "설정 상태: 제한됨 (절전 모드로 인해 알림이 지연될 수 있음 · 탭하여 해제)"
            },
            onClick = {
                BatteryOptimizationHelper.requestIgnoreBatteryOptimization(context)
            }
        )

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        SectionHeader("알림 형태 미리보기")
        NotificationShapePreview()

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        SectionHeader("알림 기억 관리")
        SettingsItem(
            icon = Icons.Default.DeleteForever,
            title = "기억된 알림 비우기",
            subtitle = "현재 ${uiState.rememberedNotificationCount}개 기억됨 (중복 방지 캐시)",
            onClick = { showClearMemoryDialog = true }
        )

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        // Data & Archive section
        SectionHeader("알림 보관 및 데이터")
        SettingsItem(
            icon = Icons.Default.Archive,
            title = "알림 영구 보관함",
            subtitle = "수신 원본 ${uiState.archiveCount}개 · 자동으로 계속 보존됩니다",
            onClick = onNavigateToArchive
        )
        SettingsItem(
            icon = Icons.Default.DeleteForever,
            title = "일반 알림 목록 비우기",
            subtitle = "피드 ${uiState.notificationCount}개 삭제 · 영구 보관함은 유지",
            onClick = { showClearDialog = true }
        )

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        // App info
        SectionHeader("정보")
        SettingsItem(
            icon = Icons.Default.Info,
            title = "Entify",
            subtitle = "v1.0 - PlayEntry 알림 앱"
        )

        Spacer(modifier = Modifier.height(32.dp))
    }

    // Theme dialog
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("테마 선택") },
            text = {
                Column {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setThemeMode(mode)
                                    showThemeDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = uiState.themeMode == mode,
                                onClick = {
                                    viewModel.setThemeMode(mode)
                                    showThemeDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                when (mode) {
                                    ThemeMode.LIGHT -> "라이트"
                                    ThemeMode.DARK -> "다크"
                                    ThemeMode.SYSTEM -> "시스템 설정"
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) { Text("닫기") }
            }
        )
    }

    // Polling interval dialog
    if (showPollingDialog) {
        val intervals = listOf(5_000L, 10_000L, 30_000L, 60_000L, 300_000L)
        AlertDialog(
            onDismissRequest = { showPollingDialog = false },
            title = { Text("폴링 간격") },
            text = {
                Column {
                    intervals.forEach { ms ->
                        val label = when (ms) {
                            5_000L -> "5초"
                            10_000L -> "10초"
                            30_000L -> "30초"
                            60_000L -> "1분"
                            300_000L -> "5분"
                            else -> "${ms / 1000}초"
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setPollingInterval(ms)
                                    showPollingDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = uiState.pollingIntervalMs == ms,
                                onClick = {
                                    viewModel.setPollingInterval(ms)
                                    showPollingDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPollingDialog = false }) { Text("닫기") }
            }
        )
    }

    // Clear data dialog
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("일반 알림 목록 비우기") },
            text = { Text("일반 피드의 알림만 삭제합니다. 수신 원본은 영구 보관함에 그대로 유지됩니다.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearNotifications()
                    showClearDialog = false
                }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("취소") }
            }
        )
    }

    if (showClearMemoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearMemoryDialog = false },
            title = { Text("기억된 알림 비우기") },
            text = { Text("기억 중인 알림 ${uiState.rememberedNotificationCount}개를 비웁니다. 이후 같은 알림도 다시 들어올 수 있습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearRememberedNotifications()
                    showClearMemoryDialog = false
                }) { Text("비우기", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearMemoryDialog = false }) { Text("취소") }
            }
        )
    }

    // Logout dialog
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("로그아웃") },
            text = { Text("로그아웃 하시겠습니까?") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    onLogout()
                }) { Text("로그아웃") }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("취소") }
            }
        )
    }

    // Edit Profile / Hex ID dialog
    if (showEditHexIdDialog) {
        AlertDialog(
            onDismissRequest = { showEditHexIdDialog = false },
            title = { Text("내 프로필 / Hex ID 설정") },
            text = {
                Column {
                    Text(
                        text = "마이페이지 주소 또는 24자리 Hex ID를 입력해주세요. 엔트리 마이페이지 주소를 붙여넣으면 ID만 자동으로 추출됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = tempHexId,
                        onValueChange = { tempHexId = it.trim() },
                        label = { Text("마이페이지 주소 또는 Hex ID") },
                        placeholder = { Text("예: https://playentry.org/profile/61409f... 또는 24자리 ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val extracted = com.example.entify_again.util.SecurityUtil.extractProfileHexId(tempHexId)
                    viewModel.setCommentUserId(extracted)
                    showEditHexIdDialog = false
                }) { Text("저장") }
            },
            dismissButton = {
                TextButton(onClick = { showEditHexIdDialog = false }) { Text("취소") }
            }
        )
    }

    // Do Not Disturb dialog
    if (showDndDialog) {
        var dndEnabled by remember { mutableStateOf(uiState.isDndEnabled) }
        var startH by remember { mutableStateOf(uiState.dndStartHour) }
        var startM by remember { mutableStateOf(uiState.dndStartMinute) }
        var endH by remember { mutableStateOf(uiState.dndEndHour) }
        var endM by remember { mutableStateOf(uiState.dndEndMinute) }

        AlertDialog(
            onDismissRequest = { showDndDialog = false },
            title = { Text("방해금지 시간 설정") },
            text = {
                Column {
                    Text(
                        text = "설정한 시간 동안은 알림 감시를 일시 정지하여 불필요한 배터리 소모를 줄이고 알림을 받지 않습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("방해금지 모드", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = dndEnabled,
                            onCheckedChange = { dndEnabled = it }
                        )
                    }
                    if (dndEnabled) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "시간대 설정 (24시간 형식)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            OutlinedButton(
                                onClick = {
                                    android.app.TimePickerDialog(
                                        context,
                                        { _, h, m -> startH = h; startM = m },
                                        startH,
                                        startM,
                                        true
                                    ).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(String.format(java.util.Locale.getDefault(), "시작: %02d:%02d", startH, startM))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = {
                                    android.app.TimePickerDialog(
                                        context,
                                        { _, h, m -> endH = h; endM = m },
                                        endH,
                                        endM,
                                        true
                                    ).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(String.format(java.util.Locale.getDefault(), "종료: %02d:%02d", endH, endM))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setDndEnabled(dndEnabled)
                    viewModel.setDndTimeRange(startH, startM, endH, endM)
                    showDndDialog = false
                }) { Text("저장") }
            },
            dismissButton = {
                TextButton(onClick = { showDndDialog = false }) { Text("취소") }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 16.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsToggleItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 16.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SnsProfileCard(
    username: String,
    profileImageUrl: String?,
    userId: String?,
    notificationCount: Int,
    pollingIntervalSec: Long,
    rememberedCount: Int,
    remainingRefreshCount: Int,
    onLogoutClick: () -> Unit,
    onOpenProfileClick: () -> Unit,
    onRefreshProfileClick: () -> Unit,
    onEditHexIdClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Gradient banner at top
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.75f)
                            )
                        )
                    )
            )

            // Overlapping Avatar + Info
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = (-28).dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Avatar with circular border
                    Surface(
                        shape = CircleShape,
                        border = BorderStroke(3.dp, MaterialTheme.colorScheme.surface),
                        shadowElevation = 3.dp,
                        modifier = Modifier.size(68.dp)
                    ) {
                        if (!profileImageUrl.isNullOrBlank()) {
                            SubcomposeAsyncImage(
                                model = profileImageUrl,
                                contentDescription = username,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop,
                                error = {
                                    ProfileFallbackAvatar(username)
                                },
                                loading = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(MaterialTheme.colorScheme.primaryContainer)
                                    )
                                }
                            )
                        } else {
                            ProfileFallbackAvatar(username)
                        }
                    }

                    // Action Buttons: Profile Refresh & Profile Link
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = onRefreshProfileClick,
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "프로필 갱신",
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("갱신 ($remainingRefreshCount)", style = MaterialTheme.typography.labelMedium)
                        }

                        OutlinedButton(
                            onClick = onOpenProfileClick,
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("마이페이지", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                // Nickname & Live Status Pill
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = (-20).dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = username,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        // Real-time online sync status chip
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color(0xFF4CAF50).copy(alpha = 0.15f)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF4CAF50))
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "실시간 연동 중",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF2E7D32),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "@$username" + (if (!userId.isNullOrBlank()) " · ID: ${userId.take(8)}..." else " · ID 미등록"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                            modifier = Modifier.clickable { onEditHexIdClick() }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "ID 수정",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(11.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = if (userId.isNullOrBlank()) "ID 등록" else "ID 수정",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // SNS Metric Stats Box
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ProfileStatItem(label = "수신 알림", value = "${notificationCount}개")
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(24.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            )
                            ProfileStatItem(label = "폴링 주기", value = "${pollingIntervalSec}초")
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(24.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            )
                            ProfileStatItem(label = "알림 기억", value = "${rememberedCount}개")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Logout Action
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = onLogoutClick,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Logout,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "로그아웃",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileFallbackAvatar(username: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        if (username.isNotBlank()) {
            Text(
                text = username.take(1).uppercase(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        } else {
            Icon(
                Icons.Default.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

@Composable
private fun ProfileStatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NotificationShapePreview() {
    var selectedSample by remember { mutableStateOf(0) }
    val samples = remember {
        listOf(
            Triple("댓글", "template_1", NotificationEntity(
                id = 1001,
                topicId = "preview_1",
                category = "COMMENT",
                template = "template_1",
                actor = "초코파이",
                actorImageUrl = null,
                title = "새 댓글이 달렸어요 💬",
                body = "초코파이님이 작품에 댓글을 작성했어요\n\"작품 너무 재밌어요! 다음 업데이트도 기대할게요 :)\"",
                deepLink = "https://playentry.org/project/sample",
                isRead = false,
                isStarred = false,
                createdAt = System.currentTimeMillis() - 120000
            )),
            Triple("답글", "template_34", NotificationEntity(
                id = 1002,
                topicId = "preview_2",
                category = "COMMENT",
                template = "template_34",
                actor = "엔트리봇",
                actorImageUrl = null,
                title = "내 댓글에 답글 도착! 💬",
                body = "엔트리봇님이 내 댓글에 답글을 작성했어요\n\"좋은 피드백 감사드려요! 곧 반영해보겠습니다.\"",
                deepLink = "https://playentry.org/project/sample",
                isRead = false,
                isStarred = false,
                createdAt = System.currentTimeMillis() - 600000
            )),
            Triple("좋아요", "template_3", NotificationEntity(
                id = 1003,
                topicId = "preview_3",
                category = "LIKE",
                template = "template_3",
                actor = "달콤젤리",
                actorImageUrl = null,
                title = "작품을 좋아해요! ❤️",
                body = "달콤젤리님이 내 작품을 좋아해요",
                deepLink = "https://playentry.org/project/sample",
                isRead = true,
                isStarred = false,
                createdAt = System.currentTimeMillis() - 3600000
            )),
            Triple("댓글 좋아요 (상세 없음)", "template_36", NotificationEntity(
                id = 1004,
                topicId = "preview_4",
                category = "LIKE",
                template = "template_36",
                actor = "누군가",
                actorImageUrl = null,
                title = "내 댓글을 좋아해요! ❤️",
                body = "누군가님이 내 댓글을 좋아해요",
                deepLink = null,
                isRead = false,
                isStarred = false,
                createdAt = System.currentTimeMillis() - 7200000
            )),
            Triple("팔로우", "template_27", NotificationEntity(
                id = 1005,
                topicId = "preview_5",
                category = "FOLLOW",
                template = "template_27",
                actor = "코딩마니아",
                actorImageUrl = null,
                title = "새 팔로워가 생겼어요! 🎉",
                body = "코딩마니아님이 나를 팔로우하기 시작했어요",
                deepLink = "https://playentry.org/profile/coding_mania",
                isRead = true,
                isStarred = false,
                createdAt = System.currentTimeMillis() - 14400000
            )),
            Triple("리메이크", "template_5", NotificationEntity(
                id = 1006,
                topicId = "preview_6",
                category = "SHARE",
                template = "template_5",
                actor = "게임제작자",
                actorImageUrl = null,
                title = "작품 리메이크 🎨",
                body = "게임제작자님이 '슈퍼 런 2026' 작품을 리메이크했습니다.",
                deepLink = "https://playentry.org/project/sample",
                isRead = false,
                isStarred = false,
                createdAt = System.currentTimeMillis() - 28800000
            )),
            Triple("운영 제재 / 경고", "template_18", NotificationEntity(
                id = 1007,
                topicId = "preview_7",
                category = "WARNING",
                template = "template_18",
                actor = "엔트리 운영진",
                actorImageUrl = null,
                title = "게시물이 블라인드 처리되었습니다 ⚠️",
                body = "회원님의 게시글이 운영정책 위반으로 블라인드 처리되었습니다.",
                deepLink = null,
                isRead = false,
                isStarred = false,
                createdAt = System.currentTimeMillis() - 86400000
            ))
        )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            samples.forEachIndexed { index, item ->
                FilterChip(
                    selected = selectedSample == index,
                    onClick = { selectedSample = index },
                    label = { Text(item.first, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            NotificationCard(
                notification = samples[selectedSample].third,
                onCardClick = {},
                onRequestNavigate = {},
                onMediaClick = { _, _ -> },
                onStarToggle = {},
                onRequestBlockUser = {},
                onUnblockUser = {},
                onDeleteNotification = {}
            )
        }
    }
}
