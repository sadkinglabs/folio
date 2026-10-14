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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sadking.folio.DiscoveryViewModel
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
                Text("Hardcover reading", style = MaterialTheme.typography.headlineSmall)
                Text(book.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Changes are sent only when you confirm. Phone and KOReader reading data stay intact.",
                    style = MaterialTheme.typography.bodySmall,
                )
                SectionHeading(
                    if (book.currentPath.isEmpty()) "1 · Manual progress"
                    else "1 · Kindle progress",
                    if (book.currentPath.isEmpty())
                        "Track this book on Hardcover now, or attach an EPUB to read it on Kindle."
                    else "Close the book in KOReader so its latest progress is saved.",
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
                        Text("Get latest from Kindle")
                    }
                    if (requestedDevice && (deviceOperation.busy || deviceOperation.failed))
                        StatusSurface(deviceOperation)
                    if (snapshot == null)
                        Text(
                            "No cached Kindle progress yet. Retrieve it, or enter a page/percentage manually below.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                }
                SectionHeading("2 · Review & update Hardcover")
                if (state.busy) {
                    Text(state.message)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (username.isBlank()) {
                    FeatureRow(
                        "Connect Hardcover",
                        "Link a book, then review status and progress updates.",
                        androidx.compose.material.icons.Icons.Outlined.AccountCircle,
                        onClick = { account = true },
                    )
                } else {
                    Text("Connected as @$username", style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(onClick = { model.openReading(book) }, enabled = !state.busy) {
                        Text("Reload Hardcover tracking")
                    }
                    if (reading == null) {
                        OutlinedTextField(
                            search,
                            { search = it.take(300) },
                            label = { Text("Title and author") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { model.searchHardcoverText(search) },
                            enabled = !state.busy && search.isNotBlank(),
                        ) {
                            Text("Find Hardcover book")
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
                                        "Confirm this match",
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                        }
                    } else {
                        Text(
                            "Status: ${when(reading?.status) { 1->"Want to Read"
2->"Currently Reading"
3->"Read"
4->"Paused"
5->"Did Not Finish"
else->"Not in your Hardcover library" }}"
                        )
                        reading?.pages?.let { Text("Last recorded: $it pages") }
                        FilledTonalButton(
                            onClick = { confirmStatus = true },
                            enabled = !state.busy && reading?.status != 2,
                        ) {
                            Text("Mark Currently Reading")
                        }
                        Text("Choose your edition", style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(
                            onClick = { chooseEdition = true },
                            enabled = !state.busy && editions.any { it.pages > 0 },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                edition?.let {
                                    "${it.pages} pages · ${it.label.ifBlank { it.title }}"
                                } ?: "Select a page-count edition"
                            )
                        }
                        if (editions.none { it.pages > 0 })
                            Text(
                                "No page-count edition was found. Reading status can still be updated; progress needs a page-count edition."
                            )
                        if (edition != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = !percentage,
                                    onClick = {
                                        percentage = false
                                        input = ""
                                    },
                                    label = { Text("Pages") },
                                )
                                FilterChip(
                                    selected = percentage,
                                    onClick = {
                                        percentage = true
                                        input = ""
                                    },
                                    label = { Text("Percentage estimate") },
                                )
                            }
                            OutlinedTextField(
                                input,
                                {
                                    useDevice = false
                                    input = it.take(12)
                                },
                                label = { Text(if (percentage) "Progress %" else "Current page") },
                                keyboardOptions =
                                    KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (percentage)
                                Text(
                                    "Estimated against ${edition.pages} pages${page?.let { " → page $it" }.orEmpty()}. EPUB pagination and printed pages differ.",
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
                                            "Use phone position · ${"%.1f".format(fraction * 100)}%"
                                        )
                                    }
                                    Text(
                                        "Saved on phone ${java.time.Instant.ofEpochMilli(phoneReading.updatedAt)}. Review before publishing.",
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
                                        Text("Use KOReader snapshot · ${"%.1f".format(percent)}%")
                                    }
                                    Text(
                                        "Snapshot fetched ${java.time.Instant.ofEpochMilli(snapshot.fetchedAt)}${if(snapshot.documentHash!=book.currentHash) "; from a previous EPUB revision" else ""}. Refresh reading data from Kindle if needed.",
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
                                Text("Review progress update")
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
            title = { Text("Choose Hardcover edition") },
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
                                    "${item.pages} pages",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(item.label.ifBlank { item.title })
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { chooseEdition = false }) { Text("Close") } },
        )
    if (confirmStatus)
        AlertDialog(
            onDismissRequest = { confirmStatus = false },
            title = { Text("Mark Currently Reading?") },
            text = {
                Text(
                    "Update ${book.title} on Hardcover. Prior completed reads, ratings, reviews and privacy are retained."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmStatus = false
                        model.currentReading(book)
                    },
                    enabled = !state.busy,
                ) {
                    Text("Update Hardcover")
                }
            },
            dismissButton = { TextButton(onClick = { confirmStatus = false }) { Text("Cancel") } },
        )
    val expected = reading
    if (confirm && expected != null && edition != null && page != null)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Publish reading progress?") },
            text = {
                Column {
                    Text(
                        "${book.title}\n$page of ${edition.pages} pages${if(percentage) " (estimated)" else ""}\n${edition.label}\nThis also marks the book Currently Reading."
                    )
                    if (expected.readId == null)
                        Row {
                            Checkbox(allowNew, { allowNew = it })
                            Text("Start a new reading session; keep completed read history")
                        }
                    if (expected.pages != null && page < expected.pages)
                        Row {
                            Checkbox(allowDecrease, { allowDecrease = it })
                            Text("Allow progress to decrease")
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
                    Text("Update Hardcover")
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
}
