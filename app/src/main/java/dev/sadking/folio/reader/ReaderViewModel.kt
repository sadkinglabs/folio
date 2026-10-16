@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package dev.sadking.folio.reader

import android.app.Application
import android.content.Context
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import dev.sadking.folio.FolioApplication
import dev.sadking.folio.R
import dev.sadking.folio.core.Epub
import dev.sadking.folio.data.*
import dev.sadking.folio.sync.SyncAnnotations
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import org.json.JSONObject
import org.readium.adapter.pdfium.document.PdfiumDocumentFactory
import org.readium.adapter.pdfium.navigator.PdfiumPreferences
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Axis
import org.readium.r2.navigator.preferences.Fit
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.*
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

private const val LISTEN_SAVE_MS = 15_000L

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as FolioApplication
    private val dao = app.database.library()
    private val settings = app.getSharedPreferences("reader", Application.MODE_PRIVATE)
    /** Per-book typography overrides, keyed by book ID; absent means the book uses the defaults. */
    private val bookSettings = app.getSharedPreferences("reader-books", Application.MODE_PRIVATE)
    private val saveLock = Mutex()

    /** Read-aloud; lives with the model so a rotation does not stop it. */
    internal val listen = ReadAloud(application, viewModelScope, settings)
    var book by mutableStateOf<Book?>(null)
        private set

    var publication by mutableStateOf<Publication?>(null)
        private set

    /** How the open book is shown. EPUB-only features check it. Null until the book opens. */
    var kind by mutableStateOf<ReaderKind?>(null)
        private set

    var initialLocator: Locator? = null
        private set

    var lastLocation by mutableStateOf<Locator?>(null)
        private set

    var lastPhone by mutableStateOf<PhoneReading?>(null)
        private set

    var kindle by mutableStateOf<ReadingSnapshot?>(null)
        private set

    /** KOReader's key for this file, so Folio Link reports can be matched to it. */
    private var linkDocument: String? = null

    /** A newer position an e-reader reported through Folio Link, offered once when opening. */
    var linkOffer by mutableStateOf<KosyncReport?>(null)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    var busy by mutableStateOf(false)
        private set

    /**
     * This book's highlights and notes from every device, each once (see
     * [SyncAnnotations.visible]). EPUB only; empty until the book opens.
     */
    var highlights by mutableStateOf<List<BookAnnotation>>(emptyList())
        private set

    var fontSize by mutableDoubleStateOf(settings.getFloat("fontSize", 110f).toDouble())
        private set

    var pageTheme by mutableStateOf(settings.getString("theme", "System") ?: "System")
        private set

    var serif by mutableStateOf(settings.getBoolean("serif", true))
        private set

    var scrolling by mutableStateOf(settings.getBoolean("scroll", false))
        private set

    var lineHeight by mutableDoubleStateOf(settings.getFloat("lineHeight", 1.5f).toDouble())
        private set

    var margins by mutableDoubleStateOf(settings.getFloat("margins", 1.0f).toDouble())
        private set

    var publisherStyles by mutableStateOf(settings.getBoolean("publisherStyles", false))
        private set

    /** Set on the Settings tab; the reader only reads it. */
    val volumeKeys: Boolean
        get() = settings.getBoolean("volumeKeys", true)

    /** True when this book keeps its own text settings instead of the reader defaults. */
    var bookOnly by mutableStateOf(false)
        private set

    /** Full package spine as ZIP paths; maps KOReader DocFragments to Readium resources. */
    var spine: List<String> = emptyList()
        private set

    /**
     * Readium's positions for this book: the slider's pages and chapter ticks. Computed once per
     * book off the main thread, since a huge book takes seconds; null until then, when the bar
     * shows the percentage alone.
     */
    var positions by mutableStateOf<ReaderPositions?>(null)
        private set

    private var positionsJob: kotlinx.coroutines.Job? = null

    /**
     * Computes the slider's positions the first time the controls show. Opening a book does not
     * wait on it or compete with the first page, and closing the reader stops it.
     */
    fun loadPositions() {
        val opened = publication ?: return
        if (positions != null || positionsJob?.isActive == true) return
        positionsJob = viewModelScope.launch {
            try {
                positions = withContext(Dispatchers.Default) { ReaderPositions.compute(opened) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // The slider keeps showing progression only.
            }
        }
    }

    /** Total Readium positions ("pages" at the current layout), or 0 until computed. */
    val positionCount: Int
        get() = positions?.locators?.size ?: 0

    var automaticKindleResume = false
        private set

    private var opening = false

    /**
     * How the book page asked to open the book, applied once after the first page shows. Kept here
     * so a rotation does not apply it again.
     */
    private var start: ReaderStart = ReaderStart.Resume

    fun takeStart(): ReaderStart = start.also { start = ReaderStart.Resume }

    fun open(id: String, from: ReaderStart = ReaderStart.Resume) {
        if (opening) return
        opening = true
        start = from
        viewModelScope.launch {
            try {
                val loaded =
                    withContext(Dispatchers.IO) {
                        UUID.fromString(id)
                        require(dao.removedEntry(id) == null) {
                            app.getString(R.string.msg_reader_restore_first)
                        }
                        val found =
                            dao.book(id) ?: error(app.getString(R.string.msg_reader_no_epub))
                        val epubFile = found.format == "epub"
                        require(opensOnPhone(found.format)) {
                            app.getString(R.string.reader_m9_format_unsupported)
                        }
                        val file = File(found.currentPath).canonicalFile
                        require(app.repository.holds(found.id, file) && file.isFile) {
                            app.getString(
                                if (epubFile) R.string.msg_reader_epub_missing
                                else R.string.msg_reader_m9_file_missing
                            )
                        }
                        require(Epub.sha256(file) == found.currentHash) {
                            app.getString(
                                if (epubFile) R.string.msg_reader_epub_changed
                                else R.string.msg_reader_m9_file_changed
                            )
                        }
                        // Readium reads only ZIP archives: a RAR comic opens from a ZIP copy.
                        val readable = if (found.format == "cbr") comicZip(found, file) else file
                        Triple(found, file, readable)
                    }
                val format = loaded.first.format
                // All resource requests through Readium are offline; no book-triggered HTTP
                // fetches.
                val http =
                    object : HttpClient {
                        override suspend fun stream(
                            request: HttpRequest
                        ): HttpTry<HttpStreamResponse> =
                            Try.failure(
                                HttpError.IO(IOException("Remote EPUB resources are disabled"))
                            )
                    }
                val retriever = AssetRetriever(app.contentResolver, http)
                suspend fun publicationOf(file: File): Publication {
                    val asset =
                        retriever.retrieve(file.toUrl(isDirectory = false)).getOrElse {
                            error(
                                app.getString(
                                    if (format == "epub") R.string.msg_reader_epub_unopenable
                                    else R.string.msg_reader_m9_unopenable
                                )
                            )
                        }
                    return PublicationOpener(
                            DefaultPublicationParser(
                                app,
                                http,
                                retriever,
                                pdfFactory = PdfiumDocumentFactory(app),
                            )
                        )
                        .open(asset, allowUserInteraction = false)
                        .getOrElse {
                            asset.close()
                            error(
                                app.getString(
                                    when (format) {
                                        "epub" -> R.string.msg_reader_epub_protected
                                        "pdf" -> R.string.msg_reader_m9_pdf_protected
                                        else -> R.string.msg_reader_m9_comic_unreadable
                                    }
                                )
                            )
                        }
                }
                val opened =
                    try {
                        publicationOf(loaded.third)
                    } catch (failure: IllegalStateException) {
                        // CancellationException is an IllegalStateException: never swallow it.
                        if (failure is CancellationException || format != "cbz") throw failure
                        // A CBZ with stray files can fail Readium's comic check; its pictures
                        // still open.
                        publicationOf(
                            withContext(Dispatchers.IO) { comicZip(loaded.first, loaded.second) }
                        )
                    }
                val openedKind = readerKind(opened)
                if (openedKind == null) {
                    opened.close()
                    error(app.getString(R.string.reader_m9_format_unsupported))
                }
                if (
                    opened.readingOrder.isEmpty() ||
                        opened.locatorFromLink(opened.readingOrder.first()) == null
                ) {
                    opened.close()
                    error(
                        app.getString(
                            if (format == "epub") R.string.msg_reader_no_chapters
                            else R.string.msg_reader_m9_no_pages
                        )
                    )
                }
                if (openedKind == ReaderKind.EPUB)
                    withContext(Dispatchers.IO) {
                        // Positions stay phone-only if the spine cannot be read.
                        spine = runCatching { Epub.spine(loaded.second) }.getOrDefault(emptyList())
                        // KOReader pointers are EPUB-only: PDFs and comics never offer or publish
                        // one.
                        linkDocument = runCatching { koreaderDigest(loaded.second) }.getOrNull()
                    }
                val saved = dao.phoneReading(id, loaded.first.currentHash)
                initialLocator = saved?.let {
                    runCatching { Locator.fromJSON(JSONObject(it.locator)) }.getOrNull()
                }
                // Starting over opens at the first page; the saved place moves once a page settles.
                if (from == ReaderStart.Beginning) initialLocator = null
                lastLocation = initialLocator
                lastPhone = saved
                // A chosen start replaces the offer of an e-reader's place.
                linkOffer =
                    if (from == ReaderStart.Resume)
                        withContext(Dispatchers.IO) { newerLinkReport(saved) }
                    else null
                kindle =
                    dao.allReading().firstOrNull {
                        it.bookId == id && it.deviceId == app.deviceSettings.load().id
                    }
                val queued =
                    app.getSharedPreferences("paired-folder", Application.MODE_PRIVATE)
                        .getString("incoming:$id", null)
                        ?.let { runCatching { JSONObject(it) }.getOrNull() }
                automaticKindleResume =
                    openedKind == ReaderKind.EPUB &&
                        from == ReaderStart.Resume &&
                        queued != null &&
                        queued.optString("revision") == loaded.first.currentHash &&
                        queued.optString("expectedPhone") == saved?.locator.orEmpty() &&
                        kindle?.position == queued.optString("position")
                bookSettings.getString(loaded.first.id, null)?.let {
                    use(ReaderTypography.fromJson(it, typography()))
                    bookOnly = true
                }
                book = loaded.first
                kind = openedKind
                publication = opened
                if (openedKind == ReaderKind.EPUB) watchHighlights(id)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                error = failure.message ?: app.getString(R.string.msg_reader_open_failed)
            }
        }
    }

    fun preferences(dark: Boolean) =
        EpubPreferences(
            // Readium wants a multiplier; passing the percentage made every size the largest.
            fontSize = readiumFontScale(fontSize),
            fontFamily = if (serif) FontFamily.SERIF else FontFamily.SANS_SERIF,
            publisherStyles = publisherStyles,
            lineHeight = lineHeight,
            pageMargins = margins,
            scroll = scrolling,
            theme =
                when (pageTheme) {
                    "Dark" -> Theme.DARK
                    "Paper" -> Theme.SEPIA
                    "Light" -> Theme.LIGHT
                    else -> if (dark) Theme.DARK else Theme.LIGHT
                },
        )

    /**
     * PDFs scroll top to bottom at the screen's width, like one long page. They have no text
     * settings.
     */
    fun pdfPreferences() =
        PdfiumPreferences(fit = Fit.WIDTH, scroll = true, scrollAxis = Axis.VERTICAL)

    /** A ZIP of the comic's pages in the cache, for archives Readium cannot read itself. */
    private fun comicZip(book: Book, file: File): File =
        try {
            ComicCache.zipFor(app, file, book.currentHash)
        } catch (_: IOException) {
            error(app.getString(R.string.msg_reader_m9_comic_unreadable))
        }

    fun appearance(
        size: Double = fontSize,
        theme: String = pageTheme,
        useSerif: Boolean = serif,
        scroll: Boolean = scrolling,
        spacing: Double = lineHeight,
        pageMargins: Double = margins,
        bookStyles: Boolean = publisherStyles,
    ) {
        pageTheme = theme
        use(ReaderTypography(size, useSerif, scroll, spacing, pageMargins, bookStyles).bounded())
        val global = settings.edit().putString("theme", theme)
        val current = book
        if (bookOnly && current != null)
            bookSettings.edit().putString(current.id, typography().toJson()).apply()
        else
            global
                .putFloat("fontSize", fontSize.toFloat())
                .putBoolean("serif", serif)
                .putBoolean("scroll", scrolling)
                .putFloat("lineHeight", lineHeight.toFloat())
                .putFloat("margins", margins.toFloat())
                .putBoolean("publisherStyles", publisherStyles)
        global.apply()
    }

    /**
     * Turning this on keeps the current text settings for this book only; turning it off drops the
     * override and returns the book to the reader defaults.
     */
    fun keepForThisBook(on: Boolean) {
        val current = book ?: return
        bookOnly = on
        if (on) bookSettings.edit().putString(current.id, typography().toJson()).apply()
        else {
            bookSettings.edit().remove(current.id).apply()
            use(defaults())
        }
    }

    private fun typography() =
        ReaderTypography(fontSize, serif, scrolling, lineHeight, margins, publisherStyles)

    private fun defaults() =
        ReaderTypography(
            settings.getFloat("fontSize", 110f).toDouble(),
            settings.getBoolean("serif", true),
            settings.getBoolean("scroll", false),
            settings.getFloat("lineHeight", 1.5f).toDouble(),
            settings.getFloat("margins", 1.0f).toDouble(),
            settings.getBoolean("publisherStyles", false),
        )

    private fun use(value: ReaderTypography) {
        fontSize = value.fontSize
        serif = value.serif
        scrolling = value.scroll
        lineHeight = value.lineHeight
        margins = value.margins
        publisherStyles = value.publisherStyles
    }

    /** Page colour Readium paints for the current theme, so system-bar areas match the page. */
    fun pageDark(systemDark: Boolean) =
        when (pageTheme) {
            "Dark" -> true
            "Light",
            "Paper" -> false
            else -> systemDark
        }

    fun pageColor(systemDark: Boolean): Long =
        when {
            pageTheme == "Paper" -> 0xFFFAF4E8
            pageDark(systemDark) -> 0xFF000000
            else -> 0xFFFFFFFF
        }

    fun location(locator: Locator) {
        lastLocation = locator
    }

    suspend fun save(locator: Locator, pointer: String = "") = saveLock.withLock {
        val current = book ?: return@withLock
        val value =
            PhoneReading(
                current.id,
                current.currentHash,
                locator.toJSON().toString(),
                pointer,
                locator.locations.totalProgression,
                System.currentTimeMillis(),
            )
        withContext(Dispatchers.IO) {
            // Revision checks keep stale reader writes from attaching to a replacement file.
            app.database.withTransaction {
                val stored = dao.book(current.id)
                if (stored?.currentHash == current.currentHash) {
                    dao.savePhoneReading(value)
                    publishToLink(value)
                    value.percent?.let { app.hardcoverOutbox.queueBook(current.id, it) }
                    if (
                        stored.status in listOf("Unread", "Want to Read") &&
                            (value.percent ?: 0.0) > 0.001
                    )
                        dao.update(
                            stored.copy(status = "Reading", statusAt = System.currentTimeMillis())
                        )
                    lastPhone = value
                }
            }
        }
    }

    /**
     * Makes the phone's position the one Folio Link hands to KOReader, when it can be expressed as
     * a KOReader pointer. KOReader then offers to jump to it when the book opens there.
     */
    private fun publishToLink(value: PhoneReading) {
        val document = linkDocument ?: return
        val percent = value.percent ?: return
        if (!value.xpointer.startsWith("/")) return
        val settings = app.link.settings
        dao.saveKosyncReport(
            KosyncReport(
                document,
                settings.phoneDeviceId,
                settings.phoneDeviceName,
                value.xpointer,
                percent.coerceIn(0.0, 1.0),
                value.updatedAt / 1000,
                value.updatedAt,
            )
        )
    }

    /** An e-reader's report that is newer than the phone's place and somewhere else in the book. */
    private suspend fun newerLinkReport(saved: PhoneReading?): KosyncReport? {
        val document = linkDocument ?: return null
        val settings = app.link.settings
        val dismissed = linkOffers.getLong(document, 0)
        return dao.kosyncReports(document)
            .filter { it.deviceId != settings.phoneDeviceId && it.progress.startsWith("/") }
            .maxByOrNull { it.timestamp }
            ?.takeIf { report ->
                report.timestamp > dismissed &&
                    report.timestamp * 1000 > (saved?.updatedAt ?: 0) &&
                    kotlin.math.abs(report.percentage - (saved?.percent ?: 0.0)) >= 0.002
            }
    }

    private val linkOffers = app.getSharedPreferences("link-offers", Application.MODE_PRIVATE)

    /** Clears the offer whether it was taken or not; the same report is not offered again. */
    fun settleLinkOffer() {
        val offer = linkOffer ?: return
        linkOffers.edit().putLong(offer.document, offer.timestamp).apply()
        linkOffer = null
    }

    /**
     * Hides the offer without answering it, when it timed out unanswered. It comes back on the next
     * open unless the phone has read on since, which makes the report old.
     */
    fun hideLinkOffer() {
        linkOffer = null
    }

    /** Other devices' latest Folio Link places in this book, newest first; null without a key. */
    fun linkReports(): Flow<List<KosyncReport>>? {
        val document = linkDocument ?: return null
        val phone = app.link.settings.phoneDeviceId
        return dao.kosyncReportsFlow(document).map { reports ->
            reports
                .filter { it.deviceId != phone && it.progress.startsWith("/") }
                .sortedByDescending { it.timestamp }
        }
    }

    private var listenedAt = 0L

    /**
     * Saves the sentence being read while the reader is in the background, at most every
     * [LISTEN_SAVE_MS], so a long listen with the screen off keeps its place even if the process
     * ends. It has no KOReader pointer; the page save adds one when the reader is back on screen.
     */
    fun listened(locator: Locator) {
        lastLocation = locator
        val now = System.currentTimeMillis()
        if (now - listenedAt < LISTEN_SAVE_MS) return
        listenedAt = now
        viewModelScope.launch {
            try {
                save(locator)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // The next sentence or the page save tries again.
            }
        }
    }

    fun saveOnPause() {
        val locator = lastLocation ?: return
        val pointer =
            lastPhone?.takeIf { it.locator == locator.toJSON().toString() }?.xpointer.orEmpty()
        // Finishing the activity cancels viewModelScope right after onPause; the write must land.
        viewModelScope.launch {
            withContext(NonCancellable) {
                try {
                    save(locator, pointer)
                } catch (_: Exception) {
                    message = app.getString(R.string.reader_save_failed)
                }
            }
        }
    }

    fun bookmark() {
        val current = book ?: return
        val locator = lastLocation ?: return
        viewModelScope.launch {
            dao.addPhoneBookmark(
                PhoneBookmark(
                    UUID.randomUUID().toString(),
                    current.id,
                    current.currentHash,
                    locator.toJSON().toString(),
                    listOfNotNull(
                            locator.title,
                            "${((locator.locations.totalProgression ?: 0.0) * 100).toInt()}%",
                        )
                        .joinToString(" · "),
                    System.currentTimeMillis(),
                )
            )
            message = app.getString(R.string.msg_reader_bookmark_saved)
        }
    }

    fun bookmarks() = book?.let { dao.phoneBookmarks(it.id, it.currentHash) }

    private fun watchHighlights(bookId: String) {
        viewModelScope.launch {
            dao.bookAnnotations(bookId)
                .map { SyncAnnotations.visible(it) }
                .flowOn(Dispatchers.Default)
                .collect { highlights = it }
        }
    }

    /**
     * Saves a highlight made on the phone, or a note when [note] has text. It goes to the e-reader
     * at the next sync and is never edited or deleted afterwards.
     */
    fun addHighlight(passage: SelectedPassage, note: String, chapter: String) {
        val current = book ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val datetime = annotationDatetime(LocalDateTime.now())
                    val plainId = annotationId(PHONE_ANNOTATION_DEVICE, datetime, passage.pos0)
                    dao.annotation(
                        phoneAnnotation(
                            current.id,
                            passage,
                            note,
                            chapter,
                            datetime,
                            sameStartTaken = dao.annotationById(plainId) != null,
                        )
                    )
                }
                message =
                    app.getString(
                        if (note.isBlank()) R.string.reader_highlight_saved
                        else R.string.reader_highlight_note_saved
                    )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                message = app.getString(R.string.reader_highlight_save_failed)
            }
        }
    }

    fun deleteBookmark(id: String) {
        viewModelScope.launch { dao.deletePhoneBookmark(id) }
    }

    fun completedIncoming() {
        val current = book ?: return
        val saved = lastPhone ?: return
        val remote = kindle ?: return
        val config = app.deviceSettings.load()
        val key =
            "baseline:" +
                Epub.hash((config.id + current.id + current.currentHash).byteInputStream())
        app.getSharedPreferences("paired-folder", Application.MODE_PRIVATE)
            .edit()
            .remove("incoming:${current.id}")
            .putString(
                key,
                JSONObject().put("phone", saved.xpointer).put("kindle", remote.position).toString(),
            )
            .apply()
        automaticKindleResume = false
    }

    fun dismissMessage() {
        message = null
    }

    override fun onCleared() {
        listen.stop()
        publication?.close()
    }
}

