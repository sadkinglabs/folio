package dev.sadking.folio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal const val LIST_PANE_TAG = "list-pane"
internal const val DETAIL_PANE_TAG = "detail-pane"
internal const val EMPTY_PANE_TAG = "empty-pane"

/** The rail's width before the panes have been placed once; measured after that. */
private const val RAIL_GUESS_DP = 80

/** A detail beside a list stays this wide at most, centred in its pane. */
private val DETAIL_MAX_WIDTH = 840.dp

/**
 * One pane or two. With [twoPane], [primary] sits on the left at [listPaneWidthDp] (up to the hinge
 * on a fold) and [detail] on the right, or [empty] while nothing is chosen. In one pane, [detail]
 * covers [primary] as on a phone, and the content is centred at most [maxWidth] wide.
 *
 * [primary] and [detail] are called from the same place whatever the layout, so their saved state
 * survives a fold or a rotation that changes the number of panes.
 */
@Composable
internal fun Panes(
    twoPane: Boolean,
    maxWidth: Dp?,
    primary: @Composable () -> Unit,
    detail: (@Composable () -> Unit)?,
    empty: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val window = LocalFolioWindow.current
    val density = LocalDensity.current
    // Where the panes start in the window, after the rail: a hinge is measured from the window's
    // left edge.
    var leftDp by remember { mutableIntStateOf(RAIL_GUESS_DP) }
    val single = Modifier.fillMaxHeight().centred(maxWidth)
    Row(
        modifier.fillMaxSize().onPlaced {
            leftDp = with(density) { it.positionInWindow().x.toDp().value.roundToInt() }
        }
    ) {
        if (twoPane || detail == null)
            Box(
                (if (twoPane) Modifier.width(listPaneWidthDp(window, leftDp).dp).fillMaxHeight()
                    else Modifier.weight(1f).then(single))
                    .testTag(LIST_PANE_TAG)
            ) {
                primary()
            }
        if (twoPane)
            if (window.hingeWidthDp > 0) Spacer(Modifier.width(window.hingeWidthDp.dp))
            else VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (twoPane || detail != null)
            Box(
                Modifier.weight(1f)
                    .then(
                        if (twoPane) Modifier.fillMaxHeight().centred(DETAIL_MAX_WIDTH) else single
                    )
                    .testTag(if (detail != null) DETAIL_PANE_TAG else EMPTY_PANE_TAG)
            ) {
                if (detail != null) detail() else empty()
            }
    }
}

/** Centred, at most [maxWidth] wide, filling that width; the full width when [maxWidth] is null. */
private fun Modifier.centred(maxWidth: Dp?): Modifier =
    if (maxWidth == null) fillMaxWidth()
    else wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = maxWidth).fillMaxWidth()

/** A detail with its own top bar: the book page's bar sits in the pane, not across the window. */
@Composable
internal fun DetailPane(bar: (@Composable () -> Unit)?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        bar?.invoke()
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

/** The right pane while nothing is chosen: one quiet line under the books illustration. */
@Composable
internal fun EmptyPane(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FolioSpace.item),
        ) {
            Illustration(FolioIllustration.Books)
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
