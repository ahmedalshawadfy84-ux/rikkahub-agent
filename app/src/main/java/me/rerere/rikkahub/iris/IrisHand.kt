package me.rerere.rikkahub.iris

import android.accessibilityservice.GestureDescription
import android.content.Context
import android.view.MotionEvent
import kotlinx.coroutines.delay
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.service.RikkaAccessibilityService
import me.rerere.rikkahub.shizuku.ShizukuManager

/**
 * Iris L5 — the hand. Taps go through the FAST path first: InputManager event injection
 * under the shell UID via Shizuku (2-6 ms, and games that drop synthesized accessibility
 * strokes accept it), with the accessibility gesture as the honest fallback. Every
 * dispatched touch is recorded into a 500 ms ring the observation side replays, so the
 * planner knows what the hand just did (the doc's L2 "touch history" channel).
 *
 * Multi-touch streams and virtual-stick MOVE sequences are composed by the caller through
 * [injectRaw] — kept public for the skills layer to build on.
 */
object IrisHand {

    data class TouchEvent(val tMs: Long, val type: String, val x: Double, val y: Double)

    data class Result(val success: Boolean, val via: String, val latencyMs: Long)

    private val history = ArrayDeque<TouchEvent>()
    private const val HISTORY_MS = 500L

    fun record(type: String, x: Double, y: Double) {
        synchronized(history) {
            history.addLast(TouchEvent(System.currentTimeMillis(), type, x, y))
            val cutoff = System.currentTimeMillis() - HISTORY_MS
            while (history.isNotEmpty() && history.first().tMs < cutoff) history.removeFirst()
        }
    }

    /** JSON array of the last 500 ms of touch activity (for the observation envelope). */
    fun historyJson(): String = synchronized(history) {
        buildJsonArray {
            history.forEach { e ->
                add(
                    buildJsonObject {
                        put("type", e.type)
                        put("x", e.x)
                        put("y", e.y)
                    }
                )
            }
        }.toString()
    }

    /** One raw injected event (no recording, no fallback) — building block for streams. */
    suspend fun injectRaw(context: Context, action: Int, x: Float, y: Float, pressure: Float, downTime: Long): Boolean =
        ShizukuManager.injectTouch(context, action, x, y, pressure, downTime, System.currentTimeMillis())

    /**
     * A tap (or hold, when [durationMs] is large): injection first, accessibility gesture
     * fallback. Records the touch either way and reports which path served it.
     */
    suspend fun tap(context: Context, x: Float, y: Float, durationMs: Long): Result {
        val t0 = System.currentTimeMillis()
        val injected = runCatching {
            val down = System.currentTimeMillis()
            val okDown = ShizukuManager.injectTouch(
                context, MotionEvent.ACTION_DOWN, x, y, 1f, down, down,
            )
            if (durationMs > 32) delay(durationMs)
            val up = System.currentTimeMillis()
            val okUp = ShizukuManager.injectTouch(
                context, MotionEvent.ACTION_UP, x, y, 0f, down, up,
            )
            okDown && okUp
        }.getOrDefault(false)
        if (injected) {
            record("tap", x.toDouble(), y.toDouble())
            return Result(true, "shizuku", System.currentTimeMillis() - t0)
        }
        val svc = RikkaAccessibilityService.instance
            ?: return Result(false, "none", System.currentTimeMillis() - t0)
        val path = svc.buildTapPath(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(40L, 5_000L))
            )
            .build()
        val ok = svc.dispatchGestureAsync(gesture)
        if (ok) record("tap", x.toDouble(), y.toDouble())
        return Result(ok, "accessibility", System.currentTimeMillis() - t0)
    }
}
