package dev.sadking.folio.ui.stats

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toPath
import androidx.compose.ui.graphics.Path
import androidx.graphics.shapes.Morph
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The milestone badge's morph is drawn by scaling `Morph.toPath` from the unit square to the badge.
 * This pins that assumption: at every step the path spans about the unit square.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class MorphBadgeTest {
    @Test
    fun theMorphStaysInTheUnitSquareFromCircleToShape() {
        val morph = Morph(MaterialShapes.Circle, MaterialShapes.Flower)
        for (progress in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val bounds = morph.toPath(progress, Path()).getBounds()
            assertEquals("left at $progress", 0f, bounds.left, 0.1f)
            assertEquals("top at $progress", 0f, bounds.top, 0.1f)
            assertEquals("right at $progress", 1f, bounds.right, 0.1f)
            assertEquals("bottom at $progress", 1f, bounds.bottom, 0.1f)
        }
    }
}
