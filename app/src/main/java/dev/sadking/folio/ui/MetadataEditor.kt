@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package dev.sadking.folio.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import dev.sadking.folio.*
import dev.sadking.folio.core.*
import dev.sadking.folio.data.*
import dev.sadking.folio.device.*

@Composable
fun MetadataEditor(
    book: Book,
    busy: Boolean,
    onDirtyChange: (Boolean) -> Unit = {},
    save: (Metadata, Uri?) -> Unit,
) {
    val initial = remember(book.id) { book.metadata() }
    var title by rememberSaveable(book.id) { mutableStateOf(initial.title) }
    var authors by rememberSaveable(book.id) { mutableStateOf(initial.authors.joinToString("\n")) }
    var description by rememberSaveable(book.id) { mutableStateOf(initial.description) }
    var publisher by rememberSaveable(book.id) { mutableStateOf(initial.publisher) }
    var language by rememberSaveable(book.id) { mutableStateOf(initial.language) }
    var identifiers by
        rememberSaveable(book.id) { mutableStateOf(initial.identifiers.joinToString("\n")) }
    var series by rememberSaveable(book.id) { mutableStateOf(initial.series) }
    var seriesIndex by rememberSaveable(book.id) { mutableStateOf(initial.seriesIndex) }
    var tags by rememberSaveable(book.id) { mutableStateOf(initial.tags.joinToString("\n")) }
    var cover by rememberSaveable { mutableStateOf<String?>(null) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            cover = it?.toString()
        }
    val numberValid =
        seriesIndex.isBlank() ||
            seriesIndex.toDoubleOrNull()?.let { it.isFinite() && it >= 0 } == true

    fun lines(s: String) = s.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val edited =
        Metadata(
            title.trim(),
            lines(authors),
            description,
            publisher.trim(),
            language.trim(),
            lines(identifiers),
            series.trim(),
            seriesIndex.trim(),
            lines(tags),
        )
    val dirty = edited != initial || cover != null
    LaunchedEffect(dirty) { onDirtyChange(dirty) }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    Modifier.fillMaxWidth()
                        .imePadding()
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Button(
                        onClick = { save(edited, cover?.let(Uri::parse)) },
                        enabled = !busy && title.isNotBlank() && numberValid && dirty,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (busy) "Saving…" else "Save changes")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionHeading(
                "Book information",
                if (book.currentPath.isBlank())
                    "Saved locally without an EPUB. Your Hardcover match is retained."
                else "Your original EPUB is preserved; edits create a new revision.",
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(book, Modifier.width(80.dp).aspectRatio(0.68f))
                Column {
                    OutlinedButton(
                        onClick = {
                            picker.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
                        },
                        enabled = !busy,
                    ) {
                        Text("Replace cover")
                    }
                    if (cover != null) {
                        Text("Replacement selected", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { cover = null }, enabled = !busy) {
                            Text("Undo cover change")
                        }
                    }
                }
            }
            EditorField(
                "Title",
                title,
                { title = it },
                error = title.isBlank(),
                enabled = !busy,
                supporting = if (title.isBlank()) "A title is required" else null,
            )
            EditorField(
                "Authors · one per line",
                authors,
                { authors = it },
                multiline = true,
                enabled = !busy,
            )
            EditorField(
                "Description",
                description,
                { description = it },
                multiline = true,
                enabled = !busy,
            )
            EditorField("Series", series, { series = it }, enabled = !busy)
            EditorField(
                "Series number",
                seriesIndex,
                { seriesIndex = it },
                error = !numberValid,
                enabled = !busy,
                supporting = if (!numberValid) "Enter a number of zero or greater" else null,
            )
            ExpandableSection("More metadata", "Publisher, language, identifiers and tags") {
                EditorField("Publisher", publisher, { publisher = it }, enabled = !busy)
                EditorField("Language", language, { language = it }, enabled = !busy)
                EditorField(
                    "Identifiers · one per line",
                    identifiers,
                    { identifiers = it },
                    multiline = true,
                    enabled = !busy,
                    supporting =
                        if (book.currentPath.isBlank()) "ISBN or other identifiers"
                        else "The EPUB's primary identifier is retained.",
                )
                EditorField(
                    "Tags · one per line",
                    tags,
                    { tags = it },
                    multiline = true,
                    enabled = !busy,
                )
            }
            Text(
                if (book.currentPath.isBlank())
                    "These edits change phone metadata only; no Hardcover update is published."
                else
                    "Books already on your Kindle are not automatically replaced after an edit. Review any revision conflict in Kindle sync.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
