#!/usr/bin/env bash
# pack-bundled.sh <casezero|casewest> [volume MiB, default 290]
#
# One install, no imports: builds the APK with YOUR prepared game data inside
# (build-apk.sh --bundle), then compresses it into a split 7-Zip archive whose volumes
# each fit a transfer's file-size cap:
#   $OUT/<game>/DR2-<game>-BUNDLED-PRIVATE.apk.7z.001, .002, ...
# On the phone: download every volume into one folder, open the .001 in an archive
# app (ZArchiver, 7-Zip, RAR), extract the APK, install it. On first launch the app
# copies the bundled data into place, then PLAY.
#
# Needs: build-native-android.sh and prepare-assets.sh for this game first, and 7z.
# Everything it makes is derived from your copy of the game: your own devices only.
source "$(dirname "$0")/common.sh"
load_game "${1:-}"
VOL="${2:-290}"
need 7z "Debian/Ubuntu: apt install 7zip"

"$SCRIPTS/build-apk.sh" "$GAME_ID" release --bundle
APK="$GAME_OUT/DR2-$GAME_ID-release-BUNDLED-PRIVATE.apk"
[[ -f "$APK" ]] || die "no bundled APK at $APK"

BASE="$GAME_OUT/DR2-$GAME_ID-BUNDLED-PRIVATE.apk.7z"
rm -f "$BASE".[0-9][0-9][0-9]
log "compressing into ${VOL} MiB volumes (LZMA2; the game's archives are mostly compressed already)"
# The archive holds the APK under its plain installable name.
( cd "$GAME_OUT" && ln -f "$APK" "DR2-$GAME_ID.apk" &&
  7z a -t7z -mx=5 -mmt=on "-v${VOL}m" "$BASE" "DR2-$GAME_ID.apk" >/dev/null &&
  rm -f "DR2-$GAME_ID.apk" )
log "testing the archive"
7z t "$BASE.001" >/dev/null || die "the split archive does not test clean"
( cd "$GAME_OUT" && sha256sum "$(basename "$BASE")".[0-9][0-9][0-9] > "$(basename "$BASE").sha256" )
ls -la "$BASE".[0-9][0-9][0-9] >&2
log "APK $(du -h "$APK" | cut -f1) -> $(ls "$BASE".[0-9][0-9][0-9] | wc -l) volumes, $(du -ch "$BASE".[0-9][0-9][0-9] | tail -1 | cut -f1) total"
