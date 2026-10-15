@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sadking.folio.R
import dev.sadking.folio.data.*
import dev.sadking.folio.link.FolioLink
import dev.sadking.folio.link.LinkLog
import dev.sadking.folio.link.PluginExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val DEVICE_LINK_CARD_TAG = "device-link-card"
internal const val DEVICE_LINK_SWITCH_TAG = "device-link-switch"
internal const val DEVICE_EREADERS_TAG = "device-ereaders"
internal const val DEVICE_QUEUE_TAG = "device-queue"
internal const val DEVICE_ACTIVITY_TAG = "device-activity"
internal const val DEVICE_SETUP_TAG = "device-setup"

/**
 * The Device tab wired to Folio Link: the service's port, the phone's addresses (refreshed while
 * the tab is open, as Wi-Fi and the hotspot come and go), the log, the sign-in sheet and saving the
 * plugin. Everything the screen shows is passed to [DeviceScreen], which previews render directly.
 */
@Composable
internal fun DeviceTab(
    link: FolioLink,
    ereaders: List<EreaderSeen>,
    queue: List<QueuedBook>,
    onOpenBook: (Book) -> Unit,
    onRemoveQueued: (Book) -> Unit,
    sshHost: String,
    onSendOverSsh: () -> Unit,
    syncJob: SyncJob?,
    onCancelJob: (String) -> Unit,
    onResumeJob: (String) -> Unit,
    busy: Boolean,
) {
    val context = LocalContext.current
    val port by link.port.collectAsStateWithLifecycle()
    val entries by link.log.entries.collectAsStateWithLifecycle()
    var enabled by remember { mutableStateOf(link.settings.enabled) }
    // The notification's "Turn off" changes the setting outside this screen; follow the port.
    LaunchedEffect(port) { enabled = link.settings.enabled }
    var addresses by remember { mutableStateOf(FolioLink.addresses()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            addresses = FolioLink.addresses()
        }
    }
    var showSignIn by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var pluginSaved by remember { mutableStateOf<Boolean?>(null) }
    val savePlugin =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) { uri ->
            if (uri != null)
                scope.launch {
                    pluginSaved =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                    context.contentResolver.openOutputStream(uri, "wt")!!.use {
                                        PluginExport.write(context, it)
                                    }
                                }
                                .isSuccess
                        }
                }
        }
    val status = linkStatus(enabled, port, addresses)
    val shown = port?.let { p -> addresses.map { "$it:$p" } } ?: emptyList()
    DeviceScreen(
        status = status,
        addresses = shown,
        onLink = { on ->
            enabled = on
            if (on) link.start() else link.stop()
        },
        onCopy = { address ->
            // Android 13+ confirms a copy itself, so no snackbar of our own.
            context
                .getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Librillo Link", address))
        },
        onSignIn = { showSignIn = true },
        onOpenWifi = {
            runCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        },
        ereaders = ereaders,
        queue = queue,
        onOpenBook = onOpenBook,
        onRemoveQueued = onRemoveQueued,
        activity = entries,
        onClearActivity = link.log::clear,
        onSavePlugin = { savePlugin.launch("folio.koplugin.zip") },
        pluginSaved = pluginSaved,
        sshHost = sshHost,
        onSendOverSsh = onSendOverSsh,
        syncJob = syncJob,
        onCancelJob = onCancelJob,
        onResumeJob = onResumeJob,
        busy = busy,
    )
    if (showSignIn)
        LinkSignInSheet(
            addresses = shown,
            username = link.settings.username,
            password = { link.settings.password() },
            resetPassword = { link.settings.resetPassword() },
            onDismiss = { showSignIn = false },
        )
}

/**
 * The Device tab: whether the e-reader can reach the phone, what is waiting, and what happened. One
 * card for Folio Link, the e-readers seen, the send queue as covers, five lines of activity. The
 * e-reader paired over SSH keeps one row while that path is still set up.
 */
