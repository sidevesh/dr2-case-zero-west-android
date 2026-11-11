package io.github.sidevesh.dr2;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.RelativeLayout;
import android.widget.TextView;

import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLSurface;

/**
 * The game: SDL's activity running the upstream runtime (libmain.so) in its own
 * process (":game", see the manifest). When the runtime's main() returns, the native
 * side ends this process and the player is back in the launcher.
 */
public class GameActivity extends SDLActivity {
    private TouchControlsView mTouch;
    private TextView mFps;
    private InGameMenu mMenu;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private long mFpsLastCount = -1, mFpsLastTime;
    /** The flight recorder (PerfWatchdog); static so GameSurface can report surface edges. */
    static PerfWatchdog sWatchdog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Before super.onCreate: that loads libmain.so, and the runtime reads its
        // paths from the environment the moment it starts.
        GameEnv.apply(this);
        super.onCreate(savedInstanceState);
        // libmain.so is loaded now: choose the Vulkan driver before this activity draws
        // anything (the manifest also keeps this process's UI off the GPU).
        try {
            NativeBridge.initVulkan();
        } catch (UnsatisfiedLinkError ignored) {
            // A stub or failed native load: SDL shows its own error.
        }
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Sustained performance: clocks the device can hold indefinitely, instead of a
        // fast first few minutes and then heavy throttling. Not every phone has it.
        if (GameEnv.prefs(this).getBoolean(GameEnv.PREF_SUSTAINED, false)) {
            android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
            if (pm != null && pm.isSustainedPerformanceModeSupported())
                getWindow().setSustainedPerformanceMode(true);
        }
        hideSystemBars();
        maybeAddTouchControls();
        maybeAddFpsCounter();
        mMenu = new InGameMenu(this);
        mMenu.attach(mLayout);
        sWatchdog = new PerfWatchdog(this);
        sWatchdog.start();
    }

    @Override
    protected void onPause() {
        if (sWatchdog != null) sWatchdog.note("activity paused");
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sWatchdog != null) sWatchdog.note("activity resumed");
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (sWatchdog != null) sWatchdog.note("Android asked to trim memory (level " + level + ")");
    }

    /**
     * SDL asks for an orientation from the window's shape whenever the runtime creates
     * or resizes its window, and for a resizable window that is "any" — which let the
     * game start in portrait. A console game on a phone is landscape, either way up.
     */
    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    /** SHOW FPS: frames actually presented (native counter), twice a second. */
    private void maybeAddFpsCounter() {
        if (mLayout == null) return;
        mFps = new TextView(this);
        mFps.setTextColor(Color.WHITE);
        mFps.setTextSize(12);
        mFps.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        mFps.setShadowLayer(3f, 0f, 0f, Color.BLACK);
        int pad = Math.round(6 * getResources().getDisplayMetrics().density);
        mFps.setPadding(pad * 2, pad, pad, pad);
        mFps.setGravity(Gravity.START);
        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
        lp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        lp.addRule(RelativeLayout.ALIGN_PARENT_START);
        mLayout.addView(mFps, lp);
        setFpsVisible(GameEnv.prefs(this).getBoolean(GameEnv.PREF_SHOW_FPS, false));
    }

    // ---- live settings (InGameMenu) ------------------------------------------------------

    ViewGroup gameLayout() { return mLayout; }

    void setFpsVisible(boolean on) {
        if (mFps == null) return;
        mHandler.removeCallbacks(mFpsTick);
        mFps.setVisibility(on ? View.VISIBLE : View.GONE);
        mFpsLastCount = -1;
        mFps.setText("");
        if (on) mHandler.post(mFpsTick);
    }

    void setSustained(boolean on) {
        android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
        if (pm != null && pm.isSustainedPerformanceModeSupported())
            getWindow().setSustainedPerformanceMode(on);
    }

    /** Shown or hidden from the menu — created on demand, even with a controller connected. */
    void setTouchVisible(boolean on) {
        if (on && mTouch == null) addTouchControls();
        if (mTouch != null) mTouch.setVisibility(on ? View.VISIBLE : View.GONE);
        if (mMenu != null && mTouch != null) bringMenuButtonToFront();
    }

    void reloadTouch() {
        if (mTouch != null) mTouch.reload();
    }

    boolean hasTouchControls() {
        return mTouch != null && mTouch.getVisibility() == View.VISIBLE;
    }

    void tapStart() {
        if (mTouch != null) mTouch.tapStart();
    }

    void onMenuClosed() {
        hideSystemBars();
    }

    private void bringMenuButtonToFront() {
        // The touch view was added after the button: re-attach the button above it.
        for (int i = 0; i < mLayout.getChildCount(); i++) {
            View v = mLayout.getChildAt(i);
            if (v instanceof TextView && "≡".contentEquals(((TextView) v).getText())) {
                v.bringToFront();
                break;
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        // BACK closes the in-game menu rather than reaching the game.
        if (mMenu != null && mMenu.isOpen() && event.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == android.view.KeyEvent.ACTION_UP) mMenu.close();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private final Runnable mFpsTick = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            long n;
            try {
                n = NativeBridge.presentCount();
            } catch (UnsatisfiedLinkError e) {
                return;
            }
            if (mFpsLastCount >= 0 && now > mFpsLastTime) {
                double fps = (n - mFpsLastCount) * 1000.0 / (now - mFpsLastTime);
                mFps.setText(String.format(java.util.Locale.US, "%.0f FPS", fps));
            }
            mFpsLastCount = n;
            mFpsLastTime = now;
            mHandler.postDelayed(this, 500);
        }
    };

    @Override
    protected String[] getLibraries() {
        // libmain.so's other dependencies (libavcodec, libavutil) load implicitly.
        return new String[] { "c++_shared", "SDL2", "main" };
    }

    @Override
    protected SDLSurface createSDLSurface(Context context) {
        return new GameSurface(context);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // SDL re-applies its own window style when the runtime's window changes mode;
        // a phone game is always edge to edge.
        if (hasFocus) hideSystemBars();
    }

    private void hideSystemBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c == null) return;
        c.hide(WindowInsets.Type.systemBars());
        c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    /**
     * The on-screen controller: on when the player has not turned it off, and only
     * when no physical gamepad is connected at launch — the runtime drives ONE pad,
     * and a real controller is the better one.
     */
    private void maybeAddTouchControls() {
        if (!GameEnv.prefs(this).getBoolean(GameEnv.PREF_TOUCH, true)) return;
        if (physicalGamepadPresent()) return;
        addTouchControls();
    }

    private void addTouchControls() {
        if (mLayout == null || mTouch != null) return;
        mTouch = new TouchControlsView(this);
        mLayout.addView(mTouch, new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
    }

    private boolean physicalGamepadPresent() {
        InputManager im = (InputManager) getSystemService(Context.INPUT_SERVICE);
        for (int id : im.getInputDeviceIds()) {
            InputDevice d = im.getInputDevice(id);
            if (d == null || d.isVirtual()) continue;
            int s = d.getSources();
            if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
                return true;
        }
        return false;
    }

    @Override
    protected void onDestroy() {
        if (sWatchdog != null) { sWatchdog.stop(); sWatchdog = null; }
        mHandler.removeCallbacks(mFpsTick);
        if (mTouch != null) mTouch.detach();
        super.onDestroy();
    }
}
