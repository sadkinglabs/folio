@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package dev.sadking.folio.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Save
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.sadking.folio.CrashReports
import dev.sadking.folio.FolioApplication
import dev.sadking.folio.R
import dev.sadking.folio.data.BooksFolder
import dev.sadking.folio.data.BooksFolderMove
import dev.sadking.folio.data.DeviceStatistics
import dev.sadking.folio.data.FolderScanNotes
import dev.sadking.folio.device.HardcoverSettings
import dev.sadking.folio.reader.ReaderDefaults
import dev.sadking.folio.reader.ReaderTextActions
import dev.sadking.folio.reader.ReaderTextSheet
import dev.sadking.folio.reader.ReaderTextState
import dev.sadking.folio.reader.ReaderTypography
import dev.sadking.folio.sync.HardcoverSyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val SETTINGS_TAG = "settings"
internal const val SETTINGS_THEME_TAG = "settings-theme"
internal const val SETTINGS_WALLPAPER_TAG = "settings-wallpaper"
internal const val SETTINGS_TEXT_TAG = "settings-text"
internal const val SETTINGS_VOLUME_TAG = "settings-volume"
internal const val SETTINGS_READING_TIME_TAG = "settings-reading-time"
internal const val SETTINGS_HARDCOVER_SYNC_TAG = "settings-hardcover-sync"
internal const val SETTINGS_HARDCOVER_LINE_TAG = "settings-hardcover-line"
internal const val SETTINGS_HARDCOVER_SYNC_NOW_TAG = "settings-hardcover-sync-now"
internal const val SETTINGS_HARDCOVER_SEND_FINISHED_TAG = "settings-hardcover-send-finished"
internal const val SETTINGS_HARDCOVER_TAG = "settings-hardcover"
internal const val SETTINGS_HARDCOVER_IMPORT_TAG = "settings-hardcover-import"
internal const val SETTINGS_REMOVED_TAG = "settings-removed"
internal const val SETTINGS_BACKUP_TAG = "settings-backup"
internal const val SETTINGS_IMPORTS_TAG = "settings-imports"
internal const val SETTINGS_SSH_TAG = "settings-ssh"
internal const val SETTINGS_FOLDER_TAG = "settings-folder"
internal const val SETTINGS_BOOKS_FOLDER_TAG = "settings-books-folder"
internal const val BOOKS_FOLDER_ALLOW_TAG = "books-folder-allow"
internal const val BOOKS_FOLDER_MOVE_TAG = "books-folder-move"
internal const val BOOKS_FOLDER_INBOX_TAG = "books-folder-inbox"
internal const val BOOKS_FOLDER_LAST_SCAN_TAG = "books-folder-last-scan"
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
 * The Settings tab wired to the app: the reader's defaults, Hardcover sync and Send Finished books
 * come from the application; everything else is passed through to [SettingsScreen], which previews
 * render directly. [onDecisions] opens Needs your decision.
 */
