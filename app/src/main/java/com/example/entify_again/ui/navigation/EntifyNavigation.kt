package com.example.entify_again.ui.navigation

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.entify_again.ui.screen.AnalyticsScreen
import com.example.entify_again.ui.screen.NotificationArchiveScreen
import com.example.entify_again.ui.screen.FilterManageScreen
import com.example.entify_again.ui.screen.NotificationFeedScreen
import com.example.entify_again.ui.screen.SettingsScreen

@Composable
fun EntifyMainScreen(
    context: Context,
    onLogout: () -> Unit,
    onNavigateToLogin: () -> Unit = {}
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                Screen.bottomBarScreens.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Feed.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Feed.route) {
                NotificationFeedScreen(
                    context = context,
                    onNavigateToAnalytics = { navController.navigate(Screen.Analytics.route) }
                )
            }
            composable(Screen.Filter.route) {
                FilterManageScreen(context = context)
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    context = context,
                    onLogout = onLogout,
                    onNavigateToLogin = onNavigateToLogin,
                    onNavigateToArchive = { navController.navigate(Screen.Archive.route) }
                )
            }
            composable(Screen.Archive.route) {
                NotificationArchiveScreen(context = context, onNavigateBack = { navController.popBackStack() })
            }
            composable(Screen.Analytics.route) {
                AnalyticsScreen(
                    context = context,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}
