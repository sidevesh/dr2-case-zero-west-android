#!/usr/bin/env bash
# fetch-sources.sh <casezero|casewest>
#
# Checks out, at pinned commits, everything the Android build compiles:
#   $SRC/<game>          the upstream PC port, with this repo's Android patches applied
#   $SRC/XenonRecomp     hedge-dev's recompiler + the DR2 local patch series
#   $SRC/XenosRecomp     hedge-dev's shader translator + the DR2 local patch series
#   $SRC/XenonLive       libxlive (built offline on Android)
#   $SRC/recomp-patches  Case Zero's exported recompiler patch series (see common.sh)
#
# Nothing here touches game data. Safe to re-run: finished steps are skipped.
source "$(dirname "$0")/common.sh"
load_game "${1:-}"
need git

# The game.
clone_at "$UPSTREAM_REPO" "$UPSTREAM_COMMIT" "$GAME_SRC"
shopt -s nullglob
game_patches=("$GAME_DIR"/patches/*.patch)
shopt -u nullglob
apply_patches "$GAME_SRC" "${game_patches[@]}"

# The recompiler patch series (shared by both games).
clone_at "$RECOMP_PATCHES_REPO" "$RECOMP_PATCHES_COMMIT" "$SRC/recomp-patches"

clone_at "$XENON_REPO" "$XENON_BASE" "$SRC/XenonRecomp"
log "XenonRecomp submodules"
git -C "$SRC/XenonRecomp" submodule update -q --init --recursive --depth 1
apply_patches "$SRC/XenonRecomp" "$SRC"/recomp-patches/patches/XenonRecomp/*.patch

clone_at "$XENOS_REPO" "$XENOS_BASE" "$SRC/XenosRecomp"
log "XenosRecomp submodules (dxc-bin only: the runtime compiles one XenosRecomp TU)"
git -C "$SRC/XenosRecomp" submodule update -q --init --depth 1 thirdparty/dxc-bin
apply_patches "$SRC/XenosRecomp" "$SRC"/recomp-patches/patches/XenosRecomp/*.patch

clone_at "$XLIVE_REPO" "$XLIVE_COMMIT" "$SRC/XenonLive"
clone_at "$VOLK_REPO" "$VOLK_COMMIT" "$SRC/volk"

log "sources ready under $SRC"
