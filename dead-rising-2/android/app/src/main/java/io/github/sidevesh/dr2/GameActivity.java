package io.github.sidevesh.dr2;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.RelativeLayout;

import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLSurface;

/**
 * The game: SDL's activity running the upstream runtime (libmain.so) in its own
 * process (":game", see the manifest). When the runtime's main() returns, the native
 * side ends this process and the player is back in the launcher.
 */
public class GameActivity extends SDLActivity {
    private TouchControlsView mTouch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Before super.onCreate: that loads libmain.so, and the runtime reads its
        // paths from the environment the moment it starts.
        GameEnv.apply(this);
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemBars();
        maybeAddTouchControls();
    }

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
        if (mLayout == null) return;
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
        if (mTouch != null) mTouch.detach();
        super.onDestroy();
    }
}
