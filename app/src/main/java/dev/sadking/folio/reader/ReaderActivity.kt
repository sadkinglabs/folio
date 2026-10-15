@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    org.readium.r2.shared.ExperimentalReadiumApi::class,
)

package dev.sadking.folio.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import dev.sadking.folio.R
import dev.sadking.folio.data.PhoneBookmark
import dev.sadking.folio.ui.FolioTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject
import org.json.JSONTokener
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl

class ReaderActivity : FragmentActivity() {
    private val model: ReaderViewModel by viewModels()
    private var navigator: EpubNavigatorFragment? = null
    private var controls by mutableStateOf(true)
    private var panelOpen = false
    private var positionNavigation = false
    private var closing by mutableStateOf(false)
    private var navigationError by mutableStateOf<String?>(null)
    private val scripts by lazy {
        assets.open("reader/positions.js").bufferedReader().use { it.readText() } +
            "\n" +
            assets.open("reader/bridge.js").bufferedReader().use { it.readText() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Publication is asynchronous. Rebuild its fragment from the Room locator, including after
        // process death, rather than letting FragmentManager restore an uninitialised navigator.
        super.onCreate(null)
        enableEdgeToEdge()
        model.open(intent.getStringExtra(BOOK_ID).orEmpty())
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val nav = navigator
        if (nav != null && model.volumeKeys && !panelOpen) {
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
                model.volumeKeys &&
                !panelOpen &&
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
        val factory =
            EpubNavigatorFactory(publication)
                .createFragmentFactory(
                    initialLocator = model.lastLocation ?: model.initialLocator,
                    initialPreferences = model.preferences(dark),
                    listener =
                        object : EpubNavigatorFragment.Listener {
                            override fun onExternalLinkActivated(url: AbsoluteUrl) {
                                navigationError = getString(R.string.reader_external_link)
                            }
                        },
                    configuration =
                        EpubNavigatorFragment.Configuration { shouldApplyInsetsPadding = false },
                )
        supportFragmentManager.fragmentFactory = factory
        val fragment =
            factory.instantiate(classLoader, EpubNavigatorFragment::class.java.name)
                as EpubNavigatorFragment
        supportFragmentManager
            .beginTransaction()
            .setReorderingAllowed(true)
            .replace(container.id, fragment, "epub")
            .commitNow()
        navigator = fragment
        harden(fragment.requireView())
        // Listeners run in order until one consumes the event. Readium's adapter claims edge taps
        // (30% each side, in its own coordinate units) and arrow/page keys; any tap it leaves is
        // a centre tap and toggles the controls. No coordinate maths of our own: comparing tap
        // points with the fragment's pixel width failed to hide the controls on the emulator.
        fragment.addInputListener(DirectionalNavigationAdapter(fragment))
        fragment.addInputListener(
            object : InputListener {
                override fun onTap(event: TapEvent): Boolean {
                    controls = !controls
                    return true
                }
            }
        )
        lifecycleScope.launch {
            if (model.automaticKindleResume) {
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
            fragment.currentLocator.collectLatest { locator ->
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
                "typeof FolioReader !== 'undefined' && typeof FolioPositions !== 'undefined'"
            )
        if (ready != "true") evaluateJavascript("$scripts\ntrue;")
        return evaluateJavascript(call)
    }

    /**
     * Saves the current locator together with its KOReader pointer when one can be computed. A page
     * without translatable text still saves its native locator.
     */
    private suspend fun savePosition() {
        val nav = navigator ?: return
        val locator = nav.currentLocator.value
        val index = docFragmentIndex(locator.href.toString(), model.spine)
        val pointer =
            if (index == null) ""
            else
                try {
                    val raw = nav.bridge("JSON.stringify(FolioReader.capture($index));")
                    val quoted = raw?.let { JSONTokener(it).nextValue() as? String }
                    quoted
                        ?.let { runCatching { JSONTokener(it).nextValue() as? String }.getOrNull() }
                        .orEmpty()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    ""
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
        val nav = navigator ?: return false
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
            // evaluateJavascript waits for Readium's page to load; no delay guesses.
            val raw =
                nav.bridge("JSON.stringify(FolioReader.resolve(${JSONObject.quote(pointer)}));")
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

    @Composable
    private fun ReaderScreen(dark: Boolean) {
        val book = model.book
        var panel by rememberSaveable { mutableStateOf("") }
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        val pageDark = model.pageDark(dark)
        SideEffect { panelOpen = panel.isNotBlank() }
        BackHandler { if (panel.isNotBlank()) panel = "" else closeReader() }
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
            navigator?.submitPreferences(model.preferences(dark))
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
                    Surface(Modifier.fillMaxSize()) {
                        Column(
                            Modifier.fillMaxSize().padding(24.dp),
                            verticalArrangement =
                                Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        ) {
                            Text(
                                stringResource(R.string.reader_open_failed),
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Text(model.error.orEmpty())
                            Button(onClick = ::closeReader) {
                                Text(stringResource(R.string.reader_back_to_details))
                            }
                        }
                    }
                else if (model.publication == null)
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
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
                        modifier = Modifier.fillMaxSize(),
                    )
            }
            AnimatedVisibility(
                visible = controls || model.error != null,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
            ) {
                TopBar(book?.title, book?.authorLabel, enabled = book != null) { panel = it }
            }
            AnimatedVisibility(
                visible = controls && book != null && model.error == null,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
            ) {
                BottomBar()
            }
            model.linkOffer?.let { offer ->
                LinkOfferCard(
                    offer,
                    Modifier.align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = if (controls) 88.dp else 24.dp,
                        ),
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
                )
            }
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = if (controls) 72.dp else 16.dp),
            )
        }
        if (panel.isNotBlank())
            ModalBottomSheet(onDismissRequest = { panel = "" }) {
                when (panel) {
                    "Appearance" -> AppearancePanel(model)
                    "Contents" -> {
                        val flow = remember(book?.id) { model.bookmarks() }
                        val bookmarks by
                            flow?.collectAsState(initial = emptyList())
                                ?: remember { mutableStateOf(emptyList<PhoneBookmark>()) }
                        ContentsPanel(
                            model.publication?.tableOfContents.orEmpty(),
                            model.lastLocation?.href?.toString()?.let(::resourcePath),
                            bookmarks,
                            onLink = {
                                navigator?.go(it, animated = false)
                                panel = ""
                            },
                            onBookmark = {
                                val loc = Locator.fromJSON(JSONObject(it.locator))
                                if (loc != null) navigator?.go(loc, animated = false)
                                panel = ""
                            },
                            onDelete = { model.deleteBookmark(it.id) },
                        )
                    }
                    "Kindle" -> DevicesPanel(scope) { panel = "" }
                }
            }
    }

    @Composable
    private fun TopBar(
        title: String?,
        author: String?,
        enabled: Boolean,
        onPanel: (String) -> Unit,
    ) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        title ?: stringResource(R.string.reader_title_fallback),
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    author?.let {
                        Text(it, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = ::closeReader, enabled = !closing) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        stringResource(R.string.reader_back),
                    )
                }
            },
            actions = {
                IconButton(onClick = { onPanel("Contents") }, enabled = enabled) {
                    Icon(
                        Icons.AutoMirrored.Outlined.FormatListBulleted,
                        stringResource(R.string.reader_contents),
                    )
                }
                IconButton(onClick = { onPanel("Appearance") }, enabled = enabled) {
                    Icon(Icons.Outlined.TextFields, stringResource(R.string.reader_appearance))
                }
                IconButton(onClick = { onPanel("Kindle") }, enabled = enabled) {
                    Icon(Icons.Outlined.Devices, stringResource(R.string.reader_devices))
                }
            },
        )
    }

    @Composable
    private fun BottomBar() {
        val location = model.lastLocation
        val percent = ((location?.locations?.totalProgression ?: 0.0) * 100).toInt()
        val page = location?.locations?.position
        Surface(tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { navigator?.goBackward(animated = false) }) {
                    Icon(
                        Icons.AutoMirrored.Outlined.NavigateBefore,
                        stringResource(R.string.reader_previous_page),
                    )
                }
                Text(
                    location?.title.orEmpty(),
                    Modifier.weight(1f),
                    maxLines = 1,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    if (page != null && model.positionCount > 0)
                        stringResource(
                            R.string.reader_progress_pages,
                            page,
                            model.positionCount,
                            percent,
                        )
                    else stringResource(R.string.reader_progress_percent, percent),
                    style = MaterialTheme.typography.labelMedium,
                )
                IconButton(onClick = model::bookmark) {
                    Icon(Icons.Outlined.BookmarkAdd, stringResource(R.string.reader_bookmark))
                }
                IconButton(onClick = { navigator?.goForward(animated = false) }) {
                    Icon(
                        Icons.AutoMirrored.Outlined.NavigateNext,
                        stringResource(R.string.reader_next_page),
                    )
                }
            }
        }
    }

    @Composable
    private fun DevicesPanel(scope: CoroutineScope, close: () -> Unit) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.devices_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(stringResource(R.string.devices_body))
            model.kindle?.let {
                Text(
                    stringResource(
                        R.string.devices_last_kindle,
                        ((it.percent ?: 0.0) * 100).toInt(),
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            OutlinedButton(
                onClick = { scope.launch { model.refreshKindle() } },
                enabled = !model.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.devices_retrieve))
            }
            FilledTonalButton(
                onClick = {
                    scope.launch { resumeKindle() }
                    close()
                },
                enabled = !model.busy && model.kindle != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.devices_resume))
            }
            HorizontalDivider()
            Text(
                stringResource(R.string.devices_phone_to_kindle),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.devices_phone_to_kindle_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            val sendFailed = stringResource(R.string.reader_send_failed)
            Button(
                onClick = {
                    scope.launch {
                        try {
                            savePosition()
                            model.sendPosition()
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            navigationError = sendFailed
                        }
                    }
                },
                enabled = !model.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (model.busy) R.string.devices_working else R.string.devices_send
                    )
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    companion object {
        private const val BOOK_ID = "book_id"
        private const val SAVE_DEBOUNCE_MS = 1000L

        fun open(context: Context, bookId: String) {
            context.startActivity(
                Intent(context, ReaderActivity::class.java).putExtra(BOOK_ID, bookId)
            )
        }
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    options: List<Pair<String, Double>>,
    selected: Double,
    enabled: Boolean = true,
    onSelect: (Double) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (name, value) ->
                FilterChip(
                    selected = kotlin.math.abs(selected - value) < 0.01,
                    onClick = { onSelect(value) },
                    label = { Text(name) },
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
private fun AppearancePanel(model: ReaderViewModel) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.appearance_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.appearance_book_only))
                Text(
                    stringResource(R.string.appearance_book_only_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(model.bookOnly, model::keepForThisBook)
        }
        TextSizeStepper(model)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                    "System" to R.string.appearance_theme_system,
                    "Light" to R.string.appearance_theme_light,
                    "Paper" to R.string.appearance_theme_paper,
                    "Dark" to R.string.appearance_theme_dark,
                )
                .forEach { (name, label) ->
                    FilterChip(
                        model.pageTheme == name,
                        { model.appearance(theme = name) },
                        { Text(stringResource(label)) },
                    )
                }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                model.serif,
                { model.appearance(useSerif = true) },
                { Text(stringResource(R.string.appearance_serif)) },
            )
            FilterChip(
                !model.serif,
                { model.appearance(useSerif = false) },
                { Text(stringResource(R.string.appearance_sans)) },
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.appearance_book_styles))
                Text(
                    stringResource(R.string.appearance_book_styles_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(model.publisherStyles, { model.appearance(bookStyles = it) })
        }
        ChoiceRow(
            stringResource(R.string.appearance_spacing),
            listOf(
                stringResource(R.string.appearance_compact) to 1.3,
                stringResource(R.string.appearance_normal) to 1.5,
                stringResource(R.string.appearance_relaxed) to 1.8,
            ),
            model.lineHeight,
            enabled = !model.publisherStyles,
        ) {
            model.appearance(spacing = it)
        }
        ChoiceRow(
            stringResource(R.string.appearance_margins),
            listOf(
                stringResource(R.string.appearance_narrow) to 0.5,
                stringResource(R.string.appearance_normal) to 1.0,
                stringResource(R.string.appearance_wide) to 1.6,
            ),
            model.margins,
            enabled = !model.publisherStyles,
        ) {
            model.appearance(pageMargins = it)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.appearance_scrolling), Modifier.weight(1f))
            Switch(model.scrolling, { model.appearance(scroll = it) })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.appearance_volume_keys), Modifier.weight(1f))
            Switch(model.volumeKeys, { model.appearance(turnWithVolume = it) })
        }
        Text(stringResource(R.string.appearance_hint), style = MaterialTheme.typography.bodySmall)
    }
}

