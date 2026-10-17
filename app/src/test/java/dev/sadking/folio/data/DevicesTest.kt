package dev.sadking.folio.data

import dev.sadking.folio.sync.EreaderSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Devices as Librillo shows them: one row per device, the owner's names, merges and forgets, and
 * the old SSH positions. The owner's phone: one Kindle that syncs and sends Progress sync reports,
 * and a position the old SSH bridge read the day before.
 */
class DevicesTest {
    private val hour = 3_600_000L
    private val now = 100 * hour
    private val kindleId = "4f1c0de5"
    private val kindle = DeviceKeys.ereader(kindleId)
    private val ssh = DeviceKeys.ssh("saved-connection")

    private fun ereader(
        id: String = kindleId,
        name: String = "Kindle",
        lastSync: Long = now - hour,
    ) = EreaderSync(id, name, lastSync)

    private fun report(
        deviceId: String = kindleId,
        device: String = "KindlePaperWhite5SE",
        percent: Double = 0.50,
        at: Long = now - 10 * hour,
        document: String = "doc",
    ) = KosyncReport(document, deviceId, device, "/body/p", percent, at / 1000, at)

    private fun snapshot(
        percent: Double = 0.46,
        at: Long = now - 23 * hour,
        book: String = "book",
    ) = ReadingSnapshot(book, "saved-connection", percent, "/p", "reading", 0, at, "", "", "")

    private val phone = PhoneReading("book", "hash", "{}", "/body", 0.49, now - 2 * hour)

    private fun directory(
        profiles: List<DeviceProfile> = emptyList(),
        reports: List<KosyncReport> = listOf(report()),
        ereaders: List<EreaderSync> = listOf(ereader()),
        snapshots: List<ReadingSnapshot> = listOf(snapshot()),
    ) = DeviceRoster(deviceSightings(ereaders, reports, snapshots, "phone"), profiles)

    private fun entries(devices: DeviceRoster, reports: List<KosyncReport> = listOf(report())) =
        progressEntries(
            true,
            phone,
            listOf(snapshot()),
            devices.reports(reports),
            true,
            392,
            devices,
        )

    private fun ereaderRows(entries: List<ProgressEntry>) = entries.filter {
        it.source == ProgressSource.EREADER
    }

    @Test
    fun syncAndProgressSyncFromOneKoreaderAreOneDevice() {
        val sightings =
            deviceSightings(
                listOf(ereader()),
                listOf(report(), report(document = "other"), report("phone", "Pixel")),
                listOf(snapshot(), snapshot(book = "b2")),
                phoneDeviceId = "phone",
            )
        assertEquals(listOf(kindle, ssh), sightings.map { it.key })
        val live = sightings[0]
        assertEquals("The name sync was set up with comes first", "Kindle", live.name)
        assertTrue(live.syncs)
        assertEquals(2, live.positions)
        assertEquals(now - hour, live.lastSeen)
        val old = sightings[1]
        assertTrue(old.old)
        assertEquals("", old.name)
        assertEquals(2, old.positions)
    }

    @Test
    fun theOwnersProgressSheetShowsTheOldPositionApartAndNotAsAhead() {
        val rows = ereaderRows(entries(directory()))
        assertEquals(2, rows.size)
        assertEquals("Kindle", rows[0].device)
        assertEquals(0.50, rows[0].percent!!, 0.0)
        assertFalse(rows[0].old)
        assertNull("The old SSH position has no name of its own", rows[1].device)
        assertTrue(rows[1].old)
        assertEquals(0.46, rows[1].percent!!, 0.0)
        assertEquals("Kindle", aheadOfPhone(entries(directory()))?.device)
    }

    @Test
    fun mergingTheOldPositionIntoTheKindleLeavesOneRowNewestWins() {
        val before = directory()
        val merged = DeviceProfiles.merge(before, before.device(ssh)!!, null, kindle, now)!!
        val devices = directory(listOf(merged))
        val rows = ereaderRows(entries(devices))
        assertEquals(1, rows.size)
        assertEquals("Kindle", rows[0].device)
        assertEquals(0.50, rows[0].percent!!, 0.0)
        assertFalse(rows[0].old)
        assertEquals(1, devices.shown.size)
        assertEquals(listOf(kindle, ssh), devices.shown.single().members.map { it.key })
        // When the old position is the newer one, it wins the row (under the Kindle's name).
        val older = listOf(report(at = now - 30 * hour))
        val row = ereaderRows(entries(directory(listOf(merged), older), older)).single()
        assertEquals(0.46, row.percent!!, 0.0)
        assertEquals("Kindle", row.device)
    }

    @Test
    fun aRenameIsUsedForRowsCardsAndReports() {
        val devices = directory()
        val renamed =
            DeviceProfiles.rename(devices.device(kindle)!!, null, "  My   Paperwhite ", now)
        val after = directory(listOf(renamed))
        assertEquals("My Paperwhite", ereaderRows(entries(after))[0].device)
        assertEquals("My Paperwhite", after.syncCards(listOf(ereader()))[0].name)
        assertEquals("My Paperwhite", after.reports(listOf(report())).single().device)
        assertTrue(after.device(kindle)!!.renamed)
        // A blank name goes back to the device's own.
        val cleared = DeviceProfiles.rename(after.device(kindle)!!, renamed, " ", now)
        assertEquals("Kindle", directory(listOf(cleared)).name(kindle))
    }

