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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sadking.folio.FolioApplication
import dev.sadking.folio.R
import dev.sadking.folio.data.*
import dev.sadking.folio.reader.koreaderDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

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
                    stringResource(R.string.library_book_other_version),
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
            ExpandableSection(
                stringResource(R.string.library_book_about),
                stringResource(R.string.library_book_about_summary),
            ) {
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
                    Text(
                        stringResource(R.string.library_book_next_in_series, book.series),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(nextInSeries.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (nextOnDevice) stringResource(R.string.library_book_next_on_device)
                        else stringResource(R.string.library_book_next_not_on_device),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        ExpandableSection(
            stringResource(R.string.library_book_info),
            stringResource(R.string.library_book_info_summary),
        ) {
            if (book.publisher.isNotBlank())
                DetailLine(stringResource(R.string.library_book_publisher), book.publisher)
            if (book.language.isNotBlank())
                DetailLine(stringResource(R.string.library_book_language), book.language)
            if (book.identifiers != "[]")
                DetailLine(
                    stringResource(R.string.library_book_identifiers),
                    decode(book.identifiers).joinToString("\n"),
                )
            if (book.tags != "[]")
                DetailLine(
                    stringResource(R.string.library_book_tags),
                    decode(book.tags).joinToString(", "),
                )
            OutlinedButton(onClick = onLookup, enabled = !busy) {
                Text(stringResource(R.string.library_book_find_details))
            }
        }
        ExpandableSection(
            stringResource(R.string.library_book_notes),
            pluralStringResource(
                R.plurals.library_book_notes_count,
                annotations.size,
                annotations.size,
            ),
        ) {
            if (annotations.isEmpty()) {
                Text(
                    stringResource(R.string.library_book_notes_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onRefreshReading, enabled = !deviceBusy) {
                    Text(stringResource(R.string.library_book_notes_get))
                }
            } else {
                OutlinedButton(onClick = onExport, enabled = !busy) {
                    Text(stringResource(R.string.library_book_notes_export))
                }
                annotations.take(100).forEach { note ->
                    Text(
                        note.chapter.ifBlank {
                            if (note.kind == "bookmark")
                                stringResource(R.string.library_book_bookmark)
                            else stringResource(R.string.library_book_highlight)
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
                    Text(stringResource(R.string.library_book_notes_limited))
            }
            TextButton(onClick = onReading) {
                Text(stringResource(R.string.library_book_activity))
            }
        }
        if (onRemove != null)
            TextButton(
                onClick = onRemove,
                enabled = !busy,
                colors =
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.library_book_remove))
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
                        label = { Text(libraryStatusLabel(book.status)) },
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
                                text = { Text(libraryStatusLabel(status)) },
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
            // Each e-reader that reported this exact file through Folio Link.
            linkReports(book).forEach { report ->
                ProgressLine(
                    report.device,
                    report.percentage,
                    stringResource(
                        R.string.progress_link_reported,
                        observationDate(report.timestamp * 1000),
                    ),
                )
            }
            signals.hardcover?.let {
                ProgressLine(stringResource(R.string.progress_hardcover), null, it)
            }
            HorizontalDivider()
            Text(
                signals.deviceVerifiedAt?.let {
                    stringResource(
                        R.string.library_book_device_state_checked,
                        displayDeviceState(signals),
                        observationDate(it),
                    )
                } ?: displayDeviceState(signals),
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
                    Text(
                        stringResource(R.string.library_book_progress_percent, (it * 100).toInt()),
                        style = MaterialTheme.typography.labelLarge,
                    )
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

/**
 * Folio Link reports for this book's current file from other devices, newest first. KOReader keys
 * them by a fingerprint of the file, so another edition of the book does not match.
 */
@Composable
private fun linkReports(book: Book): List<KosyncReport> {
    val app = LocalContext.current.applicationContext as? FolioApplication ?: return emptyList()
    val document by
        produceState<String?>(null, book.currentPath) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching { koreaderDigest(java.io.File(book.currentPath)) }.getOrNull()
                }
        }
    val reports by
        remember(document) {
                document?.let { app.database.library().kosyncReportsFlow(it) }
                    ?: flowOf(emptyList())
            }
            .collectAsStateWithLifecycle(emptyList())
    val phone = remember { app.link.settings.phoneDeviceId }
    return reports.filter { it.deviceId != phone }
}
