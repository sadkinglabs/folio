@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package dev.sadking.folio.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import dev.sadking.folio.*
import dev.sadking.folio.R
import dev.sadking.folio.core.*
import dev.sadking.folio.data.*
import dev.sadking.folio.device.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

@Composable
internal fun StatusSurface(op: Operation) {
    Surface(
        color =
            if (op.failed) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).semantics {
                liveRegion = LiveRegionMode.Polite
            }
        ) {
            Text(op.message, style = MaterialTheme.typography.bodyMedium)
            if (op.busy) {
                Spacer(Modifier.height(8.dp))
                if (op.progress != null)
                    LinearProgressIndicator(
                        progress = { op.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * A transfer running in the background, shown above every screen except the device tab. It never
 * blocks the screen beneath it.
 */
@Composable
internal fun TransferBanner(job: SyncJob, onOpen: () -> Unit, onCancel: () -> Unit) {
    val total = job.totalBooks.coerceAtLeast(1)
    val progress =
        if (job.totalBytes > 0) (job.bytesSent.toFloat() / job.totalBytes).coerceIn(0f, 1f)
        else job.completedBooks.toFloat() / total
    Surface(onClick = onOpen, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier.fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    when (job.state) {
                        "QUEUED" -> stringResource(R.string.transfer_waiting)
                        "CANCELLING" -> stringResource(R.string.transfer_stopping)
                        else ->
                            stringResource(
                                R.string.transfer_sending,
                                (job.completedBooks + 1).coerceAtMost(total),
                                total,
                            )
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                if (job.currentTitle.isNotBlank())
                    Text(
                        job.currentTitle,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            TextButton(onClick = onCancel, enabled = job.state != "CANCELLING") {
                Text(stringResource(R.string.transfer_cancel))
            }
        }
    }
}

internal val LocalRouteHeading = staticCompositionLocalOf { false }

@Composable
internal fun PageHeading(
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (!LocalRouteHeading.current) {
                Text(title, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(4.dp))
            }
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        trailing?.invoke()
    }
}

@Composable
fun Cover(book: Book, modifier: Modifier = Modifier) {
    val image by
        produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, book.coverPath) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching { CoverImages.load(book.coverPath)?.asImageBitmap() }.getOrNull()
                }
        }
    Surface(
        modifier.clip(RoundedCornerShape(8.dp)),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 2.dp,
    ) {
        if (image != null)
            Image(
                image!!,
                stringResource(R.string.shell_cover_of, book.title),
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        else
            Column(
                Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.primary)
                Text(
                    book.title,
                    style =
                        MaterialTheme.typography.titleMedium.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Serif
                        ),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
    }
}

@Composable
internal fun BookTile(
    book: Book,
    selected: Boolean,
    onClick: () -> Unit,
    onLong: () -> Unit,
    signals: BookSignals? = null,
    selectable: Boolean = true,
    longActionLabel: String? = null,
) {
    val actionLabel =
        longActionLabel
            ?: stringResource(
                if (!selectable) R.string.shell_open_book_details
                else if (selected) R.string.shell_deselect_book else R.string.shell_select_book
            )
    Column(
        Modifier.animateContentSize(animationSpec = spring())
            .semantics {
                this.selected = selected
                customActions =
                    listOf(
                        CustomAccessibilityAction(actionLabel) {
                            onLong()
                            true
                        }
                    )
            }
            .combinedClickable(onClick = onClick, onLongClick = onLong)
    ) {
        Box {
            LibraryCover(
                book,
                signals,
                Modifier.fillMaxWidth()
                    .aspectRatio(0.68f)
                    .then(
                        if (selected)
                            Modifier.border(
                                3.dp,
                                MaterialTheme.colorScheme.primary,
                                FolioSpace.coverShape,
                            )
                        else Modifier
                    ),
            )
            if (selected)
                Surface(
                    Modifier.align(Alignment.TopEnd).padding(8.dp),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Icon(
                        Icons.Outlined.Check,
                        stringResource(R.string.shell_selected),
                        Modifier.padding(6.dp).size(20.dp),
                    )
                }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            book.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            book.authorLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun EmptyState(
    title: String,
    message: String,
    icon: ImageVector,
    action: (() -> Unit)? = null,
    actionLabel: String = stringResource(R.string.shell_import_epub),
    actionEnabled: Boolean = true,
) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            FilledTonalButton(onClick = action, enabled = actionEnabled) { Text(actionLabel) }
        }
    }
}

@Composable
internal fun SelectionBar(
    count: Int,
    shelves: List<Shelf>,
    enabled: Boolean,
    onShelf: (String) -> Unit,
    onSync: () -> Unit,
    onClear: () -> Unit,
    onRemove: (() -> Unit)? = null,
    syncEnabled: Boolean = true,
    onManage: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().padding(12.dp),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClear) {
                Icon(Icons.Outlined.Close, stringResource(R.string.shell_clear_selection))
            }
            Text(stringResource(R.string.shell_selected_count, count), Modifier.weight(1f))
            Box {
                if (onManage == null)
                    IconButton(
                        onClick = { menu = true },
                        enabled = enabled && shelves.isNotEmpty(),
                    ) {
                        Icon(Icons.Outlined.BookmarkAdd, stringResource(R.string.shell_add_to_list))
                    }
                DropdownMenu(menu, { menu = false }) {
                    shelves.forEach { shelf ->
                        DropdownMenuItem(
                            text = { Text(shelf.name) },
                            onClick = {
                                onShelf(shelf.id)
                                menu = false
                            },
                        )
                    }
                }
            }
            if (onManage != null)
                IconButton(onClick = onManage, enabled = enabled) {
                    Icon(Icons.Outlined.MoreVert, stringResource(R.string.shell_manage_selected))
                }
            IconButton(onClick = onSync, enabled = enabled && syncEnabled) {
                Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.send_selected))
            }
            if (onRemove != null)
                IconButton(onClick = onRemove, enabled = enabled) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        stringResource(R.string.shell_remove_selected),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
        }
    }
}

