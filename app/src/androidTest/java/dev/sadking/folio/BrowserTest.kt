package dev.sadking.folio

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.sadking.folio.acquire.BookSourceStore
import dev.sadking.folio.acquire.BrowserActivity
import dev.sadking.folio.acquire.BrowserLaunch
import dev.sadking.folio.acquire.BrowserWebView
import dev.sadking.folio.acquire.FIND_SEARCH_TAG
import dev.sadking.folio.ui.ADD_GET_ONLINE_TAG
import dev.sadking.folio.ui.AddBooksSheet
import dev.sadking.folio.ui.FolioTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The in-app browser on a device or emulator (M5): its screens, WebView settings and entry. */
class BrowserTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun freshSources() {
        context.getSharedPreferences(BookSourceStore.PREFERENCES, 0).edit().clear().commit()
    }

    @Test
    fun findModeOpensTheStartPage() {
        ActivityScenario.launch<BrowserActivity>(BrowserLaunch.find(context)).use {
            compose.onNodeWithText("Get a book").assertIsDisplayed()
            compose.onNodeWithTag(FIND_SEARCH_TAG).assertIsDisplayed()
            compose.onNodeWithText("Standard Ebooks").assertIsDisplayed()
            compose.onNodeWithText("Project Gutenberg").assertIsDisplayed()
            compose.onNodeWithText("Internet Archive").assertIsDisplayed()
        }
    }

    @Test
    fun sourcesModeShowsTheSourcesAndHidesABuiltIn() {
        ActivityScenario.launch<BrowserActivity>(BrowserLaunch.sources(context)).use {
            compose.onNodeWithText("Book sources").assertIsDisplayed()
            compose.onNodeWithText("Add a site").assertIsDisplayed()
            compose
                .onNodeWithContentDescription("Internet Archive", substring = true)
                .performClick()
            compose.waitUntil(5_000) {
                BookSourceStore(context).visible().none { it.id == "internet-archive" }
            }
        }
    }

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun request(url: String) =
        object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)

            override fun isForMainFrame() = true

            override fun isRedirect() = false

            override fun hasGesture() = true

            override fun getMethod() = "GET"

            override fun getRequestHeaders(): Map<String, String> = emptyMap()
        }

    @Test
    fun theWebViewIsLockedDown() {
        onMain {
            for (scripts in listOf(false, true)) {
                val view = WebView(context)
                var blocked = 0
                BrowserWebView.configure(view, scripts, { blocked++ }, {}, {})
                val s = view.settings
                assertEquals(scripts, s.javaScriptEnabled)
                assertEquals(scripts, s.domStorageEnabled)
                assertFalse(s.allowFileAccess)
                assertFalse(s.allowContentAccess)
                assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, s.mixedContentMode)
                assertTrue(s.safeBrowsingEnabled)
                assertFalse(s.javaScriptCanOpenWindowsAutomatically)
                assertFalse(s.supportMultipleWindows())
                assertFalse(
                    android.webkit.CookieManager.getInstance().acceptThirdPartyCookies(view)
                )
                val client = view.webViewClient
                assertFalse(client.shouldOverrideUrlLoading(view, request("https://a.org/")))
                for (url in
                    listOf("intent://x#Intent;end", "market://details?id=x", "file:///sdcard/a")) {
                    assertTrue(url, client.shouldOverrideUrlLoading(view, request(url)))
                }
                assertEquals(3, blocked)
                // Plain http is taken over and loaded as https instead.
                assertTrue(client.shouldOverrideUrlLoading(view, request("http://a.org/")))
                view.destroy()
            }
        }
    }

    @Test
    fun theAddSheetRowOpensTheBrowser() {
        val monitor =
            InstrumentationRegistry.getInstrumentation()
                .addMonitor(BrowserActivity::class.java.name, null, false)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { FolioTheme(dynamic = false) { AddBooksSheet("", {}, {}) } }
            }
            compose.onNodeWithText("Get a book online").assertIsDisplayed()
            compose.onNodeWithTag(ADD_GET_ONLINE_TAG).performClick()
            val opened = monitor.waitForActivityWithTimeout(10_000)
            assertNotNull(opened)
            opened.finish()
        }
        InstrumentationRegistry.getInstrumentation().removeMonitor(monitor)
    }
}
