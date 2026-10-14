package com.example.entify_again.ui.screen

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.entify_again.data.entity.NotificationEntity
import com.example.entify_again.ui.component.NotificationCard
import com.example.entify_again.ui.theme.CategoryColors
import com.example.entify_again.ui.viewmodel.FeedViewModel
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    context: Context,
    onNavigateBack: (() -> Unit)? = null,
    viewModel: FeedViewModel = viewModel(factory = FeedViewModel.Factory(context))
) {
    val notifications by viewModel.allNotificationsForStats.collectAsState()

    var selectedPeriodDays by remember { mutableStateOf(0) } // 0: 전체, 1: 24시간, 7: 7일

    val filteredNotifications = remember(notifications, selectedPeriodDays) {
        if (selectedPeriodDays == 0) {
            notifications
        } else {
            val cutoff = System.currentTimeMillis() - (selectedPeriodDays.toLong() * 24 * 60 * 60 * 1000)
            notifications.filter { it.createdAt >= cutoff }
        }
    }

    val cal = remember { Calendar.getInstance() }

    // 24시간 히스토그램 집계
    val hourCounts = remember(filteredNotifications) {
        val counts = IntArray(24) { 0 }
        filteredNotifications.forEach { notif ->
            cal.timeInMillis = notif.createdAt
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            if (hour in 0..23) counts[hour]++
        }
        counts
    }

    val maxCount = remember(hourCounts) { hourCounts.maxOrNull()?.coerceAtLeast(1) ?: 1 }
    val peakHour = remember(hourCounts) {
        val maxVal = hourCounts.maxOrNull() ?: 0
        if (maxVal > 0) hourCounts.indexOf(maxVal) else null
    }

    var selectedHour by remember { mutableStateOf<Int?>(peakHour) }
    var filterBySelectedHourOnly by remember { mutableStateOf(false) }
    var visibleLimit by remember { mutableStateOf(30) }

    val displayNotifications = remember(filteredNotifications, filterBySelectedHourOnly, selectedHour) {
        if (filterBySelectedHourOnly && selectedHour != null) {
            val targetH = selectedHour!!
            filteredNotifications.filter { notif ->
                cal.timeInMillis = notif.createdAt
                cal.get(Calendar.HOUR_OF_DAY) == targetH
            }
        } else {
            filteredNotifications
        }
    }

    // 4개 구간별 집계 (야간: 0-6, 오전: 6-12, 오후: 12-18, 저녁: 18-24)
    val nightCount = remember(hourCounts) { (0..5).sumOf { hourCounts[it] } }
    val morningCount = remember(hourCounts) { (6..11).sumOf { hourCounts[it] } }
    val afternoonCount = remember(hourCounts) { (12..17).sumOf { hourCounts[it] } }
    val eveningCount = remember(hourCounts) { (18..23).sumOf { hourCounts[it] } }

    val totalCount = filteredNotifications.size
    val commentCount = remember(filteredNotifications) {
        filteredNotifications.count { it.category.equals("COMMENT", ignoreCase = true) || it.category.equals("REPLY", ignoreCase = true) }
    }
    val likeCount = remember(filteredNotifications) {
        filteredNotifications.count { it.category.equals("LIKE", ignoreCase = true) }
    }
    val followCount = remember(filteredNotifications) {
        filteredNotifications.count { it.category.equals("FOLLOW", ignoreCase = true) }
    }
    val shareCount = remember(filteredNotifications) {
        filteredNotifications.count { it.category.equals("SHARE", ignoreCase = true) }
    }
    val systemCount = remember(filteredNotifications) {
        filteredNotifications.count { it.category.equals("SYSTEM", ignoreCase = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        TopAppBar(
            title = { Text("알림 분석 & 통계") },
            navigationIcon = {
                if (onNavigateBack != null) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "뒤로가기")
                    }
                }
            }
        )

        // 기간 필터 칩
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedPeriodDays == 0,
                onClick = { selectedPeriodDays = 0 },
                label = { Text("전체 기간") }
            )
            FilterChip(
                selected = selectedPeriodDays == 7,
                onClick = { selectedPeriodDays = 7 },
                label = { Text("최근 7일") }
            )
            FilterChip(
                selected = selectedPeriodDays == 1,
                onClick = { selectedPeriodDays = 1 },
                label = { Text("최근 24시간") }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 1. 요약 통계 카드
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.QueryStats,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "활동 요약",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatSummaryItem(label = "전체 알림", value = "${totalCount}개", color = MaterialTheme.colorScheme.primary)
                    StatSummaryItem(label = "댓글/답글", value = "${commentCount}개", color = CategoryColors.Comment)
                    StatSummaryItem(label = "좋아요", value = "${likeCount}개", color = CategoryColors.Like)
                    StatSummaryItem(
                        label = "피크 시간",
                        value = peakHour?.let { "${it}시" } ?: "-",
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }

        // 2. 24시간 분포 히스토그램 (00시 ~ 23시)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "24시간 수신 분포",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    selectedHour?.let { h ->
                        Text(
                            text = "${h}시: ${hourCounts[h]}개",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 차트 막대 영역
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    hourCounts.forEachIndexed { hour, count ->
                        val isSelected = selectedHour == hour
                        val isPeak = peakHour == hour && count > 0
                        val barHeightFactor = (count.toFloat() / maxCount.toFloat()).coerceIn(0.06f, 1f)

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { selectedHour = hour }
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.72f)
                                    .height((100 * barHeightFactor).dp)
                                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                    .background(
                                        when {
                                            isSelected -> MaterialTheme.colorScheme.primary
                                            isPeak -> MaterialTheme.colorScheme.tertiary
                                            count > 0 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                        }
                                    )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 시간 레이블 (0, 6, 12, 18, 23)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("00시", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("06시", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("12시", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("18시", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("23시", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // 3. 4분할 시간대 분석
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "시간대별 비중",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                PeriodDistributionBar(label = "야간 (00~06시)", count = nightCount, total = totalCount, color = Color(0xFF5C6BC0))
                Spacer(modifier = Modifier.height(8.dp))
                PeriodDistributionBar(label = "오전 (06~12시)", count = morningCount, total = totalCount, color = Color(0xFF26A69A))
                Spacer(modifier = Modifier.height(8.dp))
                PeriodDistributionBar(label = "오후 (12~18시)", count = afternoonCount, total = totalCount, color = Color(0xFFFFA726))
                Spacer(modifier = Modifier.height(8.dp))
                PeriodDistributionBar(label = "저녁 (18~24시)", count = eveningCount, total = totalCount, color = Color(0xFFAB47BC))
            }
        }

        // 4. 카테고리별 상세 분포
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "알림 유형 분포",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                CategoryCountRow(icon = Icons.Default.ChatBubble, label = "댓글 및 답글", count = commentCount, color = CategoryColors.Comment)
                CategoryCountRow(icon = Icons.Default.Favorite, label = "작품/댓글 좋아요", count = likeCount, color = CategoryColors.Like)
                CategoryCountRow(icon = Icons.Default.PersonAdd, label = "새 팔로워", count = followCount, color = CategoryColors.Follow)
                CategoryCountRow(icon = Icons.Default.Share, label = "작품 공유 및 리메이크", count = shareCount, color = CategoryColors.Share)
                CategoryCountRow(icon = Icons.Default.SmartToy, label = "시스템 및 공지사항", count = systemCount, color = CategoryColors.System)
            }
        }

        // 5. 친근한 Gemini 인사이트 카드
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    Icons.Default.Lightbulb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "엔티파이 인사이트",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = when {
                            totalCount == 0 -> "아직 수신된 알림 데이터가 충분하지 않아요. 엔트리 활동이 쌓이면 분석이 시작돼요!"
                            eveningCount >= (totalCount / 2) -> "저녁 시간(18~24시)에 가장 활발하게 알림이 오고 있어요! 방과 후나 퇴근 시간에 피드백을 확인하기 좋아요."
                            afternoonCount >= (totalCount / 2) -> "오후 시간대(12~18시)에 활발한 상호작용이 일어나고 있어요."
                            else -> "하루 중 여러 시간대에 걸쳐 꾸준하게 알림이 도착하고 있어요!"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                    )
                }
            }
        }

        // 6. 수신된 실제 알림 목록 (가로로 긴 카드형 목록)
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "수신된 알림 목록 (${displayNotifications.size}개)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (selectedHour != null) {
                FilterChip(
                    selected = filterBySelectedHourOnly,
                    onClick = { filterBySelectedHourOnly = !filterBySelectedHourOnly },
                    label = { Text("${selectedHour}시 알림만") }
                )
            }
        }

        if (displayNotifications.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (filterBySelectedHourOnly && selectedHour != null) "${selectedHour}시에 수신된 알림이 없습니다." else "수신된 알림이 없습니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            displayNotifications.take(visibleLimit).forEach { notif ->
                NotificationCard(
                    notification = notif,
                    onStarToggle = { viewModel.toggleStarred(notif) },
                    showActions = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            if (displayNotifications.size > visibleLimit) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    TextButton(onClick = { visibleLimit += 30 }) {
                        Text("더보기 (${visibleLimit}/${displayNotifications.size})")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun StatSummaryItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PeriodDistributionBar(label: String, count: Int, total: Int, color: Color) {
    val percent = if (total > 0) (count.toFloat() / total.toFloat()) else 0f
    val percentText = (percent * 100).toInt()

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text("${count}개 ($percentText%)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = percent,
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = color,
            trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
        )
    }
}

@Composable
private fun CategoryCountRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, count: Int, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = color.copy(alpha = 0.15f),
            modifier = Modifier.size(28.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("${count}개", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}
