package dev.sadking.folio.device

import com.jcraft.jsch.*
import dev.sadking.folio.core.*
import java.io.*

/** No shell commands. Auth only proceeds after a pinned host-key match. */
class SftpDevice(config: DeviceConfig, credential: DeviceCredential) : DeviceFiles {
    private val session: Session
    private val channel: ChannelSftp
    private val root: String

    init {
        config.validate()
        val jsch = JSch()
        jsch.setHostKeyRepository(
            object : HostKeyRepository {
                override fun check(host: String?, key: ByteArray): Int {
                    val actual = sshFingerprint(key)
                    return if (actual == config.fingerprint.trimEnd('=')) HostKeyRepository.OK
                    else HostKeyRepository.CHANGED
                }

                override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit

                override fun remove(host: String?, type: String?) = Unit

                override fun remove(host: String?, type: String?, key: ByteArray?) = Unit

                override fun getKnownHostsRepositoryID() = "Folio pinned SHA256"

                override fun getHostKey(): Array<HostKey> = emptyArray()

                override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
            }
        )
        if (config.authentication == "Key") {
            val privateKey = credential.privateKey ?: error("Private key unavailable; pair again")
            jsch.addIdentity(
                "folio",
                privateKey,
                null,
                credential.secret.toByteArray().takeIf { it.isNotEmpty() },
            )
        }
        session =
            jsch.getSession(config.username, config.host, config.port).apply {
                setConfig("StrictHostKeyChecking", "yes")
                setConfig(
                    "PreferredAuthentications",
                    if (config.authentication == "Key") "publickey" else "password",
                )
                if (config.authentication != "Key")
                    setPassword(config.passwordFor(credential).toByteArray())
                timeout = 20000
                setServerAliveInterval(10000)
            }
        try {
            session.connect(20000)
            channel = (session.openChannel("sftp") as ChannelSftp).apply { connect(20000) }
            // The root must already exist; configuration never creates arbitrary directories.
            val attrs = channel.lstat(config.directory)
            require(attrs.isDir && !attrs.isLink) {
                "Kindle library folder must be an existing real directory"
            }
            root = channel.realpath(config.directory).trimEnd('/')
        } catch (e: Exception) {
            session.disconnect()
            throw e
        }
    }

    private fun path(relative: String): String {
        require(
            relative.isNotEmpty() &&
                !relative.startsWith('/') &&
                !relative.contains('\\') &&
                relative.split('/').none { it == ".." || it == "." || it.isEmpty() }
        )
        // Sidecars introduce nested reads: every parent must remain a real directory.
        var parent = root
        relative.split('/').dropLast(1).forEach { component ->
            parent += "/$component"
            val attributes = channel.lstat(parent)
            require(attributes.isDir && !attributes.isLink) {
                "Remote parent symlink or non-directory rejected"
            }
        }
        return "$root/$relative"
    }

