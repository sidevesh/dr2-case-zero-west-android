#!/usr/bin/env bash
# build-native-android.sh <casezero|casewest> [--stub]
#
# Builds the game's runtime as libmain.so for Android arm64 and stages every native
# library the APK needs into $OUT/<game>/jniLibs/arm64-v8a.
#
#   (default)  links the REAL recompiled image: <game src>/ppc must exist, made from
#              YOUR default.xex by recompile.sh. The result is your playable build.
#   --stub     links a stub image instead (upstream's tools/gen_stub_ppc.py): no game
#              data needed, nothing playable. This is what CI runs — it proves the
#              runtime compiles, links and packages for Android, and nothing more.
#
# Built with upstream's Steam Deck profile (-D<PFX>_DECK=ON): handheld defaults —
# borderless, and the title's own crowd detail rather than the desktop's doubled one.
#
# Needs: fetch-sources.sh and build-deps-android.sh first.
source "$(dirname "$0")/common.sh"
load_game "${1:-}"
STUB=0
[[ "${2:-}" == "--stub" ]] && STUB=1
need cmake; need ninja; need python3
find_ndk
mapfile -t ACM < <(android_cmake_args)

[[ -d "$GAME_SRC/runtime" ]] || die "no checkout at $GAME_SRC — run fetch-sources.sh $GAME_ID"
[[ -f "$DEPS/sdl2/lib/libSDL2.so" && -f "$DEPS/ffmpeg/lib/libavcodec.so" ]] ||
    die "Android dependencies missing — run build-deps-android.sh"
[[ -f "$DEPS/turnip/libvulkan_freedreno.so" && -f "$DEPS/turnip-r6/libvulkan_freedreno.so" &&
   -f "$DEPS/adrenotools/lib/libadrenotools.a" ]] ||
    die "Turnip / libadrenotools missing — run build-turnip.sh"
[[ -f "$SRC/volk/volk.c" ]] || die "volk missing — run fetch-sources.sh $GAME_ID"

if (( STUB )); then
    PPC="$WORK/stub-ppc/$GAME_ID"
    log "generating the stub image (no game data; NOT playable)"
    rm -rf "$PPC"
    ( cd "$GAME_SRC" && python3 tools/gen_stub_ppc.py --runtime runtime --out "$PPC" \
          --xenon "$SRC/XenonRecomp" ) >/dev/null
    BUILD="$WORK/build-$GAME_ID-android-stub"
else
    PPC="$GAME_SRC/ppc"
    [[ -f "$PPC/ppc_func_mapping.cpp" ]] ||
        die "no recompiled image at $PPC — run: scripts/recompile.sh $GAME_ID /path/to/default.xex"
    BUILD="$WORK/build-$GAME_ID-android"
fi

log "configuring $RUNTIME for Android ($([[ $STUB == 1 ]] && echo stub || echo real) image)"
cmake -S "$GAME_SRC/runtime" -B "$BUILD" -G Ninja "${ACM[@]}" \
    "-D${PFX}_PPC_DIR=$PPC" \
    "-D${PFX}_ANDROID_DIR=$DR2_DIR/native" \
    -DDR2_VOLK_DIR="$SRC/volk" -DDR2_ADRENOTOOLS_DIR="$DEPS/adrenotools" \
    "-D${PFX}_SDL2_PREFIX=$DEPS/sdl2" \
    "-D${PFX}_FFMPEG_PREFIX=$DEPS/ffmpeg" \
    "-D${PFX}_SPLIT_DEBUG=OFF" "-D${PFX}_BUNDLE_RPATH=OFF" \
    "-D${PFX}_XLIVE_OVERLAY=OFF" \
    "-D${PFX}_DECK=ON" \
    -DXLIVE_WITH_CURL=OFF -DXLIVE_BUILD_TESTS=OFF \
    -DXENON_ROOT="$SRC/XenonRecomp" \
    -DXENON_BUILD="$DEPS/xenonutils/build" \
    -DXENOS_ROOT="$SRC/XenosRecomp" \
    -DXLIVE_ROOT="$SRC/XenonLive" \
    -DCMAKE_FIND_ROOT_PATH="$DEPS/sdl2;$DEPS/ffmpeg" \
    ${EXTRA_CMAKE_ARGS:-} >"$BUILD.configure.log" 2>&1 ||
    { tail -40 "$BUILD.configure.log"; die "configure failed (full log: $BUILD.configure.log)"; }

# The real image is ~230 large generated TUs; each can want 2+ GB of RAM at -O2.
# JOBS=N overrides; the default leaves headroom on 16 GB machines.
log "building (log: $BUILD.build.log)"
cmake --build "$BUILD" -j"$(njobs)" >"$BUILD.build.log" 2>&1 ||
    { grep -E 'error|Error' "$BUILD.build.log" | head -40; die "build failed (full log: $BUILD.build.log)"; }

# Stage the APK's native libraries. libmain.so keeps its symbols in a side copy (for
# crash symbolication with ndk-stack) and ships stripped.
JNI="$GAME_OUT/jniLibs/$ANDROID_ABI"
rm -rf "$JNI"; mkdir -p "$JNI" "$GAME_OUT/symbols"
cp "$BUILD/libmain.so" "$GAME_OUT/symbols/libmain.so"
"$NDK_BIN/llvm-strip" --strip-debug -o "$JNI/libmain.so" "$BUILD/libmain.so"
cp "$DEPS/sdl2/lib/libSDL2.so" "$DEPS/ffmpeg/lib/libavcodec.so" "$DEPS/ffmpeg/lib/libavutil.so" "$JNI/"
cp "$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$NDK_HOST/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$JNI/"
# The two Turnip builds (build-turnip.sh), under names that cannot clash; the app
# copies the chosen one into private storage as libvulkan_freedreno.so before
# loading it. And the libadrenotools hooks, which must sit in nativeLibraryDir.
cp "$DEPS/turnip-r6/libvulkan_freedreno.so" "$JNI/libturnip_r6.so"
cp "$DEPS/turnip/libvulkan_freedreno.so" "$JNI/libturnip_mesa.so"
cp "$DEPS"/adrenotools/hooks/*.so "$JNI/"
echo "$STUB" > "$GAME_OUT/jniLibs/.stub"

# Every library must be loadable on the device: either shipped in the APK, or a
# platform library the NDK lists for this API level (libvulkan, libOpenSLES, ...).
SYSLIBS="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$NDK_HOST/sysroot/usr/lib/aarch64-linux-android/$ANDROID_API"
for so in "$JNI"/*.so; do
    while read -r need; do
        # libhardware/libsync: Turnip's, as a Vulkan HAL module; present on every
        # device and resolved inside the system loader's namespace (build-turnip.sh).
        [[ "$need" == libhardware.so || "$need" == libsync.so ]] && continue
        [[ -f "$JNI/$need" || -f "$SYSLIBS/$need" ]] ||
            die "$(basename "$so") needs $need: not in the APK and not an Android $ANDROID_API platform library"
    done < <("$NDK_BIN/llvm-readelf" -d "$so" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p')
done
log "native libraries staged in $JNI:"
ls -la "$JNI" >&2
