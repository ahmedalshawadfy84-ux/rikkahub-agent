package me.rerere.rikkahub.shizuku;

// AIDL contract for the process Shizuku spawns under the shell UID (bound via
// Shizuku.bindUserService — Shizuku.newProcess is private in dev.rikka.shizuku:api 13.1.5,
// verified with `javap -p` against the published artifact, so this is the supported way to
// run a command with Shizuku's privileges). See ShizukuManager.kt and ShizukuUserService.kt.
interface IShizukuUserService {

    // Runs `command` under a shell, honoring `timeoutMs`, and returns a JSON object string
    // with stdout / stderr / exit_code (or a structured error on timeout / launch failure).
    // See ShizukuCommandRunner.
    String exec(String command, int timeoutMs) = 1;

    // Iris L5/L0: PNG bytes of the current screen via `screencap -p`, run under the shell
    // UID. Raw bytes over binder — the string-based exec() caps stdout at 8 KB, far below a
    // screenshot's size. Null when the command fails or times out.
    @nullable
    byte[] screencap(int timeoutMs) = 2;

    // Iris L5: one touch event injected with the shell UID's INJECT_EVENTS right
    // (InputManager.injectInputEvent, ASYNC mode) — 2-6 ms versus 40-100 ms for
    // accessibility gestures, and games accept it where they drop synthesized
    // accessibility strokes. The app side composes DOWN/MOVE/UP sequences.
    boolean injectTouch(int action, float x, float y, float pressure, long downTime, long eventTime) = 3;

    // Reserved destroy method id required by Shizuku's server contract — it calls this to
    // tear the service down.
    void destroy() = 16777114;
}
