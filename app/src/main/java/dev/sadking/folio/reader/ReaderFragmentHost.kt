package dev.sadking.folio.reader

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.core.view.doOnAttach
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import kotlinx.coroutines.launch

/**
 * AndroidView's factory runs before attachment. A resumed, attached host can safely resolve IDs.
 */
internal fun mountReaderWhenReady(
    activity: FragmentActivity,
    container: FragmentContainerView,
    mount: () -> Unit,
) {
    container.doOnAttach {
        activity.lifecycleScope.launch {
            activity.lifecycle.withResumed {
                if (container.isAttachedToWindow && !activity.supportFragmentManager.isStateSaved)
                    mount()
            }
        }
    }
}

/**
 * Asks for a normal refresh rate whenever the page's WebViews draw. A phone with an adaptive
 * display (the owner's Pixel) lowers the rate when nothing seems to move, and changes made by the
 * page's own script (a read-aloud highlight, a page sliding across) do not count as movement: they
 * showed seconds late, or the slide as a jump. Readium adds a WebView per chapter, so every layout
 * looks for new ones.
 */
internal fun keepPageFramesSmooth(container: ViewGroup) {
    fun apply(view: View) {
        if (view is WebView) {
            if (view.requestedFrameRate != View.REQUESTED_FRAME_RATE_CATEGORY_NORMAL)
                view.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_NORMAL
        } else if (view is ViewGroup) for (i in 0 until view.childCount) apply(view.getChildAt(i))
    }
    container.viewTreeObserver.addOnGlobalLayoutListener { apply(container) }
}
