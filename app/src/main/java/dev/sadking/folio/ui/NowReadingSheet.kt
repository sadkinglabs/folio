@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package dev.sadking.folio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sadking.folio.R
import dev.sadking.folio.data.Book
import dev.sadking.folio.data.CurrentBook

/** What the Now Reading sheet can do with the current book besides continuing. */
internal enum class NowReadingAction {
    SEND,
    EDIT,
    PROGRESS,
    HARDCOVER,
    SHARE,
    REMOVE,
    OPEN,
}

/**
 * One of the six icon actions under a book: a 56 dp tonal icon button with a short label. While
 * [busy], a ring replaces the icon and the button is disabled. [tag] is for tests.
 */
internal class IconAction(
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val busy: Boolean = false,
    val tag: String? = null,
    val onClick: () -> Unit,
)

/**
 * Icon actions three to a row, each with an 11 sp label under the icon. A short last row keeps the
 * grid's columns, so five actions line up with six.
 */
@Composable
internal fun ActionRow(actions: List<IconAction>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FolioSpace.item)) {
        actions.chunked(3).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
            ) {
                row.forEach { action ->
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FilledTonalIconButton(
                            onClick = action.onClick,
                            enabled = action.enabled && !action.busy,
                            modifier =
                                Modifier.size(56.dp)
                                    .then(action.tag?.let { Modifier.testTag(it) } ?: Modifier),
                            colors =
                                if (action.destructive)
                                    IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                else IconButtonDefaults.filledTonalIconButtonColors(),
                        ) {
                            if (action.busy)
                                LoadingIndicator(
                                    Modifier.size(28.dp).semantics {
                                        contentDescription = action.label
                                    }
                                )
                            else Icon(action.icon, action.label)
                        }
                        Text(
                            action.label,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * The Now Reading sheet, from the centre tile's long-press: the current book with Continue, six
 * icon actions and a way to its page. With no current book it shows the empty form: the most recent
 * books marked Reading, and a way to the Library.
 */
@Composable
internal fun NowReadingSheet(
    current: CurrentBook?,
    recent: List<Book>,
    busy: Boolean,
    onContinue: () -> Unit,
    onAction: (NowReadingAction) -> Unit,
    onPick: (Book) -> Unit,
    onLibrary: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = FolioSpace.sheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .padding(start = FolioSpace.section, end = FolioSpace.section, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.section),
        ) {
            if (current == null) EmptyNowReading(recent, onPick, onLibrary)
            else {
                val percent = current.percent?.let { (it * 100).toInt() }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FolioSpace.item),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Decoration: the title beside it carries the meaning.
                    CoverTile(current.book, Modifier.width(72.dp).clearAndSetSemantics {})
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            current.book.title,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            current.book.authorLabel.ifBlank {
                                stringResource(R.string.shell_unknown_author)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (percent != null)
                        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { (percent / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxSize(),
                                strokeWidth = 3.dp,
                                trackColor = MaterialTheme.colorScheme.primaryContainer,
                            )
                            Text(
                                stringResource(R.string.rd_percent, percent),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                }
                Button(
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Text(
                        if (percent != null) stringResource(R.string.rd_continue_percent, percent)
                        else stringResource(R.string.rd_continue)
                    )
                }
                ActionRow(
                    listOf(
                        IconAction(
                            Icons.AutoMirrored.Outlined.Send,
                            stringResource(R.string.rd_action_send),
                            enabled = !busy,
                        ) {
                            onAction(NowReadingAction.SEND)
                        },
                        IconAction(
                            Icons.Outlined.Edit,
                            stringResource(R.string.rd_action_edit),
                            enabled = !busy,
                        ) {
                            onAction(NowReadingAction.EDIT)
                        },
                        IconAction(
                            Icons.Outlined.Timeline,
                            stringResource(R.string.rd_action_progress),
                            enabled = !busy,
                        ) {
                            onAction(NowReadingAction.PROGRESS)
                        },
                        IconAction(
                            Icons.Outlined.Link,
                            stringResource(R.string.rd_action_hardcover),
                            enabled = !busy,
                        ) {
                            onAction(NowReadingAction.HARDCOVER)
                        },
                        IconAction(
                            Icons.Outlined.Share,
                            stringResource(R.string.rd_action_share),
                            enabled = !busy,
                        ) {
                            onAction(NowReadingAction.SHARE)
                        },
                        IconAction(
                            Icons.Outlined.DeleteOutline,
                            stringResource(R.string.rd_action_remove),
                            enabled = !busy,
                            destructive = true,
                        ) {
                            onAction(NowReadingAction.REMOVE)
                        },
                    )
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.rd_open_book_page)) },
                    trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier =
                        Modifier.clickable(role = Role.Button) { onAction(NowReadingAction.OPEN) },
                )
            }
        }
    }
}

@Composable
private fun EmptyNowReading(recent: List<Book>, onPick: (Book) -> Unit, onLibrary: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(FolioSpace.small)) {
        Text(stringResource(R.string.rd_pick_a_book), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.rd_pick_a_book_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (recent.isNotEmpty())
        Row(horizontalArrangement = Arrangement.spacedBy(FolioSpace.item)) {
            recent.take(3).forEach { book ->
                val open = stringResource(R.string.shell_action_read)
                CoverTile(
                    book,
                    Modifier.width(96.dp).clickable(role = Role.Button, onClickLabel = open) {
                        onPick(book)
                    },
                )
            }
        }
    FilledTonalButton(onClick = onLibrary, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.rd_go_to_library))
    }
}
