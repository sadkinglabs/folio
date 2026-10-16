@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package dev.sadking.folio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sadking.folio.DiscoveryViewModel
import dev.sadking.folio.R
import dev.sadking.folio.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

internal const val HARDCOVER_SHEET_TAG = "hardcover-sheet"
internal const val HARDCOVER_CONNECT_TILE_TAG = "hardcover-connect-tile"
internal const val HARDCOVER_SEND_TAG = "hardcover-send"
internal const val HARDCOVER_POSITION_TAG = "hardcover-position"

/** A page or percentage as typed in the position field: no trailing zeros, a dot for decimals. */
internal fun percentInput(percent: Double): String {
    val text = String.format(java.util.Locale.ROOT, "%.1f", percent)
    return text.removeSuffix(".0")
}

/**
 * The Hardcover sheet for one book: connect, link, then a page slider, the positions other devices
 * hold, and one Send button. The queries behind it are unchanged; this is only how they are shown.
 */
@Composable
fun HardcoverReadingSheet(
    model: DiscoveryViewModel,
    book: Book,
    snapshot: ReadingSnapshot?,
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
    var confirmStatus by remember { mutableStateOf(false) }
    var send by remember { mutableStateOf<Triple<HardcoverEdition, Int, Boolean>?>(null) }
    var allowNew by remember { mutableStateOf(false) }
    var allowDecrease by remember { mutableStateOf(false) }
    var startedOn by remember { mutableStateOf(LocalDate.now()) }
    var finishing by remember { mutableStateOf(false) }
    var finishedOn by remember { mutableStateOf(LocalDate.now()) }
    val reports = linkReports(book)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = FolioSpace.sheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Box {
            HardcoverReadingContent(
                book = book,
                username = username,
                busy = state.busy,
                error = state.message.takeIf { state.failed },
                reading = reading,
                editions = editions,
                matches = matches,
                snapshot = snapshot,
                phonePercent =
                    phoneReading?.takeIf { it.revisionHash == book.currentHash }?.percent,
                reports = reports,
                onConnect = { account = true },
                onRefresh = { model.openReading(book) },
                onSearch = model::searchHardcoverText,
                onLink = model::link,
                onMarkReading = { confirmStatus = true },
                onMarkFinished = {
                    finishedOn = LocalDate.now()
                    finishing = true
                },
                onSend = { edition, page, estimated ->
                    allowNew = false
                    allowDecrease = false
                    startedOn = LocalDate.now()
                    send = Triple(edition, page, estimated)
                },
            )
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
    }
    if (account)
        HardcoverAccountSheet(model, onConnected = { model.openReading(book) }) { account = false }
    if (confirmStatus)
        AlertDialog(
            onDismissRequest = { confirmStatus = false },
            title = { Text(stringResource(R.string.hardcover_mark_reading_title)) },
            text = { Text(stringResource(R.string.hardcover_mark_reading_body, book.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmStatus = false
                        model.currentReading(book)
                    },
                    enabled = !state.busy,
                ) {
                    Text(stringResource(R.string.hardcover_mark))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmStatus = false }) {
                    Text(stringResource(R.string.shell_cancel))
                }
            },
        )
    if (finishing)
        FinishDialog(
            book,
            reading,
            finishedOn,
            busy = state.busy,
            onDate = { finishedOn = it },
            onFinish = {
                finishing = false
                model.finishReading(book, finishedOn, it)
            },
            onDismiss = { finishing = false },
        )
    val expected = reading
    val pending = send
    if (pending != null && expected != null) {
        val (edition, page, estimated) = pending
        AlertDialog(
            onDismissRequest = { send = null },
            title = {
                Text(stringResource(R.string.hardcover_send_title, page, edition.pages))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(FolioSpace.small)) {
                    Text(
                        listOfNotNull(
                                edition.label.ifBlank { null },
                                if (estimated) stringResource(R.string.hardcover_send_estimated)
                                else null,
                                stringResource(R.string.hardcover_send_marks_reading),
                            )
                            .joinToString(" · ")
                    )
                    if (expected.readId == null) {
                        ConfirmOption(stringResource(R.string.hardcover_start_new_read), allowNew) {
                            allowNew = it
                        }
                        DateField(
                            stringResource(R.string.hardcover_started_on),
                            startedOn,
                            enabled = allowNew,
                        ) {
                            startedOn = it
                        }
                    }
                    if (expected.pages != null && page < expected.pages)
                        ConfirmOption(
                            stringResource(R.string.hardcover_allow_decrease),
                            allowDecrease,
                        ) {
                            allowDecrease = it
                        }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        send = null
                        model.updateReading(
                            book,
                            edition,
                            page,
                            expected,
                            allowNew,
                            allowDecrease,
                            startedOn = startedOn.takeIf { expected.readId == null },
                        )
                    },
                    enabled =
                        !state.busy &&
                            (expected.readId != null || allowNew) &&
                            (expected.pages == null || page >= expected.pages || allowDecrease),
                ) {
                    Text(stringResource(R.string.hardcover_send))
                }
            },
            dismissButton = {
                TextButton(onClick = { send = null }) {
                    Text(stringResource(R.string.shell_cancel))
                }
            },
        )
    }
}