@Composable
internal fun SettingsTab(
    appearance: String,
    dynamic: Boolean,
    onTheme: (String) -> Unit,
    onDynamic: (Boolean) -> Unit,
    statistics: DeviceStatistics?,
    hardcoverUsername: String,
    onHardcover: () -> Unit,
    onImportHardcover: () -> Unit,
    hardcoverStatus: HardcoverSyncStatus,
    onHardcoverSync: (Boolean) -> Unit,
    onHardcoverSyncNow: () -> Unit,
    onDecisions: () -> Unit,
    now: Long,
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
    busy: Boolean,
    onMoveBooks: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as FolioApplication
    val dao = remember { app.database.library() }
    val allBooks by dao.books().collectAsState(emptyList())
    val allWishes by dao.wishes().collectAsState(emptyList())
    val allReading by dao.reading().collectAsState(emptyList())
    val observations by dao.hardcoverObservations().collectAsState(emptyList())
    val account = remember(hardcoverUsername) { HardcoverSettings(app).accountId }
    val finished =
        remember(allBooks, allWishes, allReading, observations, account) {
            finishedOnHardcover(
                allBooks,
                allWishes,
                allReading,
                observations
                    .filter { it.accountId == account && it.status == 3 }
                    .map { it.hardcoverId }
                    .toSet(),
            )
        }
    var finishedSent by remember { mutableStateOf(false) }
    // Read when Settings opens: the report is made in the background at app start.
    val crashUnseen by CrashReports.unseen.collectAsState()
    val crashReport by
        androidx.compose.runtime.produceState<String?>(null, crashUnseen) {
            value =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { CrashReports.report(context) }.getOrNull()
                }
        }
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
        hardcoverStatus = hardcoverStatus,
        onHardcoverSync = onHardcoverSync,
        onHardcoverSyncNow = onHardcoverSyncNow,
        onDecisions = onDecisions,
        now = now,
        finishedToSend = finished,
        finishedSent = finishedSent,
        onSendFinished = {
            finished.ids.forEach { app.hardcoverOutbox.queueFinish(it, null, explicit = true) }
            finishedSent = true
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
        busy = busy,
        onMoveBooks = onMoveBooks,
        onShareCrashReport = crashReport?.let { report -> { shareCrashReport(context, report) } },
    )
}

