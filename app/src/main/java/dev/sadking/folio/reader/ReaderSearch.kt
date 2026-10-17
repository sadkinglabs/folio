@file:OptIn(
    org.readium.r2.shared.ExperimentalReadiumApi::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package dev.sadking.folio.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.sadking.folio.R
import dev.sadking.folio.ui.FolioSpace
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.search.SearchIterator
import org.readium.r2.shared.publication.services.search.SearchService
import org.readium.r2.shared.publication.services.search.isSearchable
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.getOrElse

internal const val READER_SEARCH_SHEET_TAG = "reader-search-sheet"
internal const val READER_SEARCH_FIELD_TAG = "reader-search-field"

internal fun readerSearchHitTag(index: Int) = "reader-search-hit-$index"

/** The Readium decoration group for the search hit on the page. */
internal const val SEARCH_GROUP = "search"

/** At most this many matches are listed; "Showing the first 500 matches" says so. */
internal const val SEARCH_LIMIT = 500

/** A query shorter than this is not run. */
internal const val SEARCH_MIN_LENGTH = 2

/** Characters of context kept on each side of a match. */
private const val SNIPPET = 80

/** One match: where it is, its chapter, and the text around it. */
data class SearchHit(
    val locator: Locator,
    val chapter: String?,
    val before: String,
    val match: String,
    val after: String,
)

/** The search failed in Readium (a resource could not be read). */
internal class ReaderSearchFailed : Exception()

/**
 * In-book search through Readium's string search, which comes with the EPUB parser. PDFs (Pdfium
 * has no search service) and comics are not [supported]. Never logs the query.
 */
class ReaderSearch(private val publication: Publication) {
    val supported: Boolean
        get() = publication.isSearchable

    /**
     * Pages of hits as they arrive, each the full list so far, up to [limit]; cancellable. Case-
     * and accent-insensitive.
     */
    fun run(query: String, limit: Int = SEARCH_LIMIT): Flow<List<SearchHit>> = flow {
        val text = query.trim()
        if (text.isEmpty() || !supported) return@flow
        val iterator =
            publication.search(
                text,
                SearchService.Options(caseSensitive = false, diacriticSensitive = false),
            ) ?: return@flow
        try {
            emitAll(searchPages(iterator, limit))
        } finally {
            iterator.close()
        }
    }
}

/**
 * Reads [iterator] page by page (Readium gives one page per chapter with matches) until it ends or
 * [limit] hits are collected, emitting the hits so far after each page.
 */
internal fun searchPages(iterator: SearchIterator, limit: Int): Flow<List<SearchHit>> = flow {
    val hits = mutableListOf<SearchHit>()
    while (hits.size < limit) {
        val page = iterator.next().getOrElse { throw ReaderSearchFailed() } ?: break
        page.locators.forEach { locator ->
            if (hits.size < limit) searchHit(locator)?.let(hits::add)
        }
        emit(hits.toList())
    }
}

private val SPACES = Regex("\\s+")

/** A hit from a search result, with its text on one line; null when it has no matched text. */
internal fun searchHit(locator: Locator): SearchHit? {
    val match = locator.text.highlight?.replace(SPACES, " ")?.trim().orEmpty()
    if (match.isEmpty()) return null
    val before = locator.text.before.orEmpty().replace(SPACES, " ").trimStart()
    val after = locator.text.after.orEmpty().replace(SPACES, " ").trimEnd()
    return SearchHit(
        locator,
        locator.title?.trim()?.takeIf { it.isNotEmpty() },
        if (before.length > SNIPPET) "…" + before.takeLast(SNIPPET).trimStart() else before,
        match,
        if (after.length > SNIPPET) after.take(SNIPPET).trimEnd() + "…" else after,
    )
}

/** Where a search stands. */
internal enum class SearchStatus {
    IDLE,
    RUNNING,
    DONE,
    FAILED,
}

/** The last search: what was asked, what was found so far, and whether it is still running. */
internal data class SearchResults(
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
    val status: SearchStatus = SearchStatus.IDLE,
) {
    val capped: Boolean
        get() = hits.size >= SEARCH_LIMIT
}

/**
 * The search sheet: a field, a line with the count, and the matches with their chapter and the text
 * around them. Typing runs the search after a short pause; the keyboard's search key runs it at
 * once. A tap on a match goes there.
 */
@Composable
internal fun ReaderSearchSheet(
    text: String,
    results: SearchResults,
    onText: (String) -> Unit,
    onSearch: (String) -> Unit,
    onHit: (SearchHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    // Ask for the keyboard only when there is nothing to show yet.
    LaunchedEffect(Unit) { if (text.isEmpty()) runCatching { focus.requestFocus() } }
    LaunchedEffect(text) {
        if (text.trim().length >= SEARCH_MIN_LENGTH && text.trim() != results.query) {
            delay(SEARCH_TYPING_PAUSE_MS)
            onSearch(text)
        }
    }
    Column(modifier.fillMaxWidth().testTag(READER_SEARCH_SHEET_TAG)) {
        Text(
            stringResource(R.string.rd6_search_title),
            Modifier.padding(horizontal = FolioSpace.section).semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        val fieldLabel = stringResource(R.string.rd6_search_field)
        OutlinedTextField(
            text,
            onText,
            Modifier.fillMaxWidth()
                .padding(horizontal = FolioSpace.section, vertical = FolioSpace.small)
                .focusRequester(focus)
                .testTag(READER_SEARCH_FIELD_TAG),
            label = { Text(fieldLabel) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon =
                if (text.isNotEmpty()) {
                    {
                        IconButton(onClick = { onText("") }) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.rd6_search_clear))
                        }
                    }
                } else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions =
                KeyboardActions(
                    onSearch = {
                        keyboard?.hide()
                        onSearch(text)
                    }
                ),
        )
        SearchStatusLine(results)
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 560.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            itemsIndexed(results.hits) { index, hit -> SearchHitRow(hit, index) { onHit(hit) } }
        }
    }
}