    override fun exists(path: String): Boolean =
        try {
            val a = channel.lstat(path(path))
            require(!a.isLink) { "Remote symlink rejected" }
            true
        } catch (e: SftpException) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) false else throw e
        }

    override fun read(path: String): InputStream {
        val a = channel.lstat(path(path))
        require(!a.isLink && !a.isDir && a.size <= Epub.MAX_FILE) {
            "Remote resource is unsafe or too large"
        }
        return channel.get(path(path))
    }

    override fun inventory(): List<RemoteEntry> {
        val result = mutableListOf<RemoteEntry>()
        var visited = 0
        fun walk(relative: String, depth: Int) {
            require(depth <= 12) { "Device folder is too deeply nested" }
            val location = if (relative.isEmpty()) root else path(relative)
            for (raw in channel.ls(location)) {
                val e = raw as ChannelSftp.LsEntry
                if (
                    e.filename == "." ||
                        e.filename == ".." ||
                        e.attrs.isLink ||
                        e.filename.startsWith('.')
                )
                    continue
                require(++visited <= 10000) { "Device folder contains too many entries" }
                val p = if (relative.isEmpty()) e.filename else "$relative/${e.filename}"
                if (e.attrs.isDir) {
                    if (!e.filename.endsWith(".sdr")) walk(p, depth + 1)
                } else if (e.filename.endsWith(".epub", true))
                    result += RemoteEntry(p, e.attrs.size)
            }
        }
        walk("", 0)
        return result
    }

    fun directories(relative: String = ""): List<DeviceDirectory> {
        val location = if (relative.isEmpty()) root else path(relative)
        val attrs = channel.lstat(location)
        require(attrs.isDir && !attrs.isLink) { "Choose an existing real directory" }
        val entries = channel.ls(location)
        require(entries.size <= 10000) { "Folder contains too many entries" }
        return entries
            .map { it as ChannelSftp.LsEntry }
            .filter { e ->
                e.attrs.isDir &&
                    !e.attrs.isLink &&
                    !e.filename.startsWith('.') &&
                    e.filename !in listOf(".", "..") &&
                    !e.filename.contains('/') &&
                    !e.filename.contains('\\')
            }
            .map { e ->
                DeviceDirectory(
                    if (relative.isEmpty()) e.filename else "$relative/${e.filename}",
                    e.filename,
                )
            }
            .sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
    }

    fun createDirectory(parent: String, name: String) {
        require(
            name.isNotBlank() &&
                name.length <= 120 &&
                !name.startsWith('.') &&
                name.none { it == '/' || it == '\\' || it.code < 32 }
        ) {
            "Use a folder name without slashes or control characters"
        }
        val parentPath = if (parent.isEmpty()) root else path(parent)
        val attrs = channel.lstat(parentPath)
        require(attrs.isDir && !attrs.isLink) { "Parent must be an existing real directory" }
        val relative = if (parent.isEmpty()) name else "$parent/$name"
        if (!exists(relative)) channel.mkdir(path(relative))
        val created = channel.lstat(path(relative))
        require(created.isDir && !created.isLink) { "A different resource already uses that name" }
    }

    override fun upload(path: String, source: File, progress: (Long, Long) -> Unit) {
        check(!exists(path)) { "Temporary destination exists" }
        var sent = 0L
        source.inputStream().use { input ->
            channel.put(
                input,
                path(path),
                object : SftpProgressMonitor {
                    override fun init(op: Int, src: String?, dest: String?, max: Long) = Unit

                    override fun count(count: Long): Boolean {
                        sent += count
                        progress(sent, source.length())
                        return true
                    }

                    override fun end() = Unit
                },
                ChannelSftp.OVERWRITE,
            )
        }
    }

    override fun publish(temporary: String, destination: String) {
        check(!exists(destination)) { "Destination already exists" }
        // SFTP v3 rename should reject an existing target; server behavior needs device validation.
        channel.rename(path(temporary), path(destination))
    }

    override fun removeTemporary(path: String) {
        require(path.matches(Regex("\\.folio-[a-f0-9-]+\\.part")))
        channel.rm(path(path))
    }

    /** Stages a whole companion before directory publication; never modifies other plugins. */
    fun installFolioBridge(files: Map<String, File>) {
        // Plain file names only (no folders), and the two files KOReader needs to load a plugin.
        val names = files.keys
        require(
            names.containsAll(setOf("main.lua", "_meta.lua")) &&
                names.size <= 20 &&
                names.all { it.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*")) } &&
                files.values.all { it.isFile && it.length() in 1..(512 * 1024) }
        )
        val nonce = java.util.UUID.randomUUID().toString()
        val staged = ".folio-install-$nonce"
        val backup = ".folio-backup-$nonce"
        val destination = "folio.koplugin"
        check(!exists(staged) && !exists(backup))
        channel.mkdir(path(staged))
        var archived = false
        var published = false
        try {
            files.forEach { (name, source) ->
                val temporary = ".folio-${java.util.UUID.randomUUID()}.part"
                try {
                    upload(temporary, source) { _, _ -> }
                    check(read(temporary).use { Epub.hash(it) } == Epub.sha256(source))
                    publish(temporary, "$staged/$name")
                } finally {
                    runCatching { removeTemporary(temporary) }
                }
            }
            if (exists(destination)) {
                val attrs = channel.lstat(path(destination))
                require(attrs.isDir && !attrs.isLink) {
                    "Existing Folio companion is not a real directory"
                }
                channel.rename(path(destination), path(backup))
                archived = true
            }
            try {
                channel.rename(path(staged), path(destination))
                published = true
            } catch (failure: Exception) {
                if (archived && !exists(destination))
                    channel.rename(path(backup), path(destination))
                throw failure
            }
        } finally {
            if (!published) {
                names.forEach { name ->
                    runCatching { if (exists("$staged/$name")) channel.rm(path("$staged/$name")) }
                }
                runCatching { channel.rmdir(path(staged)) }
            }
        }
    }

    override fun close() {
        channel.disconnect()
        session.disconnect()
    }
}

data class DeviceDirectory(val relativePath: String, val name: String)
