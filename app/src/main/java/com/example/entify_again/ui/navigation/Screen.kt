package com.example.entify_again.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Archive
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Feed : Screen("feed", "알림", Icons.Default.Notifications)
    data object Filter : Screen("filter", "필터", Icons.Default.FilterList)
    data object Settings : Screen("settings", "설정", Icons.Default.Settings)
    data object Analytics : Screen("analytics", "분석", Icons.Default.QueryStats)
    data object Archive : Screen("archive", "영구 보관함", Icons.Default.Archive)

    companion object {
        val bottomBarScreens = listOf(Feed, Filter, Settings)
    }
}