@Composable
internal fun DeviceScreen(
    status: LinkStatus,
    addresses: List<String>,
    onLink: (Boolean) -> Unit,
    onCopy: (String) -> Unit,
    onSignIn: () -> Unit,
    ereaders: List<EreaderSeen>,
    queue: List<QueuedBook>,
    onOpenBook: (Book) -> Unit,
    onRemoveQueued: (Book) -> Unit,
    activity: List<LinkLog.Entry>,
    onClearActivity: () -> Unit,
    onSavePlugin: () -> Unit,
    pluginSaved: Boolean? = null,
    onOpenWifi: () -> Unit = {},
    sshHost: String = "",
    onSendOverSsh: () -> Unit = {},
    syncJob: SyncJob? = null,
    onCancelJob: (String) -> Unit = {},
    onResumeJob: (String) -> Unit = {},
    busy: Boolean = false,
) {
    var showAllActivity by rememberSaveable { mutableStateOf(false) }
    val large = LocalDensity.current.fontScale > 1.5f
    val on = status != LinkStatus.OFF
    val setup = status == LinkStatus.ON && ereaders.isEmpty()
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = LocalContentBottomPadding.current + FolioSpace.section),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.section),
    ) {
        Text(
            stringResource(R.string.device_title),
            style =
                if (large) MaterialTheme.typography.headlineSmall
                else MaterialTheme.typography.headlineMedium,
            modifier =
                Modifier.padding(start = FolioSpace.page, end = FolioSpace.page, top = 12.dp),
        )
        LinkCard(
            status,
            addresses,
            waitingForEreader = setup,
            onLink = onLink,
            onCopy = onCopy,
            onSignIn = onSignIn,
            onOpenWifi = onOpenWifi,
            modifier = Modifier.padding(horizontal = FolioSpace.page),
        )
        if (on && ereaders.isNotEmpty())
            Section(stringResource(R.string.device_ereaders)) {
                Column(Modifier.testTag(DEVICE_EREADERS_TAG)) {
                    ereaders.forEach { EreaderRow(it) }
                }
            }
        if (setup)
            SetupCard(onSavePlugin, pluginSaved, Modifier.padding(horizontal = FolioSpace.page))
        if (on || queue.isNotEmpty())
            Section(stringResource(R.string.device_queue)) {
                SendQueue(queue, onOpenBook, onRemoveQueued)
            }
        if (sshHost.isNotBlank())
            Section(stringResource(R.string.device_legacy)) {
                LegacyRow(sshHost, busy, onSendOverSsh)
                syncJob?.let {
                    JobRow(
                        it,
                        busy,
                        onCancelJob,
                        onResumeJob,
                        Modifier.padding(horizontal = FolioSpace.page, vertical = FolioSpace.small),
                    )
                }
            }
        Section(
            stringResource(R.string.device_activity),
            trailing =
                if (activity.isEmpty()) null
                else {
                    {
                        TextButton(onClick = { showAllActivity = true }) {
                            Text(stringResource(R.string.device_activity_all))
                        }
                    }
                },
        ) {
            ActivityList(activity.take(5), Modifier.padding(horizontal = FolioSpace.page))
        }
        if (!setup) PluginRow(onSavePlugin, pluginSaved)
    }
    if (showAllActivity)
        ActivitySheet(activity, onClear = onClearActivity, onDismiss = { showAllActivity = false })
}

/** A section eyebrow in small caps, with room for one text button on the right. */
@Composable
private fun Section(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = FolioSpace.page, end = FolioSpace.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
        content()
    }
}

/**
 * Folio Link in one card: a status dot and a word, the address with a copy icon, a switch. Waiting
 * states breathe the dot; "on" holds it still. Never red for "off".
 */
