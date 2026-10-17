package dev.sadking.folio.reader

import dev.sadking.folio.core.dictionary.DictionaryPackage
import dev.sadking.folio.reader.dictionary.DictionaryInstaller
import dev.sadking.folio.reader.dictionary.DictionaryState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dictionary download with an injected connection: nothing goes to the network. Covers resuming
 * (206), starting over (200), a cut-short download, and a file that fails its SHA-256.
 */
class DictionaryModelTest {
    private val root = Files.createTempDirectory("dictionary").toFile()

    @After fun clean() = root.deleteRecursively().let {}

    /** A small package shaped like the real one: the kept files under a top folder. */
    private val payload: ByteArray =
        ByteArrayOutputStream()
            .also { bytes ->
                ZipOutputStream(bytes).use { zip ->
                    (DictionaryPackage.KEEP + "index.sense").forEach {
                        zip.putNextEntry(ZipEntry("oewn2025-plus/$it"))
                        zip.write("contents of $it, ".repeat(50).toByteArray())
                        zip.closeEntry()
                    }
                }
            }
            .toByteArray()

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** One canned response; remembers the Range it was asked for. */
    private class Response(val code: Int, val body: ByteArray) {
        var range: String? = null
        var asked = false
    }

    private class FakeConnection(url: URL, private val response: Response) :
        HttpURLConnection(url) {
        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun usingProxy() = false

        override fun getResponseCode(): Int {
            response.asked = true
            response.range = getRequestProperty("Range")
            return response.code
        }

        override fun getInputStream(): InputStream = ByteArrayInputStream(response.body)
    }

    private fun installer(
        vararg responses: Response,
        sha: String = sha256(payload),
    ): DictionaryInstaller {
        val queue = ArrayDeque(responses.toList())
        return DictionaryInstaller(
            root,
            url = "https://example.org/english-wordnet.zip",
            bytes = payload.size.toLong(),
            sha256 = sha,
            open = { FakeConnection(it, queue.removeFirst()) },
        )
    }

    private fun install(installer: DictionaryInstaller): DictionaryState {
        val state = MutableStateFlow<DictionaryState>(DictionaryState.Missing)
        runBlocking { installer.install(state) }
        return state.value
    }

    @Test
    fun aFreshDownloadIsCheckedUnpackedAndMarkedComplete() {
        val ok = Response(200, payload)
        val installer = installer(ok)
        assertEquals(DictionaryState.Ready, install(installer))
        assertNull("a fresh download asks for the whole file", ok.range)
        assertTrue(installer.ready())
        assertEquals(
            DictionaryPackage.KEEP,
            installer.dir.list()!!.filter { !it.startsWith(".") }.toSet(),
        )
        assertFalse(installer.part.exists())
        assertFalse(File(root, DictionaryPackage.VERSION + ".partial").exists())
    }

    @Test
    fun aPartialDownloadResumesWith206() {
        val half = payload.size / 2
        root.mkdirs()
        File(root, "download.part").writeBytes(payload.copyOfRange(0, half))
        val rest = Response(206, payload.copyOfRange(half, payload.size))
        val installer = installer(rest)
        assertEquals(DictionaryState.Ready, install(installer))
        assertEquals("bytes=$half-", rest.range)
        assertTrue(installer.ready())
    }

    @Test
    fun aServerThatIgnoresTheRangeStartsOver() {
        root.mkdirs()
        // Stale bytes from an earlier try: a 200 replaces them rather than appending.
        File(root, "download.part").writeBytes(ByteArray(100) { 7 })
        val whole = Response(200, payload)
        val installer = installer(whole)
        assertEquals(DictionaryState.Ready, install(installer))
        assertEquals("bytes=100-", whole.range)
        assertTrue(installer.ready())
    }

    @Test
    fun aCutShortDownloadKeepsWhatArrivedAndTheNextTryFinishesIt() {
        val half = payload.size / 2
        val first = Response(200, payload.copyOfRange(0, half))
        val second = Response(206, payload.copyOfRange(half, payload.size))
        val installer = installer(first, second)
        assertEquals(DictionaryState.Failed, install(installer))
        assertEquals(half.toLong(), installer.part.length())
        assertFalse(installer.ready())
        assertEquals(DictionaryState.Ready, install(installer))
        assertEquals("bytes=$half-", second.range)
    }

    @Test
    fun aFileThatFailsItsChecksumIsRemovedAndNothingIsInstalled() {
        val installer = installer(Response(200, payload), sha = "0".repeat(64))
        assertEquals(DictionaryState.Failed, install(installer))
        assertFalse(installer.part.exists())
        assertFalse(installer.dir.exists())
        assertFalse(installer.ready())
    }

    @Test
    fun aServerErrorFailsAndKeepsThePartialFile() {
        root.mkdirs()
        File(root, "download.part").writeBytes(payload.copyOfRange(0, 10))
        val installer = installer(Response(500, ByteArray(0)))
        assertEquals(DictionaryState.Failed, install(installer))
        assertEquals(10L, installer.part.length())
    }

    @Test
    fun aCompleteDownloadIsNotFetchedAgain() {
        root.mkdirs()
        File(root, "download.part").writeBytes(payload)
        val never = Response(200, payload)
        assertEquals(DictionaryState.Ready, install(installer(never)))
        assertFalse(never.asked)
    }

    @Test
    fun removeDeletesTheDictionaryAndAnyPartialDownload() {
        val installer = installer(Response(200, payload))
        install(installer)
        installer.part.writeBytes(ByteArray(5))
        installer.remove()
        assertFalse(installer.dir.exists())
        assertFalse(installer.part.exists())
        assertFalse(installer.ready())
    }
}
