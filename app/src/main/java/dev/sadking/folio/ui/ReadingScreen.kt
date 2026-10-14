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
import androidx.compose.ui.unit.dp
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
        item { PageHeading("Reading", "Your reading, with saved progress and notes") }
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
                        Text("Refresh progress & notes")
                    }
                else
                    FeatureRow(
                        "Connect your Kindle",
                        "Retrieve KOReader progress and annotations over Wi-Fi",
                        Icons.Outlined.TabletMac,
                        enabled = !busy,
                        onClick = onConnect,
                    )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("Reading", "Finished", "All activity").forEach { choice ->
                        FilterChip(filter == choice, { filter = choice }, label = { Text(choice) })
                    }
                }
            }
        }
        if (activity.isEmpty())
            item {
                EmptyState(
                    "${if(filter=="Reading") "Choose your next read" else "No activity here yet"}",
                    "Set a book's phone status in its details, or refresh saved reading data from your Kindle.",
                    Icons.Outlined.MenuBook,
                    action = onLibrary,
                    actionLabel = "Browse library",
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
                            signals[b.id]?.reading ?: "Phone · ${b.status}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        r?.percent?.let { fraction ->
                            LinearProgressIndicator(
                                progress = { fraction.toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "KOReader · ${(fraction*100).toInt()}%",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                "Retrieved ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(r.fetchedAt))}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if ((notes[b.id] ?: 0) > 0)
                            Text(
                                "${notes[b.id]} saved annotations",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        TextButton(
                            onClick = { onHardcover(b) },
                            enabled = !busy,
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text("Update Hardcover")
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
                    "About reading updates",
                    "Close the book in KOReader before refreshing",
                ) {
                    Text(
                        "KOReader saves progress and notes when you close a book. Refresh imports supported adjacent sidecars without changing Kindle data. Phone status stays separate; Hardcover updates require your review.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                ExpandableSection(
                    "Reading time",
                    statistics?.let {
                        "${it.totalSeconds/3600} h ${(it.totalSeconds%3600)/60} min · imported snapshot"
                    } ?: "Optional KOReader bridge statistics",
                ) {
                    Text(
                        "Export reading statistics from the Folio bridge in KOReader, then retrieve them here.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = onStatistics,
                        enabled = !busy && paired,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Retrieve exported statistics")
                    }
                    statistics?.let { stats ->
                        Text(
                            "Exported ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(stats.exportedAt*1000))}",
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
                                Text("${day.getLong("seconds")/60} min")
                            }
                        }
                    }
                }
            }
        }
    }
}
