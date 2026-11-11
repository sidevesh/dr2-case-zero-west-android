package io.github.sidevesh.dr2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Surface;
import android.view.DisplayCutout;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;

import org.libsdl.app.SDLControllerManager;

import java.util.ArrayList;
import java.util.List;

/**
 * An on-screen Xbox-layout controller that reaches the game as an ordinary SDL
 * gamepad. It registers a virtual joystick through SDL's own Java entry points
 * (SDLControllerManager.nativeAddJoystick and friends) with every standard button and
 * six axes; SDL's Android backend then builds a GameController mapping for it from
 * those capability bits (SDL_CreateMappingForAndroidController: buttons b0..b14 in
 * SDL_CONTROLLER_BUTTON order, axes a0..a5 = LX LY RX RY LT RT). The runtime sees a
 * controller being plugged in and uses it like any other — no native code involved.
 *
 * Layout, for a landscape screen of height h (u = h / 9):
 *   left half    the move stick, floating: it centres where the thumb lands
 *   right half   the camera stick, floating, wherever no button is
 *   bottom right A B X Y          top corners LT LB / RB RT
 *   top centre   BACK  START      bottom centre-left D-pad, bottom centre L3 R3
 *
 * Drawn to stay out of the way: thin outlines over a faint dark fill, the face
 * letters in the controller's colours, the whole overlay dimming further a few
 * seconds after the last touch. Opacity, size and haptics are the player's
 * (launcher: SETTINGS > CONTROLS). Latency: touches are dispatched unbuffered (not
 * batched to the display's frame), and the view redraws only when what it shows
 * changed.
 *
 * AUTO-MASH (CONTROLS, or the in-game menu): a held A/B/X/Y repeats itself about 15
 * times a second — for the button-mash escapes from a zombie's grab.
 * RUN LOCK: the RUN button above the move stick holds the stick at full deflection in
 * the direction last moved (forward if none); steer with the stick as usual, tap RUN
 * again to stop. Settings are re-read live (reload()) when the in-game menu changes them.
 *
 * GYRO AIMING: the phone's rotation rate is added to the camera (right) stick —
 * always, or only while the aim button is held (launcher: CONTROLS > GYRO). It is
 * velocity-as-stick, the way a game without native gyro support has to take it: turn
 * the phone and the camera turns at a speed that follows how fast you turn it, with a
 * small anti-deadzone so slow corrections clear the game's own stick deadzone.
 */
public class TouchControlsView extends View implements SensorEventListener {
    // Not a real InputDevice id (those are small positive integers).
    private static final int DEVICE_ID = 0x0D2C7001;

    // SDL_GameControllerButton indices.
    private static final int A = 0, B = 1, X = 2, Y = 3, BACK = 4, GUIDE = 5, START = 6,
            LSTICK = 7, RSTICK = 8, LSHOULDER = 9, RSHOULDER = 10,
            DUP = 11, DDOWN = 12, DLEFT = 13, DRIGHT = 14;
    private static final int BUTTON_MASK = ((1 << 15) - 1) & ~(1 << GUIDE);
    private static final int AXIS_MASK = 0x3F;   // LX LY RX RY LT RT
    private static final int AX_LT = 4, AX_RT = 5;

    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFillDown = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStrokeDown = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFace = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mTmp = new RectF();

    private boolean mAttached;
    private float u;
    private float mOpacity, mScale;
    private boolean mHaptics, mAutoMash, mRunButton;
    private static final long DIM_AFTER_MS = 4000;

    // Gyro (see the class comment).
    private String mGyroMode;      // off | aim | always
    private String mGyroButton;    // LT | RT | LB | RB: the button that aims
    private float mGyroSens;       // stick units per rad/s
    private boolean mGyroInvertX, mGyroInvertY;
    private SensorManager mSensors;
    private float mTouchRX, mTouchRY;    // the camera stick's own (touch) deflection
    private float mGyroX, mGyroY;        // the gyro's, already filtered and scaled
    private static final float GYRO_ANTI_DEADZONE = 0.18f;
    private static final float GYRO_NOISE = 0.03f;    // rad/s (~1.7 deg/s) treated as still
    private static final float DIM_TO = 0.45f;

