package com.example.meupdf.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.meupdf.AppGraph
import com.example.meupdf.ui.auth.LoginScreen
import com.example.meupdf.ui.auth.LoginViewModel
import com.example.meupdf.ui.library.LibraryScreen
import com.example.meupdf.ui.library.LibraryViewModel
import com.example.meupdf.ui.reader.ReaderScreen
import com.example.meupdf.ui.reader.ReaderViewModel
import com.example.meupdf.ui.theme.MeuPDFTheme
import com.example.meupdf.ui.theme.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val ROUTE_LOGIN = "login"
private const val ROUTE_LIBRARY = "library"
private const val ROUTE_READER = "reader/{bookId}"

@Composable
fun MeuPdfNavHost(graph: AppGraph) {
    val navController = rememberNavController()
    val themeMode by graph.themePrefs.themeMode.collectAsState(initial = ThemeMode.PASTEL)
    val scope = rememberCoroutineScope()

    val loggedIn by produceState<Boolean?>(initialValue = null) {
        graph.cloudPrefs.loggedIn.collect { value = it }
    }

    // When logged in, jump straight back into whichever book/page was last open.
    LaunchedEffect(loggedIn) {
        if (loggedIn == true) {
            val lastBookId = graph.themePrefs.lastOpenBookId.first()
            if (lastBookId != null) {
                if (graph.libraryRepository.getBook(lastBookId) != null) {
                    navController.navigate("reader/$lastBookId")
                } else {
                    graph.themePrefs.setLastOpenBookId(null)
                }
            }
        }
    }

    DisposableEffect(navController) {
        val listener = NavController.OnDestinationChangedListener { _, destination, arguments ->
            val bookId = if (destination.route == ROUTE_READER) arguments?.getLong("bookId") else null
            scope.launch { graph.themePrefs.setLastOpenBookId(bookId) }
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }

    MeuPDFTheme(themeMode = themeMode) {
        when (loggedIn) {
            null -> Box(Modifier.fillMaxSize()) // brief splash while the session loads
            else -> NavHost(
                navController,
                startDestination = if (loggedIn == true) ROUTE_LIBRARY else ROUTE_LOGIN,
            ) {
                composable(ROUTE_LOGIN) {
                    val vm: LoginViewModel = viewModel(factory = LoginViewModel.Factory(graph.cloudApi))
                    LoginScreen(viewModel = vm, onLoggedIn = {
                        navController.navigate(ROUTE_LIBRARY) {
                            popUpTo(ROUTE_LOGIN) { inclusive = true }
                        }
                    })
                }
                composable(ROUTE_LIBRARY) {
                    val vm: LibraryViewModel = viewModel(
                        factory = LibraryViewModel.Factory(graph.libraryRepository, graph.syncManager, graph.cloudApi),
                    )
                    LibraryScreen(
                        viewModel = vm,
                        themeMode = themeMode,
                        onThemeModeChange = { mode -> scope.launch { graph.themePrefs.setThemeMode(mode) } },
                        onOpenBook = { bookId -> navController.navigate("reader/$bookId") },
                        onLoggedOut = {
                            navController.navigate(ROUTE_LOGIN) {
                                popUpTo(ROUTE_LIBRARY) { inclusive = true }
                            }
                        },
                    )
                }
                composable(
                    ROUTE_READER,
                    arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
                ) { backStackEntry ->
                    val bookId = backStackEntry.arguments?.getLong("bookId") ?: return@composable
                    val vm: ReaderViewModel = viewModel(
                        factory = ReaderViewModel.Factory(
                            bookId,
                            graph.libraryRepository,
                            graph.pdfEngine,
                            graph.dictionaryRepository,
                            graph.themePrefs,
                        ),
                    )
                    ReaderScreen(viewModel = vm, onBack = { navController.popBackStack() })
                }
            }
        }
    }
}
