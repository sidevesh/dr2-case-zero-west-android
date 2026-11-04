#pragma once
// android_glue.h — what the patched runtime sources call into on Android.
// Implemented in android_main.cpp. Shared by Case Zero and Case West.

#if defined(__ANDROID__)
#include <cstdint>

extern "C" {

// THE WINDOW SURFACE LIFECYCLE.
//
// Android destroys the activity's ANativeWindow whenever the app leaves the screen
// (home button, app switch, screen off) and creates a new one when it returns. A
// VkSurfaceKHR made from the old window — and every swapchain built on it — is dead
// from that moment. The desktop runtime never sees this: its window outlives the
// process. So the Java SurfaceView subclass (GameSurface.java) reports both edges,
// and the renderer's present path (patched vk_renderer.cpp) does the rest:
//
//   surfaceDestroyed  -> Dr2Android_SurfaceLost() turns true. The renderer, at its
//                        next present, idles the device, destroys the swapchain and
//                        the surface, calls Dr2Android_SurfaceReleased(), and then
//                        HOLDS its thread until the surface is back — so the guest,
//                        whose next frame waits on this one, pauses with the app
//                        instead of playing on unseen.
//   surfaceChanged    -> Dr2Android_SurfaceLost() turns false; the renderer makes a
//                        new VkSurfaceKHR from SDL's new window and a new swapchain.
//
// The Java side waits (bounded) for the release, because Android expects a
// SurfaceView's consumers to be gone by the time surfaceDestroyed returns.
bool Dr2Android_SurfaceLost();
void Dr2Android_SurfaceReleased();

}  // extern "C"
#endif
