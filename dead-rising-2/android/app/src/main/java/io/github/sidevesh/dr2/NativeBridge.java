package io.github.sidevesh.dr2;

/**
 * The JNI entry points libmain.so exports (dead-rising-2/native/android_main.cpp).
 */
public final class NativeBridge {
    private NativeBridge() {}

    private static boolean sLoaded;

    /** Loads the runtime into the CURRENT process (the launcher's, for the tools). */
    public static synchronized void load() {
        if (sLoaded) return;
        System.loadLibrary("c++_shared");
        System.loadLibrary("SDL2");
        System.loadLibrary("main");
        sLoaded = true;
    }

    /**
     * Runs one of the upstream runtime's command-line modes in this process —
     * "--extract-package", "--gen-overlays", "--shader-cache-status", "--diag" — with
     * its output captured to {@code logPath}. Returns the mode's exit code. Blocking:
     * call it off the UI thread, one at a time.
     */
    public static native int runTool(String[] args, String logPath);

    /** The game's window surface is being destroyed; returns once the renderer let go. */
    public static native void surfaceLost();

    /** A new window surface exists; the renderer may build a swapchain on it. */
    public static native void surfaceRestored();
}
