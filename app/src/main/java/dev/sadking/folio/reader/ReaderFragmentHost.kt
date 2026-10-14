package dev.sadking.folio.reader

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
