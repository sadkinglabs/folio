package dev.sadking.folio

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.sadking.folio.core.Epub
import dev.sadking.folio.data.*
import dev.sadking.folio.device.HardcoverSettings
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DiscoveryState(
    val busy: Boolean = false,
    val message: String = "",
    val failed: Boolean = false,
)

class DiscoveryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as FolioApplication
    private val dao = app.database.library()
    private val settings = HardcoverSettings(app)
    private val lookup = MetadataLookup()
    private val hardcover = HardcoverClient()
    private val lock = Mutex()
    val wishes =
        dao.wishes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val wishMemberships =
        dao.wishMemberships()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val observations =
        dao.hardcoverObservations()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val accountId = MutableStateFlow(settings.accountId)
    val profile = MutableStateFlow<List<HardcoverLibraryBook>>(emptyList())
    val profileMore = MutableStateFlow(false)
    private var profileAccount: Int? = null
    private var profileCursor = 0
    val state = MutableStateFlow(DiscoveryState())
    val query = MutableStateFlow("")
    val candidates = MutableStateFlow<List<MetadataCandidate>>(emptyList())
    val matches = MutableStateFlow<List<HardcoverMatch>>(emptyList())
    val username = MutableStateFlow(settings.username)
    val cover = MutableStateFlow<String?>(null)
    val titleResults = MutableStateFlow<List<HardcoverMatch>>(emptyList())
    val titleProposal = MutableStateFlow<HardcoverBookData?>(null)
    val titleCover = MutableStateFlow<String?>(null)

    private fun msg(id: Int, vararg args: Any) =
        if (args.isEmpty()) app.getString(id) else app.getString(id, *args)

    private fun plural(id: Int, n: Int) = app.resources.getQuantityString(id, n, n)

    fun clearTitleLookup() {
        titleResults.value = emptyList()
        titleProposal.value = null
        titleCover.value?.let { File(it).delete() }
        titleCover.value = null
    }

    fun searchTitle(text: String) =
        run(msg(R.string.msg_discovery_searching_hardcover)) {
            clearTitleLookup()
            titleResults.value = hardcover.search(settings.token(), text.trim().take(300))
            if (titleResults.value.isEmpty()) msg(R.string.msg_discovery_no_title_matches)
            else msg(R.string.msg_discovery_choose_book)
        }

    fun selectTitle(match: HardcoverMatch) =
        run(msg(R.string.msg_discovery_fetching)) {
            titleProposal.value = null
            titleCover.value?.let { File(it).delete() }
            titleCover.value = null
            val value = hardcover.bookMetadata(settings.token(), match.id)
            val coverBytes =
                value.coverUrl?.let { runCatching { HardcoverCover().load(it) }.getOrNull() }
            if (coverBytes != null) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size, bounds)
                if (
                    bounds.outWidth in 1..12000 &&
                        bounds.outHeight in 1..12000 &&
                        bounds.outWidth.toLong() * bounds.outHeight <= 40000000
                )
                    titleCover.value =
                        File(app.cacheDir, "hardcover-preview-${UUID.randomUUID()}")
                            .also { it.writeBytes(coverBytes) }
                            .absolutePath
            }
            titleProposal.value = value
            msg(R.string.msg_discovery_review_before_adding)
        }

    fun saveTitle(onSaved: (WishBook) -> Unit) =
        run(msg(R.string.msg_discovery_adding)) {
            val value =
                titleProposal.value ?: error(msg(R.string.msg_discovery_choose_result_first))
            val wish =
                app.repository.lock.withLock {
                    app.database.withTransaction {
                        val existing = dao.allStoredWishes().filter { it.hardcoverId == value.id }
                        require(existing.size <= 1) { msg(R.string.msg_discovery_duplicates) }
                        val old = existing.singleOrNull()
                        if (old != null) {
                            require(dao.removedEntry(old.id) == null) {
                                msg(R.string.msg_discovery_in_recently_removed)
                            }
                            old
                        } else {
                            val id = UUID.randomUUID().toString()
                            val folder = File(app.repository.root, id)
                            try {
                                val cover =
                                    titleCover.value
                                        ?.let(::File)
                                        ?.takeIf { it.isFile }
                                        ?.let { source ->
                                            check(folder.mkdirs())
                                            source
                                                .copyTo(File(folder, "hardcover-cover"))
                                                .absolutePath
                                        }
                                val entry =
                                    WishBook(
                                        id,
                                        "hardcover:${value.id}",
                                        value.metadata.title,
                                        encode(value.metadata.authors),
                                        cover,
                                        value.id,
                                        value.metadata.title,
                                        details = metadataJson(value.metadata).toString(),
                                    )
                                parseWish(wishJson(entry))
                                require(dao.insertWish(entry) != -1L) {
                                    msg(R.string.msg_discovery_already_in_library)
                                }
                                entry
                            } catch (e: Exception) {
                                folder.deleteRecursively()
                                throw e
                            }
                        }
                    }
                }
            withContext(Dispatchers.Main) { onSaved(wish) }
            msg(R.string.msg_discovery_added)
        }

    val matchWishId = MutableStateFlow<String?>(null)

    val readingBookId = MutableStateFlow<String?>(null)
    val hardcoverReading = MutableStateFlow<HardcoverReading?>(null)
    val hardcoverEditions = MutableStateFlow<List<HardcoverEdition>>(emptyList())
    val notices = MutableSharedFlow<Operation>(extraBufferCapacity = 8)

    fun openReading(book: Book) =
        run(msg(R.string.msg_discovery_loading_tracking)) {
            readingBookId.value = book.id
            hardcoverReading.value = null
            hardcoverEditions.value = emptyList()
            matches.value = emptyList()
            matchWishId.value = null
            if (settings.username.isBlank()) return@run msg(R.string.msg_discovery_connect_to_track)
            val link =
                dao.allWishes().firstOrNull {
                    (it.linkedBookId == book.id || it.id == book.id) && it.hardcoverId != null
                }
            if (link == null) {
                matches.value =
                    hardcover.search(
                        settings.token(),
                        "${book.title} ${book.authorLabel}".take(300),
                    )
                msg(R.string.msg_discovery_confirm_to_track)
            } else {
                loadReading(link.hardcoverId!!)
                msg(R.string.msg_discovery_tracking_loaded)
            }
        }

    private suspend fun loadReading(id: Int) {
        val token = settings.token()
        val me = hardcover.account(token)
        val value = hardcover.reading(token, id)
        hardcoverReading.value = value
        hardcoverEditions.value = hardcover.editions(token, id)
        accountId.value = me.id
        settings.accountId = me.id
        if (value.userBookId != null && value.status != null)
            dao.hardcoverObservation(
                HardcoverObservation(
                    me.id,
                    id,
                    value.status,
                    value.pages,
                    value.editionId,
                    System.currentTimeMillis(),
                )
            )
        else dao.deleteHardcoverObservation(me.id, id)
    }

    fun currentReading(book: Book) =
        run(msg(R.string.msg_discovery_updating_currently_reading)) {
            val link =
                dao.allWishes().firstOrNull {
                    (it.linkedBookId == book.id || it.id == book.id) && it.hardcoverId != null
                } ?: error(msg(R.string.msg_discovery_link_first))
            val result = hardcover.currentlyReading(settings.token(), link.hardcoverId!!)
            loadReading(link.hardcoverId)
            result
        }

    fun updateReading(
        book: Book,
        edition: HardcoverEdition,
        pages: Int,
        expected: HardcoverReading,
        allowNew: Boolean,
        allowDecrease: Boolean,
        startedOn: java.time.LocalDate? = null,
    ) =
        run(msg(R.string.msg_discovery_updating_progress)) {
            val link =
                dao.allWishes().firstOrNull {
                    (it.linkedBookId == book.id || it.id == book.id) && it.hardcoverId != null
                } ?: error(msg(R.string.msg_discovery_link_first))
            val result =
                hardcover.progress(
                    settings.token(),
                    link.hardcoverId!!,
                    edition.id,
                    pages,
                    expected,
                    allowNew,
                    allowDecrease,
                    startedOn = startedOn,
                )
            loadReading(link.hardcoverId)
            result
        }

    /** Ends the read in progress on Hardcover on [finishedOn] and marks the book Read. */
    fun finishReading(book: Book, finishedOn: java.time.LocalDate, expected: HardcoverReading) =
        run(msg(R.string.msg_discovery_updating_progress)) {
            val link =
                dao.allWishes().firstOrNull {
                    (it.linkedBookId == book.id || it.id == book.id) && it.hardcoverId != null
                } ?: error(msg(R.string.msg_discovery_link_first))
            val result =
                hardcover.finish(settings.token(), link.hardcoverId!!, finishedOn, expected)
            loadReading(link.hardcoverId)
            result
        }

    fun clearReading() {
        readingBookId.value = null
        hardcoverReading.value = null
        hardcoverEditions.value = emptyList()
        matches.value = emptyList()
    }

    private fun run(label: String, work: suspend () -> String) = viewModelScope.launch {
        lock.withLock {
            state.value = DiscoveryState(true, label)
            try {
                state.value = DiscoveryState(message = withContext(Dispatchers.IO) { work() })
                notices.emit(Operation(message = state.value.message, failed = state.value.failed))
            } catch (e: CancellationException) {
                state.value = DiscoveryState(message = msg(R.string.msg_discovery_cancelled))
                throw e
            } catch (e: Exception) {
                state.value =
                    DiscoveryState(
                        message =
                            when (e) {
                                is java.net.SocketTimeoutException ->
                                    msg(R.string.msg_discovery_timed_out)
                                is java.net.UnknownHostException ->
                                    msg(R.string.msg_discovery_offline)
                                is IllegalArgumentException,
                                is IllegalStateException ->
                                    userMessage(e) ?: msg(R.string.msg_error_incomplete)
                                else -> msg(R.string.msg_error_incomplete)
                            },
                        failed = true,
                    )
                notices.emit(Operation(message = state.value.message, failed = state.value.failed))
            }
        }
    }

    fun report(message: String) {
        state.value = DiscoveryState(message = message, failed = true)
        viewModelScope.launch {
            notices.emit(Operation(message = state.value.message, failed = state.value.failed))
        }
    }

    fun setQuery(value: String) {
        query.value = value.take(300)
    }

    fun photo(uri: Uri) =
        run(msg(R.string.msg_discovery_reading_photo)) {
            val input = File.createTempFile("folio-photo-", ".image", app.cacheDir)
            var bitmap: Bitmap? = null
            try {
                val stream =
                    app.contentResolver.openInputStream(uri)
                        ?: error(msg(R.string.msg_discovery_photo_unopenable))
                stream.use { source ->
                    input.outputStream().use { Epub.copyBounded(source, it, 25L * 1024 * 1024) }
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(input.absolutePath, bounds)
                require(bounds.outWidth in 1..30000 && bounds.outHeight in 1..30000) {
                    msg(R.string.msg_discovery_photo_too_large)
                }
                var sample = 1
                while (
                    bounds.outWidth / sample > 2000 || bounds.outHeight / sample > 2000
                ) sample *= 2
                val decoded =
                    BitmapFactory.decodeFile(
                        input.absolutePath,
                        BitmapFactory.Options().apply { inSampleSize = sample },
                    ) ?: error(msg(R.string.msg_discovery_photo_unreadable))
                bitmap = decoded
                val orientation =
                    runCatching {
                            android.media
                                .ExifInterface(input.absolutePath)
                                .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
                        }
                        .getOrDefault(1)
                val matrix =
                    android.graphics.Matrix().apply {
                        when (orientation) {
                            2 -> setScale(-1f, 1f)
                            3 -> setRotate(180f)
                            4 -> setScale(1f, -1f)
                            5 -> {
                                setRotate(90f)
                                postScale(-1f, 1f)
                            }
                            6 -> setRotate(90f)
                            7 -> {
                                setRotate(270f)
                                postScale(-1f, 1f)
                            }
                            8 -> setRotate(270f)
                        }
                    }
                if (orientation in 2..8)
                    bitmap =
                        Bitmap.createBitmap(
                                decoded,
                                0,
                                0,
                                decoded.width,
                                decoded.height,
                                matrix,
                                true,
                            )
                            .also { if (it !== decoded) decoded.recycle() }
                val recognisedBitmap = bitmap!!
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val result =
                    try {
                        suspendCoroutine<String> { continuation ->
                            recognizer
                                .process(InputImage.fromBitmap(recognisedBitmap, 0))
                                .addOnSuccessListener { continuation.resume(it.text) }
                                .addOnFailureListener { continuation.resumeWithException(it) }
                        }
                    } finally {
                        recognizer.close()
                    }
                val target = File(app.cacheDir, "discovery-cover-${UUID.randomUUID()}.jpg")
                target.outputStream().use {
                    check(recognisedBitmap.compress(Bitmap.CompressFormat.JPEG, 85, it))
                }
                val previous = cover.value
                cover.value = target.absolutePath
                previous?.let { File(it).delete() }
                query.value = BookDiscovery.query(result)
                candidates.value = emptyList()
                if (query.value.isBlank()) msg(R.string.msg_discovery_no_text)
                else msg(R.string.msg_discovery_check_text)
            } finally {
                bitmap?.recycle()
                input.delete()
            }
        }

    fun search() =
        run(msg(R.string.msg_discovery_finding_books)) {
            candidates.value = lookup.search(query.value.trim())
            if (candidates.value.isEmpty()) msg(R.string.msg_discovery_no_matches)
            else msg(R.string.msg_discovery_choose_correct)
        }

    fun save(candidate: MetadataCandidate, onSaved: () -> Unit = {}) =
        run(msg(R.string.msg_discovery_adding_want)) {
            require(
                candidate.key.matches(Regex("/works/OL[0-9]+W")) &&
                    candidate.title.isNotBlank() &&
                    candidate.title.length <= 2000 &&
                    candidate.authors.size <= 100 &&
                    candidate.authors.all { it.length <= 1000 }
            ) {
                msg(R.string.msg_discovery_bad_book)
            }
            val id = UUID.randomUUID().toString()
            var coverPath: String? = null
            val folder = File(app.repository.root, id)
            val message =
                try {
                    val bytes =
                        candidate.coverId?.let { runCatching { lookup.cover(it) }.getOrNull() }
                    val photo = cover.value?.let(::File)?.takeIf { it.isFile }
                    if (bytes != null || photo != null) {
                        check(folder.mkdirs())
                        val destination = File(folder, "wish-cover.jpg")
                        if (bytes != null) destination.writeBytes(bytes)
                        else photo!!.copyTo(destination)
                        // Bound and validate external image dimensions before decoding in the UI.
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(destination.absolutePath, bounds)
                        require(bounds.outWidth in 1..20000 && bounds.outHeight in 1..20000) {
                            msg(R.string.msg_discovery_bad_cover)
                        }
                        coverPath = destination.absolutePath
                    }
                    val inserted =
                        dao.insertWish(
                            WishBook(
                                id,
                                candidate.key,
                                candidate.title,
                                encode(candidate.authors),
                                coverPath,
                            )
                        )
                    if (inserted == -1L) {
                        folder.deleteRecursively()
                        msg(R.string.msg_discovery_already_wanted)
                    } else msg(R.string.msg_discovery_wanted)
                } catch (e: Exception) {
                    folder.deleteRecursively()
                    throw e
                }
            // A UI cancellation after commit must never remove persisted cover files.
            withContext(Dispatchers.Main) { onSaved() }
            message
        }

    fun connect(token: String, onConnected: () -> Unit = {}) =
        run(msg(R.string.msg_discovery_checking_account)) {
            val clean = token.trim().removePrefix("Bearer ").trim()
            val account = hardcover.account(clean)
            if (account.username != settings.username)
                app.database.withTransaction {
                    dao.allWishes().forEach { dao.updateWish(it.copy(hardcoverSynced = false)) }
                }
            profile.value = emptyList()
            profileAccount = null
            accountId.value = account.id
            settings.save(clean, account.username)
            settings.accountId = account.id
            username.value = account.username
            withContext(Dispatchers.Main) { onConnected() }
            msg(R.string.msg_discovery_connected, account.username)
        }

    fun forget() =
        run(msg(R.string.msg_discovery_disconnecting)) {
            settings.forget()
            accountId.value = 0
            profileAccount = null
            profile.value = emptyList()
            username.value = ""
            app.database.withTransaction {
                dao.allWishes().forEach { dao.updateWish(it.copy(hardcoverSynced = false)) }
            }
            msg(R.string.msg_discovery_disconnected)
        }

    fun searchHardcover(wish: WishBook) =
        run(msg(R.string.msg_discovery_finding_entry)) {
            matchWishId.value = wish.id
            matches.value = emptyList()
            matches.value =
                hardcover.search(settings.token(), "${wish.title} ${wish.authorLabel}".take(300))
            if (matches.value.isEmpty()) msg(R.string.msg_discovery_no_hardcover_matches_retry)
            else msg(R.string.msg_discovery_confirm_to_link)
        }

    fun discoverHardcover() =
        run(msg(R.string.msg_discovery_finding_on_hardcover)) {
            readingBookId.value = null
            matchWishId.value = null
            matches.value = emptyList()
            matches.value = hardcover.search(settings.token(), query.value.trim())
            if (matches.value.isEmpty()) msg(R.string.msg_discovery_no_matches)
            else msg(R.string.msg_discovery_confirm_to_save)
        }

    fun searchHardcoverText(text: String) =
        run(msg(R.string.msg_discovery_searching_hardcover)) {
            matches.value = hardcover.search(settings.token(), text.take(300))
            if (matches.value.isEmpty()) msg(R.string.msg_discovery_no_hardcover_matches)
            else msg(R.string.msg_discovery_confirm_to_link)
        }

    fun link(match: HardcoverMatch) =
        run(msg(R.string.msg_discovery_saving_match)) {
            val wishId = matchWishId.value ?: readingBookId.value?.let { dao.wish(it)?.id }
            if (wishId == null) {
                val id = UUID.randomUUID().toString()
                val folder = File(app.repository.root, id)
                try {
                    val trackedBook = readingBookId.value?.let { dao.book(it) }
                    val already = dao.allStoredWishes().filter { it.hardcoverId == match.id }
                    require(already.none { dao.removedEntry(it.id) != null }) {
                        msg(R.string.msg_discovery_removed_before_linking)
                    }
                    require(already.size <= 1) { msg(R.string.msg_discovery_duplicates) }
                    if (already.isNotEmpty()) {
                        trackedBook?.let { app.repository.attach(already.single().id, it.id) }
                        matches.value = emptyList()
                        matchWishId.value = null
                        if (readingBookId.value != null) loadReading(match.id)
                        return@run msg(R.string.msg_discovery_matched_existing)
                    }
                    if (trackedBook != null)
                        require(dao.allWishes().none { it.linkedBookId == trackedBook.id }) {
                            msg(R.string.msg_discovery_epub_already_matched)
                        }
                    val photo =
                        (if (trackedBook != null) trackedBook.coverPath else cover.value)
                            ?.let(::File)
                            ?.takeIf { it.isFile }
                    val savedCover = photo?.let {
                        check(folder.mkdirs())
                        it.copyTo(File(folder, "wish-cover.jpg")).absolutePath
                    }
                    val wish =
                        WishBook(
                            id,
                            "hardcover:${match.id}",
                            match.title,
                            encode(match.authors),
                            savedCover,
                            match.id,
                            match.title,
                            linkedBookId = trackedBook?.id,
                        )
                    parseWish(wishJson(wish))
                    if (dao.insertWish(wish) == -1L) {
                        folder.deleteRecursively()
                        if (trackedBook != null)
                            dao.allWishes()
                                .firstOrNull { it.catalogKey == wish.catalogKey }
                                ?.let { existing ->
                                    require(
                                        existing.linkedBookId == null ||
                                            existing.linkedBookId == trackedBook.id
                                    ) {
                                        msg(R.string.msg_discovery_linked_elsewhere)
                                    }
                                    app.repository.attach(existing.id, trackedBook.id)
                                }
                    }
                } catch (e: Exception) {
                    folder.deleteRecursively()
                    throw e
                }
            } else {
                val wish = dao.wish(wishId) ?: error(msg(R.string.msg_book_gone))
                require(dao.allWishes().none { it.id != wish.id && it.hardcoverId == match.id }) {
                    msg(R.string.msg_discovery_hardcover_book_exists)
                }
                dao.updateWish(
                    wish.copy(
                        hardcoverId = match.id,
                        hardcoverTitle = match.title,
                        hardcoverSynced = false,
                    )
                )
            }
            matches.value = emptyList()
            matchWishId.value = null
            if (readingBookId.value != null) loadReading(match.id)
            if (readingBookId.value != null) msg(R.string.msg_discovery_linked)
            else msg(R.string.msg_discovery_match_saved)
        }

    fun publish(wish: WishBook) =
        run(msg(R.string.msg_discovery_publishing)) {
            val current = dao.wish(wish.id) ?: error(msg(R.string.msg_book_gone))
            val result =
                hardcover.wantToRead(
                    settings.token(),
                    current.hardcoverId ?: error(msg(R.string.msg_discovery_link_first)),
                )
            dao.updateWish(current.copy(hardcoverSynced = true))
            result
        }

    fun attach(wish: WishBook, book: Book) =
        run(msg(R.string.msg_discovery_attaching)) {
            app.repository.attach(wish.id, book.id)
            msg(R.string.msg_discovery_attached)
        }

    fun importForAttachment(uri: Uri, onImported: (Book) -> Unit) =
        run(msg(R.string.msg_discovery_importing)) {
            val (book, added) = app.repository.import(uri)
            withContext(Dispatchers.Main) { onImported(book) }
            if (added) msg(R.string.msg_discovery_imported)
            else msg(R.string.msg_discovery_import_existing)
        }

    fun membership(wish: WishBook, shelf: Shelf, checked: Boolean) =
        run(msg(R.string.msg_discovery_updating_list)) {
            app.repository.manageBatch(setOf(wish.id), shelfId = shelf.id, add = checked)
            msg(R.string.msg_list_updated)
        }

    fun loadProfile(reset: Boolean = true) =
        run(msg(R.string.msg_discovery_loading_profile)) {
            val me = hardcover.account(settings.token())
            if (reset) {
                profile.value = emptyList()
                profileCursor = 0
            }
            require(reset || profileAccount == me.id) {
                msg(R.string.msg_discovery_account_changed)
            }
            profileAccount = me.id
            accountId.value = me.id
            settings.accountId = me.id
            val page = hardcover.libraryPage(settings.token(), me.id, profileCursor)
            require(profile.value.size + page.size <= 10000) {
                msg(R.string.msg_discovery_import_limit)
            }
            require(
                page.none { row -> profile.value.any { it.book.id == row.book.id } } &&
                    page.map { it.book.id }.distinct().size == page.size
            ) {
                msg(R.string.msg_discovery_import_duplicates)
            }
            profile.value = profile.value + page
            profileCursor = page.lastOrNull()?.userBookId ?: profileCursor
            profileMore.value = page.size == 50
            plural(R.plurals.msg_discovery_profile_ready, profile.value.size)
        }

    fun importProfile(selected: Set<Int>, targets: Map<Int, String>, onDone: () -> Unit) =
        run(msg(R.string.msg_discovery_saving_reviewed)) {
            val owner = profileAccount ?: error(msg(R.string.msg_discovery_load_first))
            require(hardcover.account(settings.token()).id == owner) {
                msg(R.string.msg_discovery_account_changed)
            }
            val rows = profile.value.filter { it.userBookId in selected }
            require(rows.isNotEmpty() && rows.size == selected.size) {
                msg(R.string.msg_discovery_review_again)
            }
            app.repository.lock.withLock {
                app.database.withTransaction {
                    rows.forEach { row ->
                        val existing =
                            dao.allStoredWishes().filter { it.hardcoverId == row.book.id }
                        require(existing.none { dao.removedEntry(it.id) != null }) {
                            msg(R.string.msg_discovery_removed_before_importing)
                        }
                        require(existing.size <= 1) { msg(R.string.msg_discovery_duplicates) }
                        val target =
                            targets[row.userBookId]?.let {
                                dao.book(it) ?: error(msg(R.string.msg_discovery_epub_gone))
                            }
                        val wish =
                            existing.singleOrNull()
                                ?: WishBook(
                                    UUID.randomUUID().toString(),
                                    "hardcover:${row.book.id}",
                                    row.book.title,
                                    encode(row.book.authors),
                                    hardcoverId = row.book.id,
                                    hardcoverTitle = row.book.title,
                                )
                        if (target != null) requireAttachment(wish, target, dao.allWishes())
                        val value =
                            wish.copy(
                                linkedBookId = target?.id ?: wish.linkedBookId,
                                hardcoverSynced = true,
                            )
                        if (existing.isEmpty())
                            require(dao.insertWish(value) != -1L) {
                                msg(R.string.msg_discovery_import_conflict)
                            }
                        else dao.updateWish(value)
                        if (target != null)
                            dao.allWishMemberships()
                                .filter { it.wishId == wish.id }
                                .forEach { dao.addMembership(Membership(target.id, it.shelfId)) }
                        dao.hardcoverObservation(
                            HardcoverObservation(
                                owner,
                                row.book.id,
                                row.status,
                                row.pages,
                                row.editionId,
                                System.currentTimeMillis(),
                            )
                        )
                    }
                }
            }
            withContext(Dispatchers.Main) { onDone() }
            plural(R.plurals.msg_discovery_profile_saved, rows.size)
        }

    fun remove(wish: WishBook) =
        run(msg(R.string.msg_discovery_removing)) {
            app.repository.removeLocal(setOf(wish.id))
            msg(R.string.msg_discovery_removed)
        }
}
