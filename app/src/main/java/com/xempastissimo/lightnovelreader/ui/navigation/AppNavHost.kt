package com.xempastissimo.lightnovelreader.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xempastissimo.lightnovelreader.ui.component.Motion
import com.xempastissimo.lightnovelreader.ui.screen.detail.BookDetailScreen
import com.xempastissimo.lightnovelreader.ui.screen.discover.DiscoverScreen
import com.xempastissimo.lightnovelreader.ui.screen.login.LoginScreen
import com.xempastissimo.lightnovelreader.ui.screen.login.WebLoginScreen
import com.xempastissimo.lightnovelreader.ui.screen.reader.ReaderScreen
import com.xempastissimo.lightnovelreader.ui.screen.search.SearchScreen
import com.xempastissimo.lightnovelreader.ui.screen.settings.SettingsScreen
import com.xempastissimo.lightnovelreader.ui.screen.shelf.ShelfScreen

/** Every route in the app, in one place so deep links and tests can reuse them. */
object Routes {
    const val DISCOVER = "discover"
    const val SHELF = "shelf"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val LOGIN = "login"
    const val WEB_LOGIN = "web-login"

    const val BOOK_DETAIL = "book/{bookId}"
    const val READER = "reader/{bookId}?chapterId={chapterId}"

    fun bookDetail(bookId: Int) = "book/$bookId"

    fun reader(bookId: Int, chapterId: Int? = null) =
        if (chapterId == null) "reader/$bookId?chapterId=-1" else "reader/$bookId?chapterId=$chapterId"
}

private data class BottomTab(val route: String, val label: String, val icon: ImageVector)

private val bottomTabs = listOf(
    BottomTab(Routes.DISCOVER, "发现", Icons.Filled.List),
    BottomTab(Routes.SHELF, "书架", Icons.Filled.Star),
    BottomTab(Routes.SEARCH, "搜索", Icons.Filled.Search),
    BottomTab(Routes.SETTINGS, "设置", Icons.Filled.Settings),
)

// ---------------------------------------------------------------------------
// Route transitions.
//
// One set per *kind* of move, so the animation says what happened rather than
// merely decorating it: a fade between peer tabs, a push/pop from the trailing
// edge for a detail opened on top of a list, a takeover for the reader, and a
// sheet-like rise for the login tasks.
// ---------------------------------------------------------------------------

/** Peer tabs: same level in the hierarchy, so nothing slides — it just fades. */
private val TabEnter: EnterTransition = fadeIn(animationSpec = tween(Motion.ENTER_MILLIS))
private val TabExit: ExitTransition = fadeOut(animationSpec = tween(Motion.EXIT_MILLIS))

/** A detail pushed on top of a list: arrives from the trailing edge. */
private val PushEnter: EnterTransition =
    slideInHorizontally(
        animationSpec = tween(Motion.ENTER_MILLIS, easing = LinearOutSlowInEasing),
    ) { width -> width / 4 } + fadeIn(animationSpec = tween(Motion.ENTER_MILLIS))
private val PushExit: ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(Motion.EXIT_MILLIS, easing = FastOutLinearInEasing),
    ) { width -> -width / 4 } + fadeOut(animationSpec = tween(Motion.EXIT_MILLIS))

/** Popping that detail off again: the list comes back from the leading edge. */
private val PopEnter: EnterTransition =
    slideInHorizontally(
        animationSpec = tween(Motion.ENTER_MILLIS, easing = LinearOutSlowInEasing),
    ) { width -> -width / 4 } + fadeIn(animationSpec = tween(Motion.ENTER_MILLIS))
private val PopExit: ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(Motion.EXIT_MILLIS, easing = FastOutLinearInEasing),
    ) { width -> width / 4 } + fadeOut(animationSpec = tween(Motion.EXIT_MILLIS))

/**
 * The reader takes over the whole display. A horizontal slide would fight the
 * page-curl-ish gesture the pager already owns, so it instead swells into place.
 */
private val ReaderEnter: EnterTransition =
    fadeIn(animationSpec = tween(Motion.ENTER_MILLIS)) +
        scaleIn(
            animationSpec = tween(Motion.ENTER_MILLIS, easing = LinearOutSlowInEasing),
            initialScale = 0.97f,
        )
private val ReaderExit: ExitTransition =
    fadeOut(animationSpec = tween(Motion.EXIT_MILLIS)) +
        scaleOut(
            animationSpec = tween(Motion.EXIT_MILLIS),
            targetScale = 1.02f,
        )

/** Login tasks: they rise from the bottom edge like the sheets they resemble. */
private val TaskEnter: EnterTransition =
    slideInVertically(
        animationSpec = tween(Motion.ENTER_MILLIS, easing = LinearOutSlowInEasing),
    ) { height -> height / 3 } + fadeIn(animationSpec = tween(Motion.ENTER_MILLIS))
