@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package dev.sadking.folio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Wallpaper
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sadking.folio.FolioApplication
import dev.sadking.folio.R
import dev.sadking.folio.data.DeviceStatistics
import dev.sadking.folio.data.LinkStatus
import dev.sadking.folio.data.linkStatus
import dev.sadking.folio.link.FolioLink
import dev.sadking.folio.reader.ReaderDefaults
import dev.sadking.folio.reader.ReaderTextActions
import dev.sadking.folio.reader.ReaderTextSheet
import dev.sadking.folio.reader.ReaderTextState
import dev.sadking.folio.reader.ReaderTypography
import kotlinx.coroutines.delay

internal const val SETTINGS_TAG = "settings"
internal const val SETTINGS_THEME_TAG = "settings-theme"
internal const val SETTINGS_WALLPAPER_TAG = "settings-wallpaper"
internal const val SETTINGS_TEXT_TAG = "settings-text"
internal const val SETTINGS_VOLUME_TAG = "settings-volume"
internal const val SETTINGS_READING_TIME_TAG = "settings-reading-time"
internal const val SETTINGS_REFRESH_TAG = "settings-refresh"
internal const val SETTINGS_LINK_TAG = "settings-link"
internal const val SETTINGS_KEEP_AWAKE_TAG = "settings-keep-awake"
internal const val SETTINGS_HARDCOVER_AUTOMATIC_TAG = "settings-hardcover-automatic"
internal const val SETTINGS_HARDCOVER_TAG = "settings-hardcover"
internal const val SETTINGS_HARDCOVER_IMPORT_TAG = "settings-hardcover-import"
internal const val SETTINGS_REMOVED_TAG = "settings-removed"
internal const val SETTINGS_BACKUP_TAG = "settings-backup"
internal const val SETTINGS_IMPORTS_TAG = "settings-imports"
internal const val SETTINGS_SSH_TAG = "settings-ssh"
internal const val SETTINGS_FOLDER_TAG = "settings-folder"
internal const val SETTINGS_PLUGIN_TAG = "settings-plugin"
internal const val TEXT_SHEET_TAG = "settings-text-sheet"
internal const val BACKUP_EXPORT_TAG = "settings-backup-export"
internal const val BACKUP_RESTORE_TAG = "settings-backup-restore"

/** The reader's defaults as the Settings tab shows them; the reader reads the same preferences. */
internal data class ReadingDefaults(
    val typography: ReaderTypography = ReaderTypography(),
    val pageTheme: String = "System",
    val volumeKeys: Boolean = true,
)

/**
 * The Settings tab wired to the app: Folio Link's switches, the reader's defaults and the Hardcover
 * outbox come from the application; everything else is passed through to [SettingsScreen], which
 * previews render directly.
 */
