package dev.sadking.folio.ui.stats

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.ui.FolioTheme
import dev.sadking.folio.ui.sampleYearReview
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class ShareImageTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun writesThePngAtItsSize() {
        val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(20, 80, 120))
        val file = writeSharePng(bitmap, context.cacheDir, 2026)
        assertEquals(File(context.cacheDir, "share/year-2026.png"), file)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        assertEquals(1080, bounds.outWidth)
        assertEquals(1920, bounds.outHeight)
    }

    @Test
    fun theIntentSharesThePngThroughTheProvider() {
        val file =
            File(context.cacheDir, "share/year-2026.png").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1))
            }
        val chooser = shareImageIntent(context, file)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/png", send.type)
        val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        assertEquals("content://${context.packageName}.photos/share/year-2026.png", uri.toString())
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
    }

    @Test
    fun theRecorderGivesAFullSizeImageThatIsNotOneColour() {
        lateinit var layer: GraphicsLayer
        compose.setContent {
            layer = rememberGraphicsLayer()
            FolioTheme(dynamic = false) { ShareCardRecorder(sampleYearReview, layer) }
        }
        compose.waitForIdle()
        // Draws the view, which records the layer.
        compose.onRoot().captureToImage()
        val bitmap = runBlocking { layer.toImageBitmap() }.asAndroidBitmap()
        assertEquals(1080, bitmap.width)
        assertEquals(1920, bitmap.height)
        val colours = HashSet<Int>()
        for (y in 0 until bitmap.height step 16) {
            for (x in 0 until bitmap.width step 16) colours += bitmap.getPixel(x, y)
        }
        assertTrue("the image is one flat colour", colours.size > 8)
    }
}
