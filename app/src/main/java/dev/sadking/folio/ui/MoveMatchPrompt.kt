@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.sadking.folio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.sadking.folio.R
import dev.sadking.folio.data.HardcoverHolder
import dev.sadking.folio.data.MoveOffer

internal const val MOVE_MATCH_TAG = "move-match"
internal const val KEEP_MATCH_TAG = "keep-match"

/** "Already matched to “The Fall of Hyperion”.", with "which is in Recently removed" when it is. */
@Composable
internal fun heldText(holder: HardcoverHolder): String =
    stringResource(
        if (holder.removed) R.string.hc_link_taken_removed else R.string.hc_link_taken,
        holder.title,
    )

/**
 * A Hardcover book the owner picked is already matched to another book: says which, what moving the
 * match does, and offers "Move the match here" or "Keep it there". The same prompt in Check these
 * matches and in the Hardcover sheet.
 */
@Composable
internal fun MoveMatchPrompt(
    offer: MoveOffer,
    enabled: Boolean,
    onMove: (MoveOffer) -> Unit,
    onKeep: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier.fillMaxWidth(),
        shape = FolioSpace.panelShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Column(
            Modifier.padding(FolioSpace.gap),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
        ) {
            Text(
                heldText(offer.holder),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(
                stringResource(R.string.hc_link_move_body, offer.holder.title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
                verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
            ) {
                Button(
                    onClick = { onMove(offer) },
                    enabled = enabled,
                    modifier = Modifier.testTag(MOVE_MATCH_TAG),
                ) {
                    Text(stringResource(R.string.hc_link_move))
                }
                OutlinedButton(
                    onClick = onKeep,
                    enabled = enabled,
                    modifier = Modifier.testTag(KEEP_MATCH_TAG),
                ) {
                    Text(stringResource(R.string.hc_link_keep))
                }
            }
        }
    }
}
