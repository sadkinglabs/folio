package dev.sadking.folio

import android.content.ContentValues
import android.graphics.*
import android.net.Uri
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.sadking.folio.core.*
import dev.sadking.folio.data.*
import dev.sadking.folio.ui.*
import java.io.File
import java.util.zip.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real MainActivity, Room, metadata revisions and the Android document picker. Fixtures only. */
class ProductJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app
        get() = ApplicationProvider.getApplicationContext<FolioApplication>()

    private val automation
        get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    private fun artwork(title: String, author: String, color: Int): ByteArray {
        val image = Bitmap.createBitmap(480, 720, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(color)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.rgb(35, 42, 30)
                textSize = 45f
                typeface = Typeface.create("serif", Typeface.BOLD)
            }
        canvas.drawRect(28f, 30f, 452f, 37f, paint)
        var y = 170f
        title.split(" ").chunked(2).forEach { words ->
            canvas.drawText(words.joinToString(" "), 35f, y, paint)
            y += 64f
        }
        paint.textSize = 25f
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        canvas.drawText(author, 35f, 615f, paint)
        canvas.drawText("FOLIO · QA FIXTURE", 35f, 665f, paint)
        return java.io
            .ByteArrayOutputStream()
            .also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            .toByteArray()
            .also { image.recycle() }
    }

    private fun epub(title: String, author: String, color: Int): File {
        val cover = artwork(title, author, color)
        val file = File(app.cacheDir, "journey-${title.hashCode()}.epub")
        ZipOutputStream(file.outputStream()).use { z ->
            fun entry(name: String, bytes: ByteArray) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
            entry("mimetype", "application/epub+zip".toByteArray())
            entry(
                "META-INF/container.xml",
                """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>"""
                    .toByteArray(),
            )
            entry(
                "book.opf",
                """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>$title</dc:title><dc:creator>$author</dc:creator><dc:identifier id="uid">urn:folio:fixture:${title.hashCode()}</dc:identifier><dc:language>en</dc:language><dc:description>Native product-verification fixture. No copyrighted book contents.</dc:description><meta name="cover" content="cover"/></metadata><manifest><item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="chapter"/></spine></package>"""
                    .toByteArray(),
            )
            entry(
                "chapter.xhtml",
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>Product verification fixture.</p></body></html>"
                    .toByteArray(),
            )
            entry("cover.png", cover)
        }
        return file
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val image = automation.takeScreenshot() ?: error("Native screenshot unavailable")
        val folder = File(app.filesDir, "qa").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }

    private fun waitText(text: String) =
        waitFor("'$text' to appear") {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }

    private fun waitTag(tag: String) =
        waitFor("'$tag' to appear") {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }

    /** Waits for [condition]; on timeout, fails with what was on screen. */
    private fun waitFor(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(15000, condition)
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError(
                "Timed out waiting for $what. Screen:\n" +
                    compose.onAllNodes(isRoot()).printToString(maxDepth = Int.MAX_VALUE),
                timeout,
            )
        }
    }

    /** A bottom-bar tab. "Library" is also the first tab inside the Library screen. */
    private fun tab(text: String) =
        compose
            .onNode(
                hasText(text) and hasClickAction() and hasAnyAncestor(hasTestTag(FOLIO_BAR_TAG))
            )
            .performClick()

    /**
     * A cover in the Library: captionless, so TalkBack and the tests read "Title, Author". A book
     * in progress shows twice, on the Reading shelf and in the grid; the centre tile is excluded.
     */
    private fun cover(title: String) =
        compose
            .onAllNodes(
                hasContentDescription(title, substring = true) and
                    hasClickAction() and
                    !hasTestTag(NOW_READING_TILE_TAG)
            )
            .onFirst()

    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label)
            return node
        repeat(node.childCount) {
            find(node.getChild(it), label)?.let {
                return it
            }
        }
        return null
    }

    @Test
    fun bookLifecycleAndVisualEvidence() {
        lateinit var pride: Book
        runBlocking {
            require(android.os.Build.HARDWARE in listOf("ranchu", "goldfish")) {
                "Product fixtures run only on an emulator"
            }
            require(
                app.database.library().allBooks().all {
                    decode(it.identifiers).any { id -> id.startsWith("urn:folio:fixture:") }
                } && app.database.library().allWishes().all { it.details.contains("FOLIO-QA") }
            ) {
                "Refusing to replace non-fixture library data"
            }
            app.database.clearAllTables()
            // Start from the default grid layout; the test switches to list view later.
            app.getSharedPreferences("library-view", 0).edit().clear().commit()
            val p = epub("Pride and Prejudice", "Jane Austen", Color.rgb(233, 215, 181))
            pride = app.repository.import(Uri.fromFile(p)).first
            app.repository.manageBatch(setOf(pride.id), status = "Reading")
            app.database
                .library()
                .savePhoneReading(
                    PhoneReading(
                        pride.id,
                        pride.currentHash,
                        "{}",
                        "/body/DocFragment[1]/body/p[3]",
                        0.37,
                        System.currentTimeMillis() - 300000,
                    )
                )
            val f =
                app.repository
                    .import(
                        Uri.fromFile(epub("Frankenstein", "Mary Shelley", Color.rgb(191, 211, 200)))
                    )
                    .first
            app.repository.import(
                Uri.fromFile(epub("Meditations", "Marcus Aurelius", Color.rgb(198, 211, 226)))
            )
            val cover =
                File(app.cacheDir, "catalogue-fixture.png").apply {
                    writeBytes(
                        artwork("A Room of One’s Own", "Virginia Woolf", Color.rgb(223, 202, 215))
                    )
                }
            app.repository.saveCatalogue(
                null,
                Metadata(
                    "A Room of One’s Own",
                    listOf("Virginia Woolf"),
                    description = "FOLIO-QA: A saved book ready for its EPUB.",
                    tags = listOf("Essays"),
                ),
                Uri.fromFile(cover),
            )
            val tracked =
                app.repository.saveCatalogue(
                    null,
                    Metadata(
                        "Hamlet",
                        listOf("William Shakespeare"),
                        description = "FOLIO-QA: Reading a book without an EPUB.",
                    ),
                    null,
                )
            app.repository.manageBatch(setOf(tracked.id), status = "Reading")
            app.database.library().insertShelf(Shelf("qa-sundays", "Slow Sundays"))
            val device = app.deviceSettings.load()
            app.database
                .library()
                .transfer(
                    Transfer(
                        f.id,
                        device.id,
                        "Frankenstein.epub",
                        f.currentHash,
                        System.currentTimeMillis() - 1800000,
                    )
                )
            app.database
                .library()
                .reading(
                    ReadingSnapshot(
                        f.id,
                        device.id,
                        0.37,
                        "fixture-anchor",
                        "reading",
                        0,
                        System.currentTimeMillis() - 600000,
                        "fixture.sdr",
                        "fixture-hash",
                        f.currentHash,
                    )
                )
        }
        waitFor("the library") {
            compose
                .onAllNodes(hasContentDescription("Frankenstein", substring = true))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        tab("Settings")
        compose.onNodeWithText("Light").performClick()
        val wallpaper = compose.onNodeWithContentDescription("Use wallpaper colours")
        if (
            wallpaper.fetchSemanticsNode().config[SemanticsProperties.ToggleableState] ==
                ToggleableState.On
        )
            wallpaper.performClick()
        capture("settings-light")
        tab("Library")
        capture("library-light")
        // Pride and Prejudice is being read, so it leads the Reading shelf.
        compose.onNodeWithTag(LIBRARY_SHELF_TAG).assertIsDisplayed()
        cover("Pride and Prejudice, Jane Austen, 37%").performClick()
        compose.onNodeWithTag(BOOK_PRIMARY_TAG).assertTextEquals("Continue · 37%")
        compose.onNodeWithTag(BOOK_ACTION_SEND_TAG).assertIsDisplayed()
        compose.onNodeWithTag(BOOK_ACTION_PROGRESS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(BOOK_LISTS_ADD_TAG).performClick()
        compose.onNode(isToggleable() and hasAnySibling(hasText("Slow Sundays"))).performClick()
        compose.onNodeWithText("Lists · Pride and Prejudice").assertIsDisplayed()
        // Close the sheet through its scrim's own action. A system back gesture did not always
        // reach the sheet window on the emulator, which left it open over the next screens.
        compose
            .onNodeWithContentDescription("Close sheet")
            .performSemanticsAction(SemanticsActions.OnClick)
        waitFor("the lists sheet to close") {
            compose
                .onAllNodesWithText("Lists · Pride and Prejudice")
                .fetchSemanticsNodes()
                .isEmpty()
        }
        waitTag(BOOK_ACTION_SEND_TAG)
        // The list the book was just added to shows as a chip.
        compose.onNodeWithText("Slow Sundays").assertIsDisplayed()
        capture("book-light")
        compose.onNodeWithTag(BOOK_ACTION_EDIT_TAG).performClick()
        capture("metadata-light")
        compose.onNodeWithText("Title").performTextClearance()
        compose.onNodeWithText("Title").performTextInput("Pride and Prejudice · Edited")
        // A disabled button ignores clicks silently: wait until Save can actually be pressed.
        compose.waitUntil(15000) {
            compose
                .onAllNodes(hasText("Save changes") and isEnabled())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("Save changes").performClick()
        // The edited title is already in the editor's own field, so wait for the saved revision.
        waitFor("the edited revision to be saved") {
            runBlocking { app.database.library().book(pride.id)!!.currentHash != pride.currentHash }
        }
        waitText("Pride and Prejudice · Edited")
        runBlocking {
            val changed = app.database.library().book(pride.id)!!
            assertEquals(pride.originalHash, Epub.sha256(File(changed.originalPath)))
            assertNotEquals(pride.currentHash, changed.currentHash)
            assertTrue(
                app.database.library().allMemberships().any {
                    it.bookId == pride.id && it.shelfId == "qa-sundays"
                }
            )
        }
        compose.onNodeWithContentDescription("Back").performClick()
        tab("Lists")
        capture("lists-light")
        // Want to read is the Library's second tab; the record has no EPUB yet.
        tab("Library")
        compose.onNodeWithTag(LIBRARY_TAB_WANTED_TAG).performClick()
        waitFor("the wanted record") {
            compose
                .onAllNodes(hasContentDescription("A Room of One’s Own", substring = true))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        capture("want-to-read-light")
        cover("A Room of One’s Own, Virginia Woolf")
            .assert(hasStateDescription("No EPUB yet"))
            .performClick()
        compose.onNodeWithTag(BOOK_PRIMARY_TAG).assertTextEquals("Find EPUB")
        compose.onNodeWithTag(BOOK_ACTION_SEND_TAG).assertDoesNotExist()
        capture("catalogue-light")
        val input = epub("A Room of One’s Own", "Virginia Woolf", Color.rgb(223, 202, 215))
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "Folio-Journey.epub")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/epub+zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
            }
        val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        app.contentResolver.openOutputStream(uri)!!.use { out ->
            input.inputStream().use { it.copyTo(out) }
        }
        // Attach: pick a file from the phone, then confirm with both covers in view.
        compose.onNodeWithTag(BOOK_ACTION_ATTACH_TAG).performClick()
        compose.onNodeWithTag(ATTACH_PICK_FILE_TAG).performClick()
        compose.waitUntil(20000) {
            find(automation.rootInActiveWindow, "Folio-Journey.epub") != null
        }
        var node = find(automation.rootInActiveWindow, "Folio-Journey.epub")!!
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        waitText("Attach to A Room of One’s Own?")
        compose.onNodeWithTag(CONFIRM_SHEET_CONFIRM_TAG).performClick()
        waitTag(BOOK_ACTION_SEND_TAG)
        compose.onNodeWithTag(BOOK_ACTION_ATTACH_TAG).assertDoesNotExist()
        app.contentResolver.delete(uri, null, null)
        compose.onNodeWithContentDescription("Back").performClick()
        // Attaching the EPUB keeps the record where it was, without the dashed frame.
        cover("A Room of One’s Own, Virginia Woolf").assert(!hasStateDescription("No EPUB yet"))
        tab("Settings")
        compose.onNodeWithText("Dark").performClick()
        tab("Library")
        compose.onNodeWithTag(LIBRARY_TAB_LIBRARY_TAG).performClick()
        capture("library-dark")
        compose.onNodeWithContentDescription("Show list").performClick()
        capture("library-list-dark")
        // The layout is remembered across launches; leave the grid for the tests that follow.
        compose.onNodeWithContentDescription("Show grid").performClick()
        tab("Device")
        capture("kindle-dark")
        // The centre tile: Pride and Prejudice was read last on the phone (37%). Long-press opens
        // the Now Reading sheet; its "Open book page" row leads to the book.
        val tile =
            compose.onNodeWithContentDescription(
                "Continue reading Pride and Prejudice · Edited, 37%"
            )
        tile.assertHasClickAction()
        tile.performTouchInput { longClick() }
        waitText("Continue · 37%")
        capture("now-reading-dark")
        compose.onNodeWithText("Open book page").performClick()
        waitTag(BOOK_ACTION_SEND_TAG)
        compose.onNodeWithContentDescription("Back").performClick()
        // Hamlet is read without an EPUB; it sits on the Library's Reading shelf all the same.
        tab("Library")
        cover("Hamlet, William Shakespeare").performClick()
        compose.onNodeWithTag(BOOK_ACTION_HARDCOVER_TAG).performScrollTo().performClick()
        waitText("Hardcover reading")
        compose.onNodeWithText("1 · Manual progress").assertIsDisplayed()
        compose.onNodeWithText("Get latest from e-reader").assertDoesNotExist()
        capture("hardcover-record-dark")
    }
}
