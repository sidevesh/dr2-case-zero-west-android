#!/usr/bin/env bash
# recompile.sh <casezero|casewest> <your game>
#
# Translates YOUR copy of the game's executable into C++ (<game src>/ppc), which
# build-native-android.sh then compiles for ARM64. <your game> is any one of:
#   - the XBLA package file from the console (no extension; ~825 MB Case Zero,
#     ~1.2 GB Case West), found at Content/0000000000000000/<TITLE_ID>/000D0000/
#   - a folder holding the unpacked package (it contains default.xex)
#   - default.xex itself
#
# The generated code is derived from the retail executable: keep it to yourself
# (it is gitignored upstream, and nothing in this repository may carry it).
source "$(dirname "$0")/common.sh"
load_game "${1:-}"
INPUT="${2:-}"
[[ -n "$INPUT" && -e "$INPUT" ]] || die "usage: $(basename "$0") $GAME_ID <package | game folder | default.xex>"
INPUT="$(cd "$(dirname "$INPUT")" && pwd)/$(basename "$INPUT")"
PREP="$HOSTB/$GAME_ID-prep/$RUNTIME"
RECOMP="$HOSTB/XenonRecomp/XenonRecomp/XenonRecomp"
[[ -x "$PREP" && -x "$RECOMP" ]] || die "run build-host-tools.sh $GAME_ID first"

GAMEDATA="$WORK/gamedata/$GAME_ID"
if [[ -d "$INPUT" ]]; then
    [[ -f "$INPUT/default.xex" ]] || die "$INPUT has no default.xex"
    GAMEDATA="$INPUT"
elif [[ "$(basename "$INPUT")" == *.xex ]]; then
    mkdir -p "$GAMEDATA"; ln -sfn "$INPUT" "$GAMEDATA/default.xex"
else
    if [[ ! -f "$GAMEDATA/default.xex" ]]; then
        log "unpacking the package (upstream's own STFS extractor)"
        rm -rf "$GAMEDATA"; mkdir -p "$GAMEDATA"
        "$PREP" --extract-package "$INPUT" "$GAMEDATA" || die "could not unpack $INPUT"
    fi
fi

# Upstream's TOML reads ../assets/game/default.xex and writes ../ppc, relative to config/.
mkdir -p "$GAME_SRC/assets" "$GAME_SRC/ppc"
ln -sfn "$GAMEDATA" "$GAME_SRC/assets/game"

log "recompiling $GAME_TITLE (this takes a few minutes)"
( cd "$GAME_SRC/config" && "$RECOMP" "$RECOMP_TOML" "$SRC/XenonRecomp/XenonUtils/ppc_context.h" ) \
    >"$WORK/$GAME_ID-recompile.log" 2>&1 ||
    { tail -20 "$WORK/$GAME_ID-recompile.log"; die "XenonRecomp failed (log: $WORK/$GAME_ID-recompile.log)"; }
[[ -f "$GAME_SRC/ppc/ppc_func_mapping.cpp" ]] || die "XenonRecomp wrote no ppc_func_mapping.cpp"

# As upstream's build instructions do: the kernel import stubs follow the image.
( cd "$GAME_SRC" && python3 tools/gen_import_stubs.py ) >/dev/null

log "recompiled image: $(ls "$GAME_SRC"/ppc/*.cpp | wc -l) translation units in $GAME_SRC/ppc"
log "next: scripts/build-native-android.sh $GAME_ID"
