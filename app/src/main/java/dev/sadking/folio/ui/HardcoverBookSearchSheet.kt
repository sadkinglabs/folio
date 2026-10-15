@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sadking.folio.DiscoveryViewModel
import dev.sadking.folio.R
import dev.sadking.folio.data.*

internal const val HARDCOVER_SEARCH_TAG = "hardcover-search"
internal const val HARDCOVER_SEARCH_ADD_TAG = "hardcover-search-add"

/**
 * Find a book on Hardcover and add it as a record: a search, result rows with Add, and the chosen
 * book's details with its cover before it is saved.
 */
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
    // "Add" on a row fetches the details first, then saves as soon as they arrive.
    var pendingAdd by remember { mutableStateOf<Int?>(null) }
    val snackbar = rememberOperationSnackbar(model.notices)
    LaunchedEffect(proposal?.id, state.failed) {
        if (state.failed) pendingAdd = null
        else if (pendingAdd != null && proposal?.id == pendingAdd) {
            pendingAdd = null
            model.saveTitle(onSaved)
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = FolioSpace.sheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Box {
            HardcoverBookSearchContent(
                query = query,
                onQuery = { query = it.take(300) },
                busy = state.busy,
                error = state.message.takeIf { state.failed },
                results = results,
                proposal =
                    proposal?.let { data ->
                        coverStub(data.metadata.title, encode(data.metadata.authors), cover)
                            .withMetadata(data.metadata)
                    },
                onSearch = { model.searchTitle(query) },
                onSelect = model::selectTitle,
                onAdd = { match ->
                    pendingAdd = match.id
                    if (proposal?.id == match.id) model.saveTitle(onSaved)
                    else model.selectTitle(match)
                },
                onSave = { model.saveTitle(onSaved) },
            )
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
internal fun HardcoverBookSearchContent(
    query: String,
    onQuery: (String) -> Unit,
    busy: Boolean,
    error: String?,
    results: List<HardcoverMatch>,
    proposal: Book?,
    onSearch: () -> Unit,
    onSelect: (HardcoverMatch) -> Unit,
    onAdd: (HardcoverMatch) -> Unit,
    onSave: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxWidth().imePadding().heightIn(max = 720.dp).testTag(HARDCOVER_SEARCH_TAG),
        contentPadding =
            PaddingValues(start = FolioSpace.section, end = FolioSpace.section, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.gap),
    ) {
        item {
            SheetTitle(stringResource(R.string.hardcover_lookup_title)) {
                InfoAction(stringResource(R.string.hardcover_lookup_source))
            }
            OutlinedTextField(
                query,
                onQuery,
                placeholder = { Text(stringResource(R.string.hardcover_lookup_query)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    IconButton(onClick = onSearch, enabled = !busy && query.isNotBlank()) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowForward,
                            stringResource(R.string.hardcover_lookup_search),
                        )
                    }
                },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions =
                    KeyboardActions(onSearch = { if (query.isNotBlank()) onSearch() }),
                modifier = Modifier.fillMaxWidth().padding(top = FolioSpace.small),
            )
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            if (error != null)
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = FolioSpace.small),
                )
        }
        if (proposal != null)
            item {
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = FolioSpace.panelShape,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(
                        Modifier.padding(FolioSpace.item),
                        verticalArrangement = Arrangement.spacedBy(FolioSpace.gap),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(FolioSpace.item)) {
                            CoverTile(proposal, Modifier.width(72.dp))
                            Column(Modifier.weight(1f), Arrangement.spacedBy(2.dp)) {
                                Text(
                                    proposal.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    proposal.authorLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val facts =
                                    listOfNotNull(
                                        proposal.series
                                            .takeIf { it.isNotBlank() }
                                            ?.let { "$it ${proposal.seriesIndex}".trim() },
                                        proposal.publisher.takeIf { it.isNotBlank() },
                                        proposal.language.takeIf { it.isNotBlank() },
                                    )
                                if (facts.isNotEmpty())
                                    Text(
                                        facts.joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                            }
                        }
                        if (proposal.description.isNotBlank())
                            Text(
                                proposal.description.take(1500),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        Button(
                            onClick = onSave,
                            enabled = !busy,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag(HARDCOVER_SEARCH_ADD_TAG),
                        ) {
                            Text(stringResource(R.string.hardcover_lookup_add))
                        }
                    }
                }
            }
        items(results, key = { it.id }) { match ->
            ResultRow(
                match.title,
                match.authors.joinToString(", "),
                enabled = !busy,
                onClick = { onSelect(match) },
            ) {
                FilledTonalButton(onClick = { onAdd(match) }, enabled = !busy) {
                    Text(stringResource(R.string.hardcover_lookup_add_short))
                }
            }
        }
    }
}