@Composable
internal fun LinkCard(
    status: LinkStatus,
    addresses: List<String>,
    waitingForEreader: Boolean,
    onLink: (Boolean) -> Unit,
    onCopy: (String) -> Unit,
    onSignIn: () -> Unit,
    onOpenWifi: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val on = status != LinkStatus.OFF
    val colour =
        when (status) {
            LinkStatus.ON -> MaterialTheme.colorScheme.primary
            LinkStatus.STARTING,
            LinkStatus.WAITING_FOR_NETWORK -> MaterialTheme.colorScheme.tertiary
            LinkStatus.OFF -> MaterialTheme.colorScheme.outline
        }
    val word =
        stringResource(
            when (status) {
                LinkStatus.ON -> R.string.device_link_on
                LinkStatus.STARTING -> R.string.link_starting
                LinkStatus.WAITING_FOR_NETWORK -> R.string.device_link_waiting_network
                LinkStatus.OFF -> R.string.device_link_off
            }
        )
    val waiting = status == LinkStatus.STARTING || status == LinkStatus.WAITING_FOR_NETWORK
    val title = stringResource(R.string.link_title)
    val haptics = rememberHaptics()
    Surface(
        modifier.fillMaxWidth().testTag(DEVICE_LINK_CARD_TAG),
        shape = FolioSpace.panelShape,
        color =
            if (on) MaterialTheme.colorScheme.surfaceContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.padding(FolioSpace.page),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.gap),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(colour, waiting = waiting || waitingForEreader)
                Spacer(Modifier.width(FolioSpace.gap))
                Column(Modifier.weight(1f)) {
                    Text(
                        word,
                        style = MaterialTheme.typography.titleLarge,
                        color = if (on) colour else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    on,
                    {
                        haptics.toggle(it)
                        onLink(it)
                    },
                    Modifier.testTag(DEVICE_LINK_SWITCH_TAG).semantics {
                        contentDescription = title
                    },
                )
            }
            when (status) {
                LinkStatus.OFF ->
                    Text(
                        stringResource(R.string.device_link_off_line),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                LinkStatus.WAITING_FOR_NETWORK ->
                    TextButton(onClick = onOpenWifi, contentPadding = PaddingValues(0.dp)) {
                        Text(stringResource(R.string.device_open_wifi))
                    }
                LinkStatus.STARTING -> Unit
                LinkStatus.ON -> {
                    addresses.forEach { address ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                address,
                                style = MaterialTheme.typography.labelLarge,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(onClick = { onCopy(address) }) {
                                Icon(
                                    Icons.Outlined.ContentCopy,
                                    stringResource(R.string.device_copy_address),
                                )
                            }
                        }
                    }
                    if (waitingForEreader)
                        Text(
                            stringResource(R.string.device_link_waiting_ereader),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    TextButton(onClick = onSignIn, contentPadding = PaddingValues(0.dp)) {
                        Text(stringResource(R.string.device_sign_in))
                    }
                }
            }
        }
    }
}

/**
 * A 12 dp dot: filled when Link is on or off, a ring while it waits. A static cue, not the
 * breathing animation the design sketched: an endless animation keeps Compose from ever reporting
 * idle, so every UI test and screenshot of this tab would hang on it.
 */
@Composable
private fun StatusDot(colour: Color, waiting: Boolean) {
    Box(
        Modifier.size(12.dp)
            .then(
                if (waiting) Modifier.border(2.dp, colour, CircleShape)
                else Modifier.background(colour, CircleShape)
            )
    )
}

