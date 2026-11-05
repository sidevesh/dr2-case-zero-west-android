#!/usr/bin/env bash
# build-turnip.sh
#
# Prepares Turnip — Mesa's open-source Vulkan driver for Qualcomm Adreno GPUs — for
# Android arm64, in two builds, plus libadrenotools, which lets an ordinary app load
# either (no root):
#   $DEPS/turnip-r6/libvulkan_freedreno.so  StevenMXZ's Turnip 26.3.0-R6 (Mesa
#                      26.3.0-devel + patches, a6xx/a7xx): the DEFAULT. It is the build
#                      LibertyRecompAndroid (GTA IV) bundles and that runs on a Galaxy
#                      S23 Ultra / Adreno 740; fetched at that project's pinned commit
#                      and checked against the SHA-256s it pins.
#   $DEPS/turnip/libvulkan_freedreno.so     upstream Mesa $MESA_VERSION built here from
#                      the official release tarball: the fallback, and the one to
#                      reproduce from source.
#   $DEPS/adrenotools/                      libadrenotools.a, its headers, its hook .so's
#
# WHY: Qualcomm's own Adreno 7xx driver does not expose shaderInt64, and every shader
# this renderer translates uses 64-bit addresses (bufferDeviceAddress). Turnip does.
# The app ships both drivers and picks per device (launcher: GPU DRIVER).
#
# Needs: the NDK, python3 with venv, ninja, curl; glslang-tools, bison, flex.
source "$(dirname "$0")/common.sh"
need curl; need ninja; need python3; need bison; need flex; need glslangValidator
find_ndk
DL="$WORK/downloads"; mkdir -p "$DL" "$DEPS"

MESA_VERSION=26.2.6
TURNIP_R6_URL=https://raw.githubusercontent.com/vaduur/LibertyRecompAndroid/f4f6d8ae904d6d781671759d3d83c983768127d6/os/android/app/src/main/assets/drivers/turnip-r6.zip
TURNIP_R6_ZIP_SHA256=5fa2305eb9eff64d9347a670e6ca5ef7c518f60e9e782a4db7ee5588c9852d81
TURNIP_R6_LIB_SHA256=4fd2fd30311af301ae5564139bc24cc727390d4dc3c90a20192c25ec91287c59
MESA_SHA256=b9436b3e1a8be76fbd4823dc884f90596ab7c6ac5e1867a305e66ffccfb31a92
ADRENOTOOLS_REPO=https://github.com/bylaws/libadrenotools
ADRENOTOOLS_COMMIT=8fae8ce254dfc1344527e05301e43f37dea2df80

# ---- Mesa / Turnip -----------------------------------------------------------------
build_turnip() {
    local pfx="$DEPS/turnip" src="$WORK/build-src/mesa-$MESA_VERSION" b="$WORK/build-turnip"
    if [[ -f "$pfx/libvulkan_freedreno.so" && "$(cat "$pfx/.version" 2>/dev/null)" == "$MESA_VERSION" ]]; then
        log "Turnip (Mesa $MESA_VERSION) already built"; return 0
    fi
    local tgz="$DL/mesa-$MESA_VERSION.tar.xz"
    [[ -f "$tgz" ]] || curl -fsSL --retry 3 -o "$tgz" "https://archive.mesa3d.org/mesa-$MESA_VERSION.tar.xz"
    echo "$MESA_SHA256  $tgz" | sha256sum -c --quiet - || die "Mesa tarball does not match its pinned SHA-256"
    mkdir -p "$WORK/build-src"
    [[ -d "$src" ]] || tar -C "$WORK/build-src" -xJf "$tgz"

    # Mesa wants a newer meson than many distributions carry; a private venv has it.
    local venv="$WORK/venv-mesa"
    if [[ ! -x "$venv/bin/meson" ]]; then
        log "creating a Python venv for Mesa's build (meson, mako, pyyaml)"
        python3 -m venv "$venv"
        "$venv/bin/pip" install -q 'meson>=1.4,<2' mako pyyaml packaging
    fi

    cat > "$WORK/mesa-android-aarch64.cross" <<EOF
[binaries]
ar = '$NDK_BIN/llvm-ar'
c = ['$NDK_BIN/aarch64-linux-android$ANDROID_API-clang']
cpp = ['$NDK_BIN/aarch64-linux-android$ANDROID_API-clang++', '-fno-exceptions', '-fno-unwind-tables', '-fno-asynchronous-unwind-tables', '-static-libstdc++', '-Wno-c++11-narrowing']
c_ld = 'lld'
cpp_ld = 'lld'
strip = '$NDK_BIN/llvm-strip'
pkg-config = ['/bin/false']

[built-in options]
c_link_args = ['-Wl,-z,max-page-size=16384']
cpp_link_args = ['-Wl,-z,max-page-size=16384', '-static-libstdc++']

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8'
endian = 'little'
EOF
    log "configuring Mesa $MESA_VERSION (Turnip only, KGSL, Android HAL)"
    rm -rf "$b"
    PATH="$venv/bin:$PATH" meson setup "$b" "$src" --cross-file "$WORK/mesa-android-aarch64.cross" \
        -Dbuildtype=release -Dstrip=true \
        -Dplatforms=android -Dplatform-sdk-version="$ANDROID_API" -Dandroid-stub=true \
        -Dandroid-libbacktrace=disabled \
        -Dgallium-drivers= -Dvulkan-drivers=freedreno -Dfreedreno-kmds=kgsl \
        -Dopengl=false -Degl=disabled -Dgles1=disabled -Dgles2=disabled -Dglx=disabled \
        -Dllvm=disabled -Dlibunwind=disabled -Dvalgrind=disabled -Dzstd=disabled \
        -Dvulkan-layers= -Dtools= -Dbuild-tests=false \
        >"$b.configure.log" 2>&1 || { tail -40 "$b.configure.log"; die "Mesa configure failed"; }
    log "building Turnip (log: $b.build.log)"
    ninja -C "$b" -j"$(njobs)" src/freedreno/vulkan/libvulkan_freedreno.so >"$b.build.log" 2>&1 ||
        { grep -E 'error' "$b.build.log" | head -30; die "Turnip build failed"; }
    mkdir -p "$pfx"
    cp "$b/src/freedreno/vulkan/libvulkan_freedreno.so" "$pfx/"
    # Self-contained: it is loaded into its own linker namespace, beside nothing of ours.
    local needed
    needed="$("$NDK_BIN/llvm-readelf" -d "$pfx/libvulkan_freedreno.so" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | tr '\n' ' ')"
    log "Turnip NEEDs: $needed"
    for n in $needed; do
        # A Vulkan HAL driver also links libhardware/libsync: not NDK libraries, but
        # present on every device, and visible to the system Vulkan loader that
        # libadrenotools loads the driver through.
        [[ "$n" == libhardware.so || "$n" == libsync.so ]] && continue
        [[ -f "$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$NDK_HOST/sysroot/usr/lib/aarch64-linux-android/$ANDROID_API/$n" ]] ||
            die "Turnip needs $n, which is not an Android platform library"
    done
    "$NDK_BIN/llvm-nm" -D --defined-only "$pfx/libvulkan_freedreno.so" | grep -q ' HMI$' ||
        die "Turnip does not export HMI — it was not built as an Android Vulkan HAL module"
    cp "$src/docs/license.rst" "$pfx/LICENSE-mesa.rst"
    echo "$MESA_VERSION" > "$pfx/.version"
}