    /** A button: a round or rectangular hit area that sends a key or drives a trigger axis. */
    private static final class Btn {
        final String label;
        final int keycode;       // Android keycode SDL translates, or 0 for a trigger
        final int triggerAxis;   // AX_LT / AX_RT, or -1
        final boolean round;
        int faceColor;           // 0 = the plain label colour
        final RectF r = new RectF();
        int pointer = -1;
        Btn(String label, int keycode, int triggerAxis, boolean round) {
            this.label = label; this.keycode = keycode; this.triggerAxis = triggerAxis; this.round = round;
        }
        boolean hit(float x, float y) {
            if (!round) return r.contains(x, y);
            float dx = x - r.centerX(), dy = y - r.centerY(), rad = r.width() / 2f * 1.15f;
            return dx * dx + dy * dy <= rad * rad;
        }
    }

    /** A floating stick: origin where the thumb lands, deflection up to `radius`. */
    private static final class Stick {
        final int axisX, axisY;
        float homeX, homeY, ox, oy, x, y, radius;
        int pointer = -1;
        Stick(int ax, int ay) { axisX = ax; axisY = ay; }
    }

    private final List<Btn> mButtons = new ArrayList<>();
    private final Stick mLeft = new Stick(0, 1);
    private final Stick mRight = new Stick(2, 3);
    private final Stick[] mSticks = { mLeft, mRight };
    // The D-pad: one control so a thumb can roll between directions.
    private final RectF mDpad = new RectF();
    private int mDpadPointer = -1;
    private int mDpadState;   // bit 0 up, 1 down, 2 left, 3 right

    private void loadPrefs() {
        android.content.SharedPreferences sp = GameEnv.prefs(getContext());
        mOpacity = Math.max(0.2f, Math.min(1f, sp.getInt(GameEnv.PREF_TOUCH_OPACITY, 60) / 100f));
        mScale = Math.max(0.7f, Math.min(1.5f, sp.getInt(GameEnv.PREF_TOUCH_SCALE, 100) / 100f));
        mHaptics = sp.getBoolean(GameEnv.PREF_HAPTICS, true);
        mAutoMash = sp.getBoolean(GameEnv.PREF_AUTOMASH, false);
        mRunButton = sp.getBoolean(GameEnv.PREF_RUN_BUTTON, true);
        mGyroMode = sp.getString(GameEnv.PREF_GYRO_MODE, "aim");
        mGyroButton = sp.getString(GameEnv.PREF_GYRO_BUTTON, "LT");
        mGyroSens = 0.55f * Math.max(0.25f, Math.min(4f, sp.getInt(GameEnv.PREF_GYRO_SENS, 100) / 100f));
        mGyroInvertX = sp.getBoolean(GameEnv.PREF_GYRO_INVERT_X, false);
        mGyroInvertY = sp.getBoolean(GameEnv.PREF_GYRO_INVERT_Y, false);
    }

    /** Re-read every setting (the in-game menu changed one) and apply it now. */
    void reload() {
        loadPrefs();
        animate().cancel();
        setAlpha(mOpacity);
        if (!mRunButton && mRunLock) setRunLock(false);
        if (!mAutoMash) stopMash();
        unregisterGyro();
        if (isAttachedToWindow()) registerGyro();
        mGyroX = mGyroY = 0f;
        if (mAttached) pushRight();
        layoutControls(getWidth(), getHeight());
        wake();
    }

