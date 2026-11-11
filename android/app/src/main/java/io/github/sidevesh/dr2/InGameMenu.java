package io.github.sidevesh.dr2;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Arrays;

/**
 * The in-game menu: a small ≡ button at the top of the screen that opens a side panel
 * over the game, with everything that can change WHILE playing — cheats, the FPS meter,
 * FSR, the on-screen controller (shown, opacity, size, haptics, auto-mash, RUN button),
 * gyro, sustained performance — and a shortcut to the game's own pause menu.
 *
 * Every row writes the same preference the launcher shows, then applies it at once
 * (GameActivity's live setters, NativeBridge.setCheats / setFsr). The game keeps
 * running underneath; START in the panel opens its pause menu.
 */
final class InGameMenu {
    private static final int BG = 0xF0141418, CARD = 0xFF1F2026, LINE = 0x33FFFFFF,
            TEXT = 0xFFF1F2F4, MUTED = 0xFFA4AAB4;

    private final GameActivity mAct;
    private final SharedPreferences mPrefs;
    private final int mAccent, mAccentDim;
    private TextView mButton;
    private FrameLayout mOverlay;
    private LinearLayout mList;

    InGameMenu(GameActivity act) {
        mAct = act;
        mPrefs = GameEnv.prefs(act);
        boolean west = "casewest".equals(BuildConfig.GAME_ID);
        mAccent = west ? 0xFFF0883E : 0xFFE4492F;
        mAccentDim = west ? 0xCC5C3418 : 0xCC5A2219;
    }

    private int dp(float v) {
        return Math.round(v * mAct.getResources().getDisplayMetrics().density);
    }

