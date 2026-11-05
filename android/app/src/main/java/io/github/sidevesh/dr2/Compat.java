package io.github.sidevesh.dr2;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Will this phone run the game? The launcher answers on its main screen without the
 * player having to ask: the runtime's own --diag runs once per installed APK and GPU
 * driver choice (CHECK THIS DEVICE re-runs it), and its verdict is kept here.
 *
 * Parsed from --diag's own lines (upstream gpu/vk_renderer.cpp):
 *   "... [0] <GPU name> (...) ... <- the renderer would use this one"
 *   "... VERDICT: CANNOT run the renderer — missing REQUIRED: a b c"
 *   "... VERDICT: the renderer CAN run on this machine's pick"
 */
final class Compat {
    private Compat() {}

    private static final String K_STAMP = "compat_stamp", K_OK = "compat_ok",
            K_GPU = "compat_gpu", K_MISSING = "compat_missing", K_DRIVER = "compat_driver";

    static final class Verdict {
        boolean ok;
        String gpu = "", missing = "", driver = "";
    }

    /** What the last check was made for: this APK and this driver choice. */
    static String stamp(Context c) {
        long t = 0;
        try {
            t = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime;
        } catch (Exception ignored) {
        }
        return t + "/" + GameEnv.driverPref(c);
    }

    static boolean due(Context c) {
        return !stamp(c).equals(GameEnv.prefs(c).getString(K_STAMP, ""));
    }

    static Verdict cached(Context c) {
        SharedPreferences p = GameEnv.prefs(c);
        if (!p.contains(K_STAMP)) return null;
        Verdict v = new Verdict();
        v.ok = p.getBoolean(K_OK, false);
        v.gpu = p.getString(K_GPU, "");
        v.missing = p.getString(K_MISSING, "");
        v.driver = p.getString(K_DRIVER, "");
        return v;
    }

    /** Runs --diag (blocking; call on the tool worker) and stores the verdict. */
    static Verdict run(Context c) {
        NativeBridge.runTool(new String[] { "--diag" }, GameEnv.toolLog(c).getAbsolutePath());
        Verdict v = parse(Fs.tail(GameEnv.toolLog(c), 256 * 1024));
        GameEnv.prefs(c).edit().putString(K_STAMP, stamp(c)).putBoolean(K_OK, v.ok)
                .putString(K_GPU, v.gpu).putString(K_MISSING, v.missing)
                .putString(K_DRIVER, v.driver).apply();
        return v;
    }

    static Verdict parse(String log) {
        Verdict v = new Verdict();
        for (String line : log.split("\n")) {
            if (line.contains("<- the renderer would use this one")) {
                int a = line.indexOf("] ");
                int b = line.indexOf(" (", a + 2);
                if (a >= 0 && b > a) v.gpu = line.substring(a + 2, b).trim();
            } else if (line.contains("VERDICT: CANNOT run the renderer")) {
                int m = line.indexOf("missing REQUIRED:");
                v.missing = m >= 0 ? line.substring(m + 17).trim()
                                   : line.substring(line.indexOf("VERDICT:") + 8).trim();
            } else if (line.contains("VERDICT: the renderer CAN run")) {
                v.ok = true;
            } else if (line.contains("[android] Vulkan driver in this process:")) {
                v.driver = line.substring(line.indexOf(':', line.indexOf("process")) + 1).trim();
            }
        }
        if (!v.missing.isEmpty()) v.ok = false;
        return v;
    }

    /** One line for the player: what is wrong, and what (if anything) to do about it. */
    static String advice(Verdict v) {
        String m = v.missing;
        if (m.contains("textureCompressionBC"))
            return "This GPU cannot read the game's compressed (BC/DXT) textures. Mali and "
                    + "PowerVR GPUs (most MediaTek, Google Tensor and older Exynos phones) are not "
                    + "supported yet.";
        if (m.contains("shaderInt64") && GameEnv.adreno())
            return "The phone's own Adreno driver lacks a required feature. Choose a Turnip "
                    + "driver in SYSTEM › GPU DRIVER (Auto does this) and check again.";
        if (m.contains("Vulkan 1.3"))
            return "The GPU driver is older than Vulkan 1.3, which the renderer needs.";
        return "Missing: " + m + ". CHECK THIS DEVICE shows the full report.";
    }
}
