@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sadking.folio.DiscoveryViewModel
import dev.sadking.folio.R
import dev.sadking.folio.sync.HardcoverSync

internal const val HARDCOVER_ACCOUNT_TAG = "hardcover-account"
internal const val HARDCOVER_TOKEN_TAG = "hardcover-token"
internal const val HARDCOVER_CONNECT_TAG = "hardcover-connect"

/**
 * One account flow for settings, discovery and book tracking: a sheet with the token field, a link
 * to get a token, and Connect. The token never enters saved state.
 */
@Composable
internal fun HardcoverAccountSheet(
    model: DiscoveryViewModel,
    onConnected: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val username by model.username.collectAsStateWithLifecycle()
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val noBrowser = stringResource(R.string.hardcover_no_browser)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = FolioSpace.sheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        HardcoverAccountContent(
            username = username,
            busy = state.busy,
            error = state.message.takeIf { state.failed },
            onGetToken = {
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://hardcover.app/account/api"))
                    )
                } catch (_: android.content.ActivityNotFoundException) {
                    model.report(noBrowser)
                }
            },
            onConnect = { token ->
                model.connect(token) {
                    // A new account: match the library and sync statuses with it now.
                    HardcoverSync.get(context.applicationContext).syncNow()
                    onConnected()
                    onDismiss()
                }
            },
            onDisconnect = {
                model.forget()
                onDismiss()
            },
        )
    }
}

@Composable
internal fun HardcoverAccountContent(
    username: String,
    busy: Boolean,
    error: String?,
    onGetToken: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    var token by remember { mutableStateOf("") }
    val connected = username.isNotBlank()
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(start = FolioSpace.section, end = FolioSpace.section, bottom = 32.dp)
            .testTag(HARDCOVER_ACCOUNT_TAG),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.item),
    ) {
        Text(
            stringResource(
                if (connected) R.string.hardcover_account else R.string.hardcover_connect
            ),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            if (connected) stringResource(R.string.hardcover_connected_as, username)
            else stringResource(R.string.hardcover_account_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            token,
            { token = it },
            label = { Text(stringResource(R.string.hardcover_token_label)) },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation(),
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth().testTag(HARDCOVER_TOKEN_TAG),
        )
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onGetToken, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text(stringResource(R.string.hardcover_get_token))
            }
            Spacer(Modifier.weight(1f))
            if (connected)
                TextButton(onClick = onDisconnect, enabled = !busy) {
                    Text(stringResource(R.string.hardcover_disconnect))
                }
            Button(
                onClick = {
                    onConnect(token)
                    token = ""
                },
                enabled = token.isNotBlank() && !busy,
                modifier = Modifier.testTag(HARDCOVER_CONNECT_TAG),
            ) {
                Text(
                    stringResource(
                        if (connected) R.string.hardcover_replace_token
                        else R.string.hardcover_connect_button
                    )
                )
            }
        }
        Text(
            stringResource(R.string.hardcover_token_privacy),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
