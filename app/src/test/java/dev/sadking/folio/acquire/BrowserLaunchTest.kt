package dev.sadking.folio.acquire

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The browser's intent values are a contract with screens on other branches, which write them as
 * literals (docs/M5_M6.md §2.1). They must never change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BrowserLaunchTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theFiveLiteralsArePinned() {
        assertEquals("dev.sadking.folio.acquire.BrowserActivity", BrowserLaunch.ACTIVITY)
        assertEquals("dev.sadking.folio.acquire.MODE", BrowserLaunch.EXTRA_MODE)
        assertEquals("dev.sadking.folio.acquire.TITLE", BrowserLaunch.EXTRA_TITLE)
        assertEquals("dev.sadking.folio.acquire.AUTHORS", BrowserLaunch.EXTRA_AUTHORS)
        assertEquals("dev.sadking.folio.acquire.WISH_ID", BrowserLaunch.EXTRA_WISH_ID)
        assertEquals("find", BrowserLaunch.MODE_FIND)
        assertEquals("sources", BrowserLaunch.MODE_SOURCES)
        assertEquals(BrowserActivity::class.java.name, BrowserLaunch.ACTIVITY)
    }

    @Test
    fun findCarriesTheBookAndTheEntry() {
        val intent = BrowserLaunch.find(context, "Emma", listOf("Jane Austen"), "wish-1")
        assertEquals(BrowserLaunch.ACTIVITY, intent.component?.className)
        assertEquals(context.packageName, intent.component?.packageName)
        assertEquals("find", intent.getStringExtra(BrowserLaunch.EXTRA_MODE))
        assertEquals("Emma", intent.getStringExtra(BrowserLaunch.EXTRA_TITLE))
        assertArrayEquals(
            arrayOf("Jane Austen"),
            intent.getStringArrayExtra(BrowserLaunch.EXTRA_AUTHORS),
        )
        assertEquals("wish-1", intent.getStringExtra(BrowserLaunch.EXTRA_WISH_ID))
        val plain = BrowserLaunch.find(context)
        assertFalse(plain.hasExtra(BrowserLaunch.EXTRA_TITLE))
        assertFalse(plain.hasExtra(BrowserLaunch.EXTRA_WISH_ID))
    }

    @Test
    fun adaptivesLiteralIntentOpensTheSameScreen() {
        // Exactly what FolioApp.kt builds on the adaptive branch.
        val intent =
            android.content
                .Intent()
                .setClassName(context, "dev.sadking.folio.acquire.BrowserActivity")
                .putExtra("dev.sadking.folio.acquire.MODE", "find")
                .putExtra("dev.sadking.folio.acquire.TITLE", "Emma")
                .putExtra("dev.sadking.folio.acquire.AUTHORS", arrayOf("Jane Austen"))
                .putExtra("dev.sadking.folio.acquire.WISH_ID", "wish-1")
        val start = BrowserStart.from(intent, BookSources.BUILT_IN)
        assertEquals(BrowserPage.WEB, start.page)
        assertEquals("wish-1", start.wishId)
        assertEquals("Emma Jane Austen", start.query)
        val sources =
            android.content
                .Intent()
                .setClassName(context, "dev.sadking.folio.acquire.BrowserActivity")
                .putExtra("dev.sadking.folio.acquire.MODE", "sources")
        assertEquals(BrowserPage.SOURCES, BrowserStart.from(sources, BookSources.BUILT_IN).page)
        assertEquals(
            "sources",
            BrowserLaunch.sources(context).getStringExtra(BrowserLaunch.EXTRA_MODE),
        )
    }
}
