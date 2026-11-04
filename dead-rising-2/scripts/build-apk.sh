#!/usr/bin/env bash
# build-apk.sh <casezero|casewest> [debug|release] [--bundle]
#
# Packages the native libraries build-native-android.sh staged into an APK:
#   $OUT/<game>/DR2-<game>-<type>.apk
#
# --bundle  also puts YOUR prepared game data inside the APK — the unpacked game, its
#           overlays and the shader cache, from prepare-assets.sh's work tree — so the
#           app needs no imports: it copies them into place on first launch. ~0.9 GB
#           for Case Zero. The APK then carries the whole game: it is for your own
#           devices only (named -BUNDLED-PRIVATE so that stays obvious).
#
# Signing: release uses $DR2_KEYSTORE (+ DR2_KEYSTORE_PASSWORD, DR2_KEY_ALIAS,
# DR2_KEY_PASSWORD) when set, and this machine's debug key otherwise. Android only
# updates an installed app with an APK signed by the same key.
source "$(dirname "$0")/common.sh"
load_game "${1:-}"
TYPE="${2:-release}"
BUNDLE=0
[[ "${3:-}" == "--bundle" ]] && BUNDLE=1
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
cp "$DEPS/turnip/LICENSE-mesa.rst" "$LIC/mesa.rst" 2>/dev/null || true
cp "$DEPS/adrenotools/LICENSE-adrenotools.txt" "$LIC/libadrenotools.txt" 2>/dev/null || true
cp "$SRC/volk/LICENSE.md" "$LIC/volk.md" 2>/dev/null || true
cp "$DEPS/dxc/LICENSE-dxc.txt" "$LIC/dxc.txt" 2>/dev/null || true
cp "$DR2_DIR/native/fsr/LICENSE-FidelityFX-FSR.txt" "$LIC/FidelityFX-FSR.txt"

# ---- --bundle: the prepared game data, plus a manifest the app installs from ----------
if (( BUNDLE )); then
    PREP_ROOT="$WORK/prepare/$GAME_ID"
    PREP="$HOSTB/$GAME_ID-prep/$RUNTIME"
    [[ -f "$PREP_ROOT/assets/game/default.xex" ]] ||
        die "no prepared game at $PREP_ROOT — run: scripts/prepare-assets.sh $GAME_ID <your package>"
    status="$(env "${PFX}_ROOT=$PREP_ROOT" "$PREP" --shader-cache-status 2>/dev/null || true)"
    grep -q 'not owed' <<<"$status" || die "the shader cache in $PREP_ROOT is not complete — rerun prepare-assets.sh"
    if [[ ! -d "$PREP_ROOT/assets/game_patched" ]]; then
        log "generating the overlays"
        env "${PFX}_ROOT=$PREP_ROOT" "$PREP" --gen-overlays >"$WORK/$GAME_ID-overlays.log" 2>&1 ||
            die "overlay generation failed (log: $WORK/$GAME_ID-overlays.log)"
    fi
    BUN="$GAME_OUT/apk-assets/bundled"
    mkdir -p "$BUN/assets"
    log "staging the bundled game data"
    for d in "$PREP_ROOT"/assets/game "$PREP_ROOT"/assets/game_* "$PREP_ROOT"/assets/shader_spv; do
        [[ -d "$d" ]] || continue
        # Hard links when possible: the same 0.9 GB need not exist twice on this disk.
        cp -RLl "$d" "$BUN/assets/" 2>/dev/null || cp -RL "$d" "$BUN/assets/"
    done
    # The app copies exactly what this lists: "<size>\t<path>" per file, after a
    # header whose stamp changes whenever the content does.
    ( cd "$BUN" && find assets -type f -printf '%s\t%p\n' | LC_ALL=C sort -k2 ) > "$WORK/$GAME_ID-bundle.list"
    {
        echo "game=$GAME_ID"
        echo "stamp=$(sha256sum "$WORK/$GAME_ID-bundle.list" | cut -c1-16)"
        echo "files=$(wc -l < "$WORK/$GAME_ID-bundle.list")"
        echo "bytes=$(awk -F'\t' '{s+=$1} END {print s}' "$WORK/$GAME_ID-bundle.list")"
        echo "--"
        cat "$WORK/$GAME_ID-bundle.list"
    } > "$BUN/MANIFEST.txt"
    log "bundled: $(sed -n 's/^files=//p' "$BUN/MANIFEST.txt") files, $(du -shL "$BUN" | cut -f1)"
fi

GRADLE="$DR2_DIR/android/gradlew"
[[ -x "$GRADLE" ]] || GRADLE=gradle
cap="$(tr '[:lower:]' '[:upper:]' <<<"${GAME_ID:0:1}")${GAME_ID:1}"
tcap="$(tr '[:lower:]' '[:upper:]' <<<"${TYPE:0:1}")${TYPE:1}"
log "gradle assemble${cap}${tcap}"
( cd "$DR2_DIR/android" && "$GRADLE" --no-daemon -q "-Pdr2.work=$WORK" "-Pdr2.bundled=$BUNDLE" "assemble${cap}${tcap}" )

APK="$(ls "$DR2_DIR/android/app/build/outputs/apk/$GAME_ID/$TYPE/"*.apk | head -1)"
[[ -f "$APK" ]] || die "gradle produced no APK"
if (( BUNDLE )); then
    # Every file the manifest promises must really be in the APK: the packager has
    # silently dropped files before (dot-files), and the app would fail mid-install.
    missing="$(comm -23 <(sed -n 's/^[0-9]*\t//p' "$BUN/MANIFEST.txt" | sed 's|^|assets/bundled/|' | LC_ALL=C sort) \
                        <(unzip -Z1 "$APK" | LC_ALL=C sort))"
    [[ -z "$missing" ]] || die "files listed in the bundle manifest are missing from the APK:
$missing"
    log "bundle check: every manifest entry is in the APK"
fi
STUB="$(cat "$GAME_OUT/jniLibs/.stub")"
SUFFIX=""
[[ "$STUB" == 1 ]] && SUFFIX="-STUB-NOT-PLAYABLE"
(( BUNDLE )) && SUFFIX="$SUFFIX-BUNDLED-PRIVATE"
NAME="DR2-$GAME_ID-$TYPE$SUFFIX.apk"
cp "$APK" "$GAME_OUT/$NAME"
( cd "$GAME_OUT" && sha256sum "$NAME" > "$NAME.sha256" )
log "APK: $GAME_OUT/$NAME ($(du -h "$GAME_OUT/$NAME" | cut -f1))"
[[ $STUB == 1 ]] && warn "this is a STUB build (no recompiled game): it installs and imports data but cannot play"
(( BUNDLE )) && warn "this APK contains your game data: keep it to your own devices"
true
