@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sadking.folio.R
import dev.sadking.folio.data.*

internal const val BOOK_ACTIONS_SHEET_TAG = "book-actions-sheet"

internal fun bookActionTag(action: BookAction) = "book-actions-${action.name.lowercase()}"

/**
 * Quick actions for a book from the library, without opening its page: the cover and title, five
 * icon actions and a row to the page. A record offers Find and Attach instead of Read. Sync puts
 * every book on the e-reader, so there is no Send.
 */
@Composable
fun BookActionsSheet(
    book: Book,
    hasEpub: Boolean,
    busy: Boolean,
    onAction: (BookAction) -> Unit,
    onDismiss: () -> Unit,
) {
    FolioSheet(
        onDismissRequest = onDismiss,
        shape = FolioSpace.sheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .padding(start = FolioSpace.section, end = FolioSpace.section, bottom = 32.dp)
                .testTag(BOOK_ACTIONS_SHEET_TAG),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.section),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FolioSpace.item),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Decoration: the title beside it carries the meaning.
                CoverTile(book, Modifier.width(72.dp).clearAndSetSemantics {}, lifted = false)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        book.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        book.authorLabel.ifBlank { stringResource(R.string.shell_unknown_author) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            ActionRow(
                quickBookActions(hasEpub).map { action ->
                    val (icon, label) =
                        when (action) {
                            BookAction.OPEN_READER ->
                                Icons.Outlined.AutoStories to R.string.rd_action_read
                            BookAction.SEARCH -> Icons.Outlined.Search to R.string.rd_action_find
                            BookAction.ATTACH ->
                                Icons.Outlined.AttachFile to R.string.rd_action_attach
                            BookAction.LISTS ->
                                Icons.Outlined.CollectionsBookmark to R.string.shell_lists
                            BookAction.STATUS ->
                                Icons.Outlined.Bookmark to R.string.rd_action_status
                            BookAction.SELECT ->
                                Icons.Outlined.CheckCircle to R.string.rd_action_select
                            BookAction.REMOVE ->
                                Icons.Outlined.DeleteOutline to R.string.rd_action_remove
                            else -> Icons.Outlined.AutoStories to R.string.rd_open_book_page
                        }
                    // Selecting and searching start nothing on the phone, so a running
                    // operation
                    // does not block them.
                    IconAction(
                        icon,
                        stringResource(label),
                        enabled = !busy || action in listOf(BookAction.SELECT, BookAction.SEARCH),
                        destructive = action == BookAction.REMOVE,
                        tag = bookActionTag(action),
                    ) {
                        onAction(action)
                    }
                }
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.rd_open_book_page)) },
                trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier =
                    Modifier.clickable(role = Role.Button) { onAction(BookAction.OPEN) }
                        .testTag(bookActionTag(BookAction.OPEN)),
            )
        }
    }
}
