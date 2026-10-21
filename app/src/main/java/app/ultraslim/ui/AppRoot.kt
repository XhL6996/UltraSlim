package app.ultraslim.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigationsuite.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.*
import androidx.navigation3.ui.NavDisplay
import app.ultraslim.ui.adaptive.WorkspaceFoldProvider
import app.ultraslim.ui.detail.DetailScreen
import app.ultraslim.ui.history.HistoryScreen
import app.ultraslim.ui.home.HomeScreen
import app.ultraslim.ui.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable private data object Home : NavKey
@Serializable private data object History : NavKey
@Serializable private data object Settings : NavKey
@Serializable private data class Detail(val id: Long, val fromHistory: Boolean) : NavKey

/** Each tab owns a saved back stack and ViewModel store, including while it isn't visible. */
@Composable
fun AppRoot() {
    WorkspaceFoldProvider { AppNavigation() }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun AppNavigation() {
    DeferredOutputHost()
    val replacementReview = AutoReplacementHost()
    val homeStack = rememberNavBackStack(Home)
    val historyStack = rememberNavBackStack(History)
    var historyTab by rememberSaveable { mutableStateOf(false) }
    var historySelecting by rememberSaveable { mutableStateOf(false) }
    val activeStack = if (historyTab) historyStack else homeStack
    val top = activeStack.last()
    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val suiteType = NavigationSuiteScaffoldDefaults.navigationSuiteType(adaptiveInfo)
    val rail = suiteType == NavigationSuiteType.WideNavigationRailCollapsed
    val suiteState = rememberNavigationSuiteScaffoldState()
    val showNavigation = !(historyTab && historySelecting) && (rail || top == Home || top == History)
    LaunchedEffect(showNavigation) { if (showNavigation) suiteState.show() else suiteState.hide() }

    fun back() {
        if (activeStack.size > 1) activeStack.removeLastOrNull()
        else historyTab = false
    }
    BackHandler(enabled = historyTab && activeStack.size == 1) { historyTab = false }
    val provider = entryProvider<NavKey> {
        entry<Home> {
            HomeScreen(
                replacementReview = replacementReview,
                onOpenSettings = { if (homeStack.last() != Settings) homeStack.add(Settings) },
                onOpenDetail = { homeStack.add(Detail(it, false)) },
            )
        }
        entry<History>(metadata = ListDetailSceneStrategy.listPane(detailPlaceholder = { DetailPlaceholder() })) {
            HistoryScreen(
                onOpenDetail = { id ->
                    // Choosing another photo replaces the detail, so Back always returns to the list.
                    while (historyStack.size > 1) historyStack.removeLastOrNull()
                    historyStack.add(Detail(id, true))
                },
                onSelectingChange = { historySelecting = it },
                activeHistoryId = (historyStack.lastOrNull() as? Detail)?.id,
            )
        }
        entry<Settings> { SettingsScreen(onBack = ::back) }
        entry<Detail>(metadata = ListDetailSceneStrategy.detailPane()) { detail ->
            DetailScreen(historyId = detail.id, onBack = ::back)
        }
    }
    val stacks = listOf(homeStack, historyStack)
    val decorated = stacks.mapIndexed { index, stack ->
        key(index) {
            rememberDecoratedNavEntries(
                backStack = stack,
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                entryProvider = provider,
            )
        }
    }
    NavigationSuiteScaffold(
        modifier = Modifier.testTag(if (rail) "navigation-rail" else "navigation-bar"),
        navigationSuiteType = suiteType,
        state = suiteState,
        containerColor = MaterialTheme.colorScheme.surface,
        navigationSuiteColors = NavigationSuiteDefaults.colors(
            wideNavigationRailColors = WideNavigationRailDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            navigationRailContainerColor = MaterialTheme.colorScheme.surface,
            shortNavigationBarContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),

        navigationItems = {
            NavigationSuiteItem(selected = !historyTab, onClick = { historyTab = false },
                icon = { Icon(Icons.Rounded.Compress, null) }, label = { Text("转换") })
            NavigationSuiteItem(selected = historyTab, onClick = { historyTab = true },
                icon = { Icon(Icons.Rounded.History, null) }, label = { Text("历史") })
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Decide against actual content width, after the navigation rail has taken its space.
            // Keep enough width for photo inspection; the strategy itself avoids physical hinges.
            val directive = calculatePaneScaffoldDirective(adaptiveInfo).copy(
                maxHorizontalPartitions = if (maxWidth >= 720.dp && maxHeight >= 480.dp && !adaptiveInfo.windowPosture.isTabletop) 2 else 1,
                horizontalPartitionSpacerSize = 16.dp,
            )
            val strategy = rememberListDetailSceneStrategy<NavKey>(shouldHandleSinglePaneLayout = true, directive = directive)
            NavDisplay(
                entries = decorated[if (historyTab) 1 else 0],
                onBack = ::back,
                sceneStrategies = if (historyTab) listOf(strategy) else emptyList(),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun DetailPlaceholder() {
    Surface(Modifier.fillMaxSize().padding(16.dp).testTag("detail-placeholder"),
        shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(24.dp))
            Text("选一张，看看省下多少", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text("在左侧选择成品，即可对比画质、查看参数或分享。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