/** Opens the share sheet with the crash report as plain text. */
internal fun shareCrashReport(context: android.content.Context, report: String) {
    try {
        context.startActivity(
            CrashReports.shareIntent(report, context.getString(R.string.crash_report_subject))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: android.content.ActivityNotFoundException) {}
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
    hardcoverStatus: HardcoverSyncStatus = HardcoverSyncStatus(),
    onHardcoverSync: (Boolean) -> Unit = {},
    onHardcoverSyncNow: () -> Unit = {},
    onDecisions: () -> Unit = {},
    now: Long = System.currentTimeMillis(),
    finishedToSend: FinishedToSend = FinishedToSend(emptyList(), 0, 0),
    finishedSent: Boolean = false,
    onSendFinished: () -> Unit = {},
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
    onMoveBooks: () -> Unit = {},
    busy: Boolean = false,
    /** Shares the last crash report; null when there is none. */
    onShareCrashReport: (() -> Unit)? = null,
) {
    val large = LocalDensity.current.fontScale > 1.5f
    val paired = sshHost.isNotBlank()
    var showText by rememberSaveable { mutableStateOf(false) }
    var showReadingTime by rememberSaveable { mutableStateOf(false) }
    var showBackup by rememberSaveable { mutableStateOf(false) }
    var showFolder by rememberSaveable { mutableStateOf(false) }
    var showBooksFolder by rememberSaveable { mutableStateOf(false) }
    val booksFolder = rememberBooksFolder(busy)
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
            // Reading time came from the old plugin over SSH; what was fetched stays readable.
            if (statistics != null)
                SettingsRow(
                    Icons.Outlined.Timer,
                    stringResource(R.string.reading_time_title),
                    readingTimeSummary(statistics),
                    Modifier.testTag(SETTINGS_READING_TIME_TAG),
                    onClick = { showReadingTime = true },
                )
        }
        HardcoverSettings(
            hardcoverUsername,
            hardcoverStatus,
            now,
            onHardcover = onHardcover,
            onImportHardcover = onImportHardcover,
            onSync = onHardcoverSync,
            onSyncNow = onHardcoverSyncNow,
            onDecisions = onDecisions,
            finishedToSend = finishedToSend,
            finishedSent = finishedSent,
            onSendFinished = onSendFinished,
            busy = busy,
        )
        SettingsGroup(stringResource(R.string.settings_library)) {
            SettingsRow(
                Icons.Outlined.FolderSpecial,
                stringResource(R.string.settings_books_folder),
                booksFolder.shown +
                    " · " +
                    when {
                        !booksFolder.hasAccess -> stringResource(R.string.books_folder_state_access)
                        booksFolder.pending > 0 ->
                            pluralStringResource(
                                R.plurals.books_folder_state_pending,
                                booksFolder.pending,
                                booksFolder.pending,
                            )
                        else -> stringResource(R.string.books_folder_state_in_use)
                    },
                Modifier.testTag(SETTINGS_BOOKS_FOLDER_TAG),
                onClick = { showBooksFolder = true },
            )
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
            )
            SettingsRow(
                Icons.Outlined.FolderOpen,
                stringResource(R.string.settings_folder),
                phoneFolder.ifBlank { stringResource(R.string.settings_folder_none) },
                Modifier.testTag(SETTINGS_FOLDER_TAG),
                onClick = { showFolder = true },
                enabled = !busy,
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
            SettingsRow(
                Icons.Outlined.BugReport,
                stringResource(R.string.crash_share_report),
                stringResource(
                    if (onShareCrashReport != null) R.string.crash_report_ready
                    else R.string.crash_report_none
                ),
                onClick = onShareCrashReport,
                enabled = onShareCrashReport != null,
            )
        }
    }
    if (showText) ReadingDefaultsSheet(reading, onReading) { showText = false }
    if (showReadingTime) ReadingTimeSheet(statistics) { showReadingTime = false }
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
    if (showBooksFolder)
        SettingsSheet({ showBooksFolder = false }) {
            BooksFolderContent(
                booksFolder.shown,
                booksFolder.hasAccess,
                onAllow = booksFolder.allow,
                onChange = booksFolder.change,
                refused = booksFolder.refused,
                pending = booksFolder.pending,
                canChange = booksFolder.inFolder == 0,
                inbox = booksFolder.inbox,
                lastScanAt = booksFolder.lastScan?.at,
                lastScanMessage = booksFolder.lastScan?.message.orEmpty(),
                busy = busy,
                onMove = {
                    showBooksFolder = false
                    onMoveBooks()
                },
            )
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

/**
 * Settings' Hardcover group: the account, the "Sync with Hardcover" switch with its status line and
 * Sync now, Import library, and Send Finished books.
 */
@Composable
internal fun HardcoverSettings(
    hardcoverUsername: String,
    hardcoverStatus: HardcoverSyncStatus,
    now: Long,
    onHardcover: () -> Unit = {},
    onImportHardcover: () -> Unit = {},
    onSync: (Boolean) -> Unit = {},
    onSyncNow: () -> Unit = {},
    onDecisions: () -> Unit = {},
    finishedToSend: FinishedToSend = FinishedToSend(emptyList(), 0, 0),
    finishedSent: Boolean = false,
    onSendFinished: () -> Unit = {},
    busy: Boolean = false,
) {
    var confirmFinished by rememberSaveable { mutableStateOf(false) }
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
        SettingsSwitchRow(
            Icons.Outlined.Sync,
            stringResource(R.string.hc10_sync_switch),
            hardcoverStatus.enabled,
            onSync,
            Modifier.testTag(SETTINGS_HARDCOVER_SYNC_TAG),
            value = stringResource(R.string.hc10_sync_hint),
        )
        hardcoverLine(hardcoverStatus)?.let { line ->
            HardcoverStatusRow(
                line,
                now,
                onSyncNow = onSyncNow,
                // A rejected token is fixed in the account sheet; matches and questions wait
                // in Needs your decision.
                onClick =
                    when {
                        line == HardcoverLine.TokenRejected -> onHardcover
                        hardcoverStatus.toCheck > 0 -> onDecisions
                        else -> null
                    },
                enabled = !busy,
            )
        }
        SettingsRow(
            Icons.Outlined.CloudDownload,
            stringResource(R.string.settings_hardcover_import),
            stringResource(R.string.settings_hardcover_import_value),
            Modifier.testTag(SETTINGS_HARDCOVER_IMPORT_TAG),
            onClick = onImportHardcover,
            enabled = !busy,
        )
        if (hardcoverUsername.isNotBlank()) {
            val toSend = finishedToSend.ids.size
            SettingsRow(
                Icons.Outlined.DoneAll,
                stringResource(R.string.hardcover_send_finished),
                when {
                    finishedSent -> stringResource(R.string.hardcover_send_finished_queued)
                    toSend > 0 ->
                        pluralStringResource(
                            R.plurals.hardcover_send_finished_value,
                            toSend,
                            toSend,
                        )
                    finishedToSend.unmatched > 0 ->
                        pluralStringResource(
                            R.plurals.hardcover_send_finished_unmatched,
                            finishedToSend.unmatched,
                            finishedToSend.unmatched,
                        )
                    finishedToSend.alreadyRead > 0 ->
                        stringResource(R.string.hardcover_send_finished_all_read)
                    else -> stringResource(R.string.hardcover_send_finished_none)
                },
                Modifier.testTag(SETTINGS_HARDCOVER_SEND_FINISHED_TAG),
                onClick = { confirmFinished = true },
                enabled = !busy && !finishedSent && toSend > 0,
            )
        }
    }
    if (confirmFinished)
        AlertDialog(
            onDismissRequest = { confirmFinished = false },
            title = {
                Text(
                    pluralStringResource(
                        R.plurals.hardcover_send_finished_title,
                        finishedToSend.ids.size,
                        finishedToSend.ids.size,
                    )
                )
            },
            text = { Text(stringResource(R.string.hardcover_send_finished_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmFinished = false
                        onSendFinished()
                    }
                ) {
                    Text(stringResource(R.string.hardcover_send_finished_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmFinished = false }) {
                    Text(stringResource(R.string.shell_cancel))
                }
            },
        )
}

/**
 * Hardcover sync's state in one line under its switch, with Sync now beside it. While a run is
 * under way, a small ring takes the button's place. At large text the button goes under the line.
 * Tapping the line, when [onClick] is set, opens what the line asks for.
 */
@Composable
private fun HardcoverStatusRow(
    line: HardcoverLine,
    now: Long,
    onSyncNow: () -> Unit,
    onClick: (() -> Unit)?,
    enabled: Boolean,
) {
    val stacked = LocalDensity.current.fontScale > 1.5f
    val problem = line == HardcoverLine.TokenRejected
    val running = line == HardcoverLine.Syncing || line is HardcoverLine.Matching
    val action: @Composable () -> Unit = {
        when {
            line == HardcoverLine.Off || problem -> {}
            running ->
                // A still ring in previews and screenshots: a spinning one never lets them settle.
                if (androidx.compose.ui.platform.LocalInspectionMode.current)
                    CircularProgressIndicator(
                        progress = { 0.7f },
                        modifier = Modifier.padding(horizontal = 12.dp).size(20.dp),
                        strokeWidth = 2.dp,
                    )
                else
                    CircularProgressIndicator(
                        Modifier.padding(horizontal = 12.dp).size(20.dp),
                        strokeWidth = 2.dp,
                    )
            else ->
                TextButton(
                    onClick = onSyncNow,
                    enabled = enabled,
                    modifier = Modifier.testTag(SETTINGS_HARDCOVER_SYNC_NOW_TAG),
                ) {
                    Text(stringResource(R.string.hc10_sync_now))
                }
        }
    }
    val text: @Composable (Modifier) -> Unit = { modifier ->
        Text(
            hardcoverLineText(line, now),
            style = MaterialTheme.typography.bodyMedium,
            color =
                if (problem) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    }
    val icon: @Composable () -> Unit = {
        Icon(
            if (problem) Icons.Outlined.ErrorOutline else Icons.Outlined.CloudSync,
            null,
            tint =
                if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
    // The line is one node, read out as it changes; Sync now stays a button of its own.
    val lineModifier =
        (if (onClick != null)
                Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            else Modifier.semantics(mergeDescendants = true) {})
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(SETTINGS_HARDCOVER_LINE_TAG)
    if (stacked)
        Column(
            Modifier.fillMaxWidth()
                .then(lineModifier)
                .heightIn(min = 56.dp)
                .padding(horizontal = FolioSpace.item, vertical = FolioSpace.small),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(Modifier.width(FolioSpace.item))
                text(Modifier.weight(1f))
            }
            action()
        }
    else
        Row(
            Modifier.fillMaxWidth()
                .then(lineModifier)
                .heightIn(min = 56.dp)
                .padding(
                    start = FolioSpace.item,
                    end = FolioSpace.small,
                    top = 4.dp,
                    bottom = 4.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(FolioSpace.item))
            text(Modifier.weight(1f))
            Spacer(Modifier.width(FolioSpace.small))
            action()
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

/** KOReader's reading statistics, as last fetched from the old plugin. Read only. */
@Composable
internal fun ReadingTimeSheet(statistics: DeviceStatistics?, onDismiss: () -> Unit) {
    SettingsSheet(onDismiss) { ReadingTimeContent(statistics) }
}

@Composable
internal fun ReadingTimeContent(statistics: DeviceStatistics?) {
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

/** The Books folder as Settings shows it, with access checked again whenever Librillo returns. */
internal class BooksFolderState(
    val shown: String,
    val hasAccess: Boolean,
    val refused: Boolean,
    /** Books still in Librillo's private storage. */
    val pending: Int,
    /** Books already in the folder; while there are any, the folder can't be moved yet. */
    val inFolder: Int,
    /** The Inbox as people see it: "Books/Inbox". */
    val inbox: String,
    /** The folder scan's last result, or null before the first scan. */
    val lastScan: FolderScanNotes.LastScan?,
    val allow: () -> Unit,
    val change: () -> Unit,
)

@Composable
internal fun rememberBooksFolder(busy: Boolean = false): BooksFolderState {
    val context = LocalContext.current
    val folder = remember { BooksFolder(context) }
    val phoneStorage = stringResource(R.string.books_folder_phone_storage)
    var location by remember { mutableStateOf(folder.location) }
    var hasAccess by remember { mutableStateOf(folder.hasAccess) }
    var refused by remember { mutableStateOf(false) }
    // Access is granted on a system page; read it again when the user comes back.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(folder) {
        hasAccess = folder.hasAccess
        resumes++
        onPauseOrDispose {}
    }
    // The folder scan's last result, read again on return and when library work ends.
    val lastScan by
        produceState<FolderScanNotes.LastScan?>(null, busy, resumes) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching {
                            FolderScanNotes.lastScan(FolderScanNotes.preferences(context))
                        }
                        .getOrNull()
                }
        }
    // Counted again when library work ends, so the row is right after a move.
    val counts by
        produceState(0 to 0, busy, location) {
            val app = context.applicationContext as? FolioApplication
            value =
                if (app == null) 0 to 0
                else
                    runCatching {
                            val move = BooksFolderMove(app.repository)
                            move.pending() to move.inFolder()
                        }
                        .getOrDefault(0 to 0)
        }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
            if (tree == null) return@rememberLauncherForActivityResult
            refused = !folder.choose(tree)
            location = folder.location
        }
    return BooksFolderState(
        shown = BooksFolder.shown(location, phoneStorage),
        hasAccess = hasAccess,
        refused = refused,
        pending = counts.first,
        inFolder = counts.second,
        inbox = "${location.name}/Inbox",
        lastScan = lastScan,
        allow = {
            runCatching { context.startActivity(BooksFolder.accessIntent(context)) }
        },
        change = { picker.launch(null) },
    )
}

/**
 * The Books folder: where it is, Android's All files access with a plain reason, the Inbox with the
 * last check, moving books in, and changing the location.
 */
@Composable
internal fun BooksFolderContent(
    location: String,
    hasAccess: Boolean,
    onAllow: () -> Unit,
    onChange: () -> Unit,
    refused: Boolean = false,
    pending: Int = 0,
    canChange: Boolean = true,
    inbox: String? = null,
    lastScanAt: Long? = null,
    lastScanMessage: String = "",
    busy: Boolean = false,
    onMove: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.item),
    ) {
        SheetHeading(stringResource(R.string.settings_books_folder), location)
        Text(
            stringResource(R.string.books_folder_body),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = FolioSpace.section),
        )
        Text(
            stringResource(R.string.books_folder_access_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = FolioSpace.section),
        )
        if (hasAccess)
            Row(
                Modifier.padding(horizontal = FolioSpace.section),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
            ) {
                Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(R.string.books_folder_allowed),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        else
            Button(
                onClick = onAllow,
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(horizontal = FolioSpace.section)
                        .testTag(BOOKS_FOLDER_ALLOW_TAG),
            ) {
                Text(stringResource(R.string.books_folder_allow))
            }
        if (hasAccess && inbox != null)
            Column(
                Modifier.padding(horizontal = FolioSpace.section).testTag(BOOKS_FOLDER_INBOX_TAG),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.m9ui_inbox, inbox),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.m9ui_inbox_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    when {
                        lastScanAt == null -> stringResource(R.string.m9ui_not_checked)
                        lastScanMessage.isBlank() ->
                            stringResource(
                                R.string.m9ui_last_check_quiet,
                                relativeTime(lastScanAt),
                            )
                        else ->
                            stringResource(
                                R.string.m9ui_last_check,
                                relativeTime(lastScanAt),
                                lastScanMessage,
                            )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp).testTag(BOOKS_FOLDER_LAST_SCAN_TAG),
                )
            }
        if (hasAccess && pending > 0) {
            Text(
                pluralStringResource(R.plurals.books_folder_pending_body, pending, pending),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = FolioSpace.section),
            )
            Button(
                onClick = onMove,
                enabled = !busy,
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(horizontal = FolioSpace.section)
                        .testTag(BOOKS_FOLDER_MOVE_TAG),
            ) {
                Text(pluralStringResource(R.plurals.books_folder_move, pending, pending))
            }
        }
        // Books in the folder are found by their path; moving the folder with them comes later.
        if (canChange)
            OutlinedButton(
                onClick = onChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = FolioSpace.section),
            ) {
                Text(stringResource(R.string.books_folder_change))
            }
        else
            Text(
                stringResource(R.string.books_folder_fixed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = FolioSpace.section),
            )
        if (refused)
            Text(
                stringResource(R.string.books_folder_not_phone),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = FolioSpace.section),
            )
    }
}

