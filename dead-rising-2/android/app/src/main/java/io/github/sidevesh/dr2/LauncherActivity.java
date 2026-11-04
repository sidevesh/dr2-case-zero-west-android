package io.github.sidevesh.dr2;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The app's front door, standing in for upstream's SDL-drawn desktop launcher: it
 * gets the game data onto the phone, says plainly what is still missing, and starts
 * the game. Everything heavy runs the upstream runtime's own tool modes in this
 * process (NativeBridge.runTool); the game itself runs in GameActivity's process.
 */
public class LauncherActivity extends Activity {
    private static final int REQ_PACKAGE = 1, REQ_ASSETS = 2;

    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
    private boolean mBusy;
    private boolean mNativeOk;
    private String mNativeError;

    private TextView mStatus, mProgressText;
    private ProgressBar mProgress;
    private Button mPlay, mImportPackage, mImportAssets, mCheck, mLog, mAdvanced, mDriver;

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
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        // An APK built with --bundle carries the game itself: install it the first
        // time (and after an update that changed it), with nothing for the player to pick.
        if (!mBusy && Importer.bundlePending(this)) {
            runBusy("Game data installed — ready to play.", ui -> Importer.installBundled(this, ui));
        }
    }

    // ---- UI ----------------------------------------------------------------------

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private Button button(LinearLayout parent, String label, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(true);
        btn.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        parent.addView(btn, lp);
        return btn;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(r);
        return r;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(16), dp(20), dp(16));
        scroll.addView(col);

        TextView title = new TextView(this);
        title.setText(BuildConfig.GAME_TITLE);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Native ARM64 port (static recompilation) — unofficial. Bring your own copy of the game.");
        sub.setAlpha(0.7f);
        col.addView(sub);

        mStatus = new TextView(this);
        mStatus.setTypeface(Typeface.MONOSPACE);
        mStatus.setTextSize(13);
        mStatus.setPadding(0, dp(12), 0, dp(8));
        col.addView(mStatus);

        mProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mProgress.setVisibility(View.GONE);
        col.addView(mProgress);
        mProgressText = new TextView(this);
        mProgressText.setVisibility(View.GONE);
        col.addView(mProgressText);

        LinearLayout r1 = row(col);
        mPlay = button(r1, "Play", v -> play());
        LinearLayout r2 = row(col);
        mImportPackage = button(r2, "Import game package", v -> pick(REQ_PACKAGE, "*/*"));
        mImportAssets = button(r2, "Import prepared assets", v -> pick(REQ_ASSETS, "*/*"));
        LinearLayout r3 = row(col);
        mCheck = button(r3, "Check this device", v -> checkDevice());
        mLog = button(r3, "Last game log", v -> showText("Last game log",
                Fs.tail(GameEnv.runtimeLog(this), 64 * 1024)));
        mAdvanced = button(r3, "Advanced", v -> advanced());

        LinearLayout r4 = row(col);
        mDriver = button(r4, "", v -> cycleDriver());

        CheckBox touch = new CheckBox(this);
        touch.setText("On-screen controls (hidden automatically when a controller is connected)");
        touch.setChecked(GameEnv.prefs(this).getBoolean(GameEnv.PREF_TOUCH, true));
        touch.setOnCheckedChangeListener((cb, on) ->
                GameEnv.prefs(this).edit().putBoolean(GameEnv.PREF_TOUCH, on).apply());
        col.addView(touch);

        TextView where = new TextView(this);
        where.setAlpha(0.6f);
        where.setTextSize(12);
        where.setPadding(0, dp(12), 0, 0);
        where.setText("Data folder (reachable over USB): " + GameEnv.root(this).getAbsolutePath()
                + "\nSaves: " + GameEnv.saves(this).getAbsolutePath()
                + "\nUninstalling asks whether to keep this data.");
        col.addView(where);

        setContentView(scroll);
    }

    private void refresh() {
        StringBuilder s = new StringBuilder();
        boolean game = GameEnv.gameUnpacked(this);
        int shaders = GameEnv.shaderModules(this);
        if (!mNativeOk) {
            s.append("✗ The native runtime failed to load:\n  ").append(mNativeError).append("\n");
        } else if (BuildConfig.NATIVE_STUB) {
            s.append("✗ This APK was built WITHOUT the recompiled game (a CI / stub build).\n")
             .append("  It can import and check data, but cannot play. Build your own APK\n")
             .append("  from your copy of the game — see the README.\n");
        }
        if (Importer.bundleHeader(this) != null)
            s.append(Importer.bundlePending(this) ? "• This app carries the game: installing it…\n"
                                                  : "✓ Game data installed from this app\n");
        s.append(game ? "✓ Game data unpacked\n"
                      : "✗ Game data missing — IMPORT GAME PACKAGE (the XBLA file, " + BuildConfig.PACKAGE_SIZE
                        + ",\n  from Content/0000000000000000/" + BuildConfig.TITLE_ID + "/000D0000/ on the console)\n");
        if (game) s.append(GameEnv.overlaysGenerated(this) ? "✓ Menu/prompt overlays generated\n"
                                                           : "• Overlays not generated (the game makes them at start)\n");
        s.append(shaders > 0 ? "✓ Shader cache: " + shaders + " modules\n"
                             : "✗ Shader cache missing — IMPORT GAME PACKAGE builds it on\n"
                               + "  the phone (a few minutes, once), or import one made on a\n"
                               + "  PC with scripts/prepare-assets.sh\n");
        mStatus.setText(s.toString());
        boolean idle = !mBusy;
        mPlay.setEnabled(idle && mNativeOk && !BuildConfig.NATIVE_STUB && game && shaders > 0);
        mImportPackage.setEnabled(idle && mNativeOk);
        mImportAssets.setEnabled(idle);
        mCheck.setEnabled(idle && mNativeOk);
        mAdvanced.setEnabled(idle);
        mDriver.setEnabled(idle);
        String pref = GameEnv.driverPref(this), eff = GameEnv.driver(this);
        mDriver.setText("GPU driver: " + (pref.equals("auto")
                ? "Auto (" + GameEnv.label(eff) + ")" : GameEnv.label(pref)));
    }

    /**
     * Auto -> Turnip R6 -> Turnip Mesa -> System -> Auto. The choice reaches the native
     * side through the environment, read once per process: the launcher's own process
     * is restarted so CHECK THIS DEVICE reports the driver just chosen.
     */
    private void cycleDriver() {
        String next = switch (GameEnv.driverPref(this)) {
            case "auto" -> "r6";
            case "r6" -> "mesa";
            case "mesa" -> "system";
            default -> "auto";
        };
        GameEnv.prefs(this).edit().putString(GameEnv.PREF_DRIVER, next).commit();
        Intent i = new Intent(this, LauncherActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        Runtime.getRuntime().exit(0);
    }

    // ---- actions --------------------------------------------------------------------

    private void play() {
        startActivity(new Intent(this, GameActivity.class));
    }

    private void pick(int req, String mime) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        // Prepared assets may come in parts; they are picked together.
        if (req == REQ_ASSETS) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, req);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
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
            NativeBridge.runTool(new String[] { "--diag" }, GameEnv.toolLog(this).getAbsolutePath());
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
        new AlertDialog.Builder(this)
                .setTitle("Advanced: environment")
                .setView(env)
                .setPositiveButton("Save", (d, w) -> GameEnv.prefs(this).edit()
                        .putString(GameEnv.PREF_EXTRA_ENV, env.getText().toString()).apply())
                .setNeutralButton("Regenerate overlays", (d, w) ->
                        runBusy("Overlays regenerated.", ui -> Importer.generateOverlays(this, ui)))
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
        new AlertDialog.Builder(this)
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
                if (err != null) showText("That did not work", err);
                else if (doneMessage != null) Toast.makeText(this, doneMessage, Toast.LENGTH_LONG).show();
            });
        });
    }
}
