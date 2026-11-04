#!/usr/bin/env bash
# build-dxc-android.sh
#
# Builds Microsoft's DirectX Shader Compiler (DXC) for Android arm64:
#   $DEPS/dxc/libdxcompiler.so
#
# WHY: the runtime translates every Xbox 360 shader to SPIR-V with DXC. Most are made
# ahead of time (the disc pass), but the title BUILDS some at run time — upstream
# translates those "on first sight" while playing. Without DXC on the phone those
# draws are skipped (on Case Zero: 12% of all draws, and a half-finished frame on
# screen). With it, the phone does exactly what the PC does, and can also run the
# whole disc pass itself.
#
# The same release as the PC-side DXC (common.sh DXC_URL). Built in two halves, as any
# LLVM cross-compile is: the table generators for this machine first, then the
# library for Android with those generators. ~40 min on 4 cores.
source "$(dirname "$0")/common.sh"
need git; need cmake; need ninja; need clang++
find_ndk
mapfile -t ACM < <(android_cmake_args)

DXC_REPO=https://github.com/microsoft/DirectXShaderCompiler
DXC_TAG=v1.8.2505.1
DXC_COMMIT=b106a961d09221b3c5bdb37be45b679257da08b8
src="$SRC/dxc" host="$WORK/build-dxc-host" b="$WORK/build-dxc-android" pfx="$DEPS/dxc"

if [[ -f "$pfx/libdxcompiler.so" && "$(cat "$pfx/.version" 2>/dev/null)" == "$DXC_COMMIT" ]]; then
    log "DXC $DXC_TAG for Android already built"; exit 0
fi

if [[ ! -d "$src/.git" ]]; then
    log "cloning DXC $DXC_TAG"
    git clone -q --depth 1 --branch "$DXC_TAG" "$DXC_REPO" "$src"
fi
[[ "$(git -C "$src" rev-parse HEAD)" == "$DXC_COMMIT" ]] || die "$src is not DXC $DXC_TAG ($DXC_COMMIT)"
# Only what SPIR-V output needs (tests and googletest stay out).
for m in external/SPIRV-Headers external/SPIRV-Tools external/DirectX-Headers; do
    git -C "$src" submodule update -q --init --depth 1 "$m"
done

COMMON=(-C "$src/cmake/caches/PredefinedParams.cmake" -DCMAKE_BUILD_TYPE=Release
        -DLLVM_INCLUDE_TESTS=OFF -DCLANG_INCLUDE_TESTS=OFF -DHLSL_INCLUDE_TESTS=OFF
        -DSPIRV_BUILD_TESTS=OFF)

if [[ ! -x "$host/bin/llvm-tblgen" || ! -x "$host/bin/clang-tblgen" ]]; then
    log "building DXC's table generators for this machine"
    cmake -S "$src" -B "$host" -G Ninja "${COMMON[@]}" \
        -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ >"$host.configure.log" 2>&1 ||
        { tail -20 "$host.configure.log"; die "host configure failed"; }
    ninja -C "$host" llvm-tblgen clang-tblgen >"$host.build.log" 2>&1 ||
        { tail -20 "$host.build.log"; die "host tablegen build failed"; }
fi

log "configuring DXC for $ANDROID_ABI"
cmake -S "$src" -B "$b" -G Ninja "${COMMON[@]}" "${ACM[@]}" \
    -DLLVM_TABLEGEN="$host/bin/llvm-tblgen" -DCLANG_TABLEGEN="$host/bin/clang-tblgen" \
    -DLLVM_HOST_TRIPLE=aarch64-unknown-linux-android \
    -DLLVM_ENABLE_TERMINFO=OFF -DLLVM_ENABLE_ZLIB=OFF >"$b.configure.log" 2>&1 ||
    { tail -20 "$b.configure.log"; die "Android configure failed"; }
log "building libdxcompiler.so (log: $b.build.log; ~40 min on 4 cores)"
ninja -C "$b" -j"$(njobs)" dxcompiler >"$b.build.log" 2>&1 ||
    { grep -E 'error:' "$b.build.log" | head -30; die "DXC build failed"; }

mkdir -p "$pfx"
"$NDK_BIN/llvm-strip" --strip-unneeded -o "$pfx/libdxcompiler.so" "$b/lib/libdxcompiler.so"
"$NDK_BIN/llvm-nm" -D --defined-only "$pfx/libdxcompiler.so" | grep -q ' DxcCreateInstance$' ||
    die "libdxcompiler.so does not export DxcCreateInstance"
cp "$src/LICENSE.TXT" "$pfx/LICENSE-dxc.txt" 2>/dev/null || cp "$src/LICENSE.txt" "$pfx/LICENSE-dxc.txt" 2>/dev/null || true
echo "$DXC_COMMIT" > "$pfx/.version"
log "DXC for Android: $(du -h "$pfx/libdxcompiler.so" | cut -f1)"
