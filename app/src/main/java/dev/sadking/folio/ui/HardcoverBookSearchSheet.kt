@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sadking.folio.DiscoveryViewModel
import dev.sadking.folio.data.*

@Composable
fun HardcoverBookSearchSheet(
    model: DiscoveryViewModel,
    onSaved: (WishBook) -> Unit,
    onDismiss: () -> Unit,
    initialQuery: String = "",
) {
    val state by model.state.collectAsStateWithLifecycle()
    val results by model.titleResults.collectAsStateWithLifecycle()
    val proposal by model.titleProposal.collectAsStateWithLifecycle()
    val cover by model.titleCover.collectAsStateWithLifecycle()
    var query by rememberSaveable(initialQuery) { mutableStateOf(initialQuery.take(300)) }
    val snackbar = rememberOperationSnackbar(model.notices)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Box {
            LazyColumn(
                Modifier.fillMaxWidth().imePadding().heightIn(max = 720.dp),
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Text("Look up on Hardcover", style = MaterialTheme.typography.headlineSmall)
                    OutlinedTextField(
                        query,
                        { query = it.take(300) },
                        label = { Text("Book title or author") },
                        singleLine = true,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { model.searchTitle(query) },
                        enabled = !state.busy && query.isNotBlank(),
                    ) {
                        Text("Search Hardcover")
                    }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.failed) Text(state.message, color = MaterialTheme.colorScheme.error)
                }
                val data = proposal
                if (data != null)
                    item {
                        val book =
                            Book(
                                    "preview",
                                    data.metadata.title,
                                    encode(data.metadata.authors),
                                    originalPath = "",
                                    currentPath = "",
                                    originalHash = "",
                                    currentHash = "",
                                    coverPath = cover,
                                )
                                .withMetadata(data.metadata)
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Cover(book, Modifier.width(90.dp).aspectRatio(0.68f))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            book.title,
                                            style = MaterialTheme.typography.titleLarge,
                                        )
                                        Text(book.authorLabel)
                                    }
                                }
                                if (book.description.isNotBlank())
                                    Text(
                                        book.description.take(1500),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                if (book.series.isNotBlank())
                                    DetailLine("Series", "${book.series} ${book.seriesIndex}")
                                if (book.publisher.isNotBlank())
                                    DetailLine("Publisher", book.publisher)
                                if (book.language.isNotBlank())
                                    DetailLine("Language", book.language)
                                Text(
                                    "Edition fields come from Hardcover's default ebook or print edition. You can edit them after saving, then attach your own EPUB.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Button(
                                    onClick = { model.saveTitle(onSaved) },
                                    enabled = !state.busy,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Add to library")
                                }
                                Text(
                                    "Saves locally with its Hardcover match. No reading status or public update is published.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                items(results, key = { it.id }) { match ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(match.title, style = MaterialTheme.typography.titleMedium)
                            Text(match.authors.joinToString(", "))
                            TextButton(
                                onClick = { model.selectTitle(match) },
                                enabled = !state.busy,
                            ) {
                                Text("Fetch book details")
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
            SnackbarHost(snackbar, Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
        }
    }
}
