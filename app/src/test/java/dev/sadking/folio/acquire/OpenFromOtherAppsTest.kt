package dev.sadking.folio.acquire

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The manifest's open and share filters (M5), matched by the package manager against the merged
 * manifest: books by type, generic types only with a book's extension, nothing else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpenFromOtherAppsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun opens(intent: Intent): Boolean =
        context.packageManager
            .queryIntentActivities(intent.setPackage(context.packageName), PackageManager.MATCH_ALL)
            .any { it.activityInfo.name == MainActivity::class.java.name }

    private fun view(uri: String, type: String) =
        Intent(Intent.ACTION_VIEW)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .setDataAndType(
                Uri.parse(uri),
                type,
            )

    private fun send(type: String, multiple: Boolean = false) =
        Intent(if (multiple) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .setType(type)

    @Test
    fun everyBookTypeOpensAndShares() {
        for (type in
            listOf(
                "application/epub+zip",
                "application/pdf",
                "application/vnd.comicbook+zip",
                "application/x-cbz",
                "application/vnd.comicbook-rar",
                "application/x-cbr",
            )) {
            assertTrue(type, opens(view("content://files/doc/42", type)))
            assertTrue(type, opens(send(type)))
            assertTrue(type, opens(send(type, multiple = true)))
        }
    }

    @Test
    fun genericTypesOpenOnlyWithABookExtension() {
        for (name in
            listOf("Home.epub", "Home.EPUB", "a.b.pdf", "x.y.z.cbz", "Comic.CBR", "Home.cbr")) {
            assertTrue(name, opens(view("content://files/tree/$name", "application/octet-stream")))
            assertTrue(name, opens(view("content://files/tree/$name", "application/zip")))
        }
        assertFalse(opens(view("content://files/tree/photo.jpg", "application/octet-stream")))
        assertFalse(opens(view("content://files/tree/archive.zip", "application/zip")))
        assertFalse(opens(view("content://files/doc/42", "application/octet-stream")))
    }

    @Test
    fun otherSchemesAndTypesAreNotOffered() {
        assertFalse(opens(view("https://example.org/Home.epub", "application/epub+zip")))
        assertFalse(opens(view("file:///sdcard/Home.epub", "application/epub+zip")))
        assertFalse(opens(view("content://files/doc/42", "text/html")))
        assertFalse(opens(send("image/jpeg")))
    }

    @Test
    fun theBrowserIsNotOpenToOtherApps() {
        val info =
            context.packageManager.getActivityInfo(
                android.content.ComponentName(context, BrowserActivity::class.java),
                0,
            )
        assertFalse(info.exported)
    }
}
