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
import android.os.SystemClock
import android.view.ActionMode
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
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
import dev.sadking.folio.data.ReaderPlaces
import dev.sadking.folio.reader.dictionary.DefineSheet
import dev.sadking.folio.reader.dictionary.DictionaryModel
import dev.sadking.folio.reader.dictionary.DictionaryState
import dev.sadking.folio.reader.narration.NarrationStatus
import dev.sadking.folio.ui.FolioMotion
import dev.sadking.folio.ui.FolioSpace
import dev.sadking.folio.ui.FolioTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONTokener
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.css.Length
import org.readium.r2.navigator.epub.css.RsProperties
import org.readium.r2.navigator.image.ImageNavigatorFragment
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.pdf.PdfNavigatorFactory
import org.readium.r2.navigator.pdf.PdfNavigatorFragment
import org.readium.r2.navigator.preferences.ReadingProgression
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
    /** The highlight colour for the search match on the page. */
    private var searchTint = android.graphics.Color.YELLOW
    /** The read-aloud sentence last drawn on the page; read by the instrumented test. */
    @Volatile
    internal var appliedListen: Locator? = null
        private set

    /** The search decorations last drawn on the page; read by the instrumented test. */
    internal var appliedSearch: List<Decoration> = emptyList()
        private set

    /** Where the last touches went, step by step; read by the instrumented test. See [TapTrace]. */
    internal val tapTrace = TapTrace()

    /**
     * Where each highlight was found on its page, by id, once its chapter has been shown: the list
     * jumps straight there. Others are found from their KOReader pointer.
     */
    private var placedHighlights: Map<String, Locator> = emptyMap()

    /** The highlight or note the owner tapped, shown in its sheet; it closes once deleted. */
    private var shownHighlightId by mutableStateOf<String?>(null)
    /** A passage waiting for its note in the Add note dialog. */
    private var pendingNote by mutableStateOf<PendingNote?>(null)
    /** Counts touches and keys, so the screen may sleep after a while without any. */
    private var interactions by mutableIntStateOf(0)
    /** The highlight whose note is being written in the Edit note dialog. */
    private var editingNoteId by mutableStateOf<String?>(null)
    /** A passage waiting for its colour (Colour… in the selection menu). */
    private var pendingColour by mutableStateOf<PendingHighlight?>(null)
    private val scripts by lazy {
        listOf("positions.js", "bridge.js", "highlights.js").joinToString("\n") { name ->
            assets.open("reader/$name").bufferedReader().use { it.readText() }
        }
    }

    /**
     * The menu over selected text: Highlight, Add note, Define and Copy. It replaces the WebView's
     * own menu, so Copy is offered here too.
     */
    private val selectionActions =
        object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                SELECTION_MENU.forEachIndexed { order, (id, label) ->
                    menu.add(Menu.NONE, id, order, label)
                }
                selectionMode = mode
                // The page opens this menu for selected text: that is its word that text is
                // selected, until its next answer.
                selectedOnPage = true
                watchSelection()
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (SELECTION_MENU.none { it.first == item.itemId }) return false
                selectionAction(item.itemId).invokeOnCompletion { mode.finish() }
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                // The watch goes on while text is selected: the page may close its menu while a
                // handle is dragged.
                if (selectionMode === mode) selectionMode = null
            }
        }

    /** The text-selection menu while it is open: the page is busy with a selection then. */
    private var selectionMode: ActionMode? = null

    /** The open text-selection menu, for the instrumented test that chooses from it. */
    internal val openSelectionMenu: Menu?
        get() = selectionMode?.menu

    /**
     * Asks the page whether text is selected, while a finger is down on the reader's window, the
     * selection menu is open or text is selected; see [watchSelection].
     */
    private var selectionWatch: Job? = null

    /** Whether the page last said text is selected; false while it is not being asked. */
    private var selectedOnPage = false

    /** True between a touch's first finger down and its last finger up. */
    private var touching = false

    /** The other hand's taps and slides while text is selected; see ReaderSelectionTurn.kt. */
    private val selectionTaps by lazy {
        val config = ViewConfiguration.get(this)
        SelectionTaps(
            slop = config.scaledTouchSlop.toFloat(),
            swipe =
                maxOf(
                    config.scaledPagingTouchSlop.toFloat(),
                    SELECTION_SWIPE_DP * resources.displayMetrics.density,
                ),
        )
    }

    /** Copies of the touch [selectionTaps] holds back from the page, oldest first. */
    private val heldTouches = mutableListOf<MotionEvent>()

    /** Gives a held touch that stays down to the page; see [SelectionTaps.expire]. */
    private val expireHeldTouch = Runnable {
        val touch = selectionTaps.expire(SystemClock.uptimeMillis()) ?: return@Runnable
        touch.note?.let(tapTrace::add)
        replayHeldTouches()
    }

    /** Turns under a selection, one at a time, so each sees the page the last one left. */
    private val selectionTurns = Mutex()

    /**
     * A tap on a highlight opens its sheet instead of toggling the controls. Readium calls this on
     * the page's script thread, in the middle of the page's own tap, so the sheet is opened from
     * the main thread once that tap is over.
     */
    private val highlightTaps =
        object : DecorableNavigator.Listener {
            override fun onDecorationActivated(
                event: DecorableNavigator.OnActivatedEvent
            ): Boolean {
                val id = event.decoration.id
                if (model.highlights.none { it.id == id }) {
                    tapTrace.add("decoration tapped, no such highlight")
                    return false
                }
                tapTrace.add("highlight tapped")
                window.decorView.post { openHighlight(id) }
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

    override fun onUserInteraction() {
        super.onUserInteraction()
        interactions++
    }

    /** Follows edge slides for warmth and brightness; see ReaderEdgeSwipe.kt. */
    private val edgeSwipe by lazy {
        EdgeSwipeTracker(
            strip = EDGE_STRIP_DP * resources.displayMetrics.density,
            slop = ViewConfiguration.get(this).scaledTouchSlop.toFloat(),
            claimWithinMs = ViewConfiguration.getLongPressTimeout().toLong(),
        )
    }

    /** The comfort when the edge slide under the finger began. */
    private var edgeStart: ReaderComfort? = null

    /** The edge last slid on, for the level on screen. */
    private var edgeShown by mutableStateOf<EdgeSide?>(null)

    /** True while an edge slide is under the finger. */
    private var edgeSliding by mutableStateOf(false)

    /**
     * Edge slides work over a page with nothing on top and no text being selected (a selection's
     * handles may be dragged to the edge).
     */
    private fun edgeSwipesOn() =
        model.comfort.edgeSwipes &&
            navigator != null &&
            !panelOpen &&
            !closing &&
            selectionMode == null &&
            !selecting

    /**
     * Every touch on the reader's window passes here first, so an edge slide can be told from a
     * tap, a page swipe or a selection before the page acts on it. See [EdgeSwipeTracker].
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // A long-press can become a drag before the selection menu exists: watch the selection
        // from the first touch. The watch stops itself once no finger is down here and nothing
        // is selected.
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                if (selectionWatch?.isActive != true) watchSelection()
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> touching = false
        }
        val step =
            edgeSwipe.onTouch(
                ev.actionMasked,
                ev.x,
                ev.y,
                window.decorView.width.toFloat(),
                ev.eventTime,
                ev.pointerCount,
                edgeSwipesOn(),
            )
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL ->
                tapTrace.add(
                    "touch ${MotionEvent.actionToString(ev.actionMasked)} at " +
                        "${ev.rawX.roundToInt()},${ev.rawY.roundToInt()}: $step"
                )
        }
        val side = edgeSwipe.side
        when (step) {
            EdgeStep.PASS -> return toPage(ev)
            EdgeStep.CLAIM -> {
                // The page and the controls saw this touch start: end it there as cancelled.
                val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
                edgeStart = model.comfort
                edgeShown = side
                edgeSliding = true
            }
            EdgeStep.DRAG -> {
                val start = edgeStart
                if (start != null && side != null)
                    model.previewComfort(
                        edgeSwiped(start, side, edgeSwipe.travel, window.decorView.height.toFloat())
                    )
            }
            EdgeStep.END -> {
                edgeStart = null
                edgeSliding = false
                // Kept like the sliders keep theirs, for every book.
                model.comfort(model.comfort)
            }
        }
        return true
    }

    /**
     * Hands [ev] to the page, except the other hand's touches while text is selected: those are
     * held back until [SelectionTaps] knows whether they turn the page. A turn never reaches the
     * page, so the held finger's drag goes on undisturbed; anything else is given to the page.
     */
    private fun toPage(ev: MotionEvent): Boolean {
        val count = ev.pointerCount
        val touch =
            selectionTaps.onTouch(
                ev.actionMasked,
                ev.actionIndex,
                IntArray(count) { ev.getPointerId(it) },
                FloatArray(count) { ev.getX(it) },
                FloatArray(count) { ev.getY(it) },
                ev.eventTime,
                turnRules(),
                epubNavigator != null && !model.scrolling && selecting,
            )
        touch.note?.let(tapTrace::add)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            window.decorView.removeCallbacks(expireHeldTouch)
            dropHeldTouches()
        }
        if (touch.turn != 0) turnUnderSelection(forward = touch.turn > 0)
        return when (val page = touch.page) {
            PageTouch.Whole -> super.dispatchTouchEvent(ev)
            PageTouch.Withheld -> {
                dropHeldTouches()
                true
            }
            PageTouch.Held -> {
                heldTouches += MotionEvent.obtain(ev)
                if (ev.actionMasked == MotionEvent.ACTION_DOWN)
                    window.decorView.postDelayed(expireHeldTouch, SELECTION_TAP_MS + 1)
                true
            }
            PageTouch.Replay -> {
                replayHeldTouches()
                super.dispatchTouchEvent(ev)
            }
            is PageTouch.Only -> {
                val trimmed = ev.only(page)
                try {
                    super.dispatchTouchEvent(trimmed)
                } finally {
                    trimmed.recycle()
                }
            }
        }
    }

    /** Where the page is in the window, and how its taps and slides turn. */
    private fun turnRules(): TurnRules {
        val view = navigator?.publicationView
        val at = IntArray(2)
        view?.getLocationInWindow(at)
        val width = view?.width?.takeIf { it > 0 } ?: window.decorView.width
        val comfort = model.comfort
        return TurnRules(
            left = if (view != null) at[0].toFloat() else 0f,
            width = width.toFloat(),
            zones = comfort.tapZones,
            leftHanded = comfort.leftHanded,
            rtl = navigator?.overflow?.value?.readingProgression == ReadingProgression.RTL,
        )
    }

    /** Gives the page the touch held back so far, as it came. */
    private fun replayHeldTouches() {
        val replay = heldTouches.toList()
        heldTouches.clear()
        for (event in replay) {
            try {
                super.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }

    /** Forgets the touch held back so far: the page never sees it. */
    private fun dropHeldTouches() {
        heldTouches.forEach(MotionEvent::recycle)
        heldTouches.clear()
    }

    /**
     * Turns the page [forward] (or back) under the selection when the chapter has a page there;
     * else a short buzz, and the page stays. When the selection is already gone, the page turns as
     * a plain tap would. Waits for the turn to show, so a quick further tap turns on from the new
     * page.
     */
    private fun turnUnderSelection(forward: Boolean) {
        val nav = epubNavigator ?: return
        lifecycleScope.launch {
            selectionTurns.withLock {
                val raw =
                    try {
                        withTimeoutOrNull(BRIDGE_WAIT_MS) {
                            nav.bridge("JSON.stringify(FolioSelection.state());")
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        null
                    }
                val state = parseSelectionPage(raw)
                val way = if (forward) "next page" else "previous page"
                val animated: Boolean
                if (state != null && !state.selected) {
                    tapTrace.add("turn: nothing selected any more, plain $way")
                    animated = model.comfort.pageAnimation
                } else if (state?.canTurn(forward) != true) {
                    tapTrace.add("turn under the selection: no page there in this chapter")
                    window.decorView.performHapticFeedback(HapticFeedbackConstants.REJECT)
                    return@withLock
                } else {
                    tapTrace.add("turn under the selection: $way")
                    animated = false
                }
                val before = nav.currentLocator.value
                if (forward) nav.goForward(animated) else nav.goBackward(animated)
                withTimeoutOrNull(SELECTION_TURN_SETTLE_MS) {
                    nav.currentLocator.first { it != before }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        showSession()
    }

    override fun onPause() {
        // Time away from the page is not reading time.
        model.speed.interrupt()
        model.sessions.hidden()
        model.saveOnPause()
        super.onPause()
    }

    /** Starts the reading session once the book is on screen. Does nothing before that. */
    private fun showSession() {
        val nav = navigator ?: return
        val bookId = model.book?.id ?: return
        model.sessions.shown(bookId, nav.currentLocator.value.locations.totalProgression)
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
            val started =
                try {
                    model.listen.start(bookId, publication, page, { navigationError = failed })
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // Never let read-aloud close the book.
                    model.listen.stop()
                    navigationError = failed
                    return@launch
                }
            if (!started) navigationError = getString(R.string.listen_no_voice)
        }
    }

    /**
     * Shares a measurements or speed-test folder as one zip through the app's file provider. The
     * folder holds timings, lengths and the sample passage's audio, never book text.
     */
    private fun shareFolder(dir: java.io.File) {
        lifecycleScope.launch {
            val uri =
                try {
                    withContext(Dispatchers.IO) {
                        val out = java.io.File(cacheDir, "epub-share/narration-${dir.name}.zip")
                        zipFolder(dir, out)
                        FileProvider.getUriForFile(
                            this@ReaderActivity,
                            "$packageName.photos",
                            out,
                        )
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    navigationError = getString(R.string.listen_share_failed)
                    return@launch
                }
            val send =
                Intent(Intent.ACTION_SEND)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newUri(contentResolver, dir.name, uri)
            try {
                startActivity(Intent.createChooser(send, null))
            } catch (_: android.content.ActivityNotFoundException) {
                navigationError = getString(R.string.listen_share_failed)
            }
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
                    nav.goForward(animated = model.comfort.pageAnimation)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    nav.goBackward(animated = model.comfort.pageAnimation)
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
        keepPageFramesSmooth(container)
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
                                    // Readium's own default, pinned: lines stay a comfortable
                                    // length on a tablet or an unfolded phone.
                                    readiumCssRsProperties =
                                        RsProperties(maxLineLength = Length.Rem(40.0))
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
        // Listeners run in order until one consumes the event. Readium's adapter keeps the arrow
        // and page keys; taps go to PageTaps, which follows the owner's tap zones. It compares the
        // tap with the width of Readium's own page view, as the adapter does: comparing tap points
        // with the fragment's pixel width once failed to hide the controls on the emulator.
        // First, and consuming nothing: notes that the page passed a touch's end or a tap on.
        nav.addInputListener(
            object : InputListener {
                override fun onTap(event: TapEvent): Boolean {
                    tapTrace.add(
                        "page tap at ${event.point.x.roundToInt()},${event.point.y.roundToInt()}"
                    )
                    return false
                }

                override fun onDrag(event: DragEvent): Boolean {
                    if (event.type == DragEvent.Type.End) tapTrace.add("page touch end")
                    return false
                }
            }
        )
        nav.addInputListener(DirectionalNavigationAdapter(nav, tapEdges = emptySet()))
        nav.addInputListener(
            PageTaps(nav, { model.comfort }, onControls = { controls = !controls })
        )
        if (epub != null) {
            epub.addDecorationListener(HIGHLIGHT_GROUP, highlightTaps)
            lifecycleScope.launch { showHighlights(epub) }
            lifecycleScope.launch { showSearchHit(epub) }
            lifecycleScope.launch { measurePageSpan(epub) }
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
                            appliedListen = spoken
                        }
                    }
            }
        lifecycleScope.launch {
            // The book is on screen now. Before it is resumed, onResume starts the session instead.
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) showSession()
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
                model.sessions.page(locator.locations.totalProgression)
                // A settled page is a reading-speed sample when it follows the last one closely.
                locator.locations.position?.let { model.speed.observe(kind, it) }
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
     * the SSH handoff, Folio Link and the Highlights & notes list. On failure the page stays where
     * it was and [failed] (or a message about the e-reader's place) says so.
     */
    private suspend fun resumeAt(pointer: String, failed: String? = null): Boolean {
        val nav =
            epubNavigator
                ?: run {
                    navigationError = failed ?: getString(R.string.reader_m9_devices_epub_only)
                    return false
                }
        val publication = model.publication ?: return false
        val index = xpointerSpineIndex(pointer, model.spine.size)
        if (index == null) {
            navigationError = failed ?: getString(R.string.reader_kindle_unsupported)
            return false
        }
        // KOReader numbers every spine item; Readium's reading order may omit non-linear ones.
        val link =
            publication.readingOrder.firstOrNull {
                resourcePath(it.url().toString()) == model.spine[index]
            }
        if (link == null) {
            navigationError = failed ?: getString(R.string.reader_kindle_not_in_reading_order)
            return false
        }
        positionNavigation = true
        model.speed.interrupt()
        model.sessions.jumped()
        try {
            val previous = nav.currentLocator.value
            val locator =
                Locator(
                    link.url(),
                    link.mediaType ?: org.readium.r2.shared.util.mediatype.MediaType.XHTML,
                )
            if (!nav.go(locator, animated = false)) {
                navigationError = failed ?: getString(R.string.reader_kindle_chapter_failed)
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
                navigationError = failed ?: getString(R.string.reader_kindle_anchor_failed)
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
                navigationError = failed ?: getString(R.string.reader_kindle_passage_failed)
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
     * Runs one item of the selection menu on the selected text. Highlight saves at once in the last
     * colour; Colour… asks for one first; Add note opens the note dialog first. A passage that is
     * already highlighted (the same, or inside one) opens that highlight instead: its sheet, or its
     * note for Add note. The selection is cleared when it is done. Internal for tests.
     *
     * A sheet or dialog it asks for opens only after the menu has closed and the selection is
     * cleared, on a later turn of the main thread, never from inside the menu's own callback.
     */
    internal fun selectionAction(itemId: Int): Job = lifecycleScope.launch {
        val nav = epubNavigator ?: return@launch
        var open: (() -> Unit)? = null
        try {
            val selection = nav.currentSelection() ?: return@launch
            if (itemId == SELECTION_DEFINE) {
                // The selected words stay on the phone and are never logged.
                selection.locator.text.highlight
                    ?.takeIf { it.isNotBlank() }
                    ?.let { words -> open = { model.define(words) } }
                return@launch
            }
            if (itemId == SELECTION_COPY) {
                selection.locator.text.highlight?.takeIf { it.isNotBlank() }?.let(::copy)
                return@launch
            }
            val href = selection.locator.href.toString()
            val index = docFragmentIndex(href, model.spine)
            val passage = index?.let {
                val here =
                    placeableHighlights(model.highlights, model.spine.size)
                        .filter { placed -> placed.chapter == it }
                        .map { placed -> placed.annotation }
                val raw = withTimeoutOrNull(BRIDGE_WAIT_MS) { nav.bridge(selectionCall(it, here)) }
                parseSelection(raw, it, model.spine.size)
            }
            if (passage == null) {
                navigationError = getString(R.string.reader_highlight_failed)
                return@launch
            }
            val existing = model.highlightFor(passage)
            if (existing != null) {
                open =
                    if (itemId == SELECTION_NOTE) {
                        { editingNoteId = existing.id }
                    } else {
                        { openHighlight(existing.id) }
                    }
                return@launch
            }
            val chapter = model.publication?.let { chapterTitle(it, href) }.orEmpty()
            when (itemId) {
                SELECTION_NOTE -> open = { pendingNote = PendingNote(passage, chapter) }
                SELECTION_COLOUR -> open = { pendingColour = PendingHighlight(passage, chapter) }
                else -> model.addHighlight(passage, "", chapter)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            navigationError = getString(R.string.reader_highlight_failed)
        } finally {
            nav.clearSelection()
            // The menu closes when this job completes; what it asked for opens after that.
            open?.let { action -> window.decorView.post { action() } }
        }
    }

    /**
     * Opens highlight [id]'s sheet once the page has let go of the touch: the selection menu
     * closed, the selection cleared and the page's focus released, so nothing on the page is still
     * busy with a gesture when the sheet takes the screen. Main thread only.
     */
    private fun openHighlight(id: String) {
        tapTrace.add("highlight sheet asked")
        selectionMode?.finish()
        epubNavigator?.let {
            it.clearSelection()
            it.publicationView.clearFocus()
        }
        shownHighlightId = id
    }

    /**
     * Asks the page every [SELECTION_POLL_MS] whether text is selected, so the other hand's tap or
     * slide can turn the page under it (see [toPage] and ReaderSelectionTurn.kt). Runs while a
     * finger is down on the reader's window, the selection menu is open, or text is selected: the
     * finger holding a selection may be on a handle, which is a window of its own, so the reader
     * may see no finger down at all. Internal for tests: a selection made by script opens no menu.
     */
    internal fun watchSelection() {
        selectionWatch?.cancel()
        val nav = epubNavigator ?: return
        selectionWatch = lifecycleScope.launch {
            while (isActive) {
                selectedOnPage =
                    if (model.scrolling) false
                    else {
                        val raw =
                            try {
                                withTimeoutOrNull(BRIDGE_WAIT_MS) {
                                    nav.bridge("JSON.stringify(FolioSelection.state());")
                                }
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                null
                            }
                        parseSelectionPage(raw)?.selected == true
                    }
                if (!touching && selectionMode == null && !selectedOnPage) break
                delay(SELECTION_POLL_MS)
            }
        }
    }

    /**
     * Whether text is selected: from when the page says so (or opens its selection menu) until it
     * says it is not. Read by the instrumented test.
     */
    internal val selecting: Boolean
        get() = selectedOnPage

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
        placedHighlights = placed
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
                // A deleted highlight is no longer in the list, so it stops drawing at once.
                val decorations = placed.mapNotNull { (id, locator) ->
                    byId[id]?.let { Decoration(id, locator, highlightStyle(it.annotation)) }
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
     * Measures how much of the chapter a screen shows, so a bookmark made at another text size
     * still marks the page it falls on. Only chapters with a bookmark are measured, after the page
     * settles; until then a bookmark marks only the exact page it was made on.
     */
    private suspend fun measurePageSpan(epub: EpubNavigatorFragment) {
        snapshotFlow {
                val path = model.lastLocation?.href?.toString()?.let(::resourcePath)
                Triple(model.lastLocation, path, path != null && model.hasBookmarkIn(path))
            }
            .collectLatest { (page, path, wanted) ->
                if (page == null || path == null || !wanted) return@collectLatest
                delay(PAGE_SPAN_DELAY_MS)
                val span =
                    try {
                        withTimeoutOrNull(BRIDGE_WAIT_MS) {
                                epub.evaluateJavascript(pageSpanScript(model.scrolling))
                            }
                            ?.toDoubleOrNull()
                            ?.takeIf { it > 0.0 && it <= 1.0 }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        null
                    }
                if (span != null && resourcePath(epub.currentLocator.value.href.toString()) == path)
                    model.pageSpan = PageSpan(path, span)
            }
    }

    /**
     * Draws the search match the owner tapped, and removes it when the search closes. Redrawn after
     * a rotation, since the match is kept in the model.
     */
    private suspend fun showSearchHit(epub: EpubNavigatorFragment) {
        var drawn = false
        snapshotFlow { model.shownHit }
            .collectLatest { hit ->
                if (hit == null && !drawn) return@collectLatest
                drawn = hit != null
                val decorations =
                    listOfNotNull(
                        hit?.let {
                            Decoration(
                                SEARCH_DECORATION_ID,
                                it.locator,
                                Decoration.Style.Highlight(tint = searchTint),
                            )
                        }
                    )
                withTimeoutOrNull(HIGHLIGHT_WAIT_MS) {
                    epub.applyDecorations(decorations, SEARCH_GROUP)
                }
                appliedSearch = decorations
            }
    }

    /** Opens a search match: the page moves there and the match is highlighted. */
    private fun openHit(hit: SearchHit) {
        model.speed.interrupt()
        model.sessions.jumped()
        model.shownHit = hit
        model.panel = null
        navigator?.go(hit.locator, animated = false)
    }

    /**
     * Opens a row of Highlights & notes: the sheet closes and the page moves there. A highlight
     * goes to where it is drawn when its chapter has been shown, else to its KOReader pointer, as
     * an e-reader's place does; a phone bookmark to its stored place.
     */
    private fun openNote(item: NoteItem) {
        model.speed.interrupt()
        model.sessions.jumped()
        model.panel = null
        item.mark?.let { mark ->
            runCatching { Locator.fromJSON(JSONObject(mark.locator)) }
                .getOrNull()
                ?.let { navigator?.go(it, animated = false) }
            return
        }
        val row = item.annotation ?: return
        val placed = placedHighlights[row.id]
        if (placed != null) {
            navigator?.go(placed, animated = false)
            return
        }
        lifecycleScope.launch { resumeAt(row.position, getString(R.string.notes_jump_failed)) }
    }

    /** Shares this book's notes as a Markdown file through the share sheet. */
    private fun shareNotes() {
        val book = model.book ?: return
        lifecycleScope.launch {
            val chooser =
                try {
                    model.notesExport.shareIntent(model.notesExport.file(book.id), book.title)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    navigationError = getString(R.string.notes_export_failed)
                    return@launch
                }
            try {
                startActivity(chooser)
            } catch (_: android.content.ActivityNotFoundException) {
                navigationError = getString(R.string.notes_export_failed)
            }
        }
    }

    /** Closes the open sheet; closing the search also takes its highlight off the page. */
    private fun closePanel() {
        if (model.panel == ReaderPanel.SEARCH) model.shownHit = null
        model.panel = null
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
        model.speed.interrupt()
        model.sessions.jumped()
        navigator?.go(target, animated = false)
    }

    @Composable
    private fun ReaderScreen(dark: Boolean) {
        val book = model.book
        var panel by model::panel
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        // Undo and messages show over the Contents sheet while it is open, where the owner is.
        val sheetSnackbar = remember { SnackbarHostState() }
        val host = { if (model.panel == ReaderPanel.CONTENTS) sheetSnackbar else snackbar }
        // Closing the sheet ends an offer shown on it, rather than leaving it for the next time.
        LaunchedEffect(panel) {
            if (panel != ReaderPanel.CONTENTS) sheetSnackbar.currentSnackbarData?.dismiss()
        }
        val density = LocalDensity.current
        var barHeight by remember { mutableStateOf(0.dp) }
        val pageDark = model.pageDark(dark)
        val listen = model.listen
        // The natural voice cannot keep up: its sheet replaces any other until answered.
        val slow = listen.narration as? NarrationStatus.TooSlow
        LaunchedEffect(slow != null) { if (slow != null) panel = null }
        SideEffect {
            panelOpen =
                panel != null ||
                    shownHighlightId != null ||
                    pendingNote != null ||
                    editingNoteId != null ||
                    pendingColour != null ||
                    slow != null ||
                    model.defineText != null
        }
        // The speed test keeps the screen on, except in its long run, which may run with it off.
        val bench by NarrationBenchmark.state.collectAsState()
        val keepScreenOn =
            (bench as? NarrationBenchmark.State.Running)?.step.let {
                it != null && it != NarrationBenchmark.Step.SUSTAINED
            }
        // Reading keeps the screen on, when chosen, until ten minutes pass without a touch or key.
        // The flag belongs to this window, so it ends when the reader is not in front.
        var awake by remember { mutableStateOf(true) }
        // Counted in the effect, not read here: a touch must not recompose the whole screen.
        LaunchedEffect(Unit) {
            snapshotFlow { interactions }
                .collectLatest {
                    awake = true
                    delay(ReaderComfort.IDLE_SLEEP_MS)
                    awake = false
                }
        }
        val screenOn = keepScreenOn || (model.comfort.keepScreenOn && awake)
        DisposableEffect(screenOn) {
            if (screenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose {
                if (screenOn) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        // The reader's own brightness, for this window only; Auto leaves it to the phone.
        val brightness = model.comfort.windowBrightness()
        LaunchedEffect(brightness) {
            window.attributes = window.attributes.also { it.screenBrightness = brightness }
        }
        UndoOffer(
            model.removedMarks,
            stringResource(R.string.reader_bookmark_removed),
            stringResource(R.string.action_undo),
            host,
            SnackbarDuration.Short,
            onUndo = { model.undoBookmarkRemoval() },
            onSettled = model::settleBookmarkRemoval,
        )
        BackHandler { if (panel != null) closePanel() else closeReader() }
        LaunchedEffect(model.message, navigationError) {
            val text = navigationError ?: model.message
            if (text != null) {
                host().showSnackbar(text, duration = SnackbarDuration.Long)
                navigationError = null
                model.dismissMessage()
            }
        }
        // Undo after a highlight is deleted.
        UndoOffer(
            model.deletedHighlight,
            stringResource(R.string.reader_highlight_deleted),
            stringResource(R.string.reader_highlight_undo),
            host,
            SnackbarDuration.Long,
            onUndo = { model.restoreHighlight(it.id) },
            onSettled = model::forgetDeletedHighlight,
        )
        val naturalProblem = listen.naturalProblem
        LaunchedEffect(naturalProblem) {
            when (naturalProblem) {
                null -> Unit
                NaturalProblem.FELL_BACK -> {
                    snackbar.showSnackbar(
                        getString(R.string.listen_natural_unavailable),
                        duration = SnackbarDuration.Long,
                    )
                    listen.dismissNaturalProblem()
                }
                NaturalProblem.STOPPED -> {
                    val result =
                        snackbar.showSnackbar(
                            getString(R.string.listen_natural_stopped),
                            actionLabel = getString(R.string.listen_use_phone_voice),
                            withDismissAction = true,
                            duration = SnackbarDuration.Long,
                        )
                    if (result == SnackbarResult.ActionPerformed) {
                        listen.preferPhoneVoice()
                        startListening()
                    } else listen.dismissNaturalProblem()
                }
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
            model.twoPages,
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
        val highlight = MaterialTheme.colorScheme.primary.toArgb()
        val searchHighlight = MaterialTheme.colorScheme.tertiary.toArgb()
        SideEffect {
            listenTint = highlight
            searchTint = searchHighlight
            // While listening, volume keys set the speech volume, even in the pause between
            // sentences when nothing is playing and Android would otherwise pick the ringer.
            volumeControlStream =
                if (listen.state == ListenState.OFF) AudioManager.USE_DEFAULT_STREAM_TYPE
                else AudioManager.STREAM_MUSIC
        }
        val fade = FolioMotion.standardSpec<Float>()
        val showBars = controls && book != null && model.error == null
        val pageBookmarked = model.pageMarks.isNotEmpty()
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
                if (model.publication != null && model.error == null && pageBookmarked)
                    BookmarkRibbon(pageDark, Modifier.align(Alignment.TopEnd).padding(end = 20.dp))
            }
            // Over the page and its margins, under the bars; sheets are windows of their own.
            WarmthOverlay(model.comfort.warmth, Modifier.matchParentSize())
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
                    bookmarked = pageBookmarked,
                    onBookmark = model::toggleBookmark,
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
                val resources = LocalResources.current
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
                    onPrevious = { navigator?.goBackward(animated = model.comfort.pageAnimation) },
                    onNext = { navigator?.goForward(animated = model.comfort.pageAnimation) },
                    modifier =
                        Modifier.onSizeChanged { barHeight = with(density) { it.height.toDp() } },
                    timeLeft = model.timeLeftNow()?.let { timeLeftText(it, resources) },
                    onPanel = { panel = it },
                    enabled = book != null,
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
                    // While preparing, play is wanted: the button pauses, like during playback.
                    playing =
                        listen.state == ListenState.PLAYING ||
                            listen.state == ListenState.PREPARING,
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
                    note = listenNote(listen.state, listen.narration),
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
            // The level while sliding along an edge, then a moment after.
            var levelShown by remember { mutableStateOf(false) }
            LaunchedEffect(edgeSliding) {
                if (edgeSliding) levelShown = true
                else {
                    delay(EDGE_LEVEL_LINGER_MS)
                    levelShown = false
                }
            }
            EdgeLevelIndicator(
                edgeShown,
                model.comfort,
                levelShown,
                Modifier.align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                    .padding(top = 16.dp),
            )
        }
        val naturalState by remember { KokoroModel.state(this@ReaderActivity) }.collectAsState()
        val fasterState by
            remember { KokoroModel.state(this@ReaderActivity, KokoroModel.Variant.FP32) }
                .collectAsState()
        var testMinutes by rememberSaveable {
            mutableIntStateOf(NarrationBenchmark.Plan().sustainedMinutes)
        }
        panel?.let { open ->
            ModalBottomSheet(
                onDismissRequest = ::closePanel,
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
                                    readyAheadSeconds = listen.readyAheadSeconds,
                                    variant = listen.naturalVariant,
                                    faster = fasterState,
                                    recording = listen.recordMeasurements,
                                    hasRecording = listen.lastRecording != null,
                                ),
                            onDownload = { KokoroModel.install(this@ReaderActivity) },
                            narration =
                                NarrationActions(
                                    onVariant = listen::chooseVariant,
                                    onDownloadFaster = {
                                        KokoroModel.install(
                                            this@ReaderActivity,
                                            KokoroModel.Variant.FP32,
                                        )
                                    },
                                    onTest = { panel = ReaderPanel.LISTEN_TEST },
                                    onRecord = listen::setRecordMeasurements,
                                    onShareRecording = { listen.lastRecording?.let(::shareFolder) },
                                ),
                        )
                    ReaderPanel.LISTEN_TEST ->
                        NarrationTestSheet(
                            bench,
                            testMinutes,
                            variants =
                                remember {
                                    NarrationBenchmark.installedVariants(this@ReaderActivity).size
                                },
                            onMinutes = { testMinutes = it },
                            onStart = {
                                // Reading aloud would compete for the same processor and
                                // holds the voice the test loads: end it first.
                                listen.stop()
                                NarrationBenchmark.start(
                                    this@ReaderActivity,
                                    NarrationBenchmark.Plan(sustainedMinutes = testMinutes),
                                )
                            },
                            onCancel = NarrationBenchmark::cancel,
                            onShare = {
                                (bench as? NarrationBenchmark.State.Done)
                                    ?.exportDir
                                    ?.let(::shareFolder)
                            },
                        )
                    ReaderPanel.TEXT ->
                        if (model.kind == ReaderKind.EPUB) TextPanel()
                        else
                            Column(
                                Modifier.verticalScroll(rememberScrollState())
                                    .padding(bottom = 32.dp)
                            ) {
                                PanelNote(R.string.reader_m9_text_epub_only)
                                // PDFs scroll, so their taps only show the controls.
                                ReaderComfortSection(
                                    model.comfort,
                                    scrolling = model.kind == ReaderKind.PDF,
                                    onChange = model::comfort,
                                )
                            }
                    ReaderPanel.CONTENTS -> ContentsPanel(sheetSnackbar)
                    ReaderPanel.SEARCH ->
                        if (model.kind == ReaderKind.EPUB)
                            ReaderSearchSheet(
                                model.searchText,
                                model.searchResults,
                                onText = model::editSearch,
                                onSearch = model::search,
                                onHit = ::openHit,
                            )
                        else PanelNote(R.string.rd6_search_epub_only)
                    ReaderPanel.DEVICES ->
                        if (model.kind == ReaderKind.EPUB)
                            DevicesPanel(book?.id, scope) { panel = null }
                        else PanelNote(R.string.reader_m9_devices_epub_only)
                }
            }
        }
        slow?.let {
            ModalBottomSheet(
                onDismissRequest = listen::dismissSlow,
                shape = FolioSpace.sheetShape,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                TooSlowSheet(it, onChoice = listen::chooseSlow, onDismiss = listen::dismissSlow)
            }
        }
        ReaderHighlightSheets()
        model.defineText?.let { text ->
            ModalBottomSheet(
                onDismissRequest = { model.define(null) },
                shape = FolioSpace.sheetShape,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                val dictionary by
                    remember { DictionaryModel.state(this@ReaderActivity) }.collectAsState()
                LaunchedEffect(text, dictionary) {
                    if (dictionary == DictionaryState.Ready) model.lookUp()
                }
                DefineSheet(
                    text,
                    dictionary,
                    model.definitions,
                    onDownload = { DictionaryModel.install(this@ReaderActivity) },
                    onRemove = {
                        DictionaryModel.remove(this@ReaderActivity)
                        model.forgetDefinitions()
                    },
                )
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

    /**
     * The tapped highlight's sheet, its Edit note dialog, and the colour sheet for Colour…. The
     * sheet follows the live row, so a new colour shows at once and a deleted one closes it.
     */
    @Composable
    private fun ReaderHighlightSheets() {
        val shown = shownHighlightId?.let { id -> model.highlights.firstOrNull { it.id == id } }
        LaunchedEffect(shownHighlightId, shown == null) {
            // Deleted (here or by a sync): nothing left to show.
            if (shownHighlightId != null && shown == null) shownHighlightId = null
        }
        LaunchedEffect(shown?.id) { if (shown != null) tapTrace.add("highlight sheet shown") }
        shown?.let { row ->
            ModalBottomSheet(
                onDismissRequest = { shownHighlightId = null },
                // Always fully open: every action is on screen, however long the passage.
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                shape = FolioSpace.sheetShape,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                HighlightSheet(
                    row,
                    HighlightSheetActions(
                        onCopy = { copy(highlightCopyText(row)) },
                        onColour = { model.recolourHighlight(row.id, it) },
                        onNote = { editingNoteId = row.id },
                        onDelete = {
                            shownHighlightId = null
                            model.deleteHighlight(row.id)
                        },
                    ),
                )
            }
        }
        editingNoteId?.let { id ->
            val row = model.highlights.firstOrNull { it.id == id }
            if (row == null) {
                LaunchedEffect(id) { editingNoteId = null }
                return@let
            }
            HighlightNoteDialog(
                row.text,
                initial = row.note,
                onSave = { note ->
                    editingNoteId = null
                    model.editHighlightNote(id, note)
                },
                onDismiss = { editingNoteId = null },
            )
        }
        pendingColour?.let { pending ->
            ModalBottomSheet(
                onDismissRequest = { pendingColour = null },
                shape = FolioSpace.sheetShape,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                HighlightColourSheet(pending.passage.text) { colour ->
                    pendingColour = null
                    model.addHighlight(pending.passage, "", pending.chapter, colour)
                }
            }
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
                onTwoPages = { model.appearance(pages = it) },
            )
        }
        // The window's own width, so split screen and an unfolded phone count as they are.
        val windowWidth = LocalWindowInfo.current.containerSize.width
        val wide = with(LocalDensity.current) { windowWidth.toDp() } >= TWO_PAGES_MIN_WIDTH_DP.dp
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
                twoPages = model.twoPages,
                showTwoPages = wide && !model.scrolling,
            ),
            actions,
            more = { ReaderComfortSection(model.comfort, model.scrolling, model::comfort) },
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

    /**
     * The Contents sheet: the chapters, and Highlights & notes with its export. Undo and messages
     * show in [snackbar], over the sheet.
     */
    @Composable
    private fun ContentsPanel(snackbar: SnackbarHostState) {
        val links = model.publication?.tableOfContents.orEmpty()
        val flat = remember(links) { flattenContents(links) }
        val entries =
            remember(flat) { flat.map { (link, depth) -> ContentsEntry(link.title, depth) } }
        val currentPath = model.lastLocation?.href?.toString()?.let(::resourcePath)
        val current =
            remember(flat, currentPath) {
                flat.indexOfLast { (link, _) -> resourcePath(link.url().toString()) == currentPath }
            }
        val notes =
            remember(model.highlights, model.marks, model.spine) {
                noteItems(model.highlights, model.marks, model.spine)
            }
        var renaming by rememberSaveable { mutableStateOf<String?>(null) }
        val folderPicker =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
                if (tree != null) model.chooseNotesFolder(tree)
            }
        val pickFolder = { folderPicker.launch(model.notesExport.folder()) }
        Box {
            ReaderContentsSheet(
                entries,
                current,
                notes,
                onEntry = { index ->
                    model.speed.interrupt()
                    model.sessions.jumped()
                    flat.getOrNull(index)?.let { (link, _) ->
                        navigator?.go(link, animated = false)
                    }
                    model.panel = null
                },
                actions =
                    NotesActions(
                        onOpen = ::openNote,
                        onColour = { row, colour -> model.recolourHighlight(row.id, colour) },
                        onNote = { editingNoteId = it.id },
                        onCopy = { copy(highlightCopyText(it)) },
                        onDelete = { item ->
                            if (item.mark != null) model.deleteBookmark(item.id)
                            else model.deleteHighlight(item.id)
                        },
                        onRename = { renaming = it.id },
                    ),
                export =
                    ExportActions(
                        folder = model.notesFolder,
                        onShare = ::shareNotes,
                        onSave = {
                            if (model.notesFolder == null) pickFolder() else model.saveNotes()
                        },
                        onChooseFolder = pickFolder,
                    ),
            )
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
        model.marks
            .firstOrNull { it.mark.id == renaming }
            ?.let { placed ->
                RenameBookmarkDialog(
                    placed.mark.title,
                    onSave = {
                        renaming = null
                        model.renameBookmark(placed.mark.id, it)
                    },
                    onDismiss = { renaming = null },
                )
            }
    }

    @Composable
    private fun DevicesPanel(bookId: String?, scope: CoroutineScope, close: () -> Unit) {
        val flow = remember(bookId) { model.devicePlaces() }
        // Until the devices load, nothing: a forgotten old position must not flash in.
        val places by flow.collectAsState(initial = ReaderPlaces(emptyList(), null, null))
        val reports = places.reports
        val fallbackName = stringResource(R.string.device_ereader_default)
        val oldName = stringResource(R.string.dev_old_name)
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
                    places.old?.let {
                        DeviceRow(
                            LEGACY_ROW,
                            places.oldName ?: oldName,
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
        /** How long a page stays before it is measured for bookmarks. */
        private const val PAGE_SPAN_DELAY_MS = 300L
        private const val SEARCH_DECORATION_ID = "search-hit"
        /** The narrowest window that offers two pages side by side. */
        private const val TWO_PAGES_MIN_WIDTH_DP = 600
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

/**
 * A copy of this event with only the pointers [PageTouch.Only.ids], as its action, for the page:
 * the same times, positions (with their history, so a drag stays smooth) and device. Recycle it.
 */
private fun MotionEvent.only(touch: PageTouch.Only): MotionEvent {
    val indices = touch.ids.map(::findPointerIndex)
    val properties =
        Array(indices.size) { i ->
            MotionEvent.PointerProperties().also { getPointerProperties(indices[i], it) }
        }
    // A sample's coordinates: a historical one, or the current one when [sample] is -1.
    fun coords(sample: Int) =
        Array(indices.size) { i ->
            MotionEvent.PointerCoords().also {
                if (sample < 0) getPointerCoords(indices[i], it)
                else getHistoricalPointerCoords(indices[i], sample, it)
            }
        }
    val action =
        when (touch.action) {
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP ->
                touch.action or (touch.actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            else -> touch.action
        }
    val history = if (touch.action == MotionEvent.ACTION_MOVE) historySize else 0
    val event =
        MotionEvent.obtain(
            downTime,
            if (history > 0) getHistoricalEventTime(0) else eventTime,
            action,
            indices.size,
            properties,
            coords(if (history > 0) 0 else -1),
            metaState,
            buttonState,
            xPrecision,
            yPrecision,
            deviceId,
            edgeFlags,
            source,
            flags,
        )
    if (history > 0) {
        for (sample in 1 until history) event.addBatch(
            getHistoricalEventTime(sample),
            coords(sample),
            metaState,
        )
        event.addBatch(eventTime, coords(-1), metaState)
    }
    return event
}
