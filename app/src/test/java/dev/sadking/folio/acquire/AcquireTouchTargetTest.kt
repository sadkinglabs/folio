package dev.sadking.folio.acquire

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
 * Material's touch target rule on the browser's screens (M5): anything tappable offers at least 48
 * x 48 dp, and every tappable thing has a label for TalkBack.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class AcquireTouchTargetTest {
    @get:Rule val compose = createComposeRule()

    private val screens: List<Pair<String, @Composable () -> Unit>> =
        listOf(
            "AcquireSourcesPreview" to { AcquireSourcesPreview() },
            "AcquireSourceEditorPreview" to { AcquireSourceEditorPreview() },
            "AcquireFindPreview" to { AcquireFindPreview() },
            "AcquireDownloadingPreview" to { AcquireDownloadingPreview() },
            "AcquireSavedPreview" to { AcquireSavedPreview() },
            "AcquireSavedNoReaderPreview" to { AcquireSavedNoReaderPreview() },
            "AcquireProtectedPreview" to { AcquireProtectedPreview() },
            "AcquireAddRowPreview" to { AcquireAddRowPreview() },
        )

    @Test
    fun everythingTappableIsAtLeast48dpAndLabelled() {
        var current by mutableStateOf(screens.first().second)
        compose.setContent { current() }
        val problems = mutableListOf<String>()
        for ((name, screen) in screens) {
            current = screen
            compose.waitForIdle()
            val density = compose.density.density
            // Merged nodes carry the text of their children, which is what TalkBack reads.
            compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach { node ->
                val label =
                    node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                        ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
                        ?: node.config.getOrNull(SemanticsProperties.EditableText)?.text
                if (label.isNullOrBlank()) problems += "$name: a tappable node without a label"
            }
            compose
                .onAllNodes(hasClickAction(), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .forEach { node ->
                    val bounds = node.touchBoundsInRoot
                    val w = bounds.width / density
                    val h = bounds.height / density
                    if (w < 1f || h < 1f) return@forEach
                    if (w < 47.5f || h < 47.5f) {
                        val label =
                            node.config
                                .getOrNull(SemanticsProperties.ContentDescription)
                                ?.joinToString()
                                ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
                                ?: "?"
                        problems += "$name: '$label' ${w.toInt()} x ${h.toInt()} dp"
                    }
                }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
