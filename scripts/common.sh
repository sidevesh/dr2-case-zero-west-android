# shellcheck shell=bash
# Shared settings and helpers for the Dead Rising 2 Android build scripts.
# Every script sources this file first:  source "$(dirname "$0")/common.sh"

set -euo pipefail

DR2_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPTS="$DR2_DIR/scripts"

# Everything this project downloads or builds lives under one work directory, so
# deleting it is a full clean. Override with DR2_WORK=/somewhere/with/space.
WORK="${DR2_WORK:-$DR2_DIR/build}"
SRC="$WORK/src"          # upstream checkouts (patched)
DEPS="$WORK/deps"        # Android-arm64 prefixes: sdl2, ffmpeg, xenonutils
HOSTB="$WORK/host"       # host (x86-64 Linux) tools: XenonRecomp, the prep runtime
OUT="$WORK/out"          # per-game native outputs and the finished APKs

# ---- pinned versions ---------------------------------------------------------
# The recompilers: both DR2 ports pin the same upstream bases.
XENON_REPO=https://github.com/hedge-dev/XenonRecomp
XENON_BASE=ddd128bcca99fe8bfbb99bea583c972351fa6ace
XENOS_REPO=https://github.com/hedge-dev/XenosRecomp
XENOS_BASE=990d03b28a27b50277ee5d8d942e1c5f873869d1
# The local recompiler patches. Case Zero's repository is the only place they were
# exported as a full series (patches/README.md there). It is a SUPERSET of the
# squashed tools/ci/*.patch both repos carry: in particular it has 0012 + 0014,
# which turn lwsync/eieio into real `dmb ish` barriers on arm64. The CI patch does
# not, and on ARM's weak memory model that is a data race, not a slowdown. So both
# games are built with this series.
RECOMP_PATCHES_REPO=https://github.com/wivi514/Dead_Rising_2_Case_Zero_Xenon_Recomp
RECOMP_PATCHES_COMMIT=10d45b0952dd1b7f53f29dbff0795a27abb81ff7

# XenonLive (libxlive): required at configure time by both runtimes. Built offline
# (-DXLIVE_WITH_CURL=OFF) on Android; this is the commit v1.1.3 shipped with.
XLIVE_REPO=https://github.com/wivi514/XenonLive
XLIVE_COMMIT=134d49b96f42a85323134a9af436dddfa987a7ea

# volk (MIT): on Android every Vulkan call goes through function pointers it loads at
# run time, so the app can choose the system driver or Turnip (native/android_vulkan.cpp).
# Pinned to the release matching the NDK's own Vulkan headers (1.3.275).
VOLK_REPO=https://github.com/zeux/volk
VOLK_COMMIT=f2a16e3e19c2349b873343b2dc38a1d4c25af23a

# The same SDL2 and ffmpeg versions upstream ships (tools/build_sdl2.sh,
# tools/build_ffmpeg_lgpl.sh). SDL's Java glue must match the native library
# exactly, so it is copied from this same source tree at build time.
SDL2_VERSION=2.32.10
FFMPEG_VERSION=8.1.2

# DXC for the PC-side shader pass (prepare-assets.sh). The shader cache's recipe
# stamp ("clip=1 vid=c", shader_translator.cpp RecipeId) does not depend on the DXC
# build, so the official release is used rather than a private checkout.
DXC_URL=https://github.com/microsoft/DirectXShaderCompiler/releases/download/v1.8.2505.1/linux_dxc_2025_07_14.x86_64.tar.gz
DXC_SHA256=f2213da1fc99dc8778c8823078e16ba97c7f80f86a1d4520ab1adf4b462bc48c

# Android target. API 33 (Android 13) is the floor: the renderer calls Vulkan 1.2
# and 1.3 core functions directly (vkCmdBeginRendering, vkGetBufferDeviceAddress,
# ...), and Android's libvulkan.so only exports the 1.3 core from API 33 — below it
# the link fails. It needs a Vulkan 1.3 driver anyway, which in practice means
# Android 13+ devices. (bionic's memfd_create, used for guest memory, is API 30.)
ANDROID_ABI=arm64-v8a
ANDROID_API=33
NDK_VERSION="${NDK_VERSION:-27.2.12479018}"

