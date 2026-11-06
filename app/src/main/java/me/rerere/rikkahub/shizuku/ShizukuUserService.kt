package me.rerere.rikkahub.shizuku

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import java.io.ByteArrayOutputStream

/**
 * Runs under the shell UID, in the separate process Shizuku spawns for it (bound via
 * [ShizukuManager.exec] / `Shizuku.bindUserService`). `Shizuku.newProcess` is private in
 * dev.rikka.shizuku:api 13.1.5 (verified with `javap -p` against the published artifact), so
 * this AIDL user service is the supported way to run a command with Shizuku's privileges.
 *
 * The no-arg constructor is required by Shizuku's server: it instantiates this class by
 * reflection in the spawned process. Actual process launch + bounded capture + timeout
 * enforcement lives in the Android-independent [ShizukuCommandRunner] so that logic can be
 * unit-tested on the host JVM without Shizuku or a device.
 *
 * Iris additions: [screencap] (raw PNG bytes of the screen) and [injectTouch] (event
 * injection through the shell UID's INJECT_EVENTS right). Hidden-API access
 * (InputManager.injectInputEvent) is safe here: this code runs in Shizuku's server
 * process under the shell UID, where hidden-API restrictions do not apply.
 */
class ShizukuUserService : IShizukuUserService.Stub() {

    init {
        Log.i(TAG, "constructor")
    }

    override fun exec(command: String?, timeoutMs: Int): String {
        val result = ShizukuCommandRunner.run(
            command = command.orEmpty(),
            timeoutMs = if (timeoutMs > 0) timeoutMs else DEFAULT_TIMEOUT_MS,
            maxStdoutBytes = MAX_STDOUT_BYTES,
            maxStderrBytes = MAX_STDERR_BYTES,
        )
        return result.toString()
    }

    override fun screencap(timeoutMs: Int): ByteArray? {
        val deadline = System.currentTimeMillis() + (if (timeoutMs > 0) timeoutMs else SCREENCAP_TIMEOUT_MS)
        return runCatching {
            val proc = ProcessBuilder("screencap", "-p").start()
            try {
                val out = ByteArrayOutputStream(512 * 1024)
                val buf = ByteArray(64 * 1024)
                val input = proc.inputStream
                while (true) {
                    if (System.currentTimeMillis() >= deadline) {
                        proc.destroy()
                        return null
                    }
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                proc.waitFor()
                if (proc.exitValue() != 0) null else out.toByteArray()
            } finally {
                runCatching { proc.destroy() }
            }
        }.onFailure { Log.w(TAG, "screencap failed: ${it.message}") }.getOrNull()
    }

    override fun injectTouch(
        action: Int,
        x: Float,
        y: Float,
        pressure: Float,
        downTime: Long,
        eventTime: Long,
    ): Boolean = injectMotionEvent(action, x, y, pressure, downTime, eventTime)

    /** Reserved method Shizuku's server calls to tear this service down. */
    override fun destroy() {
        Log.i(TAG, "destroy")
        System.exit(0)
    }

    companion object {
        private const val TAG = "ShizukuUserService"
        private const val DEFAULT_TIMEOUT_MS = 30_000
        private const val SCREENCAP_TIMEOUT_MS = 10_000
        private const val MAX_STDOUT_BYTES = 8_000
        private const val MAX_STDERR_BYTES = 2_000

        /** InputManager.INJECT_INPUT_EVENT_MODE_ASYNC — never the WAIT_FOR_FINISH mode,
         *  which adds delivery latency to every event. */
        private const val INJECT_MODE_ASYNC = 2
    }
}

/**
 * Builds and injects one touch event via the hidden InputManager API. Pure-ish and
 * reflection-based so it compiles against the public SDK; runs only in the Shizuku-spawned
 * shell-UID process. Returns false on any failure (no throw across binder).
 */
internal fun injectMotionEvent(
    action: Int,
    x: Float,
    y: Float,
    pressure: Float,
    downTime: Long,
    eventTime: Long,
): Boolean = runCatching {
    val now = SystemClock.uptimeMillis()
    @Suppress("ClickableViewAccessibility")
    val ev = MotionEvent.obtain(
        downTime,
        if (eventTime > 0) eventTime else now,
        action,
        x, y,
        pressure,
        1f,          // size
        0,           // metaState
        1f, 1f,      // x/y precision
        0,           // deviceId
        0,           // edgeFlags
        InputDevice.SOURCE_TOUCHSCREEN,
        0,           // flags
    )
    try {
        val imClass = Class.forName("android.hardware.input.InputManager")
        val im = imClass.getMethod("getInstance").invoke(null)
        val inject = imClass.getMethod(
            "injectInputEvent",
            MotionEvent::class.java,
            Int::class.javaPrimitiveType,
        )
        inject.invoke(im, ev, 2) as Boolean
    } finally {
        ev.recycle()
    }
}.getOrDefault(false)