private fun flattenContents(links: List<Link>, depth: Int = 0): List<Pair<Link, Int>> =
    links.flatMap {
        listOf(it to depth) + flattenContents(it.children, (depth + 1).coerceAtMost(5))
    }

@Composable
private fun ContentsPanel(
    links: List<Link>,
    currentPath: String?,
    bookmarks: List<PhoneBookmark>,
    onLink: (Link) -> Unit,
    onBookmark: (PhoneBookmark) -> Unit,
    onDelete: (PhoneBookmark) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val entries = remember(links) { flattenContents(links) }
    val current =
        remember(entries, currentPath) {
            entries.indexOfLast { (link, _) -> resourcePath(link.url().toString()) == currentPath }
        }
    val contentsState =
        rememberLazyListState(initialFirstVisibleItemIndex = current.coerceAtLeast(0))
    val bookmarksState = rememberLazyListState()
    val currentLabel = stringResource(R.string.contents_current)
    Column {
        Text(
            stringResource(R.string.contents_title),
            Modifier.padding(horizontal = 24.dp),
            style = MaterialTheme.typography.headlineSmall,
        )
        PrimaryTabRow(tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.contents_tab)) })
            Tab(
                tab == 1,
                { tab = 1 },
                text = { Text(stringResource(R.string.contents_bookmarks_tab, bookmarks.size)) },
            )
        }
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 500.dp),
            state = if (tab == 0) contentsState else bookmarksState,
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (tab == 0) {
                if (links.isEmpty())
                    item { Text(stringResource(R.string.contents_empty), Modifier.padding(24.dp)) }
                items(entries.size) { index ->
                    val (link, depth) = entries[index]
                    val isCurrent = index == current
                    TextButton(
                        onClick = { onLink(link) },
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(start = (16 + depth * 12).dp, end = 16.dp)
                                .semantics {
                                    selected = isCurrent
                                    if (isCurrent) stateDescription = currentLabel
                                },
                    ) {
                        Text(
                            link.title ?: stringResource(R.string.contents_chapter_fallback),
                            Modifier.weight(1f),
                            fontWeight = if (isCurrent) FontWeight.Bold else null,
                        )
                    }
                }
            } else {
                if (bookmarks.isEmpty())
                    item { Text(stringResource(R.string.bookmarks_empty), Modifier.padding(24.dp)) }
                items(bookmarks, key = { it.id }) { mark ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { onBookmark(mark) }, modifier = Modifier.weight(1f)) {
                            Text(mark.title, Modifier.weight(1f))
                        }
                        IconButton(onClick = { onDelete(mark) }) {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                stringResource(R.string.bookmark_delete),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "Continue from Kobo at 43%?", offered once when another device read further on or back. */
@Composable
private fun LinkOfferCard(
    offer: dev.sadking.folio.data.KosyncReport,
    modifier: Modifier,
    onContinue: () -> Unit,
    onStay: () -> Unit,
) {
    Surface(
        modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(
                    R.string.reader_link_offer,
                    offer.device,
                    (offer.percentage * 100).toInt(),
                ),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onStay) { Text(stringResource(R.string.reader_link_stay)) }
                Button(onClick = onContinue) { Text(stringResource(R.string.reader_link_continue)) }
            }
        }
    }
}