    private GradientDrawable shape(int color, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(mAct);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    /** The ≡ button, top centre under the touch controller's BACK / START. */
    void attach(ViewGroup layout) {
        if (layout == null || !mPrefs.getBoolean(GameEnv.PREF_MENU_BUTTON, true)) return;
        mButton = text("≡", 20, Color.WHITE, true);
        mButton.setGravity(Gravity.CENTER);
        mButton.setBackground(shape(0x66000000, 10, 0x55FFFFFF));
        mButton.setAlpha(0.75f);
        mButton.setOnClickListener(v -> open());
        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(dp(44), dp(34));
        lp.addRule(RelativeLayout.CENTER_HORIZONTAL);
        lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        // Below BACK/START, which the touch layout puts in the top ~12% of the height.
        lp.topMargin = Math.round(mAct.getResources().getDisplayMetrics().heightPixels * 0.15f);
        layout.addView(mButton, lp);
    }

    boolean isOpen() { return mOverlay != null; }

    void close() {
        if (mOverlay == null) return;
        ((ViewGroup) mOverlay.getParent()).removeView(mOverlay);
        mOverlay = null;
        mAct.onMenuClosed();
    }

    void open() {
        ViewGroup layout = mAct.gameLayout();
        if (mOverlay != null || layout == null) return;
        mOverlay = new FrameLayout(mAct);
        mOverlay.setBackgroundColor(0x66000000);
        mOverlay.setClickable(true);
        mOverlay.setOnClickListener(v -> close());   // a tap beside the panel closes it

        ScrollView sc = new ScrollView(mAct);
        sc.setBackground(shape(BG, 0, LINE));
        sc.setClickable(true);   // taps on the panel stay in the panel
        mList = new LinearLayout(mAct);
        mList.setOrientation(LinearLayout.VERTICAL);
        mList.setPadding(dp(14), dp(10), dp(14), dp(16));
        sc.addView(mList);
        int w = Math.min(dp(400), Math.round(mAct.getResources().getDisplayMetrics().widthPixels * 0.5f));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END);
        mOverlay.addView(sc, lp);
        layout.addView(mOverlay, new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fill();
    }

    private void refill() {
        if (mList == null) return;
        mList.removeAllViews();
        fill();
    }

    private void fill() {
        LinearLayout head = new LinearLayout(mAct);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("GAME MENU", 13, mAccent, true);
        title.setLetterSpacing(0.2f);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView x = text("✕", 18, TEXT, true);
        x.setPadding(dp(12), dp(4), dp(4), dp(4));
        x.setOnClickListener(v -> close());
        head.addView(x);
        mList.addView(head);

        if (mAct.hasTouchControls())
            button("Pause menu (START)", "The game's own pause menu", () -> {
                close();
                mAct.tapStart();
            });

        section("CHEATS");
        toggle("God mode", "Chuck cannot be hurt", GameEnv.PREF_CHEAT_GOD, false, on -> applyCheats());
        toggle("Zombies ignore you", "Zombies do not attack humans", GameEnv.PREF_CHEAT_IGNORE, false,
                on -> applyCheats());
        toggle("Infinite weapon durability", "Weapons and props never break",
                GameEnv.PREF_CHEAT_DURABILITY, false, on -> applyCheats());

        section("DISPLAY");
        toggle("Show FPS", "Frames per second, bottom left", GameEnv.PREF_SHOW_FPS, false,
                on -> mAct.setFpsVisible(on));
        boolean fsr = mPrefs.getString(GameEnv.PREF_UPSCALER, "fsr").equals("fsr");
        switchRow("FSR upscaling", "Sharper enlargement to the screen; off is the plain stretch",
                fsr, on -> {
                    mPrefs.edit().putString(GameEnv.PREF_UPSCALER, on ? "fsr" : "bilinear").apply();
                    applyFsr();
                    refill();
                });
        if (fsr) {
            int[] sh = { 0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100 };
            stepper("FSR sharpness", null, pct(sh), nearest(sh, mPrefs.getInt(GameEnv.PREF_FSR_SHARP, 80)),
                    i -> { mPrefs.edit().putInt(GameEnv.PREF_FSR_SHARP, sh[i]).apply(); applyFsr(); });
        }
        android.os.PowerManager pm = mAct.getSystemService(android.os.PowerManager.class);
        if (pm != null && pm.isSustainedPerformanceModeSupported())
            toggle("Sustained performance", "Steady clocks instead of fast-then-throttled",
                    GameEnv.PREF_SUSTAINED, false, on -> mAct.setSustained(on));

        section("TOUCH CONTROLS");
        toggle("On-screen controls", "Show or hide the touch controller", GameEnv.PREF_TOUCH, true,
                on -> mAct.setTouchVisible(on));
        toggle("Auto-mash", "Holding A, B, X or Y repeats it rapidly — for escaping grabs",
                GameEnv.PREF_AUTOMASH, false, on -> mAct.reloadTouch());
        toggle("RUN button", "A button above the move stick that keeps Chuck running; tap it "
                + "again to stop", GameEnv.PREF_RUN_BUTTON, true, on -> mAct.reloadTouch());
        int[] op = { 20, 30, 40, 50, 60, 70, 80, 90, 100 };
        stepper("Opacity", null, pct(op), nearest(op, mPrefs.getInt(GameEnv.PREF_TOUCH_OPACITY, 60)),
                i -> { mPrefs.edit().putInt(GameEnv.PREF_TOUCH_OPACITY, op[i]).apply(); mAct.reloadTouch(); });
        int[] sz = { 70, 80, 90, 100, 110, 120, 130, 140, 150 };
        stepper("Size", null, pct(sz), nearest(sz, mPrefs.getInt(GameEnv.PREF_TOUCH_SCALE, 100)),
                i -> { mPrefs.edit().putInt(GameEnv.PREF_TOUCH_SCALE, sz[i]).apply(); mAct.reloadTouch(); });
        toggle("Haptic feedback", null, GameEnv.PREF_HAPTICS, true, on -> mAct.reloadTouch());

        section("GYRO");
        String[] modes = { "off", "aim", "always" };
        int gm = Math.max(0, Arrays.asList(modes).indexOf(mPrefs.getString(GameEnv.PREF_GYRO_MODE, "aim")));
        stepper("Gyro", "Turn the phone to move the camera", new String[] { "Off", "While button held", "Always" },
                gm, i -> { mPrefs.edit().putString(GameEnv.PREF_GYRO_MODE, modes[i]).apply(); mAct.reloadTouch(); refill(); });
        if (gm != 0) {
            if (gm == 1) {
                String[] btns = { "LT", "RT", "LB", "RB" };
                int bi = Math.max(0, Arrays.asList(btns).indexOf(mPrefs.getString(GameEnv.PREF_GYRO_BUTTON, "LT")));
                stepper("Gyro button", null, btns, bi,
                        i -> { mPrefs.edit().putString(GameEnv.PREF_GYRO_BUTTON, btns[i]).apply(); mAct.reloadTouch(); });
            }
            int[] gs = { 25, 50, 75, 100, 125, 150, 200, 250, 300, 400 };
            stepper("Sensitivity", null, pct(gs), nearest(gs, mPrefs.getInt(GameEnv.PREF_GYRO_SENS, 100)),
                    i -> { mPrefs.edit().putInt(GameEnv.PREF_GYRO_SENS, gs[i]).apply(); mAct.reloadTouch(); });
            toggle("Invert horizontal", null, GameEnv.PREF_GYRO_INVERT_X, false, on -> mAct.reloadTouch());
            toggle("Invert vertical", null, GameEnv.PREF_GYRO_INVERT_Y, false, on -> mAct.reloadTouch());
        }
        TextView note = text("Graphics quality (resolution, shadows, detail) is set in the launcher "
                + "and applies the next time the game starts.", 11, MUTED, false);
        note.setPadding(dp(4), dp(14), dp(4), 0);
        mList.addView(note);
    }

    private void applyCheats() {
        try {
            NativeBridge.setCheats(GameEnv.cheatMask(mAct));
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    private void applyFsr() {
        boolean on = mPrefs.getString(GameEnv.PREF_UPSCALER, "fsr").equals("fsr");
        float stops = (float) (2.0 * (1.0 - mPrefs.getInt(GameEnv.PREF_FSR_SHARP, 80) / 100.0));
        try {
            NativeBridge.setFsr(on, stops);
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    // ---- rows (the launcher's look, smaller) -------------------------------------------

    private interface OnToggle { void set(boolean on); }
    private interface OnPick { void pick(int i); }

    private void section(String name) {
        TextView t = text(name, 11, mAccent, true);
        t.setLetterSpacing(0.2f);
        t.setPadding(dp(4), dp(14), 0, dp(2));
        mList.addView(t);
    }

    private LinearLayout row(String label, String hint) {
        LinearLayout r = new LinearLayout(mAct);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(7), dp(8), dp(7));
        r.setBackground(shape(CARD, 10, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(5);
        mList.addView(r, lp);
        LinearLayout labels = new LinearLayout(mAct);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(label, 14, TEXT, true));
        if (hint != null) labels.addView(text(hint, 11, MUTED, false));
        r.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return r;
    }

    private void toggle(String label, String hint, String pref, boolean def, OnToggle then) {
        switchRow(label, hint, mPrefs.getBoolean(pref, def), on -> {
            mPrefs.edit().putBoolean(pref, on).apply();
            then.set(on);
        });
    }

    private void switchRow(String label, String hint, boolean on, OnToggle onToggle) {
        LinearLayout r = row(label, hint);
        Switch sw = new Switch(mAct);
        sw.setChecked(on);
        sw.setThumbTintList(new ColorStateList(new int[][] { { android.R.attr.state_checked }, {} },
                new int[] { mAccent, 0xFFB0B4BC }));
        sw.setTrackTintList(new ColorStateList(new int[][] { { android.R.attr.state_checked }, {} },
                new int[] { mAccentDim, LINE }));
        sw.setOnCheckedChangeListener((b, v) -> onToggle.set(v));
        r.setOnClickListener(v -> sw.toggle());
        r.addView(sw);
    }

    private void stepper(String label, String hint, String[] values, int index, OnPick onPick) {
        LinearLayout r = row(label, hint);
        final int[] cur = { Math.max(0, Math.min(values.length - 1, index)) };
        TextView val = text(values[cur[0]], 13, TEXT, true);
        val.setGravity(Gravity.CENTER);
        val.setMinWidth(dp(90));
        TextView prev = arrow("‹"), next = arrow("›");
        Runnable show = () -> {
            val.setText(values[cur[0]]);
            prev.setAlpha(cur[0] > 0 ? 1f : 0.25f);
            next.setAlpha(cur[0] < values.length - 1 ? 1f : 0.25f);
        };
        prev.setOnClickListener(v -> { if (cur[0] > 0) { cur[0]--; show.run(); onPick.pick(cur[0]); } });
        next.setOnClickListener(v -> { if (cur[0] < values.length - 1) { cur[0]++; show.run(); onPick.pick(cur[0]); } });
        show.run();
        LinearLayout box = new LinearLayout(mAct);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(shape(0xFF0D0E11, 10, LINE));
        box.addView(prev);
        box.addView(val);
        box.addView(next);
        r.addView(box);
    }

    private TextView arrow(String s) {
        TextView t = text(s, 20, TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), 0, dp(12), dp(3));
        t.setMinHeight(dp(36));
        return t;
    }

    private void button(String label, String hint, Runnable r) {
        LinearLayout row = row(label, hint);
        row.addView(text("›", 20, MUTED, true));
        row.setOnClickListener(v -> r.run());
    }

    private static String[] pct(int[] v) {
        String[] out = new String[v.length];
        for (int i = 0; i < v.length; i++) out[i] = v[i] + "%";
        return out;
    }

    private static int nearest(int[] options, int v) {
        int best = 0;
        for (int i = 1; i < options.length; i++)
            if (Math.abs(options[i] - v) < Math.abs(options[best] - v)) best = i;
        return best;
    }
}
