# Dead Rising 2: Case Zero & Case West for Android

Native Android (ARM64) builds of **Dead Rising 2: Case Zero** and **Dead Rising 2: Case
West**, made from wivi514's PC ports of the two Xbox Live Arcade games:
[Case Zero](https://github.com/wivi514/Dead_Rising_2_Case_Zero_Xenon_Recomp) and
[Case West](https://github.com/wivi514/Dead_Rising_2_Case_West_Xenon_Recomp).

Those ports are **static recompilations, not emulators**: the Xbox 360's PowerPC code is
translated ahead of time to C++ ([XenonRecomp](https://github.com/hedge-dev/XenonRecomp))
and runs on a purpose-built runtime with a Vulkan renderer. This project compiles that
same runtime for Android and wraps it in an app with a launcher made for phones.

> **No game data is included, and no ready-made APK is offered.** The APK contains code
> recompiled from *your* copy of the game, so you build it yourself from the game files
> on your own Xbox 360. Keep what you build to your own devices.
> Unofficial; not affiliated with Capcom or Microsoft.

## Contents

- [Status](#status)
- [Will my phone run it?](#will-my-phone-run-it)
- [Features](#features)
- [Getting the game files off your Xbox 360](#getting-the-game-files-off-your-xbox-360)
- [Building your APK](#building-your-apk)
- [Installing and playing](#installing-and-playing)
- [Saves, achievements and Xenia](#saves-achievements-and-xenia)
- [Troubleshooting](#troubleshooting)
- [Contributing](#contributing)
- [Credits and licences](#credits-and-licences)

## Status

| | Case Zero | Case West |
|---|---|---|
| Builds for Android (real recompiled game) | ✓ | ✓ |
| Boots and renders on a phone | ✓ Galaxy S23 Ultra (Snapdragon 8 Gen 2) | not yet reported |
| Played start to finish | not yet reported | not yet reported |

This is a first release: one phone model has run it. Both games share the same runtime
and Android code, so Case West is expected to behave like Case Zero, but nobody has
reported on it yet. If you try either game on another phone, please open an issue with
your phone model and the launcher's **Check this device** output: that is how the
device list below grows.

## Will my phone run it?

The launcher answers this itself: on first start it runs the runtime's own device check
and shows the verdict on its main screen (**System › Check this device** shows the full
report). The renderer needs Vulkan 1.3 with, among others, BC texture compression,
64-bit shader integers, buffer device addresses and descriptor indexing. In practice:

| GPU (chip) | Expected | Notes |
|---|---|---|
| **Adreno 7xx** (Snapdragon 8 Gen 1 / 2 / 3, 7+ Gen 2, …) | ✓ runs | Uses the bundled Turnip driver (automatic). Seen running on an Adreno 740. |
| **Adreno 8xx** (Snapdragon 8 Elite) | likely | Turnip support for these GPUs is newer; the app falls back to the phone's own driver if Turnip cannot drive the GPU. |
| **Adreno 6xx** (Snapdragon 865 / 888 …) | possible | Turnip supports it; performance unknown, use the Battery saver preset. |
| **Xclipse** (Samsung Exynos 2200 / 2400) | possible | Runs on the phone's own driver if it has every required feature. |
| **Mali** (most MediaTek, Google Tensor, older Exynos), **PowerVR** | ✗ not yet | No BC texture support; the game's textures would need a converter that does not exist yet. You can still try a driver package (below). |

Also needed: **Android 13 or newer**, 64-bit ARM, and ideally **8 GB of RAM** or more.

## Features

- **Runs natively**, with the game's own code recompiled for ARM64; no emulation layer.
- **A launcher made for phones**, dressed in each game's own artwork (its background
  scene and logo, read from your imported game files), in four tabs:
  - **Progress**: your achievements (icons, descriptions, gamerscore, unlock dates) and
    save games.
  - **Graphics**: presets (Battery saver / Balanced / Quality), internal resolution from
    540p (experimental) to 1440p at your screen's shape, **FSR 1.0** upscaling (on/off,
    with a sharpness control), frame-rate cap (30 / 60 / 90 / 120 / unlocked), vsync, MSAA,
    shadows, zombie and world detail, field of view, exposure, FPS counter. These are the
    game's own settings, shared with its in-game PC settings screen.
  - **Controls**: on-screen controller opacity, size and haptics; **gyro aiming** (off /
    while aiming / always, with sensitivity and per-axis invert).
  - **System**: GPU driver choice, **your own GPU driver packages**, Turnip rendering
    mode, sustained performance mode,
    skip intro logos, language, device check, logs.
- **Controllers**: any Bluetooth or USB controller works as an Xbox pad. Without one, a
  translucent on-screen controller appears.
- **Turnip GPU driver bundled** for Adreno: Mesa's open-source driver, loaded without
  root, with automatic fallback.
- **Achievements are kept**: unlocks are recorded as you play, and the game's own
  achievement list is read from your copy of it.
- **Export to Xenia**: your saves and achievements in the formats Xenia Canary and Xenia
  Manager use, with step-by-step instructions.

## Getting the game files off your Xbox 360

You need the XBLA package of each game, from a console the game is installed on. Copy
it off with an FTP-capable dashboard or a USB drive formatted by the console and read
with a tool such as Horizon or Velocity on a PC.

| Game | Path on the console | Size |
|---|---|---|
| Case Zero | `Content/0000000000000000/58410A8D/000D0000/` (one file) | 864,768,000 bytes |
| Case West | `Content/0000000000000000/58410B00/000D0000/` (one file) | 1,224,163,328 bytes |

The file in that folder is the package. The build takes either the package file itself
or a folder holding its unpacked contents.

## Building your APK

You need a **Linux x86-64 PC** (WSL2 on Windows works), about **30 GB of free disk**, and
16 GB of RAM recommended. The first build takes an hour or more on 4 cores: it builds
DXC and Mesa from source, and the recompiled game is ~230 large C++ files. Later builds
reuse all of it.

**1. Install the tools** (Debian/Ubuntu package names):

```sh
sudo apt install git curl python3 python3-venv clang cmake ninja-build make pkg-config \
    libvulkan-dev libavcodec-dev libavutil-dev glslang-tools spirv-tools bison flex \
    openjdk-17-jdk p7zip-full python3-pil
```

**2. Install the Android SDK** command-line tools and, with them, the NDK and platform:

```sh
export ANDROID_HOME=$HOME/android-sdk          # wherever you unpacked the command-line tools
sdkmanager "ndk;27.2.12479018" "platforms;android-35" "build-tools;35.0.0"
```

**3. Clone and build** (`casezero` or `casewest`, then your package file or folder):

```sh
git clone https://github.com/sidevesh/dr2-case-zero-west-android
cd dr2-case-zero-west-android
scripts/build-all.sh casezero /path/to/your/case-zero-package
```

That leaves, under `build/out/`:

- `casezero/DR2-casezero-release.apk`: the app (about 38 MB);
- `casezero-android-assets.zip`: the game's translated shaders, to import on the phone.

**Or, one file with everything inside:** add `--bundle` to get a single APK with the game
included (~0.9 GB for Case Zero, ~1.3 GB for Case West). You install it and press PLAY;
there is nothing to import.

```sh
scripts/build-all.sh casezero /path/to/your/case-zero-package --bundle
```

`scripts/pack-bundled.sh` splits that APK into a 7-Zip archive of smaller parts, for
transfers that cap file size.

<details>
<summary>Step by step, and other options</summary>

Each script explains itself at the top.

```sh
scripts/fetch-sources.sh casezero             # upstream port + recompilers, pinned, patched
scripts/build-deps-android.sh                 # SDL2, ffmpeg (XMA only), XenonUtils for arm64
scripts/build-turnip.sh                       # Mesa Turnip + libadrenotools
scripts/build-dxc-android.sh                  # DXC for the phone
scripts/build-host-tools.sh casezero          # XenonRecomp, DXC, the PC-side prep runtime
scripts/recompile.sh casezero <package>       # your default.xex -> C++
scripts/build-native-android.sh casezero      # -> libmain.so (--stub: a toolchain check, no game)
scripts/build-apk.sh casezero release         # -> APK (--bundle: with the game inside)
scripts/prepare-assets.sh casezero <package>  # -> shader zip (--with-game, --split-mib N)
```

- **Build location:** everything is built under `build/` in the repository; set
  `DR2_WORK=/somewhere/else` to move it. `JOBS=N` limits parallel compile jobs.
- **Signing:** APKs are signed with your Android debug key unless you set
  `DR2_KEYSTORE`, `DR2_KEYSTORE_PASSWORD`, `DR2_KEY_ALIAS` and `DR2_KEY_PASSWORD`. Keep
  using the same key: an APK signed with another key cannot update an installed one.
- **Both games** install side by side as separate apps.
- **App icon:** made at build time from your copy of the game's box art
  (`scripts/make-icons.py`, needs `python3-pil`); without it the app gets an original
  fallback icon per game.

</details>

## Installing and playing

1. **Install the APK** on the phone (allow installs from your file manager, or use
   `adb install`). The app is called *Dead Rising 2: Case Zero* (or *Case West*).
2. Open it. With a `--bundle` APK, the first start copies the game into place; skip to
   step 5.
3. **Import game package**: pick the package file you copied off the Xbox. The app
   unpacks it with the runtime's own extractor.
4. **Import prepared assets**: pick `casezero-android-assets.zip`. (Without it, the app
   translates the shaders itself, which takes a few minutes, once.)
5. Check that the main screen says **This phone can run it**, then press **PLAY**.

Tips:

- Start with the **Balanced** preset (720p, 60 FPS cap, FSR). If it is not smooth, try
  **Battery saver** (30 FPS); if it has headroom, raise the resolution.
- Connect a controller **before** pressing PLAY; the on-screen controls hide
  themselves when one is connected.
- Game data and saves live in `Android/data/io.github.sidevesh.dr2.casezero/files/`
  (or `.casewest`), reachable over USB. Uninstalling asks whether to keep them.

## Saves, achievements and Xenia

The **Progress** tab lists your achievements and save games. **Export to Xenia** writes
one zip containing:

- your saves, with the header files Xenia Canary needs before it lists a save, and the
  same saves as a zip for **Xenia Manager's Import Saves**;
- your achievements as `<TitleID>.gpd`, with what you unlocked on the phone marked
  earned at the time you earned it;
- `HOW TO - Xenia.txt`, with steps for Xenia Manager and for Xenia Canary alone.

Two things to know:

- With **Xenia Canary alone**, start the game in Canary once *before* copying the
  achievements file in. Otherwise Canary overwrites it with an empty one on the first
  start.
- After copying it in, run **Refresh title stats** for the game in Canary. The game's
  own count and gamerscore are then correct, but **your profile's total gamerscore does
  not change**: Canary only adds to that total for achievements unlocked inside Canary.

**Import saves from Xenia** brings saves the other way. Any save it replaces is backed
up first.

### Your own GPU driver

**System › Import a driver package** takes a driver zip in the format Android
emulators use (libadrenotools': a `meta.json` naming the driver library, beside it), so
you can try a newer Turnip build or your GPU maker's driver. It then appears in
**System › GPU driver**, and the main screen re-checks the phone with it. If it fails
to load or finds no GPU, the app falls back to the bundled Turnip (on Adreno) and then
to the phone's own driver; **System** shows why. A driver cannot add what the GPU itself
lacks: on Mali, BC texture support is a hardware question, not a driver one.

## Troubleshooting

| What you see | What to do |
|---|---|
| "This phone cannot run it" on the main screen | It names what is missing. On Adreno, make sure **System › GPU driver** is *Auto* or a Turnip build. |
| "Adreno … lacks required Vulkan feature: shaderInt64" when starting | The stock driver was used. Check **System › GPU driver**; the System tab shows why Turnip did not load last time. |
| Black screen or broken graphics | Try the other Turnip build in **System › GPU driver**, or **Turnip rendering: Tiled**. Send the **Last game log**. |
| Slow | Lower the preset or resolution; turn on **Sustained performance**. FSR costs a little; turning it off is a small saving. |
| Portrait or rotated picture | Update to the latest build; the game always runs in landscape. |

When reporting a problem, include your phone model, **System › Check this device**
output and **Last game log** (both have a Copy button).

## Contributing

How the port works, and why each Android decision was made, is in
[`docs/HOW-IT-WORKS.md`](docs/HOW-IT-WORKS.md). Open areas:

1. **Device reports**: any phone other than the one tested.
2. **Mali / PowerVR**: decode the game's BC textures to a format those GPUs read.
3. **Performance**: big-core placement and Android performance hints for the game's main
   threads; real pre-rotation in the final blit.
4. **Xenia**: optionally update the profile's total gamerscore on export.
5. **Context-aware touch layouts**, for example Case West's camera mode.

The changes to the upstream ports are one patch per game (`case-zero/patches/`,
`case-west/patches/`), listed file by file in [`NOTICE.md`](NOTICE.md). To change one,
edit the checkout under `build/src/<game>` and run `scripts/dev-export-patch.sh <game>`.

## Credits and licences

- **wivi514**: the Case Zero and Case West PC ports this builds on.
- **hedge-dev**: XenonRecomp and XenosRecomp.
- **Mesa** (Turnip), **bylaws/libadrenotools**, **StevenMXZ** (Turnip R6 build), **SDL**,
  **FFmpeg**, **DirectX Shader Compiler**, **volk**, **AMD FidelityFX Super Resolution**.
- Android ports that showed the way: UnleashedRecomp-Android, MarathonRecomp-Android,
  Skate 3 Mobile, reNut for Android and LibertyRecompAndroid (no code was copied from
  them).

The upstream ports are licensed under the **PolyForm Noncommercial License 1.0.0**, and
so are this repository's patches to them: **noncommercial use only**. Every bundled
component's licence is listed in [`NOTICE.md`](NOTICE.md) and shipped inside the APK.