internal fun Modifier.toggleableCompat(
    value: Boolean,
    enabled: Boolean,
    onValue: (Boolean) -> Unit,
): Modifier =
    this.then(Modifier.clickable(enabled = enabled, role = Role.Checkbox) { onValue(!value) })
        .semantics {
            toggleableState =
                if (value) androidx.compose.ui.state.ToggleableState.On
                else androidx.compose.ui.state.ToggleableState.Off
        }

@Composable
internal fun DetailLine(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun EditorField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    multiline: Boolean = false,
    error: Boolean = false,
    enabled: Boolean = true,
    supporting: String? = null,
) {
    OutlinedTextField(
        value,
        onChange,
        Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = !multiline,
        minLines = if (multiline) 2 else 1,
        maxLines = if (multiline) 6 else 1,
        isError = error,
        enabled = enabled,
        supportingText = supporting?.let { { Text(it) } },
        shape = RoundedCornerShape(16.dp),
    )
}

/** Shared section rhythm and progressive disclosure across device, reading and book details. */
@Composable
internal fun SectionHeading(title: String, subtitle: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (subtitle.isNotBlank())
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}

@Composable
internal fun ExpandableSection(
    title: String,
    summary: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val expansionState =
        stringResource(if (expanded) R.string.shell_expanded else R.string.shell_collapsed)
    OutlinedCard(Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
            supportingContent = { Text(summary) },
            trailingContent = {
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            },
            modifier =
                Modifier.clickable(role = Role.Button) { expanded = !expanded }
                    .semantics { stateDescription = expansionState },
        )
        if (expanded)
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
    }
}

@Composable
internal fun FeatureRow(
    title: String,
    summary: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(summary, style = MaterialTheme.typography.bodySmall) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
        modifier =
            Modifier.fillMaxWidth()
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .defaultMinSize(minHeight = 64.dp),
    )
}

@Composable
internal fun rememberOperationSnackbar(
    notices: kotlinx.coroutines.flow.Flow<Operation>
): SnackbarHostState {
    val host = remember { SnackbarHostState() }
    LaunchedEffect(notices) {
        notices.collectLatest { notice ->
            host.currentSnackbarData?.dismiss()
            host.showSnackbar(
                notice.message,
                withDismissAction = true,
                duration = if (notice.failed) SnackbarDuration.Long else SnackbarDuration.Short,
            )
        }
    }
    return host
}

@Composable
internal fun BookBadges(signals: BookSignals) {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val progress = signals.progress
        Text(
            if (progress == null) signals.reading
            else
                stringResource(
                    R.string.shell_reading_with_progress,
                    signals.reading,
                    (progress * 100).toInt(),
                ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                displayDeviceState(signals),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, false),
            )
            if (signals.hardcover != null)
                Icon(Icons.Outlined.Link, signals.hardcover, Modifier.size(16.dp))
        }
    }
}

/**
 * A cover with at most one small status icon, so artwork stays readable. Records without an EPUB
 * are dimmed; reading progress is the thin bar along the bottom.
 */
@Composable
internal fun LibraryCover(
    book: Book,
    signals: BookSignals?,
    modifier: Modifier,
    compact: Boolean = false,
) {
    val reading = signals?.reading ?: book.status
    val device = signals?.device
    val noEpub = device == "No EPUB"
    val noEpubState = stringResource(R.string.shell_device_no_epub)
    val badge: Pair<ImageVector, String>? =
        when {
            reading.startsWith("Finished") -> Icons.Outlined.DoneAll to reading
            device == "On Kindle" ->
                Icons.Outlined.TabletAndroid to stringResource(R.string.shell_device_on_ereader)
            device == "Sending" || device == "Queued" ->
                Icons.Outlined.Sync to stringResource(R.string.shell_device_sending_badge)
            device in listOf("Transfer failed", "Transfer paused", "Needs update") ->
                Icons.Outlined.ErrorOutline to displayDeviceState(signals!!)
            else -> null
        }
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).semantics(mergeDescendants = false) {
            if (noEpub) stateDescription = noEpubState
        }
    ) {
        Cover(book, Modifier.matchParentSize().alpha(if (noEpub) 0.55f else 1f))
        badge?.let { (icon, description) ->
            Surface(
                Modifier.align(if (compact) Alignment.BottomEnd else Alignment.TopEnd)
                    .padding(if (compact) 4.dp else 6.dp),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Icon(icon, description, Modifier.padding(4.dp).size(16.dp))
            }
        }
        signals?.progress?.let { progress ->
            LinearProgressIndicator(
                progress = { progress.toFloat() },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
            )
        }
    }
}
