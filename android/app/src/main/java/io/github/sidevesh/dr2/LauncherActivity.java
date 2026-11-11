package io.github.sidevesh.dr2;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The app's front door, standing in for upstream's SDL-drawn desktop launcher.
 *
 * Left: what is installed, which GPU driver the last run used, and PLAY. Right: the
 * settings, in three tabs —
 *   GRAPHICS  the runtime's own settings file (RuntimeSettings): presets, internal
 *             resolution, frame-rate cap, vsync, MSAA, shadows, zombie and world
 *             detail, field of view, exposure; plus the FPS readout;
 *   CONTROLS  the on-screen controller: on/off, opacity, size, haptics;
 *   SYSTEM    GPU driver, Turnip's rendering mode, sustained performance, intro
 *             logos, language, and the tools (device check, logs, environment).
 *
 * Everything heavy runs the upstream runtime's own tool modes in this process
 * (NativeBridge.runTool); the game itself runs in GameActivity's process, which reads
 * every one of these settings when it starts.
 */
public class LauncherActivity extends Activity {
    private static final int REQ_PACKAGE = 1, REQ_ASSETS = 2, REQ_EXPORT_SAVES = 3, REQ_IMPORT_SAVES = 4,
            REQ_IMPORT_DRIVER = 5;

    // Palette.
    // Panels are translucent so the game's own background art (GameArt) shows through.
    private static final int BG = 0xFF0D0E11, PANEL = 0xD914161B, SHEET = 0x7314161B, CARD = 0xCC1D2027,
            LINE = 0x33FFFFFF, TEXT = 0xFFF1F2F4, MUTED = 0xFFA4AAB4,
            OK = 0xFF6CCB7A, WARN = 0xFFF2B84B, BAD = 0xFFEF5350;
    // Each game's colour: Case Zero's blood red, Case West's dusk orange.
    private static final boolean WEST = "casewest".equals(BuildConfig.GAME_ID);
    private static final int ACCENT = WEST ? 0xFFF0883E : 0xFFE4492F,
            ACCENT_DIM = WEST ? 0xCC5C3418 : 0xCC5A2219;

    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
    private boolean mBusy;
    private boolean mNativeOk;
    private String mNativeError;