/** Text size in steps, each applied at once, with "Aa" drawn at the chosen size and font. */
@Composable
private fun TextSizeStepper(model: ReaderViewModel) {
    val size = model.fontSize
    val smaller = stringResource(R.string.appearance_smaller)
    val larger = stringResource(R.string.appearance_larger)
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilledTonalIconButton(
            onClick = { model.appearance(size = steppedTextSize(size, up = false)) },
            enabled = size > MIN_TEXT,
            modifier = Modifier.semantics { contentDescription = smaller },
        ) {
            Text("A−", style = MaterialTheme.typography.labelLarge)
        }
        Column(
            Modifier.weight(1f).semantics(mergeDescendants = true) {},
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // A fixed-height box keeps the buttons still while the sample grows.
            Box(Modifier.height(72.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Aa",
                    fontSize = (22 * size / 100).sp,
                    fontFamily =
                        if (model.serif) androidx.compose.ui.text.font.FontFamily.Serif
                        else androidx.compose.ui.text.font.FontFamily.SansSerif,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
            Text(
                stringResource(R.string.appearance_text_size, size.toInt()),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        FilledTonalIconButton(
            onClick = { model.appearance(size = steppedTextSize(size, up = true)) },
            enabled = size < MAX_TEXT,
            modifier = Modifier.semantics { contentDescription = larger },
        ) {
            Text("A+", style = MaterialTheme.typography.labelLarge)
        }
    }
}
