package com.example.meupdf.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.meupdf.data.db.BookEntity
import com.example.meupdf.data.db.SectionEntity
import com.example.meupdf.ui.common.rememberCoverBitmap
import com.example.meupdf.ui.theme.GlassPanel
import com.example.meupdf.ui.theme.LocalGlassPalette
import com.example.meupdf.ui.theme.ThemeMode

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onOpenBook: (Long) -> Unit,
) {
    val palette = LocalGlassPalette.current
    val sections by viewModel.sections.collectAsState()
    val books by viewModel.books.collectAsState()

    var showAddSection by remember { mutableStateOf(false) }
    var bookForOptions by remember { mutableStateOf<BookEntity?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importPdf(it, null) }
    }

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
                IconButton(onClick = { importLauncher.launch(arrayOf("application/pdf")) }) {
                    Icon(Icons.Filled.Add, contentDescription = "Import PDF", tint = palette.textPrimary)
                }
            }
        }

        val grouped = remember(sections, books) {
            val bySection = books.groupBy { it.sectionId }
            val result = mutableListOf<Pair<String, List<BookEntity>>>()
            sections.forEach { section ->
                result += section.name to (bySection[section.id].orEmpty())
            }
            result += "Uncategorized" to bySection[null].orEmpty()
            result.filter { it.second.isNotEmpty() || sections.any { s -> s.name == it.first } }
        }

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            items(grouped) { (sectionName, sectionBooks) ->
                Text(
                    sectionName,
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp),
                )
                if (sectionBooks.isEmpty()) {
                    Text(
                        "No books yet",
                        color = palette.textSecondary,
                        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
                    )
                } else {
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        sectionBooks.forEach { book ->
                            BookCoverCard(
                                book = book,
                                onClick = { onOpenBook(book.id) },
                                onLongClick = { bookForOptions = book },
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
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
}

@Composable
private fun BookCoverCard(book: BookEntity, onClick: () -> Unit, onLongClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    val cover = rememberCoverBitmap(book.coverPath)
    Column(
        Modifier
            .width(110.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