/** How long typing must pause before the search runs. */
private const val SEARCH_TYPING_PAUSE_MS = 450L

@Composable
private fun SearchStatusLine(results: SearchResults) {
    val line =
        when (results.status) {
            SearchStatus.IDLE -> null
            SearchStatus.RUNNING ->
                if (results.hits.isEmpty()) stringResource(R.string.rd6_search_running)
                else
                    pluralStringResource(
                        R.plurals.rd6_search_count,
                        results.hits.size,
                        results.hits.size,
                    )
            SearchStatus.FAILED -> stringResource(R.string.rd6_search_failed)
            SearchStatus.DONE ->
                when {
                    results.hits.isEmpty() ->
                        stringResource(R.string.rd6_search_none, results.query)
                    results.capped -> stringResource(R.string.rd6_search_capped, SEARCH_LIMIT)
                    else ->
                        pluralStringResource(
                            R.plurals.rd6_search_count,
                            results.hits.size,
                            results.hits.size,
                        )
                }
        }
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 32.dp)
            .padding(horizontal = FolioSpace.section)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
    ) {
        if (line != null)
            Text(
                line,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (results.status == SearchStatus.FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        if (results.status == SearchStatus.RUNNING) LinearProgressIndicator(Modifier.width(48.dp))
    }
}

/** One match: its chapter, then the text around it with the match in bold. */
@Composable
private fun SearchHitRow(hit: SearchHit, index: Int, onClick: () -> Unit) {
    val snippet = buildAnnotatedString {
        append(hit.before)
        withStyle(
            SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        ) {
            append(hit.match)
        }
        append(hit.after)
    }
    Column(
        Modifier.fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = FolioSpace.section, vertical = 10.dp)
            .testTag(readerSearchHitTag(index)),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            hit.chapter ?: stringResource(R.string.contents_chapter_fallback),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            snippet,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
