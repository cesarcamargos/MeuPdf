package com.example.meupdf.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.meupdf.data.db.BookEntity
import com.example.meupdf.data.db.SectionEntity
import com.example.meupdf.ui.common.rememberCoverBitmap
import com.example.meupdf.ui.theme.GlassPanel
import com.example.meupdf.ui.theme.LocalGlassPalette
import com.example.meupdf.ui.theme.ThemeMode
import kotlin.math.roundToInt

private data class SectionGroup(val name: String, val sectionId: Long?, val books: List<BookEntity>)

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onOpenBook: (Long) -> Unit,
    onLoggedOut: () -> Unit,
) {
    val palette = LocalGlassPalette.current
    val sections by viewModel.sections.collectAsState()
    val books by viewModel.books.collectAsState()

    var showAddSection by remember { mutableStateOf(false) }
    var bookForOptions by remember { mutableStateOf<BookEntity?>(null) }
    var sectionForOptions by remember { mutableStateOf<SectionGroup?>(null) }
    var pendingImportSectionId by remember { mutableStateOf<Long?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importPdf(it, pendingImportSectionId) }
    }
    fun launchImport(sectionId: Long?) {
        pendingImportSectionId = sectionId
        importLauncher.launch(arrayOf("application/pdf"))
    }

    // The cloud delivers other devices' changes asynchronously; re-sync whenever we return to front.
    LifecycleResumeEffect(Unit) {
        viewModel.sync()
        onPauseOrDispose { }
    }

    // Drag-to-move-between-sections state.
    var draggedBook by remember { mutableStateOf<BookEntity?>(null) }
    var dragPositionRoot by remember { mutableStateOf(Offset.Zero) }
    var overlayRootOffset by remember { mutableStateOf(Offset.Zero) }
    val sectionBounds = remember { mutableStateMapOf<Long?, Rect>() }
    val hoveredEntry = if (draggedBook != null) {
        sectionBounds.entries.firstOrNull { it.value.contains(dragPositionRoot) }
    } else {
        null
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { overlayRootOffset = it.positionInRoot() }) {
    Column(Modifier.fillMaxSize()) {
        GlassPanel(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "My Library",
                    style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                    color = palette.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    onThemeModeChange(if (themeMode == ThemeMode.PASTEL) ThemeMode.DARK else ThemeMode.PASTEL)
                }) {
                    Icon(
                        if (themeMode == ThemeMode.PASTEL) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                        contentDescription = "Toggle theme",
                        tint = palette.textPrimary,
                    )
                }
                IconButton(onClick = { showAddSection = true }) {
                    Icon(Icons.Filled.CreateNewFolder, contentDescription = "New section", tint = palette.textPrimary)
                }
                IconButton(onClick = { launchImport(null) }) {
                    Icon(Icons.Filled.Add, contentDescription = "Import PDF", tint = palette.textPrimary)
                }
                IconButton(onClick = { viewModel.logout(onLoggedOut) }) {
                    Icon(Icons.Filled.Logout, contentDescription = "Log out", tint = palette.textPrimary)
                }
            }
        }

        val grouped = remember(sections, books) {
            val bySection = books.groupBy { it.sectionId }
            val result = mutableListOf<SectionGroup>()
            sections.forEach { section ->
                result += SectionGroup(section.name, section.id, bySection[section.id].orEmpty())
            }
            result += SectionGroup("Uncategorized", null, bySection[null].orEmpty())
            result
        }

        if (sections.isEmpty() && books.isEmpty()) {
            EmptyLibraryState(onAddBook = { launchImport(null) })
        } else {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            items(grouped, key = { it.sectionId ?: -1L }) { group ->
                val isHovered = hoveredEntry != null && hoveredEntry.key == group.sectionId
                Column(
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { sectionBounds[group.sectionId] = it.boundsInRoot() }
                        .background(
                            if (isHovered) palette.accentLavender.copy(alpha = 0.25f) else Color.Transparent,
                            RoundedCornerShape(12.dp),
                        ),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 16.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            group.name,
                            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                            color = palette.textPrimary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { launchImport(group.sectionId) }) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = "Add book to ${group.name}",
                                tint = palette.textSecondary,
                            )
                        }
                        if (group.sectionId != null) {
                            IconButton(onClick = { sectionForOptions = group }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = "Section options for ${group.name}",
                                    tint = palette.textSecondary,
                                )
                            }
                        }
                    }
                    if (group.books.isEmpty()) {
                        Text(
                            "No books yet",
                            color = palette.textSecondary,
                            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
                        )
                    } else {
                        FlowRow(
                            Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            group.books.forEach { book ->
                                BookCoverCard(
                                    book = book,
                                    isDragging = draggedBook?.id == book.id,
                                    onClick = { onOpenBook(book.id) },
                                    onOptions = { bookForOptions = book },
                                    onDragStart = { rootOffset ->
                                        draggedBook = book
                                        dragPositionRoot = rootOffset
                                    },
                                    onDrag = { delta -> dragPositionRoot += delta },
                                    onDragEnd = {
                                        hoveredEntry?.let { entry ->
                                            if (entry.key != book.sectionId) {
                                                viewModel.moveBookToSection(book.id, entry.key)
                                            }
                                        }
                                        draggedBook = null
                                    },
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
        }
    }

    draggedBook?.let { book ->
        val local = dragPositionRoot - overlayRootOffset
        Box(
            Modifier
                .offset { IntOffset((local.x - 55.dp.toPx()).roundToInt(), (local.y - 70.dp.toPx()).roundToInt()) }
                .size(width = 110.dp, height = 30.dp)
                .graphicsLayer { alpha = 0.9f },
        ) {
            GlassPanel(cornerRadius = 10.dp) {
                Text(
                    book.title,
                    color = palette.textPrimary,
                    maxLines = 1,
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
    }

    if (showAddSection) {
        AddSectionDialog(
            onDismiss = { showAddSection = false },
            onConfirm = { name -> viewModel.createSection(name); showAddSection = false },
        )
    }

    bookForOptions?.let { book ->
        BookOptionsDialog(
            book = book,
            sections = sections,
            onDismiss = { bookForOptions = null },
            onMoveToSection = { sectionId -> viewModel.moveBookToSection(book.id, sectionId); bookForOptions = null },
            onDelete = { viewModel.deleteBook(book); bookForOptions = null },
        )
    }

    sectionForOptions?.let { group ->
        val sectionId = group.sectionId
        if (sectionId != null) {
            SectionOptionsDialog(
                currentName = group.name,
                onRename = { newName -> viewModel.renameSection(sectionId, newName); sectionForOptions = null },
                onDelete = { viewModel.deleteSection(sectionId); sectionForOptions = null },
                onDismiss = { sectionForOptions = null },
            )
        }
    }
}

@Composable
private fun EmptyLibraryState(onAddBook: () -> Unit) {
    val palette = LocalGlassPalette.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.Book,
                contentDescription = null,
                tint = palette.textSecondary,
                modifier = Modifier.height(64.dp).width(64.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Your library is empty",
                color = palette.textPrimary,
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Add a PDF to get started",
                color = palette.textSecondary,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAddBook) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add PDF")
            }
        }
    }
}

@Composable
private fun BookCoverCard(
    book: BookEntity,
    isDragging: Boolean,
    onClick: () -> Unit,
    onOptions: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    val palette = LocalGlassPalette.current
    val cover = rememberCoverBitmap(book.coverPath)
    var rootPosition by remember { mutableStateOf(Offset.Zero) }
    Column(
        Modifier
            .width(110.dp)
            .onGloballyPositioned { rootPosition = it.positionInRoot() }
            .graphicsLayer { alpha = if (isDragging) 0.35f else 1f }
            .clickable(onClick = onClick)
            .pointerInput(book.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset -> onDragStart(rootPosition + offset) },
                    onDrag = { change, dragAmount -> change.consume(); onDrag(dragAmount) },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                )
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(14.dp))
                .background(palette.accentLavender.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            if (cover != null) {
                Image(
                    bitmap = cover,
                    contentDescription = book.title,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
                )
            } else {
                Icon(Icons.Filled.Book, contentDescription = null, tint = palette.textSecondary)
            }
            IconButton(
                onClick = onOptions,
                modifier = Modifier.align(Alignment.TopEnd).size(28.dp),
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "Book options",
                    tint = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            book.title,
            color = palette.textPrimary,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            maxLines = 2,
        )
    }
}

