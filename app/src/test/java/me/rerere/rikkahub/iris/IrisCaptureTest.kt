package me.rerere.rikkahub.iris

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-core tests for the Iris capture math (no Bitmap/Context — the JVM-testable half,
 * mirroring the ScreenState test style).
 */
class IrisCaptureTest {

    @Test fun `grid averages luminance per cell`() {
        // 2x2 black/white image over a 1x1 grid → 50% luminance.
        val pixels = intArrayOf(
            0xFF000000.toInt(), 0xFFFFFFFF.toInt(),
            0xFF000000.toInt(), 0xFFFFFFFF.toInt(),
        )
        val grid = IrisCapture.luminanceGrid(2, 2, pixels, cols = 1, rows = 1)
        assertEquals(1, grid.size)
        assertEquals(128, grid[0])
    }

    @Test fun `changed tiles respect the threshold`() {
        val before = IntArray(4) { 100 }
        val after = IntArray(4) { 100, 105, 120, 100 }
        // Default threshold 12: only index 2 moved enough.
        assertEquals(listOf(2), IrisCapture.changedTiles(before, after))
        // A null/absent before is "everything might have changed" — no tiles reported
        // because the caller treats the first frame as new, not as a diff.
        assertTrue(IrisCapture.changedTiles(null, after).isEmpty())
        // Size mismatch (rotation etc.) — no honest diff possible.
        assertTrue(IrisCapture.changedTiles(IntArray(3), after).isEmpty())
    }

    @Test fun `region labels map to thirds`() {
        assertEquals("top-left", IrisCapture.regionLabel(0))
        // cols=12: index 11 is the last of the top third (rows=20 → top is rows 0-5).
        assertEquals("top-right", IrisCapture.regionLabel(5 * 12 + 11))
        assertEquals("bottom-center", IrisCapture.regionLabel(19 * 12 + 6))
    }

    @Test fun `changed ratio is a fraction of the grid`() {
        assertEquals(0.25, IrisCapture.changedRatio(1, cols = 2, rows = 2), 1e-9)
        assertEquals(1.0, IrisCapture.changedRatio(48), 1e-9)
    }
}
