#!/usr/bin/env bash
# build-all.sh <casezero|casewest> <your game package or unpacked game folder> [--bundle]
#
# Everything, in order, for a playable APK from your own copy of the game:
#   1. fetch-sources.sh         upstream port + recompilers at pinned commits, patched
#   2. build-deps-android.sh    SDL2, ffmpeg (LGPL, XMA only), XenonUtils for arm64
#   3. build-turnip.sh          Mesa Turnip + libadrenotools (the Adreno GPU driver)
#   4. build-dxc-android.sh     DXC for the phone (shaders the game builds while playing)
#   5. build-host-tools.sh      XenonRecomp, DXC, the PC-side prep runtime
#   6. recompile.sh             your default.xex -> C++
#   7. build-native-android.sh  -> libmain.so
#   8. build-apk.sh             -> the APK
#   9. prepare-assets.sh        -> the game's translated shaders (with --bundle: and the game)
#
#   --bundle   put the game itself in the APK (~0.9 GB Case Zero, ~1.3 GB Case West):
#              install it and press PLAY, nothing to import. Without it you get a small
#              APK plus <game>-android-assets.zip, and import your package on the phone.
#
# Needs a Linux x86-64 machine (WSL2 works); the README lists the packages. About 30 GB
# of free disk and 16 GB of RAM; the first run takes an hour or more (DXC and Mesa).
source "$(dirname "$0")/common.sh"
load_game "${1:-}"
INPUT="${2:-}"
BUNDLE=0
[[ "${3:-}" == "--bundle" ]] && BUNDLE=1
[[ -n "$INPUT" && -e "$INPUT" ]] || die "usage: $(basename "$0") $GAME_ID <package | game folder> [--bundle]"

"$SCRIPTS/fetch-sources.sh" "$GAME_ID"
"$SCRIPTS/build-deps-android.sh"
"$SCRIPTS/build-turnip.sh"
"$SCRIPTS/build-dxc-android.sh"
"$SCRIPTS/build-host-tools.sh" "$GAME_ID"
"$SCRIPTS/recompile.sh" "$GAME_ID" "$INPUT"
"$SCRIPTS/build-native-android.sh" "$GAME_ID"

if (( BUNDLE )); then
    "$SCRIPTS/prepare-assets.sh" "$GAME_ID" "$INPUT" --with-game
    "$SCRIPTS/build-apk.sh" "$GAME_ID" release --bundle
    cat >&2 <<EOF

Done: $GAME_OUT/ holds the APK with the game inside (its name ends in
BUNDLED-PRIVATE). Install it (adb install, or copy it to the phone), open it and press
PLAY: the first launch copies the game into place.
Built from YOUR game: keep it to your own devices.
EOF
else
    "$SCRIPTS/build-apk.sh" "$GAME_ID" release
    "$SCRIPTS/prepare-assets.sh" "$GAME_ID" "$INPUT"
    cat >&2 <<EOF

Done. On the phone:
  1. install  $GAME_OUT/DR2-$GAME_ID-release.apk
  2. IMPORT GAME PACKAGE      (your XBLA package file)
  3. IMPORT PREPARED ASSETS   ($OUT/$GAME_ID-android-assets.zip)
  4. PLAY
Both the APK and the zip are built from YOUR game: keep them to your own devices.
EOF
fi
