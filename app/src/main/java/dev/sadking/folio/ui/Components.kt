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
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import dev.sadking.folio.*
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
                "Cover of ${book.title}",
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
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
    Column(
        Modifier.animateContentSize(animationSpec = spring())
            .semantics {
                this.selected = selected
                customActions =
                    listOf(
                        CustomAccessibilityAction(
                            if (longActionLabel != null) longActionLabel
                            else if (!selectable) "Open book details"
                            else if (selected) "Deselect book" else "Select book"
                        ) {
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
                    Icon(Icons.Outlined.Check, "Selected", Modifier.padding(6.dp).size(20.dp))
                }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            book.title,
            style = MaterialTheme.typography.titleMedium,
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
    actionLabel: String = "Import an EPUB",
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
            IconButton(onClick = onClear) { Icon(Icons.Outlined.Close, "Clear selection") }
            Text("$count selected", Modifier.weight(1f))
            Box {
                if (onManage == null)
                    IconButton(
                        onClick = { menu = true },
                        enabled = enabled && shelves.isNotEmpty(),
                    ) {
                        Icon(Icons.Outlined.BookmarkAdd, "Add to list")
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
                    Icon(Icons.Outlined.MoreVert, "Manage selected books")
                }
            IconButton(onClick = onSync, enabled = enabled && syncEnabled) {
                Icon(Icons.Outlined.Sync, "Compare selected EPUBs with Kindle")
            }
            if (onRemove != null)
                IconButton(onClick = onRemove, enabled = enabled) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        "Remove selected books",
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
    OutlinedCard(Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
            supportingContent = { Text(summary) },
            trailingContent = {
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            },
            modifier =
                Modifier.clickable(role = Role.Button) { expanded = !expanded }
                    .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
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
        Text(
            signals.reading + (signals.progress?.let { " · ${(it * 100).toInt()}%" } ?: ""),
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

/** Opaque Material surfaces keep status legible on arbitrary artwork, without tinting covers. */
@Composable
internal fun LibraryCover(
    book: Book,
    signals: BookSignals?,
    modifier: Modifier,
    compact: Boolean = false,
) {
    val reading = signals?.reading ?: book.status
    val device = signals?.device
    Box(modifier.clip(RoundedCornerShape(8.dp))) {
        Cover(book, Modifier.matchParentSize())
        if (compact) {
            if (reading.startsWith("Finished") || device == "On Kindle")
                Surface(
                    Modifier.align(Alignment.BottomEnd).padding(4.dp),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Icon(
                        if (reading.startsWith("Finished")) Icons.Outlined.DoneAll
                        else Icons.Outlined.TabletAndroid,
                        if (reading.startsWith("Finished")) reading else device,
                        Modifier.padding(4.dp).size(16.dp),
                    )
                }
        } else {
            Column(
                Modifier.align(Alignment.BottomStart).padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (reading.substringBefore(" ·") != "Unread")
                    CoverStatusBadge(
                        reading.substringBefore(" ·"),
                        reading,
                        reading.startsWith("Finished"),
                    )
                if (device != null)
                    CoverStatusBadge(
                        if (device == "On Kindle") "Verified" else device,
                        if (device == "On Kindle") "Last verified on Kindle" else "Device: $device",
                        device == "On Kindle",
                    )
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

@Composable
private fun CoverStatusBadge(label: String, description: String, positive: Boolean) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color =
            if (positive) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
