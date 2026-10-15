package dev.sadking.folio.data

import org.junit.Assert.*
import org.junit.Test

class DeviceTabTest {
    private fun book(id: String) =
        Book(
            id,
            "Title $id",
            "[]",
            originalPath = "",
            currentPath = "",
            originalHash = "orig-$id",
            currentHash = "hash-$id",
        )

    private fun report(
        device: String,
        name: String,
        document: String,
        seconds: Long,
        received: Long,
        percent: Double = 0.5,
    ) = KosyncReport(document, device, name, "/body/p[1]", percent, seconds, received)

    private val digests = mapOf("a" to "doc-a", "b" to "doc-b")

    @Test
    fun linkStatusFollowsTheSwitchThePortAndTheNetwork() {
        assertEquals(LinkStatus.OFF, linkStatus(false, 8765, listOf("192.168.1.2")))
        assertEquals(LinkStatus.STARTING, linkStatus(true, null, listOf("192.168.1.2")))
        assertEquals(LinkStatus.WAITING_FOR_NETWORK, linkStatus(true, 8765, emptyList()))
        assertEquals(LinkStatus.ON, linkStatus(true, 8765, listOf("192.168.1.2")))
    }

    @Test
    fun ereadersComeFromReportsAndDownloadsNewestFirst() {
        val books = listOf(book("a"), book("b"))
        val seen =
            ereadersSeen(
                listOf(
                    report("kindle", "Kindle", "doc-a", 100, 100_000, 0.4),
                    report("kindle", "Kindle", "doc-b", 200, 200_000, 0.7),
                    report("phone", "Folio on Pixel", "doc-a", 300, 300_000),
                ),
                listOf(
                    Transfer("a", "link:kobo", "/Folio/a.epub", "hash-a", 250_000),
                    Transfer("b", "ssh-device", "/mnt/us/b.epub", "hash-b", 900_000),
                ),
                books,
                "phone",
            ) {
                digests[it.id]
            }
        assertEquals(listOf("kobo", "kindle"), seen.map { it.id })
        val kindle = seen.single { it.id == "kindle" }
        assertEquals("Kindle", kindle.name)
        assertEquals(200_000, kindle.lastSeen)
        assertEquals("b", kindle.position?.book?.id)
        assertEquals(0.7, kindle.position!!.percent, 0.0)
        val kobo = seen.single { it.id == "kobo" }
        assertEquals("", kobo.name)
        assertEquals(250_000, kobo.lastSeen)
        assertNull(kobo.position)
    }

    @Test
    fun aDownloadAfterAReportMovesTheDeviceForward() {
        val seen =
            ereadersSeen(
                listOf(report("kindle", "Kindle", "doc-a", 100, 100_000)),
                listOf(Transfer("a", "link:kindle", "/Folio/a.epub", "hash-a", 500_000)),
                listOf(book("a")),
                null,
            ) {
                digests[it.id]
            }
        assertEquals(1, seen.size)
        assertEquals("Kindle", seen[0].name)
        assertEquals(500_000, seen[0].lastSeen)
        assertEquals("a", seen[0].position?.book?.id)
    }

    @Test
    fun noReportsMeansNoDigestIsComputed() {
        var asked = 0
        val seen =
            ereadersSeen(emptyList(), emptyList(), listOf(book("a")), null) {
                asked++
                "doc-a"
            }
        assertTrue(seen.isEmpty())
        assertEquals(0, asked)
    }

    @Test
    fun queueKeepsOrderSkipsMissingBooksAndMarksDeliveredRevisions() {
        val queue =
            queuedBooks(
                listOf("b", "gone", "a"),
                listOf(book("a"), book("b")),
                listOf(
                    Transfer("a", "link:kindle", "/Folio/a.epub", "hash-a", 1),
                    Transfer("b", "link:kindle", "/Folio/b.epub", "old-hash", 1),
                ),
            )
        assertEquals(listOf("b", "a"), queue.map { it.book.id })
        assertFalse(queue[0].sent)
        assertTrue(queue[1].sent)
    }
}
