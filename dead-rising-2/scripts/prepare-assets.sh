#!/usr/bin/env bash
# prepare-assets.sh <casezero|casewest> <your game> [--with-game]
#
# Makes, ON A PC, the one thing a phone cannot make for itself: the translated
# shader cache (assets/shader_spv). Upstream builds it at first run with DXC, and DXC
# has no Android build. The cache is plain SPIR-V, so it is made here, by the same
# translator the Android build carries, from YOUR game package, and copied over.
#
#   <your game>   the XBLA package file, or a folder holding the unpacked package
#   --with-game   also put the unpacked game (assets/game) and its generated
#                 overlays in the zip, so the phone has nothing left to do. Without
#                 it, import the package itself on the phone; the app unpacks it.
#
# Output: $OUT/<game>-android-assets.zip — import it with the app's
# IMPORT PREPARED ASSETS button. It contains data derived from your copy of the
# game: it is for your own devices only.
source "$(dirname "$0")/common.sh"
load_game "${1:-}"
INPUT="${2:-}"
WITH_GAME=0
[[ "${3:-}" == "--with-game" ]] && WITH_GAME=1
[[ -n "$INPUT" && -e "$INPUT" ]] || die "usage: $(basename "$0") $GAME_ID <package | game folder> [--with-game]"
INPUT="$(cd "$(dirname "$INPUT")" && pwd)/$(basename "$INPUT")"
PREP="$HOSTB/$GAME_ID-prep/$RUNTIME"
[[ -x "$PREP" ]] || die "run build-host-tools.sh $GAME_ID first"

ROOT="$WORK/prepare/$GAME_ID"
mkdir -p "$ROOT/assets"
export "${PFX}_ROOT=$ROOT"   # HostPaths::Root() — every path below derives from it

if [[ -d "$INPUT" ]]; then
    [[ -f "$INPUT/default.xex" ]] || die "$INPUT has no default.xex"
    rm -rf "$ROOT/assets/game"; ln -sfn "$INPUT" "$ROOT/assets/game"
elif [[ ! -f "$ROOT/assets/game/default.xex" ]]; then
    log "unpacking the package"
    rm -rf "$ROOT/assets/game"
    "$PREP" --extract-package "$INPUT" "$ROOT/assets/game" || die "could not unpack $INPUT"
fi
[[ -f "$ROOT/assets/game/data/shaders/$PS_BANK" ]] ||
    die "no data/shaders/$PS_BANK in the unpacked game — is this the $GAME_TITLE package?"

log "translating the game's shaders (uses every core; about a minute)"
"$PREP" --build-shader-cache >"$WORK/$GAME_ID-shaders.log" 2>&1 ||
    { tail -20 "$WORK/$GAME_ID-shaders.log"; die "shader pass failed (log: $WORK/$GAME_ID-shaders.log)"; }
status="$("$PREP" --shader-cache-status 2>/dev/null)"
echo "$status" >&2
grep -q 'not owed' <<<"$status" || die "the shader cache is not complete — see $WORK/$GAME_ID-shaders.log"
recipe="$(sed -n 's/^this build makes *//p' <<<"$status")"

paths=(assets/shader_spv)
if (( WITH_GAME )); then
    log "generating the overlays"
    "$PREP" --gen-overlays >"$WORK/$GAME_ID-overlays.log" 2>&1 ||
        { tail -20 "$WORK/$GAME_ID-overlays.log"; die "overlay generation failed"; }
    paths+=(assets/game)
    for d in "$ROOT"/assets/game_*; do [[ -d "$d" ]] && paths+=("assets/$(basename "$d")"); done
fi

# A manifest the app checks before unpacking: the right game, and a cache made by a
# translator recipe the Android build agrees with.
cat > "$ROOT/DR2-ANDROID-ASSETS.txt" <<EOF
game=$GAME_ID
recipe=$recipe
upstream=$UPSTREAM_COMMIT
with_game=$WITH_GAME
EOF

mkdir -p "$OUT"
ZIP="$OUT/$GAME_ID-android-assets.zip"
log "writing $ZIP"
python3 -I - "$ROOT" "$ZIP" DR2-ANDROID-ASSETS.txt "${paths[@]}" <<'PY'
import os, sys, zipfile
root, out, *items = sys.argv[1:]
with zipfile.ZipFile(out + ".part", "w") as z:
    for item in items:
        base = os.path.join(root, item)
        files = [base] if os.path.isfile(base) else [
            os.path.join(d, f) for d, _, fs in os.walk(base, followlinks=True) for f in sorted(fs)]
        for f in files:
            arc = os.path.relpath(f, root)
            # Shaders and text compress well; game archives are already compressed.
            comp = zipfile.ZIP_DEFLATED if arc.startswith("assets/shader_spv") or arc.endswith(".txt") \
                   else zipfile.ZIP_STORED
            z.write(f, arc, compress_type=comp)
os.replace(out + ".part", out)
PY
log "done: $(du -h "$ZIP" | cut -f1) — copy it to the phone and use IMPORT PREPARED ASSETS"
