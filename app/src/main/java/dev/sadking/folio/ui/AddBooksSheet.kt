@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class AddBookAction {
    EPUB,
    MANUAL,
    DISCOVER,
    HARDCOVER,
    TITLE,
}

@Composable
fun AddBooksSheet(
    hardcoverUsername: String,
    onAction: (AddBookAction) -> Unit,
    onDismiss: () -> Unit,
) {
    // Fully expanded and scrollable: half-height hid the Hardcover routes on ordinary phones.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)
        ) {
            Text(
                "Add books",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            FeatureRow(
                "Import EPUBs",
                "Choose files from Downloads or your storage provider",
                Icons.Outlined.FileUpload,
                onClick = { onAction(AddBookAction.EPUB) },
            )
            FeatureRow(
                "Add book manually",
                "Enter a book offline, with or without a file",
                Icons.Outlined.Edit,
                onClick = { onAction(AddBookAction.MANUAL) },
            )
            FeatureRow(
                "Scan or find a book",
                "Use a cover photo, title, author or ISBN",
                Icons.Outlined.PhotoCamera,
                onClick = { onAction(AddBookAction.DISCOVER) },
            )
            FeatureRow(
                "Look up on Hardcover",
                "Search a title and fetch its metadata",
                Icons.Outlined.Search,
                onClick = { onAction(AddBookAction.TITLE) },
            )
            FeatureRow(
                "Import Hardcover library",
                if (hardcoverUsername.isBlank()) "Connect your account, then review your books"
                else "Review books and matches from @$hardcoverUsername",
                Icons.Outlined.AccountCircle,
                onClick = { onAction(AddBookAction.HARDCOVER) },
            )
        }
    }
}
