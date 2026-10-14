@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
            Text("Find metadata & covers", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Online lookup sends your search text to Open Library. EPUB files and reading data stay on your phone.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                query,
                { query = it },
                Modifier.fillMaxWidth(),
                label = { Text("Title, author or ISBN") },
                singleLine = true,
            )
            Button(onClick = { onSearch(query) }, enabled = !busy && query.isNotBlank()) {
                Text("Search Open Library")
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
                                if (c.editionKey == null) "Work metadata · edition unspecified"
                                else "Edition ${c.editionKey.substringAfterLast('/')}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            else {
                val current = book.metadata()
                val p = proposal.metadata
                Text("Choose fields to replace", style = MaterialTheme.typography.titleLarge)
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
                                Text(name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Current: ${values.first.ifBlank { "Empty" }.take(240)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text("Proposed: ${values.second.take(600)}")
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
                        Text("Replace cover from this edition")
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
                    Text("Apply selected fields")
                }
                Text(
                    "Series, tags and unselected fields are preserved. The original EPUB is retained.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
