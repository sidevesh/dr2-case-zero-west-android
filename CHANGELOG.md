# Changelog

## 1.0.0 — first release

Dead Rising 2: Case Zero and Case West as native Android (ARM64) apps, built from your
own copy of each game.

**Running the game**
- The upstream PC runtime of each game compiled for Android 13+ (arm64, 16 KB pages),
  with one small patch per game.
- Mesa Turnip bundled for Adreno GPUs (Mesa 26.2.6 and StevenMXZ 26.3.0-R6), loaded
  with libadrenotools, no root; automatic fallback to the other build and then the
  phone's own driver.
- DXC on the phone, so shaders the game builds while playing are translated as on PC.
- Surviving the app going to the background and back; always landscape.

**Launcher**
- Main screen: install status, whether this phone can run the game (checked
  automatically), which GPU driver the last run used, PLAY.
- Progress: achievements (from the game's own list, with icons) and saves.
- Graphics: presets, internal resolution 540p (experimental) to 1440p, FSR 1.0 upscaling,
  frame-rate cap, vsync, MSAA, shadows, zombie and world detail, field of view,
  exposure, FPS counter.
- Controls: on-screen controller (opacity, size, haptics, low-latency input, dims when
  idle), gyro aiming (off / while aiming / always, sensitivity, per-axis invert).
- System: GPU driver, Turnip rendering mode, sustained performance, skip intro logos,
  language, device check, logs, advanced environment.

**Saves and achievements**
- Achievements are recorded as you unlock them (offline profile).
- Export to Xenia: saves with Xenia's header files (and a Xenia Manager import zip) and
  achievements as a title GPD, with instructions; import saves from Xenia.

**Building**
- `scripts/build-all.sh <game> <package> [--bundle]` from a Linux PC: a small APK plus
  a shader zip, or one APK with the game inside.

**Known limitations**
- Tested on one phone (Galaxy S23 Ultra, Adreno 740) with Case Zero; Case West not yet
  reported on a device.
- Mali and PowerVR GPUs are not supported (no BC texture support).
- Online co-op is not available.
- Exporting achievements to Xenia does not change the profile's total gamerscore.
