package dev.sadking.folio.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sadking.folio.DiscoveryViewModel

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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (username.isBlank()) "Connect Hardcover" else "Hardcover account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (username.isBlank())
                        "Connect to share selected books and reviewed reading updates."
                    else "Connected as @$username. Changes are published only when you confirm."
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
                            model.report("Install a browser to open Hardcover settings")
                        }
                    }
                ) {
                    Text("Get your API token ↗")
                }
                OutlinedTextField(
                    token,
                    { token = it },
                    label = { Text("Personal API token") },
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
                    "Encrypted on this phone and excluded from backups. New books use your account privacy setting; existing privacy is retained.",
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
                        Text("Disconnect Hardcover")
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
                Text(if (username.isBlank()) "Connect" else "Replace token")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
