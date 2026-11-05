#!/usr/bin/env bash
# prepare-assets.sh <casezero|casewest> <your game> [--with-game] [--split-mib N]
#
# Makes, ON A PC, the game's translated shader cache (assets/shader_spv): ~1,400
# shaders, a few minutes here. The app can also make it on the phone (it carries DXC),
# but a PC is faster and this path is the one tested on a device. The cache is plain
# SPIR-V, made by the same translator the Android build carries, from YOUR game.
#
#   <your game>   the XBLA package file, or a folder holding the unpacked package
#   --with-game   also put the unpacked game (assets/game) and its generated
#                 overlays in the zip, so the phone has nothing left to do. Without
#                 it, import the package itself on the phone; the app unpacks it.
#   --split-mib N write the zip as parts of at most N MiB each (implies --with-game),
#                 for transfers that cap file size; select ALL the parts together in
#                 IMPORT PREPARED ASSETS.
#
# Output: $OUT/<game>-android-assets.zip — import it with the app's
# IMPORT PREPARED ASSETS button. It contains data derived from your copy of the
# game: it is for your own devices only.
source "$(dirname "$0")/common.sh"
load_game "${1:-}"
INPUT="${2:-}"
WITH_GAME=0
SPLIT_MIB=0
shift 2 || true
while [[ $# -gt 0 ]]; do
    case "$1" in
        --with-game) WITH_GAME=1; shift ;;
        --split-mib) SPLIT_MIB="${2:?--split-mib needs a size}"; WITH_GAME=1; shift 2 ;;
        *) die "unknown option $1" ;;
    esac
done
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

# A manifest the app checks before unpacking — the right game, a cache made by a
# translator recipe the Android build agrees with — first in every zip (part).
mkdir -p "$OUT"
rm -f "$OUT/$GAME_ID-android-assets"*.zip
log "writing the zip$( (( SPLIT_MIB )) && echo " in parts of at most $SPLIT_MIB MiB")"
python3 -I - "$ROOT" "$OUT/$GAME_ID-android-assets" "$SPLIT_MIB" \
    "game=$GAME_ID" "recipe=$recipe" "upstream=$UPSTREAM_COMMIT" "with_game=$WITH_GAME" \
    -- "${paths[@]}" <<'PY'
import hashlib, os, sys, zipfile
root, base, split_mib, *rest = sys.argv[1:]
sep = rest.index("--")
header, items = rest[:sep], rest[sep + 1:]
files = []
for item in items:
    top = os.path.join(root, item)
    for d, _, fs in os.walk(top, followlinks=True):
        for f in sorted(fs):
            full = os.path.join(d, f)
            files.append((os.path.relpath(full, root), full, os.path.getsize(full)))
files.sort()
# Pack into parts (largest first, first fit) so no part exceeds the cap.
cap = int(split_mib) * 2**20 - 2**20   # a MiB of headroom for zip headers
parts = [[]]
if cap > 0:
    sizes = []
    for f in sorted(files, key=lambda x: -x[2]):
        if f[2] > cap:
            sys.exit(f"{f[0]} alone is larger than --split-mib")
        for i, used in enumerate(sizes):
            if used + f[2] <= cap:
                parts[i].append(f); sizes[i] += f[2]; break
        else:
            parts.append([f]) if sizes else parts[0].append(f)
            sizes.append(f[2])
    parts = [sorted(p) for p in parts]
else:
    parts = [files]
# The set id covers the content AND how it was cut, so parts of two different
# splits of the same game can never be imported as one set.
set_id = hashlib.sha256((split_mib + "|" + "|".join(
    f"{n}:{a}" for n, part in enumerate(parts) for a, _, _ in part)).encode()).hexdigest()[:16]
for i, part in enumerate(parts, 1):
    name = base + (f".part{i}of{len(parts)}.zip" if len(parts) > 1 else ".zip")
    manifest = "\n".join(header + [f"set={set_id}", f"part={i}", f"parts={len(parts)}"]) + "\n"
    with zipfile.ZipFile(name + ".part", "w") as z:
        z.writestr("DR2-ANDROID-ASSETS.txt", manifest, compress_type=zipfile.ZIP_DEFLATED)
        for arc, full, _ in part:
            # Shaders compress well; the game's archives are compressed already.
            comp = zipfile.ZIP_DEFLATED if arc.startswith("assets/shader_spv") else zipfile.ZIP_STORED
            z.write(full, arc, compress_type=comp)
    os.replace(name + ".part", name)
    print(f"{name}  {os.path.getsize(name) / 2**20:.0f} MiB  {len(part)} files")
PY
log "done — copy it (every part) to the phone and use IMPORT PREPARED ASSETS"
