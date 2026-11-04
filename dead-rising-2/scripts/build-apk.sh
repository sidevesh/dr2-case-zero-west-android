#!/usr/bin/env bash
# build-apk.sh <casezero|casewest> [debug|release]
#
# Packages the native libraries build-native-android.sh staged into an APK:
#   $OUT/<game>/DR2-<game>-<type>.apk
#
# Signing: release uses $DR2_KEYSTORE (+ DR2_KEYSTORE_PASSWORD, DR2_KEY_ALIAS,
# DR2_KEY_PASSWORD) when set, and this machine's debug key otherwise. Android only
# updates an installed app with an APK signed by the same key.
source "$(dirname "$0")/common.sh"
load_game "${1:-}"
TYPE="${2:-release}"
[[ "$TYPE" == debug || "$TYPE" == release ]] || die "build type must be debug or release"
[[ -f "$GAME_OUT/jniLibs/$ANDROID_ABI/libmain.so" ]] || die "run build-native-android.sh $GAME_ID first"
[[ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]] || die "set ANDROID_HOME to your Android SDK"

# What a desktop release ships beside its executable (upstream tools/release_package_*):
# HostPaths::ShippedDir(). The app copies assets/shipped/ to internal storage.
SHIP="$GAME_OUT/apk-assets/shipped"
rm -rf "$GAME_OUT/apk-assets"; mkdir -p "$SHIP/kbm_chips"
cp "$GAME_SRC/tools/release/vs_recipes.bin" "$GAME_SRC/tools/release/prewarm.keys" "$SHIP/"
cp "$GAME_SRC"/tools/release/kbm_chips/*.dxt "$SHIP/kbm_chips/"
# The release defaults (upstream writes the same two lines). The launcher is replaced
# by LauncherActivity on Android; the environment still wins over this file.
cat > "$SHIP/${PFX,,}_defaults.env" <<EOF
# Written by game-ports-android build-apk.sh — release defaults for the Android build.
${PFX}_VKDRAW=1
${PFX}_LAUNCHER=0
EOF
# Licences of everything in the APK.
LIC="$GAME_OUT/apk-assets/licenses"; mkdir -p "$LIC"
cp "$GAME_SRC/LICENSE" "$LIC/${GAME_ID}-LICENSE.txt"
cp "$DR2_DIR/NOTICE.md" "$LIC/NOTICE.md" 2>/dev/null || true
cp "$WORK/build-src/SDL2-$SDL2_VERSION/LICENSE.txt" "$LIC/SDL2.txt" 2>/dev/null || true
cp "$WORK/build-src/ffmpeg-$FFMPEG_VERSION/COPYING.LGPLv2.1" "$LIC/ffmpeg-LGPL-2.1.txt" 2>/dev/null || true

GRADLE="$DR2_DIR/android/gradlew"
[[ -x "$GRADLE" ]] || GRADLE=gradle
cap="$(tr '[:lower:]' '[:upper:]' <<<"${GAME_ID:0:1}")${GAME_ID:1}"
tcap="$(tr '[:lower:]' '[:upper:]' <<<"${TYPE:0:1}")${TYPE:1}"
log "gradle assemble${cap}${tcap}"
( cd "$DR2_DIR/android" && "$GRADLE" --no-daemon -q "-Pdr2.work=$WORK" "assemble${cap}${tcap}" )

APK="$(ls "$DR2_DIR/android/app/build/outputs/apk/$GAME_ID/$TYPE/"*.apk | head -1)"
[[ -f "$APK" ]] || die "gradle produced no APK"
STUB="$(cat "$GAME_OUT/jniLibs/.stub")"
NAME="DR2-$GAME_ID-$TYPE$([[ $STUB == 1 ]] && echo -STUB-NOT-PLAYABLE).apk"
cp "$APK" "$GAME_OUT/$NAME"
( cd "$GAME_OUT" && sha256sum "$NAME" > "$NAME.sha256" )
log "APK: $GAME_OUT/$NAME ($(du -h "$GAME_OUT/$NAME" | cut -f1))"
[[ $STUB == 1 ]] && warn "this is a STUB build (no recompiled game): it installs and imports data but cannot play"
true
