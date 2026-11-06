package me.rerere.rikkahub.iris

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.service.RikkaAccessibilityService
import me.rerere.rikkahub.shizuku.ShizukuManager
import java.io.ByteArrayOutputStream

/**
 * Iris capture core: one place that turns "the current screen" into a [Frame], whatever
 * the active source is, plus the token-economy pieces around it:
 *
 *  - a coarse luminance grid per frame (the "retina") used for change detection;
 *  - unchanged screens are NOT re-sent — the caller attaches events instead (the doc's
 *    "events, not frames" principle);
 *  - a 2-second full-resolution fovea cache so screen_zoom can crop detail without a
 *    new capture;
 *  - Set-of-Mark overlays: numbered badges over interactive nodes, so the model picks a
 *    number instead of guessing coordinates.
 *
 * The grid/diff/label math is pure and JVM-testable (see IrisCaptureTest); everything
 * touching Bitmap/Context lives in the suspend paths.
 */
object IrisCapture {

    const val GRID_COLS = 12
    const val GRID_ROWS = 20
    const val TILE_CHANGED_THRESHOLD = 12
    const val ATTACH_MAX_SIDE = 800
    const val ATTACH_JPEG_QUALITY = 85

    data class Frame(
        val bitmap: Bitmap,
        val grid: IntArray,
        val capturedAtMs: Long,
        val source: String,
    ) {
        val width: Int get() = bitmap.width
        val height: Int get() = bitmap.height
    }

    // ── pure, JVM-testable core ─────────────────────────────────────────────────────────

