@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.sadking.folio.R

enum class AddBookAction {
    EPUB,
    MANUAL,
    DISCOVER,
    HARDCOVER,
    TITLE,
}

internal const val ADD_SHEET_TAG = "add-sheet"

/**
 * The Add sheet: four large tonal tiles with an icon and two words, then one row for the Hardcover
 * library import. No explanations; each tile says what it does.
 */
@Composable
fun AddBooksSheet(
    hardcoverUsername: String,
    onAction: (AddBookAction) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = FolioSpace.sheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(ADD_SHEET_TAG),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = FolioSpace.section, end = FolioSpace.section, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.gap),
        ) {
            Text(
                stringResource(R.string.library_add_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpace.gap)) {
                AddTile(
                    stringResource(R.string.library_add_epub),
                    Icons.Outlined.FileUpload,
                    Modifier.weight(1f),
                ) {
                    onAction(AddBookAction.EPUB)
                }
                AddTile(
                    stringResource(R.string.library_add_lookup),
                    Icons.Outlined.TravelExplore,
                    Modifier.weight(1f),
                ) {
                    onAction(AddBookAction.TITLE)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpace.gap)) {
                AddTile(
                    stringResource(R.string.library_add_discover),
                    Icons.Outlined.PhotoCamera,
                    Modifier.weight(1f),
                ) {
                    onAction(AddBookAction.DISCOVER)
                }
                AddTile(
                    stringResource(R.string.library_add_manual),
                    Icons.Outlined.Edit,
                    Modifier.weight(1f),
                ) {
                    onAction(AddBookAction.MANUAL)
                }
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.library_add_hardcover)) },
                supportingContent = {
                    Text(
                        if (hardcoverUsername.isBlank())
                            stringResource(R.string.library_add_hardcover_connect)
                        else "@$hardcoverUsername"
                    )
                },
                leadingContent = {
                    Icon(
                        Icons.Outlined.AccountCircle,
                        null,
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                },
                trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier =
                    Modifier.clickable(role = Role.Button) { onAction(AddBookAction.HARDCOVER) },
            )
        }
    }
}

/** A large tonal tile: icon top-left, the label at the bottom. */
@Composable
private fun AddTile(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = FolioSpace.panelShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.heightIn(min = 112.dp),
    ) {
        Column(Modifier.padding(FolioSpace.item), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, null, Modifier.size(28.dp))
            Spacer(Modifier.height(FolioSpace.item))
            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 2)
        }
    }
}
