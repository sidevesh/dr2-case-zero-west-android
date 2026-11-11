# Changelog

## 1.2.1

**Case Zero**
- **Blackouts no longer stretched to 6-7 seconds.** 1.1.5's luminance hold kept the
  last good brightness reading for up to 150 frames whenever the measurement went
  black. When the black comes from the game's own exposure control, that stopped it
  recovering until the hold ran out: 150 frames is 6-7 seconds at 22-25 FPS, which is
  how long the blackouts in a 1.2.0 log lasted. The hold is now 4 frames: enough to
  ride out a glitch frame, too short to extend a dark spell.
- **A `[blackout]` line in the game log** at each blackout's start and end, with the
  game's exposure and draw count for the 24 frames before it. It shows whether the
  exposure fell before the picture went black (the game's exposure control) or the
  picture went black at a steady exposure (a rendering fault).

## 1.2.0

**Both games**
- **Cheats** (CONTROLS › CHEATS): god mode, zombies ignore you, infinite weapon
  durability — each game's own developer switches, each its own toggle, on from the
  first frame.
- **Auto-mash**: holding A, B, X or Y on the on-screen controller repeats it about 15
  times a second, for the button-mash escapes when a zombie grabs you.
- **RUN button**: above the move stick; tap it and Chuck keeps running the way you last
  moved (steer with the stick), tap again to stop.
- **In-game menu**: a small ≡ button at the top of the screen opens a side panel over
  the game, applied at once and remembered:
  - the cheats;
  - the FPS counter;
  - FSR on/off and its sharpness;
  - the touch controls: shown, opacity, size, haptics, auto-mash, RUN button;
  - gyro settings;
  - sustained performance;
  - a shortcut to the game's own pause menu.

  BACK closes it.
- Case West now has every Android fix from 1.1.x that applies to it: the 540p sizing
  fix, the bounded texture memory, the quieter log, the Balanced preset at 30 FPS and
  the performance log. (Case Zero's golden-texture cap, exposure watch and luminance
  hold do not apply: Case West has neither of those systems.)

## 1.1.5

- Case Zero: the long blackouts that end with the picture too bright. The game
  measures the scene's brightness to set its exposure; a log showed that measurement
  dropping to exactly black for up to ~8 seconds and then jumping far brighter than
  before, the exposure controller chasing a black frame. An all-black measurement
  straight after a normal one is now treated as "no new data" and the last good one is
  kept (for up to ~5 s; real dark scenes never measure exactly black). DR2_LUM_HOLD=0
  turns it off.
- The exposure watch also reports the exposure rising sharply, or becoming zero or
  invalid.

## 1.1.4

- Case Zero: an exposure watch in the game log, to find the remaining blackouts (the
  picture going black for seconds, then settling brighter). One line when the scene
  luminance the game measures jumps more than 4x (`[lum]`), and one when the exposure
  the game sets falls more than 3x within half a second (`[exposure]`); at most 400
  lines a session. DR2_EXPOSURE_WATCH=0 turns it off.

## 1.1.3

- Memory no longer grows for the whole session. Textures are recycled once the game
  has 6,144 of them (least recently used first) and their memory is given back; they
  used to stay until 65,536, gigabytes on a long session. Case Zero's "golden" texture
  store is capped at 128 MB in memory, and its file is rebuilt once it passes 96 MB
  instead of growing (and being loaded at every start) forever.
- Quieter log: the once-a-second debug chatter is left out on Android (less work and
  heat for the whole session). Advanced: DR2_VERBOSE_LOG=1 brings it back.
- The Balanced preset caps at 30 FPS, as the Xbox 360 ran the game: cooler for longer
  before the phone throttles. (Settings you already chose are kept; a Balanced setup
  from before now reads Custom until you pick a preset again.)

## 1.1.2

- Fixed a cause of the picture going black for a few seconds, again and again, at internal
  resolutions below 720p (540p). The game's automatic exposure measures the scene
  through a one-pixel-high surface, which scaled to zero rows below 720p, so the game
  read stale values and sometimes darkened the picture to black until it re-adapted.
  Sizes now never scale below one pixel; 720p and above are unchanged.

## 1.1.1

- A performance log for each game session (SYSTEM › Performance log of the last
  session): moments the picture stopped updating, with free memory, swap, GPU memory
  and the phone's thermal state, plus a line every 10 seconds — to track down
  blackouts and slowdowns that grow over a long session.

## 1.1.0

- The launcher wears each game's own artwork: its background scene and logo, read from
  the imported game files, with the colours of the game (Case Zero red, Case West dusk
  orange). Before a game is imported it keeps the plain look.
- FSR is now an explicit On / Off switch (Off is the plain stretch the PC version uses).
- Import your own GPU driver packages (the adrenotools zip format Android emulators use),
  choose them in System › GPU driver, remove them again; automatic fallback if one does
  not load or finds no GPU.
- App icons made from each game's box art at build time (original fallback icons when
  the art is not available).

## 1.0.0 — first release

Dead Rising 2: Case Zero and Case West as native Android (ARM64) apps, built from your
own copy of each game.

**Running the game**
- The upstream PC runtime of each game compiled for Android 13+ (arm64, 16 KB pages),
  with one small patch per game.
- Mesa Turnip bundled for Adreno GPUs (Mesa 26.2.6 and StevenMXZ 26.3.0-R6), loaded
  with libadrenotools, no root; automatic fallback to the other build and then the
  phone's own driver.
- DXC on the phone, so shaders the game builds while playing are translated as on PC.
- Surviving the app going to the background and back; always landscape.

**Launcher**
- Main screen: install status, whether this phone can run the game (checked
  automatically), which GPU driver the last run used, PLAY.
- Progress: achievements (from the game's own list, with icons) and saves.
- Graphics: presets, internal resolution 540p (experimental) to 1440p, FSR 1.0 upscaling,
  frame-rate cap, vsync, MSAA, shadows, zombie and world detail, field of view,
  exposure, FPS counter.
- Controls: on-screen controller (opacity, size, haptics, low-latency input, dims when
  idle), gyro aiming (off / while aiming / always, sensitivity, per-axis invert).
- System: GPU driver, Turnip rendering mode, sustained performance, skip intro logos,
  language, device check, logs, advanced environment.

**Saves and achievements**
- Achievements are recorded as you unlock them (offline profile).
- Export to Xenia: saves with Xenia's header files (and a Xenia Manager import zip) and
  achievements as a title GPD, with instructions; import saves from Xenia.

**Building**
- `scripts/build-all.sh <game> <package> [--bundle]` from a Linux PC: a small APK plus
  a shader zip, or one APK with the game inside.

**Known limitations**
- Tested on one phone (Galaxy S23 Ultra, Adreno 740) with Case Zero; Case West not yet
  reported on a device.
- Mali and PowerVR GPUs are not supported (no BC texture support).
- Online co-op is not available.
- Exporting achievements to Xenia does not change the profile's total gamerscore.
