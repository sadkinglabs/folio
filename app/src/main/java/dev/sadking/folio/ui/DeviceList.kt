@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package dev.sadking.folio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.sadking.folio.R
import dev.sadking.folio.data.DeviceRoster
import dev.sadking.folio.data.DeviceSighting
import dev.sadking.folio.data.KnownDevice

internal const val DEVICE_LIST_TAG = "device-list"
internal const val DEVICE_SHEET_TAG = "device-sheet"
internal const val DEVICE_NAME_TAG = "device-name"
internal const val DEVICE_SAVE_NAME_TAG = "device-save-name"
internal const val DEVICE_FORGET_TAG = "device-forget"

internal fun deviceRowTag(key: String) = "device-row-$key"

internal fun deviceMergeTag(key: String) = "device-merge-$key"

internal fun deviceSeparateTag(key: String) = "device-separate-$key"

/**
 * Every known device as the owner arranged them (names, merges, forgets), for the screens that show
 * e-reader positions. FolioApp provides it; previews and tests get an empty one unless they set it.
 */
internal val LocalDevices = compositionLocalOf { DeviceRoster.EMPTY }

/** What to call a device: the owner's name or its own, else "E-reader" or the old position. */
@Composable
internal fun deviceName(device: KnownDevice): String =
    device.name.ifBlank {
        stringResource(if (device.old) R.string.dev_old_name else R.string.dev_unnamed)
    }

@Composable
private fun sightingName(sighting: DeviceSighting): String =
    sighting.name.ifBlank {
        stringResource(if (sighting.old) R.string.dev_old_name else R.string.dev_unnamed)
    }

/**
 * One line on what a device is and does: "Last heard from 10 hours ago · Syncs books · Positions
 * for 12 books", or for an old position, that it was read over SSH and won't update.
 */
@Composable
internal fun deviceSummary(device: KnownDevice, now: Long): String =
    listOfNotNull(
            stringResource(R.string.dev_seen, syncedAgo(device.lastSeen, now)),
            if (device.old) stringResource(R.string.dev_old_hint) else null,
            if (device.syncs) stringResource(R.string.dev_syncs) else null,
            device.positions
                .takeIf { it > 0 && !device.old }
                ?.let { pluralStringResource(R.plurals.dev_positions, it, it) },
            device.merged.size
                .takeIf { it > 0 }
                ?.let { pluralStringResource(R.plurals.dev_includes, it, it) },
        )
        .joinToString(" · ")

/**
 * The Device tab's list of devices: every e-reader that synced or sent a position, and the old
 * positions read over SSH before sync, newest first. Forgotten ones are not in [devices]. Tapping
 * one opens [DeviceSheet].
 */
@Composable
internal fun DeviceList(
    devices: List<KnownDevice>,
    now: Long,
    onOpen: (KnownDevice) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (devices.isEmpty()) return
    SettingsGroup(stringResource(R.string.dev_section), modifier.testTag(DEVICE_LIST_TAG)) {
        Text(
            stringResource(R.string.dev_section_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = FolioSpace.item, vertical = 4.dp),
        )
        devices.forEach { device ->
            SettingsRow(
                if (device.old) Icons.Outlined.History else Icons.Outlined.TabletAndroid,
                deviceName(device),
                deviceSummary(device, now),
                Modifier.testTag(deviceRowTag(device.key)),
                onClick = { onOpen(device) },
            )
        }
    }
}

/**
 * One device: rename it, say it is the same e-reader as another one (their positions then show as
 * one row, the newest winning), show a merged one separately again, or forget it. Nothing here
 * deletes a position.
 */
@Composable
internal fun DeviceSheet(
    device: KnownDevice,
    others: List<KnownDevice>,
    now: Long,
    onRename: (String) -> Unit,
    onMerge: (KnownDevice) -> Unit,
    onSeparate: (DeviceSighting) -> Unit,
    onForget: () -> Unit,
    onDismiss: () -> Unit,
) {
    FolioSheet(
        onDismissRequest = onDismiss,
        shape = FolioSpace.sheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        DeviceSheetContent(device, others, now, onRename, onMerge, onSeparate, onForget)
    }
}

/** The sheet's content, so previews and tests can show it without a sheet window. */
@Composable
internal fun DeviceSheetContent(
    device: KnownDevice,
    others: List<KnownDevice>,
    now: Long,
    onRename: (String) -> Unit = {},
    onMerge: (KnownDevice) -> Unit = {},
    onSeparate: (DeviceSighting) -> Unit = {},
    onForget: () -> Unit = {},
) {
    var name by rememberSaveable(device.key, device.name) { mutableStateOf(device.name) }
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(start = FolioSpace.section, end = FolioSpace.section, bottom = 32.dp)
            .testTag(DEVICE_SHEET_TAG),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.item),
    ) {
        SheetTitle(deviceName(device))
        Text(
            deviceSummary(device, now),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (device.renamed && device.ownName.isNotBlank())
            Text(
                stringResource(R.string.dev_sheet_own_name, device.ownName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        OutlinedTextField(
            name,
            { name = it.take(DeviceRoster.MAX_NAME) },
            label = { Text(stringResource(R.string.dev_sheet_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onRename(name) }),
            modifier = Modifier.fillMaxWidth().testTag(DEVICE_NAME_TAG),
        )
        FilledTonalButton(
            onClick = { onRename(name) },
            enabled = name.trim() != device.name,
            modifier = Modifier.heightIn(min = 48.dp).testTag(DEVICE_SAVE_NAME_TAG),
        ) {
            Text(stringResource(R.string.dev_sheet_save_name))
        }
        if (others.isNotEmpty()) {
            SheetSection(stringResource(R.string.dev_sheet_same_title))
            Text(
                stringResource(R.string.dev_sheet_same_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            others.forEach { other ->
                OutlinedButton(
                    onClick = { onMerge(other) },
                    modifier =
                        Modifier.fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag(deviceMergeTag(other.key)),
                ) {
                    Text(stringResource(R.string.dev_sheet_same, deviceName(other)))
                }
            }
        }
        if (device.merged.isNotEmpty()) {
            SheetSection(stringResource(R.string.dev_sheet_together_title))
            device.merged.forEach { member ->
                // The name above its button, so a long name stays readable at large text sizes.
                FlowRow(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        sightingName(member),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                    TextButton(
                        onClick = { onSeparate(member) },
                        modifier = Modifier.testTag(deviceSeparateTag(member.key)),
                    ) {
                        Text(stringResource(R.string.dev_sheet_separate))
                    }
                }
            }
        }
        HorizontalDivider()
        TextButton(
            onClick = onForget,
            colors =
                ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.heightIn(min = 48.dp).testTag(DEVICE_FORGET_TAG),
        ) {
            Text(stringResource(R.string.dev_sheet_forget))
        }
        Text(
            stringResource(R.string.dev_sheet_forget_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SheetSection(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = FolioSpace.small).semantics { heading() },
    )
}
