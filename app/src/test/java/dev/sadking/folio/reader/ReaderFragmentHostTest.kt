package dev.sadking.folio.reader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import dev.sadking.folio.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** Runs AndroidX's real FragmentManager with Android framework shadows, without an emulator. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [28])
class ReaderFragmentHostTest {
    class HostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_Folio_Reader)
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this))
        }
    }

    class PageFragment : Fragment() {
        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            state: Bundle?,
        ): View = TextView(requireContext()).apply { text = "Reader page" }
    }

    @Test
    fun oldDetachedIdLookupReproducesLaunchCrash() {
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        try {
            val activity = controller.get()
            val detached = FrameLayout(activity).apply { id = R.id.reader_container }
            assertFalse(detached.isAttachedToWindow)
            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    activity.supportFragmentManager
                        .beginTransaction()
                        .replace(detached.id, PageFragment(), "old")
                        .commitNow()
                }
            assertTrue(failure.message.orEmpty().contains("No view found for id"))
            // Recover the deliberately invalid host so normal Activity teardown can proceed.
            activity.findViewById<ViewGroup>(android.R.id.content).addView(detached)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun readerMountWaitsForTheAttachedResumedHost() {
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        try {
            val activity = controller.get()
            val container = FragmentContainerView(activity).apply { id = R.id.reader_container }
            val page = PageFragment()
            assertFalse(container.isAttachedToWindow)
            var mounts = 0
            mountReaderWhenReady(activity, container) {
                mounts++
                activity.supportFragmentManager
                    .beginTransaction()
                    .replace(container.id, page, "epub")
                    .commitNow()
            }
            assertEquals(0, mounts)
            assertNull(activity.supportFragmentManager.findFragmentByTag("epub"))
            activity.findViewById<ViewGroup>(android.R.id.content).addView(container)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(1, mounts)
            assertSame(container, page.requireView().parent)
            assertSame(page, activity.supportFragmentManager.findFragmentByTag("epub"))
            assertTrue(container.isAttachedToWindow)
            assertEquals("Reader page", (page.requireView() as TextView).text.toString())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun readerWaitsThroughBackgroundLoadingAndMountsOnlyOnce() {
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        try {
            val activity = controller.get()
            val container = FragmentContainerView(activity).apply { id = R.id.reader_container }
            controller.pause()
            var mounts = 0
            mountReaderWhenReady(activity, container) {
                mounts++
                activity.supportFragmentManager
                    .beginTransaction()
                    .replace(container.id, PageFragment(), "epub")
                    .commitNow()
            }
            activity.findViewById<ViewGroup>(android.R.id.content).addView(container)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(0, mounts)
            controller.resume()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(1, mounts)
            assertTrue(
                activity.supportFragmentManager
                    .findFragmentByTag("epub")!!
                    .requireView()
                    .isAttachedToWindow
            )
            controller.pause().resume()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(1, mounts)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
