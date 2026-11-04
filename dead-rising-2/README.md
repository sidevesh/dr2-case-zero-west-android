# Dead Rising 2: Case Zero & Case West — Android

An Android (ARM64) port of wivi514's native PC ports of
[**Case Zero**](https://github.com/wivi514/Dead_Rising_2_Case_Zero_Xenon_Recomp) and
[**Case West**](https://github.com/wivi514/Dead_Rising_2_Case_West_Xenon_Recomp).
Those ports are static recompilations: the Xbox 360's PowerPC code was translated
ahead of time to C++ ([XenonRecomp](https://github.com/hedge-dev/XenonRecomp)) and
runs on a purpose-built runtime with a Vulkan renderer. This project compiles that
same runtime for Android and wraps it in an app. **It is not an emulator.**

**No game data is included.** You need your own copy of the game (the XBLA package
from your Xbox 360). Unofficial; not affiliated with Capcom or Microsoft.

## Status

> **Builds, not yet played.** Both games' runtimes compile, link and package for
> Android arm64, and CI re-checks that on every push — but against a *stub* image, because
> no CI machine may hold the game. Nobody has yet run a real build on a phone. The
> first person who does is the first tester: expect bugs, and please report them with
> the in-app **LAST GAME LOG**.

| | Case Zero | Case West |
|---|---|---|
| Android patch applies to the final upstream release | ✓ | ✓ |
| `libmain.so` compiles and links for arm64 (stub image) | ✓ | ✓ |
| APK packages (16 KB-aligned libs, Android 13+) | ✓ | ✓ |
| PC-side shader prep tool builds and passes `--smoke` | ✓ | ✓ |
| Shader cache from a real package (1,367 modules, 0 failed) | ✓ | untested |
| Real recompiled image built into an APK (57,808 functions) | ✓ | untested |
| Boots / renders / plays on a device | untested | untested |

Why there is good reason to expect it to work: upstream already shipped an **Apple
Silicon (arm64) build** of both games. The hard ARM problems — real memory barriers
for PowerPC's `lwsync`/`eieio` (ARM is weakly ordered, x86 is not), the `cntvct_el0`
timebase, 16 KB pages, no x86 SIMD flags — were solved there, and this port reuses all
of it. What Android adds is a packaging, windowing and lifecycle problem, which is what
this directory solves.

## Will my phone run it?

The renderer needs **Vulkan 1.3** with, among others, `textureCompressionBC`,
`shaderInt64`, `bufferDeviceAddress`, descriptor indexing, `dynamicRendering` and
`shaderClipDistance`. In practice:

- **Likely:** Snapdragon 8 Gen 1 or newer (Adreno 730+), Samsung Exynos with Xclipse
  (RDNA) GPUs. 8 GB RAM or more. Android 13+.
- **Adreno needs Turnip, which the app bundles.** Qualcomm's own Adreno 7xx driver
  reports `shaderInt64` absent (seen on an Adreno 740 / Galaxy S23 Ultra, Android 16),
  and every translated shader needs it. The APK carries Mesa's open-source Turnip
  driver (built from the official Mesa 26.2.6 release) and loads it with libadrenotools —
  no root. **GPU DRIVER** in the launcher: *Auto* picks Turnip on Qualcomm chips.
- **Not yet:** Mali (most MediaTek, Tensor, older Exynos) and PowerVR GPUs — they have
  no BC texture support, and the renderer uploads the game's DXT textures as BC. A CPU
  decode fallback is the main missing piece for them (see *Roadmap*).

The app's **CHECK THIS DEVICE** button runs the runtime's own `--diag` and prints the
verdict, naming any missing feature.

## Building your APK

The APK contains code recompiled from *your* `default.xex`, so you build it yourself
(on purpose — never share one built from your game). You need a **Linux x86-64**
machine (WSL2 works):

- `git curl python3 clang cmake ninja-build make pkg-config`
- `libvulkan-dev libavcodec-dev libavutil-dev` (for the PC-side shader tool)
- JDK 17+, the Android SDK (`ANDROID_HOME`) with **NDK 27.2.12479018**
  (`sdkmanager "ndk;27.2.12479018" "platforms;android-35"`)
- ~25 GB free disk, 16 GB RAM recommended (the recompiled image is ~230 large C++ files)

Then one command (`casezero` or `casewest`, and your package file or unpacked folder):

```sh
dead-rising-2/scripts/build-all.sh casezero /path/to/your/package
```

It produces, under `dead-rising-2/build/out/`:

- `casezero/DR2-casezero-release.apk` — the app
- `casezero-android-assets.zip` — the translated shader cache, for the phone

Step by step, if you prefer (each script explains itself at the top):

```sh
cd dead-rising-2
scripts/fetch-sources.sh casezero             # upstream + recompilers, pinned, patched
scripts/build-deps-android.sh                 # SDL2, ffmpeg (XMA only), XenonUtils for arm64
scripts/build-host-tools.sh casezero          # XenonRecomp, DXC, the PC-side prep runtime
scripts/recompile.sh casezero <package>       # your default.xex -> C++
scripts/build-native-android.sh casezero      # -> libmain.so (add --stub for a CI-style build)
scripts/build-apk.sh casezero                 # -> APK
scripts/prepare-assets.sh casezero <package>  # -> the shader-cache zip (add --with-game to include the game)
```

Two ways to skip the on-phone import of the package:

- `prepare-assets.sh casezero <package> --split-mib 480` puts the game, its overlays and
  the shader cache in zips of at most 480 MiB each (for transfers that cap file size);
  pick **all** the parts together in IMPORT PREPARED ASSETS.
- `build-apk.sh casezero release --bundle` (after prepare-assets.sh) builds the game data
  into the APK itself (~0.9 GB Case Zero, ~1.3 GB Case West; install it with `adb install`);
  the app copies it into place on first launch.

## Installing on the phone

1. Install the APK. It is called **Dead Rising 2: Case Zero** (or Case West).
2. **IMPORT GAME PACKAGE** — pick the XBLA package file (on the console it is at
   `Content/0000000000000000/58410A8D/000D0000/` for Case Zero, `58410B00` for Case
   West). The app unpacks it with the runtime's own extractor, without copying it
   first when it can.
3. **IMPORT PREPARED ASSETS** — pick `casezero-android-assets.zip`.
4. **PLAY.**

A Bluetooth or USB controller is the best way to play; with none connected, an
on-screen controller appears. Saves live in `Android/data/<app>/files/saves/`, reachable
over USB, and uninstalling asks whether to keep the data.

## How the port works

```
LauncherActivity (Java)            GameActivity (Java, own process ":game")
  import package / prepared zip       SDLActivity ─ SDL2 2.32.10 ─ libmain.so
  runs runtime CLI tools via JNI ─┐     GameSurface: surface lost/restored ─┐
                                  ▼                                         ▼
                       libmain.so = the UPSTREAM runtime + a ~190-line patch
                          + native/android_main.cpp (SDL_main, JNI, logcat bridge)
                          + native/android_compat.h (the glibc calls bionic lacks)
```

The upstream runtime is used as it is, plus one small patch per game
(`case-*/patches/`, listed file by file in [`NOTICE.md`](NOTICE.md)). The Android
decisions, and why:

- **One process per game launch.** The runtime keeps process-wide state (4 GB of
  reserved guest memory, the recompiled image's globals, threads it never joins) that
  cannot be torn down and restarted. The game activity runs in its own process and
  ends it when the game exits; the launcher lives in another.
- **The window surface dies when the app is backgrounded.** Android destroys the
  `ANativeWindow`; the desktop runtime never had to handle that. `GameSurface` tells the
  renderer *before* SDL releases the window; the renderer idles the GPU, drops its
  swapchain and `VkSurfaceKHR`, and holds its thread (pausing the game with the app)
  until a new surface exists, then rebuilds (`native/android_glue.h`).
- **Pre-rotation.** Landscape on a portrait-native phone reports a rotated
  `currentTransform`; upstream passes it through, which on Android would draw sideways.
  The port presents with an identity transform and lets the compositor rotate, and
  ignores the resulting per-frame `VK_SUBOPTIMAL_KHR` (real resizes are still caught by
  the renderer's own size check).
- **Shaders are translated on the PC.** Upstream translates the game's 1,300+ shaders
  to SPIR-V at first run with DXC, which has no Android build. The cache is
  platform-independent, so `prepare-assets.sh` builds the *same* patched runtime for the
  PC (against a stub image — only its `--build-shader-cache` mode runs) and makes it
  there. Every other first-run step (unpacking, overlay generation) runs on the phone,
  through the runtime's own CLI modes called over JNI. One known gap: on desktop the
  runtime can also translate a vertex shader *the first time it is seen* in play
  (upstream says the disc pass covers 102 of the 104 runtime vertex shaders). A phone
  cannot; such a draw is skipped and the game log names it (`[shxlate]` lines). If that
  shows up, playing the same spot in the PC build and copying its
  `assets/shader_spv/` over closes it.
- **Android 13 (API 33) minimum.** The renderer calls Vulkan 1.3 core functions
  directly; Android's `libvulkan.so` only exports them from API 33 (the link fails
  below it).
- **Touch controls are a real SDL gamepad.** `TouchControlsView` registers a virtual
  joystick through SDL's Java API; SDL maps it like any Android controller, and the
  runtime sees a controller being plugged in. No native input code changed.
- **Handheld defaults.** Built with upstream's Steam Deck profile (`-DCZ_DECK=ON`), and
  the first-run internal resolution matches the phone's shape (e.g. 1600×720 on 20:9) —
  as a default the in-game settings can change.
- **Offline.** XenonLive (online co-op) is compiled without networking; co-op needs
  upstream's desktop launcher anyway.

## Roadmap — what a contributor could pick up

1. **First real-device run** of both games, and fixing whatever it finds.
2. **Mali / PowerVR:** decode BC1–BC5 to RGBA8 on upload when `textureCompressionBC`
   is absent (Marathon's Android port solved the same thing with ETC2 + a cache).
3. **Real pre-rotation** (rotate in the final blit) to save the compositor pass.
4. ~~Turnip via libadrenotools~~ — done: bundled, and the launcher's default on Qualcomm.
5. **Performance:** Android performance-hint sessions and big-core pinning; upstream's
   `thread_budget.cpp` pinning is compiled in but tuned for desktop topologies.
6. Context-aware touch layouts (camera-mode buttons for Case West's photo mode).

## Prior art this port learned from

Other Xbox 360 recompilations already reached Android, and their write-ups shaped the
decisions above (no code was copied from them):
[UnleashedRecomp-Android](https://github.com/SansNope/UnleashedRecomp-Android) and
[MarathonRecomp-Android](https://github.com/SansNope/MarathonRecomp-Android) (device
matrix, Mali workarounds, shaders built on the host),
[Skate 3 Mobile](https://github.com/Buku313/Skate3-Android) (phone-side ISO import,
Android 13+ / Vulkan 1.3 floor, 16 KB pages, touch overlay),
[reNut for Android](https://github.com/Innersargento/Renut-Android) (host-side shader
recompilation, Turnip via adrenotools).

## Licensing

See [`NOTICE.md`](NOTICE.md). The upstream ports are PolyForm Noncommercial 1.0.0, and
so are this directory's patches to them: **noncommercial use only**.
