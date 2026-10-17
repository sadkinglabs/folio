@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal const val SHEET_MESSAGES_TAG = "sheet-messages"

/**
 * The app's messages (its snackbar) and the sheets open over the app. A sheet is a window of its
 * own, drawn over the screen's snackbar, so while one is open the message shows in the sheet that
 * is on top instead ([SheetMessages]), and the screen's own host stays empty.
 */
@Stable
internal class AppMessages(val host: SnackbarHostState) {
    private val sheets = mutableStateListOf<Any>()

    /** True while a sheet shows the messages: the screen's host shows nothing. */
    val inSheet: Boolean
        get() = sheets.isNotEmpty()

    internal fun open(sheet: Any) {
        sheets += sheet
    }

    internal fun close(sheet: Any) {
        sheets -= sheet
    }

    internal fun onTop(sheet: Any) = sheets.lastOrNull() === sheet
}

/** The app's messages, provided by FolioApp; null in previews and tests that do not set it. */
internal val LocalAppMessages = staticCompositionLocalOf<AppMessages?> { null }

/**
 * The app's bottom sheet: Material's modal sheet with the app's messages at its top, so a message
 * from an action taken in the sheet is seen, not hidden under it.
 */
@Composable
internal fun FolioSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = shape,
        containerColor = containerColor,
    ) {
        SheetMessages()
        content()
    }
}

/**
 * The app's current message, at the top of the sheet that is on top (the top stays in view however
 * far the sheet is pulled up or scrolled). Shows nothing outside the app shell.
 */
@Composable
internal fun SheetMessages(modifier: Modifier = Modifier) {
    val messages = LocalAppMessages.current ?: return
    val token = remember { Any() }
    DisposableEffect(messages, token) {
        messages.open(token)
        onDispose { messages.close(token) }
    }
    if (messages.onTop(token))
        SnackbarHost(
            messages.host,
            modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag(SHEET_MESSAGES_TAG),
        )
}