@Composable
private fun AddSectionDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New section") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("e.g. Adventure") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
        },
        confirmButton = { Text("Create", modifier = Modifier.padding(12.dp).clickable { onConfirm(name) }) },
        dismissButton = { Text("Cancel", modifier = Modifier.padding(12.dp).clickable(onClick = onDismiss)) },
    )
}

@Composable
private fun SectionOptionsDialog(
    currentName: String,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (confirmDelete) "Delete section?" else "Section") },
        text = {
            if (confirmDelete) {
                Text("\"$currentName\" will be removed. Its books stay in your library (moved to Uncategorized).")
            } else {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text("Name") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Delete section",
                        color = Color(0xFFC44D4D),
                        modifier = Modifier.fillMaxWidth().clickable { confirmDelete = true }.padding(vertical = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (confirmDelete) {
                Text("Delete", color = Color(0xFFC44D4D), modifier = Modifier.padding(12.dp).clickable(onClick = onDelete))
            } else {
                Text("Save", modifier = Modifier.padding(12.dp).clickable { if (name.isNotBlank()) onRename(name) })
            }
        },
        dismissButton = {
            Text(
                if (confirmDelete) "Cancel" else "Close",
                modifier = Modifier.padding(12.dp).clickable { if (confirmDelete) confirmDelete = false else onDismiss() },
            )
        },
    )
}

@Composable
private fun BookOptionsDialog(
    book: BookEntity,
    sections: List<SectionEntity>,
    onDismiss: () -> Unit,
    onMoveToSection: (Long?) -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(book.title) },
        text = {
            Column {
                Text("Move to section:", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Uncategorized",
                    modifier = Modifier.fillMaxWidth().clickable { onMoveToSection(null) }.padding(vertical = 8.dp),
                )
                sections.forEach { section ->
                    Text(
                        section.name,
                        modifier = Modifier.fillMaxWidth().clickable { onMoveToSection(section.id) }.padding(vertical = 8.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Remove from library",
                    color = Color(0xFFC44D4D),
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onDelete).padding(vertical = 8.dp),
                )
            }
        },
        confirmButton = { Text("Close", modifier = Modifier.padding(12.dp).clickable(onClick = onDismiss)) },
    )
}