@Composable
internal fun FinishDialog(
    book: Book,
    reading: HardcoverReading?,
    finishedOn: LocalDate,
    busy: Boolean,
    onDate: (LocalDate) -> Unit,
    onFinish: (HardcoverReading) -> Unit,
    onDismiss: () -> Unit,
) {
    val started = reading?.startedOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hardcover_mark_finished_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FolioSpace.item)) {
                Text(stringResource(R.string.hardcover_mark_finished_body, book.title))
                DateField(
                    stringResource(R.string.hardcover_finished_on),
                    finishedOn,
                    earliest = started,
                    onDate = onDate,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { reading?.let(onFinish) },
                enabled = !busy && reading?.readId != null,
            ) {
                Text(stringResource(R.string.hardcover_mark))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.shell_cancel)) }
        },
    )
}

/**
 * A date shown as a tappable field that opens Material's date picker. Days after today, or before
 * [earliest], cannot be picked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateField(
    label: String,
    date: LocalDate,
    enabled: Boolean = true,
    earliest: LocalDate? = null,
    onDate: (LocalDate) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val shown =
        remember(date, locale) {
            date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        }
    OutlinedButton(
        onClick = { picking = true },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag(HARDCOVER_DATE_TAG),
    ) {
        Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(18.dp))
        Spacer(Modifier.width(FolioSpace.small))
        Text("$label · $shown", Modifier.weight(1f))
    }
    if (picking) {
        val today = LocalDate.now()
        val state =
            rememberDatePickerState(
                initialSelectedDateMillis = date.utcMillis(),
                selectableDates =
                    object : SelectableDates {
                        override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                            val day = utcDate(utcTimeMillis)
                            return !day.isAfter(today) &&
                                (earliest == null || !day.isBefore(earliest))
                        }

                        override fun isSelectableYear(year: Int) = year <= today.year
                    },
            )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { onDate(utcDate(it)) }
                        picking = false
                    },
                    enabled = state.selectedDateMillis != null,
                ) {
                    Text(stringResource(R.string.shell_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { picking = false }) {
                    Text(stringResource(R.string.shell_cancel))
                }
            },
        ) {
            DatePicker(state)
        }
    }
}

/** Material's date picker counts days in UTC milliseconds. */
private fun LocalDate.utcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun utcDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

internal const val HARDCOVER_DATE_TAG = "hardcover-date"
internal const val HARDCOVER_FINISH_TAG = "hardcover-finish"

@Composable
private fun ConfirmOption(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleableCompat(checked, true, onChecked),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, null)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The sheet's content, free of the view model so it can be previewed in every state: not connected,
 * not yet linked, and linked with an edition.
 */
@Composable
internal fun HardcoverReadingContent(
    book: Book,
    username: String,
    busy: Boolean,
    error: String?,
    reading: HardcoverReading?,
    editions: List<HardcoverEdition>,
    matches: List<HardcoverMatch>,
    snapshot: ReadingSnapshot?,
    phonePercent: Double?,
    reports: List<KosyncReport>,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onSearch: (String) -> Unit,
    onLink: (HardcoverMatch) -> Unit,
    onMarkReading: () -> Unit,
    onSend: (HardcoverEdition, Int, Boolean) -> Unit,
    onMarkFinished: () -> Unit = {},
) {
    val connected = username.isNotBlank()
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(start = FolioSpace.section, end = FolioSpace.section, bottom = 32.dp)
            .testTag(HARDCOVER_SHEET_TAG),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.item),
    ) {
        SheetTitle(stringResource(R.string.hardcover_title)) {
            if (connected)
                IconButton(onClick = onRefresh, enabled = !busy) {
                    Icon(Icons.Outlined.Refresh, stringResource(R.string.hardcover_reload))
                }
        }
        // The status pill sits beside the title, or under it when the text is large.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                book.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (reading != null) StatusPill(hardcoverStatusLabel(reading.status))
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error != null)
            Text(
                error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        when {
            !connected ->
                TonalTile(
                    stringResource(R.string.hardcover_connect),
                    Icons.Outlined.Key,
                    Modifier.fillMaxWidth().testTag(HARDCOVER_CONNECT_TILE_TAG),
                    enabled = !busy,
                    onClick = onConnect,
                )
            reading == null -> LinkStep(book, matches, busy, onSearch, onLink)
            else ->
                PositionStep(
                    book,
                    reading,
                    editions,
                    snapshot,
                    phonePercent,
                    reports,
                    busy,
                    onMarkReading,
                    onMarkFinished,
                    onSend,
                )
        }
    }
}

