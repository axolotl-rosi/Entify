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
import androidx.compose.material3.AlertDialog
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
import com.example.entify_again.util.SecurityUtil

@Composable
fun FilterManageScreen(
    context: Context,
    viewModel: FilterViewModel = viewModel(factory = FilterViewModel.Factory(context))
) {
    val uiState by viewModel.uiState.collectAsState()
    var showSelfBlockEasterEgg by remember { mutableStateOf(false) }

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

        // User-specific notification controls
        Text(
            text = "사용자별 알림 관리",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Text(
            text = "사용자마다 피드에서 숨길 알림 종류를 선택할 수 있습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Add user block input row
        var inputHexOrNick by remember { mutableStateOf("") }

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputHexOrNick,
                    onValueChange = { inputHexOrNick = it },
                    label = { Text("유저 마이페이지 주소") },
                    placeholder = { Text("https://playentry.org/profile/...") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val trimmed = inputHexOrNick.trim()
                        if (trimmed.isNotBlank()) {
                            if (viewModel.isSelfUser(trimmed)) {
                                showSelfBlockEasterEgg = true
                            } else {
                                val extractedHex = SecurityUtil.extractProfileHexId(trimmed)
                                val isHex = Regex("^[0-9a-fA-F]{24}$").matches(extractedHex)
                                val hex = if (isHex) extractedHex else (if (Regex("^[0-9a-fA-F]{24}$").matches(trimmed)) trimmed else "")
                                val nick = when {
                                    isHex -> extractedHex.take(8)
                                    extractedHex.isNotBlank() && extractedHex != trimmed -> extractedHex
                                    else -> trimmed
                                }
                                viewModel.blockUser(hex, nick)
                                inputHexOrNick = ""
                            }
                        }
                    },
                    enabled = inputHexOrNick.isNotBlank()
                ) {
                    Text("관리 추가")
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "알림을 관리할 사용자의 마이페이지 주소 또는 24자리 Hex ID를 입력해주세요.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Blocked users list
        if (uiState.blockedUsers.isEmpty()) {
            Text(
                text = "별도로 알림을 관리 중인 사용자가 없습니다.",
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
                            text = "피드에서 숨길 알림 종류:",
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
                                label = { Text("전체 숨기기", style = MaterialTheme.typography.labelSmall) }
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

    if (showSelfBlockEasterEgg) {
        AlertDialog(
            onDismissRequest = { showSelfBlockEasterEgg = false },
            title = { Text("설정할 수 없음") },
            text = { Text("내 계정의 알림은 사용자별 관리 대상에서 제외됩니다.") },
            confirmButton = {
                TextButton(onClick = { showSelfBlockEasterEgg = false }) {
                    Text("확인")
                }
            }
        )
    }
}
