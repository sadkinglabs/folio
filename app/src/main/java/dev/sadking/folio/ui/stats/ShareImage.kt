package dev.sadking.folio.ui.stats

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.FileProvider
import java.io.File

/** Writes [bitmap] as `share/year-<year>.png` in [cacheDir] and returns the file. */
internal fun writeSharePng(bitmap: Bitmap, cacheDir: File, year: Int): File {
    val folder = File(cacheDir, "share").apply { mkdirs() }
    val file = File(folder, "year-$year.png")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return file
}

/** The share sheet for [file], a PNG in the cache's `share/` folder. */
internal fun shareImageIntent(context: Context, file: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
    val send =
        Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newUri(context.contentResolver, file.name, uri)
    return Intent.createChooser(send, null)
}

/**
 * Draws the share card out of sight into [layer], 1080 × 1920 px (360 × 640 dp at density 3, font
 * scale 1). The layer keeps the last recording, so `layer.toImageBitmap()` returns the image once a
 * frame has been drawn. Hidden from the eye and from TalkBack.
 */
@Composable
internal fun ShareCardRecorder(review: YearReview, layer: GraphicsLayer) {
    // No Surface sits above the hidden card, so the text colour is set here: without it the card's
    // text is black on the dark theme's surface.
    CompositionLocalProvider(
        LocalDensity provides Density(3f, 1f),
        LocalContentColor provides MaterialTheme.colorScheme.onSurface,
    ) {
        Box(
            Modifier.requiredSize(shareCardWidth, shareCardHeight)
                .clearAndSetSemantics {}
                .offset { IntOffset(-OFFSCREEN_PX, 0) }
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                }
        ) {
            ScaledShareCard(review, shareCardWidth)
        }
    }
}

/**
 * Far enough left that the card is never on screen, whatever the window. It is moved rather than
 * faded (`alpha(0f)`), so it is certain to be drawn, and so recorded.
 */
private const val OFFSCREEN_PX = 20_000
