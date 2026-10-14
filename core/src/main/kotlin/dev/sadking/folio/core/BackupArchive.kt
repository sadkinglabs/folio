package dev.sadking.folio.core

import java.io.*
import java.util.zip.*

/**
 * Portable archive: opaque, versioned JSON plus checked library resources. Never extracts in place.
 */
object BackupArchive {
    const val MAX_TOTAL = 4L * 1024 * 1024 * 1024

    private fun safe(name: String): Boolean =
        name.matches(Regex("library/[a-f0-9-]{36}/[a-zA-Z0-9.-]+")) && !name.contains("..")

    fun write(destination: File, manifest: ByteArray, files: Map<String, File>) {
        require(manifest.size <= 16 * 1024 * 1024 && files.size <= 100000)
        require(files.keys.all(::safe))
        var total = 0L
        ZipOutputStream(destination.outputStream()).use { out ->
            fun put(name: String, bytes: ByteArray) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
            put("manifest.json", manifest)
            val sums =
                files
                    .toSortedMap()
                    .map { (name, file) ->
                        total += file.length()
                        require(total <= MAX_TOTAL && file.length() <= Epub.MAX_FILE)
                        "${Epub.sha256(file)}\t$name"
                    }
                    .joinToString("\n")
            put(
                "checksums.tsv",
                (Epub.hash(manifest.inputStream()) + "\tmanifest.json\n" + sums).toByteArray(),
            )
            files.toSortedMap().forEach { (name, file) ->
                out.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }

    fun unpack(archive: File, stage: File): ByteArray {
        require(!stage.exists() || stage.listFiles().orEmpty().isEmpty())
        stage.mkdirs()
        try {
            ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().toList()
                require(
                    entries.size <= 100002 &&
                        entries.map { it.name }.distinct().size == entries.size
                ) {
                    "Duplicate or excessive archive entries"
                }
                require(
                    entries.all {
                        !it.isDirectory &&
                            (safe(it.name) || it.name in listOf("manifest.json", "checksums.tsv"))
                    }
                ) {
                    "Unsafe backup path"
                }
                fun bytes(name: String, limit: Long): ByteArray {
                    val entry = zip.getEntry(name) ?: error("Incomplete backup")
                    return ByteArrayOutputStream()
                        .also { out ->
                            zip.getInputStream(entry).use { Epub.copyBounded(it, out, limit) }
                        }
                        .toByteArray()
                }
                val manifest = bytes("manifest.json", 16L * 1024 * 1024)
                val sums =
                    bytes("checksums.tsv", 16L * 1024 * 1024)
                        .toString(Charsets.UTF_8)
                        .lines()
                        .filter { it.isNotEmpty() }
                        .map { line ->
                            val pair = line.split('\t')
                            require(
                                pair.size == 2 &&
                                    pair[0].matches(Regex("[a-f0-9]{64}")) &&
                                    (safe(pair[1]) || pair[1] == "manifest.json")
                            )
                            pair[1] to pair[0]
                        }
                require(sums.map { it.first }.distinct().size == sums.size)
                require(
                    entries.map { it.name }.toSet() ==
                        sums.map { it.first }.toSet() + setOf("checksums.tsv")
                ) {
                    "Incomplete backup checksums"
                }
                require(
                    sums.firstOrNull { it.first == "manifest.json" }?.second ==
                        Epub.hash(manifest.inputStream())
                ) {
                    "Backup metadata checksum mismatch"
                }
                var total = 0L
                for ((name, hash) in sums.filter { it.first != "manifest.json" }) {
                    val file = File(stage, name)
                    file.parentFile.mkdirs()
                    zip.getInputStream(zip.getEntry(name)).use { input ->
                        file.outputStream().use {
                            Epub.copyBounded(input, it, minOf(Epub.MAX_FILE, MAX_TOTAL - total))
                        }
                    }
                    total += file.length()
                    require(Epub.sha256(file) == hash) { "Backup checksum mismatch" }
                }
                return manifest
            }
        } catch (e: Exception) {
            stage.deleteRecursively()
            throw e
        }
    }
}