    public TouchControlsView(Context c) {
        super(c);
        loadPrefs();
        mFill.setColor(Color.argb(0x30, 0, 0, 0));
        mFillDown.setColor(Color.argb(0x55, 0xFF, 0xFF, 0xFF));
        mStroke.setColor(Color.argb(0x8C, 0xFF, 0xFF, 0xFF));
        mStroke.setStyle(Paint.Style.STROKE);
        mStrokeDown.setColor(Color.argb(0xE0, 0xFF, 0xFF, 0xFF));
        mStrokeDown.setStyle(Paint.Style.STROKE);
        mText.setColor(Color.argb(0xC8, 0xFF, 0xFF, 0xFF));
        mText.setTextAlign(Paint.Align.CENTER);
        mText.setFakeBoldText(true);
        mFace.setTextAlign(Paint.Align.CENTER);
        mFace.setFakeBoldText(true);
        setAlpha(mOpacity);
        mButtons.add(new Btn("A", KeyEvent.KEYCODE_BUTTON_A, -1, true));
        mButtons.add(new Btn("B", KeyEvent.KEYCODE_BUTTON_B, -1, true));
        mButtons.add(new Btn("X", KeyEvent.KEYCODE_BUTTON_X, -1, true));
        mButtons.add(new Btn("Y", KeyEvent.KEYCODE_BUTTON_Y, -1, true));
        mButtons.add(new Btn("LT", 0, AX_LT, false));
        mButtons.add(new Btn("LB", KeyEvent.KEYCODE_BUTTON_L1, -1, false));
        mButtons.add(new Btn("RB", KeyEvent.KEYCODE_BUTTON_R1, -1, false));
        mButtons.add(new Btn("RT", 0, AX_RT, false));
        mButtons.add(new Btn("BACK", KeyEvent.KEYCODE_BUTTON_SELECT, -1, false));
        mButtons.add(new Btn("START", KeyEvent.KEYCODE_BUTTON_START, -1, false));
        mButtons.add(new Btn("L3", KeyEvent.KEYCODE_BUTTON_THUMBL, -1, true));
        mButtons.add(new Btn("R3", KeyEvent.KEYCODE_BUTTON_THUMBR, -1, true));
        mButtons.add(new Btn("RUN", 0, -1, true));   // the run lock, not a pad button
        btn("A").faceColor = 0xFF7DD35A;
        btn("B").faceColor = 0xFFF0604F;
        btn("X").faceColor = 0xFF4FA3F0;
        btn("Y").faceColor = 0xFFF2CF4A;
        postDelayed(mDim, DIM_AFTER_MS);
        // Register the pad once the game has had time to start SDL; a touch before
        // then registers it too (attach() is idempotent).
        postDelayed(this::attach, 2000);
    }

    private void attach() {
        if (mAttached) return;
        SDLControllerManager.nativeAddJoystick(DEVICE_ID, "DR2 Touch Controls", "dr2-touch-controls",
                0, 0, false, BUTTON_MASK, 6, AXIS_MASK, 0, 0);
        // Triggers use the full axis range: -1 is released. Left at 0 they read half-pressed.
        SDLControllerManager.onNativeJoy(DEVICE_ID, AX_LT, -1f);
        SDLControllerManager.onNativeJoy(DEVICE_ID, AX_RT, -1f);
        mAttached = true;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        registerGyro();
    }

