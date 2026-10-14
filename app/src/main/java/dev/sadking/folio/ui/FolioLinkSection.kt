package dev.sadking.folio.ui

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sadking.folio.FolioApplication
import dev.sadking.folio.R
import dev.sadking.folio.link.FolioLink
import dev.sadking.folio.link.LinkLog
import kotlinx.coroutines.delay

/** Settings for Folio Link: on or off, what to type into KOReader, and what it has heard. */
@Composable
internal fun FolioLinkSection() {
    val link = (LocalContext.current.applicationContext as? FolioApplication)?.link ?: return
    val port by link.port.collectAsStateWithLifecycle()
    val entries by link.log.entries.collectAsStateWithLifecycle()
    var enabled by remember { mutableStateOf(link.settings.enabled) }
    var keepAwake by remember { mutableStateOf(link.settings.keepAwake) }
    var showPassword by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf<String?>(null) }
    // Addresses change as Wi-Fi and the hotspot come and go; refresh while the screen is open.
    var addresses by remember { mutableStateOf(FolioLink.addresses()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            addresses = FolioLink.addresses()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val title = stringResource(R.string.link_title)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.link_summary),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Switch(
                enabled,
                { on ->
                    enabled = on
                    if (on) link.start() else link.stop()
                },
                Modifier.semantics { contentDescription = title },
            )
        }
        if (!enabled) return@Column

        Text(
            when {
                port == null -> stringResource(R.string.link_starting)
                addresses.isEmpty() -> stringResource(R.string.link_no_network)
                else -> stringResource(R.string.link_address_label)
            },
            style = MaterialTheme.typography.labelLarge,
        )
        port?.let { listening ->
            SelectionContainer {
                Column {
                    addresses.forEach {
                        Text(
                            "http://$it:$listening",
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.link_username, link.settings.username))
                Text(
                    stringResource(
                        R.string.link_password,
                        if (showPassword) password ?: "" else "••••••••••••",
                    ),
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    if (password == null) password = link.settings.password()
                    showPassword = !showPassword
                }
            ) {
                Text(
                    stringResource(
                        if (showPassword) R.string.link_hide_password
                        else R.string.link_show_password
                    )
                )
            }
            TextButton(
                onClick = {
                    password = link.settings.resetPassword()
                    showPassword = true
                }
            ) {
                Text(stringResource(R.string.link_new_password))
            }
        }
        Text(stringResource(R.string.link_steps), style = MaterialTheme.typography.bodySmall)

        val heard = entries.firstOrNull { it.kind == LinkLog.Kind.REQUEST && it.status in 200..299 }
        Text(
            if (heard == null) stringResource(R.string.link_not_heard)
            else
                stringResource(
                    R.string.link_last_heard,
                    DateUtils.getRelativeTimeSpanString(heard.time).toString(),
                ),
            style = MaterialTheme.typography.bodyMedium,
        )

        val awake = stringResource(R.string.link_keep_awake)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(awake)
                Text(
                    stringResource(R.string.link_keep_awake_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                keepAwake,
                { on ->
                    keepAwake = on
                    link.settings.keepAwake = on
                    // The lock is taken when Link starts; restart it to apply the change.
                    link.stop()
                    link.start()
                },
                Modifier.semantics { contentDescription = awake },
            )
        }

        TextButton(onClick = { showLog = !showLog }) {
            Text(stringResource(if (showLog) R.string.link_hide_log else R.string.link_show_log))
        }
        if (showLog) LinkLogList(entries.take(50), onClear = link.log::clear)
    }
}

@Composable
private fun LinkLogList(entries: List<LinkLog.Entry>, onClear: () -> Unit) {
    val context = LocalContext.current
    val timeFormat = remember { DateFormat.getTimeFormat(context) }
    val dateFormat = remember { DateFormat.getDateFormat(context) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (entries.isEmpty())
            Text(
                stringResource(R.string.link_log_empty),
                style = MaterialTheme.typography.bodySmall,
            )
        entries.forEach { entry ->
            val date = java.util.Date(entry.time)
            Text(
                stringResource(
                    R.string.link_log_line,
                    "${dateFormat.format(date)} ${timeFormat.format(date)}",
                    describe(entry),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (entries.isNotEmpty())
            TextButton(onClick = onClear) { Text(stringResource(R.string.link_log_clear)) }
    }
}

@Composable
private fun describe(entry: LinkLog.Entry): String =
    when (entry.kind) {
        LinkLog.Kind.STARTED -> stringResource(R.string.link_log_started, entry.port)
        LinkLog.Kind.STOPPED -> stringResource(R.string.link_log_stopped)
        LinkLog.Kind.FAILED_TO_START -> stringResource(R.string.link_log_failed, entry.port)
        LinkLog.Kind.SCREEN_OFF -> stringResource(R.string.link_log_screen_off)
        LinkLog.Kind.SCREEN_ON -> stringResource(R.string.link_log_screen_on)
        LinkLog.Kind.REQUEST ->
            stringResource(
                when {
                    entry.status == 401 || entry.status == 429 -> R.string.link_log_refused
                    entry.route == "/users/auth" || entry.route == "/users/create" ->
                        R.string.link_log_login
                    entry.route == "/syncs/progress" -> R.string.link_log_sent
                    entry.route.startsWith("/syncs/progress/") -> R.string.link_log_asked
                    else -> R.string.link_log_other
                },
                entry.remote,
            )
    }

/** Whether reading positions go to Hardcover on their own. */
@Composable
internal fun HardcoverAutomaticRow() {
    val outbox =
        (LocalContext.current.applicationContext as? FolioApplication)?.hardcoverOutbox ?: return
    var on by remember { mutableStateOf(outbox.automatic) }
    val label = stringResource(R.string.hardcover_automatic)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            Text(
                stringResource(R.string.hardcover_automatic_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            on,
            {
                on = it
                outbox.automatic = it
            },
            Modifier.semantics { contentDescription = label },
        )
    }
}
