#!/usr/bin/env bash
# build-deps-android.sh
#
# Cross-compiles, for Android arm64, the native libraries both runtimes link:
#   $DEPS/sdl2         SDL2 (shared, libSDL2.so) + its Java glue (sdl2/java)
#   $DEPS/ffmpeg       ffmpeg, LGPL, XMA1/XMA2 decoders only (libavcodec.so, libavutil.so)
#   $DEPS/xenonutils   XenonRecomp's XenonUtils + fmt + xxhash static libs
#
# Game-independent: run once, shared by Case Zero and Case West.
# Needs: fetch-sources.sh (for XenonRecomp), the NDK, cmake, ninja, make, curl.
source "$(dirname "$0")/common.sh"
need cmake; need ninja; need make; need curl
find_ndk
mapfile -t ACM < <(android_cmake_args)
DL="$WORK/downloads"; mkdir -p "$DL" "$DEPS"

fetch() { # fetch <url> <file>
    [[ -f "$DL/$2" ]] && return 0
    log "downloading $2"
    curl -fL --retry 3 -o "$DL/$2.part" "$1" && mv "$DL/$2.part" "$DL/$2"
}

# ---- SDL2 --------------------------------------------------------------------
build_sdl2() {
    local pfx="$DEPS/sdl2" src="$WORK/build-src/SDL2-$SDL2_VERSION"
    if [[ -f "$pfx/lib/libSDL2.so" && -f "$pfx/.version" && "$(cat "$pfx/.version")" == "$SDL2_VERSION" ]]; then
        log "SDL2 $SDL2_VERSION already built"; return 0
    fi
    fetch "https://github.com/libsdl-org/SDL/releases/download/release-$SDL2_VERSION/SDL2-$SDL2_VERSION.tar.gz" \
          "SDL2-$SDL2_VERSION.tar.gz"
    mkdir -p "$WORK/build-src"
    [[ -d "$src" ]] || tar -C "$WORK/build-src" -xzf "$DL/SDL2-$SDL2_VERSION.tar.gz"
    log "building SDL2 $SDL2_VERSION for $ANDROID_ABI"
    rm -rf "$WORK/build-sdl2" "$pfx"
    cmake -S "$src" -B "$WORK/build-sdl2" -G Ninja "${ACM[@]}" \
        -DCMAKE_INSTALL_PREFIX="$pfx" \
        -DSDL_SHARED=ON -DSDL_STATIC=OFF -DSDL_TEST=OFF >/dev/null
    cmake --build "$WORK/build-sdl2" -j"$(njobs)" >/dev/null
    cmake --install "$WORK/build-sdl2" >/dev/null
    # The Java half of SDL. It is versioned with the native library — a mismatch is a
    # JNI crash at startup — so it is copied from this exact tree, never vendored.
    mkdir -p "$pfx/java"
    cp -r "$src/android-project/app/src/main/java/org" "$pfx/java/"
    echo "$SDL2_VERSION" > "$pfx/.version"
}

# ---- ffmpeg (LGPL, XMA only) ---------------------------------------------------
# The same configure line as upstream tools/build_ffmpeg_lgpl.sh, plus the cross
# compile flags. The licence checks are kept: a GPL build must never ship here.
build_ffmpeg() {
    local pfx="$DEPS/ffmpeg" src="$WORK/build-src/ffmpeg-$FFMPEG_VERSION" b="$WORK/build-ffmpeg"
    if [[ -f "$pfx/lib/libavcodec.so" && -f "$pfx/.version" && "$(cat "$pfx/.version")" == "$FFMPEG_VERSION" ]]; then
        log "ffmpeg $FFMPEG_VERSION already built"; return 0
    fi
    fetch "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz" "ffmpeg-$FFMPEG_VERSION.tar.xz"
    mkdir -p "$WORK/build-src"
    [[ -d "$src" ]] || tar -C "$WORK/build-src" -xJf "$DL/ffmpeg-$FFMPEG_VERSION.tar.xz"
    log "building ffmpeg $FFMPEG_VERSION (LGPL, xma1+xma2) for $ANDROID_ABI"
    rm -rf "$b" "$pfx"; mkdir -p "$b"
    local cc="$NDK_BIN/aarch64-linux-android$ANDROID_API-clang"
    ( cd "$b" && "$src/configure" \
        --prefix="$pfx" \
        --enable-cross-compile --target-os=android --arch=aarch64 --cpu=armv8-a \
        --cc="$cc" --cxx="$cc++" \
        --ar="$NDK_BIN/llvm-ar" --nm="$NDK_BIN/llvm-nm" \
        --ranlib="$NDK_BIN/llvm-ranlib" --strip="$NDK_BIN/llvm-strip" \
        --extra-ldflags="-Wl,-z,max-page-size=16384" \
        --enable-pic --enable-shared --disable-static \
        --disable-everything --disable-autodetect \
        --disable-programs --disable-doc \
        --disable-avdevice --disable-avformat --disable-avfilter \
        --disable-swscale --disable-swresample \
        --disable-network --disable-iconv \
        --enable-decoder=xma1,xma2 \
        --disable-gpl --disable-nonfree --disable-version3 \
        >"$b/configure.log" 2>&1 ) || { tail -40 "$b/configure.log"; die "ffmpeg configure failed"; }
    grep -q '^#define CONFIG_GPL 1' "$b/config.h" && die "ffmpeg came out GPL — refusing"
    grep -q '^#define CONFIG_NONFREE 1' "$b/config.h" && die "ffmpeg came out non-free — refusing"
    # ffmpeg 6+ keeps the per-component switches in config_components.h.
    grep -qh '^#define CONFIG_XMA2_DECODER 1' "$b"/config*.h || die "ffmpeg has no XMA2 decoder"
    make -C "$b" -j"$(njobs)" >"$b/make.log" 2>&1 || { tail -40 "$b/make.log"; die "ffmpeg build failed"; }
    make -C "$b" install >>"$b/make.log" 2>&1
    # Android loads libraries by their file name from the APK, so the SONAME must be
    # plain libavcodec.so — ffmpeg's android target does this; make sure.
    "$NDK_BIN/llvm-readelf" -d "$pfx/lib/libavcodec.so" | grep -q 'SONAME.*\[libavcodec.so\]' ||
        die "libavcodec.so has a versioned SONAME; Android cannot load that from an APK"
    echo "$FFMPEG_VERSION" > "$pfx/.version"
}

# ---- XenonUtils (the XEX loader + guest structs the runtime links) --------------
build_xenonutils() {
    local pfx="$DEPS/xenonutils" b="$WORK/build-xenonutils-android"
    [[ -d "$SRC/XenonRecomp" ]] || die "run fetch-sources.sh first"
    log "building XenonUtils/fmt/xxhash for $ANDROID_ABI"
    cmake -S "$SRC/XenonRecomp" -B "$b" -G Ninja "${ACM[@]}" >/dev/null
    cmake --build "$b" --target XenonUtils fmt xxhash -j"$(njobs)" >/dev/null
    # The runtimes look for these three archives at fixed paths under XENON_BUILD.
    mkdir -p "$pfx"
    ln -sfn "$b" "$pfx/build"
}

build_sdl2
build_ffmpeg
build_xenonutils
log "Android dependencies ready under $DEPS"
