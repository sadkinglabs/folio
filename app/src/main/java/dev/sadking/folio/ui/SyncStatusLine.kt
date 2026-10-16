package dev.sadking.folio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.sadking.folio.R
import dev.sadking.folio.sync.EreaderSync

internal const val SYNC_LINE_TAG = "sync-line"
internal const val SYNC_LINE_BUTTON_TAG = "sync-line-button"
internal const val SYNC_LINE_DECISIONS_TAG = "sync-line-decisions"

/** How long "will sync within 5 minutes" stands after Sync is tapped, if no sync follows. */
internal const val SYNC_REQUEST_SHOWN_MS = 30 * 60 * 1000L

/** What the sync line says about the e-reader. [name] is its own name, or null when unknown. */
internal sealed interface SyncLine {
    /** No e-reader has synced and the plugin was never installed from this phone. */
    data object NotSetUp : SyncLine

    /** Set up, but no sync has finished yet. */
    data class NeverSynced(val name: String?) : SyncLine

    /** A sync is under way. */
    data class Syncing(val name: String?) : SyncLine

    /** Sync was tapped; the e-reader picks it up at its next check. */
    data class Requested(val name: String?) : SyncLine

    /** The last sync finished at [at]. */
    data class Synced(val name: String?, val at: Long) : SyncLine
}

/** The sync line: what it says, and how many decisions wait (questions and Hardcover prompts). */
internal data class SyncLineState(val line: SyncLine, val decisions: Int = 0)

/**
 * What the Library's sync line shows at [now]. The e-reader shown is the one that synced or started
 * syncing last. [requestedAt] is when Sync was tapped; it counts for [SYNC_REQUEST_SHOWN_MS] and
 * until a sync finishes after it. [pluginInstalled] tells "set up, not synced yet" from "not set
 * up". [prompts] counts the Hardcover finish prompts waiting.
 */
internal fun syncLine(
    ereaders: Collection<EreaderSync>,
    requestedAt: Long,
    pluginInstalled: Boolean,
    prompts: Int,
    now: Long,
): SyncLineState {
    val decisions = ereaders.sumOf { it.waiting.size } + prompts
    val ereader = ereaders.maxByOrNull { maxOf(it.lastSync, it.syncingSince) }
    val name = ereader?.name?.takeIf { it.isNotBlank() }
    val requested =
        requestedAt > (ereader?.lastSync ?: 0L) &&
            now - requestedAt in 0 until SYNC_REQUEST_SHOWN_MS
    val line =
        when {
            ereader == null && !pluginInstalled -> SyncLine.NotSetUp
            ereader != null && ereader.syncing(now) -> SyncLine.Syncing(name)
            requested -> SyncLine.Requested(name)
            ereader == null || ereader.lastSync <= 0 -> SyncLine.NeverSynced(name)
            else -> SyncLine.Synced(name, ereader.lastSync)
        }
    return SyncLineState(line, decisions)
}

/** "just now", "2 min ago", "3 hours ago", "yesterday", "5 days ago". */
@Composable
internal fun syncedAgo(at: Long, now: Long): String {
    val minutes = ((now - at).coerceAtLeast(0) / 60_000).toInt()
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> stringResource(R.string.m9sync_ago_now)
        minutes < 60 -> pluralStringResource(R.plurals.m9sync_ago_minutes, minutes, minutes)
        hours < 24 -> pluralStringResource(R.plurals.m9sync_ago_hours, hours, hours)
        days == 1 -> stringResource(R.string.m9sync_ago_yesterday)
        else -> pluralStringResource(R.plurals.m9sync_ago_days, days, days)
    }
}

/** The line's sentence: "Kindle synced 2 min ago", "Your e-reader hasn't synced yet". */
@Composable
internal fun syncLineText(line: SyncLine, now: Long): String {
    @Composable fun named(name: String?) = name ?: stringResource(R.string.m9sync_your_ereader)
    return when (line) {
        SyncLine.NotSetUp -> stringResource(R.string.m9sync_line_not_set_up)
        is SyncLine.NeverSynced -> stringResource(R.string.m9sync_line_never, named(line.name))
        is SyncLine.Syncing ->
            line.name?.let { stringResource(R.string.m9sync_line_syncing_with, it) }
                ?: stringResource(R.string.m9sync_line_syncing)
        is SyncLine.Requested -> stringResource(R.string.m9sync_line_requested, named(line.name))
        is SyncLine.Synced ->
            stringResource(R.string.m9sync_line_synced, named(line.name), syncedAgo(line.at, now))
    }
}

