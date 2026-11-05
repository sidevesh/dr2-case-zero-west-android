package io.github.sidevesh.dr2;

import android.content.Context;
import android.view.SurfaceHolder;

import org.libsdl.app.SDLSurface;

/**
 * SDL's surface, plus the two edges of its life reported to the renderer
 * (native/android_glue.h explains the protocol).
 *
 * Order matters on both edges:
 *  - destroyed: tell the renderer FIRST and wait for it to drop its VkSurfaceKHR and
 *    swapchain, THEN let SDL release the ANativeWindow they were built on;
 *  - changed:   let SDL pick up the new ANativeWindow FIRST, THEN tell the renderer
 *    it may build on it.
 */
public class GameSurface extends SDLSurface {
    public GameSurface(Context context) {
        super(context);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        NativeBridge.surfaceLost();
        super.surfaceDestroyed(holder);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        super.surfaceChanged(holder, format, width, height);
        NativeBridge.surfaceRestored();
    }
}
