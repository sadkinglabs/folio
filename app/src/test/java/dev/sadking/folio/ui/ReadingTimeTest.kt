package dev.sadking.folio.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.sadking.folio.data.DeviceKeys
import dev.sadking.folio.data.DeviceProfile
import dev.sadking.folio.data.DeviceRoster
import dev.sadking.folio.data.DeviceStatistics
import dev.sadking.folio.data.LINK_DEVICE_PREFIX
import dev.sadking.folio.data.ReadingSnapshot
import dev.sadking.folio.data.ReadingTime
import dev.sadking.folio.data.deviceSightings
import dev.sadking.folio.sync.EreaderSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings → Reading time: KOReader's totals from each e-reader, named and joined as the device
 * roster says, with old totals fetched over SSH still shown. On the JVM (Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class ReadingTimeTest {
    @get:Rule val compose = createComposeRule()

    private fun row(id: String, seconds: Long, exportedAt: Long, days: String) =
        DeviceStatistics(id, seconds, exportedAt, exportedAt * 1000, days)

    private val kindle =
        row(
            "${LINK_DEVICE_PREFIX}kindle-7f3a",
            12 * 3600 + 4 * 60,
            1_791_750_000,
            """[{"date":"2026-10-14","seconds":2700},{"date":"2026-10-13","seconds":1500}]""",
        )
    private val kobo =
        row(
            "${LINK_DEVICE_PREFIX}kobo-21",
            3 * 3600 + 20 * 60,
            1_791_660_000,
            """[{"date":"2026-10-14","seconds":900},{"date":"2026-10-11","seconds":1800}]""",
        )
    /** Fetched over SSH by the first plugin, keyed by the saved connection. */
    private val ssh =
        row("saved-ssh", 9 * 3600, 1_780_000_000, """[{"date":"2026-06-01","seconds":600}]""")

    private fun roster(profiles: List<DeviceProfile> = emptyList(), withSsh: Boolean = false) =
        DeviceRoster(
            deviceSightings(
                listOf(
                    EreaderSync("kindle-7f3a", "Kindle Paperwhite", 1_791_750_100_000),
                    EreaderSync("kobo-21", "Kobo Libra 2", 1_791_660_100_000),
                ),
                emptyList(),
                if (withSsh)
                    listOf(
                        ReadingSnapshot(
                            "b1",
                            "saved-ssh",
                            0.4,
                            "/p",
                            "reading",
                            0,
                            1_780_000_000_000,
                            "",
                            "",
                            "",
                        )
                    )
                else emptyList(),
            ),
            profiles,
        )

    @Test
    fun twoEreadersAddUpAndEachShowsUnderItsName() {
        val time = ReadingTime.of(listOf(kobo, kindle), roster())
        assertEquals(listOf("Kindle Paperwhite", "Kobo Libra 2"), time.devices.map { it.name })
        assertEquals(15 * 3600 + 24 * 60L, time.seconds)
        assertEquals(1_791_750_000L, time.updatedAt)
        // Days are summed across e-readers, newest first.
        assertEquals(
            listOf("2026-10-14" to 3600L, "2026-10-13" to 1500L, "2026-10-11" to 1800L),
            time.days,
        )

        compose.setContent { FolioTheme(dynamic = false) { ReadingTimeContent(time) } }
        compose.onNodeWithText("15 h 24 min").assertIsDisplayed()
        compose.onNodeWithText("By e-reader").assertIsDisplayed()
        compose.onNodeWithText("Kindle Paperwhite").assertIsDisplayed()
        compose.onNodeWithText("12 h 4 min").assertIsDisplayed()
        compose.onNodeWithText("Kobo Libra 2").assertIsDisplayed()
        compose.onNodeWithText("3 h 20 min").assertIsDisplayed()
        compose.onAllNodesWithTag(READING_TIME_DEVICE_TAG).assertCountEquals(2)
        compose.onAllNodesWithTag(READING_TIME_DAY_TAG).assertCountEquals(3)
        compose.onNodeWithText("1 h 0 min").assertIsDisplayed() // 14 October, both e-readers
        compose.onNodeWithText("25 min").assertIsDisplayed()
    }

    @Test
    fun oneEreaderIsNamedUnderTheTotal() {
        val time = ReadingTime.of(listOf(kindle), roster())
        compose.setContent { FolioTheme(dynamic = false) { ReadingTimeContent(time) } }
        compose.onNodeWithText("12 h 4 min").assertIsDisplayed()
        compose.onNodeWithText("From Kindle Paperwhite").assertIsDisplayed()
        compose.onNodeWithText("By e-reader").assertDoesNotExist()
    }

    @Test
    fun nothingSyncedYetSaysWhereItComesFrom() {
        compose.setContent { FolioTheme(dynamic = false) { ReadingTimeContent(ReadingTime.EMPTY) } }
        compose.onNodeWithText("Not synced yet").assertIsDisplayed()
        compose
            .onNodeWithText(
                "Your e-reader sends its reading time from KOReader each time it syncs."
            )
            .assertIsDisplayed()
    }

    @Test
    fun theOwnersNamesJoinsAndForgetsApply() {
        val now = 1_791_800_000_000L
        // Renamed.
        val renamed =
            ReadingTime.of(
                listOf(kindle, kobo),
                roster(
                    listOf(
                        DeviceProfile(DeviceKeys.ereader("kobo-21"), "Lounge Kobo", updatedAt = now)
                    )
                ),
            )
        assertEquals(listOf("Kindle Paperwhite", "Lounge Kobo"), renamed.devices.map { it.name })
        // Forgotten: left out, its row kept.
        val forgotten =
            ReadingTime.of(
                listOf(kindle, kobo),
                roster(
                    listOf(
                        DeviceProfile(
                            DeviceKeys.ereader("kobo-21"),
                            hiddenAt = now,
                            updatedAt = now,
                        )
                    )
                ),
            )
        assertEquals(listOf("Kindle Paperwhite"), forgotten.devices.map { it.name })
        assertEquals(12 * 3600 + 4 * 60L, forgotten.seconds)
        // Joined: one e-reader, one statistics database, so the newest totals win, not the sum.
        val joined =
            ReadingTime.of(
                listOf(kindle, kobo),
                roster(
                    listOf(
                        DeviceProfile(
                            DeviceKeys.ereader("kobo-21"),
                            mergedInto = DeviceKeys.ereader("kindle-7f3a"),
                            updatedAt = now,
                        )
                    )
                ),
            )
        assertEquals(1, joined.devices.size)
        assertEquals(kindle.totalSeconds, joined.seconds)
    }

    @Test
    fun oldTotalsFetchedOverSshStillShow() {
        // No old position for that connection: shown as before sync, under a plain label.
        val time = ReadingTime.of(listOf(ssh, kindle), roster())
        val old = time.devices.single { it.old }
        assertEquals(DeviceKeys.ssh("saved-ssh"), old.key)
        assertNull(old.name)
        assertEquals(21 * 3600 + 4 * 60L, time.seconds)
        compose.setContent { FolioTheme(dynamic = false) { ReadingTimeContent(time) } }
        compose.onNodeWithText("E-reader, before sync").assertIsDisplayed()
        compose.onNodeWithText("9 h 0 min").assertIsDisplayed()

        // Joined into the Kindle on the Device tab: the Kindle's newer totals replace them.
        val joined =
            ReadingTime.of(
                listOf(ssh, kindle),
                roster(
                    listOf(
                        DeviceProfile(
                            DeviceKeys.ssh("saved-ssh"),
                            mergedInto = DeviceKeys.ereader("kindle-7f3a"),
                            updatedAt = 1L,
                        )
                    ),
                    withSsh = true,
                ),
            )
        assertEquals(listOf("Kindle Paperwhite"), joined.devices.map { it.name })
        assertTrue(joined.devices.none { it.old })
        assertEquals(kindle.totalSeconds, joined.seconds)
    }

    @Test
    fun aRowThatCannotBeReadIsSkipped() {
        val broken = kobo.copy(days = "not json")
        assertEquals(
            listOf("Kindle Paperwhite"),
            ReadingTime.of(listOf(kindle, broken), roster()).devices.map { it.name },
        )
    }
}
