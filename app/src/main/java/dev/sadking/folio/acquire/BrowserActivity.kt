package dev.sadking.folio.acquire

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sadking.folio.R
import dev.sadking.folio.core.Epub
import dev.sadking.folio.ui.FolioTheme
import kotlinx.coroutines.launch

/** The browser's screens. */
internal enum class BrowserPage {
    START,
    SOURCES,
    EDITOR,
    WEB,
}

/**
 * Where the browser opens, from its intent. In find mode with a title, the first visible source
 * that can search opens on that search; without one, the start page. Sources mode ignores the rest.
 * Pure, so it is tested on the JVM.
 */
internal data class BrowserStart(
    val page: BrowserPage,
    val query: String = "",
    val wishId: String? = null,
    val sourceId: String? = null,
    val url: String? = null,
) {
    /** The screen Back returns to from a web page; Back there closes the browser. */
    val root: BrowserPage
        get() = if (page == BrowserPage.SOURCES) BrowserPage.SOURCES else BrowserPage.START

    companion object {
        fun from(
            mode: String?,
            title: String?,
            authors: List<String>,
            wishId: String?,
            visible: List<BookSource>,
        ): BrowserStart {
            if (mode == BrowserLaunch.MODE_SOURCES) return BrowserStart(BrowserPage.SOURCES)
            val query = BookSources.query(title.orEmpty(), authors)
            val wish = wishId?.takeIf { it.isNotBlank() }
            val first =
                visible.firstNotNullOfOrNull { s ->
                    BookSources.searchFor(s, query)?.let { s to it }
                } ?: return BrowserStart(BrowserPage.START, query, wish)
            return BrowserStart(BrowserPage.WEB, query, wish, first.first.id, first.second)
        }

        fun from(intent: Intent, visible: List<BookSource>): BrowserStart =
            from(
                intent.getStringExtra(BrowserLaunch.EXTRA_MODE),
                intent.getStringExtra(BrowserLaunch.EXTRA_TITLE),
                intent.getStringArrayExtra(BrowserLaunch.EXTRA_AUTHORS)?.toList().orEmpty(),
                intent.getStringExtra(BrowserLaunch.EXTRA_WISH_ID),
                visible,
            )
    }
}

/**
 * The in-app browser (M5): a start page and the book sources in Compose, web pages in a locked-down
 * WebView. A book the page offers is downloaded with the page's cookies and imported like any
 * other; with a Want to Read entry, it is linked to it. Not exported; rotation and folding don't
 * rebuild it, so the page and its history stay.
 */
class BrowserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // An outcome left from an earlier visit is not news any more.
        if (savedInstanceState == null) AcquireDownloads.dismiss()
        val store = BookSourceStore(this)
        val start = BrowserStart.from(intent, store.visible())
        setContent {
            val appearance = remember { getSharedPreferences("appearance", MODE_PRIVATE) }
            val dark =
                when (appearance.getString("theme", "System")) {
                    "Dark" -> true
                    "Light" -> false
                    else -> isSystemInDarkTheme()
                }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            FolioTheme(dark, appearance.getBoolean("dynamic", true)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    BrowserApp(start, store, onFinish = ::finish)
                }
            }
        }
    }
}

@Composable
internal fun BrowserApp(start: BrowserStart, store: BookSourceStore, onFinish: () -> Unit) {
    val sources by store.sources.collectAsStateWithLifecycle()
    val visible = sources.filter { !it.hidden }
    var page by rememberSaveable { mutableStateOf(start.page) }
    var query by rememberSaveable { mutableStateOf(start.query) }
    var sourceId by rememberSaveable { mutableStateOf(start.sourceId) }
    var url by rememberSaveable { mutableStateOf(start.url) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }

    fun open(source: BookSource, search: Boolean) {
        sourceId = source.id
        url = (if (search) BookSources.searchFor(source, query) else null) ?: source.startUrl
        page = BrowserPage.WEB
    }

    when (page) {
        BrowserPage.START ->
            FindStartScreen(
                query = query,
                onQuery = { query = it },
                sources = visible,
                onOpen = { open(it, search = true) },
                onManage = { page = BrowserPage.SOURCES },
                onClose = onFinish,
            )
        BrowserPage.SOURCES -> {
            val back = { if (start.root == BrowserPage.SOURCES) onFinish() else page = start.root }
            BackHandler(onBack = back)
            SourcesScreen(
                sources = sources,
                onOpen = { open(it, search = false) },
                onHidden = { source, hidden -> store.setHidden(source.id, hidden) },
                onEdit = {
                    editing = it.id
                    page = BrowserPage.EDITOR
                },
                onRemove = { store.remove(it.id) },
                onAdd = {
                    editing = ""
                    page = BrowserPage.EDITOR
                },
                onBack = back,
            )
        }
        BrowserPage.EDITOR -> {
            BackHandler { page = BrowserPage.SOURCES }
            key(editing) {
                SourceEditor(
                    initial = editing?.let(store::source),
                    onSave = {
                        store.save(it)
                        page = BrowserPage.SOURCES
                    },
                    onCancel = { page = BrowserPage.SOURCES },
                )
            }
        }
        BrowserPage.WEB -> {
            val source = sourceId?.let { id -> sources.firstOrNull { it.id == id } }
            val address = url
            if (source == null || address == null) {
                LaunchedEffect(Unit) { page = start.root }
                return
            }
            val searching = query.isNotBlank() && address != source.startUrl
            val others = visible.filter {
                it.id != source.id && (!searching || it.searchUrl != null)
            }
            BrowserWebPage(
                source = source,
                url = address,
                others = others,
                searching = searching,
                wishId = start.wishId,
                onBack = { page = start.root },
                onClose = onFinish,
                onOther = { open(it, search = searching) },
            )
        }
    }
}

