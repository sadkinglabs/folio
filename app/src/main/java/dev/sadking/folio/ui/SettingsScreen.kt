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
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import dev.sadking.folio.*
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
        PageHeading("Settings", "Appearance, connections and your library")
        Column(
            Modifier.padding(horizontal = FolioSpace.page),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Appearance", style = MaterialTheme.typography.titleLarge)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("System", "Light", "Dark").forEach { mode ->
                    FilterChip(appearance == mode, { onTheme(mode) }, label = { Text(mode) })
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Use wallpaper colours", Modifier.weight(1f))
                Switch(
                    dynamic,
                    onDynamic,
                    Modifier.semantics { contentDescription = "Use wallpaper colours" },
                )
            }
            HorizontalDivider()
            Text("Paired folders", style = MaterialTheme.typography.titleLarge)
            Text(
                if (phoneFolder.isBlank())
                    "Choose a phone folder to import its EPUBs and exchange additions with the Kindle folder."
                else "Phone · $phoneFolder",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Sync keeps EPUB subfolders and checks file contents in both directions. Different editions need review; deletions and KOReader sidecars are never mirrored. Refresh reads phone additions on demand.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = onPhoneFolder,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (phoneFolder.isBlank()) "Choose phone folder" else "Change phone folder")
            }
            if (phoneFolder.isNotBlank())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onRefreshFolder, enabled = !busy) { Text("Refresh EPUBs") }
                    TextButton(onClick = onForgetFolder, enabled = !busy) {
                        Text("Disconnect folder")
                    }
                }
            HorizontalDivider()
            OutlinedButton(
                onClick = onInstallCompanion,
                enabled = !busy && paired,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Install KOReader companion")
            }
            Text(
                "The companion applies safe position handoffs through KOReader. Installation needs a restart; existing plugin files are retained.",
                style = MaterialTheme.typography.bodySmall,
            )
            HorizontalDivider()
            Text("Library storage", style = MaterialTheme.typography.titleLarge)
            Text(
                "$recordCount library entries · $count EPUBs on this phone. Original files are retained when metadata is edited.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (onRemoved != null)
                OutlinedButton(
                    onClick = onRemoved,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Recently removed · $removedCount")
                }
            if (onImports != null)
                TextButton(onClick = onImports, enabled = !busy) { Text("Last import results") }
            Text(
                "Back up your books, covers, lists and saved reading data. Keep the backup private; it contains your books and notes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onBackup,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Export library backup")
            }
            OutlinedButton(
                onClick = onRestore,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Restore from backup")
            }
            Text(
                "Restore adds missing books and merges collection memberships. Existing EPUBs and metadata are retained. Sync preferences on new restored shelves are disabled.",
                style = MaterialTheme.typography.bodySmall,
            )
            HorizontalDivider()
            SectionHeading(
                "Connections",
                "Your library works offline. Connect only the services you use.",
            )
            FeatureRow(
                "Hardcover",
                if (hardcoverUsername.isBlank())
                    "Connect for Want to Read and reviewed progress updates"
                else "Connected as @$hardcoverUsername",
                Icons.Outlined.AccountCircle,
                enabled = !busy,
                onClick = onHardcover,
            )
            Text("Kindle & sync", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                onClick = onConnection,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Connection settings")
            }
            Text(
                "File transfers are verified before completion. Existing Kindle editions stay in place; changed EPUBs require review.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { confirmForget = true }, enabled = !busy && paired) {
                Text("Forget Kindle pairing")
            }
            HorizontalDivider()
            Text(
                "Folio · ${dev.sadking.folio.BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "An independent ebook library. No required account or analytics. Online discovery and Hardcover are optional.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (confirmForget)
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget this pairing?") },
            text = {
                Text(
                    "The saved host and credential will be removed. Books on both devices remain intact."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onForget()
                        confirmForget = false
                    },
                    enabled = !busy,
                ) {
                    Text("Forget")
                }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
}
