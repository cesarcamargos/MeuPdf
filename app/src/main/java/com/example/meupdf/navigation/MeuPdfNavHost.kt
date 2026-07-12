package com.example.meupdf.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.meupdf.AppGraph
import com.example.meupdf.ui.library.LibraryScreen
import com.example.meupdf.ui.library.LibraryViewModel
import com.example.meupdf.ui.reader.ReaderScreen
import com.example.meupdf.ui.reader.ReaderViewModel
import com.example.meupdf.ui.theme.MeuPDFTheme
import com.example.meupdf.ui.theme.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val ROUTE_LIBRARY = "library"
private const val ROUTE_READER = "reader/{bookId}"

@Composable
fun MeuPdfNavHost(graph: AppGraph) {
    val navController = rememberNavController()
    val themeMode by graph.themePrefs.themeMode.collectAsState(initial = ThemeMode.PASTEL)
    val scope = rememberCoroutineScope()

    // On cold start, jump straight back into whichever book/page was last open (on top of the
    // library, so back navigation still behaves normally), no matter how the app was closed.
    LaunchedEffect(Unit) {
        val lastBookId = graph.themePrefs.lastOpenBookId.first()
        if (lastBookId != null) {
            if (graph.libraryRepository.getBook(lastBookId) != null) {
                navController.navigate("reader/$lastBookId")
            } else {
                graph.themePrefs.setLastOpenBookId(null)
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
        NavHost(navController, startDestination = ROUTE_LIBRARY) {
            composable(ROUTE_LIBRARY) {
                val vm: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory(graph.libraryRepository))
                LibraryScreen(
                    viewModel = vm,
                    themeMode = themeMode,
                    onThemeModeChange = { mode -> scope.launch { graph.themePrefs.setThemeMode(mode) } },
                    onOpenBook = { bookId -> navController.navigate("reader/$bookId") },
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
