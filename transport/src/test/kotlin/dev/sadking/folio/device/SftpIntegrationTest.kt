package dev.sadking.folio.device

import dev.sadking.folio.core.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real SSH/SFTP round trips against a disposable local SFTP fixture, never a Kindle. */
class SftpIntegrationTest {
    private fun config(): DeviceConfig {
        assumeTrue(
            "Run scripts/check-with-sftp.py for local SFTP integration tests",
            System.getenv("FOLIO_SFTP_PORT") != null,
        )
        return DeviceConfig(
            "127.0.0.1",
            System.getenv("FOLIO_SFTP_PORT").toInt(),
            System.getProperty("user.name"),
            System.getenv("FOLIO_SFTP_ROOT"),
            System.getenv("FOLIO_SFTP_FINGERPRINT"),
        )
    }

    private fun credentials() =
        DeviceCredential("", File(System.getenv("FOLIO_SFTP_KEY")).readBytes())

    @Test
    fun companionInstallationPublishesWholeDirectoryAndRetainsPreviousVersion() {
        val cfg = config()
        val pluginRoot = File(cfg.directory, "plugins-${UUID.randomUUID()}").apply { mkdirs() }
        val old = File(pluginRoot, "folio.koplugin").apply { mkdirs() }
        File(old, "main.lua").writeText("old plugin")
        File(pluginRoot, "unrelated.koplugin").mkdirs()
        val source =
            File.createTempFile("folio-plugin", ".lua").apply { writeText("new plugin bytes") }
        try {
            // The one-time Link setup travels with the plugin files.
            val files =
                listOf(
                        "main.lua",
                        "sync.lua",
                        "link.lua",
                        "_meta.lua",
                        "LICENSE",
                        "link-setup.json",
                    )
                    .associateWith { source }
            SftpDevice(cfg.copy(directory = pluginRoot.path), credentials()).use {
                it.installFolioBridge(files)
            }
            assertTrue(File(pluginRoot, "unrelated.koplugin").isDirectory)
            files.keys.forEach {
                assertEquals("new plugin bytes", File(pluginRoot, "folio.koplugin/$it").readText())
            }
            val backup = pluginRoot.listFiles()!!.single { it.name.startsWith(".folio-backup-") }
            assertEquals("old plugin", File(backup, "main.lua").readText())
            assertFalse(pluginRoot.listFiles()!!.any { it.name.startsWith(".folio-install-") })
        } finally {
            source.delete()
        }
    }

    @Test
    fun findingKoreaderSeesRealFoldersOnly() {
        val cfg = config()
        val base = File(cfg.directory, "probe-${UUID.randomUUID()}")
        val plugins = File(base, "koreader/plugins").apply { mkdirs() }
        val link = File(base, "linked")
        java.nio.file.Files.createSymbolicLink(link.toPath(), plugins.toPath())
        SftpDevice(cfg, credentials()).use {
            assertTrue(it.isDirectory(plugins.path))
            assertFalse(it.isDirectory(File(base, "missing/plugins").path))
            assertFalse(it.isDirectory(link.path))
            assertFalse(runCatching { it.isDirectory("${base.path}/../etc") }.isSuccess)
            assertFalse(runCatching { it.isDirectory("relative/plugins") }.isSuccess)
        }
    }

    @Test
    fun folderBrowserCreatesIdempotentlyAndRejectsLinksAndTraversal() {
        val cfg = config()
        val name = "folder-${UUID.randomUUID()}"
        SftpDevice(cfg, credentials()).use { remote ->
            remote.createDirectory("", name)
            remote.createDirectory("", name)
            assertTrue(remote.directories().any { it.relativePath == name })
            remote.createDirectory(name, "Inner")
            assertTrue(remote.directories(name).any { it.relativePath == "$name/Inner" })
            assertTrue(runCatching { remote.createDirectory("", "../escape") }.isFailure)
            assertTrue(runCatching { remote.directories("../escape") }.isFailure)
            val root = File(cfg.directory)
            File(root, "$name-file").writeText("retained")
            assertTrue(runCatching { remote.createDirectory("", "$name-file") }.isFailure)
            java.nio.file.Files.createSymbolicLink(
                File(root, "$name-link").toPath(),
                File(root, name).toPath(),
            )
            assertFalse(remote.directories().any { it.name == "$name-link" })
            assertTrue(runCatching { remote.directories("$name-link") }.isFailure)
        }
    }

    @Test
    fun pinnedPublicKeyAuthenticationUploadReadInventoryAndRetry() {
        val cfg = config()
        val local =
            File.createTempFile("folio-integration", ".epub").apply {
                writeText("roundtrip bytes")
                deleteOnExit()
            }
        SftpDevice(cfg, credentials()).use { remote ->
            val b = SyncBook(UUID.randomUUID().toString(), "Roundtrip", local, Epub.sha256(local))
            val item = SyncPlanner.plan(listOf(b), remote).single()
            SyncEngine(remote).send(item, {})
            assertEquals(b.hash, remote.read(item.path).use { Epub.hash(it) })
            assertTrue(remote.inventory().any { it.path == item.path })
            SyncEngine(remote).send(item, {})
            assertEquals(Action.PRESENT, SyncPlanner.plan(listOf(b), remote).single().action)
        }
    }

