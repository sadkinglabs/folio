@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.sadking.folio.Operation
import dev.sadking.folio.R
import dev.sadking.folio.SetupProgress
import dev.sadking.folio.device.ObservedHostKey
import dev.sadking.folio.device.libraryFolderName

internal const val SETUP_SHEET_TAG = "setup-sheet"
internal const val SETUP_HOST_TAG = "setup-host"
internal const val SETUP_PORT_TAG = "setup-port"
internal const val SETUP_FIND_TAG = "setup-find"
internal const val SETUP_FOLDER_TAG = "setup-folder"
internal const val SETUP_INSTALL_TAG = "setup-install"
internal const val SETUP_DONE_TAG = "setup-done"

/**
 * Setting the e-reader up, once, over Wi-Fi: the owner turns on KOReader's SSH server, the phone
 * finds the e-reader, the owner names the library folder, and the phone installs the plugin with
 * everything it needs. Then three plain steps finish the job on the e-reader. SSH is used for this
 * and nothing else.
 */
@Composable
internal fun PluginSetupSheet(
    reinstall: Boolean,
    savedHost: String,
    savedPort: Int,
    savedFingerprint: String,
    folder: String,
    observed: ObservedHostKey?,
    probe: Operation,
    progress: SetupProgress,
    onFind: (host: String, port: Int) -> Unit,
    onInstall: (host: String, port: Int, fingerprint: String, folder: String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = FolioSpace.sheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        PluginSetupContent(
            reinstall,
            savedHost,
            savedPort,
            savedFingerprint,
            folder,
            observed,
            probe,
            progress,
            onFind,
            onInstall,
            onDone = onDismiss,
        )
    }
}