/**
 * Where the reader starts when the book page's Read menu asks for something other than resuming.
 */
sealed interface ReaderStart {
    data object Resume : ReaderStart

    data object Beginning : ReaderStart

    data object Listen : ReaderStart

    /** A KOReader pointer an e-reader reported. */
    data class Device(val pointer: String) : ReaderStart
}

/** How the phone reader shows a book. */
enum class ReaderKind {
    EPUB,
    PDF,
    COMIC,
}

/** The reader for a book's stored format (Book.format), or null when the phone cannot open it. */
fun readerKind(format: String): ReaderKind? =
    when (format.lowercase()) {
        "epub" -> ReaderKind.EPUB
        "pdf" -> ReaderKind.PDF
        "cbz",
        "cbr" -> ReaderKind.COMIC
        else -> null
    }

/** True when the phone reader can open books stored in [format]. */
fun opensOnPhone(format: String) = readerKind(format) != null

/** The reader for an opened publication, from what Readium found in the file. */
internal fun readerKind(publication: Publication): ReaderKind? =
    when {
        publication.conformsTo(Publication.Profile.PDF) -> ReaderKind.PDF
        publication.conformsTo(Publication.Profile.DIVINA) -> ReaderKind.COMIC
        publication.conformsTo(Publication.Profile.EPUB) -> ReaderKind.EPUB
        else -> null
    }

