package dev.sadking.folio

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.sadking.folio.data.Book
import dev.sadking.folio.data.BookAnnotation
import dev.sadking.folio.data.PhoneReading
import dev.sadking.folio.reader.HIGHLIGHT_SHEET_TAG
import dev.sadking.folio.reader.PHONE_ANNOTATION_DEVICE
import dev.sadking.folio.reader.ReaderActivity
import dev.sadking.folio.reader.ReaderDefaults
import dev.sadking.folio.reader.SELECTION_HIGHLIGHT
import dev.sadking.folio.reader.xpointerSpineIndex
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.readium.r2.navigator.epub.EpubNavigatorFragment

/**
 * The highlight sheet answers real taps (M11 fix). Every tap here goes through the system's input
 * (touch events sent with `sendPointerSync`, not the shell), as a finger's does, to the window on
 * top; Compose's performClick would skip that path, which is where the owner's taps were lost. Both
 * ways to the sheet are covered: tapping a drawn highlight, and selecting a highlighted passage
 * again from the selection menu, itself tapped for real. Also a passage selected across a page turn
 * is saved and drawn as one highlight: one injected finger holds the selection while a second
 * finger's injected tap turns the page.
 */
class ReaderHighlightTapsTest {
    private companion object {
        /** Which page the probe is in, asked of the navigator's page. */
        const val PROBE_ID = "window.__folioTapProbe?window.__folioTapProbe.id:null"
        /** What a page's probe recorded, with the page's clock now and its file. */
        const val PROBE_REPORT =
            "JSON.stringify({probe:window.__folioTapProbe||null," +
                "now:Math.round(performance.now()),file:location.pathname," +
                "focused:document.hasFocus()})"
    }

    @get:Rule val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<FolioApplication>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val dao = app.database.library()
    private val prefs = app.getSharedPreferences(ReaderDefaults.PREFERENCES, 0)
    private val wasScrolling = prefs.getBoolean("scroll", false)
    private val books = mutableListOf<Pair<String, File>>()

    @Before
    fun paged() {
        prefs.edit().putBoolean("scroll", false).commit()
    }

    @After
    fun cleanUp() {
        prefs.edit().putBoolean("scroll", wasScrolling).commit()
        runBlocking {
            books.forEach { (id, dir) ->
                dao.deleteLocalAnnotations(id)
                dao.deleteLocalBook(id)
                dir.deleteRecursively()
            }
        }
    }

    /**
     * A three-chapter book open at chapter two (DocFragment[3]), whose paragraphs read "Chapter two
     * paragraph N with enough text." followed by [filler]; [rows] are stored with it.
     */
    private fun book(
        paragraphs: Int = 20,
        filler: String = "",
        rows: (String) -> List<BookAnnotation> = { emptyList() },
    ): String {
        val id = UUID.randomUUID().toString()
        val dir = File(app.repository.root, id).apply { mkdirs() }
        books += id to dir
        val file = File(dir, "original.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            fun chapter(title: String) =
                """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head><body><h1>$title</h1>""" +
                    (1..paragraphs).joinToString("") {
                        "<p>$title paragraph $it with enough text.$filler</p>"
                    } +
                    "</body></html>"
            entry("mimetype", "application/epub+zip")
            entry(
                "META-INF/container.xml",
                """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            entry(
                "OPS/book.opf",
                """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Tap Fixture</dc:title><dc:creator>QA Author</dc:creator><dc:identifier id="uid">urn:test:$id</dc:identifier><dc:language>en</dc:language></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/><item id="notes" href="notes.xhtml" media-type="application/xhtml+xml"/><item id="two" href="two.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="one"/><itemref idref="notes" linear="no"/><itemref idref="two"/></spine></package>""",
            )
            entry(
                "OPS/nav.xhtml",
                """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="one.xhtml">Chapter one</a></li><li><a href="two.xhtml">Chapter two</a></li></ol></nav></body></html>""",
            )
            entry("OPS/one.xhtml", chapter("Chapter one"))
            entry("OPS/notes.xhtml", chapter("Notes"))
            entry("OPS/two.xhtml", chapter("Chapter two"))
        }
        val hash = dev.sadking.folio.core.Epub.sha256(file)
        runBlocking {
            dao.insert(
                Book(
                    id,
                    "Tap Fixture",
                    "[\"QA Author\"]",
                    originalPath = file.path,
                    currentPath = file.path,
                    originalHash = hash,
                    currentHash = hash,
                )
            )
            rows(id).forEach { dao.annotation(it) }
            dao.savePhoneReading(
                PhoneReading(
                    id,
                    hash,
                    """{"href":"OPS/two.xhtml","type":"application/xhtml+xml","locations":{"progression":0.0}}""",
                    "",
                    null,
                    System.currentTimeMillis(),
                )
            )
        }
        return id
    }

