package dev.sadking.folio.link

/**
 * When Librillo Link stops by itself (M9). Link runs while Librillo is in the foreground, and for
 * [graceMs] after it leaves. A sync an e-reader has started keeps it running until the e-reader
 * finishes, or until the sync has been silent for [sessionMs], when the server drops it too.
 *
 * Pure, with an injected [clock] that never goes backwards (SystemClock.elapsedRealtime on the
 * phone), so the timing is unit-tested. The app calls [foreground] and [background]; the service
 * calls [serviceStarted], [request] for each answered request, and [shouldStop] once a minute.
 */
class LinkLifecycle(
    private val clock: () -> Long,
    private val graceMs: Long = GRACE_MS,
    private val sessionMs: Long = SyncRoutes.SESSION_MS,
) {
    private var foreground = false
    /** When Librillo left the foreground; null while it is in the foreground or before. */
    private var backgroundSince: Long? = null
    /** Each e-reader's last request in an unfinished sync, by its address. */
    private val syncing = HashMap<String, Long>()

    /** True while Librillo is in the foreground. */
    val inForeground: Boolean
        @Synchronized get() = foreground

    /** Librillo came to the foreground: Link keeps running. */
    @Synchronized
    fun foreground() {
        foreground = true
        backgroundSince = null
    }

    /** Librillo left the foreground: the countdown starts. A repeated call keeps the first time. */
    @Synchronized
    fun background() {
        if (foreground || backgroundSince == null) backgroundSince = clock()
        foreground = false
    }

    /**
     * The service started. Without a known foreground (started while Librillo was in the
     * background), the countdown starts now, so Link never runs on with no end.
     */
    @Synchronized
    fun serviceStarted() {
        if (!foreground && backgroundSince == null) backgroundSince = clock()
    }

    /** The server answered [route] (the path, without its query) with [status] for [remote]. */
    @Synchronized
    fun request(route: String, status: Int, remote: String) {
        val path = route.substringBefore('?').trimEnd('/')
        val now = clock()
        when {
            path == SYNC_ROUTE -> if (status in 200..299) syncing[remote] = now
            path == FINISH_ROUTE -> syncing.remove(remote)
            path == HELLO_ROUTE -> Unit
            path.startsWith(V2_PREFIX) && remote in syncing -> syncing[remote] = now
        }
    }

    /** True while an e-reader is in the middle of a sync. */
    @Synchronized
    fun syncInProgress(): Boolean {
        val now = clock()
        syncing.values.removeAll { now - it >= sessionMs }
        return syncing.isNotEmpty()
    }

    /** True once Link should stop by itself. */
    @Synchronized
    fun shouldStop(): Boolean {
        val since = backgroundSince ?: return false
        if (foreground || clock() - since < graceMs) return false
        return !syncInProgress()
    }

    companion object {
        /** How long Link keeps running after Librillo leaves the foreground. */
        const val GRACE_MS = 30 * 60 * 1000L
        /** How often the service asks [shouldStop]. */
        const val CHECK_MS = 60 * 1000L
        private const val V2_PREFIX = "/folio/v2/"
        private const val SYNC_ROUTE = "/folio/v2/sync"
        private const val FINISH_ROUTE = "/folio/v2/finish"
        private const val HELLO_ROUTE = "/folio/v2/hello"
    }
}