@Composable
internal fun SettingsTab(
    appearance: String,
    dynamic: Boolean,
    onTheme: (String) -> Unit,
    onDynamic: (Boolean) -> Unit,
    statistics: DeviceStatistics?,
    onStatistics: () -> Unit,
    onRefreshReading: () -> Unit,
    hardcoverUsername: String,
    onHardcover: () -> Unit,
    onImportHardcover: () -> Unit,
    count: Int,
    recordCount: Int,
    removedCount: Int,
    onRemoved: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onImports: () -> Unit,
    sshHost: String,
    onConnection: () -> Unit,
    phoneFolder: String,
    onPhoneFolder: () -> Unit,
    onRefreshFolder: () -> Unit,
    onForgetFolder: () -> Unit,
    onInstallCompanion: () -> Unit,
    busy: Boolean,
) {
    val context = LocalContext.current
    val app = context.applicationContext as FolioApplication
    val link = app.link
    val port by link.port.collectAsStateWithLifecycle()
    var linkEnabled by remember { mutableStateOf(link.settings.enabled) }
    // The notification's "Turn off" changes the setting outside this screen; follow the port.
    LaunchedEffect(port) { linkEnabled = link.settings.enabled }
    var addresses by remember { mutableStateOf(FolioLink.addresses()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            addresses = FolioLink.addresses()
        }
    }
    var keepAwake by remember { mutableStateOf(link.settings.keepAwake) }
    var automatic by remember { mutableStateOf(app.hardcoverOutbox.automatic) }
    val readerPrefs = remember { context.getSharedPreferences(ReaderDefaults.PREFERENCES, 0) }
    var reading by remember {
        mutableStateOf(
            ReadingDefaults(
                ReaderDefaults.typography(readerPrefs),
                ReaderDefaults.pageTheme(readerPrefs),
                ReaderDefaults.volumeKeys(readerPrefs),
            )
        )
    }
    SettingsScreen(
        appearance = appearance,
        dynamic = dynamic,
        onTheme = onTheme,
        onDynamic = onDynamic,
        reading = reading,
        onReading = { value ->
            reading = value
            ReaderDefaults.saveTypography(readerPrefs, value.typography)
            ReaderDefaults.savePageTheme(readerPrefs, value.pageTheme)
            ReaderDefaults.saveVolumeKeys(readerPrefs, value.volumeKeys)
        },
        statistics = statistics,
        onStatistics = onStatistics,
        onRefreshReading = onRefreshReading,
        linkStatus = linkStatus(linkEnabled, port, addresses),
        linkAddress = port?.let { p -> addresses.firstOrNull()?.let { "$it:$p" } } ?: "",
        onLink = { on ->
            linkEnabled = on
            if (on) link.start() else link.stop()
        },
        keepAwake = keepAwake,
        onKeepAwake = { on ->
            keepAwake = on
            link.settings.keepAwake = on
            // The lock is taken when Link starts; restart it to apply the change.
            if (link.settings.enabled) {
                link.stop()
                link.start()
            }
        },
        hardcoverAutomatic = automatic,
        onHardcoverAutomatic = {
            automatic = it
            app.hardcoverOutbox.automatic = it
        },
        hardcoverUsername = hardcoverUsername,
        onHardcover = onHardcover,
        onImportHardcover = onImportHardcover,
        count = count,
        recordCount = recordCount,
        removedCount = removedCount,
        onRemoved = onRemoved,
        onBackup = onBackup,
        onRestore = onRestore,
        onImports = onImports,
        sshHost = sshHost,
        onConnection = onConnection,
        phoneFolder = phoneFolder,
        onPhoneFolder = onPhoneFolder,
        onRefreshFolder = onRefreshFolder,
        onForgetFolder = onForgetFolder,
        onInstallCompanion = onInstallCompanion,
        busy = busy,
    )
}

/**
 * Settings as grouped rows: an icon, a title and the current value on one line. Switches sit
 * inline; anything with more to say opens a sheet. No paragraphs.
 */
