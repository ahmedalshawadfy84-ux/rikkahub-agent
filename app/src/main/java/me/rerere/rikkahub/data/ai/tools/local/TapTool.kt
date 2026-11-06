package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.AgentTurnTracker
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.iris.IrisHand
import me.rerere.rikkahub.service.ActionLogEntry

private const val DEFAULT_LONG_PRESS_MS = 600L

private fun coordOrError(jsonObj: kotlinx.serialization.json.JsonElement, key: String): Double? {
    val v = jsonObj.jsonObject[key]?.jsonPrimitive?.doubleOrNull
    return if (v != null && v.isFinite() && v >= 0.0) v else null
}

fun tapTool(
    context: android.content.Context,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "tap",
    description = """
        Tap at absolute screen coordinates (pixels). Taps go through the Iris fast hand:
        shell-UID event injection via Shizuku when available (2-6ms, and games that ignore
        accessibility strokes accept it), with the accessibility gesture as fallback — the
        result's "via" field says which. The result carries an "after" object: foreground
        package/window, shade_open, ime_visible, display size, and screen_changed telling
        you whether the tap actually changed the UI. Read it before deciding your next
        action instead of taking a screenshot. Coordinates outside the display are rejected
        with the display size. Prefer click_node with a node_id from read_window_tree over
        raw coordinates when a node exists.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("x", buildJsonObject {
                    put("type", "number")
                    put("description", "Absolute x in pixels of the active display")
                })
                put("y", buildJsonObject {
                    put("type", "number")
                    put("description", "Absolute y in pixels of the active display")
                })
            },
            required = listOf("x", "y")
        )
    },
    execute = { input ->
        AgentTurnTracker.recordAutomationAction()
        // Wake screen so gestures land on a visible surface, not a dark screen.
        val wakeOk = me.rerere.rikkahub.service.RikkaAccessibilityService.instance
            ?.let { wakeScreenIfNeeded(it) } ?: true
        val x = coordOrError(input, "x")
        val y = coordOrError(input, "y")
        if (x == null || y == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "x and y are required and must be non-negative numbers")
                    }.toString()
                )
            )
        }
        val payload = AccessibilityServiceHandle.withService { svc ->
            withActionEnvelope(svc) { _ ->
                val dm = svc.resources.displayMetrics
                if (coordsOutOfBounds(x, y, dm.widthPixels, dm.heightPixels)) {
                    return@withActionEnvelope buildJsonObject {
                        put("error", "out_of_bounds")
                        put("display", buildJsonObject {
                            put("w", dm.widthPixels)
                            put("h", dm.heightPixels)
                        })
                    }
                }
                val hand = IrisHand.tap(context, x.toFloat(), y.toFloat(), 50L)
                svc.appendLog(
                    ActionLogEntry(
                        type = "tap",
                        paramsSummary = "(${x.toInt()}, ${y.toInt()}) via=${hand.via}",
                        success = hand.success,
                        timestampMs = System.currentTimeMillis(),
                    )
                )
                buildJsonObject {
                    put("success", hand.success)
                    put("via", hand.via)
                    put("inject_ms", hand.latencyMs)
                    if (!hand.success) put("reason", "injection_failed")
                    if (!wakeOk) put("wake_failed", true)
                }
            }
        }
        streamer.streamIfHeadless(invocationContext, "Tap (${x.toInt()}, ${y.toInt()})")
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

fun longPressTool(
    context: android.content.Context,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "long_press",
    description = """
        Long-press at absolute screen coordinates (pixels). Default duration 600ms; duration_ms
        (100-5000) overrides. Goes through the same Iris fast hand as tap (Shizuku injection
        first, accessibility gesture fallback; "via" reports which). Same return shape as tap
        including the "after" state object; check after.screen_changed to verify the press
        had an effect.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("x", buildJsonObject {
                    put("type", "number")
                    put("description", "Absolute x in pixels")
                })
                put("y", buildJsonObject {
                    put("type", "number")
                    put("description", "Absolute y in pixels")
                })
                put("duration_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Hold duration in milliseconds (default 600, range 100-5000)")
                })
            },
            required = listOf("x", "y")
        )
    },
    execute = { input ->
        AgentTurnTracker.recordAutomationAction()
        val wakeOk = me.rerere.rikkahub.service.RikkaAccessibilityService.instance
            ?.let { wakeScreenIfNeeded(it) } ?: true
        val x = coordOrError(input, "x")
        val y = coordOrError(input, "y")
        if (x == null || y == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "x and y are required and must be non-negative numbers")
                    }.toString()
                )
            )
        }
        val durationRaw = input.jsonObject["duration_ms"]?.jsonPrimitive?.longOrNull
            ?: DEFAULT_LONG_PRESS_MS
        if (durationRaw < 100L || durationRaw > 5000L) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "duration_ms must be between 100 and 5000")
                    }.toString()
                )
            )
        }
        val payload = AccessibilityServiceHandle.withService { svc ->
            withActionEnvelope(svc) { _ ->
                val dm = svc.resources.displayMetrics
                if (coordsOutOfBounds(x, y, dm.widthPixels, dm.heightPixels)) {
                    return@withActionEnvelope buildJsonObject {
                        put("error", "out_of_bounds")
                        put("display", buildJsonObject {
                            put("w", dm.widthPixels)
                            put("h", dm.heightPixels)
                        })
                    }
                }
                val hand = IrisHand.tap(context, x.toFloat(), y.toFloat(), durationRaw)
                svc.appendLog(
                    ActionLogEntry(
                        type = "long_press",
                        paramsSummary = "(${x.toInt()}, ${y.toInt()}) ${durationRaw}ms via=${hand.via}",
                        success = hand.success,
                        timestampMs = System.currentTimeMillis(),
                    )
                )
                buildJsonObject {
                    put("success", hand.success)
                    put("via", hand.via)
                    put("inject_ms", hand.latencyMs)
                    if (!hand.success) put("reason", "injection_failed")
                    if (!wakeOk) put("wake_failed", true)
                }
            }
        }
        streamer.streamIfHeadless(invocationContext, "LongPress (${x.toInt()}, ${y.toInt()}) ${durationRaw}ms")
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
