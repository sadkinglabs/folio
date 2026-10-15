package dev.sadking.folio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.sadking.folio.FolioApplication
import dev.sadking.folio.R

/**
 * Folio Link's two settings: keeping the phone awake and sending positions on to Hardcover. The
 * switch for Link itself, the address and the activity live on the Device tab.
 */
@Composable
internal fun FolioLinkSection() {
    val app = LocalContext.current.applicationContext as? FolioApplication ?: return
    val link = app.link
    var keepAwake by remember { mutableStateOf(link.settings.keepAwake) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SwitchRow(
            stringResource(R.string.link_keep_awake),
            stringResource(R.string.link_keep_awake_hint),
            keepAwake,
        ) { on ->
            keepAwake = on
            link.settings.keepAwake = on
            // The lock is taken when Link starts; restart it to apply the change.
            if (link.settings.enabled) {
                link.stop()
                link.start()
            }
        }
        var automatic by remember { mutableStateOf(app.hardcoverOutbox.automatic) }
        SwitchRow(stringResource(R.string.hardcover_automatic), null, automatic) {
            automatic = it
            app.hardcoverOutbox.automatic = it
        }
    }
}

@Composable
private fun SwitchRow(label: String, hint: String?, value: Boolean, onValue: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (hint != null)
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
        Switch(value, onValue, Modifier.semantics { contentDescription = label })
    }
}
