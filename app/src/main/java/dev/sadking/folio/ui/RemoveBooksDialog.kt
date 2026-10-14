package dev.sadking.folio.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
                if (books.size == 1) "Remove ${books.first().title}?"
                else "Remove ${books.size} books?"
            )
        },
        text = {
            Column {
                Text(
                    "Moves these books out of your library into Recently removed. Files, covers, lists and cached reading data are kept for restoration. Downloads, Kindle and Hardcover are untouched."
                )
                if (noteCount > 0)
                    Text("$noteCount cached highlights/notes are retained in Recently removed.")
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
                Text("Move to Recently removed")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Keep books") } },
    )
}
