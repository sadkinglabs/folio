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
import dev.sadking.folio.core.dictionary.DictionaryEntry
import dev.sadking.folio.data.*
import dev.sadking.folio.reader.dictionary.DictionaryModel
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
import org.readium.r2.navigator.preferences.ColumnCount
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

    /** The phone's reading and listening sessions, for the statistics. */
    internal val sessions =
        SessionRecorder(
            app.database.stats(),
            app.background,
            DeviceKeys.phone(app.link.settings.phoneDeviceId),
        )

    /** Read-aloud; lives with the model so a rotation does not stop it. */
    internal val listen =
        ReadAloud(application, viewModelScope, settings).also { it.sessions = sessions }
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
     * This book's live highlights and notes from every device, each once (see
     * [SyncAnnotations.live]); deleted ones are left out. EPUB only; empty until the book opens.
     */
    var highlights by mutableStateOf<List<BookAnnotation>>(emptyList())
        private set

    /** The owner's own changes to highlights: delete, undo, colour and note. */
    private val highlightEdits = HighlightEdits(dao)

    /** The colour Highlight uses: the last one picked. */
    var highlightColour by
        mutableStateOf(
            settings.getString("highlightColour", null)?.let { name ->
                HighlightColour.entries.firstOrNull { it.name == name }
            } ?: HighlightColour.YELLOW
        )
        private set

    /** The highlight just deleted, until its Undo is shown and done with. */
    var deletedHighlight by mutableStateOf<BookAnnotation?>(null)
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

    /** Two pages side by side where the window is wide enough; paginated EPUBs only. */
    var twoPages by mutableStateOf(settings.getBoolean("twoPages", true))
        private set

    /**
     * The open sheet. Kept here rather than in saved state: the activity starts without its saved
     * state (the page is rebuilt from Room), so this is what brings a sheet back after a rotation.
     */
    internal var panel by mutableStateOf<ReaderPanel?>(null)

    /** The owner's reading speed on this phone, for the time left. */
    internal val speed = ReadingSpeed(settings)

    /** What the search field holds; the search itself runs through [search]. */
    internal var searchText by mutableStateOf("")
        private set

    internal var searchResults by mutableStateOf(SearchResults())
        private set

    /** The match tapped last, highlighted on the page until the search is closed. */
    internal var shownHit by mutableStateOf<SearchHit?>(null)

    private var searchJob: kotlinx.coroutines.Job? = null
    private var searcher: ReaderSearch? = null

    /** The text Define was asked for; null when its sheet is closed. */
    internal var defineText by mutableStateOf<String?>(null)
        private set

    /** The entries for [defineText]; null while looking up. */
    internal var definitions by mutableStateOf<List<DictionaryEntry>?>(null)
        private set

    private var defineJob: kotlinx.coroutines.Job? = null

    /** Brightness, warmth, keeping the screen on and page turns; see [ReaderComfort]. */
    var comfort by mutableStateOf(ReaderComfort.load(settings))
        private set

    /** Applies and keeps a comfort choice at once, for every book. */
    fun comfort(value: ReaderComfort) {
        comfort = value.bounded()
        ReaderComfort.save(settings, comfort)
    }

    /**
     * Shows a comfort choice at once without keeping it yet: an edge slide in progress. The slide
     * keeps its last value through [comfort] when the finger lifts.
     */
    fun previewComfort(value: ReaderComfort) {
        comfort = value.bounded()
    }

    private val bookmarkStore = BookmarkStore(dao)

    /** This book's bookmarks for the open revision, newest first, each with its place parsed. */
    internal var marks by mutableStateOf<List<PlacedBookmark>>(emptyList())
        private set

    /** The screen's share of the chapter, measured in the page; null until then. */
    internal var pageSpan by mutableStateOf<PageSpan?>(null)

    /** Bookmarks the owner just removed from the page, while Undo is offered. */
    internal var removedMarks by mutableStateOf<List<PhoneBookmark>?>(null)
        private set

    /** The bookmarks on the page shown; empty when it has none. */
    internal val pageMarks: List<PhoneBookmark>
        get() {
            val page = lastLocation ?: return emptyList()
            val path = resourcePath(page.href.toString())
            val span = pageSpan?.takeIf { it.path == path }?.span
            return marks
                .filter { placed ->
                    placed.locator?.let {
                        bookmarkOnPage(it, page, span, byPosition = kind != ReaderKind.EPUB)
                    } == true
                }
                .map { it.mark }
        }

    /** True when a bookmark lies in the chapter at [path], so the page is worth measuring. */
    internal fun hasBookmarkIn(path: String) = marks.any { placed ->
        placed.locator?.let { resourcePath(it.href.toString()) } == path
    }

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
                watchBookmarks(loaded.first)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                error = failure.message ?: app.getString(R.string.msg_reader_open_failed)
            }
        }
    }

    fun preferences(dark: Boolean) =
        EpubPreferences(
            // Two columns only where Readium judges the page wide enough (landscape tablets,
            // unfolded phones); one column otherwise.
            columnCount = if (twoPages) ColumnCount.AUTO else ColumnCount.ONE,
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
        pages: Boolean = twoPages,
    ) {
        pageTheme = theme
        use(
            ReaderTypography(size, useSerif, scroll, spacing, pageMargins, bookStyles, pages)
                .bounded()
        )
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
                .putBoolean("twoPages", twoPages)
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
        ReaderTypography(fontSize, serif, scrolling, lineHeight, margins, publisherStyles, twoPages)

    private fun defaults() =
        ReaderTypography(
            settings.getFloat("fontSize", 110f).toDouble(),
            settings.getBoolean("serif", true),
            settings.getBoolean("scroll", false),
            settings.getFloat("lineHeight", 1.5f).toDouble(),
            settings.getFloat("margins", 1.0f).toDouble(),
            settings.getBoolean("publisherStyles", false),
            settings.getBoolean("twoPages", true),
        )

    private fun use(value: ReaderTypography) {
        fontSize = value.fontSize
        serif = value.serif
        scrolling = value.scroll
        lineHeight = value.lineHeight
        margins = value.margins
        publisherStyles = value.publisherStyles
        twoPages = value.twoPages
    }

    /** Sets the search field; clearing it clears the results and the highlight. */
    internal fun editSearch(text: String) {
        searchText = text
        if (text.isBlank()) {
            searchJob?.cancel()
            searchResults = SearchResults()
            shownHit = null
        }
    }

    /**
     * Runs a search for [query] unless it is the one already shown or running; a new search clears
     * the highlight of the last one. Results arrive chapter by chapter.
     */
    internal fun search(query: String) {
        val text = query.trim()
        if (text.length < SEARCH_MIN_LENGTH) return
        if (text == searchResults.query && searchResults.status != SearchStatus.FAILED) return
        val opened = publication ?: return
        val engine = searcher ?: ReaderSearch(opened).also { searcher = it }
        searchJob?.cancel()
        shownHit = null
        searchResults = SearchResults(text, emptyList(), SearchStatus.RUNNING)
        searchJob = viewModelScope.launch {
            try {
                engine.run(text).collect { searchResults = searchResults.copy(hits = it) }
                searchResults = searchResults.copy(status = SearchStatus.DONE)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                searchResults = searchResults.copy(status = SearchStatus.FAILED)
            }
        }
    }

    /** Opens Define for [text], or closes it with null. */
    internal fun define(text: String?) {
        defineJob?.cancel()
        defineText = text?.takeIf { it.isNotBlank() }
        definitions = null
    }

    /** Looks [defineText] up once the dictionary is installed; nothing leaves the phone. */
    internal fun lookUp() {
        val text = defineText ?: return
        if (definitions != null || defineJob?.isActive == true) return
        defineJob = viewModelScope.launch {
            definitions =
                try {
                    DictionaryModel.define(app, text)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    emptyList()
                }
        }
    }

    /** Forgets looked-up entries, as after the dictionary is removed. */
    internal fun forgetDefinitions() {
        defineJob?.cancel()
        definitions = null
    }

    /**
     * Time left in the chapter and the book at the owner's measured speed, once positions are
     * known. PDFs and comics show the book part only.
     */
    internal fun timeLeftNow(): TimeLeft? {
        val shown = kind ?: return null
        val known = positions ?: return null
        val total = known.locators.size
        val position = lastLocation?.locations?.position ?: return null
        if (total <= 0) return null
        val chapterEnd =
            if (shown == ReaderKind.EPUB) chapterEndAt(position, known.chapterFirst, total)
            else null
        return timeLeft(position, total, chapterEnd, speed.positionsPerMinute(shown))
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

    /**
     * Other devices' latest Folio Link places in this book, newest first, and the old SSH position
     * ([kindle]), as the owner arranged the devices (named, merged, forgotten: [DeviceRoster]).
     * Without a Folio Link key, only the old position.
     */
    fun devicePlaces(): Flow<ReaderPlaces> {
        val old = kindle
        val document = linkDocument
        val phone = app.link.settings.phoneDeviceId
        val reports =
            if (document == null) kotlinx.coroutines.flow.flowOf(emptyList())
            else dao.kosyncReportsFlow(document)
        return kotlinx.coroutines.flow
            .combine(
                reports,
                dao.kosyncFlow(),
                dao.everyReading(),
                dao.deviceProfiles(),
                dev.sadking.folio.sync.SyncState.of(app).ereaders,
            ) { reports, all, snapshots, profiles, ereaders ->
                DeviceRoster(deviceSightings(ereaders.values, all, snapshots, phone), profiles)
                    .readerPlaces(
                        reports.filter { it.deviceId != phone && it.progress.startsWith("/") },
                        old,
                    )
            }
            .flowOn(Dispatchers.Default)
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

    /**
     * The top bar's bookmark: adds one for the page shown, or removes the page's bookmarks and
     * offers Undo through [removedMarks]. The icon follows from [pageMarks].
     */
    fun toggleBookmark() {
        val current = book ?: return
        val page = lastLocation ?: return
        val path = resourcePath(page.href.toString())
        val span = pageSpan?.takeIf { it.path == path }?.span
        viewModelScope.launch {
            try {
                val change =
                    bookmarkStore.toggle(current, page, span, byPosition = kind != ReaderKind.EPUB)
                if (change is BookmarkChange.Removed) removedMarks = change.marks
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                message = app.getString(R.string.reader_bookmark_failed)
            }
        }
    }

    /** Puts back the bookmarks removed last, after Undo. */
    fun undoBookmarkRemoval() {
        val removed = removedMarks ?: return
        removedMarks = null
        viewModelScope.launch {
            try {
                bookmarkStore.restore(removed)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                message = app.getString(R.string.reader_bookmark_failed)
            }
        }
    }

    /** The Undo offer ended without Undo. */
    fun settleBookmarkRemoval() {
        removedMarks = null
    }

    fun renameBookmark(id: String, title: String) {
        viewModelScope.launch {
            try {
                bookmarkStore.rename(id, title)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                message = app.getString(R.string.reader_bookmark_failed)
            }
        }
    }

    private fun watchBookmarks(opened: Book) {
        viewModelScope.launch {
            dao.phoneBookmarks(opened.id, opened.currentHash)
                .map { rows -> rows.map(::placed) }
                .flowOn(Dispatchers.Default)
                .collect { marks = it }
        }
    }

    private fun watchHighlights(bookId: String) {
        viewModelScope.launch {
            dao.bookAnnotations(bookId)
                .map { SyncAnnotations.live(it) }
                .flowOn(Dispatchers.Default)
                .collect { highlights = it }
        }
    }

    /**
     * Saves a highlight made on the phone in [colour] (the last one picked when null), or a note
     * when [note] has text. It goes to the e-reader at the next sync. Picking a colour makes it the
     * one Highlight uses next.
     */
    fun addHighlight(
        passage: SelectedPassage,
        note: String,
        chapter: String,
        colour: HighlightColour? = null,
    ) {
        val current = book ?: return
        if (colour != null && colour != highlightColour) {
            highlightColour = colour
            settings.edit().putString("highlightColour", colour.name).apply()
        }
        val look = colour ?: highlightColour
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
                            colour = look,
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

    /**
     * The live highlight the owner meant by selecting [passage] (the same passage, or one inside
     * it), or null when it is a new one. See [existingHighlight].
     */
    fun highlightFor(passage: SelectedPassage): BookAnnotation? =
        existingHighlight(passage, highlights)

    /**
     * Deletes highlight [id] softly: it stops drawing at once, Undo is offered
     * ([deletedHighlight]), and it can be restored from the book page for 30 days.
     */
    fun deleteHighlight(id: String) =
        changeHighlight({ highlightEdits.delete(id) }) { row -> row?.let { deletedHighlight = it } }

    /** Undo for the highlight just deleted, or any deleted in the last 30 days. */
    fun restoreHighlight(id: String) {
        if (deletedHighlight?.id == id) deletedHighlight = null
        changeHighlight({ highlightEdits.restore(id) })
    }

    /** The Undo offer for [deletedHighlight] is over (shown, or dismissed). */
    fun forgetDeletedHighlight() {
        deletedHighlight = null
    }

    /** Gives highlight [id] a new colour or an underline. */
    fun recolourHighlight(id: String, colour: HighlightColour) =
        changeHighlight({ highlightEdits.recolour(id, colour) })

    /** Sets highlight [id]'s note; a blank one removes it. */
    fun editHighlightNote(id: String, note: String) =
        changeHighlight({ highlightEdits.editNote(id, note) })

    /** Runs [change] off the main thread, then [then] with its result on it. */
    private fun <T> changeHighlight(change: suspend () -> T, then: (T) -> Unit = {}) {
        viewModelScope.launch {
            try {
                then(withContext(Dispatchers.IO) { change() })
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                message = app.getString(R.string.reader_highlight_change_failed)
            }
        }
    }

    /** Deletes a bookmark from the Highlights & notes list, offering Undo like the top bar. */
    fun deleteBookmark(id: String) {
        val mark = marks.firstOrNull { it.mark.id == id }?.mark ?: return
        viewModelScope.launch {
            try {
                bookmarkStore.delete(id)
                removedMarks = listOf(mark)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                message = app.getString(R.string.reader_bookmark_failed)
            }
        }
    }

    /** The Markdown export of this book's highlights and notes (M11 C). */
    internal val notesExport = NotesExport(app, dao)

    /** The remembered export folder's name ("" without one), or null when none is remembered. */
    var notesFolder by mutableStateOf(notesExport.folderName())
        private set

    /** Keeps the folder picked for exports and saves this book's notes there. */
    fun chooseNotesFolder(tree: android.net.Uri) {
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { notesExport.remember(tree) }
            if (name == null) {
                message = app.getString(R.string.notes_export_folder_refused)
                return@launch
            }
            notesFolder = name
            saveNotes()
        }
    }

    /**
     * Saves this book's notes to the remembered folder, replacing its earlier export there, and
     * says where they went. A folder Librillo can no longer reach is forgotten.
     */
    fun saveNotes() {
        val id = book?.id ?: return
        viewModelScope.launch {
            val tree = notesExport.folder()
            if (tree == null) {
                notesExport.forget()
                notesFolder = null
                message = app.getString(R.string.notes_export_folder_lost)
                return@launch
            }
            message =
                try {
                    val where = notesExport.save(tree, notesExport.file(id), id)
                    app.getString(R.string.notes_export_saved, where)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    app.getString(R.string.notes_export_folder_failed)
                }
        }
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
