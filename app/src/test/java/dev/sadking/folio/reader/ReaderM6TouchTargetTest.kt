package dev.sadking.folio.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Material's 48 dp touch target rule, and a label on everything tappable, over the reader's M6
 * previews: search, time left, two pages, and Define.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class ReaderM6TouchTargetTest {
    @get:Rule val compose = createComposeRule()

    private val screens: List<Pair<String, @Composable () -> Unit>> =
        listOf(
            "ReaderSearchPreview" to { ReaderSearchPreview() },
            "ReaderSearchEmptyPreview" to { ReaderSearchEmptyPreview() },
            "ReaderTimeLeftPreview" to { ReaderTimeLeftPreview() },
            "ReaderTextTwoPagesPreview" to { ReaderTextTwoPagesPreview() },
            "DefineSheetPreview" to { DefineSheetPreview() },
            "DefineDownloadPreview" to { DefineDownloadPreview() },
            "DefineDownloadingPreview" to { DefineDownloadingPreview() },
            "DefineNotFoundPreview" to { DefineNotFoundPreview() },
            "ReaderControlsPreview" to { ReaderControlsPreview() },
        )

    @Test
    fun everythingTappableIsAtLeast48dpAndNamed() {
        var current by mutableStateOf(screens.first().second)
        compose.setContent { current() }
        val small = mutableListOf<String>()
        val unnamed = mutableListOf<String>()
        for ((name, screen) in screens) {
            current = screen
            compose.waitForIdle()
            val density = compose.density.density
            compose
                .onAllNodes(hasClickAction(), useUnmergedTree = false)
                .fetchSemanticsNodes()
                .forEach { node ->
                    val bounds = node.touchBoundsInRoot
                    val w = bounds.width / density
                    val h = bounds.height / density
                    if (w < 1f || h < 1f) return@forEach
                    val label =
                        node.config
                            .getOrNull(SemanticsProperties.ContentDescription)
                            ?.joinToString()
                            ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
                    if (label.isNullOrBlank()) unnamed += "$name: ${node.config}"
                    if (w < 47.5f || h < 47.5f)
                        small += "$name: '$label' ${w.toInt()} x ${h.toInt()} dp"
                }
        }
        assertTrue(small.joinToString("\n"), small.isEmpty())
        assertTrue(unnamed.joinToString("\n"), unnamed.isEmpty())
    }
}
