@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.acquire

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sadking.folio.R
import dev.sadking.folio.ui.FolioSpace

internal const val FIND_SEARCH_TAG = "acquire-search"
internal const val BROWSER_WEB_TAG = "acquire-web"
internal const val OUTCOME_CARD_TAG = "acquire-outcome"

/** On a tablet, forms and lists stay a readable width, centred. */
private val MAX_CONTENT = 640.dp
private val MAX_CARD = 560.dp

/** A title marked as a heading for TalkBack. */
@Composable
private fun Heading(text: String) {
    Text(
        text,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.semantics { heading() },
    )
}

/** A small-caps eyebrow over a group of rows, marked as a heading. */
@Composable
private fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            modifier
                .padding(start = FolioSpace.page + FolioSpace.item, bottom = FolioSpace.small)
                .semantics { heading() },
    )
}

/** One panel of rows, like a settings group. */
@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        Modifier.padding(horizontal = FolioSpace.page).fillMaxWidth(),
        shape = FolioSpace.panelShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(vertical = 4.dp), content = content)
    }
}

@Composable
private fun CloseButton(onClose: () -> Unit) {
    IconButton(onClick = onClose) {
        Icon(Icons.Outlined.Close, stringResource(R.string.acq_close))
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.acq_back))
    }
}

/**
 * The start page: a search box over the visible sources. Tapping a source searches it for the words
 * typed, or opens its start page when there are none or it has no search.
 */
