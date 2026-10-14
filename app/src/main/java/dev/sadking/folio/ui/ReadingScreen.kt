package dev.sadking.folio.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sadking.folio.R
import dev.sadking.folio.data.*

@Composable
fun ReadingScreen(
    books: List<Book>,
    snapshots: List<ReadingSnapshot>,
    annotations: List<BookAnnotation>,
    busy: Boolean,
    onRefresh: () -> Unit,
    onOpen: (Book) -> Unit,
    statistics: DeviceStatistics? = null,
    onStatistics: () -> Unit = {},
    paired: Boolean = true,
    onConnect: () -> Unit = {},
    onHardcover: (Book) -> Unit = {},
    onLibrary: () -> Unit = {},
    signals: Map<String, BookSignals> = emptyMap(),
) {
    val latest =
        remember(snapshots) {
            snapshots.groupBy { it.bookId }.mapValues { it.value.maxBy { r -> r.fetchedAt } }
        }
    val notes = remember(annotations) { annotations.groupingBy { it.bookId }.eachCount() }
    // Filter identifiers stay in English; only their labels come from resources.
    var filter by rememberSaveable { mutableStateOf("Reading") }
    val activity =
        remember(books, latest, filter, signals) {
            books
                .filter { b ->
                    val r = latest[b.id]
                    val status = signals[b.id]?.reading?.substringBefore(" ·") ?: b.status
                    when (filter) {
                        "Reading" ->
                            status == "Reading" || r?.status in listOf("reading", "in_progress")
                        "Finished" -> status == "Finished" || r?.status == "complete"
                        else -> r != null || status in listOf("Reading", "Finished")
                    }
                }
                .sortedByDescending { latest[it.id]?.fetchedAt ?: it.addedAt }
        }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            PageHeading(
                stringResource(R.string.reading_title),
                stringResource(R.string.reading_subtitle),
            )
        }
        item {
            Column(
                Modifier.padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (paired)
                    FilledTonalButton(
                        onClick = onRefresh,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Sync, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.reading_refresh))
                    }
                else
                    FeatureRow(
                        stringResource(R.string.reading_connect),
                        stringResource(R.string.reading_connect_summary),
                        Icons.Outlined.TabletMac,
                        enabled = !busy,
                        onClick = onConnect,
                    )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                            "Reading" to R.string.reading_filter_reading,
                            "Finished" to R.string.reading_filter_finished,
                            "All activity" to R.string.reading_filter_all,
                        )
                        .forEach { (choice, labelRes) ->
                            FilterChip(
                                filter == choice,
                                { filter = choice },
                                label = { Text(stringResource(labelRes)) },
                            )
                        }
                }
            }
        }
        if (activity.isEmpty())
            item {
                EmptyState(
                    if (filter == "Reading") stringResource(R.string.reading_empty_reading)
                    else stringResource(R.string.reading_empty_other),
                    stringResource(R.string.reading_empty_body),
                    Icons.Outlined.MenuBook,
                    action = onLibrary,
                    actionLabel = stringResource(R.string.reading_browse_library),
                )
            }
        items(activity, key = { it.id }) { b ->
            val r = latest[b.id]
            OutlinedCard(
                onClick = { onOpen(b) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            ) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Cover(b, Modifier.width(64.dp).aspectRatio(0.68f))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(b.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            b.authorLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            signals[b.id]?.reading
                                ?: stringResource(
                                    R.string.reading_phone_status,
                                    libraryStatusLabel(b.status),
                                ),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        r?.percent?.let { fraction ->
                            LinearProgressIndicator(
                                progress = { fraction.toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                stringResource(
                                    R.string.reading_device_percent,
                                    (fraction * 100).toInt(),
                                ),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                stringResource(
                                    R.string.reading_checked,
                                    observationDate(r.fetchedAt),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        val noteCount = notes[b.id] ?: 0
                        if (noteCount > 0)
                            Text(
                                pluralStringResource(R.plurals.reading_notes, noteCount, noteCount),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        TextButton(
                            onClick = { onHardcover(b) },
                            enabled = !busy,
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text(stringResource(R.string.reading_update_hardcover))
                        }
                    }
                }
            }
        }
        item {
            Column(
                Modifier.padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ExpandableSection(
                    stringResource(R.string.reading_about_title),
                    stringResource(R.string.reading_about_summary),
                ) {
                    Text(
                        stringResource(R.string.reading_about_body),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                ExpandableSection(
                    stringResource(R.string.reading_time_title),
                    statistics?.let {
                        stringResource(
                            R.string.reading_time_total,
                            it.totalSeconds / 3600,
                            (it.totalSeconds % 3600) / 60,
                        )
                    } ?: stringResource(R.string.reading_time_none),
                ) {
                    Text(
                        stringResource(R.string.reading_time_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = onStatistics,
                        enabled = !busy && paired,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.reading_time_get))
                    }
                    statistics?.let { stats ->
                        Text(
                            stringResource(
                                R.string.reading_time_exported,
                                java.text.DateFormat.getDateTimeInstance()
                                    .format(java.util.Date(stats.exportedAt * 1000)),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        val days = org.json.JSONArray(stats.days)
                        repeat(minOf(days.length(), 30)) { index ->
                            val day = days.getJSONObject(index)
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(day.getString("date"))
                                Text(
                                    stringResource(
                                        R.string.reading_time_minutes,
                                        day.getLong("seconds") / 60,
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
