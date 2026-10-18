@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sadking.folio.R
import dev.sadking.folio.data.DeviceRoster
import dev.sadking.folio.data.KnownDevice
import dev.sadking.folio.link.FolioLink
import dev.sadking.folio.link.LinkLog
import dev.sadking.folio.link.PluginExport
import dev.sadking.folio.link.Refusal
import dev.sadking.folio.link.RefusedFile
import dev.sadking.folio.sync.EreaderSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val DEVICE_SETUP_TAG = "device-setup"
internal const val DEVICE_SYNC_CARD_TAG = "device-sync-card"
internal const val DEVICE_SYNC_BUTTON_TAG = "device-sync-button"
internal const val DEVICE_DECISIONS_TAG = "device-decisions"
internal const val DEVICE_REFUSED_TAG = "device-refused"
internal const val DEVICE_AUTO_SYNC_TAG = "device-auto-sync"
internal const val DEVICE_REINSTALL_TAG = "device-reinstall"
internal const val DEVICE_ACTIVITY_TAG = "device-activity"

/**
 * The Device tab wired to the app: the phone's sync settings, its addresses (refreshed while the
 * tab is open, as Wi-Fi and the hotspot come and go), the log, the sign-in sheet and saving the
 * plugin. Everything the screen shows is passed to [DeviceScreen], which previews render directly.
 * Its list of devices opens [DeviceSheet], whose changes go to [onRename], [onMerge] (device key,
 * the key it is the same as), [onSeparate] and [onForget].
 */
