@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.sadking.folio.data.Book

internal const val CONFIRM_SHEET_TAG = "confirm-sheet"
internal const val CONFIRM_SHEET_CONFIRM_TAG = "confirm-sheet-confirm"
internal const val CONFIRM_SHEET_DISMISS_TAG = "confirm-sheet-dismiss"

/**
 * A small sheet for a two-step action: the cover(s) it concerns, a question, one line on what
 * happens, and two buttons. The destructive variant colours the confirm button with `error`. Every
 * remove, detach and attach goes through here, so they all look and read the same.
 */
@Composable
internal fun ConfirmSheet(
    covers: List<Book>,
    title: String,
    body: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    busy: Boolean = false,
    confirmEnabled: Boolean = true,
    error: String? = null,
) {
    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        shape = FolioSpace.sheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .padding(start = FolioSpace.section, end = FolioSpace.section, bottom = 32.dp)
                .testTag(CONFIRM_SHEET_TAG),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FolioSpace.item),
        ) {
            if (covers.isNotEmpty())
                Row(
                    horizontalArrangement = Arrangement.spacedBy(FolioSpace.gap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    covers.take(3).forEach { CoverTile(it, Modifier.width(88.dp)) }
                    if (covers.size > 3)
                        Text(
                            "+${covers.size - 3}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = FolioSpace.small),
            )
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (error != null)
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            Row(
                Modifier.fillMaxWidth().padding(top = FolioSpace.small),
                horizontalArrangement = Arrangement.spacedBy(FolioSpace.gap),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !busy,
                    modifier =
                        Modifier.weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag(CONFIRM_SHEET_DISMISS_TAG),
                ) {
                    Text(dismissLabel)
                }
                FilledTonalButton(
                    onClick = onConfirm,
                    enabled = !busy && confirmEnabled,
                    modifier =
                        Modifier.weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag(CONFIRM_SHEET_CONFIRM_TAG),
                    colors =
                        if (destructive)
                            ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        else ButtonDefaults.filledTonalButtonColors(),
                ) {
                    Text(confirmLabel)
                }
            }
        }
    }
}
