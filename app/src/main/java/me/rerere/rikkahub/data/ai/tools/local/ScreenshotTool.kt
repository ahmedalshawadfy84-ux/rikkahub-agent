package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.iris.IrisCapture
import me.rerere.rikkahub.iris.IrisHand
import me.rerere.rikkahub.iris.IrisVisionSource
import me.rerere.rikkahub.service.ActionLogEntry
import me.rerere.rikkahub.service.RikkaAccessibilityService
import java.io.File
import java.io.FileOutputStream

private const val SCREENSHOT_CACHE_DIR = "screenshots"

private fun pruneOldCacheScreenshots(dir: File) {
    val cutoff = System.currentTimeMillis() - 60L * 60L * 1000L
    dir.listFiles()?.forEach { f ->
        if (f.lastModified() < cutoff) f.delete()
    }
}

fun takeScreenshotTool(context: Context): Tool = Tool(
    name = "take_screenshot",
    description = """
        Capture the current screen and return it as a vision attachment. The active source
        (Settings, Iris: broadcast mirror / accessibility capture / Shizuku / text-only) is
        used with automatic fallback. The attached image is a token-cheap 800px JPEG;
        interactive elements are NUMBERED on it (Set-of-Mark) when the accessibility tree
        exposes them — prefer tapping by the number/box in the som legend. When the screen
        is UNCHANGED since the last screenshot you sent, no new image is attached and you
        get changed_regions events instead — read them, do not re-request. Use screen_zoom
        for full-resolution detail of a region. Secure surfaces error gracefully.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("som", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Number interactive elements on the image (default true)")
                })
                put("force", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Attach the image even when the screen is unchanged (default false)")
                })
            }
        )
    },
    execute = { input ->
        val som = input.jsonObject["som"]?.jsonPrimitive?.booleanOrNull ?: true
        val force = input.jsonObject["force"]?.jsonPrimitive?.booleanOrNull ?: false
        val source = IrisVisionSource.current(context)

        if (source == IrisVisionSource.TEXT_ONLY) {
            val svc = RikkaAccessibilityService.instance
            val payload = if (svc != null) {
                buildJsonObject {
                    put("mode", "text_only")
                    put(
                        "note",
                        "Vision source is set to text-only: no image is captured or sent. " +
                            "Use read_window_tree for the screen's structure.",
                    )
                    put("screen_state", screenStateJson(svc, screenChanged = null))
                }
            } else {
                buildJsonObject { put("error", "service_not_active") }
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val frame = IrisCapture.capture(context)
            ?: return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "screenshot_unavailable")
                        put("reason", "no capture source is currently available")
                    }.toString()
                )
            )

        val events = IrisCapture.consumeEvents(
            frame,
            Json.parseToJsonElement(IrisHand.historyJson()),
        )
        val unchanged = events == null
        val attachImage = !unchanged || force

        val parts = mutableListOf<UIMessagePart>()
        var somLegend: List<String> = emptyList()
        if (attachImage) {
            var jpeg: ByteArray? = null
            if (som) {
                val overlay = IrisCapture.somOverlay(frame)
                if (overlay != null) {
                    jpeg = overlay.first
                    somLegend = overlay.second
                }
            }
            if (jpeg == null) jpeg = IrisCapture.encodeJpeg(frame)
            val cacheDir = File(context.cacheDir, SCREENSHOT_CACHE_DIR).apply { mkdirs() }
            pruneOldCacheScreenshots(cacheDir)
            val cacheFile = File(cacheDir, "iris-${frame.capturedAtMs}.jpg")
            runCatching {
                FileOutputStream(cacheFile).use { it.write(jpeg) }
            }.onSuccess {
                parts.add(UIMessagePart.Image(url = "file://${cacheFile.absolutePath}"))
            }
        }

        RikkaAccessibilityService.instance?.appendLog(
            ActionLogEntry(
                type = "take_screenshot",
                paramsSummary = if (unchanged) {
                    "${frame.source} unchanged"
                } else {
                    "${frame.source} ${frame.width}x${frame.height}"
                },
                success = true,
                timestampMs = System.currentTimeMillis(),
            )
        )

        val payload = buildJsonObject {
            put("success", true)
            put("source", frame.source)
            put("size", buildJsonObject {
                put("w", frame.width)
                put("h", frame.height)
            })
            put("unchanged", unchanged)
            events?.let { put("events", it) }
            if (somLegend.isNotEmpty()) {
                put("som", buildJsonArray { somLegend.forEach { add(it) } })
            }
        }
        parts.add(UIMessagePart.Text(payload.toString()))
        parts
    }
)
