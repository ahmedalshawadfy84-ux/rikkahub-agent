package me.rerere.rikkahub.iris

import android.content.Context

/**
 * Which eyes the screen tools look through (Iris design doc, hybrid principle):
 * the framebuffer is the only surface no app can hide, the accessibility tree is cheaper
 * and exact when present, and text-only is the token-cheapest mode for plain app UIs.
 *
 * Persisted in the app's shared "rikkahub.preferences" store so it survives restarts and
 * applies to every assistant using the screen tools.
 */
enum class IrisVisionSource(val id: String, val label: String) {
    /** Broadcast mirror when active, accessibility capture otherwise. */
    AUTO("auto", "تلقائي — بث الشاشة عند تشغيله، وإلا التقاط إمكانية الوصول"),

    /** The OS accessibility takeScreenshot (~1/sec rate limit, exact tree available). */
    ACCESSIBILITY("accessibility", "التقاط إمكانية الوصول"),

    /** MediaProjection mirror: instant latest frame, no rate limit, needs one consent. */
    PROJECTION("projection", "بث الشاشة (MediaProjection)"),

    /** `screencap` under the shell UID via Shizuku; works when the a11y service can't. */
    SHIZUKU("shizuku", "Shizuku screencap"),

    /** No image is ever captured or sent; the tools return structure and text only. */
    TEXT_ONLY("text_only", "نص فقط — لا تُرسل أي صورة (الأوفر للرصيد)");

    companion object {
        private const val PREFS = "rikkahub.preferences"
        private const val KEY = "iris_vision_source"

        fun current(context: Context): IrisVisionSource {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null)
            return entries.firstOrNull { it.id == raw } ?: AUTO
        }

        fun set(context: Context, source: IrisVisionSource) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, source.id)
                .apply()
        }
    }
}