# ---- helpers -----------------------------------------------------------------
log()  { printf '\033[1;34m==>\033[0m %s\n' "$*" >&2; }
warn() { printf '\033[1;33mwarning:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "$1 is required${2:+ ($2)}"; }
njobs() { echo "${JOBS:-$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)}"; }

# load_game <casezero|casewest>: read the per-game port.env into the environment.
load_game() {
    case "${1:-}" in
        casezero|case-zero|cz) GAME_DIR="$DR2_DIR/case-zero" ;;
        casewest|case-west|cw) GAME_DIR="$DR2_DIR/case-west" ;;
        *) die "first argument must be the game: casezero or casewest" ;;
    esac
    # shellcheck source=/dev/null
    source "$GAME_DIR/port.env"
    GAME_SRC="$SRC/$GAME_ID"
    GAME_OUT="$OUT/$GAME_ID"
    export GAME_ID GAME_TITLE GAME_FOLDER PFX RUNTIME RECOMP_TOML TITLE_ID PS_BANK
}

# The Android NDK: $ANDROID_NDK_ROOT, else $ANDROID_HOME/ndk/<pinned version>.
find_ndk() {
    local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [[ -z "${ANDROID_NDK_ROOT:-}" && -n "$sdk" && -d "$sdk/ndk/$NDK_VERSION" ]]; then
        ANDROID_NDK_ROOT="$sdk/ndk/$NDK_VERSION"
    fi
    [[ -n "${ANDROID_NDK_ROOT:-}" && -f "$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" ]] ||
        die "Android NDK not found. Install NDK $NDK_VERSION (sdkmanager \"ndk;$NDK_VERSION\") and set ANDROID_HOME, or set ANDROID_NDK_ROOT."
    export ANDROID_NDK_ROOT
    NDK_TOOLCHAIN="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake"
    case "$(uname -s)" in
        Linux)  NDK_HOST=linux-x86_64 ;;
        Darwin) NDK_HOST=darwin-x86_64 ;;   # the NDK ships universal binaries here
        *) die "unsupported build host $(uname -s)" ;;
    esac
    NDK_BIN="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$NDK_HOST/bin"
}

# The CMake arguments every Android-arm64 configure in this project shares.
# ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES makes every .so 16 KB-page compatible, which
# Google Play requires for new apps and Android 15+ devices may run with.
android_cmake_args() {
    find_ndk
    printf '%s\n' \
        "-DCMAKE_TOOLCHAIN_FILE=$NDK_TOOLCHAIN" \
        "-DANDROID_ABI=$ANDROID_ABI" \
        "-DANDROID_PLATFORM=android-$ANDROID_API" \
        "-DANDROID_STL=c++_shared" \
        "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON" \
        "-DCMAKE_BUILD_TYPE=Release"
}

# clone_at <url> <commit> <dir>: a checkout of exactly <commit>, reused if present.
clone_at() {
    local url="$1" commit="$2" dir="$3"
    if [[ -d "$dir/.git" ]]; then
        if [[ "$(git -C "$dir" rev-parse HEAD)" == "$commit" ]]; then
            return 0
        fi
        git -C "$dir" fetch -q origin "$commit" 2>/dev/null || git -C "$dir" fetch -q origin
    else
        log "cloning $url"
        mkdir -p "$(dirname "$dir")"
        git init -q "$dir"
        git -C "$dir" remote add origin "$url"
        # A shallow fetch of one commit; GitHub serves any reachable sha this way.
        git -C "$dir" fetch -q --depth 1 origin "$commit" || git -C "$dir" fetch -q origin
    fi
    git -C "$dir" -c advice.detachedHead=false checkout -q -f "$commit"
}

# apply_patches <repo dir> <patch files...>: apply in order, once. A stamp file in
# the repo's .git records what was applied, so re-running is a no-op and a changed
# patch set is detected (the checkout is then reset and patched again).
apply_patches() {
    local dir="$1"; shift
    [[ $# -gt 0 ]] || return 0
    local stamp="$dir/.git/android-port-patches" want
    want="$(cat "$@" | sha256sum | cut -d' ' -f1)"
    if [[ -f "$stamp" && "$(cat "$stamp")" == "$want" ]]; then
        return 0
    fi
    if [[ -f "$stamp" ]]; then
        log "patch set changed — resetting $(basename "$dir")"
        git -C "$dir" reset -q --hard
        git -C "$dir" clean -qfd -e build -e ppc -e 'build-*'
    fi
    local p
    for p in "$@"; do
        log "applying $(basename "$p") to $(basename "$dir")"
        git -C "$dir" apply --whitespace=nowarn "$p" ||
            die "$(basename "$p") does not apply to $dir — is the checkout at the pinned commit?"
    done
    echo "$want" > "$stamp"
}