private val TaskExit: ExitTransition =
    slideOutVertically(animationSpec = tween(Motion.EXIT_MILLIS)) { height -> height / 3 } +
        fadeOut(animationSpec = tween(Motion.EXIT_MILLIS))

/**
 * App navigation.
 *
 * The reader is a full-screen route: the bottom bar is hidden there so the page
 * gets the whole display, which is what a reading surface needs.
 */
@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = bottomTabs.any { tab ->
        backStackEntry?.destination?.hierarchy?.any { it.route == tab.route } == true
    }

    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(
                    animationSpec = tween(Motion.ENTER_MILLIS, easing = LinearOutSlowInEasing),
                ) { height -> height } + fadeIn(animationSpec = tween(Motion.ENTER_MILLIS)),
                exit = slideOutVertically(
                    animationSpec = tween(Motion.EXIT_MILLIS, easing = FastOutLinearInEasing),
                ) { height -> height } + fadeOut(animationSpec = tween(Motion.EXIT_MILLIS)),
            ) {
                NavigationBar {
                    bottomTabs.forEach { tab ->
                        val selected = backStackEntry?.destination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (currentRoute == tab.route) return@NavigationBarItem
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.DISCOVER,
            modifier = Modifier.padding(
                bottom = if (showBottomBar) innerPadding.calculateBottomPadding() else androidx.compose.ui.unit.Dp.Unspecified,
            ),
            enterTransition = { TabEnter },
            exitTransition = { TabExit },
            popEnterTransition = { TabEnter },
            popExitTransition = { TabExit },
        ) {
            composable(Routes.DISCOVER) {
                DiscoverScreen(
                    onOpenBook = { bookId -> navController.navigate(Routes.bookDetail(bookId)) },
                    onOpenSearch = { navController.navigate(Routes.SEARCH) },
                )
            }
            composable(Routes.SHELF) {
                ShelfScreen(
                    onOpenBook = { bookId -> navController.navigate(Routes.bookDetail(bookId)) },
                    onContinueReading = { bookId, chapterId -> navController.navigate(Routes.reader(bookId, chapterId)) },
                    onOpenSearch = { navController.navigate(Routes.SEARCH) },
                    onOpenLogin = { navController.navigate(Routes.LOGIN) },
                )
            }
            composable(Routes.SEARCH) {
                SearchScreen(onOpenBook = { bookId -> navController.navigate(Routes.bookDetail(bookId)) })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onOpenLogin = { navController.navigate(Routes.LOGIN) })
            }
            composable(
                Routes.LOGIN,
                enterTransition = { TaskEnter },
                exitTransition = { TaskExit },
                popEnterTransition = { TaskEnter },
                popExitTransition = { TaskExit },
            ) {
                LoginScreen(
                    onLoggedIn = { navController.popBackStack() },
                    onOpenWebLogin = { navController.navigate(Routes.WEB_LOGIN) },
                )
            }
            composable(
                Routes.WEB_LOGIN,
                enterTransition = { TaskEnter },
                exitTransition = { TaskExit },
                popEnterTransition = { TaskEnter },
                popExitTransition = { TaskExit },
            ) {
                WebLoginScreen(onDone = { navController.popBackStack() })
            }
            composable(
                route = Routes.BOOK_DETAIL,
                arguments = listOf(navArgument("bookId") { type = NavType.IntType }),
                enterTransition = { PushEnter },
                exitTransition = { PushExit },
                popEnterTransition = { PopEnter },
                popExitTransition = { PopExit },
            ) { entry ->
                val bookId = entry.arguments?.getInt("bookId") ?: return@composable
                BookDetailScreen(
                    bookId = bookId,
                    onBack = { navController.popBackStack() },
                    onRead = { chapterId -> navController.navigate(Routes.reader(bookId, chapterId)) },
                )
            }
            composable(
                route = Routes.READER,
                arguments = listOf(
                    navArgument("bookId") { type = NavType.IntType },
                    navArgument("chapterId") { type = NavType.IntType; defaultValue = -1 },
                ),
                enterTransition = { ReaderEnter },
                exitTransition = { ReaderExit },
                popEnterTransition = { ReaderEnter },
                popExitTransition = { ReaderExit },
            ) { entry ->
                val bookId = entry.arguments?.getInt("bookId") ?: return@composable
                val chapterId = entry.arguments?.getInt("chapterId") ?: -1
                ReaderScreen(
                    bookId = bookId,
                    startChapterId = chapterId,
                    onBack = { navController.popBackStack() },
                    onOpenChapters = { navController.popBackStack() },
                )
            }
        }
    }
}
