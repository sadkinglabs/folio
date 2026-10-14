@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sadking.folio.R
import dev.sadking.folio.data.*

@Composable
fun BookActionsSheet(
    book: Book,
    hasEpub: Boolean,
    hasAnnotations: Boolean,
    busy: Boolean,
    onAction: (BookAction) -> Unit,
    attached: Boolean = false,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                    Text(book.title, style = MaterialTheme.typography.headlineSmall)
                    Text(book.authorLabel, style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(
                availableBookActions(hasEpub, hasAnnotations).filter {
                    it != BookAction.DETACH || attached
                }
            ) { action ->
                val label =
                    when (action) {
                        BookAction.OPEN -> R.string.shell_open_book_details
                        BookAction.SELECT -> R.string.shell_action_select
                        BookAction.SEND -> R.string.book_send
                        BookAction.PROGRESS -> R.string.shell_update_progress
                        BookAction.EDIT -> R.string.shell_action_edit
                        BookAction.LISTS -> R.string.shell_manage_lists
                        BookAction.STATUS -> R.string.shell_action_status
                        BookAction.EXPORT_NOTES -> R.string.shell_action_export_notes
                        BookAction.ATTACH -> R.string.shell_action_attach
                        BookAction.SEARCH -> R.string.shell_action_search
                        BookAction.REMOVE -> R.string.shell_action_remove
                        BookAction.DETACH -> R.string.shell_action_detach
                        BookAction.EXPORT_EPUB -> R.string.shell_action_export_epub
                        BookAction.SHARE_EPUB -> R.string.shell_action_share_epub
                        BookAction.OPEN_READER -> R.string.shell_action_read
                        BookAction.EXTERNAL_READER -> R.string.shell_action_external
                    }
                val icon =
                    when (action) {
                        BookAction.OPEN -> Icons.Outlined.AutoStories
                        BookAction.SELECT -> Icons.Outlined.CheckCircle
                        BookAction.SEND -> Icons.Outlined.Send
                        BookAction.PROGRESS -> Icons.Outlined.Sync
                        BookAction.EDIT -> Icons.Outlined.Edit
                        BookAction.LISTS -> Icons.Outlined.CollectionsBookmark
                        BookAction.STATUS -> Icons.Outlined.Bookmark
                        BookAction.EXPORT_NOTES -> Icons.Outlined.FileDownload
                        BookAction.ATTACH -> Icons.Outlined.AttachFile
                        BookAction.SEARCH -> Icons.Outlined.Search
                        BookAction.REMOVE -> Icons.Outlined.DeleteOutline
                        BookAction.DETACH -> Icons.Outlined.LinkOff
                        BookAction.EXPORT_EPUB -> Icons.Outlined.FileDownload
                        BookAction.SHARE_EPUB -> Icons.Outlined.Share
                        BookAction.OPEN_READER -> Icons.Outlined.AutoStories
                        BookAction.EXTERNAL_READER -> Icons.Outlined.OpenInNew
                    }
                TextButton(
                    onClick = { onAction(action) },
                    enabled =
                        !busy ||
                            action in listOf(BookAction.OPEN, BookAction.SELECT, BookAction.SEARCH),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor =
                                if (action == BookAction.REMOVE) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface
                        ),
                ) {
                    Icon(icon, null)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(label), Modifier.weight(1f))
                }
            }
        }
    }
}
