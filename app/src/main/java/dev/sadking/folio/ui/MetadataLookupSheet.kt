@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sadking.folio.R
import dev.sadking.folio.core.Metadata
import dev.sadking.folio.data.*

internal const val LOOKUP_SHEET_TAG = "lookup-sheet"
internal const val LOOKUP_APPLY_TAG = "lookup-apply"

/**
 * Find details and a cover on Open Library: a search, result rows, then one row per field with the
 * current value struck through under the suggestion, a checkbox each, and Apply.
 */
@Composable
fun MetadataLookupSheet(
    book: Book,
    results: List<MetadataCandidate>,
    proposal: MetadataProposal?,
    busy: Boolean,
    onSearch: (String) -> Unit,
    onSelect: (MetadataCandidate) -> Unit,
    onApply: (Metadata, Long?) -> Unit,
    onDismiss: () -> Unit,
    coverPreview: String? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = FolioSpace.sheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        MetadataLookupContent(
            book,
            results,
            proposal,
            busy,
            onSearch,
            onSelect,
            onApply,
            coverPreview,
        )
    }
}

@Composable
internal fun MetadataLookupContent(
    book: Book,
    results: List<MetadataCandidate>,
    proposal: MetadataProposal?,
    busy: Boolean,
    onSearch: (String) -> Unit,
    onSelect: (MetadataCandidate) -> Unit,
    onApply: (Metadata, Long?) -> Unit,
    coverPreview: String? = null,
) {
    var query by rememberSaveable(book.id) { mutableStateOf("${book.title} ${book.authorLabel}") }
    var selected by remember(proposal) { mutableStateOf(emptySet<String>()) }
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(start = FolioSpace.section, end = FolioSpace.section, bottom = 32.dp)
            .testTag(LOOKUP_SHEET_TAG),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.gap),
    ) {
        SheetTitle(stringResource(R.string.library_lookup_title)) {
            InfoAction(stringResource(R.string.library_lookup_privacy))
        }
        OutlinedTextField(
            query,
            { query = it },
            Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.library_lookup_query)) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = {
                IconButton(onClick = { onSearch(query) }, enabled = !busy && query.isNotBlank()) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowForward,
                        stringResource(R.string.library_lookup_search),
                    )
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions =
                KeyboardActions(onSearch = { if (query.isNotBlank()) onSearch(query) }),
        )
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (proposal == null)
            results.forEach { c ->
                ResultRow(
                    c.title,
                    c.authors.joinToString(", "),
                    detail =
                        c.editionKey?.let {
                            stringResource(
                                R.string.library_lookup_edition,
                                it.substringAfterLast('/'),
                            )
                        },
                    enabled = !busy,
                    onClick = { onSelect(c) },
                ) {
                    Icon(
                        Icons.Outlined.ChevronRight,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        else {
            val current = book.metadata()
            val fieldLabels =
                mapOf(
                    "Title" to stringResource(R.string.library_lookup_field_title),
                    "Authors" to stringResource(R.string.library_lookup_field_authors),
                    "Description" to stringResource(R.string.library_lookup_field_description),
                    "Publisher" to stringResource(R.string.library_lookup_field_publisher),
                    "Language" to stringResource(R.string.library_lookup_field_language),
                    "Identifiers" to stringResource(R.string.library_lookup_field_identifiers),
                )
            lookupFields(current, proposal.metadata).forEach { field ->
                val on = field.key in selected
                Row(
                    Modifier.fillMaxWidth()
                        .toggleableCompat(on, !busy) { checked ->
                            selected = if (checked) selected + field.key else selected - field.key
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    // The row is the tap target, so the box has no touch padding of its own: give
                    // it a slot that centres it on the field name and the first line of the value.
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        Checkbox(on, null, enabled = !busy)
                    }
                    Column(Modifier.weight(1f).padding(top = 3.dp, start = 4.dp)) {
                        Text(
                            fieldLabels[field.key] ?: field.key,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            field.suggested.take(600),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (field.current.isNotBlank() && field.current != field.suggested)
                            Text(
                                field.current.take(240),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textDecoration = TextDecoration.LineThrough,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                    }
                }
            }
            if (proposal.coverId != null) {
                val useCover = "Cover" in selected
                Row(
                    Modifier.fillMaxWidth()
                        .toggleableCompat(useCover, !busy) { checked ->
                            selected = if (checked) selected + "Cover" else selected - "Cover"
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FolioSpace.gap),
                ) {
                    if (coverPreview != null)
                        CoverTile(book.copy(coverPath = coverPreview), Modifier.width(48.dp))
                    else Icon(Icons.Outlined.Image, null, Modifier.padding(start = 12.dp))
                    Text(
                        stringResource(R.string.library_lookup_cover),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(useCover, null, enabled = !busy)
                }
            }
            Button(
                onClick = {
                    onApply(
                        applyLookup(current, proposal.metadata, selected),
                        proposal.coverId.takeIf { "Cover" in selected },
                    )
                },
                enabled = !busy && selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(LOOKUP_APPLY_TAG),
            ) {
                Text(stringResource(R.string.library_lookup_apply))
            }
            Text(
                stringResource(R.string.library_lookup_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
