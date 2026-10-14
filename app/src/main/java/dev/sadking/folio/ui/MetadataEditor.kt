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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import dev.sadking.folio.*
import dev.sadking.folio.R
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
                        Text(
                            if (busy) stringResource(R.string.library_editor_saving)
                            else stringResource(R.string.library_editor_save)
                        )
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
                stringResource(R.string.library_editor_heading),
                if (book.currentPath.isBlank()) stringResource(R.string.library_editor_no_epub_note)
                else stringResource(R.string.library_editor_epub_note),
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
                        Text(stringResource(R.string.library_editor_replace_cover))
                    }
                    if (cover != null) {
                        Text(
                            stringResource(R.string.library_editor_cover_selected),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        TextButton(onClick = { cover = null }, enabled = !busy) {
                            Text(stringResource(R.string.library_editor_undo_cover))
                        }
                    }
                }
            }
            EditorField(
                stringResource(R.string.library_editor_title),
                title,
                { title = it },
                error = title.isBlank(),
                enabled = !busy,
                supporting =
                    if (title.isBlank()) stringResource(R.string.library_editor_title_required)
                    else null,
            )
            EditorField(
                stringResource(R.string.library_editor_authors),
                authors,
                { authors = it },
                multiline = true,
                enabled = !busy,
            )
            EditorField(
                stringResource(R.string.library_editor_description),
                description,
                { description = it },
                multiline = true,
                enabled = !busy,
            )
            EditorField(
                stringResource(R.string.library_editor_series),
                series,
                { series = it },
                enabled = !busy,
            )
            EditorField(
                stringResource(R.string.library_editor_series_number),
                seriesIndex,
                { seriesIndex = it },
                error = !numberValid,
                enabled = !busy,
                supporting =
                    if (!numberValid) stringResource(R.string.library_editor_series_number_error)
                    else null,
            )
            ExpandableSection(
                stringResource(R.string.library_editor_more),
                stringResource(R.string.library_editor_more_summary),
            ) {
                EditorField(
                    stringResource(R.string.library_editor_publisher),
                    publisher,
                    { publisher = it },
                    enabled = !busy,
                )
                EditorField(
                    stringResource(R.string.library_editor_language),
                    language,
                    { language = it },
                    enabled = !busy,
                )
                EditorField(
                    stringResource(R.string.library_editor_identifiers),
                    identifiers,
                    { identifiers = it },
                    multiline = true,
                    enabled = !busy,
                    supporting =
                        if (book.currentPath.isBlank())
                            stringResource(R.string.library_editor_identifiers_hint)
                        else stringResource(R.string.library_editor_identifiers_epub),
                )
                EditorField(
                    stringResource(R.string.library_editor_tags),
                    tags,
                    { tags = it },
                    multiline = true,
                    enabled = !busy,
                )
            }
            Text(
                if (book.currentPath.isBlank())
                    stringResource(R.string.library_editor_no_epub_footer)
                else stringResource(R.string.library_editor_epub_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