@Composable
private fun EreaderRow(ereader: EreaderSeen) {
    val name = ereader.name.ifBlank { stringResource(R.string.device_ereader_default) }
    val seen = relativeTime(ereader.lastSeen)
    ListItem(
        headlineContent = { Text(name, style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            Text(
                ereader.position?.let {
                    stringResource(
                        R.string.device_ereader_seen_position,
                        seen,
                        (it.percent * 100).toInt(),
                        it.book.title,
                    )
                } ?: stringResource(R.string.device_ereader_seen, seen),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Icon(Icons.Outlined.TabletAndroid, null, tint = MaterialTheme.colorScheme.primary)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/** The only instructions on the tab: three short steps, shown until an e-reader has connected. */
@Composable
private fun SetupCard(onSavePlugin: () -> Unit, saved: Boolean?, modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth().testTag(DEVICE_SETUP_TAG),
        shape = FolioSpace.panelShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.padding(FolioSpace.page),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.gap),
        ) {
            Text(
                stringResource(R.string.device_setup_title),
                style = MaterialTheme.typography.titleMedium,
            )
            listOf(
                    R.string.device_setup_step1,
                    R.string.device_setup_step2,
                    R.string.device_setup_step3,
                )
                .forEachIndexed { index, step ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier.size(24.dp)
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Spacer(Modifier.width(FolioSpace.gap))
                        Text(
                            stringResource(step),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).padding(top = 2.dp),
                        )
                    }
                }
            FilledTonalButton(onClick = onSavePlugin) {
                Icon(Icons.Outlined.SaveAlt, null, Modifier.size(18.dp))
                Spacer(Modifier.width(FolioSpace.small))
                Text(stringResource(R.string.link_plugin_save))
            }
            PluginSavedNote(saved)
        }
    }
}