    private LinearLayout mStatus;
    private android.widget.ImageView mBgView, mLogoView;
    private View mTextTitle;
    private boolean mArtLoaded;
    private TextView mDriverLine, mProgressText, mPlay;
    private ProgressBar mProgress;
    private View mImportPackage, mImportAssets, mCheck, mLog;
    private LinearLayout mTabContent;
    private final TextView[] mTabs = new TextView[4];
    private int mTab;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        GameEnv.apply(this);
        try {
            NativeBridge.load();
            mNativeOk = true;
        } catch (Throwable t) {
            mNativeError = t.toString();
        }
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        showTab(mTab);   // the in-game panel may have changed the settings file
        loadArt();       // the game may have just been imported
        // An APK built with --bundle carries the game itself: install it the first
        // time (and after an update that changed it), with nothing for the player to pick.
        if (!mBusy && Importer.bundlePending(this)) {
            runBusy("Game data installed — ready to play.", ui -> Importer.installBundled(this, ui));
        }
        // Will this phone run it? Checked once per APK and GPU driver choice, on the tool
        // worker, and shown on the main screen (Compat).
        if (mNativeOk && Compat.due(this)) {
            mWorker.execute(() -> {
                try {
                    Compat.run(this);
                } catch (Throwable ignored) {
                }
                runOnUiThread(this::refresh);
            });
        }
    }

    // ---- small view helpers -----------------------------------------------------------

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable shape(int color, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private void pressable(View v, int color, float radiusDp, int stroke) {
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                shape(color, radiusDp, stroke), shape(Color.WHITE, radiusDp, 0)));
        v.setClickable(true);
        v.setFocusable(true);
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private TextView action(String label, View.OnClickListener l) {
        TextView t = text(label, 13, TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(11), dp(10), dp(11));
        pressable(t, CARD, 10, LINE);
        t.setOnClickListener(l);
        return t;
    }

    private static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    // ---- layout ------------------------------------------------------------------------

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(20), dp(14), dp(20), dp(14));
        // Keep the UI clear of a camera cutout and the gesture bar.
        root.setOnApplyWindowInsetsListener((v, in) -> {
            android.graphics.Insets i = in.getInsets(android.view.WindowInsets.Type.systemBars()
                    | android.view.WindowInsets.Type.displayCutout());
            v.setPadding(dp(20) + i.left, dp(14) + i.top, dp(20) + i.right, dp(14) + i.bottom);
            return in;
        });

        // LEFT: identity, status, play.
        ScrollView leftScroll = new ScrollView(this);
        leftScroll.setVerticalScrollBarEnabled(false);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(0, 0, dp(18), 0);
        leftScroll.addView(left);
        root.addView(leftScroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 4f));

        // The game's own logo, once the game is imported (GameArt); until then, text.
        mLogoView = new android.widget.ImageView(this);
        mLogoView.setAdjustViewBounds(true);
        mLogoView.setMaxHeight(dp(110));
        mLogoView.setScaleType(android.widget.ImageView.ScaleType.FIT_START);
        mLogoView.setVisibility(View.GONE);
        left.addView(mLogoView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout textTitle = new LinearLayout(this);
        textTitle.setOrientation(LinearLayout.VERTICAL);
        mTextTitle = textTitle;
        left.addView(textTitle);
        TextView kicker = text("DEAD RISING 2", 12, ACCENT, true);
        kicker.setLetterSpacing(0.25f);
        textTitle.addView(kicker);
        String sub = BuildConfig.GAME_TITLE.contains(":")
                ? BuildConfig.GAME_TITLE.substring(BuildConfig.GAME_TITLE.indexOf(':') + 1).trim()
                : BuildConfig.GAME_TITLE;
        TextView title = text(sub.toUpperCase(Locale.ROOT), 30, TEXT, true);
        title.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        title.setLetterSpacing(0.02f);
        textTitle.addView(title);
        TextView tag = text("Native ARM64 recompilation · unofficial · your own copy of the game", 12, MUTED, false);
        tag.setPadding(0, dp(2), 0, dp(12));
        left.addView(tag);

        mStatus = new LinearLayout(this);
        mStatus.setOrientation(LinearLayout.VERTICAL);
        mStatus.setPadding(dp(14), dp(10), dp(14), dp(10));
        mStatus.setBackground(shape(PANEL, 14, LINE));
        left.addView(mStatus);

        mDriverLine = text("", 12, MUTED, false);
        mDriverLine.setPadding(dp(4), dp(8), dp(4), dp(10));
        left.addView(mDriverLine);

        mPlay = text("▶  PLAY", 20, Color.WHITE, true);
        mPlay.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        mPlay.setLetterSpacing(0.12f);
        mPlay.setGravity(Gravity.CENTER);
        mPlay.setPadding(0, dp(16), 0, dp(16));
        pressable(mPlay, ACCENT, 14, 0);
        mPlay.setOnClickListener(v -> play());
        left.addView(mPlay);

        mProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mProgress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        mProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(10);
        left.addView(mProgress, plp);
        mProgressText = text("", 12, MUTED, false);
        mProgressText.setVisibility(View.GONE);
        left.addView(mProgressText);

        LinearLayout a1 = new LinearLayout(this);
        LinearLayout a2 = new LinearLayout(this);
        for (LinearLayout r : new LinearLayout[] { a1, a2 }) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(10);
            left.addView(r, lp);
        }
        mImportPackage = action("Import game package", v -> pick(REQ_PACKAGE));
        mImportAssets = action("Import prepared assets", v -> pick(REQ_ASSETS));
        mCheck = action("Check this device", v -> checkDevice());
        mLog = action("Last game log", v -> showText("Last game log",
                Fs.tail(GameEnv.runtimeLog(this), 64 * 1024)));
        addPair(a1, mImportPackage, mImportAssets);
        addPair(a2, mCheck, mLog);

        // RIGHT: settings.
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        // See-through, so the scene shows; each setting row carries its own backing.
        right.setBackground(shape(SHEET, 18, LINE));
        right.setPadding(dp(14), dp(12), dp(14), dp(4));
        root.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 6f));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setBackground(shape(BG, 12, LINE));
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        String[] names = { "PROGRESS", "GRAPHICS", "CONTROLS", "SYSTEM" };
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = text(names[i], 12, MUTED, true);
            t.setLetterSpacing(0.12f);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(9), 0, dp(9));
            t.setOnClickListener(v -> showTab(idx));
            mTabs[i] = t;
            tabs.addView(t, weight(1));
        }
        right.addView(tabs);

        ScrollView sc = new ScrollView(this);
        mTabContent = new LinearLayout(this);
        mTabContent.setOrientation(LinearLayout.VERTICAL);
        mTabContent.setPadding(0, dp(6), 0, dp(10));
        sc.addView(mTabContent);
        right.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // The art layer under everything; the content draws over it.
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        mBgView = new android.widget.ImageView(this);
        mBgView.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        frame.addView(mBgView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(Color.TRANSPARENT);
        frame.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) { mArtLoaded = false; loadArt(); }
        });
        setContentView(frame);
    }

    /** The game's background and logo, once it is imported (GameArt), off the UI thread. */
    private void loadArt() {
        if (mArtLoaded || mBgView == null || !GameArt.available(this)) return;
        final int w = mBgView.getWidth(), h = mBgView.getHeight();
        if (w <= 0 || h <= 0) return;
        mArtLoaded = true;
        new Thread(() -> {
            android.graphics.Bitmap bg = null, logo = null;
            try {
                bg = GameArt.background(this, w, h);
                logo = GameArt.logo(this);
            } catch (Throwable ignored) {
            }
            final android.graphics.Bitmap fb = bg, fl = logo;
            runOnUiThread(() -> {
                if (fb != null) mBgView.setImageBitmap(fb);
                if (fl != null) {
                    mLogoView.setImageBitmap(fl);
                    mLogoView.setVisibility(View.VISIBLE);
                    mTextTitle.setVisibility(View.GONE);
                }
            });
        }, "dr2-art").start();
    }

    private void addPair(LinearLayout row, View a, View b) {
        LinearLayout.LayoutParams la = weight(1), lb = weight(1);
        la.rightMargin = dp(5);
        lb.leftMargin = dp(5);
        row.addView(a, la);
        row.addView(b, lb);
    }

    private void statusLine(String mark, int color, String line) {
        LinearLayout r = new LinearLayout(this);
        r.setPadding(0, dp(3), 0, dp(3));
        TextView m = text(mark, 13, color, true);
        m.setMinWidth(dp(20));
        r.addView(m);
        TextView t = text(line, 13, TEXT, false);
        r.addView(t, weight(1));
        mStatus.addView(r);
    }

    private void refresh() {
        mStatus.removeAllViews();
        boolean game = GameEnv.gameUnpacked(this);
        int shaders = GameEnv.shaderModules(this);
        if (!mNativeOk) {
            statusLine("✕", BAD, "The native runtime failed to load: " + mNativeError);
        } else if (BuildConfig.NATIVE_STUB) {
            statusLine("✕", BAD, "Built WITHOUT the recompiled game (CI / stub build): it can import "
                    + "and check data, but cannot play. Build your own APK — see the README.");
        }
        if (Importer.bundleHeader(this) != null) {
            if (Importer.bundlePending(this)) statusLine("•", WARN, "This app carries the game: installing it…");
            else statusLine("✓", OK, "Game data installed from this app");
        }
        if (game) statusLine("✓", OK, "Game data unpacked");
        else statusLine("✕", BAD, "Game data missing — IMPORT GAME PACKAGE (the XBLA file, "
                + BuildConfig.PACKAGE_SIZE + ", from Content/0000000000000000/" + BuildConfig.TITLE_ID
                + "/000D0000/ on the console)");
        if (game) {
            if (GameEnv.overlaysGenerated(this)) statusLine("✓", OK, "Menu and prompt overlays generated");
            else statusLine("•", WARN, "Overlays not generated yet (the game makes them at start)");
        }
        if (mNativeOk) {
            Compat.Verdict v = Compat.cached(this);
            if (v == null || Compat.due(this)) {
                statusLine("•", WARN, "Checking whether this phone can run the game…");
            } else if (v.ok) {
                statusLine("✓", OK, "This phone can run it" + (v.gpu.isEmpty() ? "" : ": " + v.gpu));
            } else {
                statusLine("✕", BAD, (v.gpu.isEmpty() ? "This phone" : v.gpu) + " cannot run it yet. "
                        + Compat.advice(v));
            }
        }
        if (shaders > 0) statusLine("✓", OK, "Shader cache: " + shaders + " modules");
        else statusLine("✕", BAD, "Shader cache missing — IMPORT GAME PACKAGE builds it on the phone "
                + "(a few minutes, once), or import one made on a PC");

        // The driver: what is chosen, and what the last run really loaded.
        String pref = GameEnv.driverPref(this), eff = GameEnv.driver(this);
        StringBuilder d = new StringBuilder("GPU driver: ").append(pref.equals("auto")
                ? GameEnv.label(eff) + " (auto)" : GameEnv.label(this, pref));
        String rep = GameEnv.lastDriverReport(this);
        int color = MUTED;
        if (rep != null) {
            String loaded = null;
            boolean failed = false;
            for (String l : rep.split("\n")) {
                if (l.startsWith("loaded: ")) loaded = l.substring(8);
                if (l.startsWith("failed: ")) failed = true;
            }
            if (loaded != null) {
                d.append("\nLast run used: ").append(loaded.replace("turnip: ", ""));
                if (failed) {
                    d.append(" — another build failed to load (SYSTEM tab shows why)");
                    color = WARN;
                }
                if (loaded.equals("system") && GameEnv.adreno()) {
                    d.append(" — cannot run this renderer on Adreno; pick a Turnip build");
                    color = BAD;
                }
            }
        }
        mDriverLine.setText(d.toString());
        mDriverLine.setTextColor(color);

        boolean idle = !mBusy;
        boolean canPlay = idle && mNativeOk && !BuildConfig.NATIVE_STUB && game && shaders > 0;
        mPlay.setEnabled(canPlay);
        mPlay.setAlpha(canPlay ? 1f : 0.4f);
        for (View v : new View[] { mImportPackage, mCheck }) enable(v, idle && mNativeOk);
        enable(mImportAssets, idle);
        enable(mLog, true);
    }

    private static void enable(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.4f);
    }

    // ---- settings tabs --------------------------------------------------------------------

    private void showTab(int idx) {
        mTab = idx;
        for (int i = 0; i < mTabs.length; i++) {
            boolean on = i == idx;
            mTabs[i].setTextColor(on ? Color.WHITE : MUTED);
            mTabs[i].setBackground(on ? shape(ACCENT_DIM, 9, ACCENT) : null);
        }
        mTabContent.removeAllViews();
        switch (idx) {
            case 0: progressTab(); break;
            case 1: graphicsTab(); break;
            case 2: controlsTab(); break;
            default: systemTab(); break;
        }
    }

    private interface OnPick { void pick(int index); }
    private interface OnToggle { void set(boolean on); }

    private void section(String name) {
        TextView t = text(name, 11, ACCENT, true);
        t.setLetterSpacing(0.2f);
        t.setPadding(dp(4), dp(14), 0, dp(4));
        mTabContent.addView(t);
    }

    private LinearLayout settingRow(String label, String hint) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(8), dp(8), dp(8));
        r.setBackground(shape(CARD, 10, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        mTabContent.addView(r, lp);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(label, 14, TEXT, true));
        if (hint != null) {
            TextView h = text(hint, 11, MUTED, false);
            h.setPadding(0, dp(1), dp(6), 0);
            labels.addView(h);
        }
        r.addView(labels, weight(1));
        return r;
    }

    /** ‹ value › — tap the arrows (or the value) to step through the choices. */
    private void stepper(String label, String hint, String[] values, int index, OnPick onPick) {
        LinearLayout r = settingRow(label, hint);
        final int[] cur = { Math.max(0, Math.min(values.length - 1, index)) };
        TextView val = text(values[cur[0]], 13, TEXT, true);
        val.setGravity(Gravity.CENTER);
        val.setMinWidth(dp(120));
        TextView prev = arrow("‹"), next = arrow("›");
        Runnable show = () -> {
            val.setText(values[cur[0]]);
            prev.setAlpha(cur[0] > 0 ? 1f : 0.25f);
            next.setAlpha(cur[0] < values.length - 1 ? 1f : 0.25f);
        };
        prev.setOnClickListener(v -> {
            if (cur[0] > 0) { cur[0]--; show.run(); onPick.pick(cur[0]); }
        });
        next.setOnClickListener(v -> {
            if (cur[0] < values.length - 1) { cur[0]++; show.run(); onPick.pick(cur[0]); }
        });
        val.setOnClickListener(v -> {
            cur[0] = (cur[0] + 1) % values.length; show.run(); onPick.pick(cur[0]);
        });
        show.run();
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(shape(BG, 10, LINE));
        box.addView(prev);
        box.addView(val);
        box.addView(next);
        r.addView(box);
    }

    private TextView arrow(String s) {
        TextView t = text(s, 22, TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), 0, dp(14), dp(3));
        t.setMinHeight(dp(40));
        return t;
    }

    private void toggle(String label, String hint, boolean on, OnToggle onToggle) {
        LinearLayout r = settingRow(label, hint);
        Switch sw = new Switch(this);
        sw.setChecked(on);
        sw.setThumbTintList(new ColorStateList(new int[][] { { android.R.attr.state_checked }, {} },
                new int[] { ACCENT, 0xFFB0B4BC }));
        sw.setTrackTintList(new ColorStateList(new int[][] { { android.R.attr.state_checked }, {} },
                new int[] { ACCENT_DIM, LINE }));
        sw.setOnCheckedChangeListener((b, v) -> onToggle.set(v));
        r.setOnClickListener(v -> sw.toggle());
        r.addView(sw);
    }

    private void button(String label, String hint, View.OnClickListener l) {
        LinearLayout r = settingRow(label, hint);
        r.addView(text("›", 22, MUTED, true));
        pressable(r, CARD, 10, 0);
        r.setOnClickListener(l);
    }

    private void note(String s) {
        TextView t = text(s, 11, MUTED, false);
        t.setPadding(dp(6), dp(10), dp(6), 0);
        mTabContent.addView(t);
    }

    private static int nearest(int[] options, int v) {
        int best = 0;
        for (int i = 1; i < options.length; i++)
            if (Math.abs(options[i] - v) < Math.abs(options[best] - v)) best = i;
        return best;
    }

    private static String tenths(int x10) {
        return String.format(Locale.US, "%.1f", x10 / 10f);
    }

    // ---- PROGRESS: achievements and saves ---------------------------------------------

    private int mProgressGen;

    private void progressTab() {
        final int gen = ++mProgressGen;
        section("ACHIEVEMENTS");
        TextView loading = text("Reading the achievement list from your game…", 12, MUTED, false);
        loading.setPadding(dp(6), dp(6), 0, 0);
        mTabContent.addView(loading);
        final int lang = RuntimeSettings.load(this).getInt("language", 1);
        // Off the UI thread: the first time, the list is read out of default.xex.
        // On the worker that runs the native tools: they must not run two at a time.
        mWorker.execute(() -> {
            Xdbf spa = mNativeOk && GameEnv.gameUnpacked(this) ? Progress.spa(this) : null;
            java.util.List<Xdbf.Achievement> list = spa != null ? spa.achievements(lang)
                                                                : new java.util.ArrayList<>();
            java.util.Map<Integer, Long> got = Progress.unlocked(this);
            runOnUiThread(() -> {
                if (gen != mProgressGen || mTab != 0) return;
                mTabContent.removeAllViews();
                fillProgress(spa, list, got);
            });
        });
    }

    private void fillProgress(Xdbf spa, java.util.List<Xdbf.Achievement> list,
                              java.util.Map<Integer, Long> got) {
        section("ACHIEVEMENTS");
        if (list.isEmpty()) {
            note(GameEnv.gameUnpacked(this)
                    ? "Could not read the achievement list from the game (" + got.size() + " unlocked so far)."
                    : "Import the game first: the achievement list is read from your copy of it.");
        } else {
            int have = 0, score = 0, total = 0;
            for (Xdbf.Achievement a : list) {
                total += a.score;
                if (got.containsKey(a.id)) { have++; score += a.score; }
            }
            LinearLayout sum = settingRow(have + " of " + list.size() + " unlocked",
                    "Kept on this phone as you play (offline profile). Xenia keeps its own.");
            TextView g = text(score + " / " + total + " G", 15, ACCENT, true);
            sum.addView(g);
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(Math.max(1, list.size()));
            bar.setProgress(have);
            bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
            mTabContent.addView(bar);
            java.util.List<Xdbf.Achievement> sorted = new java.util.ArrayList<>(list);
            sorted.sort((a, b) -> {
                Long ta = got.get(a.id), tb = got.get(b.id);
                if ((ta != null) != (tb != null)) return ta != null ? -1 : 1;
                if (ta != null) return Long.compare(tb, ta);
                return Integer.compare(a.id, b.id);
            });
            java.text.DateFormat df = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM);
            for (Xdbf.Achievement a : sorted) {
                Long when = got.get(a.id);
                boolean earned = when != null;
                String title = !earned && a.secret() ? "Secret achievement" : a.name;
                String desc = earned ? a.unlockedText
                        : a.secret() ? "Keep playing to find out." : a.lockedText;
                if (earned && when > 0) desc += "\nUnlocked " + df.format(new java.util.Date(when));
                LinearLayout r = settingRow(title, desc);
                byte[] png = earned || !a.secret() ? spa.image(a.imageId) : null;
                if (png != null) {
                    android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length);
                    if (bm != null) {
                        android.widget.ImageView iv = new android.widget.ImageView(this);
                        iv.setImageBitmap(bm);
                        if (!earned) {
                            android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
                            cm.setSaturation(0);
                            iv.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
                            iv.setAlpha(0.45f);
                        }
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
                        lp.rightMargin = dp(12);
                        r.addView(iv, 0, lp);
                    }
                }
                TextView pts = text(a.score + " G", 13, earned ? ACCENT : MUTED, true);
                pts.setPadding(dp(8), 0, dp(4), 0);
                r.addView(pts);
                if (!earned) r.setAlpha(0.7f);
            }
        }

        section("SAVES");
        java.util.List<Progress.Save> saves = Progress.saves(this);
        if (saves.isEmpty()) note("No saves yet. They appear here once the game saves.");
        java.text.DateFormat dtf = java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT);
        for (Progress.Save s : saves) {
            settingRow(s.dir.getName(), Fs.human(s.bytes) + " · " + s.files + (s.files == 1 ? " file" : " files")
                    + " · " + dtf.format(new java.util.Date(s.modified)));
        }
        section("XENIA");
        button("Export to Xenia", "Your saves (with the header files Xenia needs, plus a zip for "
                + "Xenia Manager's Import Saves) and your achievements as " + BuildConfig.TITLE_ID
                + ".gpd, in one zip with instructions", v -> {
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("application/zip");
                    i.putExtra(Intent.EXTRA_TITLE, BuildConfig.GAME_ID + "-" + BuildConfig.TITLE_ID + "-xenia.zip");
                    startActivityForResult(i, REQ_EXPORT_SAVES);
                });
        button("How to use it in Xenia", "Where each file goes, with and without Xenia Manager",
                v -> showText("Export to Xenia", XeniaExport.instructions()));
        button("Import saves from Xenia", "A zip of Xenia's " + BuildConfig.TITLE_ID + " folder (or of an "
                + "export from here). Saves it replaces are backed up first", v -> {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    startActivityForResult(i, REQ_IMPORT_SAVES);
                });
        note("Saves on this phone: " + Progress.profileDir(this).getAbsolutePath()
                + "\nBackups of replaced saves: " + Progress.backups(this).getAbsolutePath());
    }

    private void graphicsTab() {
        final RuntimeSettings s = RuntimeSettings.load(this);

        // Presets.
        int preset = RuntimeSettings.Options.matchPreset(this, s);
        String[] presetNames = preset >= 0 ? RuntimeSettings.Options.PRESETS
                : append(RuntimeSettings.Options.PRESETS, "Custom");
        section("PRESET");
        stepper("Quality preset", "Sets everything below at once; changing any row makes it Custom",
                presetNames, preset >= 0 ? preset : presetNames.length - 1, i -> {
                    if (i >= RuntimeSettings.Options.PRESETS.length) return;
                    RuntimeSettings.Options.applyPreset(this, s, i);
                    s.save();
                    showTab(1);
                });

        section("PERFORMANCE");
        int[] res = s.resolution(this);
        int[] heights = RuntimeSettings.Options.HEIGHTS;
        int resIdx = -1;
        String[] resNames = new String[heights.length];
        for (int i = 0; i < heights.length; i++) {
            int[] r = RuntimeSettings.Options.resolutionFor(this, heights[i]);
            resNames[i] = heights[i] + "p  (" + r[0] + "×" + r[1] + ")"
                    + (heights[i] < 720 ? "  · experimental" : "");
            if (r[0] == res[0] && r[1] == res[1]) resIdx = i;
        }
        if (resIdx < 0) {
            resNames = append(resNames, "Custom  (" + res[0] + "×" + res[1] + ")");
            resIdx = resNames.length - 1;
        }
        final int customRes = resIdx == heights.length ? resIdx : -1;
        stepper("Internal resolution", "What the game renders at — the biggest single cost. 720p is "
                + "the Xbox 360's own; 540p is below it (experimental: thin lines and small text "
                + "may suffer), best with FSR", resNames, resIdx, i -> {
                    if (i == customRes) return;
                    int[] r = RuntimeSettings.Options.resolutionFor(this, heights[i]);
                    s.setResolution(r[0], r[1]);
                    s.save();
                    showTab(1);
                });
        SharedPreferences gp = GameEnv.prefs(this);
        boolean fsrOn = gp.getString(GameEnv.PREF_UPSCALER, "fsr").equals("fsr");
        stepper("FSR upscaling", "The game renders at the internal resolution above and is "
                + "enlarged to fill the screen. On: AMD FidelityFX FSR 1.0 keeps edges sharp. Off: "
                + "the plain stretch the PC version uses (softer, a little cheaper)",
                new String[] { "On", "Off" },
                fsrOn ? 0 : 1, i -> {
                    gp.edit().putString(GameEnv.PREF_UPSCALER, i == 0 ? "fsr" : "bilinear").apply();
                    showTab(1);
                });
        if (fsrOn) {
            int[] sh = { 0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100 };
            stepper("FSR sharpness", null, labels10(sh, "%"),
                    nearest(sh, gp.getInt(GameEnv.PREF_FSR_SHARP, 80)),
                    i -> gp.edit().putInt(GameEnv.PREF_FSR_SHARP, sh[i]).apply());
        }
        int[] fps = RuntimeSettings.Options.FPS;
        String[] fpsNames = { "30 FPS", "60 FPS", "90 FPS", "120 FPS", "Unlocked" };
        stepper("Frame-rate cap", "30 runs cooler and steadier for longer sessions; the Xbox 360 "
                + "ran this game at 30", fpsNames, nearestExact(fps, s.getInt("fps_cap", 0)), i -> {
                    s.put("fps_cap", fps[i]);
                    s.save();
                    showTab(1);
                });
        toggle("VSync", "On: no wasted frames the screen never shows (saves battery). Off: "
                + "lowest latency", s.getInt("vsync", 1) != 0, on -> {
                    s.put("vsync", on ? 1 : 0);
                    s.save();
                    showTab(1);
                });
        int[] msaa = RuntimeSettings.Options.MSAA;
        stepper("Anti-aliasing (MSAA)", "Smooths edges at a large cost on a phone GPU",
                new String[] { "Off", "2×", "4×" }, nearestExact(msaa, s.getInt("msaa", 2)), i -> {
                    s.put("msaa", msaa[i]);
                    s.save();
                    showTab(1);
                });

        section("DETAIL");
        stepper("Shadows", null, RuntimeSettings.Options.SHADOWS,
                Math.max(0, Math.min(2, s.getInt("shadow_tier", 2))), i -> {
                    s.put("shadow_tier", i);
                    s.save();
                    showTab(1);
                });
        int[] crowd = RuntimeSettings.Options.CROWD_X10;
        stepper("Zombie detail", "How many zombies get their full model (1.0 is the Xbox 360's); "
                + "a heavy cost in big crowds", labels(crowd, "", ""),
                nearest(crowd, s.getInt("crowd_lod_x10", 20)), i -> {
                    s.put("crowd_lod_x10", crowd[i]);
                    s.save();
                    showTab(1);
                });
        int[] world = RuntimeSettings.Options.WORLD_X10;
        stepper("World detail", "How far away buildings and props switch to their detailed models",
                labels(world, "", ""), nearest(world, s.getInt("world_lod_x10", 10)), i -> {
                    s.put("world_lod_x10", world[i]);
                    s.save();
                    showTab(1);
                });

        section("PICTURE");
        int[] fov = RuntimeSettings.Options.FOV;
        String[] fovNames = new String[fov.length];
        for (int i = 0; i < fov.length; i++)
            fovNames[i] = fov[i] == 0 ? "Default" : (fov[i] > 0 ? "+" : "") + fov[i] + "°";
        stepper("Field of view", "Wider helps on a small screen held close", fovNames,
                nearest(fov, s.getInt("fov", 0)), i -> {
                    s.put("fov", fov[i]);
                    s.save();
                });
        int[] exp = RuntimeSettings.Options.EXPOSURE_X10;
        stepper("Exposure", "Higher settles the picture darker (2.5 is the default)",
                labels(exp, "", ""), nearest(exp, s.getInt("exposure_x10", 25)), i -> {
                    s.put("exposure_x10", exp[i]);
                    s.save();
                });
        SharedPreferences p = GameEnv.prefs(this);
        toggle("Show FPS", "A small frame counter in the corner while playing",
                p.getBoolean(GameEnv.PREF_SHOW_FPS, false),
                on -> p.edit().putBoolean(GameEnv.PREF_SHOW_FPS, on).apply());
        note("These are the game's own settings (" + BuildConfig.SETTINGS_FILE + "), shared with "
                + "its in-game PC SETTINGS screen. They apply the next time the game starts.");
    }

    private static int nearestExact(int[] options, int v) {
        for (int i = 0; i < options.length; i++) if (options[i] == v) return i;
        return nearest(options, v);
    }

    private static String[] labels(int[] x10, String pre, String post) {
        String[] out = new String[x10.length];
        for (int i = 0; i < x10.length; i++) out[i] = pre + tenths(x10[i]) + post;
        return out;
    }

    private static String[] append(String[] a, String extra) {
        String[] out = java.util.Arrays.copyOf(a, a.length + 1);
        out[a.length] = extra;
        return out;
    }

    private void controlsTab() {
        SharedPreferences p = GameEnv.prefs(this);
        section("ON-SCREEN CONTROLLER");
        toggle("On-screen controls", "Hidden automatically when a controller is connected at launch",
                p.getBoolean(GameEnv.PREF_TOUCH, true),
                on -> p.edit().putBoolean(GameEnv.PREF_TOUCH, on).apply());
        int[] op = { 20, 30, 40, 50, 60, 70, 80, 90, 100 };
        stepper("Opacity", "They also fade further a few seconds after your last touch",
                labels10(op, "%"), nearest(op, p.getInt(GameEnv.PREF_TOUCH_OPACITY, 60)),
                i -> p.edit().putInt(GameEnv.PREF_TOUCH_OPACITY, op[i]).apply());
        int[] sz = { 70, 80, 90, 100, 110, 120, 130, 140, 150 };
        stepper("Size", null, labels10(sz, "%"), nearest(sz, p.getInt(GameEnv.PREF_TOUCH_SCALE, 100)),
                i -> p.edit().putInt(GameEnv.PREF_TOUCH_SCALE, sz[i]).apply());
        toggle("Haptic feedback", "A short buzz on each button press",
                p.getBoolean(GameEnv.PREF_HAPTICS, true),
                on -> p.edit().putBoolean(GameEnv.PREF_HAPTICS, on).apply());
        toggle("Auto-mash", "Holding A, B, X or Y repeats it rapidly — for the button-mash "
                + "escapes when a zombie grabs you", p.getBoolean(GameEnv.PREF_AUTOMASH, false),
                on -> p.edit().putBoolean(GameEnv.PREF_AUTOMASH, on).apply());
        toggle("RUN button", "A button above the move stick: tap it and Chuck keeps running "
                + "the way you last moved (steer with the stick); tap again to stop",
                p.getBoolean(GameEnv.PREF_RUN_BUTTON, true),
                on -> p.edit().putBoolean(GameEnv.PREF_RUN_BUTTON, on).apply());
        toggle("In-game menu button", "A small ≡ at the top of the screen: cheats, FPS, FSR, "
                + "touch and gyro settings while you play",
                p.getBoolean(GameEnv.PREF_MENU_BUTTON, true),
                on -> p.edit().putBoolean(GameEnv.PREF_MENU_BUTTON, on).apply());
        section("CHEATS");
        toggle("God mode", "Chuck cannot be hurt", p.getBoolean(GameEnv.PREF_CHEAT_GOD, false),
                on -> p.edit().putBoolean(GameEnv.PREF_CHEAT_GOD, on).apply());
        toggle("Zombies ignore you", "Zombies do not attack humans — walk through crowds",
                p.getBoolean(GameEnv.PREF_CHEAT_IGNORE, false),
                on -> p.edit().putBoolean(GameEnv.PREF_CHEAT_IGNORE, on).apply());
        toggle("Infinite weapon durability", "Weapons and props never break",
                p.getBoolean(GameEnv.PREF_CHEAT_DURABILITY, false),
                on -> p.edit().putBoolean(GameEnv.PREF_CHEAT_DURABILITY, on).apply());
        note("The game's own developer switches, on from the first frame; the in-game menu "
                + "turns them on and off while playing. Achievements still unlock with them on.");
        section("GYRO AIMING");
        android.hardware.SensorManager sm = getSystemService(android.hardware.SensorManager.class);
        boolean hasGyro = sm != null && sm.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE) != null;
        String[] gModes = { "off", "aim", "always" };
        int gm = java.util.Arrays.asList(gModes).indexOf(p.getString(GameEnv.PREF_GYRO_MODE, "aim"));
        stepper("Gyro", hasGyro ? "Turn the phone to move the camera, on top of the camera stick"
                        : "This phone has no gyroscope", new String[] { "Off", "While aiming", "Always" },
                Math.max(0, gm), i -> {
                    p.edit().putString(GameEnv.PREF_GYRO_MODE, gModes[i]).apply();
                    showTab(2);
                });
        if (gm != 0) {
            String[] btns = { "LT", "RT", "LB", "RB" };
            if (gm == 1) {
                int bi = java.util.Arrays.asList(btns).indexOf(p.getString(GameEnv.PREF_GYRO_BUTTON, "LT"));
                stepper("Aim button", "Gyro is on while this on-screen button is held — the one the "
                        + "game aims with (a gun's or a thrown item's aim)", btns, Math.max(0, bi),
                        i -> p.edit().putString(GameEnv.PREF_GYRO_BUTTON, btns[i]).apply());
            }
            int[] gs = { 25, 50, 75, 100, 125, 150, 200, 250, 300, 400 };
            stepper("Gyro sensitivity", null, labels10(gs, "%"),
                    nearest(gs, p.getInt(GameEnv.PREF_GYRO_SENS, 100)),
                    i -> p.edit().putInt(GameEnv.PREF_GYRO_SENS, gs[i]).apply());
            toggle("Invert horizontal", null, p.getBoolean(GameEnv.PREF_GYRO_INVERT_X, false),
                    on -> p.edit().putBoolean(GameEnv.PREF_GYRO_INVERT_X, on).apply());
            toggle("Invert vertical", null, p.getBoolean(GameEnv.PREF_GYRO_INVERT_Y, false),
                    on -> p.edit().putBoolean(GameEnv.PREF_GYRO_INVERT_Y, on).apply());
        }
        note("Gyro works with the on-screen controls; with a physical controller connected the "
                + "game reads that controller only.");

        note("Layout: left half = move stick (it appears where your thumb lands), right half = "
                + "camera. Face buttons bottom right, triggers and bumpers along the top, BACK and "
                + "START top centre, D-pad and stick clicks bottom centre.\n\n"
                + "A Bluetooth or USB controller works as an Xbox pad — connect it before you press PLAY.");
    }

    private static String[] labels10(int[] v, String post) {
        String[] out = new String[v.length];
        for (int i = 0; i < v.length; i++) out[i] = v[i] + post;
        return out;
    }

    private void systemTab() {
        SharedPreferences p = GameEnv.prefs(this);
        section("GPU");
        String pref = GameEnv.driverPref(this);
        button("GPU driver: " + (pref.equals("auto")
                        ? "Auto (" + GameEnv.label(GameEnv.driver(this)) + ")" : GameEnv.label(this, pref)),
                "On Adreno, a Turnip build is needed: the stock driver lacks a feature this "
                        + "renderer requires. If the chosen driver fails to load, the bundled Turnip "
                        + "and then the phone's own driver are tried",
                v -> chooseDriver());
        button("Import a driver package", "A driver zip in the format Android emulators use (a "
                + "meta.json beside the driver). Lets you try newer Turnip builds or your GPU "
                + "maker's drivers; it does not add features a GPU lacks",
                v -> {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    startActivityForResult(i, REQ_IMPORT_DRIVER);
                });
        if (!CustomDrivers.list(this).isEmpty())
            button("Remove a custom driver", null, v -> removeDriver());
        String rep = GameEnv.lastDriverReport(this);
        if (rep != null && rep.contains("failed: ")) note("Last driver load:\n" + rep);
        stepper("Turnip rendering", "Direct is usually faster for this renderer; Tiled can save "
                + "power on some scenes", new String[] { "Direct (sysmem)", "Tiled (GMEM)" },
                p.getString(GameEnv.PREF_TURNIP_MODE, "sysmem").equals("gmem") ? 1 : 0,
                i -> p.edit().putString(GameEnv.PREF_TURNIP_MODE, i == 1 ? "gmem" : "sysmem").apply());
        android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
        boolean sustained = pm != null && pm.isSustainedPerformanceModeSupported();
        toggle("Sustained performance", sustained
                        ? "Steady clocks the phone can hold for a long session, instead of fast-then-throttled"
                        : "Not supported on this phone (has no effect)",
                p.getBoolean(GameEnv.PREF_SUSTAINED, false),
                on -> p.edit().putBoolean(GameEnv.PREF_SUSTAINED, on).apply());

        section("GAME");
        final RuntimeSettings s = RuntimeSettings.load(this);
        toggle("Skip intro logos", "Straight to the title screen (the logos are legal notices; "
                + "off by default)", s.getInt("skip_intro_logos", 0) != 0, on -> {
                    s.put("skip_intro_logos", on ? 1 : 0);
                    s.save();
                });
        int[] lang = RuntimeSettings.Options.LANG;
        stepper("Language", "Subtitles and menus", RuntimeSettings.Options.LANG_NAMES,
                nearestExact(lang, s.getInt("language", 1)), i -> {
                    s.put("language", lang[i]);
                    s.save();
                });

        section("TOOLS");
        button("Check this device", "The runtime's own Vulkan check, with the driver chosen above",
                v -> { if (!mBusy && mNativeOk) checkDevice(); });
        button("Performance log of the last session", "Frame stalls, memory, swap and "
                + "temperature through the last game session — for blackouts and slowdowns",
                v -> showText("Performance log", Fs.tail(PerfWatchdog.file(this), 64 * 1024)));
        button("Advanced: environment", "KEY=VALUE switches for the runtime (" + BuildConfig.ENV_PREFIX
                + "_*), for debugging", v -> advanced());
        button("Regenerate overlays", "Rebuild the patched menu and prompt assets from the game data",
                v -> { if (!mBusy && mNativeOk)
                    runBusy("Overlays regenerated.", ui -> Importer.generateOverlays(this, ui)); });
        button("Reset graphics settings", "Back to the Balanced preset",
                v -> {
                    RuntimeSettings r = RuntimeSettings.load(this);
                    RuntimeSettings.Options.applyPreset(this, r, RuntimeSettings.Options.PRESET_BALANCED);
                    r.save();
                    Toast.makeText(this, "Graphics reset to Balanced", Toast.LENGTH_SHORT).show();
                });
        note("Data folder (reachable over USB): " + GameEnv.root(this).getAbsolutePath()
                + "\nSaves: " + GameEnv.saves(this).getAbsolutePath()
                + "\nUninstalling asks whether to keep this data.");
    }

    /**
     * The choice reaches the native side through the environment, read once per
     * process: the launcher's own process is restarted so CHECK THIS DEVICE reports the
     * driver just chosen.
     */
    private void chooseDriver() {
        java.util.List<String> keys = new java.util.ArrayList<>(java.util.Arrays.asList("auto", "mesa", "r6", "system"));
        java.util.List<String> names = new java.util.ArrayList<>(java.util.Arrays.asList(
                "Auto (" + GameEnv.label(GameEnv.adreno() ? "mesa" : "system") + ")",
                GameEnv.label("mesa"), GameEnv.label("r6"), "System driver"));
        for (CustomDrivers.Driver d : CustomDrivers.list(this)) {
            keys.add(CustomDrivers.PREFIX + d.id);
            names.add("Custom: " + d.label());
        }
        int cur = keys.indexOf(GameEnv.driverPref(this));
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("GPU driver")
                .setSingleChoiceItems(names.toArray(new String[0]), cur, (d, which) -> {
                    d.dismiss();
                    if (which != cur) selectDriver(keys.get(which));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * The choice reaches the native side through the environment, read once per
     * process: the launcher's own process is restarted so CHECK THIS DEVICE (and the
     * automatic check) use the driver just chosen.
     */
    private void selectDriver(String key) {
        GameEnv.prefs(this).edit().putString(GameEnv.PREF_DRIVER, key).commit();
        Intent i = new Intent(this, LauncherActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        Runtime.getRuntime().exit(0);
    }

    private void removeDriver() {
        java.util.List<CustomDrivers.Driver> list = CustomDrivers.list(this);
        if (list.isEmpty()) {
            Toast.makeText(this, "No custom drivers imported", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) names[i] = list.get(i).label();
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Remove a custom driver")
                .setItems(names, (d, which) -> {
                    CustomDrivers.Driver drv = list.get(which);
                    boolean inUse = GameEnv.driverPref(this).equals(CustomDrivers.PREFIX + drv.id);
                    CustomDrivers.remove(this, drv.id);
                    if (inUse) selectDriver("auto");
                    else showTab(mTab);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- actions --------------------------------------------------------------------

    private void play() {
        startActivity(new Intent(this, GameActivity.class));
    }

    private void pick(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        // Prepared assets may come in parts; they are picked together.
        if (req == REQ_ASSETS) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, req);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == REQ_EXPORT_SAVES && data.getData() != null) {
            Uri dest = data.getData();
            runBusy(null, ui -> {
                ui.status("Exporting to Xenia…");
                XeniaExport.Result r = XeniaExport.export(this, dest);
                runOnUiThread(() -> showText("Exported to Xenia", r.saves + (r.saves == 1 ? " save" : " saves")
                        + " and " + r.unlocked + " of " + r.achievements + " achievements exported.\n\n"
                        + XeniaExport.instructions()));
            });
            return;
        }
        if (req == REQ_IMPORT_DRIVER && data.getData() != null) {
            Uri src = data.getData();
            runBusy(null, ui -> {
                ui.status("Importing the driver package…");
                CustomDrivers.Driver d = CustomDrivers.importZip(this, src);
                runOnUiThread(() -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Driver imported")
                        .setMessage(d.label() + (d.vendor.isEmpty() ? "" : "\nVendor: " + d.vendor)
                                + (d.description.isEmpty() ? "" : "\n\n" + d.description)
                                + "\n\nUse it now? The launcher restarts, then checks this phone with it.")
                        .setPositiveButton("Use it", (dd, w) -> selectDriver(CustomDrivers.PREFIX + d.id))
                        .setNegativeButton("Later", (dd, w) -> showTab(mTab))
                        .show());
            });
            return;
        }
        if (req == REQ_IMPORT_SAVES && data.getData() != null) {
            Uri src = data.getData();
            runBusy(null, ui -> {
                ui.status("Importing saves…");
                int n = Progress.importZip(this, src);
                runOnUiThread(() -> {
                    Toast.makeText(this, n + (n == 1 ? " save" : " saves") + " imported", Toast.LENGTH_LONG).show();
                    if (mTab == 0) showTab(0);
                });
            });
            return;
        }
        java.util.List<Uri> uris = new java.util.ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++)
                uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        runBusy(req == REQ_PACKAGE ? "Game package imported." : "Prepared assets imported.", ui -> {
            if (req == REQ_PACKAGE) Importer.importPackage(this, uris.get(0), ui);
            else Importer.importPreparedAssets(this, uris, ui);
        });
    }

    /** The runtime's own --diag: OS, GPU, driver, and whether the renderer CAN run here. */
    private void checkDevice() {
        runBusy(null, ui -> {
            ui.status("Checking Vulkan support…");
            Compat.run(this);
            String out = Fs.tail(GameEnv.toolLog(this), 64 * 1024);
            runOnUiThread(() -> showText("Device check", out));
        });
    }

    private void advanced() {
        EditText env = new EditText(this);
        env.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        env.setTypeface(Typeface.MONOSPACE);
        env.setGravity(Gravity.TOP);
        env.setMinLines(6);
        env.setHint("KEY=VALUE, one per line — passed to the game process\n"
                + "(the upstream runtime's " + BuildConfig.ENV_PREFIX + "_* switches; see its docs)");
        env.setText(GameEnv.prefs(this).getString(GameEnv.PREF_EXTRA_ENV, ""));
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(16), dp(8), dp(16), 0);
        wrap.addView(env);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Advanced: environment")
                .setView(wrap)
                .setPositiveButton("Save", (d, w) -> GameEnv.prefs(this).edit()
                        .putString(GameEnv.PREF_EXTRA_ENV, env.getText().toString()).apply())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showText(String title, String text) {
        TextView tv = new TextView(this);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(11);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(16), dp(8), dp(16), dp(8));
        tv.setText(text);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("Close", null)
                .setNeutralButton("Copy", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText(title, text));
                    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
                })
                .show();
        sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
    }

    // ---- background work -------------------------------------------------------------

    private interface Job { void run(Importer.Ui ui) throws Exception; }

    private void runBusy(String doneMessage, Job job) {
        mBusy = true;
        refresh();
        mProgress.setVisibility(View.VISIBLE);
        mProgressText.setVisibility(View.VISIBLE);
        mProgress.setIndeterminate(true);
        Importer.Ui ui = new Importer.Ui() {
            @Override public void status(String text) {
                runOnUiThread(() -> mProgressText.setText(text));
            }
            @Override public void progress(long done, long total) {
                runOnUiThread(() -> {
                    if (total <= 0) {
                        mProgress.setIndeterminate(true);
                    } else {
                        mProgress.setIndeterminate(false);
                        mProgress.setMax(1000);
                        mProgress.setProgress((int) (1000 * done / total));
                    }
                });
            }
        };
        mWorker.execute(() -> {
            String error = null;
            try {
                job.run(ui);
            } catch (Importer.Failure f) {
                error = f.getMessage();
            } catch (Throwable t) {
                error = t.toString();
            }
            final String err = error;
            runOnUiThread(() -> {
                mBusy = false;
                mProgress.setVisibility(View.GONE);
                mProgressText.setVisibility(View.GONE);
                refresh();
                loadArt();   // an import may have just brought the game's artwork
                if (err != null) showText("That did not work", err);
                else if (doneMessage != null) Toast.makeText(this, doneMessage, Toast.LENGTH_LONG).show();
            });
        });
    }
}
