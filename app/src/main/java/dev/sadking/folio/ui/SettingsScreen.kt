@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package dev.sadking.folio.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
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
internal fun SettingsScreen(
    appearance: String,
    dynamic: Boolean,
    count: Int,
    onTheme: (String) -> Unit,
    onDynamic: (Boolean) -> Unit,
    onConnection: () -> Unit,
    onForget: () -> Unit,
    busy: Boolean = false,
    onBackup: () -> Unit = {},
    onRestore: () -> Unit = {},
    hardcoverUsername: String = "",
    onHardcover: () -> Unit = {},
    paired: Boolean = true,
    onRemoved: (() -> Unit)? = null,
    removedCount: Int = 0,
    onImports: (() -> Unit)? = null,
    recordCount: Int = count,
    phoneFolder: String = "",
    onPhoneFolder: () -> Unit = {},
    onRefreshFolder: () -> Unit = {},
    onForgetFolder: () -> Unit = {},
    onInstallCompanion: () -> Unit = {},
) {
    var confirmForget by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        PageHeading(
            stringResource(R.string.settings_title),
            stringResource(R.string.settings_subtitle),
        )
        Column(
            Modifier.padding(horizontal = FolioSpace.page),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                stringResource(R.string.settings_appearance),
                style = MaterialTheme.typography.titleLarge,
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // "System", "Light" and "Dark" are stored preference values; only the label is
                // translated.
                listOf(
                        "System" to R.string.appearance_theme_system,
                        "Light" to R.string.appearance_theme_light,
                        "Dark" to R.string.appearance_theme_dark,
                    )
                    .forEach { (mode, labelRes) ->
                        FilterChip(
                            appearance == mode,
                            { onTheme(mode) },
                            label = { Text(stringResource(labelRes)) },
                        )
                    }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val wallpaper = stringResource(R.string.settings_wallpaper_colours)
                Text(wallpaper, Modifier.weight(1f))
                Switch(
                    dynamic,
                    onDynamic,
                    Modifier.semantics { contentDescription = wallpaper },
                )
            }
            HorizontalDivider()
            Text(
                stringResource(R.string.settings_folder_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                if (phoneFolder.isBlank()) stringResource(R.string.settings_folder_none)
                else stringResource(R.string.settings_folder_current, phoneFolder),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.settings_folder_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = onPhoneFolder,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (phoneFolder.isBlank()) stringResource(R.string.settings_folder_choose)
                    else stringResource(R.string.settings_folder_change)
                )
            }
            if (phoneFolder.isNotBlank())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onRefreshFolder, enabled = !busy) {
                        Text(stringResource(R.string.settings_folder_refresh))
                    }
                    TextButton(onClick = onForgetFolder, enabled = !busy) {
                        Text(stringResource(R.string.settings_folder_disconnect))
                    }
                }
            HorizontalDivider()
            OutlinedButton(
                onClick = onInstallCompanion,
                enabled = !busy && paired,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_plugin_install))
            }
            Text(
                stringResource(R.string.settings_plugin_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            HorizontalDivider()
            Text(
                stringResource(R.string.settings_storage_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                stringResource(
                    R.string.settings_storage_summary,
                    pluralStringResource(
                        R.plurals.settings_storage_entries,
                        recordCount,
                        recordCount,
                    ),
                    pluralStringResource(R.plurals.settings_storage_files, count, count),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (onRemoved != null)
                OutlinedButton(
                    onClick = onRemoved,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_recently_removed, removedCount))
                }
            if (onImports != null)
                TextButton(onClick = onImports, enabled = !busy) {
                    Text(stringResource(R.string.settings_last_import))
                }
            Text(
                stringResource(R.string.settings_backup_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onBackup,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_backup_export))
            }
            OutlinedButton(
                onClick = onRestore,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_backup_restore))
            }
            Text(
                stringResource(R.string.settings_restore_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            HorizontalDivider()
            SectionHeading(
                stringResource(R.string.settings_connections_title),
                stringResource(R.string.settings_connections_subtitle),
            )
            FeatureRow(
                stringResource(R.string.settings_hardcover),
                if (hardcoverUsername.isBlank()) stringResource(R.string.settings_hardcover_connect)
                else stringResource(R.string.settings_hardcover_connected, hardcoverUsername),
                Icons.Outlined.AccountCircle,
                enabled = !busy,
                onClick = onHardcover,
            )
            HardcoverAutomaticRow()
            FolioLinkSection()
            Text(
                stringResource(R.string.settings_device_title),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedButton(
                onClick = onConnection,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_connection_settings))
            }
            Text(
                stringResource(R.string.settings_device_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { confirmForget = true }, enabled = !busy && paired) {
                Text(stringResource(R.string.settings_forget_device))
            }
            HorizontalDivider()
            Text(
                stringResource(
                    R.string.settings_about_version,
                    dev.sadking.folio.BuildConfig.VERSION_NAME,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.settings_about),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (confirmForget)
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(stringResource(R.string.settings_forget_title)) },
            text = { Text(stringResource(R.string.settings_forget_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onForget()
                        confirmForget = false
                    },
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.settings_forget_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
}