/** What "Send Finished books to Hardcover" would do: what it sends, and what it leaves. */
internal data class FinishedToSend(
    /** Book or catalogue ids to mark Read on Hardcover. */
    val ids: List<String>,
    /** Finished books not matched to Hardcover, which cannot be sent. */
    val unmatched: Int,
    /** Finished books Hardcover already has as Read. */
    val alreadyRead: Int,
)

/**
 * Every book that shows as Finished in Librillo: marked Finished on the phone, finished on the
 * e-reader (KOReader's "complete"), or a catalogue entry without a file marked Finished. Those
 * matched to Hardcover and not already Read there are sent; the rest are counted, so the row can
 * say why they are not.
 */
internal fun finishedOnHardcover(
    books: List<dev.sadking.folio.data.Book>,
    wishes: List<dev.sadking.folio.data.WishBook>,
    reading: List<dev.sadking.folio.data.ReadingSnapshot> = emptyList(),
    readOnHardcover: Set<Int> = emptySet(),
): FinishedToSend {
    val owned = books.map { it.id }.toSet()
    val kindleFinished = reading.filter { it.status == "complete" }.map { it.bookId }.toSet()
    val finished =
        books
            .filter { it.status == "Finished" || it.id in kindleFinished }
            .map { book ->
                book.id to wishes.firstOrNull { it.linkedBookId == book.id }?.hardcoverId
            } +
            wishes
                .filter { it.linkedBookId !in owned && it.status == "Finished" }
                .map { it.id to it.hardcoverId }
    val matched = finished.filter { it.second != null }
    val send = matched.filter { it.second !in readOnHardcover }
    return FinishedToSend(
        send.map { it.first },
        unmatched = finished.size - matched.size,
        alreadyRead = matched.size - send.size,
    )
}