private val COMIC_PAGE_TYPES =
    setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "bmp", "jxl", "tif", "tiff")

/**
 * An archive path as a comic page's ZIP entry name, or null when it is not a page: folders, hidden
 * files, macOS leftovers and metadata such as ComicInfo.xml.
 */
internal fun comicPageName(path: String): String? {
    val name = path.replace('\\', '/').trimStart('/')
    val parts = name.split('/')
    if (parts.any { it.isEmpty() || it.startsWith(".") || it == "__MACOSX" }) return null
    return name.takeIf { it.substringAfterLast('.', "").lowercase() in COMIC_PAGE_TYPES }
}

/**
 * RAR comics (CBR): Readium reads only ZIP archives, so the pages are copied once per file revision
 * into a ZIP in the app's cache, and the reader opens that. The book file is only read. One comic
 * is kept; opening another replaces it, and Android may clear the cache at any time.
 */
internal object ComicCache {
    private const val BLOCK = 64 * 1024

    fun zipFor(context: Context, source: File, hash: String): File {
        val dir = File(context.cacheDir, "comics").apply { mkdirs() }
        val target = File(dir, "$hash.cbz")
        if (target.isFile && target.length() > 0) return target
        dir.listFiles()?.forEach { it.delete() }
        val partial = File(dir, "$hash.partial")
        try {
            ZipOutputStream(partial.outputStream().buffered()).use { zip ->
                // Pictures are already compressed.
                zip.setLevel(Deflater.NO_COMPRESSION)
                if (copyPages(source, zip) == 0) throw IOException("No pages")
            }
            if (!partial.renameTo(target)) throw IOException("Cache rename failed")
            return target
        } finally {
            partial.delete()
        }
    }