/** The sheet's content, for previews and tests. */
@Composable
internal fun PluginSetupContent(
    reinstall: Boolean,
    savedHost: String,
    savedPort: Int,
    savedFingerprint: String,
    folder: String,
    observed: ObservedHostKey?,
    probe: Operation,
    progress: SetupProgress,
    onFind: (host: String, port: Int) -> Unit,
    onInstall: (host: String, port: Int, fingerprint: String, folder: String) -> Unit,
    onDone: () -> Unit,
) {
    var host by rememberSaveable { mutableStateOf(savedHost) }
    var portText by rememberSaveable { mutableStateOf(savedPort.toString()) }
    var folderText by rememberSaveable { mutableStateOf(folder) }
    val port = portText.toIntOrNull()?.takeIf { it in 1..65535 }
    // The key read belongs to the address as it was typed then; a new address needs a new look.
    val found = observed?.takeIf { it.host == host.trim() && it.port == port }
    val installing = progress.stage == SetupProgress.Stage.INSTALLING
    val folderName = libraryFolderName(folderText)
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = FolioSpace.section, end = FolioSpace.section, bottom = 32.dp)
            .testTag(SETUP_SHEET_TAG),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.item),
    ) {
        Text(
            stringResource(
                if (reinstall) R.string.m9sync_setup_reinstall_title
                else R.string.m9sync_setup_title
            ),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        if (progress.stage == SetupProgress.Stage.DONE) {
            SetupDone(onDone)
            return@Column
        }
        Text(
            stringResource(R.string.m9sync_setup_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SetupStep(1, stringResource(R.string.m9sync_setup_step_ssh)) {
            Text(
                stringResource(R.string.m9sync_setup_step_ssh_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        SetupStep(2, stringResource(R.string.m9sync_setup_step_find)) {
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpace.small)) {
                OutlinedTextField(
                    host,
                    { host = it },
                    Modifier.weight(2f).testTag(SETUP_HOST_TAG),
                    label = { Text(stringResource(R.string.pairing_host)) },
                    singleLine = true,
                    enabled = !installing,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next,
                        ),
                    shape = RoundedCornerShape(16.dp),
                )
                OutlinedTextField(
                    portText,
                    { portText = it.filter(Char::isDigit).take(5) },
                    Modifier.weight(1f).testTag(SETUP_PORT_TAG),
                    label = { Text(stringResource(R.string.pairing_port)) },
                    singleLine = true,
                    enabled = !installing,
                    isError = port == null,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                    shape = RoundedCornerShape(16.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(
                    onClick = { port?.let { onFind(host.trim(), it) } },
                    enabled = host.isNotBlank() && port != null && !probe.busy && !installing,
                    modifier = Modifier.testTag(SETUP_FIND_TAG),
                ) {
                    Text(stringResource(R.string.m9sync_setup_find))
                }
                if (probe.busy) {
                    Spacer(Modifier.width(FolioSpace.gap))
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            when {
                found != null -> {
                    Text(
                        stringResource(R.string.m9sync_setup_found, found.host),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(R.string.m9sync_setup_key, found.fingerprint),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (
                        savedFingerprint.isNotBlank() &&
                            savedHost == found.host &&
                            savedFingerprint != found.fingerprint
                    )
                        Text(
                            stringResource(R.string.m9sync_setup_key_changed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                }
                probe.failed && !probe.busy ->
                    Text(
                        probe.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
            }
        }
        SetupStep(3, stringResource(R.string.m9sync_setup_step_folder)) {
            OutlinedTextField(
                folderText,
                { folderText = it },
                Modifier.fillMaxWidth().testTag(SETUP_FOLDER_TAG),
                label = { Text(stringResource(R.string.m9sync_setup_folder_label)) },
                singleLine = true,
                enabled = !installing,
                isError = folderName == null,
                supportingText = {
                    Text(
                        stringResource(
                            if (folderName == null) R.string.m9sync_setup_folder_invalid
                            else R.string.m9sync_setup_folder_hint
                        )
                    )
                },
                shape = RoundedCornerShape(16.dp),
            )
        }
        Button(
            onClick = {
                if (found != null && folderName != null && port != null)
                    onInstall(found.host, port, found.fingerprint, folderName)
            },
            enabled = found != null && folderName != null && !installing,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(SETUP_INSTALL_TAG),
        ) {
            if (installing) {
                CircularProgressIndicator(
                    Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(FolioSpace.small))
                Text(stringResource(R.string.m9sync_setup_installing))
            } else Text(stringResource(R.string.m9sync_setup_install))
        }
        if (progress.stage == SetupProgress.Stage.FAILED && progress.message.isNotBlank())
            Text(
                progress.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
    }
}

/** A numbered step: a small filled circle with the number, the title, then its content. */
@Composable
private fun SetupStep(number: Int, title: String, content: @Composable ColumnScope.() -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        StepNumber(number)
        Spacer(Modifier.width(FolioSpace.gap))
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun StepNumber(number: Int) {
    // Grows with the text size, so the number stays inside its circle at 200%.
    val size =
        with(androidx.compose.ui.platform.LocalDensity.current) {
            maxOf(24.dp, (MaterialTheme.typography.labelMedium.lineHeight * 1.5f).toDp())
        }
    Box(
        Modifier.size(size).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$number",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** What is left to do on the e-reader, in order, and Done. */
@Composable
private fun SetupDone(onDone: () -> Unit) {
    Column(
        Modifier.testTag(SETUP_DONE_TAG),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.item),
    ) {
        Text(
            stringResource(R.string.m9sync_setup_done),
            style = MaterialTheme.typography.bodyLarge,
        )
        listOf(
                R.string.m9sync_setup_done_restart,
                R.string.m9sync_setup_done_sync,
                R.string.m9sync_setup_done_ssh_off,
            )
            .forEachIndexed { index, step ->
                Row(verticalAlignment = Alignment.Top) {
                    StepNumber(index + 1)
                    Spacer(Modifier.width(FolioSpace.gap))
                    Text(
                        stringResource(step),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(top = 2.dp),
                    )
                }
            }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.shell_done))
        }
    }
}
