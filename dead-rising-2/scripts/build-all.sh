#!/usr/bin/env bash
# build-all.sh <casezero|casewest> <your game package or unpacked game folder>
#
# Everything, in order, for a playable APK from your own copy of the game:
#   1. fetch-sources.sh       upstream port + recompilers at pinned commits, patched
#   2. build-deps-android.sh  SDL2, ffmpeg (LGPL, XMA only), XenonUtils for arm64
#   3. build-host-tools.sh    XenonRecomp, DXC, the host prep runtime
#   4. recompile.sh           your default.xex -> C++
#   5. build-native-android.sh  -> libmain.so
#   6. build-apk.sh           -> the APK
#   7. prepare-assets.sh      -> the shader-cache zip to import on the phone
#
# Needs a Linux x86-64 machine (WSL2 works) with: git curl python3 clang cmake ninja
# make pkg-config, libvulkan-dev libavcodec-dev libavutil-dev, JDK 17+, the Android
# SDK (ANDROID_HOME) with NDK 27.2.12479018. ~25 GB free disk, 16 GB RAM recommended.
source "$(dirname "$0")/common.sh"
load_game "${1:-}"
INPUT="${2:-}"
[[ -n "$INPUT" && -e "$INPUT" ]] || die "usage: $(basename "$0") $GAME_ID <package | game folder>"

"$SCRIPTS/fetch-sources.sh" "$GAME_ID"
"$SCRIPTS/build-deps-android.sh"
"$SCRIPTS/build-turnip.sh"
"$SCRIPTS/build-host-tools.sh" "$GAME_ID"
"$SCRIPTS/recompile.sh" "$GAME_ID" "$INPUT"
"$SCRIPTS/build-native-android.sh" "$GAME_ID"
"$SCRIPTS/build-apk.sh" "$GAME_ID" release
"$SCRIPTS/prepare-assets.sh" "$GAME_ID" "$INPUT"

cat >&2 <<EOF

Done. On the phone:
  1. install  $GAME_OUT/DR2-$GAME_ID-release.apk
  2. IMPORT GAME PACKAGE      (your XBLA package file)
  3. IMPORT PREPARED ASSETS   ($GAME_OUT/../$GAME_ID-android-assets.zip)
  4. PLAY
Both the APK and the zip are built from YOUR game: keep them to your own devices.
EOF
