# How the port works

Technical notes for contributors: how the upstream PC runtime is turned into an Android
app, and why each Android-specific decision was made. Start with the
[README](../README.md) if you just want to build and play.


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
(`case-*/patches/`, listed file by file in [`NOTICE.md`](../NOTICE.md)). The Android
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
- **DXC runs on the phone.** The title builds some shaders while it runs, and upstream
  translates those the first time they appear, with DXC. Without DXC those draws were
  skipped (12% of Case Zero's, leaving half-finished frames on screen), so the APK carries
  DXC v1.8.2505.1 built for Android (`scripts/build-dxc-android.sh`, ~13 min on 4 cores),
  run on a 32 MB-stack thread since bionic's default ~1 MB is too small for it. It also
  lets IMPORT GAME PACKAGE build the whole shader cache on the phone.
- **Shaders can also be translated on the PC.** The game's ~1,400 disc shaders are
  translated to SPIR-V once. The cache is platform-independent, so `prepare-assets.sh`
  builds the *same* patched runtime for the PC (against a stub image — only its
  `--build-shader-cache` mode runs) and makes it there in a few minutes; the phone can
  also make it itself with the bundled DXC. Every other first-run step (unpacking,
  overlay generation) runs on the phone, through the runtime's own CLI modes over JNI.
- **Android 13 (API 33) minimum.** The renderer calls Vulkan 1.3 core functions
  directly; Android's `libvulkan.so` only exports them from API 33 (the link fails
  below it).
- **Touch controls are a real SDL gamepad.** `TouchControlsView` registers a virtual
  joystick through SDL's Java API; SDL maps it like any Android controller, and the
  runtime sees a controller being plugged in. No native input code changed. Touches
  are dispatched unbuffered (not batched to the display frame), the overlay redraws
  only when it changes, and it dims when idle; opacity, size and haptics are settings.
- **Settings are the game's own.** The launcher's GRAPHICS tab edits the runtime's
  `cz_settings.txt` / `cw_settings.txt` — the file behind the in-game PC SETTINGS panel
  — so the two never disagree: presets (Battery saver / Balanced / Quality), internal
  resolution at the phone's shape, frame-rate cap, vsync, MSAA, shadows, zombie and
  world detail, field of view, exposure. A first launch writes *Balanced* (720p, 60 FPS
  cap, no MSAA, medium shadows, 1.0 zombie detail) instead of the desktop defaults.
  SHOW FPS counts real presents (a counter in the renderer's present path).
- **FSR 1.0 and 540p.** The present path's bilinear stretch can be replaced by AMD
  FidelityFX Super Resolution 1.0 (EASU + RCAS as two compute passes,
  `native/android_fsr.inc`; the default) whenever the frame is being enlarged. On
  Android the runtime also accepts internal resolutions down to 540 lines —
  experimental, below the title's own 720.
- **Gyro aiming.** The phone's rotation rate is added to the on-screen controller's
  camera stick, always or only while the aim button (LT by default) is held, with
  sensitivity and inversion settings.
- **Always landscape.** SDL requests an orientation from the window's shape;
  GameActivity overrides that request.
- **Handheld defaults.** Built with upstream's Steam Deck profile (`-DCZ_DECK=ON`), and
  the first-run internal resolution matches the phone's shape (e.g. 1544×720 on an
  S23 Ultra) — as a default the settings can change.
- **Offline.** XenonLive (online co-op) is compiled without networking; co-op needs
  upstream's desktop launcher anyway.
- **GPU drivers.** Qualcomm's own Adreno 7xx driver reports `shaderInt64` absent, and
  every translated shader needs it, so the APK carries Mesa's open-source Turnip driver
  in two builds (Mesa 26.2.6 built here, and StevenMXZ's 26.3.0-R6) and loads it with
  libadrenotools — no root (`native/android_vulkan.cpp`). The renderer's Vulkan calls go
  through volk, so the driver is chosen at run time. Order: the chosen build, the other
  build, then the system driver; a Turnip that finds no GPU it supports also falls back.
  libadrenotools cannot replace a driver that is already loaded in the process, and
  Android's UI renderer loads the stock one when it draws with Vulkan — so the app's UI
  is not hardware-accelerated and the game loads its driver before it draws anything.
  After loading, the driver is asked whether it really is Turnip, and the launcher shows
  what the last run used.
- **Your own drivers.** `CustomDrivers.java` imports driver zips in libadrenotools'
  package format (`meta.json` with `libraryName`, the library beside it), checks the
  library is AArch64, and unpacks it into private storage. It loads exactly like the
  bundled Turnip; the "is it really Turnip?" check is skipped for it, since a package may
  be a GPU maker's driver, but the no-device fallback still applies.
- **The launcher's look** comes from the game's dashboard artwork in the imported package
  (`X_IMAGEID_GAME_BKGND.PNG`, the box art's logo), baked once at the window's size
  (`GameArt.java`): the UI draws in software, so a pre-scaled, pre-darkened bitmap keeps
  scrolling smooth.
- **Will this phone run it?** The launcher runs the runtime's own `--diag` once per APK
  and driver choice and shows the verdict on its main screen, with advice when a required
  feature is missing (`Compat.java`).
- **Achievements and saves.** On Android the game runs on the offline default profile,
  where upstream kept earned achievements in memory only; the patch appends each unlock
  to `achievements.txt` beside the saves. The achievement list (names, descriptions,
  gamerscore, icons) is read from the player's own `default.xex` by `--dump-spa` and
  parsed by `Xdbf.java`; the PROGRESS tab shows both.
- **Export to Xenia.** `XeniaExport.java` writes the saves with the
  `Headers/00000001/<save>.header` files Xenia Canary requires before it lists a save,
  plus a title GPD (`<TitleID>.gpd`) serialized the way Canary's own `GpdInfo` writes
  one. Formats were read from xenia-canary 838850f and Xenia Manager a905dfe.
