#!/usr/bin/env bash
# build-host-tools.sh <casezero|casewest>
#
# Builds the tools that run on the build machine (x86-64 Linux):
#   $HOSTB/XenonRecomp          the recompiler: your default.xex -> C++ (recompile.sh)
#   $HOSTB/dxc                  Microsoft's DXC, for the shader pass
#   $HOSTB/<game>-prep/<runtime>  the game's own runtime, built for the HOST against the
#                               stub image. Never runs the game: only its CLI modes
#                               (--extract-package, --build-shader-cache,
#                               --shader-cache-status), which return before any guest
#                               code would. It exists because a phone cannot translate
#                               shaders (no DXC for Android), and this is the exact
#                               translator the Android build carries, so the cache it
#                               makes is the one the phone expects.
#
# Host packages (Debian/Ubuntu): clang cmake ninja-build python3 pkg-config
#   libvulkan-dev libavcodec-dev libavutil-dev
source "$(dirname "$0")/common.sh"
load_game "${1:-}"
need clang++; need cmake; need ninja; need python3; need curl; need pkg-config
[[ "$(uname -s)-$(uname -m)" == "Linux-x86_64" ]] ||
    die "the host tools are built for x86-64 Linux (WSL2 works) — this is $(uname -s)-$(uname -m)"
[[ -d "$SRC/XenonRecomp" && -d "$GAME_SRC" ]] || die "run fetch-sources.sh $GAME_ID first"
mkdir -p "$HOSTB"

# ---- XenonRecomp -------------------------------------------------------------------
if [[ ! -x "$HOSTB/XenonRecomp/XenonRecomp/XenonRecomp" ]]; then
    log "building XenonRecomp (host)"
    cmake -S "$SRC/XenonRecomp" -B "$HOSTB/XenonRecomp" -G Ninja -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ >/dev/null
    cmake --build "$HOSTB/XenonRecomp" -j"$(njobs)" >"$HOSTB/XenonRecomp.build.log" 2>&1 ||
        { tail -30 "$HOSTB/XenonRecomp.build.log"; die "XenonRecomp build failed"; }
fi

# ---- DXC ------------------------------------------------------------------------------
if [[ ! -f "$HOSTB/dxc/lib/libdxcompiler.so" ]]; then
    log "fetching DXC"
    mkdir -p "$WORK/downloads" "$HOSTB/dxc"
    tgz="$WORK/downloads/$(basename "$DXC_URL")"
    [[ -f "$tgz" ]] || curl -fsSL --retry 3 -o "$tgz" "$DXC_URL"
    echo "$DXC_SHA256  $tgz" | sha256sum -c --quiet - || die "DXC download does not match its pinned SHA-256"
    tar -C "$HOSTB/dxc" -xzf "$tgz"
fi

# ---- the host prep runtime ------------------------------------------------------------
PREPB="$HOSTB/$GAME_ID-prep"
STUB="$WORK/stub-ppc/$GAME_ID-host"
log "building the host prep runtime for $GAME_TITLE (stub image)"
rm -rf "$STUB"
( cd "$GAME_SRC" && python3 tools/gen_stub_ppc.py --runtime runtime --out "$STUB" \
      --xenon "$SRC/XenonRecomp" ) >/dev/null
cmake -S "$GAME_SRC/runtime" -B "$PREPB" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ \
    "-D${PFX}_PPC_DIR=$STUB" "-D${PFX}_WINDOW=OFF" \
    "-D${PFX}_SPLIT_DEBUG=OFF" "-D${PFX}_BUNDLE_RPATH=OFF" "-D${PFX}_XLIVE_OVERLAY=OFF" \
    -DXLIVE_WITH_CURL=OFF -DXLIVE_BUILD_TESTS=OFF \
    -DXENON_ROOT="$SRC/XenonRecomp" -DXENON_BUILD="$HOSTB/XenonRecomp" \
    -DXENOS_ROOT="$SRC/XenosRecomp" -DXLIVE_ROOT="$SRC/XenonLive" \
    >"$PREPB.configure.log" 2>&1 || { tail -30 "$PREPB.configure.log"; die "prep runtime configure failed"; }
cmake --build "$PREPB" -j"$(njobs)" >"$PREPB.build.log" 2>&1 ||
    { grep -E 'error' "$PREPB.build.log" | head -30; die "prep runtime build failed"; }
# What the runtime looks for beside its executable (HostPaths::ShippedDir).
cp "$GAME_SRC/tools/release/vs_recipes.bin" "$GAME_SRC/tools/release/prewarm.keys" "$PREPB/"
mkdir -p "$PREPB/kbm_chips" && cp "$GAME_SRC"/tools/release/kbm_chips/*.dxt "$PREPB/kbm_chips/"
mkdir -p "$PREPB/lib" && cp "$HOSTB/dxc/lib/"*.so "$PREPB/lib/"
"$PREPB/$RUNTIME" --smoke >/dev/null 2>&1 || die "the prep runtime fails its own --smoke"
log "host tools ready: $HOSTB"
