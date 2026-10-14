package com.example.entify_again.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Calendar
import java.util.concurrent.TimeUnit

@Composable
fun DateSectionHeader(
    label: String,
    modifier: Modifier = Modifier,
    isSelectionMode: Boolean = false,
    allDateSelected: Boolean = false,
    onToggleDateSelect: (() -> Unit)? = null,
    onDeleteDateGroup: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isSelectionMode && onToggleDateSelect != null) {
                TextButton(
                    onClick = onToggleDateSelect,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (allDateSelected) "$label 선택 해제" else "$label 전체 선택",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (onDeleteDateGroup != null) {
                TextButton(
                    onClick = onDeleteDateGroup,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "$label 삭제",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }
}

fun resolveDateGroup(millis: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - millis
    val todayStart = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    return when {
        millis >= todayStart -> "오늘"
        millis >= todayStart - TimeUnit.DAYS.toMillis(1) -> "어제"
        diff < TimeUnit.DAYS.toMillis(7) -> "이번 주"
        else -> "이전"
    }
}
