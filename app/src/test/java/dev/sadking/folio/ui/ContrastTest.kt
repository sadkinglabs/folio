package dev.sadking.folio.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WCAG contrast, as Material's accessibility guidance asks, for the colour pairs the app draws text
 * with: 4.5:1 for body text, 3:1 for large text and icons. Light and dark, without wallpaper
 * colours (those come from the phone and are Material's own tonal palettes).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContrastTest {
    @get:Rule val compose = createComposeRule()

    private fun ratio(a: Color, b: Color): Double {
        val x = a.compositeOver(b).luminance() + 0.05
        val y = b.luminance() + 0.05
        return maxOf(x, y) / minOf(x, y)
    }

    private fun schemes(): Map<String, ColorScheme> {
        val found = mutableMapOf<String, ColorScheme>()
        compose.setContent {
            FolioTheme(dark = false, dynamic = false) { found["light"] = MaterialTheme.colorScheme }
            FolioTheme(dark = true, dynamic = false) { found["dark"] = MaterialTheme.colorScheme }
        }
        compose.waitForIdle()
        return found
    }

    @Test
    fun textMeetsWcagOnEverySurfaceItIsDrawnOn() {
        val failures = mutableListOf<String>()
        for ((mode, c) in schemes()) {
            val grounds =
                mapOf(
                    "surface" to c.surface,
                    "surfaceContainerLow" to c.surfaceContainerLow,
                    "surfaceContainer" to c.surfaceContainer,
                    "surfaceContainerHigh" to c.surfaceContainerHigh,
                    "surfaceContainerHighest" to c.surfaceContainerHighest,
                )
            // Body text: 4.5:1 on every ground.
            val body = mapOf("onSurface" to c.onSurface, "onSurfaceVariant" to c.onSurfaceVariant)
            // Coloured text and icons used as links, text buttons and marks: at least 3:1, and
            // 4.5:1 where they are body-sized text (primary as text buttons).
            val accents =
                mapOf("primary" to c.primary, "error" to c.error, "tertiary" to c.tertiary)
            for ((g, ground) in grounds) {
                body.forEach { (n, ink) ->
                    val r = ratio(ink, ground)
                    if (r < 4.5) failures += "$mode $n on $g: %.2f (needs 4.5)".format(r)
                }
                accents.forEach { (n, ink) ->
                    val r = ratio(ink, ground)
                    if (r < 4.5) failures += "$mode $n on $g: %.2f (needs 4.5 as text)".format(r)
                }
            }
            // Content on its own container.
            listOf(
                    "onPrimary/primary" to (c.onPrimary to c.primary),
                    "onPrimaryContainer/primaryContainer" to
                        (c.onPrimaryContainer to c.primaryContainer),
                    "onSecondaryContainer/secondaryContainer" to
                        (c.onSecondaryContainer to c.secondaryContainer),
                    "onTertiaryContainer/tertiaryContainer" to
                        (c.onTertiaryContainer to c.tertiaryContainer),
                    "onErrorContainer/errorContainer" to (c.onErrorContainer to c.errorContainer),
                )
                .forEach { (n, pair) ->
                    val r = ratio(pair.first, pair.second)
                    if (r < 4.5) failures += "$mode $n: %.2f (needs 4.5)".format(r)
                }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
