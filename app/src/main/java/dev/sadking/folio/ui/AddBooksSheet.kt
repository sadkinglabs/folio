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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sadking.folio.R

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
                stringResource(R.string.library_add_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            FeatureRow(
                stringResource(R.string.library_add_epub),
                stringResource(R.string.library_add_epub_body),
                Icons.Outlined.FileUpload,
                onClick = { onAction(AddBookAction.EPUB) },
            )
            FeatureRow(
                stringResource(R.string.library_add_manual),
                stringResource(R.string.library_add_manual_body),
                Icons.Outlined.Edit,
                onClick = { onAction(AddBookAction.MANUAL) },
            )
            FeatureRow(
                stringResource(R.string.library_add_discover),
                stringResource(R.string.library_add_discover_body),
                Icons.Outlined.PhotoCamera,
                onClick = { onAction(AddBookAction.DISCOVER) },
            )
            FeatureRow(
                stringResource(R.string.library_add_lookup),
                stringResource(R.string.library_add_lookup_body),
                Icons.Outlined.Search,
                onClick = { onAction(AddBookAction.TITLE) },
            )
            FeatureRow(
                stringResource(R.string.library_add_hardcover),
                if (hardcoverUsername.isBlank())
                    stringResource(R.string.library_add_hardcover_connect)
                else stringResource(R.string.library_add_hardcover_review, hardcoverUsername),
                Icons.Outlined.AccountCircle,
                onClick = { onAction(AddBookAction.HARDCOVER) },
            )
        }
    }
}