    @Test
    fun wrongPinnedKeyRejectsConnection() {
        val cfg = config().copy(fingerprint = "SHA256:" + "A".repeat(43))
        try {
            SftpDevice(cfg, credentials()).close()
            fail("Wrong host key accepted")
        } catch (e: com.jcraft.jsch.JSchException) {
            assertTrue(e.message.orEmpty().contains("HostKey"))
        }
    }

    @Test
    fun remoteSymlinkIsNeverRead() {
        val cfg = config()
        val root = File(cfg.directory)
        val target = File(root, "target").apply { writeText("protected") }
        java.nio.file.Files.createSymbolicLink(File(root, "link.epub").toPath(), target.toPath())
        SftpDevice(cfg, credentials()).use { remote ->
            assertFalse(remote.inventory().any { it.path == "link.epub" })
            try {
                remote.read("link.epub")
                fail("Symlink read")
            } catch (_: IllegalArgumentException) {}
        }
    }

    @Test
    fun sidecarParentSymlinkRejected() {
        val cfg = config()
        val outside = java.nio.file.Files.createTempDirectory("outside-sidecar").toFile()
        try {
            File(outside, "metadata.epub.lua").writeText("return { percent_finished = 0.4 }")
            val link = File(cfg.directory, "unsafe.sdr")
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
            SftpDevice(cfg, credentials()).use { remote ->
                assertThrows(IllegalArgumentException::class.java) {
                    remote.read("unsafe.sdr/metadata.epub.lua")
                }
            }
            link.delete()
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun adjacentSidecarReadOverRealSftp() {
        val cfg = config()
        val folder = File(cfg.directory, "reading.sdr").apply { mkdir() }
        File(folder, "metadata.epub.lua")
            .writeText(
                "return {percent_finished=0.4,last_xpointer='/body/p[2]',annotations={{text='Quote',note='A note',pos0='x',pos1='y'}}}"
            )
        SftpDevice(cfg, credentials()).use { remote ->
            val snapshot = ReadingRetriever.read(remote, "reading.epub")!!.first
            assertEquals(0.4, snapshot.percent!!, 0.001)
            assertEquals("/body/p[2]", snapshot.position)
            assertEquals("A note", snapshot.annotations.single().note)
        }
    }

    @Test
    fun hostProbeReadsCorrectKeyWithoutAuthentication() {
        val cfg = config()
        val metrics = File(System.getenv("FOLIO_SFTP_AUTH_LOG"))
        val before = metrics.readText()
        val observed = HostFingerprint.read(cfg.host, cfg.port)
        assertEquals(cfg.fingerprint, observed.fingerprint)
        assertEquals(cfg.host, observed.host)
        assertEquals(cfg.port, observed.port)
        assertEquals("ecdsa-sha2-nistp256", observed.algorithm)
        assertEquals(before, metrics.readText())
    }

    @Test
    fun passwordFreeKoreaderModeTransfersWithPinnedHost() {
        val cfg =
            config()
                .copy(username = "koreader-no-password", authentication = "KOReader password-free")
        val file =
            File.createTempFile("folio-password-free", ".epub").apply {
                writeText("temporary access bytes")
                deleteOnExit()
            }
        SftpDevice(cfg, DeviceCredential("")).use { remote ->
            val book = SyncBook(UUID.randomUUID().toString(), "Phone-only", file, Epub.sha256(file))
            val item = SyncPlanner.plan(listOf(book), remote).single()
            SyncEngine(remote).send(item, {})
            assertEquals(book.hash, remote.read(item.path).use { Epub.hash(it) })
            assertEquals(Action.PRESENT, SyncPlanner.plan(listOf(book), remote).single().action)
        }
    }

    @Test
    fun passwordFreeModeStillRejectsWrongHostBeforeAuthentication() {
        val cfg =
            config()
                .copy(
                    username = "koreader-no-password",
                    authentication = "KOReader password-free",
                    fingerprint = "SHA256:" + "A".repeat(43),
                )
        val metrics = File(System.getenv("FOLIO_SFTP_AUTH_LOG"))
        val before = metrics.readText()
        assertThrows(com.jcraft.jsch.JSchException::class.java) {
            SftpDevice(cfg, DeviceCredential("")).close()
        }
        assertEquals(before, metrics.readText())
    }

    @Test
    fun passwordFreeModeCannotBypassAKeyOnlyServer() {
        val cfg = config().copy(authentication = "KOReader password-free")
        assertThrows(com.jcraft.jsch.JSchException::class.java) {
            SftpDevice(cfg, DeviceCredential("")).close()
        }
    }

    @Test
    fun regularPasswordModeStillWorks() {
        val cfg = config().copy(username = "password-test", authentication = "Password")
        SftpDevice(cfg, DeviceCredential("fixture-password")).use { assertNotNull(it.inventory()) }
        assertThrows(com.jcraft.jsch.JSchException::class.java) {
            SftpDevice(cfg, DeviceCredential("wrong-password")).close()
        }
    }
}
