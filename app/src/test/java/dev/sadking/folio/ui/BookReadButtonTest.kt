package dev.sadking.folio.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.R
import dev.sadking.folio.data.Book
import dev.sadking.folio.data.PhoneReading
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class BookReadButtonTest {
    @get:Rule val compose = createComposeRule()

    private fun book(format: String) =
        Book(
            "b",
            "Title",
            "[\"A\"]",
            originalPath = "/none",
            currentPath = "/none",
            originalHash = "h",
            currentHash = "h",
            format = format,
        )

    @Test
    fun unknownFormatShowsOpensOnYourEreader() {
        compose.setContent { FolioTheme(dynamic = false) { BookDetails(book("mobi")) } }
        compose.onNodeWithTag(BOOK_PRIMARY_TAG).assertIsNotEnabled()
        compose.onNodeWithText("Opens on your e-reader").assertExists()
        compose.onNodeWithTag(BOOK_READ_MENU_TAG).assertDoesNotExist()
    }

    @Test
    fun pdfWithoutAPlaceIsAPlainReadButton() {
        var reads = 0
        compose.setContent {
            FolioTheme(dynamic = false) { BookDetails(book("pdf"), onRead = { reads++ }) }
        }
        compose.onNodeWithTag(BOOK_PRIMARY_TAG).assertIsEnabled()
        compose.onNodeWithTag(BOOK_READ_MENU_TAG).assertDoesNotExist()
        compose.onNodeWithTag(BOOK_PRIMARY_TAG).performClick()
        compose.runOnIdle { assertEquals(1, reads) }
    }

    @Test
    fun pdfWithAPlaceKeepsTheMenuWithoutListen() {
        compose.setContent {
            FolioTheme(dynamic = false) {
                BookDetails(
                    book("pdf"),
                    phoneReading = PhoneReading("b", "h", "{}", "", 0.4, 1),
                )
            }
        }
        compose.onNodeWithTag(BOOK_READ_MENU_TAG).assertExists()
        compose.onNodeWithTag(BOOK_READ_MENU_TAG).performClick()
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.onNodeWithText(context.getString(R.string.book_read_beginning)).assertExists()
        compose.onNodeWithText(context.getString(R.string.book_read_listen)).assertDoesNotExist()
    }
}
