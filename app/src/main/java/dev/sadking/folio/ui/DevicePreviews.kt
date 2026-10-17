package dev.sadking.folio.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dev.sadking.folio.data.*
import dev.sadking.folio.sync.EreaderSync
import dev.sadking.folio.sync.MatchCandidateView
import dev.sadking.folio.sync.MatchCheck

/*
 * Previews for devices and for Hardcover matches held by another book: the owner's Kindle that
 * syncs and sends Progress sync reports, a Kobo that only sends reports, and the old position the
 * SSH bridge read before sync.
 */

private const val HOUR = 3_600_000L

private val previewKindle = EreaderSync("kindle-7f3a", "Kindle Paperwhite", PREVIEW_NOW - 2 * HOUR)

private fun previewReports(now: Long) =
    listOf(
        KosyncReport(
            "doc",
            "kindle-7f3a",
            "KindlePaperWhite5SE",
            "/p",
            0.50,
            (now - 10 * HOUR) / 1000,
            now - 10 * HOUR,
        ),
        KosyncReport(
            "doc2",
            "kindle-7f3a",
            "KindlePaperWhite5SE",
            "/p",
            0.20,
            (now - 30 * HOUR) / 1000,
            now - 30 * HOUR,
        ),
        KosyncReport(
            "doc",
            "kobo-21",
            "Kobo Libra 2",
            "/p",
            0.31,
            (now - 50 * HOUR) / 1000,
            now - 50 * HOUR,
        ),
    )

private fun previewSnapshots(now: Long) =
    listOf(
        ReadingSnapshot(
            "sample1",
            "saved-ssh",
            0.46,
            "/p",
            "reading",
            0,
            now - 23 * HOUR,
            "",
            "",
            "",
        )
    )

private fun previewRoster(now: Long, profiles: List<DeviceProfile> = emptyList()) =
    DeviceRoster(
        deviceSightings(
            listOf(previewKindle.copy(lastSync = now - 2 * HOUR)),
            previewReports(now),
            previewSnapshots(now),
        ),
        profiles,
    )

/** The old SSH position merged into the Kindle. */
private fun mergedProfiles(now: Long) =
    listOf(
        DeviceProfile(
            DeviceKeys.ssh("saved-ssh"),
            mergedInto = DeviceKeys.ereader("kindle-7f3a"),
            updatedAt = now,
        )
    )

/** The Device tab with three devices: the Kindle, a Kobo and the old position before sync. */
@Preview(name = "Device · several · light", widthDp = 412, heightDp = 1800)
@Preview(
    name = "Device · several · dark",
    widthDp = 412,
    heightDp = 1800,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun DevicesPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize()) {
            DeviceScreen(
                ereaders = listOf(previewKindle),
                line = SyncLineState(SyncLine.Synced("Kindle Paperwhite", previewKindle.lastSync)),
                requestedAt = 0,
                now = PREVIEW_NOW,
                autoSync = true,
                onAutoSync = {},
                keepAwake = false,
                onKeepAwake = {},
                noWifi = false,
                onOpenWifi = {},
                onSync = {},
                onSetUp = {},
                onDecisions = {},
                activity = emptyList(),
                onClearActivity = {},
                onSignIn = {},
                onSavePlugin = {},
                devices = previewRoster(PREVIEW_NOW).shown,
            )
        }
    }
}

/** One device's sheet: the Kindle, renamed, with the old position shown together with it. */
@Preview(name = "Device sheet · light", widthDp = 412, heightDp = 1100)
@Preview(
    name = "Device sheet · dark",
    widthDp = 412,
    heightDp = 1100,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun DeviceSheetPreview() {
    val roster =
        previewRoster(
            PREVIEW_NOW,
            mergedProfiles(PREVIEW_NOW) +
                DeviceProfile(
                    DeviceKeys.ereader("kindle-7f3a"),
                    "My Paperwhite",
                    updatedAt = PREVIEW_NOW,
                ),
        )
    val kindle = roster.device(DeviceKeys.ereader("kindle-7f3a"))!!
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            DeviceSheetContent(kindle, roster.shown.filter { it.key != kindle.key }, PREVIEW_NOW)
        }
    }
}

