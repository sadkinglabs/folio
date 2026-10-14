package dev.sadking.folio.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sadking.folio.DiscoveryViewModel
import dev.sadking.folio.R

/** One account flow for settings, discovery and book tracking. Token never enters saved state. */
@Composable
internal fun HardcoverAccountDialog(
    model: DiscoveryViewModel,
    onConnected: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val username by model.username.collectAsStateWithLifecycle()
    val state by model.state.collectAsStateWithLifecycle()
    var token by remember { mutableStateOf("") }
    val context = LocalContext.current
    val noBrowser = stringResource(R.string.hardcover_no_browser)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (username.isBlank()) stringResource(R.string.hardcover_connect)
                else stringResource(R.string.hardcover_account)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (username.isBlank()) stringResource(R.string.hardcover_account_intro)
                    else stringResource(R.string.hardcover_account_connected, username)
                )
                TextButton(
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://hardcover.app/account/api"),
                                )
                            )
                        } catch (_: android.content.ActivityNotFoundException) {
                            model.report(noBrowser)
                        }
                    }
                ) {
                    Text(stringResource(R.string.hardcover_get_token))
                }
                OutlinedTextField(
                    token,
                    { token = it },
                    label = { Text(stringResource(R.string.hardcover_token_label)) },
                    singleLine = true,
                    enabled = !state.busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.failed)
                    Text(
                        state.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                Text(
                    stringResource(R.string.hardcover_token_privacy),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (username.isNotBlank())
                    TextButton(
                        onClick = {
                            model.forget()
                            token = ""
                            onDismiss()
                        },
                        enabled = !state.busy,
                    ) {
                        Text(stringResource(R.string.hardcover_disconnect))
                    }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    model.connect(token) {
                        token = ""
                        onConnected()
                        onDismiss()
                    }
                },
                enabled = token.isNotBlank() && !state.busy,
            ) {
                Text(
                    if (username.isBlank()) stringResource(R.string.hardcover_connect_button)
                    else stringResource(R.string.hardcover_replace_token)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.discovery_cancel)) }
        },
    )
}