    private void registerGyro() {
        if (mSensors != null || mGyroMode.equals("off")) return;
        mSensors = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
        Sensor g = mSensors != null ? mSensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) : null;
        if (g != null) mSensors.registerListener(this, g, 10_000);   // 100 Hz
        else mSensors = null;
    }

    private void unregisterGyro() {
        if (mSensors != null) mSensors.unregisterListener(this);
        mSensors = null;
    }

    @Override
    protected void onDetachedFromWindow() {
        unregisterGyro();
        stopMash();
        super.onDetachedFromWindow();
    }

    private boolean gyroActive() {
        if (mGyroMode.equals("always")) return true;
        if (!mGyroMode.equals("aim")) return false;
        for (Btn b : mButtons) if (b.label.equals(mGyroButton)) return b.pointer >= 0;
        return false;
    }

    @Override
    public void onSensorChanged(SensorEvent ev) {
        if (!mAttached) return;
        if (!gyroActive()) {
            if (mGyroX != 0f || mGyroY != 0f) { mGyroX = mGyroY = 0f; pushRight(); }
            return;
        }
        // Device axes -> screen axes for a landscape hold. Yaw (turning left/right) is
        // rotation about the device's X axis, pitch about its Y; which way is which
        // depends on which way up the phone is (ROTATION_90 vs ROTATION_270). Signs
        // chosen so the camera follows the phone like a viewfinder.
        int rot = getDisplay() != null ? getDisplay().getRotation() : Surface.ROTATION_90;
        float yaw = ev.values[0], pitch = ev.values[1];
        float sx, sy;
        if (rot == Surface.ROTATION_270) { sx = yaw; sy = -pitch; }
        else { sx = -yaw; sy = pitch; }
        if (mGyroInvertX) sx = -sx;
        if (mGyroInvertY) sy = -sy;
        mGyroX = shape(sx * mGyroSens, Math.abs(yaw));
        mGyroY = shape(sy * mGyroSens, Math.abs(pitch));
        pushRight();
    }

    /** Noise floor, then an anti-deadzone so slow aiming clears the game's stick deadzone. */
    private static float shape(float v, float rate) {
        if (rate < GYRO_NOISE || v == 0f) return 0f;
        // The anti-deadzone fades in over the first few degrees a second, so the
        // tremor of a hand holding still does not jitter the camera.
        float ramp = Math.min(1f, (rate - GYRO_NOISE) / 0.05f);
        float m = Math.min(1f, Math.abs(v));
        return Math.signum(v) * Math.min(1f, GYRO_ANTI_DEADZONE * ramp + (1f - GYRO_ANTI_DEADZONE) * m);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    /** The camera stick the game sees: the touch stick plus the gyro. */
    private void pushRight() {
        SDLControllerManager.onNativeJoy(DEVICE_ID, mRight.axisX, clamp(mTouchRX + mGyroX));
        SDLControllerManager.onNativeJoy(DEVICE_ID, mRight.axisY, clamp(mTouchRY + mGyroY));
    }

    void detach() {
        stopMash();
        if (!mAttached) return;
        SDLControllerManager.nativeRemoveJoystick(DEVICE_ID);
        mAttached = false;
    }

    private Btn btn(String label) {
        for (Btn b : mButtons) if (b.label.equals(label)) return b;
        throw new IllegalArgumentException(label);
    }

    private final Runnable mDim = new Runnable() {
        @Override public void run() {
            if (anyHeld()) { postDelayed(this, DIM_AFTER_MS); return; }
            animate().alpha(mOpacity * DIM_TO).setDuration(400).start();
        }
    };

    private boolean anyHeld() {
        if (mLeft.pointer >= 0 || mRight.pointer >= 0 || mDpadPointer >= 0) return true;
        for (Btn b : mButtons) if (b.pointer >= 0) return true;
        return false;
    }

    /** Full opacity while in use; dim again a few seconds after the last touch. */
    private void wake() {
        removeCallbacks(mDim);
        if (getAlpha() < mOpacity) {
            animate().cancel();
            setAlpha(mOpacity);
        }
        postDelayed(mDim, DIM_AFTER_MS);
    }

    private void buzz() {
        if (mHaptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private static void circle(RectF r, float cx, float cy, float rad) {
        r.set(cx - rad, cy - rad, cx + rad, cy + rad);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        layoutControls(w, h);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets in) {
        layoutControls(getWidth(), getHeight());
        return super.onApplyWindowInsets(in);
    }

    private void layoutControls(int fullW, int h) {
        if (fullW <= 0 || h <= 0) return;
        // Keep clear of a camera cutout on either short edge: lay out in the safe band.
        int left = 0, right = 0;
        WindowInsets wi = getRootWindowInsets();
        DisplayCutout cut = wi != null ? wi.getDisplayCutout() : null;
        if (cut != null) { left = cut.getSafeInsetLeft(); right = cut.getSafeInsetRight(); }
        int w = fullW - left - right;
        layoutIn(w, h);
        if (left != 0) {
            for (Btn b : mButtons) b.r.offset(left, 0);
            mDpad.offset(left, 0);
            mLeft.homeX += left; mRight.homeX += left;
        }
        invalidate();
    }

    private void layoutIn(int w, int h) {
        u = h / 9f * mScale;
        mStroke.setStrokeWidth(Math.max(1.5f, u * 0.035f));
        mStrokeDown.setStrokeWidth(Math.max(2f, u * 0.05f));
        mText.setTextSize(u * 0.34f);
        mFace.setTextSize(u * 0.52f);
        // Face buttons.
        float fx = w - 2.6f * u, fy = h - 2.6f * u, off = 1.2f * u, fr = 0.6f * u;
        circle(btn("A").r, fx, fy + off, fr);
        circle(btn("B").r, fx + off, fy, fr);
        circle(btn("X").r, fx - off, fy, fr);
        circle(btn("Y").r, fx, fy - off, fr);
        // Shoulders and triggers.
        float sw = 1.9f * u, sh = 0.95f * u, m = 0.3f * u;
        btn("LT").r.set(m, m, m + sw, m + sh);
        btn("LB").r.set(m + sw + m, m, m + 2 * sw + m, m + sh);
        btn("RT").r.set(w - m - sw, m, w - m, m + sh);
        btn("RB").r.set(w - 2 * sw - 2 * m, m, w - sw - 2 * m, m + sh);
        // Back / Start.
        float bw = 1.7f * u, bh = 0.75f * u;
        btn("BACK").r.set(w / 2f - bw - 0.3f * u, m, w / 2f - 0.3f * u, m + bh);
        btn("START").r.set(w / 2f + 0.3f * u, m, w / 2f + bw + 0.3f * u, m + bh);
        // Stick clicks and the D-pad along the bottom middle.
        circle(btn("L3").r, w / 2f - 1.3f * u, h - 1.0f * u, 0.55f * u);
        circle(btn("R3").r, w / 2f + 1.3f * u, h - 1.0f * u, 0.55f * u);
        // The run lock, above where the move stick rests.
        circle(btn("RUN").r, 2.4f * u, h - 4.75f * u, 0.5f * u);
        float dc = 1.25f * u;
        mDpad.set(w * 0.30f - dc, h - 1.2f * u - dc, w * 0.30f + dc, h - 1.2f * u + dc);
        // Stick homes (where they are drawn at rest).
        mLeft.radius = mRight.radius = 1.5f * u;
        mLeft.homeX = 2.4f * u; mLeft.homeY = h - 2.6f * u;
        mRight.homeX = w * 0.64f; mRight.homeY = h * 0.55f;
    }

    @Override
    protected void onDraw(Canvas c) {
        drawStick(c, mLeft, true);
        drawStick(c, mRight, false);
        float corner = u * 0.28f;
        for (Btn b : mButtons) {
            if (b.label.equals("RUN") && !mRunButton) continue;
            boolean down = b.label.equals("RUN") ? mRunLock : b.pointer >= 0;
            Paint fill = down ? mFillDown : mFill, stroke = down ? mStrokeDown : mStroke;
            if (b.round) {
                float rad = b.r.width() / 2f;
                c.drawCircle(b.r.centerX(), b.r.centerY(), rad, fill);
                c.drawCircle(b.r.centerX(), b.r.centerY(), rad, stroke);
            } else {
                c.drawRoundRect(b.r, corner, corner, fill);
                c.drawRoundRect(b.r, corner, corner, stroke);
            }
            Paint t = mText;
            if (b.faceColor != 0) {
                mFace.setColor(down ? Color.WHITE : b.faceColor);
                t = mFace;
            }
            float ty = b.r.centerY() - (t.descent() + t.ascent()) / 2f;
            c.drawText(b.label, b.r.centerX(), ty, t);
        }
        // D-pad: four arms.
        float cx = mDpad.centerX(), cy = mDpad.centerY(), a = mDpad.width() / 2f, t = a * 0.34f;
        drawArm(c, cx - t, cy - a, cx + t, cy - t, (mDpadState & 1) != 0);
        drawArm(c, cx - t, cy + t, cx + t, cy + a, (mDpadState & 2) != 0);
        drawArm(c, cx - a, cy - t, cx - t, cy + t, (mDpadState & 4) != 0);
        drawArm(c, cx + t, cy - t, cx + a, cy + t, (mDpadState & 8) != 0);
    }

    private void drawArm(Canvas c, float l, float t, float r, float b, boolean down) {
        mTmp.set(l, t, r, b);
        c.drawRoundRect(mTmp, u * 0.12f, u * 0.12f, down ? mFillDown : mFill);
        c.drawRoundRect(mTmp, u * 0.12f, u * 0.12f, down ? mStrokeDown : mStroke);
    }

    /**
     * A stick: a ring and a knob where the thumb is. At rest the move stick shows
     * where it sits; the camera stick (it floats over half the screen) only a faint ring.
     */
    private void drawStick(Canvas c, Stick s, boolean showAtRest) {
        boolean active = s.pointer >= 0;
        if (!active && !showAtRest) {
            c.drawCircle(s.homeX, s.homeY, s.radius * 0.45f, mStroke);
            return;
        }
        float bx = active ? s.ox : s.homeX, by = active ? s.oy : s.homeY;
        c.drawCircle(bx, by, s.radius, mFill);
        c.drawCircle(bx, by, s.radius, active ? mStrokeDown : mStroke);
        float kx = active ? s.x : bx, ky = active ? s.y : by;
        c.drawCircle(kx, ky, s.radius * 0.42f, active ? mFillDown : mFill);
        c.drawCircle(kx, ky, s.radius * 0.42f, active ? mStrokeDown : mStroke);
    }

    // ---- input ---------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        attach();
        int action = e.getActionMasked();
        mDirty = false;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                // Every move as it arrives, not batched to the next display frame:
                // the stick reaches the game up to a frame sooner.
                requestUnbufferedDispatch(e);
                wake();
                down(e.getPointerId(0), e.getX(0), e.getY(0));
                break;
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                down(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++)
                    move(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int i = e.getActionIndex();
                up(e.getPointerId(i));
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                for (int i = 0; i < e.getPointerCount(); i++) up(e.getPointerId(i));
                break;
            default:
                break;
        }
        if (action != MotionEvent.ACTION_MOVE) wake();
        if (mDirty) invalidate();
        return true;
    }

    private boolean mDirty;

    private void down(int id, float x, float y) {
        Btn run = btn("RUN");
        if (mRunButton && run.hit(x, y)) {
            setRunLock(!mRunLock);
            buzz();
            mDirty = true;
            return;
        }
        for (Btn b : mButtons) {
            if (b == run) continue;
            if (b.pointer < 0 && b.hit(x, y)) {
                b.pointer = id;
                press(b, true);
                buzz();
                mDirty = true;
                return;
            }
        }
        if (mDpadPointer < 0 && mDpad.contains(x, y)) {
            mDpadPointer = id;
            dpad(x, y);
            buzz();
            mDirty = true;
            return;
        }
        Stick s = x < getWidth() / 2f ? mLeft : mRight;
        if (s.pointer < 0) {
            s.pointer = id;
            s.ox = s.x = x;
            s.oy = s.y = y;
            stick(s);
            mDirty = true;
        }
    }

    private void move(int id, float x, float y) {
        if (id == mDpadPointer) { dpad(x, y); return; }
        for (Stick s : mSticks) {
            if (s.pointer != id) continue;
            float dx = x - s.ox, dy = y - s.oy, d = (float) Math.hypot(dx, dy);
            if (d > s.radius) {
                // Drag the origin along so reversing direction responds at once.
                float k = (d - s.radius) / d;
                s.ox += dx * k; s.oy += dy * k;
            }
            if (s.x != x || s.y != y) mDirty = true;
            s.x = x; s.y = y;
            stick(s);
        }
    }

    private void up(int id) {
        for (Btn b : mButtons) if (b.pointer == id) { b.pointer = -1; press(b, false); mDirty = true; }
        if (id == mDpadPointer) { mDpadPointer = -1; setDpad(0); mDirty = true; }
        for (Stick s : mSticks) {
            if (s.pointer == id) {
                s.pointer = -1;
                mDirty = true;
                if (s == mRight) {
                    mTouchRX = mTouchRY = 0f;
                    pushRight();
                } else if (mRunLock) {
                    pushRunDirection();   // keep running the way the stick last pointed
                } else {
                    SDLControllerManager.onNativeJoy(DEVICE_ID, s.axisX, 0f);
                    SDLControllerManager.onNativeJoy(DEVICE_ID, s.axisY, 0f);
                }
            }
        }
    }

    private void press(Btn b, boolean down) {
        if (down && mAutoMash && isFace(b)) startMash();
        if (b.triggerAxis >= 0) {
            SDLControllerManager.onNativeJoy(DEVICE_ID, b.triggerAxis, down ? 1f : -1f);
        } else if (down) {
            SDLControllerManager.onNativePadDown(DEVICE_ID, b.keycode);
        } else {
            SDLControllerManager.onNativePadUp(DEVICE_ID, b.keycode);
        }
    }

    private void stick(Stick s) {
        float vx = clamp((s.x - s.ox) / s.radius), vy = clamp((s.y - s.oy) / s.radius);
        // Android's axis convention, which SDL keeps: +Y is down.
        if (s == mRight) {
            mTouchRX = vx;
            mTouchRY = vy;
            pushRight();
            return;
        }
        float mag = (float) Math.hypot(vx, vy);
        if (mag > 0.3f) { mRunDX = vx / mag; mRunDY = vy / mag; }
        if (mRunLock) {
            // Steering while locked: full deflection, the new heading (a thumb resting
            // near the centre keeps the old one).
            pushRunDirection();
            return;
        }
        SDLControllerManager.onNativeJoy(DEVICE_ID, s.axisX, vx);
        SDLControllerManager.onNativeJoy(DEVICE_ID, s.axisY, vy);
    }

    /** The in-game menu's "Pause menu (START)": a short START press. */
    void tapStart() {
        attach();
        SDLControllerManager.onNativePadDown(DEVICE_ID, KeyEvent.KEYCODE_BUTTON_START);
        postDelayed(() -> SDLControllerManager.onNativePadUp(DEVICE_ID, KeyEvent.KEYCODE_BUTTON_START), 90);
    }

    // ---- run lock ------------------------------------------------------------------

    private boolean mRunLock;
    private float mRunDX = 0f, mRunDY = -1f;   // forward until the stick says otherwise

    private void setRunLock(boolean on) {
        attach();
        mRunLock = on;
        if (on) {
            pushRunDirection();
        } else if (mLeft.pointer < 0) {
            SDLControllerManager.onNativeJoy(DEVICE_ID, mLeft.axisX, 0f);
            SDLControllerManager.onNativeJoy(DEVICE_ID, mLeft.axisY, 0f);
        } else {
            stick(mLeft);
        }
        invalidate();
    }

    private void pushRunDirection() {
        SDLControllerManager.onNativeJoy(DEVICE_ID, mLeft.axisX, mRunDX);
        SDLControllerManager.onNativeJoy(DEVICE_ID, mLeft.axisY, mRunDY);
    }

    // ---- auto-mash -----------------------------------------------------------------

    private static final long MASH_HALF_PERIOD_MS = 33;   // ~15 presses a second
    private boolean mMashing, mMashReleased;

    private static boolean isFace(Btn b) {
        return b.keycode == KeyEvent.KEYCODE_BUTTON_A || b.keycode == KeyEvent.KEYCODE_BUTTON_B
                || b.keycode == KeyEvent.KEYCODE_BUTTON_X || b.keycode == KeyEvent.KEYCODE_BUTTON_Y;
    }

    private void startMash() {
        if (mMashing) return;
        mMashing = true;
        mMashReleased = false;
        postDelayed(mMash, MASH_HALF_PERIOD_MS * 2);
    }

    private void stopMash() {
        mMashing = false;
        removeCallbacks(mMash);
    }

    /** Alternates every held face button between released and pressed. */
    private final Runnable mMash = new Runnable() {
        @Override public void run() {
            if (!mMashing || !mAutoMash) { mMashing = false; return; }
            boolean any = false;
            mMashReleased = !mMashReleased;
            for (Btn b : mButtons) {
                if (b.pointer < 0 || !isFace(b)) continue;
                any = true;
                if (mMashReleased) SDLControllerManager.onNativePadUp(DEVICE_ID, b.keycode);
                else SDLControllerManager.onNativePadDown(DEVICE_ID, b.keycode);
            }
            if (!any) { mMashing = false; return; }
            postDelayed(this, MASH_HALF_PERIOD_MS);
        }
    };

    private void dpad(float x, float y) {
        float dx = x - mDpad.centerX(), dy = y - mDpad.centerY();
        float dead = mDpad.width() * 0.12f;
        int st = 0;
        // Eight-way: each axis counts when it is at least ~40% of the other.
        if (Math.abs(dy) > dead && Math.abs(dy) >= Math.abs(dx) * 0.4f) st |= dy < 0 ? 1 : 2;
        if (Math.abs(dx) > dead && Math.abs(dx) >= Math.abs(dy) * 0.4f) st |= dx < 0 ? 4 : 8;
        setDpad(st);
    }

    private void setDpad(int st) {
        final int[] keys = { KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT };
        if (st != mDpadState) mDirty = true;
        for (int bit = 0; bit < 4; bit++) {
            boolean was = (mDpadState & (1 << bit)) != 0, is = (st & (1 << bit)) != 0;
            if (is && !was) SDLControllerManager.onNativePadDown(DEVICE_ID, keys[bit]);
            if (!is && was) SDLControllerManager.onNativePadUp(DEVICE_ID, keys[bit]);
        }
        mDpadState = st;
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }
}