/** The old position's sheet, before it is merged or forgotten. */
@Preview(name = "Device sheet old · light", widthDp = 412, heightDp = 900)
@Composable
internal fun OldPositionSheetPreview() {
    val roster = previewRoster(PREVIEW_NOW)
    val old = roster.device(DeviceKeys.ssh("saved-ssh"))!!
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            DeviceSheetContent(old, roster.shown.filter { it.key != old.key }, PREVIEW_NOW)
        }
    }
}

@Composable
private fun ProgressPreview(profiles: (Long) -> List<DeviceProfile>) {
    // Relative times ("10 hours ago") are the system's, against the real clock.
    val now = System.currentTimeMillis()
    val roster = previewRoster(now, profiles(now))
    val book = sampleBooks[0]
    val entries =
        progressEntries(
            true,
            PhoneReading("sample1", "", "{}", "/p", 0.49, now - HOUR),
            previewSnapshots(now),
            roster.reports(previewReports(now).filter { it.document == "doc" }),
            hardcoverLinked = true,
            hardcoverPage = 392,
            devices = roster,
        )
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            ProgressContent(book, true, entries)
        }
    }
}

/** The Progress sheet as the owner saw it: the old position from before sync on its own row. */
@Preview(name = "Progress devices · light", widthDp = 412, heightDp = 892)
@Preview(
    name = "Progress devices · dark",
    widthDp = 412,
    heightDp = 892,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun ProgressDevicesPreview() = ProgressPreview { emptyList() }

/** The same book after "This is the same e-reader as Kindle Paperwhite": one row, newest wins. */
@Preview(name = "Progress merged · light", widthDp = 412, heightDp = 892)
@Preview(
    name = "Progress merged · dark",
    widthDp = 412,
    heightDp = 892,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun ProgressMergedPreview() = ProgressPreview(::mergedProfiles)

private val heldHolder = HardcoverHolder("entry", "book-fall", "The Fall of Hyperion", false)

private val heldCheck =
    MatchCheck(
        "book-hyperion",
        "Hyperion",
        listOf("Dan Simmons"),
        listOf(MatchCandidateView(77, "Hyperion", listOf("Dan Simmons"), 1989, null)),
        mapOf(77 to heldHolder),
    )

/** Check these matches when the only candidate is already matched to another book. */
@Preview(name = "Decisions held · light", widthDp = 412, heightDp = 892)
@Preview(
    name = "Decisions held · dark",
    widthDp = 412,
    heightDp = 892,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun DecisionsHeldPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            DecisionsContent(
                emptyList(),
                listOf(heldCheck),
                offer =
                    MoveOffer("book-hyperion", 77, "Hyperion", listOf("Dan Simmons"), heldHolder),
            )
        }
    }
}

/** The Hardcover sheet's Link found the book matched elsewhere: move it or keep it there. */
@Preview(name = "Hardcover move · light", widthDp = 412, heightDp = 892)
@Preview(
    name = "Hardcover move · dark",
    widthDp = 412,
    heightDp = 892,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun HardcoverMovePreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            HardcoverReadingContent(
                book = sampleBooks[0].copy(title = "Hyperion", authors = "[\"Dan Simmons\"]"),
                username = "alex",
                busy = false,
                error = "Already matched to “The Fall of Hyperion”.",
                reading = null,
                editions = emptyList(),
                matches =
                    listOf(
                        HardcoverMatch(77, "Hyperion", listOf("Dan Simmons"), "hyperion", 1989),
                        HardcoverMatch(
                            78,
                            "The Fall of Hyperion",
                            listOf("Dan Simmons"),
                            "the-fall-of-hyperion",
                            1990,
                        ),
                    ),
                snapshot = null,
                phonePercent = null,
                reports = emptyList(),
                onConnect = {},
                onRefresh = {},
                onSearch = {},
                onLink = {},
                onMarkReading = {},
                onSend = { _, _, _ -> },
                moveOffer = MoveOffer("sample1", 77, "Hyperion", listOf("Dan Simmons"), heldHolder),
            )
        }
    }
}
