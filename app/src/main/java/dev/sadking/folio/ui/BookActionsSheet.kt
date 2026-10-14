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
import androidx.compose.ui.unit.dp
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
                        BookAction.OPEN -> "Open book details"
                        BookAction.SELECT -> "Select for batch actions"
                        BookAction.SEND -> "Send to device"
                        BookAction.PROGRESS -> "Update progress"
                        BookAction.EDIT -> "Edit metadata & cover"
                        BookAction.LISTS -> "Manage lists"
                        BookAction.STATUS -> "Change phone reading status"
                        BookAction.EXPORT_NOTES -> "Export highlights & notes"
                        BookAction.ATTACH -> "Attach EPUB from phone"
                        BookAction.SEARCH -> "Search Anna’s Archive ↗"
                        BookAction.REMOVE -> "Remove from library"
                        BookAction.DETACH -> "Detach catalogue entry"
                        BookAction.EXPORT_EPUB -> "Export EPUB to Files"
                        BookAction.SHARE_EPUB -> "Share EPUB"
                        BookAction.OPEN_READER -> "Read on phone"
                        BookAction.EXTERNAL_READER -> "Open with another reader"
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
                    Text(label, Modifier.weight(1f))
                }
            }
        }
    }
}
