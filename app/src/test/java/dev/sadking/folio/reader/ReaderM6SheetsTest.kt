package dev.sadking.folio.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.sadking.folio.core.dictionary.DictionaryEntry
import dev.sadking.folio.core.dictionary.PartOfSpeech
import dev.sadking.folio.core.dictionary.Sense
import dev.sadking.folio.reader.dictionary.DEFINE_DOWNLOAD_TAG
import dev.sadking.folio.reader.dictionary.DefineSheet
import dev.sadking.folio.reader.dictionary.DictionaryState
import dev.sadking.folio.ui.FolioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The search and Define sheets on the JVM (Robolectric), not on a device. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class ReaderM6SheetsTest {
    @get:Rule val compose = createComposeRule()

    private fun hit(chapter: String, match: String) =
        SearchHit(
            Locator(Url("c.xhtml")!!, MediaType.XHTML),
            chapter,
            "the ",
            match,
            " was old",
        )

    @Test
    fun theSelectionMenuOffersDefineBetweenNoteAndCopy() {
        assertEquals(
            listOf(
                SELECTION_HIGHLIGHT,
                SELECTION_COLOUR,
                SELECTION_NOTE,
                SELECTION_DEFINE,
                SELECTION_COPY,
            ),
            SELECTION_MENU.map { it.first },
        )
        assertEquals(dev.sadking.folio.R.string.rd6_define_action, SELECTION_MENU[3].second)
    }

    @Test
    fun typingRunsTheSearchAndATapOpensTheMatch() {
        val searched = mutableListOf<String>()
        val opened = mutableListOf<SearchHit>()
        var text by mutableStateOf("")
        val found = hit("Chapter one", "orchard")
        var results by mutableStateOf(SearchResults())
        compose.setContent {
            FolioTheme(dynamic = false) {
                ReaderSearchSheet(
                    text,
                    results,
                    onText = { text = it },
                    onSearch = {
                        searched += it
                        results = SearchResults(it.trim(), listOf(found), SearchStatus.DONE)
                    },
                    onHit = { opened += it },
                )
            }
        }
        compose.onNodeWithTag(READER_SEARCH_FIELD_TAG).performTextInput("orchard")
        compose.waitUntil(5_000) { searched.isNotEmpty() }
        assertEquals(listOf("orchard"), searched)
        compose.onNodeWithText("1 match").assertIsDisplayed()
        compose.onNodeWithText("Chapter one").assertIsDisplayed()
        compose.onNodeWithTag(readerSearchHitTag(0)).performClick()
        assertEquals(listOf(found), opened)
        // The clear button empties the field.
        compose.onNodeWithContentDescription("Clear search").performClick()
        assertEquals("", text)
    }

    @Test
    fun theCountLineSaysWhatHappened() {
        var results by mutableStateOf(SearchResults("walnut", emptyList(), SearchStatus.DONE))
        compose.setContent {
            FolioTheme(dynamic = false) { ReaderSearchSheet("walnut", results, {}, {}, {}) }
        }
        compose.onNodeWithText("No matches for “walnut”").assertIsDisplayed()
        results = SearchResults("a", List(SEARCH_LIMIT) { hit("C", "a") }, SearchStatus.DONE)
        compose.onNodeWithText("Showing the first 500 matches").assertIsDisplayed()
        results = SearchResults("a", emptyList(), SearchStatus.FAILED)
        compose.onNodeWithText("Couldn't search this book. Try again.").assertIsDisplayed()
    }

    @Test
    fun theSheetTitleIsAHeading() {
        compose.setContent {
            FolioTheme(dynamic = false) { ReaderSearchSheet("", SearchResults(), {}, {}, {}) }
        }
        compose
            .onNode(
                hasText("Search this book") and
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
            )
            .assertExists()
    }

    private val entries =
        listOf(
            DictionaryEntry(
                "run",
                PartOfSpeech.VERB,
                listOf(
                    Sense("move fast by using one's feet", listOf("Don't run"), emptyList()),
                    Sense("flee", emptyList(), listOf("scarper", "turn tail")),
                ),
            )
        )

    @Test
    fun theFirstTimeDefineOffersTheDownloadWithItsSize() {
        var downloads = 0
        compose.setContent {
            FolioTheme(dynamic = false) {
                DefineSheet("orchard", DictionaryState.Missing, null, { downloads++ }, {})
            }
        }
        compose.onNodeWithText("About 11 MB.").assertIsDisplayed()
        compose.onNodeWithText("From Open English WordNet (CC BY 4.0)").assertExists()
        compose.onAllNodesWithText("Remove dictionary").assertCountEquals(0)
        compose.onNodeWithTag(DEFINE_DOWNLOAD_TAG).performClick()
        assertEquals(1, downloads)
    }

    @Test
    fun aFailedDownloadSaysSoAndOffersItAgain() {
        compose.setContent {
            FolioTheme(dynamic = false) {
                DefineSheet("orchard", DictionaryState.Failed, null, {}, {})
            }
        }
        compose
            .onNodeWithText("Couldn't download the dictionary", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("Download dictionary").assertIsDisplayed()
    }

    @Test
    fun anEntryShowsNumberedSensesExamplesAndSynonyms() {
        var removed = 0
        compose.setContent {
            FolioTheme(dynamic = false) {
                DefineSheet("running", DictionaryState.Ready, entries, {}, { removed++ })
            }
        }
        compose
            .onNode(hasText("run") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertExists()
        compose.onNodeWithText("verb").assertIsDisplayed()
        compose.onNodeWithText("1.").assertIsDisplayed()
        compose.onNodeWithText("move fast by using one's feet").assertIsDisplayed()
        compose.onNodeWithText("“Don't run”").assertIsDisplayed()
        compose.onNodeWithText("2.").assertIsDisplayed()
        compose.onNodeWithText("Also: scarper, turn tail").assertIsDisplayed()
        compose.onNodeWithText("Remove dictionary").performScrollTo().performClick()
        assertEquals(1, removed)
    }

    @Test
    fun nothingFoundAndTooLongSelectionsAreExplained() {
        var selection by mutableStateOf("Ardwyn")
        compose.setContent {
            FolioTheme(dynamic = false) {
                DefineSheet(selection, DictionaryState.Ready, emptyList(), {}, {})
            }
        }
        compose.onNodeWithText("No definition for “Ardwyn”").assertIsDisplayed()
        selection = "the orchard had been planted by someone who expected to be forgotten"
        compose
            .onNodeWithText("Select one word, or a short phrase, to look it up.")
            .assertIsDisplayed()
    }
}
