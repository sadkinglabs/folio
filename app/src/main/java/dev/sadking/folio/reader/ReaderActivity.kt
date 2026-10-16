@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    org.readium.r2.shared.ExperimentalReadiumApi::class,
)

package dev.sadking.folio.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import dev.sadking.folio.R
import dev.sadking.folio.data.BookAnnotation
import dev.sadking.folio.data.KosyncReport
import dev.sadking.folio.data.PhoneBookmark
import dev.sadking.folio.ui.FolioMotion
import dev.sadking.folio.ui.FolioSpace
import dev.sadking.folio.ui.FolioTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import org.json.JSONTokener
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.image.ImageNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.pdf.PdfNavigatorFactory
import org.readium.r2.navigator.pdf.PdfNavigatorFragment
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.mediatype.MediaType

class ReaderActivity : FragmentActivity() {
    private val model: ReaderViewModel by viewModels()
    /** The page view for any format: page turns, jumps and the current place. */
    private var navigator: OverflowableNavigator? = null
    /**
     * The same navigator when the book is an EPUB; scripts, highlights and text settings need it.
     */
    private var epubNavigator: EpubNavigatorFragment? = null
    // Immersive on open: a tap in the centre brings the bars in.
    private var controls by mutableStateOf(false)
    private var panelOpen = false
    private var positionNavigation = false
    private var closing by mutableStateOf(false)
    private var navigationError by mutableStateOf<String?>(null)
    /** The highlight colour for the sentence being read, from the current theme. */
    private var listenTint = android.graphics.Color.YELLOW
    /** The highlight or note the owner tapped, shown read-only in a sheet. */
    private var shownHighlight by mutableStateOf<BookAnnotation?>(null)
    /** A passage waiting for its note in the Add note dialog. */
    private var pendingNote by mutableStateOf<PendingNote?>(null)
    private val scripts by lazy {
        listOf("positions.js", "bridge.js", "highlights.js").joinToString("\n") { name ->
            assets.open("reader/$name").bufferedReader().use { it.readText() }
        }
    }

