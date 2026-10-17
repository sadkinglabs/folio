package dev.sadking.folio.reader.dictionary

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sadking.folio.R
import dev.sadking.folio.core.dictionary.DictionaryEntry
import dev.sadking.folio.core.dictionary.DictionaryPackage
import dev.sadking.folio.core.dictionary.PartOfSpeech
import dev.sadking.folio.core.dictionary.WordNetText
import dev.sadking.folio.reader.megabytes
import dev.sadking.folio.ui.FolioSpace

internal const val DEFINE_SHEET_TAG = "reader-define-sheet"
internal const val DEFINE_DOWNLOAD_TAG = "reader-define-download"
internal const val DEFINE_ENTRY_TAG = "reader-define-entry"

/** How much of a long selection the sheet repeats back. */
private const val SHOWN_SELECTION = 40

/**
 * Define: the selected word's entries from the offline dictionary. Each headword, then for each
 * part of speech its numbered senses with the definition, examples in italics and "Also: …"
 * synonyms. Before the dictionary is here, it offers the one-time download with its size, and shows
 * its progress. [entries] is null while the lookup runs.
 */
@Composable
internal fun DefineSheet(
    selection: String,
    state: DictionaryState,
    entries: List<DictionaryEntry>?,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown = shownSelection(selection)
    LazyColumn(
        modifier.fillMaxWidth().heightIn(max = 640.dp).testTag(DEFINE_SHEET_TAG),
        contentPadding =
            PaddingValues(start = FolioSpace.section, end = FolioSpace.section, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
    ) {
        when (state) {
            DictionaryState.Ready -> found(shown, selection, entries)
            else -> item { Offer(shown, state, onDownload) }
        }
        item { Footer(state == DictionaryState.Ready, onRemove) }
    }
}

/** The selection as the sheet repeats it: on one line, shortened when long. */
internal fun shownSelection(selection: String): String {
    val line = selection.replace(Regex("\\s+"), " ").trim()
    return if (line.length > SHOWN_SELECTION) line.take(SHOWN_SELECTION).trimEnd() + "…" else line
}

private fun LazyListScope.found(shown: String, selection: String, entries: List<DictionaryEntry>?) {
    when {
        entries == null ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(FolioSpace.small)) {
                    Headword(shown)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Note(stringResource(R.string.rd6_define_looking))
                }
            }
        entries.isEmpty() ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(FolioSpace.small)) {
                    Headword(shown)
                    Note(
                        if (WordNetText.normalise(selection) == null)
                            stringResource(R.string.rd6_define_select_word)
                        else stringResource(R.string.rd6_define_none, shown)
                    )
                }
            }
        else ->
            entries
                .groupBy { it.headword }
                .forEach { (headword, group) ->
                    item { Headword(headword) }
                    group.forEach { entry ->
                        item { EntryBlock(entry) }
                    }
                }
    }
}

@Composable
private fun Headword(text: String) {
    Text(
        text,
        Modifier.padding(top = FolioSpace.small).semantics { heading() },
        style = MaterialTheme.typography.headlineSmall,
        fontFamily = FontFamily.Serif,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Note(text: String, error: Boolean = false) {
    Text(
        text,
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyLarge,
        color =
            if (error) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun partOfSpeech(pos: PartOfSpeech): String =
    stringResource(
        when (pos) {
            PartOfSpeech.NOUN -> R.string.rd6_pos_noun
            PartOfSpeech.VERB -> R.string.rd6_pos_verb
            PartOfSpeech.ADJECTIVE -> R.string.rd6_pos_adjective
            PartOfSpeech.ADVERB -> R.string.rd6_pos_adverb
        }
    )

/** One part of speech: its name, then the numbered senses. */
@Composable
private fun EntryBlock(entry: DictionaryEntry) {
    Column(
        Modifier.fillMaxWidth().testTag(DEFINE_ENTRY_TAG),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
    ) {
        Text(
            partOfSpeech(entry.partOfSpeech),
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.primary,
        )
        entry.senses.forEachIndexed { index, sense ->
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpace.small)) {
                Text(
                    stringResource(R.string.rd6_define_number, index + 1),
                    Modifier.widthIn(min = 24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (sense.definition.isNotEmpty())
                        Text(sense.definition, style = MaterialTheme.typography.bodyLarge)
                    sense.examples.forEach {
                        Text(
                            stringResource(R.string.rd6_define_example, it),
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (sense.synonyms.isNotEmpty())
                        Text(
                            stringResource(
                                R.string.rd6_define_also,
                                sense.synonyms.joinToString(", "),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
            }
        }
    }
}

/** The first time: what the dictionary is, its size, and the download; then its progress. */
@Composable
private fun Offer(shown: String, state: DictionaryState, onDownload: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(FolioSpace.small)) {
        Headword(shown)
        when (state) {
            is DictionaryState.Downloading -> {
                LinearProgressIndicator(
                    progress = { state.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Note(
                    stringResource(R.string.rd6_define_downloading, (state.fraction * 100).toInt())
                )
            }
            DictionaryState.Unpacking -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Note(stringResource(R.string.rd6_define_unpacking))
            }
            else -> {
                Note(stringResource(R.string.rd6_define_offer))
                Note(stringResource(R.string.rd6_define_size, megabytes(DictionaryPackage.BYTES)))
                if (state == DictionaryState.Failed)
                    Note(stringResource(R.string.rd6_define_failed), error = true)
                FilledTonalButton(
                    onClick = onDownload,
                    modifier =
                        Modifier.padding(top = FolioSpace.small).testTag(DEFINE_DOWNLOAD_TAG),
                ) {
                    Text(stringResource(R.string.rd6_define_download))
                }
            }
        }
    }
}

/** The attribution, and Remove dictionary once it is installed. */
@Composable
private fun Footer(installed: Boolean, onRemove: () -> Unit) {
    Column(Modifier.padding(top = FolioSpace.item)) {
        HorizontalDivider()
        Text(
            stringResource(R.string.rd6_define_attribution),
            Modifier.padding(top = FolioSpace.small),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (installed)
            TextButton(onClick = onRemove) { Text(stringResource(R.string.rd6_define_remove)) }
    }
}