/** One site in the locked-down WebView, with the frame, downloads and Back through its history. */
@Composable
private fun BrowserWebPage(
    source: BookSource,
    url: String,
    others: List<BookSource>,
    searching: Boolean,
    wishId: String?,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onOther: (BookSource) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val download by AcquireDownloads.state.collectAsStateWithLifecycle()
    var loading by remember { mutableIntStateOf(100) }
    var host by remember { mutableStateOf("") }
    // The page last shown for the address asked for, so a rebuilt screen comes back to it.
    // Never logged.
    val requested by rememberUpdatedState(url)
    val latestBack by rememberUpdatedState(onBack)
    var lastFor by rememberSaveable { mutableStateOf<String?>(null) }
    var lastPage by rememberSaveable { mutableStateOf<String?>(null) }
    val blocked = stringResource(R.string.acq_blocked_link)
    val notABook = stringResource(R.string.acq_not_a_book)
    val oneAtATime = stringResource(R.string.acq_one_at_a_time)
    fun say(text: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }
    val onDownload by rememberUpdatedState {
        page: String?,
        address: String,
        agent: String?,
        disposition: String?,
        mime: String?,
        length: Long ->
        when (
            val decision = DownloadSniffer.decide(address, disposition, mime, length, Epub.MAX_FILE)
        ) {
            is DownloadDecision.Fetch ->
                if (
                    !AcquireDownloads.start(
                        context,
                        DownloadRequest(decision.url, agent, page),
                        decision.name,
                        wishId,
                    )
                )
                    say(oneAtATime)
            is DownloadDecision.Refuse -> AcquireDownloads.report(context, decision.outcome)
            DownloadDecision.NotABook -> say(notABook)
        }
    }
    val webView =
        remember(source.id) {
            WebView(context).also { view ->
                BrowserWebView.configure(
                    view,
                    javaScript = source.javaScript,
                    onBlocked = { say(blocked) },
                    onProgress = { loading = it },
                    onPage = { address ->
                        host =
                            address
                                ?.let { runCatching { it.toUri().host }.getOrNull() }
                                ?.removePrefix("www.")
                                .orEmpty()
                        if (address != null && BrowserPolicy.decide(address) == Navigation.Load) {
                            lastFor = requested
                            lastPage = address
                        }
                    },
                    onGone = { latestBack() },
                )
                view.setDownloadListener { address, agent, disposition, mime, length ->
                    onDownload(view.url, address, agent, disposition, mime, length)
                }
            }
        }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }
    LaunchedEffect(webView, url) { webView.loadUrl(lastPage?.takeIf { lastFor == url } ?: url) }
    BackHandler { if (webView.canGoBack()) webView.goBack() else onBack() }
    BrowserFrame(
        source = source,
        host = host,
        loading = loading,
        others = others,
        searching = searching,
        download = download,
        snackbar = snackbar,
        onBack = onBack,
        onClose = onClose,
        onOther = onOther,
        onStop = AcquireDownloads::stop,
        onDone = {
            AcquireDownloads.dismiss()
            onClose()
        },
        onKeepBrowsing = AcquireDownloads::dismiss,
    ) { modifier ->
        AndroidView(
            factory = {
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView
            },
            modifier = modifier,
        )
    }
}