@Composable
internal fun DeviceTab(
    link: FolioLink,
    ereaders: List<EreaderSync>,
    line: SyncLineState,
    requestedAt: Long,
    now: Long,
    onSync: () -> Unit,
    onSetUp: () -> Unit,
    onDecisions: () -> Unit,
    devices: DeviceRoster = DeviceRoster.EMPTY,
    onRename: (String, String) -> Unit = { _, _ -> },
    onMerge: (String, String) -> Unit = { _, _ -> },
    onSeparate: (String) -> Unit = {},
    onForget: (String) -> Unit = {},
) {
    var openDevice by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val port by link.port.collectAsStateWithLifecycle()
    val entries by link.log.entries.collectAsStateWithLifecycle()
    var autoSync by remember { mutableStateOf(link.settings.autoSync) }
    var keepAwake by remember { mutableStateOf(link.settings.keepAwake) }
    // The notification's "Turn off" changes the setting outside this screen; follow the port.
    LaunchedEffect(port) { autoSync = link.settings.autoSync }
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
    val shown = (port ?: link.settings.port).let { p -> addresses.map { "$it:$p" } }
    DeviceScreen(
        ereaders = ereaders,
        line = line,
        requestedAt = requestedAt,
        now = now,
        autoSync = autoSync,
        onAutoSync = { on ->
            autoSync = on
            if (on) link.start() else link.stop()
        },
        keepAwake = keepAwake,
        onKeepAwake = {
            keepAwake = it
            link.settings.keepAwake = it
            // The lock is taken when the phone starts listening; restart to apply the change.
            if (port != null && link.settings.enabled) {
                link.stop()
                link.start()
            }
        },
        noWifi = addresses.isEmpty(),
        onOpenWifi = {
            runCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        },
        onSync = onSync,
        onSetUp = onSetUp,
        onDecisions = onDecisions,
        // The e-reader checks for changes every few minutes; that is not news.
        activity =
            entries.filterNot {
                it.route == "/folio/v1/hello" || it.route == "/folio/v2/hello"
            },
        onClearActivity = link.log::clear,
        onSignIn = { showSignIn = true },
        onSavePlugin = { savePlugin.launch("folio.koplugin.zip") },
        pluginSaved = pluginSaved,
        devices = devices.shown,
        onDevice = { openDevice = it.key },
    )
    // The device stays open while it is shown; merged into another or forgotten, the sheet closes.
    val device = openDevice?.let { key -> devices.shown.firstOrNull { it.key == key } }
    if (device != null)
        DeviceSheet(
            device,
            others = devices.shown.filter { it.key != device.key },
            now = now,
            onRename = { onRename(device.key, it) },
            onMerge = { other ->
                openDevice = null
                onMerge(device.key, other.key)
            },
            onSeparate = { member -> onSeparate(member.key) },
            onForget = {
                openDevice = null
                onForget(device.key)
            },
            onDismiss = { openDevice = null },
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
 * The Device tab, built around sync: each e-reader with its last sync and a Sync button, what needs
 * the owner's decision, the files the phone couldn't take, the two sync settings and the plugin
 * setup. Below, what happened lately, and the way to set an e-reader up by hand.
 */
@Composable
internal fun DeviceScreen(
    ereaders: List<EreaderSync>,
    line: SyncLineState,
    requestedAt: Long,
    now: Long,
    autoSync: Boolean,
    onAutoSync: (Boolean) -> Unit,
    keepAwake: Boolean,
    onKeepAwake: (Boolean) -> Unit,
    noWifi: Boolean,
    onOpenWifi: () -> Unit,
    onSync: () -> Unit,
    onSetUp: () -> Unit,
    onDecisions: () -> Unit,
    activity: List<LinkLog.Entry>,
    onClearActivity: () -> Unit,
    onSignIn: () -> Unit,
    onSavePlugin: () -> Unit,
    pluginSaved: Boolean? = null,
    /** Every device to list, newest first (forgotten ones left out). */
    devices: List<KnownDevice> = emptyList(),
    onDevice: (KnownDevice) -> Unit = {},
) {
    var showAllActivity by rememberSaveable { mutableStateOf(false) }
    val large = LocalDensity.current.fontScale > 1.5f
    val setUp = line.line != SyncLine.NotSetUp
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
                Modifier.padding(start = FolioSpace.page, end = FolioSpace.page, top = 12.dp)
                    .semantics { heading() },
        )
        if (!setUp) SetupCard(onSetUp, Modifier.padding(horizontal = FolioSpace.page))
        else
            Column(
                Modifier.padding(horizontal = FolioSpace.page),
                verticalArrangement = Arrangement.spacedBy(FolioSpace.gap),
            ) {
                // Set up from this phone but not synced yet: one card for the e-reader to come.
                ereaders
                    .ifEmpty { listOf(EreaderSync("")) }
                    .forEach { ereader ->
                        EreaderCard(ereader, requestedAt, now, noWifi, onSync, onOpenWifi)
                    }
            }
        if (line.decisions > 0)
            SettingsGroup(stringResource(R.string.m9sync_decisions_title)) {
                SettingsRow(
                    Icons.Outlined.ErrorOutline,
                    pluralStringResource(
                        R.plurals.m9sync_decisions,
                        line.decisions,
                        line.decisions,
                    ),
                    stringResource(R.string.m9sync_decisions_row),
                    Modifier.testTag(DEVICE_DECISIONS_TAG),
                    onClick = onDecisions,
                )
            }
        val refused = ereaders.flatMap { it.refused }
        if (refused.isNotEmpty()) RefusedFiles(refused)
        DeviceList(devices, now, onDevice)
        SettingsGroup(stringResource(R.string.m9sync_settings)) {
            SettingsSwitchRow(
                Icons.Outlined.Sync,
                stringResource(R.string.m9sync_auto),
                autoSync,
                onAutoSync,
                Modifier.testTag(DEVICE_AUTO_SYNC_TAG),
                value = stringResource(R.string.m9sync_auto_hint),
            )
            SettingsSwitchRow(
                Icons.Outlined.Schedule,
                stringResource(R.string.link_keep_awake),
                keepAwake,
                onKeepAwake,
                value = stringResource(R.string.link_keep_awake_hint),
            )
            if (setUp)
                SettingsRow(
                    Icons.Outlined.Refresh,
                    stringResource(R.string.m9sync_reinstall),
                    stringResource(R.string.m9sync_reinstall_hint),
                    Modifier.testTag(DEVICE_REINSTALL_TAG),
                    onClick = onSetUp,
                )
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
        SettingsGroup(stringResource(R.string.m9sync_by_hand)) {
            SettingsRow(
                Icons.Outlined.Extension,
                stringResource(R.string.link_plugin_save),
                stringResource(R.string.link_plugin_hint),
                onClick = onSavePlugin,
                trailing = { Icon(Icons.Outlined.SaveAlt, null) },
            )
            if (pluginSaved != null)
                PluginSavedNote(
                    pluginSaved,
                    Modifier.padding(horizontal = FolioSpace.item, vertical = 4.dp),
                )
            SettingsRow(
                Icons.Outlined.Key,
                stringResource(R.string.device_sign_in),
                stringResource(R.string.m9sync_sign_in_hint),
                onClick = onSignIn,
            )
        }
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

/** Shown until an e-reader is set up: what sync does, in two lines, and Set up. */
@Composable
private fun SetupCard(onSetUp: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth().testTag(DEVICE_SETUP_TAG),
        shape = FolioSpace.panelShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.padding(FolioSpace.page),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.gap),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.TabletAndroid, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(FolioSpace.gap))
                Text(
                    stringResource(R.string.device_setup_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                stringResource(R.string.m9sync_setup_card_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onSetUp, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.m9sync_set_up))
            }
        }
    }
}