    /** Average luminance (0-255) of each grid cell over [pixels] (ARGB). */
    fun luminanceGrid(
        w: Int,
        h: Int,
        pixels: IntArray,
        cols: Int = GRID_COLS,
        rows: Int = GRID_ROWS,
    ): IntArray {
        val out = IntArray(cols * rows)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                var sum = 0L
                var count = 0
                val x0 = c * w / cols
                val x1 = ((c + 1) * w / cols).coerceAtLeast(x0 + 1).coerceAtMost(w)
                val y0 = r * h / rows
                val y1 = ((r + 1) * h / rows).coerceAtLeast(y0 + 1).coerceAtMost(h)
                var y = y0
                while (y < y1) {
                    var x = x0
                    while (x < x1) {
                        val p = pixels[y * w + x]
                        sum += ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                        count++
                        x++
                    }
                    y++
                }
                out[r * cols + c] = if (count > 0) (sum / count).toInt() else 0
            }
        }
        return out
    }

    /** Indices of grid cells whose luminance moved by more than [threshold]. */
    fun changedTiles(before: IntArray?, after: IntArray, threshold: Int = TILE_CHANGED_THRESHOLD): List<Int> {
        if (before == null || before.size != after.size) return emptyList()
        return (0 until after.size).filter { kotlin.math.abs(after[it] - before[it]) > threshold }
    }

    /** "top-left" style label for a grid cell — cheap, position-honest change events. */
    fun regionLabel(tileIndex: Int, cols: Int = GRID_COLS, rows: Int = GRID_ROWS): String {
        val col = tileIndex % cols
        val row = tileIndex / cols
        val v = when { row < rows / 3 -> "top"; row < 2 * rows / 3 -> "middle"; else -> "bottom" }
        val h = when { col < cols / 3 -> "left"; col < 2 * cols / 3 -> "center"; else -> "right" }
        return "$v-$h"
    }

    fun changedRatio(changed: Int, cols: Int = GRID_COLS, rows: Int = GRID_ROWS): Double =
        changed.toDouble() / (cols * rows)

    // ── state ───────────────────────────────────────────────────────────────────────────

    @Volatile
    private var lastFrame: Frame? = null

    @Volatile
    private var lastSentGrid: IntArray? = null

    /** The most recent frame if it is younger than [maxAgeMs] — the fovea cache. */
    fun cachedFrame(maxAgeMs: Long = 2_000): Frame? {
        val f = lastFrame ?: return null
        return if (System.currentTimeMillis() - f.capturedAtMs <= maxAgeMs) f else null
    }

    /**
     * Captures the current screen through the active source (with honest fallbacks).
     * Returns null when nothing is available. The full-resolution frame is kept as the
     * fovea cache; encode attachments with [encodeJpeg].
     */
    suspend fun capture(context: Context): Frame? {
        val frame = when (IrisVisionSource.current(context)) {
            IrisVisionSource.TEXT_ONLY -> null
            IrisVisionSource.PROJECTION -> projectionFrame() ?: accessibilityFrame()
            IrisVisionSource.SHIZUKU -> shizukuFrame(context) ?: accessibilityFrame()
            IrisVisionSource.ACCESSIBILITY -> accessibilityFrame()
            IrisVisionSource.AUTO ->
                projectionFrame() ?: accessibilityFrame() ?: shizukuFrame(context)
        } ?: return null
        val old = lastFrame
        lastFrame = frame
        old?.bitmap?.recycle()
        return frame
    }

    private suspend fun accessibilityFrame(): Frame? {
        val svc = RikkaAccessibilityService.instance ?: return null
        return when (val res = svc.captureScreenshot(0)) {
            is RikkaAccessibilityService.ScreenshotOutcome.Success ->
                frameOf(res.bitmap, "accessibility")
            else -> null
        }
    }

    private fun projectionFrame(): Frame? =
        IrisProjectionService.acquireFrame()?.let { frameOf(it, "projection") }

    private suspend fun shizukuFrame(context: Context): Frame? {
        val png = ShizukuManager.screencap(context) ?: return null
        if (png.isEmpty()) return null
        val bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return null
        return frameOf(bmp, "shizuku")
    }

    private fun frameOf(bitmap: Bitmap, source: String): Frame {
        val small = Bitmap.createScaledBitmap(bitmap, GRID_COLS * 8, GRID_ROWS * 8, true)
        val w = small.width
        val h = small.height
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        val grid = luminanceGrid(w, h, pixels)
        small.recycle()
        return Frame(bitmap, grid, System.currentTimeMillis(), source)
    }

    // ── events ──────────────────────────────────────────────────────────────────────────

    /**
     * Marks [frame]'s grid as the last SENT one and returns a change-events JSON object
     * carrying [touchHistory]. Returns null when the screen is unchanged since the last
     * sent frame — the caller then attaches no image (nothing new to see or pay for).
     */
    fun consumeEvents(frame: Frame, touchHistory: JsonElement): JsonObject? {
        val before = lastSentGrid
        val changed = changedTiles(before, frame.grid)
        if (before != null && changed.isEmpty()) return null
        lastSentGrid = frame.grid.copyOf()
        val regions = changed.map { regionLabel(it) }.distinct()
        return buildJsonObject {
            put("changed_regions", buildJsonArray { regions.forEach { add(it) } })
            put("changed_ratio", changedRatio(changed.size))
            put("touch_last_500ms", touchHistory)
        }
    }

    // ── encoders ────────────────────────────────────────────────────────────────────────

    /** Token-cheap attachment: longest side [maxSide] JPEG at [quality] (PalmClaw recipe). */
    fun encodeJpeg(frame: Frame, maxSide: Int = ATTACH_MAX_SIDE, quality: Int = ATTACH_JPEG_QUALITY): ByteArray {
        val longest = maxOf(frame.width, frame.height).coerceAtLeast(1)
        val scale = if (longest > maxSide) maxSide.toFloat() / longest else 1f
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                frame.bitmap,
                (frame.width * scale).toInt().coerceAtLeast(1),
                (frame.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else frame.bitmap
        val bytes = ByteArrayOutputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it); it.toByteArray() }
        if (bmp !== frame.bitmap) bmp.recycle()
        return bytes
    }

    /**
     * Set-of-Mark overlay: numbered badges over the interactive nodes the accessibility
     * tree exposes, drawn on a scaled copy. Returns the encoded JPEG plus the legend
     * ("n: [x,y,WxH] label" in ORIGINAL screen pixels). Null when the tree exposes
     * nothing (games/canvases) — the caller then sends the plain frame, honestly.
     */
    fun somOverlay(
        frame: Frame,
        maxSide: Int = ATTACH_MAX_SIDE,
        quality: Int = ATTACH_JPEG_QUALITY,
    ): Pair<ByteArray, List<String>>? {
        val svc = RikkaAccessibilityService.instance ?: return null
        val root = runCatching { svc.rootInActiveWindow }.getOrNull() ?: return null
        data class Mark(val rect: Rect, val label: String)
        val marks = mutableListOf<Mark>()
        runCatching {
            svc.traverseTree(
                root,
                filter = { n, _ ->
                    n.isClickable || n.isEditable || n.isScrollable ||
                        !n.text.isNullOrBlank() || !n.contentDescription.isNullOrBlank()
                },
                cap = 48,
                emit = { n, _, _ ->
                    val r = Rect()
                    runCatching { n.getBoundsInScreen(r) }
                    if (!r.isEmpty && r.width() > 8 && r.height() > 8) {
                        val label = listOfNotNull(
                            n.text?.toString(),
                            n.contentDescription?.toString(),
                        ).firstOrNull { it.isNotBlank() }
                            ?.take(28)
                            ?.replace('\n', ' ')
                            .orEmpty()
                        marks.add(Mark(r, label))
                    }
                },
            )
        }
        if (marks.isEmpty()) return null

        val longest = maxOf(frame.width, frame.height).coerceAtLeast(1)
        val scale = if (longest > maxSide) maxSide.toFloat() / longest else 1f
        val w = (frame.width * scale).toInt().coerceAtLeast(1)
        val h = (frame.height * scale).toInt().coerceAtLeast(1)
        val base = Bitmap.createScaledBitmap(frame.bitmap, w, h, true)
        val canvas = Canvas(base)
        val boxPaint = Paint().apply {
            style = Paint.Style.STROKE
            color = Color.argb(220, 80, 200, 255)
            strokeWidth = 2f
            isAntiAlias = true
        }
        val badgePaint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.argb(230, 80, 200, 255)
            isAntiAlias = true
        }
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 14f
            isAntiAlias = true
        }
        val legend = mutableListOf<String>()
        marks.forEachIndexed { i, m ->
            val l = (m.rect.left * scale).toInt()
            val t = (m.rect.top * scale).toInt()
            val r = (m.rect.right * scale).toInt()
            val b = (m.rect.bottom * scale).toInt()
            canvas.drawRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat(), boxPaint)
            canvas.drawCircle((l + 9).toFloat(), (t + 9).toFloat(), 9f, badgePaint)
            canvas.drawText("${i + 1}", (l + 5).toFloat(), (t + 14).toFloat(), textPaint)
            legend.add(
                "${i + 1}: [${m.rect.left},${m.rect.top},${m.rect.width()}x${m.rect.height()}] ${m.label}".trim()
            )
        }
        val bytes = ByteArrayOutputStream().use { base.compress(Bitmap.CompressFormat.JPEG, quality, it); it.toByteArray() }
        base.recycle()
        return bytes to legend
    }

    /**
     * Fovea: crops a normalized region out of the cached (or a fresh) full-resolution
     * frame and optionally upsamples it. The doc's "sharp blink" — detail where it
     * matters, without paying for the whole screen at high resolution.
     */
    suspend fun foveaCrop(
        context: Context,
        rx: Double,
        ry: Double,
        rw: Double,
        rh: Double,
        scale: Double,
    ): Frame? {
        val frame = cachedFrame() ?: capture(context) ?: return null
        val x = (rx * frame.width).toInt().coerceIn(0, frame.width - 1)
        val y = (ry * frame.height).toInt().coerceIn(0, frame.height - 1)
        val w = (rw * frame.width).toInt().coerceIn(1, frame.width - x)
        val h = (rh * frame.height).toInt().coerceIn(1, frame.height - y)
        val crop = Bitmap.createBitmap(frame.bitmap, x, y, w, h)
        val scaled = if (scale > 1.01) {
            val sw = (w * scale).toInt().coerceIn(1, 1600)
            val sh = (h * scale).toInt().coerceIn(1, 1600)
            Bitmap.createScaledBitmap(crop, sw, sh, true).also { if (it !== crop) crop.recycle() }
        } else crop
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        val grid = luminanceGrid(scaled.width, scaled.height, pixels)
        return Frame(scaled, grid, System.currentTimeMillis(), "fovea")
    }
}
