package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.iris.IrisCapture
import java.io.File
import java.io.FileOutputStream

private const val ZOOM_CACHE_DIR = "screenshots"

/**
 * Iris fovea (L0 "sharp blink"): crop a region of the CURRENT screen at the display's
 * original resolution — small text becomes readable and tight targets locatable without
 * paying for the whole screen at high resolution. Reuses the last captured frame when it
 * is under 2 seconds old; otherwise captures fresh.
 */
fun screenZoomTool(context: Context): Tool = Tool(
    name = "screen_zoom",
    description = """
        Crop a region of the current screen at FULL resolution and return it as a vision
        attachment — for reading small text or aiming at a precise target. Coordinates are
        normalized fractions of the display: x, y is the region's top-left (0.0-1.0), w, h
        its size (0.01-1.0). scale (1-3) upsamples the crop when pixels are still too
        small. The crop comes from the last frame captured within 2 seconds; take_screenshot
        first if you need a guaranteed-fresh view.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("x", buildJsonObject {
                    put("type", "number")
                    put("description", "Region left edge, 0.0-1.0 of display width")
                })
                put("y", buildJsonObject {
                    put("type", "number")
                    put("description", "Region top edge, 0.0-1.0 of display height")
                })
                put("w", buildJsonObject {
                    put("type", "number")
                    put("description", "Region width, 0.01-1.0 of display width")
                })
                put("h", buildJsonObject {
                    put("type", "number")
                    put("description", "Region height, 0.01-1.0 of display height")
                })
                put("scale", buildJsonObject {
                    put("type", "integer")
                    put("description", "Upsample factor 1-3 (default 1)")
                })
            },
            required = listOf("x", "y", "w", "h")
        )
    },
    execute = { input ->
        fun num(key: String): Double? {
            val v = input.jsonObject[key]?.jsonPrimitive?.doubleOrNull
            return if (v != null && v.isFinite() && v >= 0.0 && v <= 1.0) v else null
        }

        val x = num("x")
        val y = num("y")
        val w = num("w")
        val h = num("h")
        if (x == null || y == null || w == null || h == null || w < 0.01 || h < 0.01) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "x, y, w, h are required fractions in [0,1] (w, h >= 0.01)")
                    }.toString()
                )
            )
        }
        val scale = (input.jsonObject["scale"]?.jsonPrimitive?.doubleOrNull ?: 1.0)
            .coerceIn(1.0, 3.0)

        val frame = IrisCapture.foveaCrop(context, x, y, w, h, scale)
            ?: return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "zoom_unavailable")
                        put("reason", "no frame available — take a screenshot first")
                    }.toString()
                )
            )

        val jpeg = IrisCapture.encodeJpeg(frame, maxSide = 1280, quality = 88)
        val cacheDir = File(context.cacheDir, ZOOM_CACHE_DIR).apply { mkdirs() }
        val cacheFile = File(cacheDir, "iris-zoom-${frame.capturedAtMs}.jpg")
        val written = runCatching {
            FileOutputStream(cacheFile).use { it.write(jpeg) }
        }.isSuccess

        val parts = mutableListOf<UIMessagePart>()
        if (written) parts.add(UIMessagePart.Image(url = "file://${cacheFile.absolutePath}"))
        parts.add(
            UIMessagePart.Text(
                buildJsonObject {
                    put("success", written)
                    put("region", buildJsonObject {
                        put("x", x); put("y", y); put("w", w); put("h", h)
                    })
                    put("crop_pixels", buildJsonObject {
                        put("w", frame.width)
                        put("h", frame.height)
                    })
                    put("scale", scale)
                    if (!written) put("error", "cache_write_failed")
                }.toString()
            )
        )
        frame.bitmap.recycle()
        parts
    }
)
