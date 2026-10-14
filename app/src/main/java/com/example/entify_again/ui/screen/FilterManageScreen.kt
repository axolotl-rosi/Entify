package com.example.entify_again.ui.screen

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.entify_again.ui.component.CategorySettingItem
import com.example.entify_again.ui.viewmodel.FilterViewModel

@Composable
fun FilterManageScreen(
    context: Context,
    viewModel: FilterViewModel = viewModel(factory = FilterViewModel.Factory(context))
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        TopAppBar(title = { Text("필터 관리") })

        // Category importance settings
        Text(
            text = "카테고리별 알림 설정",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        uiState.categorySettings.forEach { setting ->
            CategorySettingItem(
                category = setting.category,
                label = setting.label,
                importance = setting.importance,
                onImportanceChanged = { viewModel.setCategoryImportance(setting.category, it) }
            )
        }

        Divider(modifier = Modifier.padding(vertical = 16.dp))

        // User filters section (차단된 사용자 관리)
        Text(
            text = "차단된 사용자 관리",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Text(
            text = "차단된 사용자의 알림은 메인 피드에서 숨겨지고 '차단된 알림' 공간으로 분리됩니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Add user block input row
        var inputHexOrNick by remember { mutableStateOf("") }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputHexOrNick,
                onValueChange = { inputHexOrNick = it },
                label = { Text("닉네임 또는 유저 Hex ID") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    val trimmed = inputHexOrNick.trim()
                    if (trimmed.isNotBlank()) {
                        val isHex = Regex("^[0-9a-fA-F]{24}$").matches(trimmed)
                        val hex = if (isHex) trimmed else ""
                        val nick = if (isHex) trimmed.take(8) else trimmed
                        viewModel.blockUser(hex, nick)
                        inputHexOrNick = ""
                    }
                },
                enabled = inputHexOrNick.isNotBlank()
            ) {
                Text("차단")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Blocked users list
        if (uiState.blockedUsers.isEmpty()) {
            Text(
                text = "현재 차단된 사용자가 없습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        } else {
            uiState.blockedUsers.forEach { user ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Block,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = user.nickname.ifBlank { "(이름 없음)" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (user.hexId.isNotBlank()) {
                                    Text(
                                        text = "ID: ${user.hexId}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }
                            TextButton(
                                onClick = {
                                    val idToUnblock = user.hexId.ifBlank { user.nickname }
                                    viewModel.unblockUser(idToUnblock)
                                }
                            ) {
                                Text("삭제", color = MaterialTheme.colorScheme.error)
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "차단할 알림 종류:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        val idForFilter = user.hexId.ifBlank { user.nickname }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = user.isBlockAll,
                                onClick = { viewModel.setUserBlockAll(idForFilter, !user.isBlockAll) },
                                label = { Text("전체 차단", style = MaterialTheme.typography.labelSmall) }
                            )
                            val filterCategories = listOf(
                                "COMMENT" to "댓글",
                                "LIKE" to "좋아요",
                                "FOLLOW" to "팔로우",
                                "SHARE" to "공유"
                            )
                            filterCategories.forEach { (catKey, catLabel) ->
                                val isSelected = user.isBlockAll || user.blockedCategories.contains(catKey)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        if (user.isBlockAll) {
                                            viewModel.setUserBlockAll(idForFilter, false)
                                            viewModel.toggleUserCategory(idForFilter, catKey)
                                        } else {
                                            viewModel.toggleUserCategory(idForFilter, catKey)
                                        }
                                    },
                                    label = { Text(catLabel, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                }
            }
        }

        Divider(modifier = Modifier.padding(vertical = 16.dp))

        // Highlight rules section
        Text(
            text = "하이라이트 규칙",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Text(
            text = "특정 키워드가 포함된 알림을 강조 표시합니다.\n(추후 업데이트 예정)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}
