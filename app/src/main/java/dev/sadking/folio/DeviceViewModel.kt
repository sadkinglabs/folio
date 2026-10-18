package dev.sadking.folio

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sadking.folio.core.*
import dev.sadking.folio.data.*
import dev.sadking.folio.device.*
import dev.sadking.folio.sync.EreaderSync
import dev.sadking.folio.sync.HardcoverItemState
import dev.sadking.folio.sync.HardcoverSync
import dev.sadking.folio.sync.HardcoverSyncStatus
import dev.sadking.folio.sync.MatchCandidateView
import dev.sadking.folio.sync.MatchCheck
import dev.sadking.folio.sync.StatusQuestion
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
 * Sync as the Device tab, the Library and Settings see it: sync with each e-reader (its state, the
 * Sync button and the answers to its questions), sync with Hardcover (its status line, the matches
 * and status questions waiting for the owner), and setting the e-reader up over SSH. SSH is only
 * used to install the plugin; books, notes and statuses go both ways through sync.
 */
class DeviceViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as FolioApplication
    private val runner = app.operations
    private val pairedFolder = PairedFolder(app)
    private val syncState = SyncState.of(app)
    private val hardcover = HardcoverSync.get(app)
    val phoneFolder = MutableStateFlow(pairedFolder.label)
    private val dao = app.database.library()

    /**
     * Every device that has synced or sent a position (sync e-readers, Progress sync reports, the
     * old SSH positions), after the owner's names, merges and forgets.
     */
    val directory: StateFlow<DeviceRoster> =
        deviceRosterFlow(syncState.ereaders, dao) { app.link.settings.phoneDeviceId }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, DeviceRoster.EMPTY)

    /**
     * Every e-reader that has synced with this phone, most recent first, under the name Librillo
     * shows for it.
     */
    val ereaders: StateFlow<List<EreaderSync>> =
        combine(syncState.ereaders, directory) { all, devices ->
                all.values.map(devices::named).sortedByDescending {
                    maxOf(it.lastSync, it.syncingSince)
                }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                syncState.ereaders.value.values.sortedByDescending {
                    maxOf(it.lastSync, it.syncingSince)
                },
            )

    /** The Device tab's sync cards: one per device, forgotten ones left out. */
    val ereaderCards: StateFlow<List<EreaderSync>> =
        combine(syncState.ereaders, directory) { all, devices ->
                devices.syncCards(all.values.toList())
            }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                syncState.ereaders.value.values.sortedByDescending {
                    maxOf(it.lastSync, it.syncingSince)
                },
            )

    /** [key]'s device is called [name] everywhere; a blank name goes back to its own. */
    fun renameDevice(key: String, name: String) =
        changeDevice(key) { _, device, now ->
            val profile = DeviceProfiles.rename(device, dao.deviceProfile(device.key), name, now)
            profile to
                if (profile.displayName.isEmpty())
                    app.getString(R.string.dev_name_reset, device.name)
                else app.getString(R.string.dev_renamed, profile.displayName)
        }

    /** "This is the same e-reader as …": [key]'s device shows as [into]'s from now on. */
    fun mergeDevice(key: String, into: String) =
        changeDevice(key) { devices, device, now ->
            val profile =
                DeviceProfiles.merge(devices, device, dao.deviceProfile(device.key), into, now)
                    ?: error(app.getString(R.string.dev_same_already))
            profile to
                app.getString(
                    R.string.dev_merged,
                    deviceLabel(device),
                    devices.device(into)?.let(::deviceLabel).orEmpty(),
                )
        }

    /** "Show separately": [memberKey] is its own device again. */
    fun separateDevice(memberKey: String) =
        changeDevice(memberKey) { devices, _, now ->
            val member = devices.all.flatMap { it.members }.firstOrNull { it.key == memberKey }
            val label =
                member?.name?.takeIf { it.isNotBlank() }
                    ?: app.getString(
                        if (member?.old == true) R.string.dev_old_name else R.string.dev_unnamed
                    )
            DeviceProfiles.separate(memberKey, dao.deviceProfile(memberKey), now) to
                app.getString(R.string.dev_separated, label)
        }

    /**
     * "Forget this device": hidden from Progress and the Device tab until it reports again. Its
     * reports, positions and snapshots stay in the database, and in backups.
     */
    fun forgetEreader(key: String) =
        changeDevice(key) { _, device, now ->
            DeviceProfiles.forget(device, dao.deviceProfile(device.key), now) to
                app.getString(R.string.dev_forgotten, deviceLabel(device))
        }

    /** A device's name for a message: the owner's or its own, else what the screens call it. */
    private fun deviceLabel(device: KnownDevice) =
        device.name.ifBlank {
            app.getString(if (device.old) R.string.dev_old_name else R.string.dev_unnamed)
        }

    /**
     * Saves the profile [change] makes for [key]'s device, says so on the app's message line, and
     * offers Undo, which puts the profile back as it was.
     */
    private fun changeDevice(
        key: String,
        change: suspend (DeviceRoster, KnownDevice, Long) -> Pair<DeviceProfile, String>,
    ) =
        runner.launchUndoable(viewModelScope, app.getString(R.string.dev_saving)) {
            val devices = directory.value
            val device = devices.device(key) ?: error(app.getString(R.string.dev_gone))
            val (profile, message) = change(devices, device, System.currentTimeMillis())
            val before = dao.deviceProfile(profile.deviceKey)
            dao.saveDeviceProfile(profile)
            val undo: () -> Unit = {
                viewModelScope.launch(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    dao.saveDeviceProfile(
                        before?.copy(updatedAt = now)
                            ?: DeviceProfile(profile.deviceKey, updatedAt = now)
                    )
                }
            }
            message to undo
        }

    /** When Sync was last tapped; 0 if never. */
    val syncRequestedAt: StateFlow<Long> = syncState.syncRequestedAt

    /** Hardcover sync for Settings' switch and status line. */
    val hardcoverStatus: StateFlow<HardcoverSyncStatus> = hardcover.status

    /** "Check these matches": items Librillo could not match to Hardcover for sure. */
    val hardcoverChecks: StateFlow<List<MatchCheck>> =
        hardcover.checks.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Books whose status changed on both Librillo and Hardcover since they last agreed. */
    val hardcoverQuestions: StateFlow<List<StatusQuestion>> =
        hardcover.questions.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Book and catalogue entry ids to what their Hardcover tile says. */
    val hardcoverItemStates: StateFlow<Map<String, HardcoverItemState>> =
        hardcover.itemStates.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** A Hardcover action that could not be done, in a sentence for the snackbar. */
    val hardcoverNotices = MutableSharedFlow<String>(extraBufferCapacity = 4)

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

    /** "Sync with Hardcover". Turning it on starts a sync. */
    fun setHardcoverSync(on: Boolean) {
        hardcover.enabled = on
    }

    /** Settings' Sync now, and after connecting an account. */
    fun syncHardcoverNow() = hardcover.syncNow()

    /**
     * A pick that waits for the owner: the Hardcover book is already matched to something else.
     * Needs your decision shows it on the item's card with "Move the match here" and "Keep it
     * there".
     */
    val matchOffer = MutableStateFlow<MoveOffer?>(null)

    /**
     * "This one" in Check these matches. A Hardcover book already matched elsewhere becomes
     * [matchOffer]; any other refusal is said in a sentence ([hardcoverNotices]).
     */
    fun pickMatch(check: MatchCheck, candidate: MatchCandidateView) = hardcoverAction {
        matchOffer.value = null
        try {
            hardcover.pick(check.itemId, candidate)
        } catch (e: HardcoverLinkTaken) {
            // The card says it, with what to do about it; no message on top of that.
            matchOffer.value =
                MoveOffer(
                    check.itemId,
                    candidate.hardcoverId,
                    candidate.title,
                    candidate.authors,
                    e.holder,
                )
        }
    }

    /** "Move the match here": the offer's item takes the Hardcover book from its holder. */
    fun moveMatch(offer: MoveOffer) = hardcoverAction {
        matchOffer.value = null
        hardcover.pick(
            offer.itemId,
            MatchCandidateView(offer.hardcoverId, offer.title, offer.authors, null, null),
            move = true,
        )
        hardcoverNotices.tryEmit(app.getString(R.string.hc_link_moved, offer.holder.title))
    }

    /** "Keep it there": the match stays where it is; the item's card stays to check. */
    fun keepMatch() {
        matchOffer.value = null
    }

    /** "Not on Hardcover": never matched or asked again. */
    fun notOnHardcover(check: MatchCheck) = hardcoverAction {
        hardcover.notOnHardcover(check.itemId)
    }

    /**
     * A status question answered: [useHardcover] true takes Hardcover's status, false Librillo's.
     */
    fun answerHardcover(question: StatusQuestion, useHardcover: Boolean) = hardcoverAction {
        hardcover.answer(question, useHardcover)
    }

    /** "Use Hardcover for all" or "Use Librillo for all". */
    fun answerAllHardcover(useHardcover: Boolean) = hardcoverAction {
        hardcover.answerAll(useHardcover)
    }

    /**
     * Runs [work] off the main thread. An IllegalArgumentException carries a sentence for the owner
     * (HardcoverSync's contract); anything else gets a fixed one, so no book content is shown.
     */
    private fun hardcoverAction(work: suspend () -> Unit) = viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) { work() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            hardcoverNotices.tryEmit(e.message ?: app.getString(R.string.hc10_action_failed))
        } catch (e: Exception) {
            hardcoverNotices.tryEmit(app.getString(R.string.hc10_action_failed))
        }
    }

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
