package com.example.meupdf.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.meupdf.ui.theme.GlassPanel
import com.example.meupdf.ui.theme.LocalGlassPalette
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(viewModel: ReaderViewModel, onBack: () -> Unit) {
    val palette = LocalGlassPalette.current
    val book by viewModel.book.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    var showBookmarks by remember { mutableStateOf(false) }
    var showColorModeDialog by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        if (viewModel.loadError) {
            Text(
                "Could not open this PDF.",
                color = palette.textPrimary,
                modifier = Modifier.align(Alignment.Center),
            )
        } else if (viewModel.isLoading || book == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = palette.accentLavender,
            )
        } else {
            PageArea(viewModel)
        }

        AnimatedVisibility(
            visible = viewModel.controlsVisible,
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            TopControls(
                title = book?.title.orEmpty(),
                isBookmarked = bookmarks.any { it.page == viewModel.currentPage },
                pageColorMode = viewModel.pageColorMode,
                onBack = onBack,
                onIncreaseFont = viewModel::increaseFont,
                onDecreaseFont = viewModel::decreaseFont,
                onToggleBookmark = viewModel::toggleBookmarkForCurrentPage,
                onShowBookmarks = { showBookmarks = true },
                onShowColorMode = { showColorModeDialog = true },
            )
        }

        AnimatedVisibility(
            visible = viewModel.controlsVisible,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            BottomPageSlider(
                currentPage = viewModel.currentPage,
                pageCount = viewModel.pageCount,
                onPageChange = viewModel::goToPage,
            )
        }

        viewModel.definitionState?.let { state ->
            DefinitionPopup(state = state, onDismiss = viewModel::dismissDefinition)
        }
    }

    if (showBookmarks) {
        BookmarksDialog(
            bookmarks = bookmarks,
            onDismiss = { showBookmarks = false },
            onJump = { viewModel.jumpToBookmark(it); showBookmarks = false },
            onDelete = viewModel::deleteBookmark,
        )
    }

    if (showColorModeDialog) {
        ColorModeDialog(
            current = viewModel.pageColorMode,
            onSelect = { viewModel.changePageColorMode(it); showColorModeDialog = false },
            onDismiss = { showColorModeDialog = false },
        )
    }
}

private val InvertColorMatrix = ColorMatrix(
    floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    ),
)

private val SepiaColorMatrix = ColorMatrix(
    floatArrayOf(
        0.393f, 0.769f, 0.189f, 0f, 0f,
        0.349f, 0.686f, 0.168f, 0f, 0f,
        0.272f, 0.534f, 0.131f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    ),
)

@Composable
private fun PageArea(viewModel: ReaderViewModel) {
    val density = LocalDensity.current
    val colorMode = viewModel.pageColorMode
    val pageBackground = when (colorMode) {
        PageColorMode.NORMAL -> Color.White
        PageColorMode.DARK -> Color.Black
        PageColorMode.SEPIA -> Color(0xFFF4ECD8)
    }
    val pageColorFilter = when (colorMode) {
        PageColorMode.NORMAL -> null
        PageColorMode.DARK -> ColorFilter.colorMatrix(InvertColorMatrix)
        PageColorMode.SEPIA -> ColorFilter.colorMatrix(SepiaColorMatrix)
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(pageBackground)) {
        val baseWidthPx = with(density) { maxWidth.toPx() }.roundToInt()
        val baseHeightPx = with(density) { maxHeight.toPx() }.roundToInt()
        val fontScale = viewModel.fontScale
        val aspect = viewModel.pageAspectRatio()
        // Fit the whole page inside the available area first, then scale that
        // baseline by fontScale so pinch/zoom enlarges from a page that already fits.
        val fitWidthPx = minOf(baseWidthPx, (baseHeightPx * aspect).roundToInt()).coerceAtLeast(1)
        val renderWidthPx = (fitWidthPx * fontScale).roundToInt().coerceAtLeast(1)
        val renderHeightPx = (renderWidthPx / aspect).roundToInt().coerceAtLeast(1)

        val bitmapState = produceState<android.graphics.Bitmap?>(
            initialValue = null,
            key1 = viewModel.currentPage,
            key2 = renderWidthPx,
        ) {
            value = viewModel.renderPageBitmap(viewModel.currentPage, renderWidthPx, renderHeightPx)
        }

        val hScroll = rememberScrollState()
        val vScroll = rememberScrollState()

        Box(
            Modifier
                .fillMaxSize()
                .then(if (fontScale > 1f) Modifier.horizontalScroll(hScroll).verticalScroll(vScroll) else Modifier)
                .pointerInput(baseWidthPx) {
                    detectTapGestures(
                        onTap = { offset ->
                            when {
                                offset.x < baseWidthPx * 0.2f -> viewModel.prevPage()
                                offset.x > baseWidthPx * 0.8f -> viewModel.nextPage()
                                else -> viewModel.toggleControls()
                            }
                        },
                        onDoubleTap = { viewModel.scaleFont(1.1f) },
                        onLongPress = { offset ->
                            viewModel.onWordLongPress(offset.x, offset.y, renderWidthPx, renderHeightPx)
                        },
                    )
                },
        ) {
            bitmapState.value?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    colorFilter = pageColorFilter,
                    modifier = Modifier.width(with(density) { renderWidthPx.toDp() }),
                )
            }
        }
    }
}

