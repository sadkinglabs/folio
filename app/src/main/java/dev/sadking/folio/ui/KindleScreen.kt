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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import dev.sadking.folio.*
import dev.sadking.folio.R
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
    syncScope: String? = null,
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
            stringResource(R.string.device_title),
            if (config.host.isBlank()) stringResource(R.string.device_subtitle_not_set_up)
            else
                inventoryCheckedAt?.let {
                    stringResource(R.string.device_subtitle_checked, observationDate(it))
                } ?: stringResource(R.string.device_subtitle_not_checked),
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
                                "COMPLETE" -> stringResource(R.string.device_job_complete)
                                "FAILED" -> stringResource(R.string.device_job_failed)
                                "PAUSED" -> stringResource(R.string.device_job_paused)
                                "CANCELLED" -> stringResource(R.string.device_job_cancelled)
                                "CANCELLING" -> stringResource(R.string.device_job_cancelling)
                                else -> stringResource(R.string.device_job_sending)
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            pluralStringResource(
                                R.plurals.device_job_progress,
                                job.totalBooks,
                                job.completedBooks,
                                job.totalBooks,
                                job.bytesSent / 1024 / 1024,
                                job.totalBytes / 1024 / 1024,
                            )
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
                        val phaseLabel = transferStateLabel(job.phase)
                        Text(
                            job.message.ifBlank { phaseLabel },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (job.state in listOf("FAILED", "PAUSED"))
                                Button(onClick = { onResumeJob(job.id) }, enabled = !busy) {
                                    Text(stringResource(R.string.device_job_resume))
                                }
                            if (job.state !in listOf("COMPLETE", "CANCELLED", "CANCELLING"))
                                OutlinedButton(onClick = { onCancelJob(job.id) }) {
                                    Text(stringResource(R.string.device_job_cancel))
                                }
                        }
                    }
                }
            }
            FeatureRow(
                if (config.host.isBlank()) stringResource(R.string.device_set_up)
                else stringResource(R.string.device_connection_settings),
                if (config.host.isBlank()) stringResource(R.string.device_set_up_summary)
                else "${config.host}:${config.port} · ${config.directory}",
                Icons.Outlined.SettingsEthernet,
                enabled = !busy,
                onClick = onConfigure,
            )
            if (config.host.isBlank())
                Text(
                    stringResource(R.string.device_set_up_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else {
                Text(
                    syncScope ?: stringResource(R.string.device_sync_scope_default),
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = onSyncMissing,
                    enabled = !busy && localBookCount > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Sync, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.device_send_missing))
                }
                OutlinedButton(
                    onClick = onPreview,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.device_check))
                }
            }
            if (localBookCount == 0) {
                Text(
                    stringResource(R.string.device_no_books),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onLibrary) {
                    Text(stringResource(R.string.device_browse_library))
                }
            }
            if (plan.isNotEmpty()) {
                val uploads = plan.count { it.action == Action.UPLOAD }
                Text(
                    stringResource(R.string.device_preview_title),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    stringResource(
                        R.string.device_preview_counts,
                        uploads,
                        plan.count { it.action == Action.PRESENT },
                        plan.count { it.action == Action.CONFLICT },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (uploads > 0)
                    Button(onClick = onSync, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Sync, null)
                        Spacer(Modifier.width(8.dp))
                        Text(pluralStringResource(R.plurals.device_preview_send, uploads, uploads))
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
                    stringResource(R.string.device_preview_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (config.host.isNotBlank())
                ExpandableSection(
                    stringResource(R.string.device_collections_title),
                    stringResource(R.string.device_collections_summary),
                ) {
                    OutlinedButton(
                        onClick = onCollections,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.device_collections_send))
                    }
                    Text(
                        stringResource(R.string.device_collections_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            if (inventory.isNotEmpty())
                ExpandableSection(
                    stringResource(R.string.device_inventory_title),
                    if (inventoryCheckedAt != null)
                        pluralStringResource(
                            R.plurals.device_inventory_summary_dated,
                            inventory.size,
                            inventory.size,
                            observationDate(inventoryCheckedAt),
                        )
                    else
                        pluralStringResource(
                            R.plurals.device_inventory_summary_session,
                            inventory.size,
                            inventory.size,
                        ),
                ) {
                    inventory.take(100).forEach { entry ->
                        val local = remoteBooks[entry.path]
                        if (local != null)
                            FeatureRow(
                                local.title,
                                stringResource(
                                    R.string.device_inventory_book_line,
                                    local.authorLabel,
                                    entry.size / 1024,
                                ),
                                Icons.Outlined.AutoStories,
                                onClick = { onOpenBook(local) },
                            )
                        else
                            DetailLine(
                                entry.path.substringAfterLast('/'),
                                stringResource(
                                    R.string.device_inventory_file_line,
                                    entry.size / 1024,
                                    entry.path,
                                ),
                            )
                    }
                    if (inventory.size > 100)
                        Text(stringResource(R.string.device_inventory_more, inventory.size))
                }
            ExpandableSection(
                stringResource(R.string.device_history_title),
                if (history.isEmpty()) stringResource(R.string.device_history_empty_summary)
                else
                    pluralStringResource(
                        R.plurals.device_history_summary,
                        history.size,
                        history.size,
                    ),
            ) {
                if (history.isEmpty())
                    Text(
                        stringResource(R.string.device_history_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                history.take(30).forEach { row ->
                    ListItem(
                        headlineContent = {
                            Text(
                                bookTitles[row.bookId]
                                    ?: stringResource(R.string.device_history_unknown_book)
                            )
                        },
                        supportingContent = {
                            Text(
                                stringResource(
                                    R.string.device_history_line,
                                    transferStateLabel(row.state),
                                    row.message.ifBlank { observationDate(row.startedAt) },
                                )
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

/** Display text for a stored transfer state. The stored value itself never changes. */
@Composable
private fun transferStateLabel(state: String): String =
    when (state) {
        "QUEUED",
        "PLANNED" -> stringResource(R.string.device_state_waiting)
        "CONNECTING" -> stringResource(R.string.device_state_connecting)
        "RUNNING",
        "TRANSFERRING" -> stringResource(R.string.device_state_sending)
        "VERIFYING" -> stringResource(R.string.device_state_checking)
        "PUBLISHING" -> stringResource(R.string.device_state_finishing)
        "COMPARING" -> stringResource(R.string.device_state_comparing)
        "COMPLETE" -> stringResource(R.string.device_state_complete)
        "FAILED" -> stringResource(R.string.device_state_failed)
        "PAUSED" -> stringResource(R.string.device_state_paused)
        "CANCELLED" -> stringResource(R.string.device_state_cancelled)
        "CANCELLING" -> stringResource(R.string.device_state_cancelling)
        "INTERRUPTED" -> stringResource(R.string.device_state_interrupted)
        else -> state.lowercase().replaceFirstChar { it.uppercase() }
    }