@Composable
internal fun SettingsScreen(
    appearance: String,
    dynamic: Boolean,
    onTheme: (String) -> Unit,
    onDynamic: (Boolean) -> Unit,
    reading: ReadingDefaults = ReadingDefaults(),
    onReading: (ReadingDefaults) -> Unit = {},
    statistics: DeviceStatistics? = null,
    onStatistics: () -> Unit = {},
    onRefreshReading: () -> Unit = {},
    linkStatus: LinkStatus = LinkStatus.OFF,
    linkAddress: String = "",
    onLink: (Boolean) -> Unit = {},
    keepAwake: Boolean = false,
    onKeepAwake: (Boolean) -> Unit = {},
    hardcoverAutomatic: Boolean = false,
    onHardcoverAutomatic: (Boolean) -> Unit = {},
    hardcoverUsername: String = "",
    onHardcover: () -> Unit = {},
    onImportHardcover: () -> Unit = {},
    count: Int = 0,
    recordCount: Int = count,
    removedCount: Int = 0,
    onRemoved: () -> Unit = {},
    onBackup: () -> Unit = {},
    onRestore: () -> Unit = {},
    onImports: () -> Unit = {},
    sshHost: String = "",
    onConnection: () -> Unit = {},
    phoneFolder: String = "",
    onPhoneFolder: () -> Unit = {},
    onRefreshFolder: () -> Unit = {},
    onForgetFolder: () -> Unit = {},
    onInstallCompanion: () -> Unit = {},
    busy: Boolean = false,
) {
    val large = LocalDensity.current.fontScale > 1.5f
    val paired = sshHost.isNotBlank()
    var showText by rememberSaveable { mutableStateOf(false) }
    var showReadingTime by rememberSaveable { mutableStateOf(false) }
    var showBackup by rememberSaveable { mutableStateOf(false) }
    var showFolder by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = LocalContentBottomPadding.current + FolioSpace.section)
            .testTag(SETTINGS_TAG),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.section),
    ) {
        Text(
            stringResource(R.string.settings_title),
            style =
                if (large) MaterialTheme.typography.headlineSmall
                else MaterialTheme.typography.headlineMedium,
            modifier =
                Modifier.padding(start = FolioSpace.page, end = FolioSpace.page, top = 12.dp),
        )
        SettingsGroup(stringResource(R.string.settings_appearance)) {
            ThemeRow(appearance, onTheme)
            SettingsSwitchRow(
                Icons.Outlined.Wallpaper,
                stringResource(R.string.settings_wallpaper_colours),
                dynamic,
                onDynamic,
                Modifier.testTag(SETTINGS_WALLPAPER_TAG),
            )
        }
        SettingsGroup(stringResource(R.string.settings_reading)) {
            SettingsRow(
                Icons.Outlined.TextFields,
                stringResource(R.string.settings_text),
                stringResource(
                    R.string.settings_text_summary,
                    reading.typography.fontSize.toInt(),
                    stringResource(
                        if (reading.typography.serif) R.string.appearance_serif
                        else R.string.appearance_sans
                    ),
                ),
                Modifier.testTag(SETTINGS_TEXT_TAG),
                onClick = { showText = true },
            )
            SettingsSwitchRow(
                Icons.Outlined.VolumeUp,
                stringResource(R.string.appearance_volume_keys),
                reading.volumeKeys,
                { onReading(reading.copy(volumeKeys = it)) },
                Modifier.testTag(SETTINGS_VOLUME_TAG),
            )
            SettingsRow(
                Icons.Outlined.Timer,
                stringResource(R.string.reading_time_title),
                readingTimeSummary(statistics),
                Modifier.testTag(SETTINGS_READING_TIME_TAG),
                onClick = { showReadingTime = true },
            )
            SettingsRow(
                Icons.Outlined.Sync,
                stringResource(R.string.reading_refresh),
                stringResource(R.string.settings_refresh_value),
                Modifier.testTag(SETTINGS_REFRESH_TAG),
                onClick = onRefreshReading,
                enabled = !busy && paired,
            )
        }
        SettingsGroup(stringResource(R.string.settings_link)) {
            SettingsSwitchRow(
                Icons.Outlined.Link,
                stringResource(R.string.link_title),
                linkStatus != LinkStatus.OFF,
                onLink,
                Modifier.testTag(SETTINGS_LINK_TAG),
                value =
                    when (linkStatus) {
                        LinkStatus.OFF -> stringResource(R.string.device_link_off)
                        LinkStatus.STARTING -> stringResource(R.string.link_starting)
                        LinkStatus.WAITING_FOR_NETWORK ->
                            stringResource(R.string.device_link_waiting_network)
                        LinkStatus.ON -> linkAddress
                    },
            )
            SettingsSwitchRow(
                Icons.Outlined.Schedule,
                stringResource(R.string.link_keep_awake),
                keepAwake,
                onKeepAwake,
                Modifier.testTag(SETTINGS_KEEP_AWAKE_TAG),
                value = stringResource(R.string.link_keep_awake_hint),
            )
            SettingsSwitchRow(
                Icons.Outlined.Sync,
                stringResource(R.string.hardcover_automatic),
                hardcoverAutomatic,
                onHardcoverAutomatic,
                Modifier.testTag(SETTINGS_HARDCOVER_AUTOMATIC_TAG),
            )
        }
        SettingsGroup(stringResource(R.string.settings_hardcover)) {
            SettingsRow(
                Icons.Outlined.AccountCircle,
                stringResource(R.string.settings_hardcover_account),
                if (hardcoverUsername.isBlank())
                    stringResource(R.string.settings_hardcover_not_connected)
                else stringResource(R.string.settings_hardcover_connected, hardcoverUsername),
                Modifier.testTag(SETTINGS_HARDCOVER_TAG),
                onClick = onHardcover,
                enabled = !busy,
            )
            SettingsRow(
                Icons.Outlined.CloudDownload,
                stringResource(R.string.settings_hardcover_import),
                stringResource(R.string.settings_hardcover_import_value),
                Modifier.testTag(SETTINGS_HARDCOVER_IMPORT_TAG),
                onClick = onImportHardcover,
                enabled = !busy,
            )
        }
        SettingsGroup(stringResource(R.string.settings_library)) {
            SettingsRow(
                Icons.Outlined.Storage,
                stringResource(R.string.settings_storage),
                pluralStringResource(R.plurals.settings_storage_entries, recordCount, recordCount) +
                    " · " +
                    pluralStringResource(R.plurals.settings_storage_files, count, count),
            )
            SettingsRow(
                Icons.Outlined.DeleteOutline,
                stringResource(R.string.settings_recently_removed),
                if (removedCount == 0) stringResource(R.string.settings_removed_none)
                else
                    pluralStringResource(
                        R.plurals.settings_removed_count,
                        removedCount,
                        removedCount,
                    ),
                Modifier.testTag(SETTINGS_REMOVED_TAG),
                onClick = onRemoved,
                enabled = !busy,
            )
            SettingsRow(
                Icons.Outlined.Save,
                stringResource(R.string.settings_backup),
                stringResource(R.string.settings_backup_value),
                Modifier.testTag(SETTINGS_BACKUP_TAG),
                onClick = { showBackup = true },
                enabled = !busy,
            )
            SettingsRow(
                Icons.Outlined.History,
                stringResource(R.string.settings_last_import),
                stringResource(R.string.settings_last_import_value),
                Modifier.testTag(SETTINGS_IMPORTS_TAG),
                onClick = onImports,
                enabled = !busy,
            )
        }
        SettingsGroup(stringResource(R.string.settings_advanced)) {
            SettingsRow(
                Icons.Outlined.Cable,
                stringResource(R.string.settings_ssh),
                if (paired) sshHost else stringResource(R.string.settings_ssh_not_set_up),
                Modifier.testTag(SETTINGS_SSH_TAG),
                onClick = onConnection,
                enabled = !busy,
                badge = stringResource(R.string.settings_ssh_legacy),
            )
            SettingsRow(
                Icons.Outlined.FolderOpen,
                stringResource(R.string.settings_folder),
                phoneFolder.ifBlank { stringResource(R.string.settings_folder_none) },
                Modifier.testTag(SETTINGS_FOLDER_TAG),
                onClick = { showFolder = true },
                enabled = !busy,
            )
            SettingsRow(
                Icons.Outlined.Extension,
                stringResource(R.string.settings_plugin_install),
                stringResource(R.string.settings_plugin_value),
                Modifier.testTag(SETTINGS_PLUGIN_TAG),
                onClick = onInstallCompanion,
                enabled = !busy && paired,
            )
        }
        SettingsGroup(stringResource(R.string.settings_about)) {
            SettingsRow(
                Icons.Outlined.Info,
                stringResource(
                    R.string.settings_about_version,
                    dev.sadking.folio.BuildConfig.VERSION_NAME,
                ),
                stringResource(R.string.settings_about_line),
            )
        }
    }
    if (showText) ReadingDefaultsSheet(reading, onReading) { showText = false }
    if (showReadingTime)
        ReadingTimeSheet(statistics, onStatistics, enabled = !busy && paired) {
            showReadingTime = false
        }
    if (showBackup)
        BackupSheet(
            onExport = {
                showBackup = false
                onBackup()
            },
            onRestore = {
                showBackup = false
                onRestore()
            },
            busy = busy,
        ) {
            showBackup = false
        }
    if (showFolder)
        PhoneFolderSheet(
            phoneFolder,
            onChoose = {
                showFolder = false
                onPhoneFolder()
            },
            onRefresh = onRefreshFolder,
            onForget = {
                showFolder = false
                onForgetFolder()
            },
            busy = busy,
        ) {
            showFolder = false
        }
}

