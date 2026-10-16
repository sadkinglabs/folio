package dev.sadking.folio.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkLifecycleTest {
    private var now = 1_000_000L
    private val lifecycle = LinkLifecycle({ now })

    private fun minutes(n: Int) = n * 60_000L

    private fun at(minute: Int, from: Long) {
        now = from + minutes(minute)
    }

    @Test
    fun linkNeverStopsWhileLibrilloIsOpen() {
        lifecycle.foreground()
        lifecycle.serviceStarted()
        now += minutes(600)
        assertFalse(lifecycle.shouldStop())
    }

    @Test
    fun linkStopsThirtyMinutesAfterLibrilloLeaves() {
        lifecycle.foreground()
        val left = now
        lifecycle.background()
        now = left + minutes(30) - 1
        assertFalse(lifecycle.shouldStop())
        now = left + minutes(30)
        assertTrue(lifecycle.shouldStop())
    }

    @Test
    fun comingBackStartsTheThirtyMinutesAgain() {
        lifecycle.foreground()
        lifecycle.background()
        now += minutes(20)
        lifecycle.foreground()
        now += minutes(20)
        assertFalse(lifecycle.shouldStop())
        val left = now
        lifecycle.background()
        at(29, left)
        assertFalse(lifecycle.shouldStop())
        at(30, left)
        assertTrue(lifecycle.shouldStop())
    }

    @Test
    fun leavingTwiceKeepsTheFirstTime() {
        lifecycle.foreground()
        val left = now
        lifecycle.background()
        now += minutes(10)
        lifecycle.background()
        at(30, left)
        assertTrue(lifecycle.shouldStop())
    }

    @Test
    fun aSyncInProgressKeepsLinkRunningUntilItFinishes() {
        lifecycle.foreground()
        val left = now
        lifecycle.background()
        at(25, left)
        lifecycle.request("/folio/v2/sync", 200, "192.168.1.77")
        at(40, left)
        lifecycle.request("/folio/v2/upload", 200, "192.168.1.77")
        at(65, left)
        assertFalse(lifecycle.shouldStop())
        lifecycle.request("/folio/v2/finish", 200, "192.168.1.77")
        assertTrue(lifecycle.shouldStop())
    }

    @Test
    fun aSyncThatGoesSilentEndsWithItsSession() {
        lifecycle.foreground()
        val left = now
        lifecycle.background()
        at(10, left)
        lifecycle.request("/folio/v2/sync", 200, "192.168.1.77")
        now = left + minutes(10) + SyncRoutes.SESSION_MS - 1
        assertFalse(lifecycle.shouldStop())
        now = left + minutes(10) + SyncRoutes.SESSION_MS
        assertTrue(lifecycle.shouldStop())
    }

    @Test
    fun onlyASyncKeepsLinkRunning() {
        lifecycle.foreground()
        val left = now
        lifecycle.background()
        at(29, left)
        lifecycle.request("/syncs/progress", 200, "192.168.1.77")
        lifecycle.request("/folio/v2/hello", 200, "192.168.1.77")
        lifecycle.request("/folio/v1/queue", 200, "192.168.1.77")
        // Without a sync started, a v2 request is not part of one.
        lifecycle.request("/folio/v2/upload", 200, "192.168.1.77")
        at(30, left)
        assertTrue(lifecycle.shouldStop())
    }

    @Test
    fun aRefusedSyncDoesNotCount() {
        lifecycle.foreground()
        val left = now
        lifecycle.background()
        at(29, left)
        lifecycle.request("/folio/v2/sync", 403, "192.168.1.77")
        lifecycle.request("/folio/v2/sync", 401, "192.168.1.77")
        at(30, left)
        assertTrue(lifecycle.shouldStop())
    }

    @Test
    fun oneEreaderFinishingLeavesAnotherSyncing() {
        lifecycle.foreground()
        val left = now
        lifecycle.background()
        at(29, left)
        lifecycle.request("/folio/v2/sync", 200, "192.168.1.77")
        lifecycle.request("/folio/v2/sync/", 200, "192.168.1.78")
        lifecycle.request("/folio/v2/finish", 200, "192.168.1.77")
        at(31, left)
        assertTrue(lifecycle.syncInProgress())
        assertFalse(lifecycle.shouldStop())
        lifecycle.request("/folio/v2/finish", 410, "192.168.1.78")
        assertTrue(lifecycle.shouldStop())
    }

    @Test
    fun startedInTheBackgroundStopsThirtyMinutesLater() {
        val started = now
        lifecycle.serviceStarted()
        at(29, started)
        assertFalse(lifecycle.shouldStop())
        at(30, started)
        assertTrue(lifecycle.shouldStop())
        // Opening Librillo keeps it.
        lifecycle.foreground()
        assertFalse(lifecycle.shouldStop())
    }

    @Test
    fun aServiceStartWhileOpenChangesNothing() {
        lifecycle.foreground()
        lifecycle.serviceStarted()
        val left = now + minutes(5)
        now = left
        lifecycle.background()
        lifecycle.serviceStarted()
        at(29, left)
        assertFalse(lifecycle.shouldStop())
        at(30, left)
        assertTrue(lifecycle.shouldStop())
    }

    @Test
    fun beforeAnythingHappensNothingStops() {
        now += minutes(600)
        assertFalse(lifecycle.shouldStop())
        assertFalse(lifecycle.inForeground)
    }
}