/** Not linked yet: a prefilled search and the matches as rows with a Link button. */
@Composable
private fun LinkStep(
    book: Book,
    matches: List<HardcoverMatch>,
    busy: Boolean,
    onSearch: (String) -> Unit,
    onLink: (HardcoverMatch) -> Unit,
) {
    var search by
        rememberSaveable(book.id) { mutableStateOf("${book.title} ${book.authorLabel}".take(300)) }
    OutlinedTextField(
        search,
        { search = it.take(300) },
        label = { Text(stringResource(R.string.hardcover_title_and_author)) },
        singleLine = true,
        trailingIcon = {
            IconButton(onClick = { onSearch(search) }, enabled = !busy && search.isNotBlank()) {
                Icon(Icons.Outlined.Search, stringResource(R.string.hardcover_find))
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { if (search.isNotBlank()) onSearch(search) }),
        modifier = Modifier.fillMaxWidth(),
    )
    matches.forEach { match ->
        ResultRow(match.title, match.authors.joinToString(", ")) {
            FilledTonalButton(onClick = { onLink(match) }, enabled = !busy) {
                Text(stringResource(R.string.hardcover_link_match))
            }
        }
    }
}

/** Linked: status, edition, the page slider with the positions other devices hold, and Send. */
@Composable
private fun PositionStep(
    book: Book,
    reading: HardcoverReading,
    editions: List<HardcoverEdition>,
    snapshot: ReadingSnapshot?,
    phonePercent: Double?,
    reports: List<KosyncReport>,
    busy: Boolean,
    onMarkReading: () -> Unit,
    onMarkFinished: () -> Unit,
    onSend: (HardcoverEdition, Int, Boolean) -> Unit,
) {
    var editionId by rememberSaveable(book.id) { mutableStateOf<Int?>(null) }
    var input by rememberSaveable(book.id) { mutableStateOf("") }
    var percentage by rememberSaveable(book.id) { mutableStateOf(false) }
    // The e-reader's position fills the field until the reader types something else.
    var useDevice by rememberSaveable(book.id) { mutableStateOf(true) }
    val paged = editions.filter { it.pages > 0 }
    val edition =
        paged.firstOrNull { it.id == editionId }
            // Without a linked edition the owner picks one: progress sent against the wrong
            // edition would land on the wrong page count on Hardcover.
            ?: paged.firstOrNull { it.id == reading.editionId }
    val page =
        if (percentage)
            input
                .toDoubleOrNull()
                ?.takeIf { it.isFinite() && it in 0.0..100.0 }
                ?.let { p -> edition?.let { estimatedHardcoverPage(p, it.pages) } }
        else input.toIntOrNull()?.takeIf { it in 0..(edition?.pages ?: -1) }
    val devicePercent = snapshot?.percent?.takeIf { it.isFinite() && it in 0.0..1.0 }
    LaunchedEffect(snapshot?.fetchedAt) {
        if (useDevice && devicePercent != null) {
            percentage = true
            input = percentInput(koreaderPercent(devicePercent))
        }
    }
    if (reading.status != 2)
        AssistChip(
            onClick = onMarkReading,
            enabled = !busy,
            label = { Text(stringResource(R.string.hardcover_mark_reading)) },
            leadingIcon = { Icon(Icons.Outlined.AutoStories, null, Modifier.size(18.dp)) },
        )
    else if (reading.readId != null)
        AssistChip(
            onClick = onMarkFinished,
            enabled = !busy,
            label = { Text(stringResource(R.string.hardcover_mark_finished)) },
            leadingIcon = { Icon(Icons.Outlined.DoneAll, null, Modifier.size(18.dp)) },
            modifier = Modifier.testTag(HARDCOVER_FINISH_TAG),
        )
    EditionRow(paged, edition, enabled = !busy) { editionId = it.id }
    if (edition != null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FolioSpace.gap),
        ) {
            OutlinedTextField(
                input,
                {
                    useDevice = false
                    input = it.take(12)
                },
                textStyle = MaterialTheme.typography.headlineSmall,
                suffix = {
                    Text(
                        if (percentage) "%"
                        else stringResource(R.string.hardcover_of_pages, edition.pages),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                isError = input.isNotBlank() && page == null,
                modifier = Modifier.weight(1f).testTag(HARDCOVER_POSITION_TAG),
            )
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = !percentage,
                    onClick = {
                        if (percentage) {
                            percentage = false
                            input = page?.toString() ?: ""
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) {
                    Text(stringResource(R.string.hardcover_mode_pages))
                }
                SegmentedButton(
                    selected = percentage,
                    onClick = {
                        if (!percentage) {
                            percentage = true
                            input = page?.let { percentInput(it * 100.0 / edition.pages) } ?: ""
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) {
                    Text("%")
                }
            }
        }
        val max = if (percentage) 100f else edition.pages.toFloat()
        val position =
            (if (percentage) input.toFloatOrNull() ?: 0f else (page ?: 0).toFloat()).coerceIn(
                0f,
                max,
            )
        Slider(
            value = position,
            onValueChange = {
                useDevice = false
                input = it.roundToInt().toString()
            },
            valueRange = 0f..max,
            enabled = !busy,
        )
        if (percentage)
            Text(
                page?.let { stringResource(R.string.hardcover_about_page, it, edition.pages) }
                    ?: stringResource(R.string.hardcover_percent_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            phonePercent?.let { fraction ->
                PositionChip(
                    stringResource(R.string.hardcover_use_phone_position, (fraction * 100).toInt()),
                    Icons.Outlined.Smartphone,
                    enabled = !busy,
                ) {
                    useDevice = false
                    percentage = true
                    input = percentInput(fraction * 100)
                }
            }
            devicePercent?.let { fraction ->
                PositionChip(
                    stringResource(
                        R.string.hardcover_use_device_position,
                        koreaderPercent(fraction).toInt(),
                    ),
                    Icons.Outlined.TabletAndroid,
                    enabled = !busy,
                ) {
                    percentage = true
                    input = percentInput(koreaderPercent(fraction))
                }
            }
            // Positions e-readers sent through Folio Link for this exact file.
            reports.forEach { report ->
                val percent = koreaderPercent(report.percentage)
                PositionChip(
                    stringResource(
                        R.string.hardcover_use_link_position,
                        report.device,
                        percent.toInt(),
                    ),
                    Icons.Outlined.TabletAndroid,
                    enabled = !busy,
                ) {
                    useDevice = false
                    percentage = true
                    input = percentInput(percent)
                }
            }
        }
        if (snapshot != null && snapshot.documentHash != book.currentHash)
            Text(
                stringResource(R.string.hardcover_device_fetched_old),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        Button(
            onClick = { page?.let { onSend(edition, it, percentage) } },
            enabled = !busy && page != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(HARDCOVER_SEND_TAG),
        ) {
            Text(stringResource(R.string.hardcover_send_to_hardcover))
        }
    }
}

@Composable
private fun PositionChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) },
    )
}

/** The edition row: the chosen edition's pages and label, a menu of the ones with a page count. */
@Composable
private fun EditionRow(
    editions: List<HardcoverEdition>,
    edition: HardcoverEdition?,
    enabled: Boolean,
    onChoose: (HardcoverEdition) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(stringResource(R.string.hardcover_edition)) },
            supportingContent = {
                Text(
                    edition?.let {
                        pluralStringResource(
                            R.plurals.hardcover_edition_option,
                            it.pages,
                            it.pages,
                            it.label.ifBlank { it.title },
                        )
                    }
                        ?: stringResource(
                            if (editions.isEmpty()) R.string.hardcover_no_page_edition
                            else R.string.hardcover_choose_edition
                        ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = {
                Icon(Icons.Outlined.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
            },
            trailingContent = {
                if (editions.size > 1 || (edition == null && editions.isNotEmpty()))
                    Icon(
                        Icons.Outlined.ExpandMore,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier =
                Modifier.fillMaxWidth()
                    .then(
                        if (editions.size > 1 || (edition == null && editions.isNotEmpty()))
                            Modifier.clickable(enabled = enabled, role = Role.DropdownList) {
                                open = true
                            }
                        else Modifier
                    ),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            editions.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Text(
                            pluralStringResource(
                                R.plurals.hardcover_edition_option,
                                item.pages,
                                item.pages,
                                item.label.ifBlank { item.title },
                            )
                        )
                    },
                    onClick = {
                        onChoose(item)
                        open = false
                    },
                    trailingIcon = { if (item.id == edition?.id) Icon(Icons.Outlined.Check, null) },
                )
            }
        }
    }
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