/** Theme as three segments under the row title, full width, so no size squeezes the label. */
@Composable
private fun ThemeRow(appearance: String, onTheme: (String) -> Unit) {
    // "System", "Light" and "Dark" are stored preference values; only the label is translated.
    val choices =
        listOf(
            "System" to R.string.appearance_theme_system,
            "Light" to R.string.appearance_theme_light,
            "Dark" to R.string.appearance_theme_dark,
        )
    SettingsRow(Icons.Outlined.Palette, stringResource(R.string.settings_theme))
    SingleChoiceSegmentedButtonRow(
        Modifier.fillMaxWidth()
            .padding(start = FolioSpace.item, end = FolioSpace.item, bottom = 12.dp)
            .testTag(SETTINGS_THEME_TAG)
    ) {
        choices.forEachIndexed { index, (mode, label) ->
            SegmentedButton(
                selected = appearance == mode,
                onClick = { onTheme(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                label = { Text(stringResource(label), maxLines = 1) },
            )
        }
    }
}

@Composable
private fun readingTimeSummary(statistics: DeviceStatistics?): String =
    statistics?.let {
        stringResource(
            R.string.reading_time_total,
            it.totalSeconds / 3600,
            (it.totalSeconds % 3600) / 60,
        )
    } ?: stringResource(R.string.reading_time_none)

@Composable
private fun SettingsSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = FolioSpace.sheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            content = content,
        )
    }
}