@Composable
private fun TopControls(
    title: String,
    isBookmarked: Boolean,
    pageColorMode: PageColorMode,
    onBack: () -> Unit,
    onIncreaseFont: () -> Unit,
    onDecreaseFont: () -> Unit,
    onToggleBookmark: () -> Unit,
    onShowBookmarks: () -> Unit,
    onShowColorMode: () -> Unit,
) {
    val palette = LocalGlassPalette.current
    GlassPanel(modifier = Modifier.fillMaxWidth().padding(12.dp), cornerRadius = 18.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = palette.textPrimary)
            }
            Text(
                title,
                color = palette.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            IconButton(onClick = onDecreaseFont) {
                Icon(Icons.Filled.TextDecrease, contentDescription = "Smaller text", tint = palette.textPrimary)
            }
            IconButton(onClick = onIncreaseFont) {
                Icon(Icons.Filled.TextIncrease, contentDescription = "Larger text", tint = palette.textPrimary)
            }
            IconButton(onClick = onShowColorMode) {
                Icon(
                    when (pageColorMode) {
                        PageColorMode.NORMAL -> Icons.Filled.LightMode
                        PageColorMode.DARK -> Icons.Filled.DarkMode
                        PageColorMode.SEPIA -> Icons.Filled.MenuBook
                    },
                    contentDescription = "Page color mode",
                    tint = palette.textPrimary,
                )
            }
            IconButton(onClick = onShowBookmarks) {
                Icon(Icons.Filled.List, contentDescription = "Bookmarks", tint = palette.textPrimary)
            }
            IconButton(onClick = onToggleBookmark) {
                Icon(
                    if (isBookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    contentDescription = "Toggle bookmark",
                    tint = palette.accentPink,
                )
            }
        }
    }
}

@Composable
private fun ColorModeDialog(current: PageColorMode, onSelect: (PageColorMode) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Page color") },
        text = {
            Column {
                listOf(
                    PageColorMode.NORMAL to "Normal",
                    PageColorMode.DARK to "Dark",
                    PageColorMode.SEPIA to "Sepia",
                ).forEach { (mode, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(mode) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, modifier = Modifier.weight(1f))
                        if (mode == current) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        }
                    }
                }
            }
        },
        confirmButton = { Text("Close", modifier = Modifier.padding(12.dp).clickable(onClick = onDismiss)) },
    )
}

@Composable
private fun BottomPageSlider(currentPage: Int, pageCount: Int, onPageChange: (Int) -> Unit) {
    val palette = LocalGlassPalette.current
    GlassPanel(modifier = Modifier.fillMaxWidth().padding(12.dp), cornerRadius = 18.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "Page ${currentPage + 1} of ${pageCount.coerceAtLeast(1)}",
                color = palette.textSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = currentPage.toFloat(),
                onValueChange = { onPageChange(it.roundToInt()) },
                valueRange = 0f..((pageCount - 1).coerceAtLeast(0)).toFloat(),
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = palette.accentLavender,
                    activeTrackColor = palette.accentLavender,
                ),
            )
        }
    }
}

@Composable
private fun DefinitionPopup(state: DefinitionUiState, onDismiss: () -> Unit) {
    val palette = LocalGlassPalette.current
    Box(
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.15f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        GlassPanel(modifier = Modifier.widthIn(max = 320.dp).padding(24.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text(state.word, color = palette.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.size(8.dp))
                when {
                    state.loading -> CircularProgressIndicator(modifier = Modifier.size(20.dp), color = palette.accentLavender)
                    state.definition == null -> Text("No definition found.", color = palette.textSecondary)
                    else -> {
                        Text(state.definition.partOfSpeech, color = palette.textSecondary, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.size(4.dp))
                        Text(state.definition.definition, color = palette.textPrimary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarksDialog(
    bookmarks: List<com.example.meupdf.data.db.BookmarkEntity>,
    onDismiss: () -> Unit,
    onJump: (com.example.meupdf.data.db.BookmarkEntity) -> Unit,
    onDelete: (com.example.meupdf.data.db.BookmarkEntity) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bookmarks") },
        text = {
            Column {
                if (bookmarks.isEmpty()) {
                    Text("No bookmarks yet. Tap the bookmark icon to save this page.")
                }
                bookmarks.forEach { bookmark ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onJump(bookmark) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(bookmark.label, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onDelete(bookmark) }) {
                            Icon(Icons.Filled.BookmarkBorder, contentDescription = "Remove bookmark")
                        }
                    }
                }
            }
        },
        confirmButton = { Text("Close", modifier = Modifier.padding(12.dp).clickable(onClick = onDismiss)) },
    )
}
