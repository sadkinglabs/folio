@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sadking.folio.R
import dev.sadking.folio.data.*

@Composable
fun BookDetails(
    book: Book,
    shelves: List<Shelf>,
    memberships: Set<String>,
    onDevice: Boolean,
    onShelf: (String, Boolean) -> Unit,
    onStatus: (String) -> Unit,
    onSync: () -> Unit,
    busy: Boolean = false,
    deviceBusy: Boolean = busy,
    reading: ReadingSnapshot? = null,
    annotations: List<BookAnnotation> = emptyList(),
    onLookup: () -> Unit = {},
    onExport: () -> Unit = {},
    nextInSeries: Book? = null,
    nextOnDevice: Boolean = false,
    onNext: (Book) -> Unit = {},
    onHardcover: () -> Unit = {},
    onCollections: () -> Unit = {},
    onReading: () -> Unit = {},
    signals: BookSignals? = null,
    onRemove: (() -> Unit)? = null,
    onRefreshReading: () -> Unit = {},
    onRead: () -> Unit = {},
    phoneReading: PhoneReading? = null,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(FolioSpace.page),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.section),
    ) {
        BookHero(book)
        // One clear primary action; sending and progress are secondary.
        Button(onClick = onRead, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(
                if (phoneReading != null)
                    stringResource(
                        R.string.book_continue,
                        ((phoneReading.percent ?: 0.0) * 100).toInt(),
                    )
                else stringResource(R.string.book_read)
            )
        }
        BookPrimaryActions(
            stringResource(R.string.book_send),
            onSync,
            onHardcover,
            enabled = !deviceBusy,
            progressEnabled = !busy,
            prominent = false,
        )
        BookProgressCard(
            book,
            phoneReading,
            reading,
            signals
                ?: BookSignals(
                    if (onDevice) "On Kindle" else "Phone only",
                    book.status,
                    null,
                    null,
                ),
            busy,
            deviceBusy,
            onStatus,
            onRefreshReading,
        )
        reading
            ?.takeIf { it.documentHash != book.currentHash }
            ?.let {
                Text(
                    "Saved progress belongs to the transferred edition. Its position and notes are retained.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        BookListsSummary(
            shelves.filter { it.id in memberships }.map { it.name },
            onCollections,
            !busy,
        )
        if (book.description.isNotBlank())
            ExpandableSection("About this book", "Description") {
                Text(
                    android.text.Html.fromHtml(
                            book.description,
                            android.text.Html.FROM_HTML_MODE_COMPACT,
                        )
                        .toString(),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        if (nextInSeries != null)
            OutlinedCard(onClick = { onNext(nextInSeries) }, modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(FolioSpace.item),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Next in ${book.series}", style = MaterialTheme.typography.labelLarge)
                    Text(nextInSeries.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (nextOnDevice) "Last verified on Kindle" else "EPUB on phone",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        ExpandableSection("Book information", "Publisher, language, identifiers and tags") {
            if (book.publisher.isNotBlank()) DetailLine("Publisher", book.publisher)
            if (book.language.isNotBlank()) DetailLine("Language", book.language)
            if (book.identifiers != "[]")
                DetailLine("Identifiers", decode(book.identifiers).joinToString("\n"))
            if (book.tags != "[]") DetailLine("Tags", decode(book.tags).joinToString(", "))
            OutlinedButton(onClick = onLookup, enabled = !busy) {
                Text("Find metadata & covers online")
            }
        }
        ExpandableSection("Highlights & notes", "${annotations.size} saved annotations") {
            if (annotations.isEmpty()) {
                Text(
                    "Close the book in KOReader, then refresh to retrieve its saved notes.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onRefreshReading, enabled = !deviceBusy) {
                    Text("Retrieve notes from Kindle")
                }
            } else {
                OutlinedButton(onClick = onExport, enabled = !busy) {
                    Text("Export Markdown for Obsidian")
                }
                annotations.take(100).forEach { note ->
                    Text(
                        note.chapter.ifBlank {
                            if (note.kind == "bookmark") "Bookmark" else "Highlight"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (note.text.isNotBlank()) Text(note.text)
                    if (note.note.isNotBlank())
                        Text(note.note, style = MaterialTheme.typography.bodyMedium)
                    if (note.date.isNotBlank())
                        Text(note.date, style = MaterialTheme.typography.labelSmall)
                    HorizontalDivider()
                }
                if (annotations.size > 100)
                    Text("Showing 100 notes. Export includes all saved annotations.")
            }
            TextButton(onClick = onReading) { Text("All reading activity") }
        }
        if (onRemove != null)
            TextButton(
                onClick = onRemove,
                enabled = !busy,
                colors =
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Remove from library")
            }
        Spacer(Modifier.height(FolioSpace.item))
    }
}

/** Where this book stands: its status, each device's position and whether the EPUB is sent. */
@Composable
private fun BookProgressCard(
    book: Book,
    phoneReading: PhoneReading?,
    reading: ReadingSnapshot?,
    signals: BookSignals,
    busy: Boolean,
    deviceBusy: Boolean,
    onStatus: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    var statusMenu by rememberSaveable(book.id) { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(FolioSpace.item),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.progress_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    AssistChip(
                        onClick = { statusMenu = true },
                        enabled = !busy,
                        label = { Text(book.status) },
                        trailingIcon = {
                            Icon(
                                Icons.Outlined.ArrowDropDown,
                                stringResource(R.string.progress_change_status),
                            )
                        },
                    )
                    DropdownMenu(statusMenu, { statusMenu = false }) {
                        phoneStatuses.forEach { status ->
                            DropdownMenuItem(
                                text = { Text(status) },
                                onClick = {
                                    onStatus(status)
                                    statusMenu = false
                                },
                            )
                        }
                    }
                }
            }
            ProgressLine(
                stringResource(R.string.progress_phone),
                phoneReading?.percent,
                if (phoneReading == null) stringResource(R.string.progress_not_started) else null,
            )
            ProgressLine(
                stringResource(R.string.progress_device),
                reading?.percent,
                reading?.fetchedAt?.let {
                    stringResource(R.string.progress_checked, observationDate(it))
                } ?: stringResource(R.string.progress_not_checked),
            ) {
                IconButton(onClick = onRefresh, enabled = !deviceBusy) {
                    Icon(Icons.Outlined.Sync, stringResource(R.string.progress_refresh))
                }
            }
            signals.hardcover?.let {
                ProgressLine(stringResource(R.string.progress_hardcover), null, it)
            }
            HorizontalDivider()
            Text(
                displayDeviceState(signals) +
                    (signals.deviceVerifiedAt?.let { " · ${observationDate(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProgressLine(
    label: String,
    progress: Double?,
    detail: String?,
    action: @Composable () -> Unit = {},
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                progress?.let {
                    Text("${(it * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                }
            }
            progress?.let {
                LinearProgressIndicator(
                    progress = { it.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        action()
    }
}