    /**
     * The menu over selected text: Highlight, Add note and Copy. It replaces the WebView's own
     * menu, so Copy is offered here too.
     */
    private val selectionActions =
        object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(Menu.NONE, SELECTION_HIGHLIGHT, 0, R.string.reader_highlight_action)
                menu.add(Menu.NONE, SELECTION_NOTE, 1, R.string.reader_highlight_note_action)
                menu.add(Menu.NONE, SELECTION_COPY, 2, R.string.reader_highlight_copy_action)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId !in listOf(SELECTION_HIGHLIGHT, SELECTION_NOTE, SELECTION_COPY))
                    return false
                selectionAction(item.itemId).invokeOnCompletion { mode.finish() }
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) = Unit
        }

    /** A tap on a highlight opens its sheet instead of toggling the controls. */
    private val highlightTaps =
        object : DecorableNavigator.Listener {
            override fun onDecorationActivated(
                event: DecorableNavigator.OnActivatedEvent
            ): Boolean {
                val tapped =
                    model.highlights.firstOrNull { it.id == event.decoration.id } ?: return false
                shownHighlight = tapped
                return true
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Publication is asynchronous. Rebuild its fragment from the Room locator, including after
        // process death, rather than letting FragmentManager restore an uninitialised navigator.
        super.onCreate(null)
        enableEdgeToEdge()
        model.open(
            intent.getStringExtra(BOOK_ID).orEmpty(),
            when (intent.getStringExtra(START)) {
                START_BEGINNING -> ReaderStart.Beginning
                START_LISTEN -> ReaderStart.Listen
                START_DEVICE ->
                    intent.getStringExtra(POINTER)?.let(ReaderStart::Device) ?: ReaderStart.Resume
                else -> ReaderStart.Resume
            },
        )
        setContent {
            val appearance = remember { getSharedPreferences("appearance", MODE_PRIVATE) }
            val dark =
                when (appearance.getString("theme", "System")) {
                    "Dark" -> true
                    "Light" -> false
                    else -> isSystemInDarkTheme()
                }
            FolioTheme(dark, appearance.getBoolean("dynamic", true)) { ReaderScreen(dark) }
        }
    }

    override fun onPause() {
        model.saveOnPause()
        super.onPause()
    }

    /** Reads aloud from the first sentence on screen. */
    private fun startListening() {
        val nav = epubNavigator ?: return
        val publication = model.publication ?: return
        controls = false
        lifecycleScope.launch {
            val failed = getString(R.string.listen_failed)
            val bookId = model.book?.id ?: return@launch
            val page = suspend { nav.firstVisibleElementLocator() ?: nav.currentLocator.value }
            if (!model.listen.start(bookId, publication, page, { navigationError = failed }))
                navigationError = getString(R.string.listen_no_voice)
        }
    }

    /** Android's text-to-speech settings, where voices are added; general settings if hidden. */
    private fun openVoiceSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (_: android.content.ActivityNotFoundException) {
            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
        }
    }

    /** Volume keys turn pages when set, except over a sheet or while reading aloud. */
    private fun volumeTurnsPages() =
        model.volumeKeys && !panelOpen && model.listen.state == ListenState.OFF

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val nav = navigator
        if (nav != null && volumeTurnsPages()) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    nav.goForward(animated = false)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    nav.goBackward(animated = false)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // Swallow the matching key-up so the system volume panel does not appear.
        if (
            navigator != null &&
                volumeTurnsPages() &&
                keyCode in listOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP)
        )
            return true
        return super.onKeyUp(keyCode, event)
    }

    private fun closeReader() {
        if (closing) return
        closing = true
        lifecycleScope.launch {
            try {
                savePosition()
                finish()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                closing = false
                navigationError = getString(R.string.reader_save_failed)
            }
        }
    }

    private fun mount(container: FragmentContainerView, dark: Boolean) {
        if (navigator != null) return
        val publication = model.publication ?: return
        val kind = model.kind ?: return
        val place = model.lastLocation ?: model.initialLocator
        val factory: FragmentFactory
        val fragmentClass: Class<out Fragment>
        val tag: String
        when (kind) {
            ReaderKind.EPUB -> {
                factory =
                    EpubNavigatorFactory(publication)
                        .createFragmentFactory(
                            initialLocator = place,
                            initialPreferences = model.preferences(dark),
                            listener =
                                object : EpubNavigatorFragment.Listener {
                                    override fun onExternalLinkActivated(url: AbsoluteUrl) {
                                        navigationError = getString(R.string.reader_external_link)
                                    }
                                },
                            configuration =
                                EpubNavigatorFragment.Configuration {
                                    shouldApplyInsetsPadding = false
                                    selectionActionModeCallback = selectionActions
                                },
                        )
                fragmentClass = EpubNavigatorFragment::class.java
                tag = "epub"
            }
            ReaderKind.PDF -> {
                factory =
                    PdfNavigatorFactory(publication, PdfiumEngineProvider())
                        .createFragmentFactory(
                            initialLocator = place,
                            initialPreferences = model.pdfPreferences(),
                        )
                fragmentClass = PdfNavigatorFragment::class.java
                tag = "pdf"
            }
            ReaderKind.COMIC -> {
                factory = ImageNavigatorFragment.createFactory(publication, place)
                fragmentClass = ImageNavigatorFragment::class.java
                tag = "comic"
            }
        }
        supportFragmentManager.fragmentFactory = factory
        val fragment = factory.instantiate(classLoader, fragmentClass.name)
        supportFragmentManager
            .beginTransaction()
            .setReorderingAllowed(true)
            .replace(container.id, fragment, tag)
            .commitNow()
        val nav = fragment as OverflowableNavigator
        val epub = fragment as? EpubNavigatorFragment
        navigator = nav
        epubNavigator = epub
        // Only the EPUB page is a WebView; PDF and comic views load nothing from outside.
        if (epub != null) harden(fragment.requireView())
        // Listeners run in order until one consumes the event. Readium's adapter claims edge taps
        // (30% each side, in its own coordinate units) and arrow/page keys; any tap it leaves is
        // a centre tap and toggles the controls. No coordinate maths of our own: comparing tap
        // points with the fragment's pixel width failed to hide the controls on the emulator.
        nav.addInputListener(DirectionalNavigationAdapter(nav))
        nav.addInputListener(
            object : InputListener {
                override fun onTap(event: TapEvent): Boolean {
                    controls = !controls
                    return true
                }
            }
        )
        if (epub != null) {
            epub.addDecorationListener(HIGHLIGHT_GROUP, highlightTaps)
            lifecycleScope.launch { showHighlights(epub) }
        }
        // The sentence being read: highlight it and keep its page in view. The page turn saves the
        // place through the usual debounced save. On the activity's scope, so WebView calls stay
        // on the main thread. Read-aloud highlights are EPUB-only.
        if (epub != null)
            lifecycleScope.launch {
                // Nothing touches the page until read-aloud starts: an empty highlight at open
                // would queue a script on the page while it is still loading.
                var highlighted = false
                snapshotFlow { model.listen.utterance }
                    .collectLatest { spoken ->
                        if (spoken == null && !highlighted) return@collectLatest
                        highlighted = spoken != null
                        // With the screen off the page may not follow; save the sentence itself.
                        if (
                            spoken != null &&
                                !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                        )
                            model.listened(spoken)
                        // Turn to the sentence first: the highlight waits on the page's script and
                        // must never hold the page back.
                        if (spoken != null) epub.go(spoken, animated = false)
                        withTimeoutOrNull(HIGHLIGHT_WAIT_MS) {
                            epub.applyDecorations(
                                listOfNotNull(
                                    spoken?.let {
                                        Decoration(
                                            "spoken",
                                            it,
                                            Decoration.Style.Highlight(tint = listenTint),
                                        )
                                    }
                                ),
                                "listen",
                            )
                        }
                    }
            }
        lifecycleScope.launch {
            if (epub != null && model.automaticKindleResume) {
                try {
                    if (resumeKindle()) {
                        model.completedIncoming()
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    navigationError = getString(R.string.reader_kindle_resolve_failed)
                }
            }
            when (val start = model.takeStart()) {
                ReaderStart.Listen ->
                    if (epub != null) {
                        // Read-aloud asks the page for its first sentence; wait until it answers.
                        withTimeoutOrNull(CHAPTER_WAIT_MS) { epub.evaluateJavascript("true") }
                        startListening()
                    }
                is ReaderStart.Device ->
                    if (!resumeAt(start.pointer))
                        navigationError = getString(R.string.reader_link_failed)
                ReaderStart.Beginning,
                ReaderStart.Resume -> Unit
            }
            nav.currentLocator.collectLatest { locator ->
                if (positionNavigation) return@collectLatest
                model.location(locator)
                // One durable write per settled page: a further page turn cancels this one.
                delay(SAVE_DEBOUNCE_MS)
                try {
                    savePosition()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    navigationError = getString(R.string.reader_position_save_failed)
                }
            }
        }
    }

    /** Virtual Readium resources are intercepted locally; WebView cannot fetch arbitrary URLs. */
    private fun harden(view: View) {
        if (view is WebView) {
            view.settings.blockNetworkLoads = true
            view.settings.allowFileAccess = false
            view.settings.allowContentAccess = false
            view.settings.javaScriptCanOpenWindowsAutomatically = false
            view.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) harden(view.getChildAt(i))
            view.setOnHierarchyChangeListener(
                object : ViewGroup.OnHierarchyChangeListener {
                    override fun onChildViewAdded(parent: View?, child: View?) {
                        child?.let(::harden)
                    }

                    override fun onChildViewRemoved(parent: View?, child: View?) = Unit
                }
            )
        }
    }

    /** Runs a bridge call, injecting the 15 KB position scripts only once per chapter document. */
    private suspend fun EpubNavigatorFragment.bridge(call: String): String? {
        val ready =
            evaluateJavascript(
                "typeof FolioReader !== 'undefined' && typeof FolioPositions !== 'undefined' && " +
                    "typeof FolioHighlights !== 'undefined'"
            )
        if (ready != "true") evaluateJavascript("$scripts\ntrue;")
        return evaluateJavascript(call)
    }

    /**
     * Saves the current locator together with its KOReader pointer when one can be computed. A page
     * without translatable text still saves its native locator.
     */
    private suspend fun savePosition() {
        val any = navigator ?: return
        val nav = epubNavigator
        if (nav == null) {
            // PDFs and comics keep Readium's own place (page and progression); KOReader pointers
            // are EPUB-only.
            model.save(any.currentLocator.value)
            return
        }
        var locator = nav.currentLocator.value
        var pointer = ""
        try {
            // Readium reports a restored place before the page has scrolled there. Wait for the
            // page to catch up; if it never does, save without a pointer rather than a wrong one.
            for (attempt in 1..SETTLE_ATTEMPTS) {
                locator = nav.currentLocator.value
                val index = docFragmentIndex(locator.href.toString(), model.spine) ?: break
                val progression = locator.locations.progression
                if (nav.bridge("FolioReader.settled($progression);") == "true") {
                    val raw = nav.bridge("JSON.stringify(FolioReader.capture($index));")
                    val quoted = raw?.let { JSONTokener(it).nextValue() as? String }
                    pointer =
                        quoted
                            ?.let {
                                runCatching { JSONTokener(it).nextValue() as? String }.getOrNull()
                            }
                            .orEmpty()
                    break
                }
                delay(SETTLE_STEP_MS)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            pointer = ""
        }
        if (nav.currentLocator.value == locator) model.save(locator, pointer)
    }

    private suspend fun resumeKindle(): Boolean {
        val snapshot = model.kindle ?: return false
        val book = model.book ?: return false
        if (snapshot.documentHash != book.currentHash) {
            navigationError = getString(R.string.reader_kindle_other_revision)
            return false
        }
        return resumeAt(snapshot.position)
    }

    /**
     * Moves to the passage a KOReader pointer names, then saves it as the phone's place. Shared by
     * the SSH handoff and Folio Link. On failure the page stays where it was.
     */
    private suspend fun resumeAt(pointer: String): Boolean {
        val nav =
            epubNavigator
                ?: run {
                    navigationError = getString(R.string.reader_m9_devices_epub_only)
                    return false
                }
        val publication = model.publication ?: return false
        val index = xpointerSpineIndex(pointer, model.spine.size)
        if (index == null) {
            navigationError = getString(R.string.reader_kindle_unsupported)
            return false
        }
        // KOReader numbers every spine item; Readium's reading order may omit non-linear ones.
        val link =
            publication.readingOrder.firstOrNull {
                resourcePath(it.url().toString()) == model.spine[index]
            }
        if (link == null) {
            navigationError = getString(R.string.reader_kindle_not_in_reading_order)
            return false
        }
        positionNavigation = true
        try {
            val previous = nav.currentLocator.value
            val locator =
                Locator(
                    link.url(),
                    link.mediaType ?: org.readium.r2.shared.util.mediatype.MediaType.XHTML,
                )
            if (!nav.go(locator, animated = false)) {
                navigationError = getString(R.string.reader_kindle_chapter_failed)
                return false
            }
            // Wait until Readium shows the chapter before asking its page: a script sent while the
            // pager is still settling can go to a page that never answers. Each try has a limit,
            // so a slow page fails visibly instead of leaving the reader unable to save.
            val chapter = model.spine[index]
            withTimeoutOrNull(CHAPTER_WAIT_MS) {
                nav.currentLocator.first { resourcePath(it.href.toString()) == chapter }
            }
            val call = "JSON.stringify(FolioReader.resolve(${JSONObject.quote(pointer)}));"
            val raw =
                withTimeoutOrNull(BRIDGE_WAIT_MS) { nav.bridge(call) }
                    ?: run {
                        delay(500)
                        withTimeoutOrNull(BRIDGE_WAIT_MS) { nav.bridge(call) }
                    }
            val serialized = raw?.let { JSONTokener(it).nextValue() as? String }
            val resolved = serialized?.takeIf { it != "null" }?.let { JSONObject(it) }
            if (resolved == null) {
                nav.go(previous, animated = false)
                navigationError = getString(R.string.reader_kindle_anchor_failed)
                return false
            }
            val target =
                locator.copy(
                    locations =
                        Locator.Locations(
                            otherLocations = mapOf("cssSelector" to resolved.getString("selector"))
                        ),
                    text = Locator.Text.fromJSON(resolved.optJSONObject("text")),
                )
            if (!nav.go(target, animated = false)) {
                nav.go(previous, animated = false)
                navigationError = getString(R.string.reader_kindle_passage_failed)
                return false
            }
            nav.evaluateJavascript("true")
            savePosition()
            return true
        } finally {
            positionNavigation = false
            model.location(nav.currentLocator.value)
        }
    }

    /**
     * Runs one item of the selection menu on the selected text. Highlight saves at once; Add note
     * opens the note dialog first. The selection is cleared when it is done. Internal for tests.
     */
    internal fun selectionAction(itemId: Int): Job = lifecycleScope.launch {
        val nav = epubNavigator ?: return@launch
        try {
            val selection = nav.currentSelection() ?: return@launch
            if (itemId == SELECTION_COPY) {
                selection.locator.text.highlight?.takeIf { it.isNotBlank() }?.let(::copy)
                return@launch
            }
            val href = selection.locator.href.toString()
            val index = docFragmentIndex(href, model.spine)
            val passage = index?.let {
                val raw =
                    withTimeoutOrNull(BRIDGE_WAIT_MS) {
                        nav.bridge("JSON.stringify(FolioHighlights.selection($it));")
                    }
                parseSelection(raw, it, model.spine.size)
            }
            if (passage == null) {
                navigationError = getString(R.string.reader_highlight_failed)
                return@launch
            }
            val chapter = model.publication?.let { chapterTitle(it, href) }.orEmpty()
            if (itemId == SELECTION_NOTE) pendingNote = PendingNote(passage, chapter)
            else model.addHighlight(passage, "", chapter)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            navigationError = getString(R.string.reader_highlight_failed)
        } finally {
            nav.clearSelection()
        }
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(model.book?.title.orEmpty(), text))
    }

    /**
     * Draws the book's highlights and notes from every device. A chapter's are placed the first
     * time it is on screen, by resolving their KOReader pointers in that page; one that does not
     * resolve is not drawn, and the book page still lists it. Nothing touches the page while the
     * book has none.
     */
    private suspend fun showHighlights(epub: EpubNavigatorFragment) {
        val placed = mutableMapOf<String, Locator>()
        val tried = mutableSetOf<String>()
        var applied = emptyList<Decoration>()
        combine(
                snapshotFlow { model.highlights },
                epub.currentLocator.map { resourcePath(it.href.toString()) }.distinctUntilChanged(),
            ) { rows, path ->
                rows to path
            }
            .collectLatest { (rows, path) ->
                val all = placeableHighlights(rows, model.spine.size)
                val index = model.spine.indexOf(path)
                val wanted =
                    all.filter { it.chapter == index && it.annotation.id !in tried }
                        .map { it.annotation }
                if (index >= 0 && wanted.isNotEmpty()) {
                    val quotes = placeChapter(epub, path, wanted)
                    val link =
                        model.publication?.readingOrder?.firstOrNull {
                            resourcePath(it.url().toString()) == path
                        }
                    if (quotes != null && link != null) {
                        tried += wanted.map { it.id }
                        quotes.forEach { (id, quote) ->
                            placed[id] =
                                Locator(
                                    link.url(),
                                    link.mediaType ?: MediaType.XHTML,
                                    locations =
                                        Locator.Locations(
                                            otherLocations = mapOf("cssSelector" to quote.selector)
                                        ),
                                    text =
                                        Locator.Text(
                                            before = quote.before,
                                            highlight = quote.highlight,
                                            after = quote.after,
                                        ),
                                )
                        }
                    }
                }
                val byId = all.associateBy { it.annotation.id }
                val decorations = placed.mapNotNull { (id, locator) ->
                    byId[id]?.let {
                        Decoration(
                            id,
                            locator,
                            Decoration.Style.Highlight(tint = highlightTint(it.annotation)),
                        )
                    }
                }
                if (decorations != applied) {
                    withTimeoutOrNull(HIGHLIGHT_WAIT_MS) {
                        epub.applyDecorations(decorations, HIGHLIGHT_GROUP)
                    }
                    applied = decorations
                }
            }
    }

    /**
     * Asks the page on screen where [rows] sit, once it is the chapter at [path]. Null when the
     * page never answered for that chapter (the reader moved on, or it is still loading).
     */
    private suspend fun placeChapter(
        epub: EpubNavigatorFragment,
        path: String,
        rows: List<BookAnnotation>,
    ): Map<String, HighlightQuote>? {
        val call = placementCall(rows)
        repeat(PLACE_ATTEMPTS) {
            if (resourcePath(epub.currentLocator.value.href.toString()) != path) return null
            val raw = withTimeoutOrNull(BRIDGE_WAIT_MS) { epub.bridge(call) }
            val placement = parsePlacement(raw)
            if (placement != null && samePage(placement.path, path)) return placement.quotes
            delay(PLACE_RETRY_MS)
        }
        return null
    }

    /**
     * Slider release: opens the Readium position nearest [fraction] through the navigator, like any
     * other navigation, so the usual debounced save runs. Nothing moves until positions are known.
     */
    private fun jumpTo(fraction: Float) {
        val positions = model.positions ?: return
        val index = positionIndexAt(fraction, positions.locators.size)
        val target = positions.locators.getOrNull(index) ?: return
        navigator?.go(target, animated = false)
    }

    @Composable
    private fun ReaderScreen(dark: Boolean) {
        val book = model.book
        var panel by rememberSaveable { mutableStateOf<ReaderPanel?>(null) }
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        val density = LocalDensity.current
        var barHeight by remember { mutableStateOf(0.dp) }
        val pageDark = model.pageDark(dark)
        SideEffect { panelOpen = panel != null || shownHighlight != null || pendingNote != null }
        BackHandler { if (panel != null) panel = null else closeReader() }
        LaunchedEffect(model.message, navigationError) {
            val text = navigationError ?: model.message
            if (text != null) {
                snackbar.showSnackbar(text, duration = SnackbarDuration.Long)
                navigationError = null
                model.dismissMessage()
            }
        }
        LaunchedEffect(
            model.fontSize,
            model.pageTheme,
            model.serif,
            model.scrolling,
            model.lineHeight,
            model.margins,
            model.publisherStyles,
            dark,
        ) {
            epubNavigator?.submitPreferences(model.preferences(dark))
        }
        // Immersive reading: hiding controls also hides the system bars (API 30+, where the
        // reading area below is sized to ignore their visibility and so never re-paginates).
        LaunchedEffect(controls, pageDark, dark) {
            val bars = WindowCompat.getInsetsController(window, window.decorView)
            bars.isAppearanceLightStatusBars = !(if (controls) dark else pageDark)
            bars.isAppearanceLightNavigationBars = !(if (controls) dark else pageDark)
            bars.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (controls) bars.show(WindowInsetsCompat.Type.systemBars())
            else bars.hide(WindowInsetsCompat.Type.systemBars())
        }
        val listen = model.listen
        val highlight = MaterialTheme.colorScheme.primary.toArgb()
        SideEffect {
            listenTint = highlight
            // While listening, volume keys set the speech volume, even in the pause between
            // sentences when nothing is playing and Android would otherwise pick the ringer.
            volumeControlStream =
                if (listen.state == ListenState.OFF) AudioManager.USE_DEFAULT_STREAM_TYPE
                else AudioManager.STREAM_MUSIC
        }
        val fade = FolioMotion.standardSpec<Float>()
        val showBars = controls && book != null && model.error == null
        LaunchedEffect(showBars) { if (showBars) model.loadPositions() }
        // Controls are drawn over the page. The page area itself never changes size when they
        // appear or disappear, so Readium does not re-paginate and the reading position holds.
        Box(Modifier.fillMaxSize().background(Color(model.pageColor(dark)))) {
            Box(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
                    )
            ) {
                if (model.error != null)
                    ReaderFailed(
                        model.error.orEmpty(),
                        onBack = ::closeReader,
                        backEnabled = !closing,
                    )
                else if (model.publication == null)
                    LoadingIndicator(Modifier.align(Alignment.Center))
                else
                    AndroidView(
                        factory = {
                            FragmentContainerView(it).apply {
                                id = R.id.reader_container
                                layoutParams = ViewGroup.LayoutParams(-1, -1)
                                val container = this
                                mountReaderWhenReady(this@ReaderActivity, container) {
                                    mount(container, dark)
                                }
                            }
                        },
                        // Not a blur source: blurring the page means drawing the WebView into a
                        // layer the moment a bar appears, and on a phone that shows as the text
                        // blinking. The bars use a flat tint instead.
                        modifier = Modifier.fillMaxSize(),
                    )
            }
            AnimatedVisibility(
                visible = showBars,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(fade),
                exit = fadeOut(fade),
            ) {
                ReaderTopBar(
                    null,
                    book?.title,
                    enabled = book != null,
                    backEnabled = !closing,
                    insets =
                        WindowInsets.statusBarsIgnoringVisibility.union(
                            WindowInsets.displayCutout.only(WindowInsetsSides.Top)
                        ),
                    onBack = ::closeReader,
                    onPanel = { panel = it },
                    onListen = if (model.kind == ReaderKind.EPUB) ::startListening else null,
                )
            }
            AnimatedVisibility(
                visible = showBars,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(fade),
                exit = fadeOut(fade),
            ) {
                val location = model.lastLocation
                val positions = model.positions
                val progression = location?.locations?.totalProgression
                ReaderBottomBar(
                    null,
                    fraction = progressFraction(progression),
                    label =
                        pageLabel(progression, location?.locations?.position, model.positionCount),
                    ticks = positions?.ticks.orEmpty(),
                    pageCount = model.positionCount,
                    chapterAt = { fraction ->
                        positions?.let {
                            chapterTitleAt(fraction, it.chapterStarts, it.chapterTitles)
                        }
                    },
                    insets = WindowInsets.navigationBarsIgnoringVisibility,
                    onJump = ::jumpTo,
                    onPrevious = { navigator?.goBackward(animated = false) },
                    onNext = { navigator?.goForward(animated = false) },
                    onBookmark = model::bookmark,
                    modifier =
                        Modifier.onSizeChanged { barHeight = with(density) { it.height.toDp() } },
                )
            }
            // Above the bottom bar while it shows; otherwise just above the navigation bar.
            val floating =
                Modifier.align(Alignment.BottomCenter)
                    .windowInsetsPadding(
                        if (showBars) WindowInsets(0) else WindowInsets.navigationBars
                    )
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = if (showBars) barHeight + 12.dp else 24.dp,
                    )
            // While it reads, the player steps aside with the bars so it never covers the lines
            // being read; a tap in the centre brings both back, and a pause keeps it in view.
            if (
                listen.state != ListenState.OFF && (showBars || listen.state != ListenState.PLAYING)
            )
                ListenBar(
                    playing = listen.state == ListenState.PLAYING,
                    starting = listen.state == ListenState.STARTING,
                    speed = listen.speed,
                    modifier = floating,
                    onToggle = listen::toggle,
                    onPrevious = listen::previous,
                    onNext = listen::next,
                    onSpeed = listen::cycleSpeed,
                    onStop = listen::stop,
                    timerOn = listen.sleep != SleepTimer.OFF,
                    onOptions = { panel = ReaderPanel.LISTEN },
                )
            model.linkOffer?.let { offer ->
                LinkOfferCard(
                    offer.device,
                    (offer.percentage * 100).toInt(),
                    floating,
                    onContinue = {
                        scope.launch {
                            try {
                                // The offer can appear before the page is mounted.
                                withTimeoutOrNull(15_000) {
                                    while (navigator == null) delay(100)
                                }
                                if (!resumeAt(offer.progress))
                                    navigationError = getString(R.string.reader_link_failed)
                            } finally {
                                model.settleLinkOffer()
                            }
                        }
                    },
                    onStay = model::settleLinkOffer,
                    onTimeout = model::hideLinkOffer,
                )
            }
            SnackbarHost(snackbar, floating)
        }
        val naturalState by remember { KokoroModel.state(this@ReaderActivity) }.collectAsState()
        panel?.let { open ->
            ModalBottomSheet(
                onDismissRequest = { panel = null },
                shape = FolioSpace.sheetShape,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                when (open) {
                    ReaderPanel.LISTEN ->
                        ListenOptionsSheet(
                            listen.sleep,
                            listen.voices,
                            listen.voice,
                            onSleep = listen::sleepIn,
                            onVoice = listen::chooseVoice,
                            onAddVoices = ::openVoiceSettings,
                            natural =
                                NaturalVoiceRow(
                                    listen.naturalFits,
                                    naturalState,
                                    listen.natural,
                                    listen.naturalPace,
                                ),
                            onDownload = { KokoroModel.install(this@ReaderActivity) },
                        )
                    ReaderPanel.TEXT ->
                        if (model.kind == ReaderKind.EPUB) TextPanel()
                        else PanelNote(R.string.reader_m9_text_epub_only)
                    ReaderPanel.CONTENTS -> ContentsPanel(book?.id) { panel = null }
                    ReaderPanel.DEVICES ->
                        if (model.kind == ReaderKind.EPUB)
                            DevicesPanel(book?.id, scope) { panel = null }
                        else PanelNote(R.string.reader_m9_devices_epub_only)
                }
            }
        }
        shownHighlight?.let { shown ->
            ModalBottomSheet(
                onDismissRequest = { shownHighlight = null },
                shape = FolioSpace.sheetShape,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                HighlightSheet(shown, onCopy = { copy(highlightCopyText(shown)) })
            }
        }
        pendingNote?.let { pending ->
            HighlightNoteDialog(
                pending.passage.text,
                onSave = { note ->
                    pendingNote = null
                    model.addHighlight(pending.passage, note, pending.chapter)
                },
                onDismiss = { pendingNote = null },
            )
        }
    }

    @Composable
    private fun TextPanel() {
        val actions = remember {
            ReaderTextActions(
                onSize = { model.appearance(size = it) },
                onTheme = { model.appearance(theme = it) },
                onSerif = { model.appearance(useSerif = it) },
                onSpacing = { model.appearance(spacing = it) },
                onMargins = { model.appearance(pageMargins = it) },
                onBookStyles = { model.appearance(bookStyles = it) },
                onScroll = { model.appearance(scroll = it) },
                onBookOnly = model::keepForThisBook,
            )
        }
        ReaderTextSheet(
            ReaderTextState(
                model.fontSize,
                model.pageTheme,
                model.serif,
                model.scrolling,
                model.lineHeight,
                model.margins,
                model.publisherStyles,
                model.bookOnly,
            ),
            actions,
        )
    }

    /** One line in place of a sheet that only works for EPUB books. */
    @Composable
    private fun PanelNote(text: Int) {
        Text(
            stringResource(text),
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 32.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }

    @Composable
    private fun ContentsPanel(bookId: String?, close: () -> Unit) {
        val links = model.publication?.tableOfContents.orEmpty()
        val flat = remember(links) { flattenContents(links) }
        val entries =
            remember(flat) { flat.map { (link, depth) -> ContentsEntry(link.title, depth) } }
        val currentPath = model.lastLocation?.href?.toString()?.let(::resourcePath)
        val current =
            remember(flat, currentPath) {
                flat.indexOfLast { (link, _) -> resourcePath(link.url().toString()) == currentPath }
            }
        val flow = remember(bookId) { model.bookmarks() }
        val bookmarks by
            flow?.collectAsState(initial = emptyList())
                ?: remember { mutableStateOf(emptyList<PhoneBookmark>()) }
        ReaderContentsSheet(
            entries,
            current,
            bookmarks,
            onEntry = { index ->
                flat.getOrNull(index)?.let { (link, _) -> navigator?.go(link, animated = false) }
                close()
            },
            onBookmark = {
                val loc = Locator.fromJSON(JSONObject(it.locator))
                if (loc != null) navigator?.go(loc, animated = false)
                close()
            },
            onDelete = { model.deleteBookmark(it.id) },
        )
    }

    @Composable
    private fun DevicesPanel(bookId: String?, scope: CoroutineScope, close: () -> Unit) {
        val flow = remember(bookId) { model.linkReports() }
        val reports by
            flow?.collectAsState(initial = emptyList())
                ?: remember { mutableStateOf(emptyList<KosyncReport>()) }
        val fallbackName = stringResource(R.string.device_ereader_default)
        val rows =
            reports.map {
                DeviceRow(
                    it.deviceId,
                    it.device.ifBlank { fallbackName },
                    (it.percentage * 100).toInt(),
                    it.timestamp * 1000,
                )
            } +
                listOfNotNull(
                    model.kindle?.let {
                        DeviceRow(
                            LEGACY_ROW,
                            fallbackName,
                            ((it.percent ?: 0.0) * 100).toInt(),
                            it.fetchedAt,
                            legacy = true,
                        )
                    }
                )
        val linkFailed = stringResource(R.string.reader_link_failed)
        ReaderDevicesSheet(
            rows,
            busy = model.busy,
            onJump = { row ->
                close()
                scope.launch {
                    if (row.legacy) resumeKindle()
                    else {
                        val report = reports.firstOrNull { it.deviceId == row.id } ?: return@launch
                        if (!resumeAt(report.progress)) navigationError = linkFailed
                    }
                }
            },
        )
    }

    companion object {
        internal const val BOOK_ID = "book_id"
        private const val SAVE_DEBOUNCE_MS = 1000L
        private const val SETTLE_ATTEMPTS = 30
        private const val SETTLE_STEP_MS = 100L
        private const val CHAPTER_WAIT_MS = 10_000L
        private const val HIGHLIGHT_WAIT_MS = 3_000L
        private const val BRIDGE_WAIT_MS = 8_000L
        private const val PLACE_ATTEMPTS = 5
        private const val PLACE_RETRY_MS = 400L
        /** The Devices row for the SSH snapshot, which has no Folio Link device ID. */
        private const val LEGACY_ROW = "legacy-ssh"

        private const val START = "start"
        private const val POINTER = "pointer"
        private const val START_BEGINNING = "beginning"
        private const val START_LISTEN = "listen"
        private const val START_DEVICE = "device"

        fun open(context: Context, bookId: String, start: ReaderStart = ReaderStart.Resume) {
            val intent = Intent(context, ReaderActivity::class.java).putExtra(BOOK_ID, bookId)
            when (start) {
                ReaderStart.Resume -> Unit
                ReaderStart.Beginning -> intent.putExtra(START, START_BEGINNING)
                ReaderStart.Listen -> intent.putExtra(START, START_LISTEN)
                is ReaderStart.Device ->
                    intent.putExtra(START, START_DEVICE).putExtra(POINTER, start.pointer)
            }
            context.startActivity(intent)
        }
    }
}

private fun flattenContents(links: List<Link>, depth: Int = 0): List<Pair<Link, Int>> =
    links.flatMap {
        listOf(it to depth) + flattenContents(it.children, (depth + 1).coerceAtMost(5))
    }
