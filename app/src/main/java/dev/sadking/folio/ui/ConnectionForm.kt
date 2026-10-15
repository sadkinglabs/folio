@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package dev.sadking.folio.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sadking.folio.FolderListing
import dev.sadking.folio.Operation
import dev.sadking.folio.R
import dev.sadking.folio.device.DeviceConfig
import dev.sadking.folio.device.ObservedHostKey
import dev.sadking.folio.device.validateEndpoint

internal const val PAIRING_TAG = "pairing"
internal const val PAIRING_STEPS_TAG = "pairing-steps"
internal const val PAIRING_PROBE_TAG = "pairing-probe"
internal const val PAIRING_SAVE_TAG = "pairing-save"
internal const val PAIRING_TRUST_TAG = "pairing-trust"
internal const val PAIRING_ADDRESS_CHANGED_TAG = "pairing-address-changed"
internal const val PAIRING_FORGET_TAG = "pairing-forget"

/**
 * The legacy SSH pairing, under Settings → Advanced: four steps with the current one lit, the
 * fingerprint as a chip, the probe as a tonal button with its result beside it. The fields and what
 * they save are unchanged.
 */
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
    onForget: (() -> Unit)? = null,
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
    var confirmForget by rememberSaveable { mutableStateOf(false) }
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
    val hasSecret =
        when (authentication) {
            "KOReader password-free" -> true
            "Key" -> keyUri != null
            else -> password.isNotEmpty()
        }
    val step =
        pairingStep(config.host, config.port, config.fingerprint, authentication, hasSecret, reuse)
    val context = LocalContext.current
    val valid = runCatching { config.validate() }.isSuccess
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(start = FolioSpace.section, end = FolioSpace.section, bottom = 32.dp)
            .testTag(PAIRING_TAG),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.item),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
        ) {
            Text(
                stringResource(R.string.pairing_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer) {
                Text(
                    stringResource(R.string.settings_ssh_legacy).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            stringResource(R.string.pairing_legacy_line),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StepHeader(step)

        // 1 · Network
        StepTitle(
            1,
            stringResource(R.string.pairing_step_network),
            stringResource(R.string.pairing_network_hint),
        )
        FilledTonalButton(
            onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)
                    )
                }
            }
        ) {
            Text(stringResource(R.string.pairing_open_network))
        }

        // 2 · Address
        StepTitle(
            2,
            stringResource(R.string.pairing_step_address),
            stringResource(R.string.pairing_address_hint),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(FolioSpace.gap)) {
            Box(Modifier.weight(2f)) {
                EditorField(
                    stringResource(R.string.pairing_host),
                    host,
                    { host = it },
                    enabled = !busy,
                )
            }
            Box(Modifier.weight(1f)) {
                EditorField(
                    stringResource(R.string.pairing_port),
                    port,
                    { port = it },
                    enabled = !busy,
                )
            }
        }
        EditorField(stringResource(R.string.pairing_username), user, { user = it }, enabled = !busy)
        if (fingerprint.isBlank())
            Text(
                stringResource(R.string.pairing_fingerprint_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        else {
            FingerprintChip(fingerprint) {
                context
                    .getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("SSH fingerprint", fingerprint))
            }
            Text(
                stringResource(R.string.pairing_fingerprint_saved),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onProbe != null)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(FolioSpace.gap),
                verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = { onProbe(host.trim(), port.toIntOrNull() ?: 0) },
                    enabled =
                        !busy &&
                            runCatching { validateEndpoint(host.trim(), port.toIntOrNull() ?: 0) }
                                .isSuccess,
                    modifier = Modifier.testTag(PAIRING_PROBE_TAG),
                ) {
                    Text(
                        stringResource(
                            if (fingerprint.isBlank()) R.string.pairing_read_fingerprint
                            else R.string.pairing_check_fingerprint
                        )
                    )
                }
                if (probeStatus.message.isNotBlank()) ResultPill(probeStatus)
            }
        ExpandableSection(
            stringResource(R.string.pairing_fingerprint_manual),
            stringResource(R.string.pairing_fingerprint_manual_hint),
        ) {
            EditorField(
                stringResource(R.string.pairing_fingerprint_field),
                fingerprint,
                { fingerprint = it },
                enabled = !busy,
            )
        }

        // 3 · Login
        StepTitle(
            3,
            stringResource(R.string.pairing_step_login),
            stringResource(R.string.pairing_login_hint),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpace.small)) {
            // The stored mode names stay; only the labels are translated.
            listOf(
                    "Key" to R.string.pairing_auth_key,
                    "Password" to R.string.pairing_auth_password,
                    "KOReader password-free" to R.string.pairing_auth_free,
                )
                .forEach { (mode, label) ->
                    FilterChip(
                        authentication == mode,
                        { authentication = mode },
                        label = { Text(stringResource(label)) },
                        enabled = !busy,
                    )
                }
        }
        if (authentication == "Key") {
            FilledTonalButton(onClick = { keyPicker.launch(arrayOf("*/*")) }, enabled = !busy) {
                Text(
                    stringResource(
                        if (keyUri == null) R.string.pairing_pick_key
                        else R.string.pairing_key_chosen
                    )
                )
            }
            Text(
                stringResource(R.string.pairing_key_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (authentication == "KOReader password-free")
            Text(
                stringResource(R.string.pairing_free_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        else
            OutlinedTextField(
                password,
                { password = it },
                Modifier.fillMaxWidth(),
                label = {
                    Text(
                        stringResource(
                            when {
                                authentication == "Key" -> R.string.pairing_passphrase
                                initial.host.isBlank() -> R.string.pairing_password
                                else -> R.string.pairing_password_keep
                            }
                        )
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

        // 4 · Folder
        StepTitle(
            4,
            stringResource(R.string.pairing_step_folder),
            stringResource(R.string.pairing_folder_hint),
        )
        EditorField(
            stringResource(R.string.pairing_directory),
            directory,
            { directory = it },
            enabled = !busy,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBrowse != null)
                FilledTonalButton(
                    onClick = { onBrowse(config, password, keyUri?.let(Uri::parse), "", null) },
                    enabled =
                        !busy &&
                            runCatching { config.copy(directory = "/mnt/us").validate() }.isSuccess,
                ) {
                    Text(stringResource(R.string.pairing_browse))
                }
            TextButton(onClick = { directory = "/mnt/us/documents" }, enabled = !busy) {
                Text(stringResource(R.string.pairing_use_documents))
            }
        }
        if (folderListing == null && (folderStatus.busy || folderStatus.failed))
            ResultPill(folderStatus)

        if (!valid)
            Text(
                runCatching { config.validate() }.exceptionOrNull()?.message
                    ?: stringResource(R.string.pairing_incomplete),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        Button(
            onClick = { save(config, password, keyUri?.let(Uri::parse)) },
            enabled = !busy && valid && (hasSecret || reuse),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(PAIRING_SAVE_TAG),
        ) {
            Text(stringResource(R.string.pairing_save))
        }
        if (onForget != null && initial.host.isNotBlank())
            TextButton(
                onClick = { confirmForget = true },
                enabled = !busy,
                modifier = Modifier.align(Alignment.CenterHorizontally).testTag(PAIRING_FORGET_TAG),
                colors =
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.settings_forget_device))
            }
    }
    if (confirmForget && onForget != null)
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(stringResource(R.string.settings_forget_title)) },
            text = { Text(stringResource(R.string.settings_forget_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmForget = false
                        onForget()
                    },
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.settings_forget_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) {
                    Text(stringResource(R.string.shell_cancel))
                }
            },
        )
    if (observed != null) {
        val sameAddress = observed.host == host.trim() && observed.port == port.toIntOrNull()
        val changed = fingerprint.isNotBlank() && fingerprint.trimEnd('=') != observed.fingerprint
        AlertDialog(
            onDismissRequest = onClearProbe,
            title = {
                Text(
                    stringResource(
                        if (changed) R.string.pairing_trust_changed_title
                        else R.string.pairing_trust_title
                    )
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${observed.host}:${observed.port} · ${observed.algorithm}")
                    SelectionContainer {
                        Text(
                            observed.fingerprint,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Text(
                        stringResource(
                            if (changed) R.string.pairing_trust_changed_body
                            else R.string.pairing_trust_body
                        )
                    )
                    if (!sameAddress)
                        Text(
                            stringResource(R.string.pairing_address_changed),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag(PAIRING_ADDRESS_CHANGED_TAG),
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
                    modifier = Modifier.testTag(PAIRING_TRUST_TAG),
                ) {
                    Text(
                        stringResource(
                            if (changed) R.string.pairing_trust_replacement
                            else R.string.pairing_trust
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onClearProbe) { Text(stringResource(R.string.shell_cancel)) }
            },
        )
    }
    folderListing?.let { listing ->
        FolderBrowserSheet(
            listing,
            config,
            folderStatus,
            busy,
            onPick = {
                directory = it
                onClearFolders()
            },
            onBrowse = { relative, newFolder ->
                onBrowse?.invoke(config, password, keyUri?.let(Uri::parse), relative, newFolder)
            },
            onDismiss = onClearFolders,
        )
    }
}

/** The four steps in a row: done ones ticked, the current one filled, the rest outlined. */
@Composable
private fun StepHeader(current: PairingStep) {
    val labels =
        listOf(
            R.string.pairing_step_network,
            R.string.pairing_step_address,
            R.string.pairing_step_login,
            R.string.pairing_step_folder,
        )
    Row(
        Modifier.fillMaxWidth().testTag(PAIRING_STEPS_TAG),
        horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
    ) {
        PairingStep.entries.forEachIndexed { index, step ->
            val label = stringResource(labels[index])
            val done = step.ordinal < current.ordinal
            val active = step == current
            val description =
                stringResource(
                    when {
                        active -> R.string.pairing_step_current
                        done -> R.string.pairing_step_done
                        else -> R.string.pairing_step_todo
                    },
                    index + 1,
                    label,
                )
            Column(
                Modifier.weight(1f).semantics(mergeDescendants = true) {
                    contentDescription = description
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier.size(28.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                active -> MaterialTheme.colorScheme.primary
                                done -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.surfaceContainerHigh
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (done)
                        Icon(
                            Icons.Outlined.Check,
                            null,
                            Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    else
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color =
                                if (active) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A step's number, name and its one line of help. */
@Composable
private fun StepTitle(number: Int, title: String, hint: String) {
    Column(
        Modifier.padding(top = FolioSpace.small),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("$number · $title", style = MaterialTheme.typography.titleMedium)
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The trusted fingerprint as a monospace chip with a copy icon. */
@Composable
private fun FingerprintChip(fingerprint: String, onCopy: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                fingerprint,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onCopy) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    stringResource(R.string.pairing_copy_fingerprint),
                    Modifier.size(18.dp),
                )
            }
        }
    }
}

/** An operation's message as a pill beside the button that started it. */
@Composable
private fun ResultPill(op: Operation) {
    Surface(
        shape = CircleShape,
        color =
            if (op.failed) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
        ) {
            if (op.busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(op.message, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** The e-reader's folders, one level at a time, with a way to make a new one. */
@Composable
private fun FolderBrowserSheet(
    listing: FolderListing,
    config: DeviceConfig,
    folderStatus: Operation,
    busy: Boolean,
    onPick: (String) -> Unit,
    onBrowse: (String, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var newFolder by remember { mutableStateOf("") }
    val sameDevice = listing.config.copy(directory = config.directory) == config
    val path = "/mnt/us" + if (listing.relative.isEmpty()) "" else "/${listing.relative}"
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = FolioSpace.sheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = FolioSpace.section, end = FolioSpace.section, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.pairing_folders_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                path,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (folderStatus.busy || folderStatus.failed) ResultPill(folderStatus)
            if (!sameDevice)
                Text(
                    stringResource(R.string.pairing_folders_changed),
                    color = MaterialTheme.colorScheme.error,
                )
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpace.small)) {
                Button(onClick = { onPick(path) }, enabled = !busy && sameDevice) {
                    Text(stringResource(R.string.pairing_use_folder))
                }
                if (listing.relative.isNotEmpty())
                    TextButton(
                        onClick = { onBrowse(listing.relative.substringBeforeLast('/', ""), null) },
                        enabled = !busy && sameDevice,
                    ) {
                        Text(stringResource(R.string.pairing_parent_folder))
                    }
            }
            listing.directories.forEach { folder ->
                TextButton(
                    onClick = { onBrowse(folder.relativePath, null) },
                    enabled = !busy && sameDevice,
                ) {
                    Text(folder.name)
                }
            }
            OutlinedTextField(
                newFolder,
                { newFolder = it.take(120) },
                label = { Text(stringResource(R.string.pairing_new_folder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            FilledTonalButton(
                onClick = {
                    onBrowse(listing.relative, newFolder)
                    newFolder = ""
                },
                enabled = !busy && sameDevice && newFolder.isNotBlank(),
            ) {
                Text(stringResource(R.string.pairing_create_folder))
            }
        }
    }
}
