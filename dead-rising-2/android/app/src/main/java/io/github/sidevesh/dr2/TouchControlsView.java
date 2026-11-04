package io.github.sidevesh.dr2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

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
 */
public class TouchControlsView extends View {
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
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean mAttached;
    private float u;

    /** A button: a round or rectangular hit area that sends a key or drives a trigger axis. */
    private static final class Btn {
        final String label;
        final int keycode;       // Android keycode SDL translates, or 0 for a trigger
        final int triggerAxis;   // AX_LT / AX_RT, or -1
        final boolean round;
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
    // The D-pad: one control so a thumb can roll between directions.
    private final RectF mDpad = new RectF();
    private int mDpadPointer = -1;
    private int mDpadState;   // bit 0 up, 1 down, 2 left, 3 right

    public TouchControlsView(Context c) {
        super(c);
        mFill.setColor(0x40FFFFFF);
        mFillDown.setColor(0x90FFFFFF);
        mStroke.setColor(0xB0FFFFFF);
        mStroke.setStyle(Paint.Style.STROKE);
        mText.setColor(0xE0FFFFFF);
        mText.setTextAlign(Paint.Align.CENTER);
        mText.setFakeBoldText(true);
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

    void detach() {
        if (!mAttached) return;
        SDLControllerManager.nativeRemoveJoystick(DEVICE_ID);
        mAttached = false;
    }

    private Btn btn(String label) {
        for (Btn b : mButtons) if (b.label.equals(label)) return b;
        throw new IllegalArgumentException(label);
    }

    private static void circle(RectF r, float cx, float cy, float rad) {
        r.set(cx - rad, cy - rad, cx + rad, cy + rad);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        u = h / 9f;
        mStroke.setStrokeWidth(Math.max(2f, u * 0.05f));
        mText.setTextSize(u * 0.42f);
        // Face buttons.
        float fx = w - 3.0f * u, fy = h - 3.0f * u, off = 1.25f * u, fr = 0.68f * u;
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
        float dc = 1.25f * u;
        mDpad.set(w * 0.30f - dc, h - 1.2f * u - dc, w * 0.30f + dc, h - 1.2f * u + dc);
        // Stick homes (where they are drawn at rest).
        mLeft.radius = mRight.radius = 1.6f * u;
        mLeft.homeX = 2.6f * u; mLeft.homeY = h - 2.8f * u;
        mRight.homeX = w * 0.62f; mRight.homeY = h * 0.55f;
    }

    @Override
    protected void onDraw(Canvas c) {
        drawStick(c, mLeft);
        drawStick(c, mRight);
        for (Btn b : mButtons) {
            Paint p = b.pointer >= 0 ? mFillDown : mFill;
            if (b.round) {
                float rad = b.r.width() / 2f;
                c.drawCircle(b.r.centerX(), b.r.centerY(), rad, p);
                c.drawCircle(b.r.centerX(), b.r.centerY(), rad, mStroke);
            } else {
                c.drawRoundRect(b.r, u * 0.2f, u * 0.2f, p);
                c.drawRoundRect(b.r, u * 0.2f, u * 0.2f, mStroke);
            }
            float ty = b.r.centerY() - (mText.descent() + mText.ascent()) / 2f;
            c.drawText(b.label, b.r.centerX(), ty, mText);
        }
        // D-pad: four arms.
        float cx = mDpad.centerX(), cy = mDpad.centerY(), a = mDpad.width() / 2f, t = a * 0.36f;
        drawArm(c, cx - t, cy - a, cx + t, cy - t, (mDpadState & 1) != 0);
        drawArm(c, cx - t, cy + t, cx + t, cy + a, (mDpadState & 2) != 0);
        drawArm(c, cx - a, cy - t, cx - t, cy + t, (mDpadState & 4) != 0);
        drawArm(c, cx + t, cy - t, cx + a, cy + t, (mDpadState & 8) != 0);
    }

    private void drawArm(Canvas c, float l, float t, float r, float b, boolean down) {
        RectF rf = new RectF(l, t, r, b);
        c.drawRoundRect(rf, u * 0.1f, u * 0.1f, down ? mFillDown : mFill);
        c.drawRoundRect(rf, u * 0.1f, u * 0.1f, mStroke);
    }

    private void drawStick(Canvas c, Stick s) {
        boolean active = s.pointer >= 0;
        float bx = active ? s.ox : s.homeX, by = active ? s.oy : s.homeY;
        c.drawCircle(bx, by, s.radius, mFill);
        c.drawCircle(bx, by, s.radius, mStroke);
        float kx = active ? s.x : bx, ky = active ? s.y : by;
        c.drawCircle(kx, ky, s.radius * 0.45f, active ? mFillDown : mFill);
    }

    // ---- input ---------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        attach();
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
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
        invalidate();
        return true;
    }

    private void down(int id, float x, float y) {
        for (Btn b : mButtons) {
            if (b.pointer < 0 && b.hit(x, y)) {
                b.pointer = id;
                press(b, true);
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                return;
            }
        }
        if (mDpadPointer < 0 && mDpad.contains(x, y)) {
            mDpadPointer = id;
            dpad(x, y);
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            return;
        }
        Stick s = x < getWidth() / 2f ? mLeft : mRight;
        if (s.pointer < 0) {
            s.pointer = id;
            s.ox = s.x = x;
            s.oy = s.y = y;
            stick(s);
        }
    }

    private void move(int id, float x, float y) {
        if (id == mDpadPointer) { dpad(x, y); return; }
        for (Stick s : new Stick[] { mLeft, mRight }) {
            if (s.pointer != id) continue;
            float dx = x - s.ox, dy = y - s.oy, d = (float) Math.hypot(dx, dy);
            if (d > s.radius) {
                // Drag the origin along so reversing direction responds at once.
                float k = (d - s.radius) / d;
                s.ox += dx * k; s.oy += dy * k;
            }
            s.x = x; s.y = y;
            stick(s);
        }
    }

    private void up(int id) {
        for (Btn b : mButtons) if (b.pointer == id) { b.pointer = -1; press(b, false); }
        if (id == mDpadPointer) { mDpadPointer = -1; setDpad(0); }
        for (Stick s : new Stick[] { mLeft, mRight }) {
            if (s.pointer == id) {
                s.pointer = -1;
                SDLControllerManager.onNativeJoy(DEVICE_ID, s.axisX, 0f);
                SDLControllerManager.onNativeJoy(DEVICE_ID, s.axisY, 0f);
            }
        }
    }

    private void press(Btn b, boolean down) {
        if (b.triggerAxis >= 0) {
            SDLControllerManager.onNativeJoy(DEVICE_ID, b.triggerAxis, down ? 1f : -1f);
        } else if (down) {
            SDLControllerManager.onNativePadDown(DEVICE_ID, b.keycode);
        } else {
            SDLControllerManager.onNativePadUp(DEVICE_ID, b.keycode);
        }
    }

    private void stick(Stick s) {
        float vx = (s.x - s.ox) / s.radius, vy = (s.y - s.oy) / s.radius;
        // Android's axis convention, which SDL keeps: +Y is down.
        SDLControllerManager.onNativeJoy(DEVICE_ID, s.axisX, clamp(vx));
        SDLControllerManager.onNativeJoy(DEVICE_ID, s.axisY, clamp(vy));
    }

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
        int[] keys = { KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT };
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
