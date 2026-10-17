package dev.sadking.folio

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
 * (`input tap`), as a finger's does, to the window on top; Compose's performClick would skip that
 * path, which is where the owner's taps were lost. Both ways to the sheet are covered: tapping a
 * drawn highlight, and selecting a highlighted passage again from the selection menu, itself tapped
 * for real. Also a passage selected across a page turn is saved and drawn as one highlight.
 */
class ReaderHighlightTapsTest {
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

    private fun tap(x: Int, y: Int) {
        Diagnostics.shell("input tap $x $y")
    }

    private fun longPress(x: Int, y: Int) {
        Diagnostics.shell("input swipe $x $y $x $y 1200")
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

    /** Taps [label] in the text-selection menu, a system window, through accessibility. */
    private fun tapSelectionMenu(label: String) {
        val automation = instrumentation.uiAutomation
        automation.serviceInfo =
            automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val item =
                automation.windows
                    .mapNotNull { it.root }
                    .flatMap { it.findAccessibilityNodeInfosByText(label) }
                    .firstOrNull {
                        it.text?.toString().equals(label, ignoreCase = true) && it.isVisibleToUser
                    }
            if (item != null) {
                val bounds = Rect()
                item.getBoundsInScreen(bounds)
                tap(bounds.centerX(), bounds.centerY())
                return
            }
            Thread.sleep(200)
        }
        fail("The selection menu never offered $label.\n" + Diagnostics.state())
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
            // The highlight's first line, tapped like a finger.
            val at =
                scenario.onScreen(
                    "readium.getDecorations('highlights').items.find(" +
                        "i => i.decoration.id === '${kindle.id}').range.getClientRects()[0]"
                ) ?: error("The highlight has no place on the page.")
            tap(at.first, at.second)
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
            longPress(x, y)
            tapSelectionMenu(app.getString(R.string.reader_highlight_action))
            compose.waitUntil(30_000) { phoneRows(id).isNotEmpty() }
            val made = phoneRows(id).single()
            assertEquals("enough", made.text)
            compose.waitUntil(30_000) {
                runCatching { made.id in scenario.drawn() }.getOrDefault(false)
            }
            // The same word again: the menu's Highlight opens the one already there.
            longPress(x, y)
            tapSelectionMenu(app.getString(R.string.reader_highlight_action))
            awaitSheet()
            assertEquals(1, phoneRows(id).size)
            scenario.recolourAndDelete(made.id)
        }
    }

    @Test
    fun aSelectionHeldAtThePagesEndTurnsThePageAndIsSavedAndDrawnWhole() {
        // Long paragraphs: chapter two runs over several pages.
        val filler = " The rows ran down to the river and back up the hill again.".repeat(6)
        val id = book(paragraphs = 40, filler = filler)
        open(id).use { scenario ->
            compose.waitUntil(30_000) {
                runCatching { scenario.onScreen(word(1, "enough")) }.getOrNull() != null
            }
            fun scrolled() =
                (scenario.page("Math.abs(document.scrollingElement.scrollLeft)") ?: "0").toDouble()
            val before = scrolled()
            // A word on the first page is selected, as a long press would.
            scenario.page(
                """(function(){var t=document.getElementsByTagName('p')[1].firstChild;""" +
                    """var i=t.data.indexOf('enough');var r=document.createRange();""" +
                    """r.setStart(t,i);r.setEnd(t,i+6);var s=getSelection();""" +
                    """s.removeAllRanges();s.addRange(r);return true;})()"""
            )
            // A script's selection opens no menu; start what the menu starts.
            scenario.onActivity { it.watchSelection() }
            Thread.sleep(600)
            // Its end is dragged to the last character on the page, and held there.
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
            // The page turns after a short wait, and the selection is still there.
            compose.waitUntil(10_000) { scrolled() > before + 1 }
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
            val squash = { text: String -> text.replace(Regex("\\s+"), " ").trim() }
            assertEquals(squash(made.text), squash(scenario.drawn().getValue(made.id)))
            assertNotEquals(made.position, made.endPosition)
        }
    }
}
