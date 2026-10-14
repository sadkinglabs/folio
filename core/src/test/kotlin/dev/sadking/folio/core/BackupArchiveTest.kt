package dev.sadking.folio.core

import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.*
import org.junit.Assert.*
import org.junit.Test

class BackupArchiveTest {
    private fun folder() = Files.createTempDirectory("folio-backup-test").toFile()

    @Test
    fun portableRoundTripChecksEveryResource() {
        val root = folder()
        try {
            val file = File(root, "original.epub").apply { writeText("test epub bytes") }
            val name = "library/${UUID.randomUUID()}/original.epub"
            val zip = File(root, "backup.zip")
            BackupArchive.write(zip, "{\"version\":1}".toByteArray(), mapOf(name to file))
            val stage = File(root, "stage")
            val manifest = BackupArchive.unpack(zip, stage)
            assertEquals("{\"version\":1}", manifest.toString(Charsets.UTF_8))
            assertArrayEquals(file.readBytes(), File(stage, name).readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun malformed(entries: Map<String, ByteArray>, root: File): File =
        File(root, "bad.zip").also { f ->
            ZipOutputStream(f.outputStream()).use { zip ->
                val checked = entries.toMutableMap()
                val manifest = checked["manifest.json"] ?: byteArrayOf()
                val existing = checked["checksums.tsv"]?.toString(Charsets.UTF_8).orEmpty()
                checked["checksums.tsv"] =
                    (Epub.hash(manifest.inputStream()) + "\tmanifest.json\n" + existing)
                        .toByteArray()
                checked.forEach { (n, b) ->
                    zip.putNextEntry(ZipEntry(n))
                    zip.write(b)
                    zip.closeEntry()
                }
            }
        }

    @Test
    fun pathTraversalRejectedAndStageRemoved() {
        val root = folder()
        try {
            val zip =
                malformed(
                    mapOf(
                        "manifest.json" to "{}".toByteArray(),
                        "checksums.tsv" to byteArrayOf(),
                        "../outside" to byteArrayOf(1),
                    ),
                    root,
                )
            val stage = File(root, "stage")
            assertThrows(Exception::class.java) { BackupArchive.unpack(zip, stage) }
            assertFalse(stage.exists())
            assertFalse(File(root, "outside").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun tamperedBytesRejected() {
        val root = folder()
        try {
            val name = "library/${UUID.randomUUID()}/original.epub"
            val zip =
                malformed(
                    mapOf(
                        "manifest.json" to "{}".toByteArray(),
                        "checksums.tsv" to ("0".repeat(64) + "\t$name").toByteArray(),
                        name to "tampered".toByteArray(),
                    ),
                    root,
                )
            assertThrows(Exception::class.java) { BackupArchive.unpack(zip, File(root, "stage")) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun uncheckedExtraResourceRejected() {
        val root = folder()
        try {
            val name = "library/${UUID.randomUUID()}/original.epub"
            val zip =
                malformed(
                    mapOf(
                        "manifest.json" to "{}".toByteArray(),
                        "checksums.tsv" to byteArrayOf(),
                        name to byteArrayOf(1),
                    ),
                    root,
                )
            assertThrows(Exception::class.java) { BackupArchive.unpack(zip, File(root, "stage")) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cannotExtractOverExistingContent() {
        val root = folder()
        try {
            val zip = File(root, "backup.zip")
            BackupArchive.write(zip, "{}".toByteArray(), emptyMap())
            val stage = File(root, "stage").apply { mkdir() }
            File(stage, "existing").writeText("keep")
            assertThrows(Exception::class.java) { BackupArchive.unpack(zip, stage) }
            assertEquals("keep", File(stage, "existing").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun manifestCorruptionIsDetected() {
        val root = folder()
        try {
            val zip = File(root, "corrupt-metadata.zip")
            ZipOutputStream(zip.outputStream()).use { out ->
                out.putNextEntry(ZipEntry("manifest.json"))
                out.write("{\"title\":\"Changed\"}".toByteArray())
                out.closeEntry()
                out.putNextEntry(ZipEntry("checksums.tsv"))
                out.write(
                    (Epub.hash("{\"title\":\"Original\"}".byteInputStream()) + "\tmanifest.json")
                        .toByteArray()
                )
                out.closeEntry()
            }
            assertThrows(Exception::class.java) { BackupArchive.unpack(zip, File(root, "stage")) }
        } finally {
            root.deleteRecursively()
        }
    }
}