/** Saving the plugin for an e-reader that is already set up: one row, out of the way. */
@Composable
private fun PluginRow(onSavePlugin: () -> Unit, saved: Boolean?) {
    Column {
        ListItem(
            headlineContent = { Text(stringResource(R.string.link_plugin_title)) },
            supportingContent = { Text(stringResource(R.string.link_plugin_hint)) },
            leadingContent = {
                Icon(Icons.Outlined.Extension, null, tint = MaterialTheme.colorScheme.primary)
            },
            trailingContent = {
                Icon(Icons.Outlined.SaveAlt, stringResource(R.string.link_plugin_save))
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier =
                Modifier.padding(horizontal = 4.dp)
                    .clickable(role = Role.Button, onClick = onSavePlugin),
        )
        if (saved != null) PluginSavedNote(saved, Modifier.padding(horizontal = FolioSpace.page))
    }
}

@Composable
private fun PluginSavedNote(saved: Boolean?, modifier: Modifier = Modifier) {
    when (saved) {
        true ->
            Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.link_plugin_saved),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(R.string.link_plugin_folders),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        false ->
            Text(
                stringResource(R.string.link_plugin_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = modifier,
            )
        null -> Unit
    }
}

/**
 * The books waiting for e-readers, as 72 dp covers with a chip under each. The cover opens the
 * book; the small cross takes it out of the queue. A book stays "Sent" until it ages out, so a
 * second e-reader still gets it.
 */
@Composable
internal fun SendQueue(
    queue: List<QueuedBook>,
    onOpenBook: (Book) -> Unit,
    onRemove: (Book) -> Unit,
) {
    if (queue.isEmpty()) {
        Text(
            stringResource(R.string.device_queue_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = FolioSpace.page, vertical = FolioSpace.small),
        )
        return
    }
    LazyRow(
        Modifier.fillMaxWidth().testTag(DEVICE_QUEUE_TAG),
        contentPadding = PaddingValues(horizontal = FolioSpace.page, vertical = FolioSpace.small),
        horizontalArrangement = Arrangement.spacedBy(FolioSpace.item),
    ) {
        items(queue, key = { it.book.id }) { item ->
            val label =
                stringResource(
                    if (item.sent) R.string.device_queue_sent else R.string.device_queue_waiting
                )
            // The chip may grow wider than the cover at large text sizes.
            Column(
                Modifier.widthIn(min = 72.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box {
                    CoverTile(
                        item.book,
                        Modifier.width(72.dp)
                            .clip(FolioSpace.coverShape)
                            .clickable(role = Role.Button) { onOpenBook(item.book) }
                            .semantics {
                                contentDescription = "${item.book.title}, $label"
                            },
                        variant = CoverVariant.Queued,
                    )
                    IconButton(
                        onClick = { onRemove(item.book) },
                        // Bottom corner: generated covers carry their title at the top.
                        modifier = Modifier.align(Alignment.BottomEnd).size(28.dp).padding(2.dp),
                        colors =
                            IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            stringResource(R.string.device_queue_remove, item.book.title),
                            Modifier.size(16.dp),
                        )
                    }
                }
                Row(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(
                            if (item.sent) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Icon(
                        if (item.sent) Icons.Outlined.Check else Icons.Outlined.Schedule,
                        null,
                        Modifier.size(12.dp),
                    )
                    Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}

/** The e-reader paired over SSH, kept as one row until that path moves under Settings. */
@Composable
private fun LegacyRow(host: String, busy: Boolean, onSend: () -> Unit) {
    ListItem(
        headlineContent = { Text(host, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(stringResource(R.string.device_legacy_ssh)) },
        leadingContent = {
            Icon(Icons.Outlined.SettingsEthernet, null, tint = MaterialTheme.colorScheme.primary)
        },
        trailingContent = {
            FilledTonalIconButton(onClick = onSend, enabled = !busy) {
                Icon(
                    Icons.AutoMirrored.Outlined.Send,
                    stringResource(R.string.device_send_missing),
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/** A running or stuck SSH transfer: state, count, progress, and Resume or Cancel as icons. */
@Composable
private fun JobRow(
    job: SyncJob,
    busy: Boolean,
    onCancel: (String) -> Unit,
    onResume: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier.fillMaxWidth(),
        shape = FolioSpace.panelShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.padding(FolioSpace.item),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        when (job.state) {
                            "COMPLETE" -> stringResource(R.string.device_job_complete)
                            "FAILED" -> stringResource(R.string.device_job_failed)
                            "PAUSED" -> stringResource(R.string.device_job_paused)
                            "CANCELLED" -> stringResource(R.string.device_job_cancelled)
                            "CANCELLING" -> stringResource(R.string.device_job_cancelling)
                            else -> stringResource(R.string.device_job_sending)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        pluralStringResource(
                            R.plurals.device_job_progress,
                            job.totalBooks,
                            job.completedBooks,
                            job.totalBooks,
                            job.bytesSent / 1024 / 1024,
                            job.totalBytes / 1024 / 1024,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (job.currentTitle.isNotBlank())
                        Text(
                            job.currentTitle,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    val note = job.message.ifBlank { transferStateLabel(job.phase) }
                    if (note.isNotBlank())
                        Text(
                            note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                }
                if (job.state in listOf("FAILED", "PAUSED"))
                    FilledTonalIconButton(onClick = { onResume(job.id) }, enabled = !busy) {
                        Icon(Icons.Outlined.PlayArrow, stringResource(R.string.device_job_resume))
                    }
                if (job.state !in listOf("COMPLETE", "CANCELLED", "CANCELLING"))
                    IconButton(onClick = { onCancel(job.id) }) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.device_job_cancel))
                    }
            }
            if (job.active)
                LinearProgressIndicator(
                    progress = {
                        if (job.totalBytes > 0)
                            (job.bytesSent.toFloat() / job.totalBytes).coerceIn(0f, 1f)
                        else 0f
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
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

/** The last few things Folio Link did, one line each: when, then what. */
@Composable
internal fun ActivityList(entries: List<LinkLog.Entry>, modifier: Modifier = Modifier) {
    Column(
        modifier.testTag(DEVICE_ACTIVITY_TAG),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (entries.isEmpty())
            Text(
                stringResource(R.string.link_log_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = FolioSpace.small),
            )
        entries.forEach { entry ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    shortRelativeTime(entry.time),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier.width(80.dp * LocalDensity.current.fontScale.coerceAtMost(2f))
                            .padding(top = 2.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    describe(entry),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Every entry the log holds, with date and time, and a way to clear it. */
@Composable
private fun ActivitySheet(
    entries: List<LinkLog.Entry>,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val timeFormat = remember { DateFormat.getTimeFormat(context) }
    val dateFormat = remember { DateFormat.getDateFormat(context) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = FolioSpace.sheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = FolioSpace.section, end = FolioSpace.item),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.device_activity_all),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (entries.isNotEmpty())
                TextButton(
                    onClick = {
                        onClear()
                        onDismiss()
                    }
                ) {
                    Text(stringResource(R.string.link_log_clear))
                }
        }
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding =
                PaddingValues(start = FolioSpace.section, end = FolioSpace.section, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (entries.isEmpty())
                item {
                    Text(
                        stringResource(R.string.link_log_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = FolioSpace.item),
                    )
                }
            items(entries) { entry ->
                val date = java.util.Date(entry.time)
                Text(
                    stringResource(
                        R.string.link_log_line,
                        "${dateFormat.format(date)} ${timeFormat.format(date)}",
                        describe(entry),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** "3 min. ago", "Yesterday": the system's short wording for a narrow column. */
private fun shortRelativeTime(millis: Long): String =
    android.text.format.DateUtils.getRelativeTimeSpanString(
            millis,
            System.currentTimeMillis(),
            android.text.format.DateUtils.MINUTE_IN_MILLIS,
            android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE,
        )
        .toString()

/** One log entry in plain words. No credentials or book keys are in the log to begin with. */
@Composable
internal fun describe(entry: LinkLog.Entry): String =
    when (entry.kind) {
        LinkLog.Kind.STARTED -> stringResource(R.string.link_log_started, entry.port)
        LinkLog.Kind.STOPPED -> stringResource(R.string.link_log_stopped)
        LinkLog.Kind.FAILED_TO_START -> stringResource(R.string.link_log_failed, entry.port)
        LinkLog.Kind.SCREEN_OFF -> stringResource(R.string.link_log_screen_off)
        LinkLog.Kind.SCREEN_ON -> stringResource(R.string.link_log_screen_on)
        LinkLog.Kind.REQUEST ->
            stringResource(
                when {
                    entry.status == 401 || entry.status == 429 -> R.string.link_log_refused
                    entry.route == "/users/auth" || entry.route == "/users/create" ->
                        R.string.link_log_login
                    entry.route == "/syncs/progress" -> R.string.link_log_sent
                    entry.route.startsWith("/syncs/progress/") -> R.string.link_log_asked
                    else -> R.string.link_log_other
                },
                entry.remote,
            )
    }

/**
 * What to type into KOReader: the address, the username and the password. The password is read only
 * when the user asks to see it, and never leaves this sheet.
 */
@Composable
internal fun LinkSignInSheet(
    addresses: List<String>,
    username: String,
    password: () -> String,
    resetPassword: () -> String,
    onDismiss: () -> Unit,
) {
    var shown by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = FolioSpace.sheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .padding(start = FolioSpace.section, end = FolioSpace.section, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.gap),
        ) {
            Text(
                stringResource(R.string.device_sign_in),
                style = MaterialTheme.typography.titleLarge,
            )
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    addresses.forEach {
                        Text(
                            "http://$it",
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Text(stringResource(R.string.link_username, username))
                    Text(
                        stringResource(R.string.link_password, shown ?: "••••••••••••"),
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpace.small)) {
                TextButton(onClick = { shown = if (shown == null) password() else null }) {
                    Text(
                        stringResource(
                            if (shown == null) R.string.link_show_password
                            else R.string.link_hide_password
                        )
                    )
                }
                TextButton(onClick = { shown = resetPassword() }) {
                    Text(stringResource(R.string.link_new_password))
                }
            }
            Text(
                stringResource(R.string.link_steps),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
