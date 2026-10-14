@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sadking.folio.DiscoveryViewModel
import dev.sadking.folio.R
import dev.sadking.folio.data.*

@Composable
fun HardcoverReadingSheet(
    model: DiscoveryViewModel,
    book: Book,
    snapshot: ReadingSnapshot?,
    onRefreshDevice: () -> Unit = {},
    devicePaired: Boolean = false,
    deviceOperation: dev.sadking.folio.Operation = dev.sadking.folio.Operation(),
    phoneReading: PhoneReading? = null,
    onDismiss: () -> Unit,
) {
    val username by model.username.collectAsStateWithLifecycle()
    val state by model.state.collectAsStateWithLifecycle()
    val reading by model.hardcoverReading.collectAsStateWithLifecycle()
    val editions by model.hardcoverEditions.collectAsStateWithLifecycle()
    val matches by model.matches.collectAsStateWithLifecycle()
    val snackbar = rememberOperationSnackbar(model.notices)
    var account by remember { mutableStateOf(false) }
    var search by
        remember(book.id) { mutableStateOf("${book.title} ${book.authorLabel}".take(300)) }
    var chooseEdition by remember { mutableStateOf(false) }
    var editionId by remember(book.id) { mutableStateOf<Int?>(null) }
    var input by remember(book.id) { mutableStateOf("") }
    var requestedDevice by remember(book.id) { mutableStateOf(false) }
    var useDevice by remember(book.id) { mutableStateOf(true) }
    var percentage by remember(book.id) { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var confirmStatus by remember { mutableStateOf(false) }
    var allowNew by remember { mutableStateOf(false) }
    var allowDecrease by remember { mutableStateOf(false) }
    val edition =
        editions.firstOrNull { it.id == editionId }
            ?: editions.firstOrNull { it.id == reading?.editionId && it.pages > 0 }
    val page =
        if (percentage)
            input
                .toDoubleOrNull()
                ?.takeIf { it.isFinite() && it in 0.0..100.0 }
                ?.let { p -> edition?.let { estimatedHardcoverPage(p, it.pages) } }
        else input.toIntOrNull()?.takeIf { it in 0..(edition?.pages ?: -1) }
    LaunchedEffect(snapshot?.fetchedAt) {
        if (useDevice && snapshot?.percent != null) {
            percentage = true
            input = koreaderPercent(snapshot.percent).toString()
        }
    }
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Box {
            Column(
                Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.hardcover_reading_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(book.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.hardcover_reading_intro),
                    style = MaterialTheme.typography.bodySmall,
                )
                SectionHeading(
                    if (book.currentPath.isEmpty()) stringResource(R.string.hardcover_step_manual)
                    else stringResource(R.string.hardcover_step_device),
                    if (book.currentPath.isEmpty())
                        stringResource(R.string.hardcover_step_manual_hint)
                    else stringResource(R.string.hardcover_step_device_hint),
                )
                if (book.currentPath.isNotEmpty()) {
                    OutlinedButton(
                        onClick = {
                            requestedDevice = true
                            useDevice = true
                            onRefreshDevice()
                        },
                        enabled =
                            book.currentPath.isNotEmpty() &&
                                devicePaired &&
                                !deviceOperation.busy &&
                                !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.hardcover_get_from_device))
                    }
                    if (requestedDevice && (deviceOperation.busy || deviceOperation.failed))
                        StatusSurface(deviceOperation)
                    if (snapshot == null)
                        Text(
                            stringResource(R.string.hardcover_no_device_progress),
                            style = MaterialTheme.typography.bodySmall,
                        )
                }
                SectionHeading(stringResource(R.string.hardcover_step_review))
                if (state.busy) {
                    Text(state.message)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (username.isBlank()) {
                    FeatureRow(
                        stringResource(R.string.hardcover_connect),
                        stringResource(R.string.hardcover_connect_hint),
                        androidx.compose.material.icons.Icons.Outlined.AccountCircle,
                        onClick = { account = true },
                    )
                } else {
                    Text(
                        stringResource(R.string.hardcover_connected_as, username),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    OutlinedButton(onClick = { model.openReading(book) }, enabled = !state.busy) {
                        Text(stringResource(R.string.hardcover_reload))
                    }
                    if (reading == null) {
                        OutlinedTextField(
                            search,
                            { search = it.take(300) },
                            label = { Text(stringResource(R.string.hardcover_title_and_author)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { model.searchHardcoverText(search) },
                            enabled = !state.busy && search.isNotBlank(),
                        ) {
                            Text(stringResource(R.string.hardcover_find))
                        }
                        matches.forEach { match ->
                            OutlinedCard(
                                onClick = { model.link(match) },
                                enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(match.title, style = MaterialTheme.typography.titleMedium)
                                    Text(match.authors.joinToString(", "))
                                    Text(
                                        stringResource(R.string.hardcover_link_match),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                        }
                    } else {
                        Text(
                            stringResource(
                                R.string.hardcover_status_line,
                                hardcoverStatusLabel(reading?.status),
                            )
                        )
                        reading?.pages?.let {
                            Text(stringResource(R.string.hardcover_last_page, it))
                        }
                        FilledTonalButton(
                            onClick = { confirmStatus = true },
                            enabled = !state.busy && reading?.status != 2,
                        ) {
                            Text(stringResource(R.string.hardcover_mark_reading))
                        }
                        Text(
                            stringResource(R.string.hardcover_choose_edition),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        OutlinedButton(
                            onClick = { chooseEdition = true },
                            enabled = !state.busy && editions.any { it.pages > 0 },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                edition?.let {
                                    pluralStringResource(
                                        R.plurals.hardcover_edition_option,
                                        it.pages,
                                        it.pages,
                                        it.label.ifBlank { it.title },
                                    )
                                } ?: stringResource(R.string.hardcover_choose_edition_button)
                            )
                        }
                        if (editions.none { it.pages > 0 })
                            Text(stringResource(R.string.hardcover_no_page_edition))
                        if (edition != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = !percentage,
                                    onClick = {
                                        percentage = false
                                        input = ""
                                    },
                                    label = { Text(stringResource(R.string.hardcover_mode_pages)) },
                                )
                                FilterChip(
                                    selected = percentage,
                                    onClick = {
                                        percentage = true
                                        input = ""
                                    },
                                    label = {
                                        Text(stringResource(R.string.hardcover_mode_percent))
                                    },
                                )
                            }
                            OutlinedTextField(
                                input,
                                {
                                    useDevice = false
                                    input = it.take(12)
                                },
                                label = {
                                    Text(
                                        if (percentage)
                                            stringResource(R.string.hardcover_percent_label)
                                        else stringResource(R.string.hardcover_page_label)
                                    )
                                },
                                keyboardOptions =
                                    KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (percentage)
                                Text(
                                    page?.let {
                                        pluralStringResource(
                                            R.plurals.hardcover_estimate_page,
                                            edition.pages,
                                            edition.pages,
                                            it,
                                        )
                                    }
                                        ?: pluralStringResource(
                                            R.plurals.hardcover_estimate,
                                            edition.pages,
                                            edition.pages,
                                        ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            phoneReading
                                ?.takeIf { it.revisionHash == book.currentHash }
                                ?.percent
                                ?.let { fraction ->
                                    TextButton(
                                        onClick = {
                                            useDevice = false
                                            percentage = true
                                            input = koreaderPercent(fraction).toString()
                                        }
                                    ) {
                                        Text(
                                            stringResource(
                                                R.string.hardcover_use_phone_position,
                                                fraction * 100,
                                            )
                                        )
                                    }
                                    Text(
                                        stringResource(
                                            R.string.hardcover_phone_saved,
                                            observationDate(phoneReading.updatedAt),
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            snapshot
                                ?.percent
                                ?.takeIf { it.isFinite() && it in 0.0..1.0 }
                                ?.let { fraction ->
                                    val percent = koreaderPercent(fraction)
                                    OutlinedButton(
                                        onClick = {
                                            percentage = true
                                            input = percent.toString()
                                        },
                                        enabled = !state.busy,
                                    ) {
                                        Text(
                                            stringResource(
                                                R.string.hardcover_use_device_position,
                                                percent,
                                            )
                                        )
                                    }
                                    Text(
                                        stringResource(
                                            if (snapshot.documentHash != book.currentHash)
                                                R.string.hardcover_device_fetched_old
                                            else R.string.hardcover_device_fetched,
                                            observationDate(snapshot.fetchedAt),
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            Button(
                                onClick = {
                                    allowNew = false
                                    allowDecrease = false
                                    confirm = true
                                },
                                enabled = !state.busy && !deviceOperation.busy && page != null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.hardcover_review_progress))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
    }
    if (account) HardcoverAccountDialog(model) { account = false }
    if (chooseEdition)
        AlertDialog(
            onDismissRequest = { chooseEdition = false },
            title = { Text(stringResource(R.string.hardcover_choose_edition_title)) },
            text = {
                LazyColumn(
                    Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(editions.filter { it.pages > 0 }, key = { it.id }) { item ->
                        OutlinedCard(
                            onClick = {
                                editionId = item.id
                                chooseEdition = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    pluralStringResource(
                                        R.plurals.hardcover_pages,
                                        item.pages,
                                        item.pages,
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(item.label.ifBlank { item.title })
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { chooseEdition = false }) {
                    Text(stringResource(R.string.discovery_close))
                }
            },
        )
    if (confirmStatus)
        AlertDialog(
            onDismissRequest = { confirmStatus = false },
            title = { Text(stringResource(R.string.hardcover_mark_reading_title)) },
            text = {
                Text(stringResource(R.string.hardcover_mark_reading_body, book.title))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmStatus = false
                        model.currentReading(book)
                    },
                    enabled = !state.busy,
                ) {
                    Text(stringResource(R.string.hardcover_update))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmStatus = false }) {
                    Text(stringResource(R.string.discovery_cancel))
                }
            },
        )
    val expected = reading
    if (confirm && expected != null && edition != null && page != null)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.hardcover_send_progress_title)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            if (percentage) R.string.hardcover_send_progress_body_estimated
                            else R.string.hardcover_send_progress_body,
                            book.title,
                            page,
                            edition.pages,
                            edition.label,
                        )
                    )
                    if (expected.readId == null)
                        Row {
                            Checkbox(allowNew, { allowNew = it })
                            Text(stringResource(R.string.hardcover_start_new_read))
                        }
                    if (expected.pages != null && page < expected.pages)
                        Row {
                            Checkbox(allowDecrease, { allowDecrease = it })
                            Text(stringResource(R.string.hardcover_allow_decrease))
                        }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        model.updateReading(book, edition, page, expected, allowNew, allowDecrease)
                    },
                    enabled =
                        !state.busy &&
                            (expected.readId != null || allowNew) &&
                            (expected.pages == null || page >= expected.pages || allowDecrease),
                ) {
                    Text(stringResource(R.string.hardcover_update))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) {
                    Text(stringResource(R.string.discovery_cancel))
                }
            },
        )
}

/** Hardcover status ids mapped to the names Hardcover shows. */
@Composable
internal fun hardcoverStatusLabel(status: Int?): String =
    when (status) {
        1 -> stringResource(R.string.hardcover_status_want_to_read)
        2 -> stringResource(R.string.hardcover_status_currently_reading)
        3 -> stringResource(R.string.hardcover_status_read)
        4 -> stringResource(R.string.hardcover_status_paused)
        5 -> stringResource(R.string.hardcover_status_did_not_finish)
        else -> stringResource(R.string.hardcover_status_none)
    }