    @Test
    fun forgettingHidesUntilTheDeviceReportsAgain() {
        val devices = directory()
        val forgot = DeviceProfiles.forget(devices.device(ssh)!!, null, now)
        val after = directory(listOf(forgot))
        assertTrue(after.hidden(ssh))
        assertEquals(listOf(kindle), after.shown.map { it.key })
        assertEquals(listOf("Kindle"), ereaderRows(entries(after)).map { it.device })
        // The Kindle forgotten too: no e-reader rows, nothing deleted.
        val both = listOf(forgot, DeviceProfiles.forget(after.device(kindle)!!, null, now))
        val none = directory(both)
        assertTrue(ereaderRows(entries(none)).isEmpty())
        assertTrue(none.syncCards(listOf(ereader())).isEmpty())
        assertEquals("Still known, only hidden", 2, none.all.size)
        // A report after it was forgotten brings the Kindle back; the old position never reports.
        val fresh = listOf(report(at = now + hour))
        val back = directory(both, fresh)
        assertEquals(listOf(kindle), back.shown.map { it.key })
        assertEquals(listOf("Kindle"), ereaderRows(entries(back, fresh)).map { it.device })
    }

    @Test
    fun aMergeNeverLoopsAndCanBeUndone() {
        val devices = directory()
        assertNull(
            "Not into itself",
            DeviceProfiles.merge(devices, devices.device(kindle)!!, null, kindle, now),
        )
        val sshIntoKindle =
            DeviceProfiles.merge(devices, devices.device(ssh)!!, null, kindle, now)!!
        val merged = directory(listOf(sshIntoKindle))
        assertNull(
            "Not into a device merged into it",
            DeviceProfiles.merge(merged, merged.device(kindle)!!, null, ssh, now),
        )
        // Even a loop saved by hand ends somewhere.
        val loop =
            listOf(
                DeviceProfile(kindle, mergedInto = ssh, updatedAt = 1),
                DeviceProfile(ssh, mergedInto = kindle, updatedAt = 1),
            )
        assertEquals(2, directory(loop).all.flatMap { it.members }.size)
        // "Show separately".
        val apart = DeviceProfiles.separate(ssh, sshIntoKindle, now)
        assertEquals(2, directory(listOf(apart)).shown.size)
    }

    @Test
    fun twoSyncIdsOfOneKindleShowOneCard() {
        val oldId = ereader("before-setup-2", "Kindle", lastSync = now - 50 * hour)
        val devices = directory(ereaders = listOf(ereader(), oldId))
        assertEquals(2, devices.syncCards(listOf(ereader(), oldId)).size)
        val merged =
            DeviceProfiles.merge(
                devices,
                devices.device(DeviceKeys.ereader("before-setup-2"))!!,
                null,
                kindle,
                now,
            )!!
        val cards = directory(listOf(merged), ereaders = listOf(ereader(), oldId))
        assertEquals(
            listOf(kindleId),
            cards.syncCards(listOf(ereader(), oldId)).map { it.id },
        )
    }

    @Test
    fun reportsFoldMergedDevicesAndNameThem() {
        val other = report("kobo-1", "Kobo", 0.7, now - 5 * hour)
        val devices = directory(reports = listOf(report(), other))
        assertEquals(
            "Newest first, each under the name Librillo shows",
            listOf("Kobo", "Kindle"),
            devices.reports(listOf(report(), other)).map { it.device },
        )
        // Without a sync name, the name Progress sync sends.
        val unsynced = directory(reports = listOf(report(), other), ereaders = emptyList())
        assertEquals("KindlePaperWhite5SE", unsynced.name(kindle))
        val merged =
            DeviceProfiles.merge(
                devices,
                devices.device(DeviceKeys.ereader("kobo-1"))!!,
                null,
                kindle,
                now,
            )!!
        val one = directory(listOf(merged), listOf(report(), other))
        val folded = one.reports(listOf(report(), other)).single()
        assertEquals("The newest report wins", 0.7, folded.percentage, 0.0)
        assertEquals("Kindle", folded.device)
    }

    @Test
    fun theReaderShowsTheOldPositionOnlyAsTheProgressSheetDoes() {
        val now = this.now
        // Shown on its own, with no name of its own: the sheet's label for an old position.
        val shown = directory().readerPlaces(listOf(report()), snapshot())
        assertEquals(listOf(kindleId), shown.reports.map { it.deviceId })
        assertEquals(snapshot(), shown.old)
        assertNull(shown.oldName)

        // Forgotten: gone from the reader too.
        val roster = directory()
        val forgot = DeviceProfiles.forget(roster.device(ssh)!!, null, now)
        assertNull(directory(listOf(forgot)).readerPlaces(listOf(report()), snapshot()).old)

        // Joined with the Kindle, whose report is newer: one row, the Kindle's.
        val merged = DeviceProfiles.merge(roster, roster.device(ssh)!!, null, kindle, now)!!
        val joined = directory(listOf(merged)).readerPlaces(listOf(report()), snapshot())
        assertNull(joined.old)
        assertEquals(1, joined.reports.size)

        // Joined, and the old position is the newer one: it wins, under the Kindle's name.
        val older = report(at = now - 30 * hour)
        val newer =
            directory(listOf(merged), reports = listOf(older))
                .readerPlaces(listOf(older), snapshot())
        assertTrue(newer.reports.isEmpty())
        assertEquals(snapshot(), newer.old)
        assertEquals("Kindle", newer.oldName)
    }
}