/** A title and one quiet line, the way every settings sheet opens. */
@Composable
private fun SheetHeading(title: String, line: String? = null) {
    Column(
        Modifier.padding(horizontal = FolioSpace.section),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (line != null)
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}

/** A switch row inside a sheet: no icon, the title does the work. */
@Composable
private fun SheetSwitch(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    val haptics = rememberHaptics()
    Row(
        Modifier.fillMaxWidth()
            .toggleable(checked, role = Role.Switch) {
                haptics.toggle(it)
                onChecked(it)
            }
            .padding(horizontal = FolioSpace.section, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, null)
    }
}

/**
 * The reader's text defaults: the reader's own Text sheet, applied to every book, without "Only for
 * this book". One design for the same choices wherever they are made.
 */
@Composable
internal fun ReadingDefaultsSheet(
    reading: ReadingDefaults,
    onReading: (ReadingDefaults) -> Unit,
    onDismiss: () -> Unit,
) {
    // Not SettingsSheet: the Text sheet scrolls itself, and a scroll inside a scroll fails.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = FolioSpace.sheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        ReadingDefaultsContent(reading, onReading)
    }
}

@Composable
internal fun ReadingDefaultsContent(
    reading: ReadingDefaults,
    onReading: (ReadingDefaults) -> Unit,
) {
    val text = reading.typography
    fun typography(value: ReaderTypography) = onReading(reading.copy(typography = value.bounded()))
    ReaderTextSheet(
        ReaderTextState(
            text.fontSize,
            reading.pageTheme,
            text.serif,
            text.scroll,
            text.lineHeight,
            text.margins,
            text.publisherStyles,
        ),
        ReaderTextActions(
            onSize = { typography(text.copy(fontSize = it)) },
            onTheme = { onReading(reading.copy(pageTheme = it)) },
            onSerif = { typography(text.copy(serif = it)) },
            onSpacing = { typography(text.copy(lineHeight = it)) },
            onMargins = { typography(text.copy(margins = it)) },
            onBookStyles = { typography(text.copy(publisherStyles = it)) },
            onScroll = { typography(text.copy(scroll = it)) },
            onBookOnly = null,
        ),
        subtitle = stringResource(R.string.settings_text_body),
        tag = TEXT_SHEET_TAG,
    )
}

/** KOReader's reading statistics, fetched over the e-reader connection. */
@Composable
internal fun ReadingTimeSheet(
    statistics: DeviceStatistics?,
    onStatistics: () -> Unit,
    enabled: Boolean,
    onDismiss: () -> Unit,
) {
    SettingsSheet(onDismiss) { ReadingTimeContent(statistics, onStatistics, enabled) }
}

