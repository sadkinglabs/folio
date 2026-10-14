package dev.sadking.folio.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.sadking.folio.R
import dev.sadking.folio.data.Book

@Composable
fun RemoveBooksDialog(
    books: List<Book>,
    noteCount: Int,
    busy: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (books.size == 1)
                    stringResource(R.string.shell_remove_one_title, books.first().title)
                else
                    pluralStringResource(
                        R.plurals.shell_remove_many_title,
                        books.size,
                        books.size,
                    )
            )
        },
        text = {
            Column {
                Text(stringResource(R.string.shell_remove_body))
                if (noteCount > 0)
                    Text(pluralStringResource(R.plurals.shell_remove_notes, noteCount, noteCount))
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !busy && books.isNotEmpty(),
                colors =
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.shell_remove_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.shell_keep_books))
            }
        },
    )
}
