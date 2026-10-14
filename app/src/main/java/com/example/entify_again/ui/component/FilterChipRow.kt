package com.example.entify_again.ui.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.entify_again.ui.theme.CategoryColors
import com.example.entify_again.ui.viewmodel.FeedFilter

@Composable
fun FilterChipRow(
    activeFilter: FeedFilter,
    onFilterSelected: (FeedFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FeedFilter.entries.forEach { filter ->
            val chipColor = when (filter) {
                FeedFilter.COMMENT -> CategoryColors.Comment
                FeedFilter.LIKE -> CategoryColors.Like
                FeedFilter.FOLLOW -> CategoryColors.Follow
                FeedFilter.REMAKE -> CategoryColors.Remake
                FeedFilter.SHARE -> CategoryColors.Share
                FeedFilter.STARRED -> CategoryColors.Like
                FeedFilter.BLOCKED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            }
            FilterChip(
                selected = activeFilter == filter,
                onClick = { onFilterSelected(filter) },
                label = { Text(filter.label) },
                leadingIcon = when (filter) {
                    FeedFilter.STARRED -> {
                        { Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.padding(end = 2.dp)) }
                    }
                    FeedFilter.BLOCKED -> {
                        { Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.padding(end = 2.dp)) }
                    }
                    else -> null
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = chipColor.copy(alpha = 0.15f),
                    selectedLabelColor = chipColor
                )
            )
        }
    }
}
