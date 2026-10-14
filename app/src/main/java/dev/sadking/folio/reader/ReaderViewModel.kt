@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package dev.sadking.folio.reader

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import dev.sadking.folio.FolioApplication
import dev.sadking.folio.core.Epub
import dev.sadking.folio.data.*
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positions
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
    private val saveLock = Mutex()
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

    var volumeKeys by mutableStateOf(settings.getBoolean("volumeKeys", true))
        private set

    /** Full package spine as ZIP paths; maps KOReader DocFragments to Readium resources. */
    var spine: List<String> = emptyList()
        private set

    /** Total Readium positions ("pages" at the current layout), once computed. */
    var positionCount by mutableIntStateOf(0)
        private set

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
                        require(dao.removedEntry(id) == null) { "Restore this book before reading" }
                        val found = dao.book(id) ?: error("This book has no attached EPUB")
                        val file = File(found.currentPath).canonicalFile
                        require(
                            file.path.startsWith(
                                app.repository.root.canonicalPath + File.separator
                            ) && file.isFile
                        ) {
                            "EPUB is unavailable. Attach it from book details."
                        }
                        require(Epub.sha256(file) == found.currentHash) {
                            "EPUB changed outside Folio. Reimport it before reading."
                        }
                        // Positions stay phone-only if the spine cannot be read.
                        spine = runCatching { Epub.spine(file) }.getOrDefault(emptyList())
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
                        error("Could not open EPUB")
                    }
                val opened =
                    PublicationOpener(
                            DefaultPublicationParser(app, http, retriever, pdfFactory = null)
                        )
                        .open(asset, allowUserInteraction = false)
                        .getOrElse {
                            asset.close()
                            error("Unsupported or protected EPUB. Folio reads DRM-free EPUBs.")
                        }
                if (
                    opened.readingOrder.isEmpty() ||
                        opened.locatorFromLink(opened.readingOrder.first()) == null
                ) {
                    opened.close()
                    error(
                        "This EPUB has no readable chapters. Attach a valid EPUB or try another reader."
                    )
                }
                val saved = dao.phoneReading(id, loaded.first.currentHash)
                initialLocator = saved?.let {
                    runCatching { Locator.fromJSON(JSONObject(it.locator)) }.getOrNull()
                }
                lastLocation = initialLocator
                lastPhone = saved
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
                book = loaded.first
                publication = opened
                launch {
                    positionCount = runCatching { opened.positions().size }.getOrDefault(0)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                error = failure.message ?: "Could not open this book"
            }
        }
    }

    fun preferences(dark: Boolean) =
        EpubPreferences(
            fontSize = fontSize,
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
        turnWithVolume: Boolean = volumeKeys,
    ) {
        fontSize = size.coerceIn(75.0, 200.0)
        pageTheme = theme
        serif = useSerif
        scrolling = scroll
        lineHeight = spacing.coerceIn(1.0, 2.0)
        margins = pageMargins.coerceIn(0.0, 2.0)
        publisherStyles = bookStyles
        volumeKeys = turnWithVolume
        settings
            .edit()
            .putFloat("fontSize", fontSize.toFloat())
            .putString("theme", theme)
            .putBoolean("serif", serif)
            .putBoolean("scroll", scrolling)
            .putFloat("lineHeight", lineHeight.toFloat())
            .putFloat("margins", margins.toFloat())
            .putBoolean("publisherStyles", publisherStyles)
            .putBoolean("volumeKeys", volumeKeys)
            .apply()
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
                    message = "Position could not be saved. Try again before closing."
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
            message = "Bookmark saved on phone"
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
            message = "Kindle position retrieved. Choose Resume from Kindle to move there."
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            message = failure.message ?: "Could not retrieve Kindle position"
        } finally {
            busy = false
        }
    }

    suspend fun sendPosition() {
        val current = book ?: return
        val saved = lastPhone ?: error("Turn a page first to save a position")
        busy = true
        try {
            ReaderHandoff(app).send(current, saved)
            message =
                "Position request sent. Open this EPUB in KOReader → Folio bridge → Review phone reading position."
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            message = failure.message ?: "Could not send reading position"
        } finally {
            busy = false
        }
    }

    override fun onCleared() {
        publication?.close()
    }
}