    private fun open(id: String): ActivityScenario<ReaderActivity> =
        ActivityScenario.launch(
            Intent(app, ReaderActivity::class.java)
                .putExtra("book_id", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

    private fun ActivityScenario<ReaderActivity>.navigator(): EpubNavigatorFragment? {
        var navigator: EpubNavigatorFragment? = null
        onActivity {
            navigator =
                it.supportFragmentManager.findFragmentByTag("epub") as? EpubNavigatorFragment
        }
        return navigator
    }

    /** Runs [script] in the page on screen; WebView's answer, as JSON. */
    private fun ActivityScenario<ReaderActivity>.page(script: String): String? {
        val nav = navigator() ?: return null
        return runBlocking { withContext(Dispatchers.Main) { nav.evaluateJavascript(script) } }
    }

    /** A script's `JSON.stringify(...)` answer, decoded. */
    private fun ActivityScenario<ReaderActivity>.json(script: String): Any? {
        val raw = page(script) ?: return null
        val text = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
        return text?.let { runCatching { JSONTokener(it).nextValue() }.getOrNull() }
    }

    /** Decoration ids and their text in the page on screen. */
    private fun ActivityScenario<ReaderActivity>.drawn(): Map<String, String> {
        val items =
            json(
                "JSON.stringify(readium.getDecorations('highlights').items" +
                    ".map(i => [i.decoration.id, i.range.toString()]))"
            )
                as? JSONArray ?: return emptyMap()
        return (0 until items.length()).associate {
            items.getJSONArray(it).getString(0) to items.getJSONArray(it).getString(1)
        }
    }

    /** The reader's page on screen, as a WebView, and where it sits on the screen. */
    private fun ActivityScenario<ReaderActivity>.pageOnScreen(): IntArray {
        var origin: IntArray? = null
        onActivity { activity ->
            val decor = activity.window.decorView
            fun find(view: View): WebView? {
                if (view is WebView) {
                    val visible = Rect()
                    if (view.isShown && view.getGlobalVisibleRect(visible))
                        if (visible.width() * 2 >= decor.width) return view
                }
                if (view is ViewGroup)
                    for (i in 0 until view.childCount) find(view.getChildAt(i))?.let {
                        return it
                    }
                return null
            }
            origin = find(decor)?.let { web -> IntArray(2).also { web.getLocationOnScreen(it) } }
        }
        return origin ?: error("No page on screen.\n" + Diagnostics.state())
    }

    /**
     * Where the page's [rectScript] (a script giving a DOMRect) is centred on the screen, in
     * pixels, or null while the page cannot say.
     */
    private fun ActivityScenario<ReaderActivity>.onScreen(rectScript: String): Pair<Int, Int>? {
        val value =
            json(
                "(function(){var b=($rectScript);if(!b||!b.width)return null;" +
                    "return JSON.stringify({x:b.left+b.width/2,y:b.top+b.height/2," +
                    "d:window.devicePixelRatio});})()"
            )
                as? JSONObject ?: return null
        val origin = pageOnScreen()
        val scale = value.getDouble("d")
        return (origin[0] + value.getDouble("x") * scale).roundToInt() to
            (origin[1] + value.getDouble("y") * scale).roundToInt()
    }

    /** The word [word] in the [paragraph]th paragraph (0-based), as a DOMRect script. */
    private fun word(paragraph: Int, word: String) =
        "(function(){var t=document.getElementsByTagName('p')[$paragraph].firstChild;" +
            "var i=t.data.indexOf('$word');var r=document.createRange();" +
            "r.setStart(t,i);r.setEnd(t,i+${word.length});return r.getBoundingClientRect();})()"

    /**
     * Where the page draws highlight [id], on the screen: the middle of the element the page itself
     * tests a tap against, or null while the highlight has none on the page.
     */
    private fun ActivityScenario<ReaderActivity>.highlightOnScreen(id: String): Pair<Int, Int>? =
        runCatching {
                onScreen(
                    "(function(){var i=readium.getDecorations('highlights').items.find(" +
                        "i=>i.decoration.id==='$id');" +
                        "return i&&i.clickableElements&&i.clickableElements.length" +
                        "?i.clickableElements[0].getBoundingClientRect():null;})()"
                )
            }
            .getOrNull()

    /** The place of highlight [id] once it has held still for a moment; fails if it never does. */
    private fun ActivityScenario<ReaderActivity>.settledHighlight(id: String): Pair<Int, Int> {
        var settled: Pair<Int, Int>? = null
        var last: Pair<Int, Int>? = null
        compose.waitUntil(30_000) {
            val now = highlightOnScreen(id)
            if (now != null && now == last) settled = now
            last = now
            Thread.sleep(250)
            settled != null
        }
        return settled!!
    }

    /** What the page says about highlight [id] and its selection, for a failure message. */
    private fun ActivityScenario<ReaderActivity>.highlightState(id: String): String =
        page(
            "JSON.stringify((function(){var i=readium.getDecorations('highlights').items.find(" +
                "i=>i.decoration.id==='$id');var c=i&&i.clickableElements;" +
                "return {found:!!i,clickable:c?c.length:null," +
                "clickRect:c&&c.length?c[0].getBoundingClientRect():null," +
                "textRect:i?i.range.getBoundingClientRect():null," +
                "selectionCollapsed:getSelection().isCollapsed," +
                "scrollLeft:document.scrollingElement.scrollLeft," +
                "width:innerWidth,height:innerHeight,dpr:devicePixelRatio};})())"
        ) ?: "no page"

    /**
     * Records in a page the touches and clicks it gets: on arrival, with the point and whether text
     * is selected then; and again if they pass on to the window, with whether something prevented
     * their default. Points and times only, never text. Kept until the page reloads; [id] names the
     * page so the navigator's own can be told apart.
     */
    private fun probeScript(id: Int) =
        "(function(){var p=window.__folioTapProbe;if(!p){p=window.__folioTapProbe={events:[]};" +
            "var note=function(s){p.events.push(s+' @'+Math.round(performance.now()));" +
            "if(p.events.length>24)p.events.shift();};" +
            "['touchstart','touchend','touchcancel','click'].forEach(function(type){" +
            "addEventListener(type,function(e){var t=(e.changedTouches&&e.changedTouches[0])||e;" +
            "note(type+' '+Math.round(t.clientX)+','+Math.round(t.clientY)+" +
            "(getSelection().isCollapsed?'':' while text is selected'));}," +
            "{capture:true,passive:true});" +
            "addEventListener(type,function(e){note(type+' passed'+" +
            "(e.defaultPrevented?', default prevented':''));},{passive:true});});}" +
            "p.id=$id;return true;})()"

    /**
     * Runs a script, given each page's index, in every page WebView in the reader's window, the
     * navigator's current one or not; each answer follows the view's place on the screen.
     */
    private fun ActivityScenario<ReaderActivity>.inEveryPage(
        script: (Int) -> String
    ): List<String> {
        val pages = mutableListOf<Pair<WebView, String>>()
        onActivity { activity ->
            fun collect(view: View) {
                if (view is WebView) {
                    val at = IntArray(2).also { view.getLocationOnScreen(it) }
                    val visible = Rect()
                    val seen = view.isShown && view.getGlobalVisibleRect(visible)
                    pages +=
                        view to
                            "at ${at[0]},${at[1]} ${view.width}x${view.height}" +
                                if (seen) " showing $visible" else " not showing"
                } else if (view is ViewGroup)
                    for (i in 0 until view.childCount) collect(view.getChildAt(i))
            }
            collect(activity.window.decorView)
        }
        return pages.mapIndexed { index, (web, where) ->
            val answer = CompletableDeferred<String?>()
            instrumentation.runOnMainSync {
                web.evaluateJavascript(script(index)) { answer.complete(it) }
            }
            val said = runBlocking { withTimeoutOrNull(5_000) { answer.await() } }
            "page $index $where: ${said ?: "no answer"}"
        }
    }

    /** The reader's own record of where the last touches went; see TapTrace. */
    private fun ActivityScenario<ReaderActivity>.tapSteps(): List<String> {
        var steps = emptyList<String>()
        onActivity { steps = it.tapTrace.steps() }
        return steps
    }

    /**
     * Sends one touch event with [fingers] (id to screen point) as [action] through the system's
     * input, as a finger's would reach the window there, and waits until it is handled. [downTime]
     * is the gesture's start; the event's time is now. No shell: a shell command can silently do
     * nothing on CI.
     */
    /**
     * The windows of other apps, the system's included, that sit above the app's own at ([x], [y]),
     * described for a failure message; empty when the app's window is on top there. A heads-up
     * notification or a system dialog there takes the touch, and injection into it is refused.
     */
    private fun coveringWindows(x: Int, y: Int): List<String> {
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        val retrieve = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        if (info.flags and retrieve == 0) {
            info.flags = info.flags or retrieve
            automation.serviceInfo = info
        }
        val bounds = Rect()
        val covering = mutableListOf<String>()
        for (window in automation.windows.sortedByDescending { it.layer }) {
            window.getBoundsInScreen(bounds)
            if (!bounds.contains(x, y)) continue
            if (window.root?.packageName == app.packageName) break
            covering +=
                "${window.title} (package ${window.root?.packageName}, type ${window.type}, " +
                    "layer ${window.layer}, $bounds)"
        }
        return covering
    }

    /** Waits up to 15 seconds for nothing to cover ([x], [y]); fails naming what still does. */
    private fun awaitUncovered(x: Int, y: Int) {
        fun look() = runCatching { coveringWindows(x, y) }.getOrDefault(emptyList())
        val deadline = SystemClock.uptimeMillis() + 15_000
        var covering = look()
        while (covering.isNotEmpty() && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(250)
            covering = look()
        }
        if (covering.isNotEmpty())
            fail("Another window covers the touch at $x,$y:\n" + covering.joinToString("\n"))
    }

    private fun inject(downTime: Long, action: Int, vararg fingers: Pair<Int, Pair<Int, Int>>) {
        // A gesture starts only where the app's window is on top.
        if (action == MotionEvent.ACTION_DOWN)
            fingers.firstOrNull()?.let { (_, at) -> awaitUncovered(at.first, at.second) }
        val properties =
            fingers
                .map { (id, _) ->
                    MotionEvent.PointerProperties().apply {
                        this.id = id
                        toolType = MotionEvent.TOOL_TYPE_FINGER
                    }
                }
                .toTypedArray()
        val coords =
            fingers
                .map { (_, at) ->
                    MotionEvent.PointerCoords().apply {
                        x = at.first.toFloat()
                        y = at.second.toFloat()
                        pressure = 1f
                        size = 1f
                    }
                }
                .toTypedArray()
        val event =
            MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                action,
                fingers.size,
                properties,
                coords,
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0,
            )
        try {
            instrumentation.sendPointerSync(event)
        } catch (refused: IllegalArgumentException) {
            val (x, y) = fingers.first().second
            val covering = runCatching { coveringWindows(x, y) }.getOrDefault(emptyList())
            fail(
                "The touch at $x,$y was refused (${refused.message}). Windows above the app " +
                    "there: ${covering.ifEmpty { listOf("none found") }.joinToString("; ")}"
            )
        } finally {
            event.recycle()
        }
    }

    /** The second finger's [action] (pointer down or up), for an event's second pointer. */
    private fun second(action: Int) = action or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private fun tap(x: Int, y: Int) {
        val downTime = SystemClock.uptimeMillis()
        inject(downTime, MotionEvent.ACTION_DOWN, 0 to (x to y))
        Thread.sleep(50)
        inject(downTime, MotionEvent.ACTION_UP, 0 to (x to y))
    }

    /** A finger held still at ([x], [y]) for about 1.2 seconds, moving nowhere, then lifted. */
    private fun longPress(x: Int, y: Int) {
        val downTime = SystemClock.uptimeMillis()
        inject(downTime, MotionEvent.ACTION_DOWN, 0 to (x to y))
        repeat(3) {
            Thread.sleep(400)
            inject(downTime, MotionEvent.ACTION_MOVE, 0 to (x to y))
        }
        inject(downTime, MotionEvent.ACTION_UP, 0 to (x to y))
    }

    /** A real tap in the middle of the Compose node [node], wherever its window is. */
    private fun tap(node: SemanticsNodeInteraction) {
        val found = node.fetchSemanticsNode()
        val at = found.positionOnScreen
        tap(
            (at.x + found.size.width / 2f).roundToInt(),
            (at.y + found.size.height / 2f).roundToInt(),
        )
    }

    /**
     * Chooses [itemId] from the text-selection menu once the long-press has opened it. The menu is
     * a system floating toolbar the test cannot reliably find on screen, so the item goes through
     * the menu itself: the same onActionItemClicked a finger reaches. Fails if no menu opened.
     */
    private fun ActivityScenario<ReaderActivity>.chooseFromSelectionMenu(itemId: Int) {
        compose.waitUntil(15_000) { menuOffers(itemId) }
        onActivity { it.openSelectionMenu!!.performIdentifierAction(itemId, 0) }
    }

    private fun ActivityScenario<ReaderActivity>.menuOffers(itemId: Int): Boolean {
        var open = false
        onActivity { open = it.openSelectionMenu?.findItem(itemId) != null }
        return open
    }

    /**
     * Long-presses ([x], [y]) until the selection menu opens, up to 3 times: a press that lands
     * before the page takes touches is lost, as in ReaderUiTest.
     */
    private fun ActivityScenario<ReaderActivity>.select(x: Int, y: Int) {
        repeat(3) {
            longPress(x, y)
            if (
                runCatching { compose.waitUntil(5_000) { menuOffers(SELECTION_HIGHLIGHT) } }
                    .isSuccess
            )
                return
        }
        fail("No selection menu after 3 long-presses.\n" + Diagnostics.state())
    }

    private fun sheetShown() =
        compose.onAllNodesWithTag(HIGHLIGHT_SHEET_TAG).fetchSemanticsNodes().isNotEmpty()

    /** Waits for the sheet, then for its opening slide to finish: a moving sheet stops on touch. */
    private fun awaitSheet() {
        compose.waitUntil(30_000) { sheetShown() }
        instrumentation.waitForIdleSync()
        Thread.sleep(1500)
    }

    private fun row(id: String) = runBlocking { dao.annotationById(id) }

    private fun phoneRows(book: String) =
        runBlocking { dao.allAnnotations() }
            .filter { it.bookId == book && it.deviceId == PHONE_ANNOTATION_DEVICE }

    /**
     * A new colour (Pink, or Green when it is Pink already) and Delete, each a real tap on the open
     * sheet of highlight [id].
     */
    private fun ActivityScenario<ReaderActivity>.recolourAndDelete(id: String) {
        val (swatch, colour) =
            if (row(id)?.color == "purple") "Green" to "green" else "Pink" to "purple"
        tap(compose.onNodeWithContentDescription(swatch))
        compose.waitUntil(15_000) { row(id)?.color == colour }
        // The sheet follows the row: the new colour is the chosen one.
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithContentDescription(swatch).assertIsSelected() }
                .isSuccess
        }
        tap(compose.onNodeWithText("Delete"))
        compose.waitUntil(15_000) { row(id)?.deleted == true }
        compose.waitUntil(15_000) { !sheetShown() }
        compose.waitUntil(30_000) { runCatching { id !in drawn() }.getOrDefault(false) }
    }

    @Test
    fun aTappedHighlightsSheetRecoloursAndDeletesWithRealTaps() {
        lateinit var kindle: BookAnnotation
        val id = book { book ->
            kindle =
                BookAnnotation(
                    "kindle-$book",
                    book,
                    "link:kindle",
                    "paragraph",
                    "",
                    "Chapter two",
                    "2026-10-16 08:00:00",
                    "/body/DocFragment[3]/body/p[2]/text().12",
                    "/body/DocFragment[3]/body/p[2]/text().21",
                    "highlight",
                    color = "yellow",
                    drawer = "lighten",
                )
            listOf(kindle)
        }
        open(id).use { scenario ->
            compose.waitUntil(30_000) {
                runCatching { kindle.id in scenario.drawn() }.getOrDefault(false)
            }
            // The drawn highlight, tapped like a finger. A tap that lands before the page takes
            // touches is lost, and a tap the page does not take as a highlight's turns the page or
            // toggles the controls and moves the highlight: each try finds the highlight again,
            // once it holds still, and a third miss fails with what the page said about it.
            var taps = 0
            val tapped = mutableListOf<String>()
            while (!sheetShown() && taps < 3) {
                val at = scenario.settledHighlight(kindle.id)
                scenario.inEveryPage(::probeScript)
                tapped +=
                    "${at.first},${at.second} on a page at ${scenario.pageOnScreen().toList()}"
                tap(at.first, at.second)
                taps++
                runCatching { compose.waitUntil(5_000) { sheetShown() } }
            }
            // Where the taps stopped. The reader's steps, in order, for each tap: "touch
            // ACTION_DOWN/UP" (the window got it), "page touch end" (the page's script got it and
            // Readium passed it on), then "highlight tapped" (Readium hit the highlight) or "page
            // tap" (it did not), "highlight sheet asked" and "highlight sheet shown". The pages'
            // own record says which page got touchstart, touchend and click, and whether text was
            // selected then (a selection makes Readium ignore the tap).
            if (!sheetShown())
                fail(
                    "The sheet did not open after $taps taps on the highlight.\n" +
                        "Taps at: ${tapped.joinToString("; ")}\n" +
                        "Page: ${scenario.highlightState(kindle.id)}\n" +
                        "Reader steps:\n${scenario.tapSteps().joinToString("\n")}\n" +
                        "Pages (the navigator's is ${scenario.page(PROBE_ID)}):\n" +
                        scenario.inEveryPage { PROBE_REPORT }.joinToString("\n") +
                        "\n" +
                        Diagnostics.state()
                )
            awaitSheet()
            scenario.recolourAndDelete(kindle.id)
        }
    }

    @Test
    fun aPassageSelectedAgainOpensASheetThatAnswersRealTaps() {
        val id = book()
        open(id).use { scenario ->
            // "enough" in the second paragraph, once the page can say where it is.
            var at: Pair<Int, Int>? = null
            compose.waitUntil(30_000) {
                at = runCatching { scenario.onScreen(word(1, "enough")) }.getOrNull()
                at != null
            }
            val (x, y) = at!!
            // Long-press selects the word; Highlight in the selection menu saves it.
            scenario.select(x, y)
            scenario.chooseFromSelectionMenu(SELECTION_HIGHLIGHT)
            compose.waitUntil(30_000) { phoneRows(id).isNotEmpty() }
            val made = phoneRows(id).single()
            assertEquals("enough", made.text)
            compose.waitUntil(30_000) {
                runCatching { made.id in scenario.drawn() }.getOrDefault(false)
            }
            // The same word again: the menu's Highlight opens the one already there.
            scenario.select(x, y)
            scenario.chooseFromSelectionMenu(SELECTION_HIGHLIGHT)
            awaitSheet()
            assertEquals(1, phoneRows(id).size)
            scenario.recolourAndDelete(made.id)
        }
    }

    @Test
    fun aSecondFingersTapTurnsThePageUnderAHeldSelectionAndItIsSavedAndDrawnWhole() {
        // Long paragraphs: chapter two runs over several pages.
        val filler = " The rows ran down to the river and back up the hill again.".repeat(6)
        val id = book(paragraphs = 40, filler = filler)
        open(id).use { scenario ->
            var at: Pair<Int, Int>? = null
            compose.waitUntil(30_000) {
                at = runCatching { scenario.onScreen(word(1, "enough")) }.getOrNull()
                at != null
            }
            val held = at!!
            fun scrolled() =
                (scenario.page("Math.abs(document.scrollingElement.scrollLeft)") ?: "0").toDouble()
            fun selecting(): Boolean {
                var known = false
                scenario.onActivity { known = it.selecting }
                return known && scenario.page("getSelection().isCollapsed") == "false"
            }
            val before = scrolled()
            // One finger long-presses a word on the first page and stays down: the page selects
            // the word, and the reader learns that text is selected. A press that lands before the
            // page takes touches is lost: it is cancelled (never a tap) and tried again.
            var downTime = 0L
            // The first finger stays still for [ms] or until [done], reporting moves in place
            // every 300 ms with real times, as a resting finger does.
            fun holdUntil(ms: Long, done: () -> Boolean): Boolean {
                val end = SystemClock.uptimeMillis() + ms
                while (SystemClock.uptimeMillis() < end) {
                    if (done()) return true
                    Thread.sleep(300)
                    inject(downTime, MotionEvent.ACTION_MOVE, 0 to held)
                }
                return done()
            }
            var tries = 0
            while (true) {
                downTime = SystemClock.uptimeMillis()
                inject(downTime, MotionEvent.ACTION_DOWN, 0 to held)
                tries++
                if (holdUntil(5_000) { selecting() }) break
                inject(downTime, MotionEvent.ACTION_CANCEL, 0 to held)
                if (tries == 3)
                    fail(
                        "No selection under a held finger after 3 tries.\n" +
                            "Reader steps:\n${scenario.tapSteps().joinToString("\n")}\n" +
                            Diagnostics.state()
                    )
            }
            // Its end is dragged to the last character on the page, still under the held finger.
            val extended =
                scenario.page(
                    """(function(){var s=getSelection();var w=document.createTreeWalker(""" +
                        """document.body,NodeFilter.SHOW_TEXT);var last=null,n;""" +
                        """var vis=function(r){return r.width>0&&r.height>0&&r.right>1&&""" +
                        """r.left<innerWidth-1&&r.bottom>1&&r.top<innerHeight-1};""" +
                        """while((n=w.nextNode())){for(var i=0;i<n.data.length;i++){""" +
                        """if(!/\S/.test(n.data[i]))continue;var r=document.createRange();""" +
                        """r.setStart(n,i);r.setEnd(n,i+1);""" +
                        """if([].some.call(r.getClientRects(),vis))last=[n,i];}}""" +
                        """if(!last)return false;s.extend(last[0],last[1]+1);return true;})()"""
                )
            assertEquals("true", extended)
            // Held there, the page stays: only a tap turns it.
            holdUntil(900) { false }
            assertEquals(before, scrolled(), 1.0)
            // A second finger taps the right half, the way an English book reads, while the first
            // stays down.
            var width = 0
            var height = 0
            scenario.onActivity {
                width = it.window.decorView.width
                height = it.window.decorView.height
            }
            val tap = width * 3 / 4 to height / 2
            inject(downTime, second(MotionEvent.ACTION_POINTER_DOWN), 0 to held, 1 to tap)
            Thread.sleep(60)
            inject(downTime, second(MotionEvent.ACTION_POINTER_UP), 0 to held, 1 to tap)
            // The page turns, and the selection is still there.
            if (runCatching { compose.waitUntil(10_000) { scrolled() > before + 1 } }.isFailure)
                fail(
                    "The page did not turn after a second finger's tap.\n" +
                        "Reader steps:\n${scenario.tapSteps().joinToString("\n")}\n" +
                        Diagnostics.state()
                )
            assertEquals("false", scenario.page("getSelection().isCollapsed"))
            // The end goes on onto the new page: a few words into its first full line.
            val further =
                scenario.page(
                    """(function(){var s=getSelection();var w=document.createTreeWalker(""" +
                        """document.body,NodeFilter.SHOW_TEXT);var n,seen=0;""" +
                        """var vis=function(r){return r.width>0&&r.height>0&&r.right>1&&""" +
                        """r.left<innerWidth-1&&r.bottom>1&&r.top<innerHeight-1};""" +
                        """while((n=w.nextNode())){for(var i=0;i<n.data.length;i++){""" +
                        """var r=document.createRange();r.setStart(n,i);r.setEnd(n,i+1);""" +
                        """if([].some.call(r.getClientRects(),vis)&&++seen>40){""" +
                        """s.extend(n,i);return true;}}}return false;})()"""
                )
            assertEquals("true", further)
            // The first finger lifts; the selection stays.
            inject(downTime, MotionEvent.ACTION_UP, 0 to held)
            assertEquals("false", scenario.page("getSelection().isCollapsed"))
            scenario.onActivity { it.selectionAction(SELECTION_HIGHLIGHT) }
            compose.waitUntil(30_000) { phoneRows(id).isNotEmpty() }
            val made = phoneRows(id).single()
            // One highlight, in chapter two, from the first page's paragraph to a later one.
            assertEquals(2, xpointerSpineIndex(made.position, 3))
            assertEquals(2, xpointerSpineIndex(made.endPosition, 3))
            val start = Regex("""/p\[(\d+)]""").find(made.position)!!.groupValues[1].toInt()
            val end = Regex("""/p\[(\d+)]""").find(made.endPosition)!!.groupValues[1].toInt()
            assertEquals(2, start)
            assertTrue("$start..$end", end > start)
            assertTrue(made.text, made.text.startsWith("enough text."))
            // Drawn over the same words, on both pages.
            compose.waitUntil(30_000) {
                runCatching { made.id in scenario.drawn() }.getOrDefault(false)
            }
            // Paragraph breaks are a line break in the saved text and nothing in the drawn range.
            val squash = { text: String -> text.replace(Regex("\\s+"), "") }
            assertEquals(squash(made.text), squash(scenario.drawn().getValue(made.id)))
            assertNotEquals(made.position, made.endPosition)
        }
    }
}
