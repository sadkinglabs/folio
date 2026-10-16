package dev.sadking.folio

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sadking.folio.core.*
import dev.sadking.folio.data.*
import dev.sadking.folio.device.*
import dev.sadking.folio.sync.EreaderSync
import dev.sadking.folio.sync.FinishPrompt
import dev.sadking.folio.sync.HardcoverFinishPrompts
import dev.sadking.folio.sync.SyncState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Where the e-reader setup sheet is: nothing yet, installing, done, or failed with [message]. */
data class SetupProgress(val stage: Stage = Stage.IDLE, val message: String = "") {
    enum class Stage {
        IDLE,
        INSTALLING,
        DONE,
        FAILED,
    }
}

/**
 * The e-reader as the Device tab and the Library see it: sync with each e-reader (its state, the
 * Sync button and the answers to its questions), the Hardcover finish prompts, and setting the
 * e-reader up over SSH. SSH is only used to install the plugin; books, notes and statuses go both
 * ways through sync.
 */
class DeviceViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as FolioApplication
    private val runner = app.operations
    private val pairedFolder = PairedFolder(app)
    private val syncState = SyncState.of(app)
    private val finishPrompts = HardcoverFinishPrompts(app)
    val phoneFolder = MutableStateFlow(pairedFolder.label)

    /** Every e-reader that has synced with this phone, most recent first. */
    val ereaders: StateFlow<List<EreaderSync>> =
        syncState.ereaders
            .map { all -> all.values.sortedByDescending { maxOf(it.lastSync, it.syncingSince) } }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                syncState.ereaders.value.values.sortedByDescending {
                    maxOf(it.lastSync, it.syncingSince)
                },
            )

    /** When Sync was last tapped; 0 if never. */
    val syncRequestedAt: StateFlow<Long> = syncState.syncRequestedAt

    /** "You finished … Mark it as read on Hardcover?", oldest first. */
    val prompts: StateFlow<List<FinishPrompt>> = finishPrompts.prompts

    /** When the plugin was last installed from this phone; 0 if never. */
    val pluginInstalledAt = MutableStateFlow(PluginSetup.installedAt(app))

    /** The library folder chosen at the last install. */
    val libraryFolder = MutableStateFlow(PluginSetup.root(app))

    val setup = MutableStateFlow(SetupProgress())

    /**
     * The Sync button: the phone starts listening if it wasn't, and every e-reader is told the
     * phone has changes, so it syncs at its next check (every 5 minutes, and when its Wi-Fi
     * connects).
     */
    fun syncNow() {
        app.link.syncNow()
        syncState.requestSync()
    }

    /** Keeps [option] as the answer to [questionId]; [ereaderId] applies it at its next sync. */
    fun answer(ereaderId: String, questionId: String, option: String) {
        if (syncState.answer(ereaderId, questionId, option)) app.link.syncNow()
    }

    /** The owner answered a Hardcover finish prompt (Not now, or Hardcover took the finish). */
    fun dismissPrompt(bookId: String) = finishPrompts.dismiss(bookId)

    fun choosePhoneFolder(uri: Uri) =
        run(app.getString(R.string.msg_device_connecting_folder)) {
            pairedFolder.select(uri)
            phoneFolder.value = pairedFolder.label
            val added = pairedFolder.refresh()
            app.resources.getQuantityString(R.plurals.msg_device_folder_connected, added, added)
        }

    fun forgetPhoneFolder() =
        run(app.getString(R.string.msg_device_disconnecting_folder)) {
            pairedFolder.forget()
            phoneFolder.value = ""
            app.getString(R.string.msg_device_folder_disconnected)
        }

    fun refreshPhoneFolder() =
        run(app.getString(R.string.msg_device_refreshing_folder)) {
            val added = pairedFolder.refresh()
            app.resources.getQuantityString(R.plurals.msg_device_folder_refreshed, added, added)
        }

    val observedHost = MutableStateFlow<ObservedHostKey?>(null)
    val probeStatus = MutableStateFlow(Operation())
    private val probeGeneration = java.util.concurrent.atomic.AtomicLong()
    val device = MutableStateFlow(app.deviceSettings.load())

    /** Work started from the device screens, shown on the shared status line. */
    private fun run(label: String, work: suspend () -> String) =
        runner.launch(viewModelScope, label, work)

    private fun safeError(e: Exception) =
        dev.sadking.folio.safeError(e, app.resources, device.value.authentication)

    fun probeHost(host: String, port: Int) {
        val generation = probeGeneration.incrementAndGet()
        observedHost.value = null
        probeStatus.value = Operation(true, app.getString(R.string.msg_pairing_reading_fingerprint))
        run(app.getString(R.string.msg_pairing_reading_ssh_fingerprint)) {
            try {
                val observed = HostFingerprint.read(host.trim(), port)
                if (probeGeneration.get() == generation) {
                    observedHost.value = observed
                    probeStatus.value =
                        Operation(message = app.getString(R.string.msg_pairing_fingerprint_confirm))
                }
                app.getString(R.string.msg_pairing_fingerprint_read)
            } catch (e: Exception) {
                if (probeGeneration.get() == generation)
                    probeStatus.value = Operation(message = safeError(e), failed = true)
                throw e
            }
        }
    }

    fun clearHostProbe() {
        probeGeneration.incrementAndGet()
        observedHost.value = null
        probeStatus.value = Operation()
    }

    /** The setup sheet opened or closed: start from the first step. */
    fun resetSetup() {
        clearHostProbe()
        setup.value = SetupProgress()
    }

    /**
     * Sets the e-reader up over SSH: the connection is saved (KOReader's "Login without password"
     * unless the same e-reader is already saved with its own login), then the plugin is installed
     * with setup version 2 and the library folder [root].
     */
    fun setUpEreader(host: String, port: Int, fingerprint: String, root: String) {
        setup.value = SetupProgress(SetupProgress.Stage.INSTALLING)
        run(app.getString(R.string.msg_device_installing_companion)) {
            try {
                val folder =
                    libraryFolderName(root)
                        ?: error(app.getString(R.string.m9sync_setup_folder_invalid))
                val saved = app.deviceSettings.load()
                val same =
                    saved.host == host.trim() &&
                        saved.port == port &&
                        saved.fingerprint == fingerprint
                if (!same) {
                    val config =
                        DeviceConfig(
                            host = host.trim(),
                            port = port,
                            username = "root",
                            directory = "/mnt",
                            fingerprint = fingerprint,
                            authentication = "KOReader password-free",
                        )
                    app.deviceSettings.save(config, "")
                    device.value = config
                }
                val message = BridgeInstaller(app).install(folder)
                pluginInstalledAt.value = PluginSetup.installedAt(app)
                libraryFolder.value = folder
                setup.value = SetupProgress(SetupProgress.Stage.DONE, message)
                message
            } catch (e: Exception) {
                setup.value =
                    SetupProgress(
                        SetupProgress.Stage.FAILED,
                        if (e is CancellationException) "" else safeError(e),
                    )
                throw e
            }
        }
    }

    val folderStatus = MutableStateFlow(Operation())
    val folderListing = MutableStateFlow<FolderListing?>(null)
    private val folderGeneration = java.util.concurrent.atomic.AtomicLong()

    fun clearFolders() {
        folderGeneration.incrementAndGet()
        folderListing.value = null
        folderStatus.value = Operation()
    }

    fun browseFolders(
        config: DeviceConfig,
        password: String,
        key: Uri?,
        relative: String = "",
        create: String? = null,
    ) {
        val generation = folderGeneration.incrementAndGet()
        run(
            app.getString(
                if (create == null) R.string.msg_pairing_reading_folders
                else R.string.msg_pairing_creating_folder
            )
        ) {
            folderStatus.value =
                Operation(
                    busy = true,
                    message = app.getString(R.string.msg_pairing_reading_folders),
                )
            try {
                val saved = app.deviceSettings.load()
                val credential =
                    when (config.authentication) {
                        "KOReader password-free" -> DeviceCredential("")
                        "Password" ->
                            if (password.isNotEmpty()) DeviceCredential(password)
                            else {
                                check(
                                    saved.fingerprint == config.fingerprint &&
                                        saved.username == config.username &&
                                        saved.authentication == config.authentication
                                ) {
                                    app.getString(R.string.msg_pairing_password_first)
                                }
                                app.deviceSettings.credential()
                            }
                        else ->
                            if (key != null) {
                                val bytes =
                                    app.contentResolver.openInputStream(key)?.use { input ->
                                        java.io
                                            .ByteArrayOutputStream()
                                            .also { Epub.copyBounded(input, it, 65536) }
                                            .toByteArray()
                                    } ?: error(app.getString(R.string.msg_pairing_key_unreadable))
                                DeviceCredential(password, bytes)
                            } else {
                                check(
                                    saved.fingerprint == config.fingerprint &&
                                        saved.username == config.username &&
                                        saved.authentication == config.authentication
                                ) {
                                    app.getString(R.string.msg_pairing_key_first)
                                }
                                app.deviceSettings.credential().let {
                                    if (password.isNotEmpty()) it.copy(secret = password) else it
                                }
                            }
                    }
                SftpDevice(config.copy(directory = "/mnt/us"), credential).use { remote ->
                    if (create != null) remote.createDirectory(relative, create)
                    val dirs = remote.directories(relative)
                    if (folderGeneration.get() == generation)
                        folderListing.value = FolderListing(config, relative, dirs)
                }
                if (folderGeneration.get() == generation) folderStatus.value = Operation()
                app.getString(
                    if (create == null) R.string.msg_pairing_folders_loaded
                    else R.string.msg_pairing_folder_created
                )
            } catch (e: Exception) {
                if (folderGeneration.get() == generation)
                    folderStatus.value = Operation(message = safeError(e), failed = true)
                throw e
            }
        }
    }

    fun saveDevice(
        config: DeviceConfig,
        password: String,
        keyUri: Uri? = null,
        onSaved: () -> Unit = {},
    ) =
        run(app.getString(R.string.msg_pairing_saving)) {
            val keyBytes =
                keyUri
                    ?.takeIf { config.authentication == "Key" }
                    ?.let { uri ->
                        app.contentResolver.openInputStream(uri)?.use { input ->
                            java.io
                                .ByteArrayOutputStream()
                                .also { Epub.copyBounded(input, it, 65536) }
                                .toByteArray()
                        } ?: error(app.getString(R.string.msg_pairing_key_unreadable))
                    }
            app.deviceSettings.save(config, password, keyBytes)
            device.value = config
            observedHost.value = null
            withContext(Dispatchers.Main) { onSaved() }
            app.getString(R.string.msg_pairing_saved)
        }

    fun forgetDevice() =
        run(app.getString(R.string.msg_pairing_removing)) {
            app.deviceSettings.forget()
            device.value = DeviceConfig()
            app.getString(R.string.msg_pairing_removed)
        }
}

data class FolderListing(
    val config: DeviceConfig,
    val relative: String,
    val directories: List<DeviceDirectory>,
)