@Composable
internal fun ReadingTimeContent(
    statistics: DeviceStatistics?,
    onStatistics: () -> Unit,
    enabled: Boolean,
) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.item),
    ) {
        SheetHeading(stringResource(R.string.reading_time_title), readingTimeSummary(statistics))
        statistics?.let { stats ->
            Text(
                stringResource(
                    R.string.reading_time_exported,
                    java.text.DateFormat.getDateTimeInstance()
                        .format(java.util.Date(stats.exportedAt * 1000)),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = FolioSpace.section),
            )
        }
        FilledTonalButton(
            onClick = onStatistics,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(horizontal = FolioSpace.section),
        ) {
            Text(stringResource(R.string.reading_time_get))
        }
        if (statistics == null)
            Text(
                stringResource(R.string.reading_time_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = FolioSpace.section),
            )
        statistics?.let { stats ->
            val days = org.json.JSONArray(stats.days)
            Column(Modifier.padding(horizontal = FolioSpace.section)) {
                repeat(minOf(days.length(), 30)) { index ->
                    val day = days.getJSONObject(index)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(day.getString("date"), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(
                                R.string.reading_time_minutes,
                                day.getLong("seconds") / 60,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

/** Export or restore. Older backup formats still restore; the sheet only picks the file. */
@Composable
internal fun BackupSheet(
    onExport: () -> Unit,
    onRestore: () -> Unit,
    busy: Boolean,
    onDismiss: () -> Unit,
) {
    SettingsSheet(onDismiss) { BackupContent(onExport, onRestore, busy) }
}

@Composable
internal fun BackupContent(onExport: () -> Unit, onRestore: () -> Unit, busy: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
    ) {
        SheetHeading(
            stringResource(R.string.settings_backup),
            stringResource(R.string.settings_backup_body),
        )
        Column(Modifier.padding(horizontal = FolioSpace.small)) {
            SettingsRow(
                Icons.Outlined.FileUpload,
                stringResource(R.string.settings_backup_export),
                modifier = Modifier.testTag(BACKUP_EXPORT_TAG),
                onClick = onExport,
                enabled = !busy,
            )
            SettingsRow(
                Icons.Outlined.FileDownload,
                stringResource(R.string.settings_backup_restore),
                stringResource(R.string.settings_restore_body),
                Modifier.testTag(BACKUP_RESTORE_TAG),
                onClick = onRestore,
                enabled = !busy,
            )
        }
    }
}

/** The paired phone folder: where it is, and the three things you can do with it. */
@Composable
internal fun PhoneFolderSheet(
    folder: String,
    onChoose: () -> Unit,
    onRefresh: () -> Unit,
    onForget: () -> Unit,
    busy: Boolean,
    onDismiss: () -> Unit,
) {
    SettingsSheet(onDismiss) { PhoneFolderContent(folder, onChoose, onRefresh, onForget, busy) }
}

@Composable
internal fun PhoneFolderContent(
    folder: String,
    onChoose: () -> Unit,
    onRefresh: () -> Unit,
    onForget: () -> Unit,
    busy: Boolean,
) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.item),
    ) {
        SheetHeading(
            stringResource(R.string.settings_folder),
            folder.ifBlank { stringResource(R.string.settings_folder_none) },
        )
        Text(
            stringResource(R.string.settings_folder_body),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = FolioSpace.section),
        )
        FilledTonalButton(
            onClick = onChoose,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(horizontal = FolioSpace.section),
        ) {
            Text(
                stringResource(
                    if (folder.isBlank()) R.string.settings_folder_choose
                    else R.string.settings_folder_change
                )
            )
        }
        if (folder.isNotBlank())
            Row(
                Modifier.padding(horizontal = FolioSpace.item),
                horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
            ) {
                TextButton(onClick = onRefresh, enabled = !busy) {
                    Text(stringResource(R.string.settings_folder_refresh))
                }
                TextButton(onClick = onForget, enabled = !busy) {
                    Text(stringResource(R.string.settings_folder_disconnect))
                }
            }
    }
}
