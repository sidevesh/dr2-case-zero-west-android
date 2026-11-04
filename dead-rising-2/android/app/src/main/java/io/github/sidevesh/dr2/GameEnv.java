package io.github.sidevesh.dr2;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Where everything lives on the device, and the environment the native runtime reads
 * it from. Both processes (the launcher's and the game's) call {@link #apply} before
 * loading libmain.so, so the runtime resolves the same paths in each.
 *
 * <pre>
 * &lt;external files&gt;/            the runtime's data root (CZ_ROOT / CW_ROOT)
 *     assets/package/           where an imported XBLA package is copied, if needed
 *     assets/game/              the unpacked game (default.xex, data/...)
 *     assets/game_patched/ ...  the overlays the runtime generates from it
 *     assets/shader_spv/        the translated shaders (made on a PC)
 *     saves/                    XDG_DATA_HOME: saves and settings
 *     cz_runtime.log            every game run's log (cw_runtime.log for Case West)
 * &lt;internal files&gt;/shipped/    what a desktop release ships beside its executable
 * </pre>
 *
 * External app storage is used for the data so a player can reach it over USB
 * (Android/data/&lt;package&gt;/files) — to back up saves, or to copy a package in.
 */
public final class GameEnv {
    private static final String TAG = "DR2";
    public static final String PREFS = "dr2";
    public static final String PREF_TOUCH = "touch_controls";
    public static final String PREF_EXTRA_ENV = "extra_env";
    public static final String PREF_DRIVER = "gpu_driver";   // auto | r6 | mesa | system

    private GameEnv() {}

    public static File root(Context c) {
        File f = c.getExternalFilesDir(null);
        return f != null ? f : c.getFilesDir();
    }
    public static File assets(Context c)      { return new File(root(c), "assets"); }
    public static File packageDir(Context c)  { return new File(assets(c), "package"); }
    public static File game(Context c)        { return new File(assets(c), "game"); }
    public static File shaderCache(Context c) { return new File(assets(c), "shader_spv"); }
    public static File saves(Context c)       { return new File(root(c), "saves"); }
    public static File shipped(Context c)     { return new File(c.getFilesDir(), "shipped"); }
    public static File runtimeLog(Context c)  { return new File(root(c), BuildConfig.RUNTIME_LOG); }
    public static File toolLog(Context c)     { return new File(root(c), "launcher_tool.log"); }

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Sets the runtime's environment for this process. Call before NativeBridge.load(). */
    public static void apply(Context c) {
        String p = BuildConfig.ENV_PREFIX;
        assets(c).mkdirs();
        saves(c).mkdirs();
        installShipped(c);
        set(p + "_ROOT", root(c).getAbsolutePath());
        set("DR2_SHIPPED_DIR", shipped(c).getAbsolutePath());
        set("HOME", c.getFilesDir().getAbsolutePath());
        set("XDG_DATA_HOME", saves(c).getAbsolutePath());
        set("XDG_CACHE_HOME", c.getCacheDir().getAbsolutePath());
        set("TMPDIR", c.getCacheDir().getAbsolutePath());
        // The Vulkan driver (native/android_vulkan.cpp): a bundled Turnip build, or the
        // device's own. The libadrenotools hooks stay in nativeLibraryDir; the chosen
        // Turnip is copied into private storage first (prepareTurnip).
        set("DR2_NATIVE_LIB_DIR", c.getApplicationInfo().nativeLibraryDir);
        String drv = driver(c);
        File turnipDir = drv.equals("system") ? null : prepareTurnip(c, drv);
        if (turnipDir != null) {
            set("DR2_VK_DRIVER", "turnip");
            set("DR2_VK_DRIVER_DIR", turnipDir.getAbsolutePath());
            set("DR2_VK_DRIVER_FILE", "libvulkan_freedreno.so");
            set("DR2_VK_DRIVER_LABEL", label(drv));
            // Turnip's direct (sysmem) rendering rather than tiled GMEM: LibertyRecomp
            // measured GMEM ~15% slower on a renderer with many small passes and
            // resolves, which this one is too. TU_DEBUG= in ADVANCED restores GMEM.
            set("TU_DEBUG", "sysmem");
        } else {
            set("DR2_VK_DRIVER", "system");
        }
        // The desktop launcher is SDL-drawn and replaced on Android by LauncherActivity.
        set(p + "_LAUNCHER", "0");
        // SDL hints read from the environment. Touches must not turn into mouse events:
        // the runtime would switch its prompts and camera to keyboard/mouse.
        set("SDL_TOUCH_MOUSE_EVENTS", "0");
        set("SDL_ANDROID_TRAP_BACK_BUTTON", "1");
        // SDL would otherwise poll the accelerometer as a fake joystick: battery, no use.
        set("SDL_ACCELEROMETER_AS_JOYSTICK", "0");
        // The first-run internal resolution: 720 lines at the screen's shape. Only a
        // default — the runtime ignores it once a settings file exists (patched
        // host/settings.cpp), so the in-game RESOLUTION row keeps working.
        set("DR2_DEFAULT_RES", defaultResolution(c));
        // The player's own KEY=VALUE lines (the launcher's ADVANCED box) win over all of it.
        for (String line : prefs(c).getString(PREF_EXTRA_ENV, "").split("\n")) {
            line = line.trim();
            int eq = line.indexOf('=');
            if (line.isEmpty() || line.startsWith("#") || eq <= 0) continue;
            set(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
    }

    /**
     * 720 lines at the display's landscape aspect, within the runtime's rule for an
     * internal resolution (Settings_ValidInternalRes: even width, at least 1280x720,
     * at least 16:10). A 20:9 phone gets 1600x720; a 4:3 tablet 1280x800.
     */
    static String defaultResolution(Context c) {
        android.graphics.Rect b = c.getSystemService(android.view.WindowManager.class)
                .getMaximumWindowMetrics().getBounds();
        int lw = Math.max(b.width(), b.height()), lh = Math.min(b.width(), b.height());
        if (lh <= 0) return "1280x720";
        double aspect = (double) lw / lh;
        if (aspect < 1.6) return "1280x800";
        int w = (int) Math.round(720 * aspect / 2.0) * 2;
        return Math.max(1280, Math.min(w, 6880)) + "x720";
    }

    /**
     * An Adreno GPU: a Qualcomm SoC, or the KGSL device node Turnip drives. Anything
     * else (Mali, Xclipse, ...) gets its own driver; Turnip cannot run there.
     */
    static boolean adreno() {
        String m = android.os.Build.SOC_MANUFACTURER;
        boolean qcom = m != null && (m.equalsIgnoreCase("QTI") || m.toLowerCase().contains("qualcomm"));
        return qcom || new File("/dev/kgsl-3d0").exists() || new File("/sys/class/kgsl").exists();
    }

    static String driverPref(Context c) {
        String p = prefs(c).getString(PREF_DRIVER, "auto");
        return p.equals("turnip") ? "r6" : p;   // the setting's name before R6 was bundled
    }

    /** The build to load: "r6", "mesa" or "system". "auto" is R6 on Adreno. */
    static String driver(Context c) {
        String p = driverPref(c);
        if (p.equals("auto")) return adreno() ? "r6" : "system";
        return p;
    }

    static String label(String build) {
        switch (build) {
            case "r6": return "StevenMXZ Turnip 26.3.0-R6";
            case "mesa": return "Mesa Turnip 26.2.6";
            default: return "system driver";
        }
    }

    /**
     * Copies nativeLibraryDir/libturnip_<build>.so to files/drivers/<build>/
     * libvulkan_freedreno.so when it is missing or from another APK, and returns
     * that directory; null (= use the system driver) if the copy fails.
     */
    static File prepareTurnip(Context c, String build) {
        File src = new File(c.getApplicationInfo().nativeLibraryDir, "libturnip_" + build + ".so");
        File dir = new File(new File(c.getFilesDir(), "drivers"), build);
        File dst = new File(dir, "libvulkan_freedreno.so");
        File stamp = new File(dir, ".stamp");
        String want = apkStamp(c) + "/" + src.length();
        if (dst.isFile() && dst.length() == src.length() && want.equals(Fs.readSmall(stamp))) return dir;
        try {
            Fs.deleteTree(dir);
            if (!dir.mkdirs()) throw new IOException("cannot create " + dir);
            try (InputStream in = new java.io.FileInputStream(src);
                 OutputStream out = new FileOutputStream(dst)) {
                Fs.copy(in, out, null);
            }
            Fs.writeSmall(stamp, want);
            return dir;
        } catch (IOException e) {
            Log.e(TAG, "could not prepare Turnip (" + build + "), using the system driver", e);
            return null;
        }
    }

    private static void set(String k, String v) {
        try {
            Os.setenv(k, v, true);
        } catch (ErrnoException e) {
            Log.e(TAG, "setenv " + k + " failed", e);
        }
    }

    /**
     * Copies the APK's assets/shipped tree (cz_defaults.env, vs_recipes.bin,
     * prewarm.keys, kbm_chips/) to internal storage, once per app version. The runtime
     * reads them as plain files (HostPaths::ShippedDir).
     */
    private static void installShipped(Context c) {
        File dst = shipped(c);
        File stamp = new File(dst, ".version");
        String want = BuildConfig.VERSION_NAME + "/" + BuildConfig.VERSION_CODE + "/" + apkStamp(c);
        if (stamp.isFile() && want.equals(Fs.readSmall(stamp))) return;
        Fs.deleteTree(dst);
        dst.mkdirs();
        try {
            copyAssetTree(c.getAssets(), "shipped", dst);
            Fs.writeSmall(stamp, want);
        } catch (IOException e) {
            Log.e(TAG, "could not install the shipped files", e);
        }
    }

    private static String apkStamp(Context c) {
        try {
            return Long.toString(c.getPackageManager()
                    .getPackageInfo(c.getPackageName(), 0).lastUpdateTime);
        } catch (Exception e) {
            return "0";
        }
    }

    private static void copyAssetTree(AssetManager am, String path, File dst) throws IOException {
        String[] kids = am.list(path);
        if (kids == null || kids.length == 0) {
            try (InputStream in = am.open(path); OutputStream out = new FileOutputStream(dst)) {
                Fs.copy(in, out, null);
            }
            return;
        }
        dst.mkdirs();
        for (String k : kids) copyAssetTree(am, path + "/" + k, new File(dst, k));
    }

    // ---- readiness, mirroring the runtime's own FirstRun::Check ----------------------

    public static boolean gameUnpacked(Context c) {
        return new File(game(c), "default.xex").isFile();
    }

    public static int shaderModules(Context c) {
        File[] fs = shaderCache(c).listFiles((d, n) -> n.endsWith(".spv"));
        return fs == null ? 0 : fs.length;
    }

    public static boolean overlaysGenerated(Context c) {
        return new File(assets(c), "game_patched").isDirectory();
    }
}
