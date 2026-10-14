@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    org.readium.r2.shared.ExperimentalReadiumApi::class,
)

package dev.sadking.folio.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl

class ReaderActivity : FragmentActivity() {
    private val model: ReaderViewModel by viewModels()
    private var navigator: EpubNavigatorFragment? = null
    private var controls by mutableStateOf(true)
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

    private fun closeReader() {
        if (closing) return
        closing = true
        lifecycleScope.launch {
            try {
                savePosition()
                finish()
            } catch (_: Exception) {
                closing = false
                navigationError = "Could not save your place. Try again before closing."
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
                                navigationError =
                                    "External links are disabled while reading. Open them separately in your browser."
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
        fragment.addInputListener(
            object : InputListener {
                override fun onTap(event: TapEvent): Boolean {
                    val width = fragment.view?.width ?: return false
                    if (event.point.x in width * 0.25f..width * 0.75f) {
                        controls = !controls
                        return true
                    }
                    return false
                }
            }
        )
        lifecycleScope.launch {
            if (model.automaticKindleResume) {
                try {
                    if (resumeKindle()) {
                        model.completedIncoming()
                    }
                } catch (_: Exception) {
                    navigationError =
                        "Could not resolve the queued Kindle position. Your phone position is retained."
                }
            }
            fragment.currentLocator.collectLatest { locator ->
                if (positionNavigation) return@collectLatest
                model.location(locator)
                // Save the native locator immediately; translated anchors are optional and follow.
                try {
                    model.save(locator)
                } catch (_: Exception) {
                    navigationError = "Position could not be saved. Check phone storage."
                }
                delay(450)
                try {
                    savePosition()
                } catch (_: Exception) {
                    navigationError =
                        "Position translation is unavailable on this page. Phone reading is saved."
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

    private suspend fun savePosition() {
        val nav = navigator ?: return
        val locator = nav.currentLocator.value
        val index =
            model.publication?.readingOrder?.indexOfFirst { it.url().isEquivalent(locator.href) }
                ?: -1
        if (index < 0) {
            model.save(locator)
            return
        }
        val raw = nav.evaluateJavascript("$scripts\nJSON.stringify(FolioReader.capture($index));")
        val quoted = raw?.let { JSONTokener(it).nextValue() as? String }
        val pointer =
            quoted
                ?.let { runCatching { JSONTokener(it).nextValue() as? String }.getOrNull() }
                .orEmpty()
        if (nav.currentLocator.value == locator) model.save(locator, pointer)
    }

    private suspend fun resumeKindle(): Boolean {
        val nav = navigator ?: return false
        val publication = model.publication ?: return false
        val snapshot = model.kindle ?: return false
        val book = model.book ?: return false
        if (snapshot.documentHash != book.currentHash) {
            navigationError =
                "This Kindle position belongs to another EPUB revision. Transfer the matching file first."
            return false
        }
        val index = xpointerSpineIndex(snapshot.position, publication.readingOrder.size)
        if (index == null) {
            navigationError =
                "This KOReader position format cannot be translated safely. Your phone position is retained."
            return false
        }
        positionNavigation = true
        try {
            val previous = nav.currentLocator.value
            val link = publication.readingOrder[index]
            val locator =
                Locator(
                    link.url(),
                    link.mediaType ?: org.readium.r2.shared.util.mediatype.MediaType.XHTML,
                )
            if (!nav.go(locator, animated = false)) {
                navigationError = "Could not open the Kindle chapter"
                return false
            }
            // evaluateJavascript waits for Readium's page to load; no delay guesses.
            val raw =
                nav.evaluateJavascript(
                    "$scripts\nJSON.stringify(FolioReader.resolve(${JSONObject.quote(snapshot.position)}));"
                )
            val serialized = raw?.let { JSONTokener(it).nextValue() as? String }
            val resolved = serialized?.takeIf { it != "null" }?.let { JSONObject(it) }
            if (resolved == null) {
                nav.go(previous, animated = false)
                navigationError =
                    "This Kindle anchor cannot be resolved in the EPUB. Your phone position is retained."
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
                navigationError = "Could not navigate to the Kindle passage"
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
        BackHandler { if (panel.isNotBlank()) panel = "" else closeReader() }
        LaunchedEffect(model.message, navigationError) {
            val text = navigationError ?: model.message
            if (text != null) {
                snackbar.showSnackbar(text, duration = SnackbarDuration.Long)
                navigationError = null
                model.dismissMessage()
            }
        }
        LaunchedEffect(model.fontSize, model.pageTheme, model.serif, model.scrolling, dark) {
            navigator?.submitPreferences(model.preferences(dark))
        }
        Scaffold(
            topBar = {
                if (controls)
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    book?.title ?: "Reader",
                                    maxLines = 1,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                book?.let {
                                    Text(
                                        it.authorLabel,
                                        maxLines = 1,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = ::closeReader, enabled = !closing) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Return to book")
                            }
                        },
                        actions = {
                            IconButton(onClick = { panel = "Contents" }, enabled = book != null) {
                                Icon(Icons.Outlined.FormatListBulleted, "Contents and bookmarks")
                            }
                            IconButton(onClick = { panel = "Appearance" }, enabled = book != null) {
                                Icon(Icons.Outlined.TextFields, "Reading appearance")
                            }
                            IconButton(onClick = { panel = "Kindle" }, enabled = book != null) {
                                Icon(Icons.Outlined.Devices, "Continue between phone and Kindle")
                            }
                        },
                    )
            },
            bottomBar = {
                if (controls && book != null)
                    Surface(tonalElevation = 2.dp) {
                        Row(
                            Modifier.fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { navigator?.goBackward(animated = false) }) {
                                Icon(Icons.AutoMirrored.Outlined.NavigateBefore, "Previous page")
                            }
                            Text(
                                model.lastLocation?.title.orEmpty(),
                                Modifier.weight(1f),
                                maxLines = 1,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                "${((model.lastLocation?.locations?.totalProgression ?: 0.0) * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            IconButton(onClick = model::bookmark) {
                                Icon(Icons.Outlined.BookmarkAdd, "Bookmark this page")
                            }
                            IconButton(onClick = { navigator?.goForward(animated = false) }) {
                                Icon(Icons.AutoMirrored.Outlined.NavigateNext, "Next page")
                            }
                        }
                    }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (model.error != null)
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("Unable to open book", style = MaterialTheme.typography.headlineSmall)
                        Text(model.error.orEmpty())
                        Button(onClick = ::closeReader) { Text("Back to book details") }
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
                    "Kindle" ->
                        Column(
                            Modifier.fillMaxWidth().padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                "Continue on another device",
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Text(
                                "Positions are kept separately. Choose when to replace your current place. Both devices must have the same EPUB."
                            )
                            model.kindle?.let {
                                Text(
                                    "Last retrieved Kindle progress · ${((it.percent ?: 0.0) * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            OutlinedButton(
                                onClick = { scope.launch { model.refreshKindle() } },
                                enabled = !model.busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Retrieve saved Kindle position")
                            }
                            FilledTonalButton(
                                onClick = {
                                    scope.launch { resumeKindle() }
                                    panel = ""
                                },
                                enabled = !model.busy && model.kindle != null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Resume from Kindle on phone")
                            }
                            HorizontalDivider()
                            Text("Phone → Kindle", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Requires the updated Folio bridge on KOReader. Send the EPUB first. The bridge asks before moving your position; bookmarks and highlights are preserved.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Button(
                                onClick = {
                                    scope.launch {
                                        try {
                                            savePosition()
                                            model.sendPosition()
                                        } catch (_: Exception) {
                                            navigationError =
                                                "Could not prepare this position. Try another text page."
                                        }
                                    }
                                },
                                enabled = !model.busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    if (model.busy) "Working…" else "Send phone position for review"
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                }
            }
    }

    companion object {
        private const val BOOK_ID = "book_id"

        fun open(context: Context, bookId: String) {
            context.startActivity(
                Intent(context, ReaderActivity::class.java).putExtra(BOOK_ID, bookId)
            )
        }
    }
}

@Composable
private fun AppearancePanel(model: ReaderViewModel) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Make yourself comfortable", style = MaterialTheme.typography.headlineSmall)
        Text("Text size · ${model.fontSize.toInt()}%", style = MaterialTheme.typography.labelLarge)
        Slider(
            model.fontSize.toFloat(),
            { model.appearance(size = it.toDouble()) },
            valueRange = 75f..200f,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("System", "Light", "Paper", "Dark").forEach { name ->
                FilterChip(
                    model.pageTheme == name,
                    { model.appearance(theme = name) },
                    { Text(name) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(model.serif, { model.appearance(useSerif = true) }, { Text("Serif") })
            FilterChip(!model.serif, { model.appearance(useSerif = false) }, { Text("Sans serif") })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Continuous scrolling", Modifier.weight(1f))
            Switch(model.scrolling, { model.appearance(scroll = it) })
        }
        Text(
            "Your place is saved automatically. Tap the centre of a page to hide or show controls.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun flattenContents(links: List<Link>, depth: Int = 0): List<Pair<Link, Int>> =
    links.flatMap {
        listOf(it to depth) + flattenContents(it.children, (depth + 1).coerceAtMost(5))
    }

@Composable
private fun ContentsPanel(
    links: List<Link>,
    bookmarks: List<PhoneBookmark>,
    onLink: (Link) -> Unit,
    onBookmark: (PhoneBookmark) -> Unit,
    onDelete: (PhoneBookmark) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column {
        Text(
            "Your place in the book",
            Modifier.padding(horizontal = 24.dp),
            style = MaterialTheme.typography.headlineSmall,
        )
        PrimaryTabRow(tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Contents") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Bookmarks · ${bookmarks.size}") })
        }
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 500.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (tab == 0) {
                if (links.isEmpty())
                    item { Text("This EPUB has no table of contents.", Modifier.padding(24.dp)) }
                items(flattenContents(links)) { (link, depth) ->
                    TextButton(
                        onClick = { onLink(link) },
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(start = (16 + depth * 12).dp, end = 16.dp),
                    ) {
                        Text(link.title ?: "Chapter", Modifier.weight(1f))
                    }
                }
            } else {
                if (bookmarks.isEmpty())
                    item {
                        Text(
                            "Bookmark a page using the ribbon button while reading.",
                            Modifier.padding(24.dp),
                        )
                    }
                items(bookmarks, key = { it.id }) { mark ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { onBookmark(mark) }, modifier = Modifier.weight(1f)) {
                            Text(mark.title, Modifier.weight(1f))
                        }
                        IconButton(onClick = { onDelete(mark) }) {
                            Icon(Icons.Outlined.DeleteOutline, "Delete bookmark")
                        }
                    }
                }
            }
        }
    }
}
