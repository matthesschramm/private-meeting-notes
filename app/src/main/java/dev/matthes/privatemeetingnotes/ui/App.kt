package dev.matthes.privatemeetingnotes.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.matthes.privatemeetingnotes.AppContainer
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable object MeetingsRoute
@Serializable object AskRoute
@Serializable object LibraryRoute
@Serializable object SettingsRoute
@Serializable object RecordRoute
@Serializable data class MeetingRoute(val id: String, val atMs: Long = -1)
@Serializable data class DocumentRoute(val id: String, val page: Int = -1)

private data class TopLevel(val label: String, val icon: ImageVector, val route: Any, val type: KClass<*>)

@Composable
fun App(container: AppContainer) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val tabs = listOf(
        TopLevel("Meetings", Icons.AutoMirrored.Outlined.ViewList, MeetingsRoute, MeetingsRoute::class),
        TopLevel("Ask", Icons.Outlined.QuestionAnswer, AskRoute, AskRoute::class),
        TopLevel("Library", Icons.AutoMirrored.Outlined.LibraryBooks, LibraryRoute, LibraryRoute::class),
        TopLevel("Settings", Icons.Outlined.Settings, SettingsRoute, SettingsRoute::class),
    )
    val onTab = tabs.any { tab -> destination?.hasRoute(tab.type) == true }
    val openMeeting: (String, Long?) -> Unit = { id, ms -> nav.navigate(MeetingRoute(id, ms ?: -1)) }
    val openDocument: (String, Int?) -> Unit = { id, page -> nav.navigate(DocumentRoute(id, page ?: -1)) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (onTab) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = destination?.hierarchy?.any { it.hasRoute(tab.type) } == true,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = MeetingsRoute) {
            composable<MeetingsRoute> {
                MeetingsScreen(
                    viewModel { MeetingsViewModel(container) },
                    onOpen = { nav.navigate(MeetingRoute(it)) },
                    onRecord = { nav.navigate(RecordRoute) },
                    onSettings = { nav.navigate(SettingsRoute) },
                    padding = padding,
                )
            }
            composable<AskRoute> { AskScreen(viewModel { AskViewModel(container, null) }, padding, openMeeting, openDocument) }
            composable<LibraryRoute> { LibraryScreen(viewModel { LibraryViewModel(container) }, padding) { nav.navigate(DocumentRoute(it)) } }
            composable<SettingsRoute> { SettingsScreen(viewModel { SettingsViewModel(container) }, padding) }
            composable<RecordRoute> {
                RecordScreen(viewModel { RecordViewModel(container) }, onBack = { nav.popBackStack() }) { meetingId ->
                    nav.popBackStack()
                    nav.navigate(MeetingRoute(meetingId))
                }
            }
            composable<MeetingRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<MeetingRoute>()
                val meeting = viewModel(key = route.id) { MeetingViewModel(container, route.id) }
                val ask = viewModel(key = "ask-${route.id}") { meeting.askModel() }
                MeetingScreen(
                    meeting,
                    onBack = { nav.popBackStack() },
                    openAtMs = route.atMs.takeIf { it >= 0 },
                    extraTabs = remember(route.id) { listOf("Ask" to @Composable { AskPanel(ask, global = false, openMeeting, openDocument) }) },
                )
            }
            composable<DocumentRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<DocumentRoute>()
                DocumentScreen(container, route.id, route.page.takeIf { it > 0 }) { nav.popBackStack() }
            }
        }
    }
}
