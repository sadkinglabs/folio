package dev.sadking.folio.acquire

import android.content.Context
import android.content.Intent

/**
 * How to open the in-app browser. The literal values are a contract: screens on other branches
 * build the same intent by class name (docs/M5_M6.md §2.1), so they must never change.
 */
object BrowserLaunch {
    const val ACTIVITY = "dev.sadking.folio.acquire.BrowserActivity"
    const val EXTRA_MODE = "dev.sadking.folio.acquire.MODE"
    const val EXTRA_TITLE = "dev.sadking.folio.acquire.TITLE"
    const val EXTRA_AUTHORS = "dev.sadking.folio.acquire.AUTHORS"
    const val EXTRA_WISH_ID = "dev.sadking.folio.acquire.WISH_ID"
    const val MODE_FIND = "find"
    const val MODE_SOURCES = "sources"

    /**
     * The start page: a search box and the visible sources; with a title, that search runs at once.
     */
    fun find(
        context: Context,
        title: String? = null,
        authors: List<String> = emptyList(),
        wishId: String? = null,
    ): Intent =
        Intent().setClassName(context, ACTIVITY).putExtra(EXTRA_MODE, MODE_FIND).apply {
            if (!title.isNullOrBlank()) putExtra(EXTRA_TITLE, title)
            if (authors.isNotEmpty()) putExtra(EXTRA_AUTHORS, authors.toTypedArray())
            if (wishId != null) putExtra(EXTRA_WISH_ID, wishId)
        }

    /** Book sources: show, hide, add, edit, remove; tap one to browse it. */
    fun sources(context: Context): Intent =
        Intent().setClassName(context, ACTIVITY).putExtra(EXTRA_MODE, MODE_SOURCES)
}