# ---- libadrenotools ---------------------------------------------------------------------
build_adrenotools() {
    local pfx="$DEPS/adrenotools" src="$SRC/libadrenotools" b="$WORK/build-adrenotools"
    if [[ -f "$pfx/lib/libadrenotools.a" && "$(cat "$pfx/.version" 2>/dev/null)" == "$ADRENOTOOLS_COMMIT" ]]; then
        log "libadrenotools already built"; return 0
    fi
    clone_at "$ADRENOTOOLS_REPO" "$ADRENOTOOLS_COMMIT" "$src"
    git -C "$src" submodule update -q --init --recursive --depth 1
    mapfile -t ACM < <(android_cmake_args)
    log "building libadrenotools"
    rm -rf "$b" "$pfx"
    cmake -S "$src" -B "$b" -G Ninja "${ACM[@]}" -DCMAKE_INSTALL_PREFIX="$pfx" >"$b.configure.log" 2>&1 ||
        { tail -30 "$b.configure.log"; die "libadrenotools configure failed"; }
    cmake --build "$b" -j"$(njobs)" >"$b.build.log" 2>&1 || { tail -30 "$b.build.log"; die "libadrenotools build failed"; }
    # Copied by hand: upstream's install() rules only run when it is the top-level
    # project. linkernsbypass is a second static library it needs at link time; the
    # four hook .so's ship in the APK's native library directory.
    mkdir -p "$pfx/lib" "$pfx/hooks"
    cp "$b/libadrenotools.a" "$b/lib/linkernsbypass/liblinkernsbypass.a" "$pfx/lib/"
    cp "$b"/src/hook/lib*_hook.so "$b/src/hook/libhook_impl.so" "$pfx/hooks/"
    cp -r "$src/include" "$pfx/include"
    cp "$src/LICENSE" "$pfx/LICENSE-adrenotools.txt"
    echo "$ADRENOTOOLS_COMMIT" > "$pfx/.version"
}

# ---- StevenMXZ Turnip 26.3.0-R6 (prebuilt, pinned) -------------------------------------
fetch_turnip_r6() {
    local pfx="$DEPS/turnip-r6" zip="$DL/turnip-r6.zip"
    if [[ -f "$pfx/libvulkan_freedreno.so" ]] &&
       echo "$TURNIP_R6_LIB_SHA256  $pfx/libvulkan_freedreno.so" | sha256sum -c --quiet - 2>/dev/null; then
        log "Turnip 26.3.0-R6 already present"; return 0
    fi
    [[ -f "$zip" ]] || curl -fsSL --retry 3 -o "$zip" "$TURNIP_R6_URL"
    echo "$TURNIP_R6_ZIP_SHA256  $zip" | sha256sum -c --quiet - || die "turnip-r6.zip does not match its pinned SHA-256"
    rm -rf "$pfx"; mkdir -p "$pfx"
    unzip -q -o "$zip" -d "$pfx"
    echo "$TURNIP_R6_LIB_SHA256  $pfx/libvulkan_freedreno.so" | sha256sum -c --quiet - ||
        die "the R6 driver library does not match its pinned SHA-256"
    log "Turnip 26.3.0-R6: $(sed -n 's/.*"name": *"\(.*\)".*/\1/p' "$pfx/meta.json") verified"
}

build_adrenotools
fetch_turnip_r6
build_turnip
log "Turnip + libadrenotools ready under $DEPS"
