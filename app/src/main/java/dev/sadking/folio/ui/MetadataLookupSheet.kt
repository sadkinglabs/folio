@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sadking.folio.R
import dev.sadking.folio.core.Metadata
import dev.sadking.folio.data.*

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
    var query by rememberSaveable(book.id) { mutableStateOf("${book.title} ${book.authorLabel}") }
    var selected by remember(proposal) { mutableStateOf(emptySet<String>()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.library_lookup_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                stringResource(R.string.library_lookup_privacy),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                query,
                { query = it },
                Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.library_lookup_query)) },
                singleLine = true,
            )
            Button(onClick = { onSearch(query) }, enabled = !busy && query.isNotBlank()) {
                Text(stringResource(R.string.library_lookup_search))
            }
            if (proposal == null)
                results.forEach { c ->
                    OutlinedCard(
                        onClick = { onSelect(c) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(c.title, style = MaterialTheme.typography.titleMedium)
                            Text(c.authors.joinToString(", "))
                            Text(
                                if (c.editionKey == null)
                                    stringResource(R.string.library_lookup_work)
                                else
                                    stringResource(
                                        R.string.library_lookup_edition,
                                        c.editionKey.substringAfterLast('/'),
                                    ),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            else {
                val current = book.metadata()
                val p = proposal.metadata
                Text(
                    stringResource(R.string.library_lookup_choose),
                    style = MaterialTheme.typography.titleLarge,
                )
                // Field keys stay in English: they track which fields are selected.
                val fieldLabels =
                    mapOf(
                        "Title" to stringResource(R.string.library_lookup_field_title),
                        "Authors" to stringResource(R.string.library_lookup_field_authors),
                        "Description" to stringResource(R.string.library_lookup_field_description),
                        "Publisher" to stringResource(R.string.library_lookup_field_publisher),
                        "Language" to stringResource(R.string.library_lookup_field_language),
                        "Identifiers" to stringResource(R.string.library_lookup_field_identifiers),
                    )
                val emptyValue = stringResource(R.string.library_lookup_empty)
                val fields =
                    listOf(
                        "Title" to (current.title to p.title),
                        "Authors" to
                            (current.authors.joinToString(", ") to p.authors.joinToString(", ")),
                        "Description" to (current.description to p.description),
                        "Publisher" to (current.publisher to p.publisher),
                        "Language" to (current.language to p.language),
                        "Identifiers" to
                            (current.identifiers.joinToString(", ") to
                                p.identifiers.joinToString(", ")),
                    )
                fields.forEach { (name, values) ->
                    if (values.second.isNotBlank())
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.Top,
                        ) {
                            Checkbox(
                                name in selected,
                                { checked ->
                                    selected = if (checked) selected + name else selected - name
                                },
                                enabled = !busy,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    fieldLabels[name] ?: name,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    stringResource(
                                        R.string.library_lookup_current,
                                        values.first.ifBlank { emptyValue }.take(240),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    stringResource(
                                        R.string.library_lookup_proposed,
                                        values.second.take(600),
                                    )
                                )
                            }
                        }
                }
                if (coverPreview != null)
                    Cover(
                        book.copy(coverPath = coverPreview),
                        Modifier.width(112.dp).aspectRatio(0.68f),
                    )
                proposal.coverId?.let {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(
                            "Cover" in selected,
                            { checked ->
                                selected = if (checked) selected + "Cover" else selected - "Cover"
                            },
                            enabled = !busy,
                        )
                        Text(stringResource(R.string.library_lookup_cover))
                    }
                }
                Button(
                    onClick = {
                        onApply(
                            current.copy(
                                title = if ("Title" in selected) p.title else current.title,
                                authors = if ("Authors" in selected) p.authors else current.authors,
                                description =
                                    if ("Description" in selected) p.description
                                    else current.description,
                                publisher =
                                    if ("Publisher" in selected) p.publisher else current.publisher,
                                language =
                                    if ("Language" in selected) p.language else current.language,
                                identifiers =
                                    if ("Identifiers" in selected) p.identifiers
                                    else current.identifiers,
                            ),
                            proposal.coverId.takeIf { "Cover" in selected },
                        )
                    },
                    enabled = !busy && selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.library_lookup_apply))
                }
                Text(
                    stringResource(R.string.library_lookup_note),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
