@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package dev.sadking.folio.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.*
import dev.sadking.folio.*
import dev.sadking.folio.core.*
import dev.sadking.folio.data.*
import dev.sadking.folio.device.*

@Composable
fun ConnectionForm(
    initial: DeviceConfig,
    busy: Boolean,
    observed: ObservedHostKey? = null,
    probeStatus: Operation = Operation(),
    onProbe: ((String, Int) -> Unit)? = null,
    onClearProbe: () -> Unit = {},
    folderListing: FolderListing? = null,
    folderStatus: Operation = Operation(),
    onBrowse: ((DeviceConfig, String, Uri?, String, String?) -> Unit)? = null,
    onClearFolders: () -> Unit = {},
    save: (DeviceConfig, String, Uri?) -> Unit,
) {
    var host by rememberSaveable { mutableStateOf(initial.host) }
    var port by rememberSaveable { mutableStateOf(initial.port.toString()) }
    var user by rememberSaveable { mutableStateOf(initial.username) }
    var directory by rememberSaveable { mutableStateOf(initial.directory) }
    var fingerprint by rememberSaveable { mutableStateOf(initial.fingerprint) }
    var password by remember { mutableStateOf("") }
    var authentication by rememberSaveable {
        mutableStateOf(
            if (initial.host.isBlank()) "KOReader password-free" else initial.authentication
        )
    }
    var keyUri by rememberSaveable { mutableStateOf<String?>(null) }
    val keyPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            keyUri = it?.toString()
        }
    val config =
        DeviceConfig(
            host.trim(),
            port.toIntOrNull() ?: 0,
            user.trim(),
            directory.trim().trimEnd('/'),
            fingerprint.trim(),
            authentication,
        )
    val reuse =
        initial.host.isNotBlank() &&
            config.fingerprint == initial.fingerprint &&
            config.username == initial.username &&
            config.authentication == initial.authentication
    val context = androidx.compose.ui.platform.LocalContext.current
    val valid = runCatching { config.validate() }.isSuccess
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Pair your Kindle", style = MaterialTheme.typography.headlineMedium)
        Text(
            "1. Connect your Kindle to your phone hotspot or the same Wi-Fi.\n2. In KOReader: Tools → Network → SSH server. Enable the server and note its IP and port (usually 2222).\n3. Read and trust its fingerprint, select its login mode, then choose a book folder.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(
            onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)
                    )
                }
            }
        ) {
            Text("Open phone network settings")
        }
        Text(
            "The Kindle address may change after reconnecting. Update it here; the verified host key and saved credential can be retained.",
            style = MaterialTheme.typography.bodySmall,
        )
        SectionHeading("Kindle address")
        EditorField("IP address or hostname", host, { host = it }, enabled = !busy)
        EditorField("SSH port", port, { port = it }, enabled = !busy)
        EditorField("Username", user, { user = it }, enabled = !busy)
        SectionHeading(
            "Trust this Kindle",
            if (fingerprint.isBlank()) "Read its key and confirm the address displayed in KOReader."
            else "Host key saved · changes require your confirmation",
        )
        ExpandableSection("Manual fingerprint", "Optional independently verified SHA256 key") {
            EditorField(
                "Trusted SHA256 host fingerprint",
                fingerprint,
                { fingerprint = it },
                enabled = !busy,
            )
        }
        if (onProbe != null) {
            OutlinedButton(
                onClick = { onProbe(host.trim(), port.toIntOrNull() ?: 0) },
                enabled =
                    !busy &&
                        runCatching { validateEndpoint(host.trim(), port.toIntOrNull() ?: 0) }
                            .isSuccess,
            ) {
                Text(
                    if (fingerprint.isBlank()) "Read Kindle fingerprint"
                    else "Check Kindle fingerprint"
                )
            }
            if (probeStatus.message.isNotBlank()) StatusSurface(probeStatus)
        }
        Text(
            "Read the fingerprint here, then confirm the IP shown by KOReader before trusting it. A changed key blocks transfers until you explicitly trust the replacement.",
            style = MaterialTheme.typography.bodySmall,
        )
        SectionHeading("SSH login", "Choose the mode enabled in KOReader.")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Key", "Password", "KOReader password-free").forEach { mode ->
                FilterChip(
                    authentication == mode,
                    { authentication = mode },
                    label = { Text(mode) },
                    enabled = !busy,
                )
            }
        }
        if (authentication == "Key") {
            OutlinedButton(onClick = { keyPicker.launch(arrayOf("*/*")) }, enabled = !busy) {
                Text(if (keyUri == null) "Select SSH private key" else "Private key selected")
            }
            Text(
                "Install the matching public key in KOReader's authorized_keys. Private keys are encrypted with Android Keystore.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (authentication == "KOReader password-free")
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Phone + Kindle only", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "In KOReader, stop SSH, turn off key-only login and enable ‘Login without password’, then restart SSH. No password or key file is needed in Folio."
                    )
                    Text(
                        "Anyone on the same network can access KOReader’s open SSH server while it is running. Turn it off after syncing. You can switch back by entering a password or selecting a private key.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        if (authentication != "KOReader password-free")
            OutlinedTextField(
                password,
                { password = it },
                Modifier.fillMaxWidth(),
                label = {
                    Text(
                        if (authentication == "Key") "Key passphrase · if required"
                        else if (initial.host.isBlank()) "SSH password"
                        else "Password · blank keeps saved credential"
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                    ),
                singleLine = true,
                enabled = !busy,
                shape = RoundedCornerShape(16.dp),
            )
        SectionHeading(
            "Book folder",
            "The Kindle documents folder works immediately; a custom folder is optional.",
        )
        EditorField("Book directory", directory, { directory = it }, enabled = !busy)
        if (onBrowse != null)
            OutlinedButton(
                onClick = { onBrowse(config, password, keyUri?.let(Uri::parse), "", null) },
                enabled =
                    !busy &&
                        runCatching { config.copy(directory = "/mnt/us").validate() }.isSuccess,
            ) {
                Text("Choose Kindle folder")
            }

        if (folderStatus.busy || folderStatus.failed) {
            Text(folderStatus.message)
            if (folderStatus.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        TextButton(onClick = { directory = "/mnt/us/documents" }, enabled = !busy) {
            Text("Use Kindle documents folder")
        }
        if (!valid)
            Text(
                runCatching { config.validate() }.exceptionOrNull()?.message
                    ?: "Complete the pairing details",
                style = MaterialTheme.typography.bodySmall,
            )
        Button(
            onClick = { save(config, password, keyUri?.let(Uri::parse)) },
            enabled =
                !busy &&
                    valid &&
                    (when (authentication) {
                        "KOReader password-free" -> true
                        "Key" -> keyUri != null || reuse
                        else -> password.isNotEmpty() || reuse
                    }),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save pairing")
        }
        Spacer(Modifier.height(24.dp))
    }
    if (observed != null) {
        val sameAddress = observed.host == host.trim() && observed.port == port.toIntOrNull()
        val changed = fingerprint.isNotBlank() && fingerprint.trimEnd('=') != observed.fingerprint
        AlertDialog(
            onDismissRequest = onClearProbe,
            title = { Text(if (changed) "Kindle host key changed" else "Trust this Kindle?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${observed.host}:${observed.port} · ${observed.algorithm}")
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(observed.fingerprint, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        if (changed)
                            "This differs from the key already entered. Confirm you intended to pair this device or that its SSH host key was regenerated before replacing it."
                        else
                            "Confirm this IP is the one shown by KOReader on your Kindle. This is first-time trust of a key read over your network, not independent verification."
                    )
                    if (!sameAddress)
                        Text(
                            "The address changed. Cancel and read its fingerprint again.",
                            color = MaterialTheme.colorScheme.error,
                        )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        fingerprint = observed.fingerprint
                        onClearProbe()
                    },
                    enabled = sameAddress && !busy,
                ) {
                    Text(if (changed) "Trust replacement key" else "Trust this Kindle")
                }
            },
            dismissButton = { TextButton(onClick = onClearProbe) { Text("Cancel") } },
        )
    }
    folderListing?.let { listing ->
        var newFolder by remember { mutableStateOf("") }
        val sameDevice = listing.config.copy(directory = config.directory) == config
        ModalBottomSheet(onDismissRequest = onClearFolders) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Kindle folders", style = MaterialTheme.typography.headlineSmall)
                if (folderStatus.busy || folderStatus.failed) {
                    Text(folderStatus.message)
                    if (folderStatus.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Text("/mnt/us" + if (listing.relative.isEmpty()) "" else "/${listing.relative}")
                if (!sameDevice)
                    Text(
                        "Connection settings changed. Close and browse again.",
                        color = MaterialTheme.colorScheme.error,
                    )
                Button(
                    onClick = {
                        directory =
                            "/mnt/us" +
                                if (listing.relative.isEmpty()) "" else "/${listing.relative}"
                        onClearFolders()
                    },
                    enabled = !busy && sameDevice,
                ) {
                    Text("Use this folder")
                }
                if (listing.relative.isNotEmpty())
                    TextButton(
                        onClick = {
                            onBrowse?.invoke(
                                config,
                                password,
                                keyUri?.let(Uri::parse),
                                listing.relative.substringBeforeLast('/', ""),
                                null,
                            )
                        },
                        enabled = !busy && sameDevice,
                    ) {
                        Text("Parent folder")
                    }
                listing.directories.forEach { folder ->
                    TextButton(
                        onClick = {
                            onBrowse?.invoke(
                                config,
                                password,
                                keyUri?.let(Uri::parse),
                                folder.relativePath,
                                null,
                            )
                        },
                        enabled = !busy && sameDevice,
                    ) {
                        Text(folder.name)
                    }
                }
                OutlinedTextField(
                    newFolder,
                    { newFolder = it.take(120) },
                    label = { Text("New folder name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        onBrowse?.invoke(
                            config,
                            password,
                            keyUri?.let(Uri::parse),
                            listing.relative,
                            newFolder,
                        )
                        newFolder = ""
                    },
                    enabled = !busy && sameDevice && newFolder.isNotBlank(),
                ) {
                    Text("Create folder here")
                }
            }
        }
    }
}
