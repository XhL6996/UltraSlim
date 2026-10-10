package app.ultraslim.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import app.ultraslim.ui.theme.Motion

object Routes {
    const val HOME = "home"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val DETAIL = "detail/{historyId}"

    fun detail(historyId: Long) = "detail/$historyId"
}

private val topLevelRoutes = setOf(Routes.HOME, Routes.HISTORY)

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTopLevel = currentRoute in topLevelRoutes
    // 历史选择模式有自己的底部操作栏，主导航先让位，避免双底栏。
    var historySelecting by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val useRail = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width / androidx.compose.ui.platform.LocalDensity.current.density >= 840

    if (useRail) {
        // 宽屏下 rail 常驻：推入设置/详情时不再整列消失。
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
            AppNavHost(navController = navController, modifier = Modifier.weight(1f), onSelectingChange = { historySelecting = it })
        }
    } else {
        Scaffold(
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            bottomBar = {
                AnimatedVisibility(
                    visible = isTopLevel && !historySelecting,
                    enter = slideInVertically(Motion.emphasized()) { it } + fadeIn(Motion.emphasized()),
                    exit = slideOutVertically(Motion.exit(Motion.Emphasized)) { it } + fadeOut(Motion.exit(Motion.Emphasized)),
                ) {
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
            AppNavHost(navController = navController, modifier = Modifier.padding(padding), onSelectingChange = { historySelecting = it })
        }
    }
}

@Composable
private fun AppNavHost(navController: NavHostController, modifier: Modifier = Modifier, onSelectingChange: (Boolean) -> Unit) {
    // 顶级 Tab 之间用 fade-through（轻微缩放淡换）；进出 设置/详情 保留横向推入。
    fun bothTopLevel(initial: androidx.navigation.NavBackStackEntry, target: androidx.navigation.NavBackStackEntry) =
        initial.destination.route in topLevelRoutes && target.destination.route in topLevelRoutes
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
        enterTransition = {
            if (bothTopLevel(initialState, targetState))
                fadeIn(tween(Motion.Emphasized, easing = Motion.StandardEasing)) +
                    scaleIn(tween(Motion.Emphasized, easing = Motion.StandardEasing), initialScale = 0.94f)
            else
                slideInHorizontally(tween(Motion.Emphasized, easing = Motion.StandardEasing)) { it / 5 } +
                    fadeIn(tween(Motion.Emphasized))
        },
        exitTransition = {
            if (bothTopLevel(initialState, targetState))
                fadeOut(tween(Motion.Emphasized, easing = Motion.StandardEasing)) +
                    scaleOut(tween(Motion.Emphasized, easing = Motion.StandardEasing), targetScale = 0.94f)
            else
                slideOutHorizontally(tween(Motion.Emphasized, easing = Motion.StandardEasing)) { -it / 5 } +
                    fadeOut(tween(Motion.Emphasized))
        },
        popEnterTransition = {
            if (bothTopLevel(initialState, targetState))
                fadeIn(tween(Motion.Emphasized, easing = Motion.StandardEasing)) +
                    scaleIn(tween(Motion.Emphasized, easing = Motion.StandardEasing), initialScale = 0.94f)
            else
                slideInHorizontally(tween(Motion.Emphasized, easing = Motion.StandardEasing)) { -it / 5 } +
                    fadeIn(tween(Motion.Emphasized))
        },
        popExitTransition = {
            if (bothTopLevel(initialState, targetState))
                fadeOut(tween(Motion.Emphasized, easing = Motion.StandardEasing)) +
                    scaleOut(tween(Motion.Emphasized, easing = Motion.StandardEasing), targetScale = 0.94f)
            else
                slideOutHorizontally(tween(Motion.Emphasized, easing = Motion.StandardEasing)) { it / 5 } +
                    fadeOut(tween(Motion.Emphasized))
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
                onSelectingChange = onSelectingChange,
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
