package app.ultraslim.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.ultraslim.ui.detail.DetailScreen
import app.ultraslim.ui.history.HistoryScreen
import app.ultraslim.ui.home.HomeScreen
import app.ultraslim.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val DETAIL = "detail/{historyId}"

    fun detail(historyId: Long) = "detail/$historyId"
}

private const val TRANSITION_MS = 320

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTopLevel = currentRoute == Routes.HOME || currentRoute == Routes.HISTORY
    val useRail = LocalConfiguration.current.screenWidthDp >= 840

    if (isTopLevel && useRail) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail {
                Spacer(Modifier.weight(1f))
                NavigationRailItem(
                    selected = currentRoute == Routes.HOME,
                    onClick = { navController.navigateTopLevel(Routes.HOME) },
                    icon = { Icon(Icons.Rounded.Compress, contentDescription = null) },
                    label = { Text("转换") },
                )
                NavigationRailItem(
                    selected = currentRoute == Routes.HISTORY,
                    onClick = { navController.navigateTopLevel(Routes.HISTORY) },
                    icon = { Icon(Icons.Rounded.History, contentDescription = null) },
                    label = { Text("历史") },
                )
                Spacer(Modifier.weight(1f))
            }
            AppNavHost(navController = navController)
        }
    } else {
        Scaffold(
            bottomBar = {
                if (isTopLevel) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = currentRoute == Routes.HOME,
                            onClick = { navController.navigateTopLevel(Routes.HOME) },
                            icon = { Icon(Icons.Rounded.Compress, contentDescription = null) },
                            label = { Text("转换") },
                        )
                        NavigationBarItem(
                            selected = currentRoute == Routes.HISTORY,
                            onClick = { navController.navigateTopLevel(Routes.HISTORY) },
                            icon = { Icon(Icons.Rounded.History, contentDescription = null) },
                            label = { Text("历史") },
                        )
                    }
                }
            },
        ) { padding ->
            AppNavHost(navController = navController, modifier = Modifier.padding(padding))
        }
    }
}

@Composable
private fun AppNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
        enterTransition = {
            slideInHorizontally(tween(TRANSITION_MS, easing = FastOutSlowInEasing)) { it / 5 } +
                fadeIn(tween(TRANSITION_MS))
        },
        exitTransition = {
            slideOutHorizontally(tween(TRANSITION_MS, easing = FastOutSlowInEasing)) { -it / 5 } +
                fadeOut(tween(TRANSITION_MS))
        },
        popEnterTransition = {
            slideInHorizontally(tween(TRANSITION_MS, easing = FastOutSlowInEasing)) { -it / 5 } +
                fadeIn(tween(TRANSITION_MS))
        },
        popExitTransition = {
            slideOutHorizontally(tween(TRANSITION_MS, easing = FastOutSlowInEasing)) { it / 5 } +
                fadeOut(tween(TRANSITION_MS))
        },
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenDetail = { historyId -> navController.navigate(Routes.detail(historyId)) },
            )
        }
        composable(Routes.HISTORY) {
            HistoryScreen(
                onOpenDetail = { historyId -> navController.navigate(Routes.detail(historyId)) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.DETAIL,
            arguments = listOf(navArgument("historyId") { type = NavType.LongType }),
        ) { entry ->
            val historyId = entry.arguments?.getLong("historyId") ?: 0L
            DetailScreen(
                historyId = historyId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
