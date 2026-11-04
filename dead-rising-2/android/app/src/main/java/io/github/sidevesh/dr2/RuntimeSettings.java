package io.github.sidevesh.dr2;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The runtime's own settings file (cz_settings.txt / cw_settings.txt), the store
 * behind the game's in-game PC SETTINGS panel (upstream runtime/host/settings.cpp).
 * The launcher edits the same file before a launch, so the two never disagree: a
 * change made in game shows up here next time, and the other way round.
 *
 * The format is upstream's: '#' comments, then key=value lines. Keys this class does
 * not know are kept as they are, like the runtime itself does with keys it does not
 * know — a file written by a newer build must survive this one.
 */
final class RuntimeSettings {
    private final File mFile;
    private final LinkedHashMap<String, String> mValues = new LinkedHashMap<>();
    private final List<String> mComments = new ArrayList<>();

    private RuntimeSettings(File f) { mFile = f; }

    static File file(Context c) {
        return new File(new File(GameEnv.saves(c), BuildConfig.SAVE_FOLDER), BuildConfig.SETTINGS_FILE);
    }

    static boolean exists(Context c) { return file(c).isFile(); }

    static RuntimeSettings load(Context c) {
        RuntimeSettings s = new RuntimeSettings(file(c));
        String text = Fs.readSmall(s.mFile);
        if (text != null) {
            for (String line : text.split("\n")) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                if (t.startsWith("#")) { s.mComments.add(t); continue; }
                int eq = t.indexOf('=');
                if (eq <= 0) continue;
                s.mValues.put(t.substring(0, eq).trim(), t.substring(eq + 1).trim());
            }
        }
        return s;
    }

    int getInt(String key, int def) {
        try {
            String v = mValues.get(key);
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    void put(String key, int v) { mValues.put(key, Integer.toString(v)); }

    /** The internal resolution, as the runtime stores it. */
    int[] resolution(Context c) {
        int w = getInt("res_w", 0), h = getInt("res_h", 0);
        if (w > 0 && h > 0) return new int[] { w, h };
        return Options.resolutionFor(c, 720);
    }

    void setResolution(int w, int h) {
        put("res_w", w);
        put("res_h", h);
        // The legacy mirror the runtime still writes for older builds: round(H/720).
        put("render_scale", Math.max(1, Math.min(4, Math.round(h / 720f))));
        put("aspect", w * 9 > h * 16 ? 1 : 0);
    }

    void save() {
        File dir = mFile.getParentFile();
        if (dir != null) dir.mkdirs();
        StringBuilder sb = new StringBuilder();
        if (mComments.isEmpty()) {
            sb.append("# ").append(BuildConfig.GAME_TITLE).append(" recomp — graphics settings.\n")
              .append("# Written by the Android launcher and the in-game PC Settings screen.\n");
        } else {
            for (String cmt : mComments) sb.append(cmt).append('\n');
        }
        // display_mode 1 (borderless): the only mode that means anything on a phone.
        if (!mValues.containsKey("display_mode")) mValues.put("display_mode", "1");
        for (Map.Entry<String, String> e : mValues.entrySet())
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        try {
            Fs.writeSmall(mFile, sb.toString());
        } catch (java.io.IOException e) {
            android.util.Log.e("DR2", "could not write " + mFile, e);
        }
    }

    /**
     * A first launch on a phone: the runtime's desktop defaults (2x MSAA, high shadows,
     * double zombie detail, an uncapped frame rate) are a desktop GPU's. Write the
     * BALANCED preset instead, once, before the game ever runs; after that the file is
     * the player's.
     */
    static void ensurePhoneDefaults(Context c) {
        if (exists(c)) return;
        RuntimeSettings s = load(c);
        Options.applyPreset(c, s, Options.PRESET_BALANCED);
        s.put("skip_intro_logos", 0);
        s.save();
    }

    /** The choices the launcher offers, and the presets that set several at once. */
    static final class Options {
        static final int PRESET_BATTERY = 0, PRESET_BALANCED = 1, PRESET_QUALITY = 2;
        static final String[] PRESETS = { "Battery saver", "Balanced", "Quality" };

        // 540 is below the title's own 720 (Android only; see the patched settings.cpp).
        static final int[] HEIGHTS = { 540, 720, 900, 1080, 1440 };
        static final int[] FPS = { 30, 60, 90, 120, 0 };
        static final int[] MSAA = { 0, 2, 4 };
        static final String[] SHADOWS = { "Low", "Medium", "High" };
        static final int[] CROWD_X10 = { 5, 10, 15, 20 };
        static final int[] WORLD_X10 = { 10, 15, 20, 30, 40 };
        static final int[] FOV = { -10, -5, 0, 5, 10, 15, 20, 25, 30 };
        static final int[] EXPOSURE_X10 = { 10, 15, 20, 25, 30, 35, 40, 45, 50 };
        static final int[] LANG = { 1, 4, 5, 6, 2, 7 };
        static final String[] LANG_NAMES = { "English", "Français", "Español", "Italiano", "日本語", "한국어" };

        /**
         * H lines at the display's landscape shape, within the runtime's rule for an
         * internal resolution (Settings_ValidInternalRes: even width, at least 16:10,
         * at most 6880 wide). A 19.3:9 S23 Ultra at 720 gets 1560x720.
         */
        static int[] resolutionFor(Context c, int h) {
            android.graphics.Rect b = c.getSystemService(android.view.WindowManager.class)
                    .getMaximumWindowMetrics().getBounds();
            int lw = Math.max(b.width(), b.height()), lh = Math.min(b.width(), b.height());
            double aspect = lh > 0 ? (double) lw / lh : 16.0 / 9.0;
            aspect = Math.max(aspect, 1.6);
            int w = (int) Math.round(h * aspect / 2.0) * 2;
            int min = (h * 16 + 9) / 10;
            if (w < min) w = min + (min & 1);
            return new int[] { Math.min(w, 6880), h };
        }

        static void applyPreset(Context c, RuntimeSettings s, int preset) {
            int[] r;
            switch (preset) {
                case PRESET_BATTERY:
                    r = resolutionFor(c, 720);
                    s.setResolution(r[0], r[1]);
                    s.put("fps_cap", 30);
                    s.put("vsync", 1);
                    s.put("msaa", 0);
                    s.put("shadow_tier", 0);
                    s.put("crowd_lod_x10", 5);
                    s.put("world_lod_x10", 10);
                    break;
                case PRESET_QUALITY:
                    r = resolutionFor(c, 1080);
                    s.setResolution(r[0], r[1]);
                    s.put("fps_cap", 60);
                    s.put("vsync", 1);
                    s.put("msaa", 2);
                    s.put("shadow_tier", 2);
                    s.put("crowd_lod_x10", 15);
                    s.put("world_lod_x10", 15);
                    break;
                default:
                    r = resolutionFor(c, 720);
                    s.setResolution(r[0], r[1]);
                    s.put("fps_cap", 60);
                    s.put("vsync", 1);
                    s.put("msaa", 0);
                    s.put("shadow_tier", 1);
                    s.put("crowd_lod_x10", 10);
                    s.put("world_lod_x10", 10);
                    break;
            }
        }

        /** Which preset the current values are exactly, or -1 (custom). */
        static int matchPreset(Context c, RuntimeSettings s) {
            for (int p = 0; p < PRESETS.length; p++) {
                RuntimeSettings t = new RuntimeSettings(s.mFile);
                applyPreset(c, t, p);
                boolean same = true;
                for (Map.Entry<String, String> e : t.mValues.entrySet()) {
                    if (e.getKey().equals("render_scale") || e.getKey().equals("aspect")) continue;
                    String v = s.mValues.get(e.getKey());
                    if (v == null || !v.equals(e.getValue())) { same = false; break; }
                }
                if (same) return p;
            }
            return -1;
        }

        private Options() {}
    }
}
