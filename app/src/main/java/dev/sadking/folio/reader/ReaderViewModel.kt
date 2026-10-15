@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package dev.sadking.folio.reader

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import dev.sadking.folio.FolioApplication
import dev.sadking.folio.R
import dev.sadking.folio.core.Epub
import dev.sadking.folio.data.*
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubPreferences
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

    fun open(id: String) {
        if (opening) return
        opening = true
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
                        val file = File(found.currentPath).canonicalFile
                        require(
                            file.path.startsWith(
                                app.repository.root.canonicalPath + File.separator
                            ) && file.isFile
                        ) {
                            app.getString(R.string.msg_reader_epub_missing)
                        }
                        require(Epub.sha256(file) == found.currentHash) {
                            app.getString(R.string.msg_reader_epub_changed)
                        }
                        // Positions stay phone-only if the spine cannot be read.
                        spine = runCatching { Epub.spine(file) }.getOrDefault(emptyList())
                        linkDocument = runCatching { koreaderDigest(file) }.getOrNull()
                        found to file
                    }
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
                val asset =
                    retriever.retrieve(loaded.second.toUrl(isDirectory = false)).getOrElse {
                        error(app.getString(R.string.msg_reader_epub_unopenable))
                    }
                val opened =
                    PublicationOpener(
                            DefaultPublicationParser(app, http, retriever, pdfFactory = null)
                        )
                        .open(asset, allowUserInteraction = false)
                        .getOrElse {
                            asset.close()
                            error(app.getString(R.string.msg_reader_epub_protected))
                        }
                if (
                    opened.readingOrder.isEmpty() ||
                        opened.locatorFromLink(opened.readingOrder.first()) == null
                ) {
                    opened.close()
                    error(app.getString(R.string.msg_reader_no_chapters))
                }
                val saved = dao.phoneReading(id, loaded.first.currentHash)
                initialLocator = saved?.let {
                    runCatching { Locator.fromJSON(JSONObject(it.locator)) }.getOrNull()
                }
                lastLocation = initialLocator
                lastPhone = saved
                linkOffer = withContext(Dispatchers.IO) { newerLinkReport(saved) }
                kindle =
                    dao.allReading().firstOrNull {
                        it.bookId == id && it.deviceId == app.deviceSettings.load().id
                    }
                val queued =
                    app.getSharedPreferences("paired-folder", Application.MODE_PRIVATE)
                        .getString("incoming:$id", null)
                        ?.let { runCatching { JSONObject(it) }.getOrNull() }
                automaticKindleResume =
                    queued != null &&
                        queued.optString("revision") == loaded.first.currentHash &&
                        queued.optString("expectedPhone") == saved?.locator.orEmpty() &&
                        kindle?.position == queued.optString("position")
                bookSettings.getString(loaded.first.id, null)?.let {
                    use(ReaderTypography.fromJson(it, typography()))
                    bookOnly = true
                }
                book = loaded.first
                publication = opened
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
                        dao.update(stored.copy(status = "Reading"))
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

    suspend fun refreshKindle() {
        val current = book ?: return
        busy = true
        try {
            val snapshot = ReaderHandoff(app).retrieve(current)
            kindle = snapshot
            message =
                app.getString(
                    R.string.msg_reader_device_position,
                    app.getString(R.string.devices_jump),
                )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            message = failure.message ?: app.getString(R.string.msg_reader_device_position_failed)
        } finally {
            busy = false
        }
    }

    suspend fun sendPosition() {
        val current = book ?: return
        val saved = lastPhone ?: error(app.getString(R.string.msg_reader_turn_page_first))
        busy = true
        try {
            ReaderHandoff(app).send(current, saved)
            message = app.getString(R.string.msg_reader_position_sent)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            message = failure.message ?: app.getString(R.string.msg_reader_position_send_failed)
        } finally {
            busy = false
        }
    }

    override fun onCleared() {
        listen.stop()
        publication?.close()
    }
}
