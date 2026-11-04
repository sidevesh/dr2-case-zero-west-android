#!/usr/bin/env bash
# dev-export-patch.sh <casezero|casewest>
#
# For maintainers: after editing the upstream checkout in $SRC/<game> (which
# fetch-sources.sh left patched), write the whole difference from the pinned upstream
# commit back into <game>/patches/0001-android-platform-support.patch, and mark the
# checkout as carrying exactly that patch so fetch-sources.sh leaves it alone.
source "$(dirname "$0")/common.sh"
load_game "${1:-}"
[[ -d "$GAME_SRC/.git" ]] || die "no checkout at $GAME_SRC"

out="$GAME_DIR/patches/0001-android-platform-support.patch"
mkdir -p "$(dirname "$out")"
git -C "$GAME_SRC" diff --no-color --stat "$UPSTREAM_COMMIT" -- . ':!ppc' ':!runtime/kernel/import_stubs.cpp' >&2
git -C "$GAME_SRC" diff --no-color "$UPSTREAM_COMMIT" -- . ':!ppc' ':!runtime/kernel/import_stubs.cpp' > "$out"
[[ -s "$out" ]] || die "no changes against $UPSTREAM_COMMIT"
cat "$GAME_DIR"/patches/*.patch | sha256sum | cut -d' ' -f1 > "$GAME_SRC/.git/android-port-patches"
log "wrote $out"
