@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package dev.sadking.folio.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import dev.sadking.folio.*
import dev.sadking.folio.core.*
import dev.sadking.folio.data.*
import dev.sadking.folio.device.*

@Composable
fun KindleScreen(
    config: DeviceConfig,
    inventory: List<RemoteEntry>,
    plan: List<SyncItem>,
    history: List<Journal>,
    busy: Boolean,
    onConfigure: () -> Unit,
    onPreview: () -> Unit,
    onSync: () -> Unit,
    onSyncMissing: () -> Unit,
    onCollections: () -> Unit = {},
    syncScope: String = "Sync includes all phone books.",
    syncJob: SyncJob? = null,
    onCancelJob: (String) -> Unit = {},
    onResumeJob: (String) -> Unit = {},
    bookTitles: Map<String, String> = emptyMap(),
    remoteBooks: Map<String, Book> = emptyMap(),
    onOpenBook: (Book) -> Unit = {},
    inventoryCheckedAt: Long? = null,
    localBookCount: Int = 0,
    onLibrary: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        PageHeading(
            "Your Kindle",
            if (config.host.isBlank()) "Connect once. Transfer over Wi-Fi."
            else
                inventoryCheckedAt?.let { "Folder checked ${observationDate(it)}" }
                    ?: "Configured · reachability not checked yet",
        )
        Column(
            Modifier.padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            syncJob?.let { job ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            when (job.state) {
                                "COMPLETE" -> "Transfer complete"
                                "FAILED" -> "Transfer needs attention"
                                "PAUSED" -> "Transfer paused"
                                "CANCELLED" -> "Transfer cancelled"
                                "CANCELLING" -> "Cancelling transfer…"
                                else -> "Sending your books"
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            "${job.completedBooks} of ${job.totalBooks} books verified · ${job.bytesSent/1024/1024} of ${job.totalBytes/1024/1024} MB uploaded"
                        )
                        if (job.currentTitle.isNotBlank())
                            Text(job.currentTitle, style = MaterialTheme.typography.bodyMedium)
                        if (job.active)
                            LinearProgressIndicator(
                                progress = {
                                    if (job.totalBytes > 0)
                                        (job.bytesSent.toFloat() / job.totalBytes).coerceIn(0f, 1f)
                                    else 0f
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        Text(
                            job.message.ifBlank {
                                job.phase.lowercase().replaceFirstChar { it.uppercase() }
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (job.state in listOf("FAILED", "PAUSED"))
                                Button(onClick = { onResumeJob(job.id) }, enabled = !busy) {
                                    Text("Resume remaining books")
                                }
                            if (job.state !in listOf("COMPLETE", "CANCELLED", "CANCELLING"))
                                OutlinedButton(onClick = { onCancelJob(job.id) }) {
                                    Text("Cancel transfer")
                                }
                        }
                    }
                }
            }
            FeatureRow(
                if (config.host.isBlank()) "Set up your Kindle" else "Connection settings",
                if (config.host.isBlank()) "Phone hotspot or shared Wi-Fi · no computer needed"
                else "${config.host}:${config.port} · ${config.directory}",
                Icons.Outlined.SettingsEthernet,
                enabled = !busy,
                onClick = onConfigure,
            )
            if (config.host.isBlank())
                Text(
                    "Start the SSH server on your Kindle and connect both devices to Wi-Fi. Read and confirm the host fingerprint here to pair your Kindle.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else {
                Text(syncScope, style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = onSyncMissing,
                    enabled = !busy && localBookCount > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Sync, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Send missing EPUBs")
                }
                OutlinedButton(
                    onClick = onPreview,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Check Kindle & compare")
                }
            }
            if (localBookCount == 0) {
                Text(
                    "Add an EPUB to your library to send it to Kindle.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onLibrary) { Text("Browse library") }
            }
            if (plan.isNotEmpty()) {
                Text("Transfer preview", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "${plan.count { it.action == Action.UPLOAD }} to send · ${plan.count { it.action == Action.PRESENT }} present · ${plan.count { it.action == Action.CONFLICT }} conflicts",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (plan.any { it.action == Action.UPLOAD })
                    Button(onClick = onSync, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Sync, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Send ${plan.count { it.action == Action.UPLOAD }} EPUBs")
                    }
                plan
                    .filter { it.action != Action.PRESENT }
                    .forEach { item ->
                        ListItem(
                            headlineContent = { Text(item.book.title) },
                            supportingContent = { Text(item.reason) },
                            leadingContent = {
                                Icon(
                                    when (item.action) {
                                        Action.UPLOAD -> Icons.Outlined.Upload
                                        Action.PRESENT -> Icons.Outlined.CheckCircle
                                        Action.CONFLICT -> Icons.Outlined.WarningAmber
                                    },
                                    null,
                                )
                            },
                        )
                    }
                Text(
                    "Uploads only. Conflicts remain untouched. No books or reading data are deleted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (config.host.isNotBlank())
                ExpandableSection(
                    "KOReader collections",
                    "Optional bridge · reviewed additions on your Kindle",
                ) {
                    OutlinedButton(
                        onClick = onCollections,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Send collection additions")
                    }
                    Text(
                        "Transfer books first, then review additions in the Folio bridge on your Kindle. Existing collection members are retained.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            if (inventory.isNotEmpty())
                ExpandableSection(
                    "On this Kindle",
                    "${inventory.size} EPUBs · retrieved ${inventoryCheckedAt?.let(::observationDate) ?: "this session"}",
                ) {
                    inventory.take(100).forEach { entry ->
                        val local = remoteBooks[entry.path]
                        if (local != null)
                            FeatureRow(
                                local.title,
                                "${local.authorLabel} · ${entry.size/1024} KB",
                                Icons.Outlined.AutoStories,
                                onClick = { onOpenBook(local) },
                            )
                        else
                            DetailLine(
                                entry.path.substringAfterLast('/'),
                                "${entry.size/1024} KB · ${entry.path}",
                            )
                    }
                    if (inventory.size > 100) Text("Showing first 100 of ${inventory.size} EPUBs")
                }
            ExpandableSection(
                "Recent transfers",
                if (history.isEmpty()) "No transfers yet"
                else "${history.size} recorded operations",
            ) {
                if (history.isEmpty())
                    Text(
                        "Verified transfers will appear here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                history.take(30).forEach { row ->
                    ListItem(
                        headlineContent = {
                            Text(bookTitles[row.bookId] ?: "Transferred book")
                        },
                        supportingContent = {
                            Text(
                                row.state.lowercase().replaceFirstChar { it.uppercase() } +
                                    " · " +
                                    row.message.ifBlank {
                                        java.text.DateFormat.getDateTimeInstance(
                                                java.text.DateFormat.SHORT,
                                                java.text.DateFormat.SHORT,
                                            )
                                            .format(java.util.Date(row.startedAt))
                                    }
                            )
                        },
                        leadingContent = {
                            Icon(
                                if (row.state == "COMPLETE") Icons.Outlined.CheckCircle
                                else Icons.Outlined.History,
                                null,
                            )
                        },
                    )
                }
            }
        }
    }
}
