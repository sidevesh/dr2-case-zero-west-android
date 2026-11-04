# Notices — Dead Rising 2 Android port

## The upstream ports (PolyForm Noncommercial 1.0.0)

This directory ports two projects by **wivi514** to Android:

- [Dead_Rising_2_Case_Zero_Xenon_Recomp](https://github.com/wivi514/Dead_Rising_2_Case_Zero_Xenon_Recomp)
  at `10d45b0952dd1b7f53f29dbff0795a27abb81ff7`
- [Dead_Rising_2_Case_West_Xenon_Recomp](https://github.com/wivi514/Dead_Rising_2_Case_West_Xenon_Recomp)
  at `ba78bde572d654022f35e00eb9860be6c4583456`

Both are **Copyright (c) 2026 wivi514**, licensed under the
[PolyForm Noncommercial License 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0).
That licence allows use, modification and redistribution for **noncommercial purposes
only**, provided the notice is kept and changes are stated.

This repository contains **no upstream source files**. The scripts download them at
the pinned commits. What it does contain that is derived from them is:

- `case-zero/patches/0001-android-platform-support.patch`
- `case-west/patches/0001-android-platform-support.patch`

These patches are modifications of the upstream works and are distributed under the
same PolyForm Noncommercial License 1.0.0, with the notice above. **What they change**,
in both games (each `#if defined(__ANDROID__)`-guarded unless stated):

| file | change |
|---|---|
| `runtime/CMakeLists.txt` | build the runtime as `libmain.so` under the NDK; add this port's `native/` glue; skip the host-`clang++` probe when a toolchain file is in use (all platforms) |
| `runtime/main.cpp` | `--diag` OS facts from Android system properties (no glibc) |
| `runtime/host/bug_report.cpp` | CPU name from `ro.soc.*` (no CPUID on ARM) |
| `runtime/host/host_paths.cpp` | `ExeDir()` from `DR2_SHIPPED_DIR` (the process image is `app_process64`) |
| `runtime/host/window.cpp` | no SDL progress window (one SDL window on Android); `--diag` skips the SDL video probe |
| `runtime/host/first_run.cpp` | the missing-shader-cache message says how to make one for Android |
| `runtime/host/settings.cpp` | first-run internal resolution from `DR2_DEFAULT_RES` (a default, never a pin); internal resolutions down to 540 lines accepted |
| `runtime/gpu/vk_renderer.cpp` | recreate the window surface across Android's background/foreground cycle; identity pre-transform; do not rebuild on Android's per-frame `SUBOPTIMAL`; count presents for the FPS readout; optional FSR 1.0 upscale at present (`native/android_fsr.inc`) |

## Recompilers (MIT)

[XenonRecomp](https://github.com/hedge-dev/XenonRecomp) and
[XenosRecomp](https://github.com/hedge-dev/XenosRecomp) by hedge-dev, MIT, fetched at
pinned commits with the DR2 port's local patch series (exported in the Case Zero
repository's `patches/`).

## XenonLive

[XenonLive](https://github.com/wivi514/XenonLive)'s client library is fetched and
compiled in (both runtimes require it at configure time), **offline**
(`-DXLIVE_WITH_CURL=OFF`). It is not redistributed in source form here.

## Libraries inside the APK

| component | licence | how |
|---|---|---|
| SDL 2.32.10 | zlib | `libSDL2.so`, plus its Java glue (`org.libsdl.app`) compiled into the app |
| FFmpeg 8.1.2 | LGPL 2.1+ | `libavcodec.so`, `libavutil.so`, dynamically linked; configured with only the XMA1/XMA2 decoders and `--disable-gpl --disable-nonfree` (the build refuses otherwise) |
| LLVM libc++ | Apache 2.0 with LLVM exception | `libc++_shared.so` from the NDK |
| fmt, xxHash | MIT, BSD-2 | statically, via XenonUtils |
| Turnip 26.3.0-R6 (StevenMXZ; Mesa 26.3.0-devel `a995eaeda4` + patches) | MIT | `libturnip_r6.so`, the **default** Adreno driver: the prebuilt package LibertyRecompAndroid bundles, fetched at its commit `f4f6d8a` and checked against the SHA-256s it pins (`scripts/build-turnip.sh`) |
| Mesa 26.2.6 — Turnip | MIT | `libturnip_mesa.so`, the fallback Adreno driver, built here from the official release tarball |
| libadrenotools (+ linkernsbypass) | BSD-2-Clause | statically in `libmain.so`, plus its four hook libraries; loads Turnip without root |
| volk | MIT | statically in `libmain.so`; Vulkan entry points loaded at run time |
| AMD FidelityFX Super Resolution 1.0 (`native/fsr/`) | MIT | compiled into `libmain.so` as SPIR-V (the present-time upscaler) |
| DirectX Shader Compiler v1.8.2505.1 | University of Illinois/NCSA (LLVM) | `libdxcompiler.so`, built for Android by `scripts/build-dxc-android.sh`; translates shaders the game builds while it runs |

The licence texts ship in the APK under `assets/licenses/`.

## Prepared on the PC, not shipped

[DirectX Shader Compiler](https://github.com/microsoft/DirectXShaderCompiler) (DXC),
University of Illinois/NCSA, is downloaded at a pinned release by
`scripts/build-host-tools.sh` and used only on the build machine.

## The game

Dead Rising 2: Case Zero and Dead Rising 2: Case West are © Capcom. Nothing of either
game is in this repository. The recompiled code, the APK built from it, and the
prepared-assets zip are all **derived from your own copy**: keep them on your own
devices. This project is not affiliated with or endorsed by Capcom or Microsoft.