@Composable
internal fun FindStartScreen(
    query: String,
    onQuery: (String) -> Unit,
    sources: List<BookSource>,
    onOpen: (BookSource) -> Unit,
    onManage: () -> Unit,
    onClose: () -> Unit,
) {
    val searchable = sources.firstOrNull { it.searchUrl != null }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Heading(stringResource(R.string.acq_find_title)) },
                navigationIcon = { CloseButton(onClose) },
                actions = {
                    IconButton(onClick = onManage) {
                        Icon(Icons.Outlined.Tune, stringResource(R.string.acq_manage_sources))
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = MAX_CONTENT)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.gap),
        ) {
            OutlinedTextField(
                query,
                onQuery,
                label = { Text(stringResource(R.string.acq_search_label)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions =
                    KeyboardActions(onSearch = { if (searchable != null) onOpen(searchable) }),
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(horizontal = FolioSpace.page)
                        .testTag(FIND_SEARCH_TAG),
            )
            if (sources.isEmpty()) {
                Text(
                    stringResource(R.string.acq_find_no_sites),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = FolioSpace.page),
                )
                FilledTonalButton(
                    onClick = onManage,
                    modifier = Modifier.padding(horizontal = FolioSpace.page),
                ) {
                    Text(stringResource(R.string.acq_manage_sources))
                }
            } else {
                Spacer(Modifier.height(FolioSpace.small))
                Eyebrow(stringResource(R.string.acq_find_sites))
                Panel {
                    sources.forEach { source ->
                        val searches = query.isNotBlank() && source.searchUrl != null
                        SourceRow(
                            source,
                            icon = if (searches) Icons.Outlined.Search else Icons.Outlined.Public,
                            onClick = { onOpen(source) },
                            trailing = {
                                Icon(
                                    Icons.Outlined.ChevronRight,
                                    null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
                Text(
                    stringResource(R.string.acq_find_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier.padding(horizontal = FolioSpace.page + FolioSpace.item)
                            .padding(top = FolioSpace.small, bottom = FolioSpace.section),
                )
            }
        }
    }
}

/** A source: name over host. The row opens it; [trailing] holds its own controls. */
@Composable
private fun SourceRow(
    source: BookSource,
    icon: ImageVector,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                source.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                source.host,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
    )
}

/**
 * Book sources: built-ins with a switch to show or hide each, the owner's sites with edit and
 * remove, and "Add a site". Tapping a row browses the site.
 */
@Composable
internal fun SourcesScreen(
    sources: List<BookSource>,
    onOpen: (BookSource) -> Unit,
    onHidden: (BookSource, Boolean) -> Unit,
    onEdit: (BookSource) -> Unit,
    onRemove: (BookSource) -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
) {
    var removing by remember { mutableStateOf<BookSource?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Heading(stringResource(R.string.acq_sources_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = MAX_CONTENT)
                .fillMaxWidth()
                .padding(bottom = FolioSpace.section),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
        ) {
            Eyebrow(
                stringResource(R.string.acq_sources_built_in),
                Modifier.padding(top = FolioSpace.small),
            )
            Panel {
                sources
                    .filter { it.builtIn }
                    .forEach { source ->
                        val label = stringResource(R.string.acq_shown)
                        SourceRow(
                            source,
                            Icons.Outlined.Public,
                            onClick = { onOpen(source) },
                            trailing = {
                                Switch(
                                    checked = !source.hidden,
                                    onCheckedChange = { onHidden(source, !it) },
                                    modifier =
                                        Modifier.semantics {
                                            contentDescription = "${source.name}: $label"
                                        },
                                )
                            },
                        )
                    }
            }
            Spacer(Modifier.height(FolioSpace.small))
            Eyebrow(stringResource(R.string.acq_sources_yours))
            val mine = sources.filter { !it.builtIn }
            if (mine.isEmpty())
                Text(
                    stringResource(R.string.acq_sources_yours_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = FolioSpace.page + FolioSpace.item),
                )
            else
                Panel {
                    mine.forEach { source ->
                        SourceRow(
                            source,
                            Icons.Outlined.Language,
                            onClick = { onOpen(source) },
                            trailing = {
                                Row {
                                    IconButton(onClick = { onEdit(source) }) {
                                        Icon(
                                            Icons.Outlined.Edit,
                                            stringResource(R.string.acq_edit_site, source.name),
                                        )
                                    }
                                    IconButton(onClick = { removing = source }) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            stringResource(R.string.acq_remove_site, source.name),
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            FilledTonalButton(
                onClick = onAdd,
                modifier =
                    Modifier.padding(horizontal = FolioSpace.page, vertical = FolioSpace.small),
            ) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(FolioSpace.small))
                Text(stringResource(R.string.acq_add_site))
            }
        }
    }
    removing?.let { source ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.acq_remove_confirm, source.name)) },
            text = { Text(stringResource(R.string.acq_remove_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        removing = null
                        onRemove(source)
                    }
                ) {
                    Text(stringResource(R.string.acq_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) {
                    Text(stringResource(R.string.acq_cancel))
                }
            },
        )
    }
}

/**
 * Adds or edits one of the owner's sites: name, start page, an optional search address with
 * `{query}`, and whether scripts run. A problem shows under its field after Save.
 */
@Composable
internal fun SourceEditor(
    initial: BookSource?,
    onSave: (BookSource) -> Unit,
    onCancel: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var start by rememberSaveable { mutableStateOf(initial?.startUrl ?: "https://") }
    var search by rememberSaveable { mutableStateOf(initial?.searchUrl.orEmpty()) }
    var scripts by rememberSaveable { mutableStateOf(initial?.javaScript ?: true) }
    var tried by rememberSaveable { mutableStateOf(false) }
    val problem =
        if (tried) BookSources.problem(name, start, search.takeIf { it.isNotBlank() }) else null
    val startProblem = problem == SourceProblem.NOT_HTTPS && !BookSources.isHttps(start)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Heading(
                        if (initial == null) stringResource(R.string.acq_new_site)
                        else stringResource(R.string.acq_edit_site, initial.name)
                    )
                },
                navigationIcon = { CloseButton(onCancel) },
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = MAX_CONTENT)
                .fillMaxWidth()
                .padding(horizontal = FolioSpace.page)
                .padding(bottom = FolioSpace.section),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.gap),
        ) {
            OutlinedTextField(
                name,
                { name = it },
                label = { Text(stringResource(R.string.acq_field_name)) },
                singleLine = true,
                isError = problem == SourceProblem.NAME_MISSING,
                supportingText =
                    if (problem == SourceProblem.NAME_MISSING) {
                        { Text(stringResource(R.string.acq_problem_name)) }
                    } else null,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                start,
                { start = it },
                label = { Text(stringResource(R.string.acq_field_start)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = startProblem,
                supportingText =
                    if (startProblem) {
                        { Text(stringResource(R.string.acq_problem_https)) }
                    } else null,
                modifier = Modifier.fillMaxWidth(),
            )
            val searchProblem =
                problem == SourceProblem.QUERY_MISSING ||
                    (problem == SourceProblem.NOT_HTTPS && !startProblem)
            OutlinedTextField(
                search,
                { search = it },
                label = { Text(stringResource(R.string.acq_field_search)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = searchProblem,
                supportingText = {
                    Text(
                        stringResource(
                            when {
                                problem == SourceProblem.QUERY_MISSING -> R.string.acq_problem_query
                                searchProblem -> R.string.acq_problem_https
                                else -> R.string.acq_field_search_hint
                            }
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.acq_field_scripts)) },
                supportingContent = { Text(stringResource(R.string.acq_field_scripts_hint)) },
                trailingContent = { Switch(scripts, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier =
                    Modifier.fillMaxWidth().clickable(role = Role.Switch) { scripts = !scripts },
            )
            Button(
                onClick = {
                    tried = true
                    val search = search.trim().takeIf { it.isNotEmpty() }
                    if (BookSources.problem(name, start, search) == null)
                        onSave(
                            BookSource(
                                id = initial?.id ?: BookSources.newId(),
                                name = name.trim(),
                                startUrl = start.trim(),
                                searchUrl = search,
                                javaScript = scripts,
                                hidden = initial?.hidden ?: false,
                            )
                        )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.acq_save))
            }
        }
    }
}

/**
 * The browser's frame: back, the site's name over the page's host, "Search another site" (or "Open
 * another site" without search words) and Close; a progress line while a page loads; the page in
 * [page]; the download card over its bottom edge.
 */
@Composable
internal fun BrowserFrame(
    source: BookSource,
    host: String,
    loading: Int,
    others: List<BookSource>,
    searching: Boolean,
    download: DownloadState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onOther: (BookSource) -> Unit,
    onStop: () -> Unit,
    onDone: () -> Unit,
    onKeepBrowsing: () -> Unit,
    page: @Composable (Modifier) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Heading(source.name)
                            if (host.isNotEmpty())
                                Text(
                                    host,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                        }
                    },
                    navigationIcon = { BackButton(onBack) },
                    actions = {
                        if (others.isNotEmpty())
                            Box {
                                IconButton(onClick = { menu = true }) {
                                    Icon(
                                        Icons.Outlined.TravelExplore,
                                        stringResource(
                                            if (searching) R.string.acq_search_other
                                            else R.string.acq_open_other
                                        ),
                                    )
                                }
                                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                    others.forEach { other ->
                                        DropdownMenuItem(
                                            text = { Text(other.name) },
                                            onClick = {
                                                menu = false
                                                onOther(other)
                                            },
                                        )
                                    }
                                }
                            }
                        CloseButton(onClose)
                    },
                )
                if (loading in 0..99)
                    LinearProgressIndicator(
                        progress = { loading / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            page(Modifier.fillMaxSize().testTag(BROWSER_WEB_TAG))
            DownloadCard(
                download,
                onStop,
                onDone,
                onKeepBrowsing,
                Modifier.align(Alignment.BottomCenter)
                    .padding(FolioSpace.gap)
                    .widthIn(max = MAX_CARD),
            )
        }
    }
}

/**
 * The download in progress with Stop, or its outcome with Done (closes the browser) and Keep
 * browsing. Nothing while idle.
 */
@Composable
internal fun DownloadCard(
    state: DownloadState,
    onStop: () -> Unit,
    onDone: () -> Unit,
    onKeepBrowsing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    when (state) {
        DownloadState.Idle -> Unit
        is DownloadState.Running ->
            Surface(
                modifier.fillMaxWidth(),
                shape = FolioSpace.panelShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shadowElevation = 6.dp,
            ) {
                Row(
                    Modifier.padding(start = FolioSpace.item, end = FolioSpace.small)
                        .padding(vertical = FolioSpace.gap),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FolioSpace.gap),
                ) {
                    Icon(Icons.Outlined.Download, null, tint = MaterialTheme.colorScheme.primary)
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            stringResource(R.string.acq_downloading, state.name),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val total = state.total
                        if (total != null && total > 0)
                            LinearProgressIndicator(
                                progress = { (state.bytes.toFloat() / total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            if (total != null && total > 0)
                                stringResource(
                                    R.string.acq_progress,
                                    Formatter.formatShortFileSize(context, state.bytes),
                                    Formatter.formatShortFileSize(context, total),
                                )
                            else Formatter.formatShortFileSize(context, state.bytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onStop) { Text(stringResource(R.string.acq_stop)) }
                }
            }
        is DownloadState.Finished -> {
            val outcome = state.outcome
            val ok = !outcome.failed
            val res = LocalResources.current
            Surface(
                modifier.fillMaxWidth().testTag(OUTCOME_CARD_TAG),
                shape = FolioSpace.panelShape,
                color =
                    if (ok) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer,
                contentColor =
                    if (ok) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onErrorContainer,
                shadowElevation = 6.dp,
            ) {
                Column(
                    Modifier.padding(FolioSpace.item),
                    verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FolioSpace.gap),
                    ) {
                        Icon(
                            when (outcome) {
                                is AcquireOutcome.Saved,
                                is AcquireOutcome.Already -> Icons.Outlined.CheckCircle
                                AcquireOutcome.Protected -> Icons.Outlined.Lock
                                else -> Icons.Outlined.ErrorOutline
                            },
                            null,
                        )
                        Text(
                            outcome.headline(res),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    outcome.details(res, state.ereader).forEach {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.spacedBy(FolioSpace.small, Alignment.End),
                    ) {
                        TextButton(
                            onClick = onKeepBrowsing,
                            colors =
                                ButtonDefaults.textButtonColors(
                                    contentColor = LocalContentColor.current
                                ),
                        ) {
                            Text(stringResource(R.string.acq_keep_browsing))
                        }
                        Button(onClick = onDone) { Text(stringResource(R.string.acq_done)) }
                    }
                }
            }
        }
    }
}
