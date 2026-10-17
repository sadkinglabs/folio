package dev.sadking.folio.reader

import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Every page WebView asks for a normal refresh rate, including ones added later. */
@RunWith(RobolectricTestRunner::class)
class PageFrameRateTest {
    @Test
    fun pageWebViewsAskForANormalRefreshRate() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val container = FrameLayout(context)
        val pager = FrameLayout(context).also { container.addView(it) }
        val first = WebView(context).also { pager.addView(it) }
        keepPageFramesSmooth(container)
        container.viewTreeObserver.dispatchOnGlobalLayout()
        assertEquals(View.REQUESTED_FRAME_RATE_CATEGORY_NORMAL, first.requestedFrameRate)

        // A chapter added later is found at the next layout.
        val next = WebView(context).also { pager.addView(it) }
        container.viewTreeObserver.dispatchOnGlobalLayout()
        assertEquals(View.REQUESTED_FRAME_RATE_CATEGORY_NORMAL, next.requestedFrameRate)
    }
}