/**
 * One e-reader: its name, its last sync in words, how many books both sides hold, and Sync now. A
 * phone with no Wi-Fi says so, with a way to Wi-Fi settings. Before the first sync, the one thing
 * to do on the e-reader.
 */
@Composable
private fun EreaderCard(
    ereader: EreaderSync,
    requestedAt: Long,
    now: Long,
    noWifi: Boolean,
    onSync: () -> Unit,
    onOpenWifi: () -> Unit,
) {
    val state = syncLine(listOf(ereader), requestedAt, pluginInstalled = true, hardcover = 0, now)
    val syncing = state.line is SyncLine.Syncing
    Surface(
        Modifier.fillMaxWidth().testTag(DEVICE_SYNC_CARD_TAG),
        shape = FolioSpace.panelShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.padding(FolioSpace.page),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.gap),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.TabletAndroid, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(FolioSpace.gap))
                Text(
                    ereader.name.ifBlank { stringResource(R.string.m9sync_your_ereader) },
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(syncCardText(state.line, now), style = MaterialTheme.typography.bodyMedium)
            if (ereader.books > 0)
                Text(
                    pluralStringResource(R.plurals.m9sync_books, ereader.books, ereader.books),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            if (ereader.lastSync <= 0 && !syncing)
                Text(
                    stringResource(R.string.m9sync_first_sync_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            if (noWifi) {
                Text(
                    stringResource(R.string.m9sync_no_wifi),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                TextButton(onClick = onOpenWifi, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(R.string.device_open_wifi))
                }
            }
            FilledTonalButton(
                onClick = onSync,
                enabled = !syncing,
                modifier = Modifier.heightIn(min = 48.dp).testTag(DEVICE_SYNC_BUTTON_TAG),
            ) {
                if (syncing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Sync, null, Modifier.size(18.dp))
                Spacer(Modifier.width(FolioSpace.small))
                Text(stringResource(R.string.m9sync_sync_now))
            }
        }
    }
}

/** The plain reason a file was refused. */
@Composable
internal fun refusalText(reason: Refusal): String =
    stringResource(
        when (reason) {
            Refusal.NOT_A_BOOK -> R.string.m9sync_refused_not_a_book
            Refusal.TOO_LARGE -> R.string.m9sync_refused_too_large
            Refusal.OTHER_FORMAT -> R.string.m9sync_refused_other_format
        }
    )

/** "Couldn't take: Scan.pdf — not a book Librillo can open", one line per file. */
@Composable
private fun RefusedFiles(refused: List<RefusedFile>) {
    SettingsGroup(stringResource(R.string.m9sync_refused_title)) {
        Column(
            Modifier.padding(horizontal = FolioSpace.item, vertical = FolioSpace.small)
                .testTag(DEVICE_REFUSED_TAG),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
        ) {
            refused.forEach { file ->
                Text(
                    stringResource(
                        R.string.m9sync_refused_line,
                        file.name,
                        refusalText(file.reason),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                stringResource(R.string.m9sync_refused_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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

/** The last few things that happened, one line each: when, then what. */
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
    FolioSheet(
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
                modifier = Modifier.weight(1f).semantics { heading() },
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
                Text(
                    stringResource(
                        R.string.link_log_line,
                        FolioDates.dayAndTime(entry.time),
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

/** The log string for a request to [route] that was answered with [status]. */
internal fun describeRoute(route: String, status: Int): Int =
    when {
        status == 401 || status == 429 -> R.string.link_log_refused
        route == "/users/auth" || route == "/users/create" -> R.string.link_log_login
        route == "/syncs/progress" -> R.string.link_log_sent
        route.startsWith("/syncs/progress/") -> R.string.link_log_asked
        route == "/folio/v2/sync" -> R.string.m9sync_log_sync_started
        route == "/folio/v2/finish" -> R.string.m9sync_log_sync_finished
        route.startsWith("/folio/v2/books/") -> R.string.link_log_downloaded
        route == "/folio/v2/upload" -> R.string.m9sync_log_uploaded
        route == "/folio/v2/hello" -> R.string.link_log_checked
        route == "/folio/v1/hello" || route == "/folio/v1/queue" -> R.string.link_log_checked
        route.startsWith("/folio/v1/books/") -> R.string.link_log_downloaded
        route == "/folio/v1/ack" -> R.string.link_log_saved
        route.startsWith("/opds") -> R.string.link_log_catalogue
        else -> R.string.link_log_other
    }

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
            stringResource(describeRoute(entry.route, entry.status), entry.remote)
    }

/**
 * What to type into KOReader's Progress sync to set it up by hand: the address, the username and
 * the password. The password is read only when the owner asks to see it, and never leaves this
 * sheet.
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
    FolioSheet(
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
                modifier = Modifier.semantics { heading() },
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
