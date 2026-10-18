@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.ui.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sadking.folio.StatsLoad
import dev.sadking.folio.StatsViewModel
import dev.sadking.folio.ui.FolioSheet
import dev.sadking.folio.ui.FolioSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The book page's Reading stats card, and its sheet. It shows nothing until the statistics are
 * ready, and nothing for a book that has no reading or listening: no card, no empty state.
 */
@Composable
internal fun BookStatsSlot(model: StatsViewModel, key: String) {
    val load by model.load.collectAsStateWithLifecycle()
    val snapshot = (load as? StatsLoad.Ready)?.snapshot ?: return
    val text = rememberStatsText()
    val stats by
        produceState<BookStats?>(null, snapshot, key, text) {
            value = withContext(Dispatchers.Default) { StatsUi.book(snapshot, key, text) }
        }
    val shown = stats ?: return
    var open by rememberSaveable(key) { mutableStateOf(false) }
    BookStatsCard(shown, onClick = { open = true })
    if (open)
        FolioSheet(
            onDismissRequest = { open = false },
            shape = FolioSpace.sheetShape,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) { BookStatsContent(shown) }
        }
}
