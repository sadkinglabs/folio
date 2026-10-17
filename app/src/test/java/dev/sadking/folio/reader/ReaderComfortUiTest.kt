package dev.sadking.folio.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import dev.sadking.folio.data.PhoneBookmark
import dev.sadking.folio.ui.FolioTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The comfort controls, the top bar's bookmark and Rename, on the JVM (Robolectric). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class ReaderComfortUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun theComfortControlsChangeTheSettings() {
        var comfort by mutableStateOf(ReaderComfort())
        compose.setContent {
            FolioTheme(dynamic = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ReaderComfortSection(comfort, scrolling = false, onChange = { comfort = it })
                }
            }
        }
        compose.onNodeWithText("Auto").performClick()
        assertFalse(comfort.autoBrightness)
        compose.onNodeWithText("Auto").performClick()
        assertTrue(comfort.autoBrightness)
        // Moving the slider leaves Auto.
        compose.onNodeWithTag(READER_BRIGHTNESS_TAG).performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.SetProgress
        ) {
            it(0.8f)
        }
        assertFalse(comfort.autoBrightness)
        assertEquals(0.8f, comfort.brightness, 0.01f)
        compose.onNodeWithTag(READER_WARMTH_TAG).performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.SetProgress
        ) {
            it(0.5f)
        }
        assertEquals(0.5f, comfort.warmth, 0.01f)
        compose.onNodeWithText("50%").assertExists()
        compose.onNodeWithText("Keep screen on").performScrollTo().performClick()
        assertFalse(comfort.keepScreenOn)
        // Edge slides are on until turned off here.
        assertTrue(comfort.edgeSwipes)
        compose.onNodeWithText("Swipe the edges").performScrollTo().performClick()
        assertFalse(comfort.edgeSwipes)
        compose.onNodeWithText("Left and right halves").performScrollTo().performClick()
        assertEquals(TapZones.HALVES, comfort.tapZones)
        compose.onNodeWithText("Left-handed").performScrollTo().performClick()
        assertTrue(comfort.leftHanded)
        compose.onNodeWithText("Page-turn animation").performScrollTo().performClick()
        assertTrue(comfort.pageAnimation)
    }

    @Test
    fun theEdgeLevelNamesTheSettingAndItsValue() {
        var side by mutableStateOf(EdgeSide.RIGHT)
        var shown by mutableStateOf(true)
        compose.setContent {
            FolioTheme(dynamic = false) {
                EdgeLevelIndicator(
                    side,
                    ReaderComfort(autoBrightness = false, brightness = 0.62f, warmth = 0.3f),
                    shown,
                )
            }
        }
        compose.onNodeWithContentDescription("Brightness 62%").assertIsDisplayed()
        side = EdgeSide.LEFT
        compose.onNodeWithContentDescription("Warmth 30%").assertIsDisplayed()
        shown = false
        compose.waitForIdle()
        compose.onNodeWithTag(READER_EDGE_LEVEL_TAG).assertDoesNotExist()
    }

    @Test
    fun scrollingDimsTheTapChoices() {
        compose.setContent {
            FolioTheme(dynamic = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ReaderComfortSection(ReaderComfort(), scrolling = true, onChange = {})
                }
            }
        }
        compose.onNodeWithText("Tap zones work when Scroll is off.").assertExists()
        compose.onNode(hasText("Left and right halves") and hasClickAction()).assertIsNotEnabled()
        compose.onNode(hasText("Left-handed") and hasClickAction()).assertIsNotEnabled()
        compose.onNode(hasText("Page-turn animation") and hasClickAction()).assertIsEnabled()
    }

    @Test
    fun theTopBarRibbonFillsAndRemoves() {
        var bookmarked by mutableStateOf(false)
        var taps = 0
        compose.setContent {
            FolioTheme(dynamic = false) {
                ReaderTopBar(
                    null,
                    "The Orchard",
                    enabled = true,
                    backEnabled = true,
                    insets = WindowInsets(0),
                    onBack = {},
                    onPanel = {},
                    bookmarked = bookmarked,
                    onBookmark = {
                        taps++
                        bookmarked = !bookmarked
                    },
                )
            }
        }
        compose.onNodeWithContentDescription("Bookmark this page").performClick()
        assertEquals(1, taps)
        compose
            .onNodeWithTag(READER_BOOKMARK_BUTTON_TAG)
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Bookmarked")
            )
        compose.onNodeWithContentDescription("Remove bookmark").performClick()
        assertEquals(2, taps)
        compose.onNodeWithContentDescription("Bookmark this page").assertExists()
    }

    @Test
    fun aBookmarkRowAsksForANewName() {
        val asked = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val marks = listOf(PhoneBookmark("b1", "book", "rev", "{}", "Chapter one · 4%", 1))
        compose.setContent {
            FolioTheme(dynamic = false) {
                ReaderContentsSheet(
                    emptyList(),
                    -1,
                    noteItems(emptyList(), marks.map(::placed), emptyList()),
                    {},
                    NotesActions(onOpen = { opened += it.id }, onRename = { asked += it.id }),
                    initialTab = 1,
                )
            }
        }
        compose.onNodeWithText("Chapter one · 4%").assertIsDisplayed()
        compose.onNodeWithContentDescription("Options").performClick()
        compose.onNodeWithText("Rename").performClick()
        assertEquals(listOf("b1"), asked)
        // Rename does not also open the bookmark.
        assertTrue(opened.isEmpty())
    }

    @Test
    fun theNameFieldKeepsOneShortLineAndDoneSaves() {
        var value by mutableStateOf(TextFieldValue("Chapter one · 4%"))
        var done = 0
        compose.setContent {
            FolioTheme(dynamic = false) {
                RenameBookmarkField(value, { value = it }, onDone = { done++ })
            }
        }
        compose.onNodeWithTag(READER_BOOKMARK_RENAME_TAG).performTextReplacement("Where Ada starts")
        assertEquals("Where Ada starts", value.text)
        compose.onNodeWithTag(READER_BOOKMARK_RENAME_TAG).performImeAction()
        assertEquals(1, done)
        compose.onNodeWithTag(READER_BOOKMARK_RENAME_TAG).performTextReplacement("x".repeat(300))
        assertEquals(BOOKMARK_TITLE_MAX, value.text.length)
        // The dialog's Save is off for a blank name; the store refuses one too.
        assertNull(cleanBookmarkTitle("   "))
    }
}