    private fun copyPages(source: File, zip: ZipOutputStream): Int {
        val archive = Archive.readNew()
        try {
            Archive.readSupportFilterAll(archive)
            Archive.readSupportFormatAll(archive)
            Archive.readOpenFileName(archive, source.path.toByteArray(), BLOCK.toLong())
            val bytes = ByteArray(BLOCK)
            val written = mutableSetOf<String>()
            while (true) {
                val entry = Archive.readNextHeader(archive)
                if (entry == 0L) break
                if (ArchiveEntry.isEncrypted(entry)) throw IOException("Password-protected")
                val path =
                    ArchiveEntry.pathnameUtf8(entry)
                        ?: ArchiveEntry.pathname(entry)?.let { String(it) }
                val name = path?.let(::comicPageName)
                if (
                    ArchiveEntry.filetype(entry) != ArchiveEntry.AE_IFREG ||
                        name == null ||
                        !written.add(name)
                )
                    continue
                zip.putNextEntry(ZipEntry(name))
                while (true) {
                    val buffer = ByteBuffer.wrap(bytes)
                    Archive.readData(archive, buffer)
                    if (buffer.position() == 0) break
                    zip.write(bytes, 0, buffer.position())
                }
                zip.closeEntry()
            }
            return written.size
        } finally {
            Archive.readFree(archive)
        }
    }
}