/** The same, without the name, for the e-reader's card, which shows its name above it. */
@Composable
internal fun syncCardText(line: SyncLine, now: Long): String =
    when (line) {
        SyncLine.NotSetUp,
        is SyncLine.NeverSynced -> stringResource(R.string.m9sync_card_never)
        is SyncLine.Syncing -> stringResource(R.string.m9sync_line_syncing)
        is SyncLine.Requested -> stringResource(R.string.m9sync_card_requested)
        is SyncLine.Synced -> stringResource(R.string.m9sync_card_synced, syncedAgo(line.at, now))
    }

/**
 * The sync line at the top of the Library: one sentence about the e-reader, "1 decision needed"
 * when something waits for the owner, and one button: Sync, or Set up when there is no e-reader
 * yet. While a sync runs, a small spinner takes the button's place. At large text the button goes
 * under the sentence.
 */
@Composable
internal fun SyncStatusLine(
    state: SyncLineState,
    now: Long,
    onSync: () -> Unit,
    onSetUp: () -> Unit,
    onDecisions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stacked = LocalDensity.current.fontScale > 1.5f
    val line = state.line
    val text = syncLineText(line, now)
    val icon =
        when (line) {
            SyncLine.NotSetUp -> Icons.Outlined.TabletAndroid
            else -> Icons.Outlined.Sync
        }
    val action: @Composable () -> Unit = {
        when (line) {
            is SyncLine.Syncing ->
                // A still ring in previews and screenshots: a spinning one never lets them settle.
                if (androidx.compose.ui.platform.LocalInspectionMode.current)
                    CircularProgressIndicator(
                        progress = { 0.7f },
                        modifier = Modifier.padding(horizontal = 12.dp).size(20.dp),
                        strokeWidth = 2.dp,
                    )
                else
                    CircularProgressIndicator(
                        Modifier.padding(horizontal = 12.dp).size(20.dp),
                        strokeWidth = 2.dp,
                    )
            SyncLine.NotSetUp ->
                FilledTonalButton(
                    onClick = onSetUp,
                    modifier = Modifier.testTag(SYNC_LINE_BUTTON_TAG),
                ) {
                    Text(stringResource(R.string.m9sync_set_up))
                }
            else ->
                FilledTonalButton(
                    onClick = onSync,
                    modifier = Modifier.testTag(SYNC_LINE_BUTTON_TAG),
                ) {
                    Text(stringResource(R.string.m9sync_sync))
                }
        }
    }
    Surface(
        modifier.fillMaxWidth().testTag(SYNC_LINE_TAG),
        shape = FolioSpace.panelShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        val body: @Composable (Modifier) -> Unit = { textModifier ->
            Column(textModifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (state.decisions > 0) DecisionsLink(state.decisions, onDecisions)
            }
        }
        if (stacked)
            Column(
                Modifier.padding(horizontal = FolioSpace.item, vertical = FolioSpace.small),
                verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(FolioSpace.gap))
                    body(Modifier.weight(1f))
                }
                action()
            }
        else
            Row(
                Modifier.padding(
                    start = FolioSpace.item,
                    end = FolioSpace.small,
                    top = 6.dp,
                    bottom = 6.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(FolioSpace.gap))
                body(Modifier.weight(1f))
                Spacer(Modifier.width(FolioSpace.small))
                action()
            }
    }
}

/** "1 decision needed", in the error colour, as a button that opens the decisions sheet. */
@Composable
private fun DecisionsLink(count: Int, onClick: () -> Unit) {
    Row(
        Modifier.clip(FolioSpace.panelShape)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 32.dp)
            .testTag(SYNC_LINE_DECISIONS_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.ErrorOutline,
            null,
            Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            pluralStringResource(R.plurals.m9sync_decisions, count, count),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
