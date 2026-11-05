package io.github.sidevesh.dr2;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

import java.io.File;

/**
 * The launcher's look, from the game itself: every XBLA package carries artwork for
 * the console's dashboard, and after IMPORT GAME PACKAGE it sits in assets/game/ on the
 * phone. Nothing here ships in the APK or the repository.
 *
 *   X_IMAGEID_GAME_BKGND.PNG    1920-wide scene: the launcher's background
 *   X_IMAGEID_GAME_BOXART.PNG   584x800 cover: the title logo is cut from it
 *
 * The app's UI is drawn in software (the manifest explains why), so the background is
 * baked once, at the window's exact size and already darkened: drawing it is then a
 * plain copy, and scrolling the panels over it stays smooth.
 */
final class GameArt {
    private GameArt() {}

    private static File art(Context c, String name) {
        File dir = GameEnv.game(c);
        String[] kids = dir.list();
        if (kids == null) return null;
        for (String k : kids) if (k.equalsIgnoreCase(name)) return new File(dir, k);
        return null;
    }

    static boolean available(Context c) {
        File f = art(c, "X_IMAGEID_GAME_BKGND.PNG");
        return f != null && f.isFile();
    }

    /** Where the scene's subject is, so a crop to the phone's shape keeps it. */
    private static float focusX() {
        // Case Zero: Chuck on the bike, right of centre. Case West: the two on the cliff, left.
        return "casewest".equals(BuildConfig.GAME_ID) ? 0.30f : 0.62f;
    }

    /**
     * The background at w x h: centre-cropped around the subject, darkened, and darkest
     * on the left, under the status panel and PLAY, so text there always reads.
     */
    static Bitmap background(Context c, int w, int h) {
        File f = art(c, "X_IMAGEID_GAME_BKGND.PNG");
        if (f == null || w <= 0 || h <= 0) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= w && o.outHeight / (o.inSampleSize * 2) >= h)
            o.inSampleSize *= 2;
        Bitmap src = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (src == null) return null;

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        // Case West's scene carries its own logo in the bottom-left corner; the launcher
        // shows the logo already, so only the part above it is used.
        float usableH = src.getHeight() * ("casewest".equals(BuildConfig.GAME_ID) ? 0.80f : 1f);
        float scale = Math.max((float) w / src.getWidth(), h / usableH);
        float sw = w / scale, sh = h / scale;
        float sx = Math.max(0, Math.min(src.getWidth() - sw, src.getWidth() * focusX() - sw / 2));
        float sy = Math.max(0, (usableH - sh) / 2);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        cv.drawBitmap(src, new Rect((int) sx, (int) sy, (int) (sx + sw), (int) (sy + sh)),
                new RectF(0, 0, w, h), p);
        src.recycle();
        // Darken: everywhere a little, the left (status, PLAY) much more, the bottom edge too.
        cv.drawColor(Color.argb(35, 0, 0, 0));
        Paint g = new Paint();
        g.setShader(new LinearGradient(0, 0, w * 0.55f, 0,
                new int[] { Color.argb(215, 6, 6, 9), Color.argb(120, 6, 6, 9), Color.argb(0, 6, 6, 9) },
                new float[] { 0f, 0.6f, 1f }, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, g);
        g.setShader(new LinearGradient(0, h * 0.7f, 0, h,
                Color.TRANSPARENT, Color.argb(130, 0, 0, 0), Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, g);
        return out;
    }

    /** The title logo, cut from the box art (the same region as the app icon), edges faded. */
    static Bitmap logo(Context c) {
        File f = art(c, "X_IMAGEID_GAME_BOXART.PNG");
        if (f == null) return null;
        Bitmap box = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (box == null) return null;
        // In the 584x800 cover (scripts/make-icons.py has the same numbers).
        int[] r = "casewest".equals(BuildConfig.GAME_ID)
                ? new int[] { 100, 630, 492, 790 } : new int[] { 28, 300, 556, 556 };
        float k = box.getWidth() / 584f;
        int l = (int) (r[0] * k), t = (int) (r[1] * k), rr = (int) (r[2] * k), b = (int) (r[3] * k);
        rr = Math.min(rr, box.getWidth());
        b = Math.min(b, box.getHeight());
        Bitmap logo = Bitmap.createBitmap(rr - l, b - t, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(logo);
        cv.drawBitmap(box, new Rect(l, t, rr, b), new Rect(0, 0, logo.getWidth(), logo.getHeight()), null);
        box.recycle();
        // The cover's own dark backdrop around the logo would show as a box over the
        // background: fade all four edges out.
        Paint m = new Paint();
        m.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        int lw = logo.getWidth(), lh = logo.getHeight();
        float fx = lw * 0.10f, fy = lh * 0.18f;
        m.setShader(new LinearGradient(0, 0, fx, 0, 0x00FFFFFF, 0xFFFFFFFF, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, fx, lh, m);
        m.setShader(new LinearGradient(lw - fx, 0, lw, 0, 0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        cv.drawRect(lw - fx, 0, lw, lh, m);
        m.setShader(new LinearGradient(0, 0, 0, fy, 0x00FFFFFF, 0xFFFFFFFF, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, lw, fy, m);
        m.setShader(new LinearGradient(0, lh - fy, 0, lh, 0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        cv.drawRect(0, lh - fy, lw, lh, m);
        return logo;
    }
}
