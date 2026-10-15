package dev.sadking.folio.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.sadking.folio.R
import dev.sadking.folio.data.Book

/**
 * "Remove Frankenstein?" with the cover: the books go to Recently removed, and their files, notes
 * and progress are kept. Permanent deletion lives only in Recently removed.
 */
@Composable
fun RemoveBooksSheet(
    books: List<Book>,
    busy: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmSheet(
        covers = books,
        title =
            if (books.size == 1)
                stringResource(R.string.shell_remove_one_title, books.first().title)
            else pluralStringResource(R.plurals.shell_remove_many_title, books.size, books.size),
        body =
            if (books.size == 1) stringResource(R.string.shell_remove_body)
            else stringResource(R.string.rd_remove_body_many),
        confirmLabel = stringResource(R.string.shell_remove_confirm),
        dismissLabel = stringResource(R.string.shell_keep_books),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = true,
        busy = busy,
        confirmEnabled = books.isNotEmpty(),
        error = error,
    )
}
