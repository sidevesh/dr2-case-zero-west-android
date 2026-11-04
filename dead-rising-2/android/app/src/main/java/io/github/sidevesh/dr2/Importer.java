package io.github.sidevesh.dr2;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** The two ways game data gets onto the phone. Blocking; run off the UI thread. */
final class Importer {
    interface Ui {
        void status(String text);
        void progress(long done, long total);   // total <= 0: indeterminate
    }

    static final class Failure extends Exception {
        Failure(String m) { super(m); }
    }

    private Importer() {}

    /**
     * The XBLA package from the console, unpacked by the runtime's own extractor
     * (--extract-package), then the overlays generated (--gen-overlays).
     *
     * The extractor wants a file path. A picked document usually is a real file behind
     * its descriptor, so /proc/self/fd/N lets it read the package in place — no copy,
     * no extra gigabyte. Providers that hand out pipes (cloud storage) cannot seek;
     * then the package is copied into assets/package first.
     */
    static void importPackage(Context c, Uri uri, Ui ui) throws Failure, IOException {
        File game = GameEnv.game(c);
        String log = GameEnv.toolLog(c).getAbsolutePath();
        ui.status("Unpacking the game package…");
        ui.progress(0, 0);
        Fs.deleteTree(game);
        int rc;
        try (ParcelFileDescriptor pfd = c.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) throw new Failure("could not open the selected file");
            rc = NativeBridge.runTool(new String[] {
                    "--extract-package", "/proc/self/fd/" + pfd.getFd(), game.getAbsolutePath() }, log);
        }
        if (rc != 0) {
            // Fall back to a real copy.
            Fs.deleteTree(game);
            File dir = GameEnv.packageDir(c);
            Fs.deleteTree(dir);
            dir.mkdirs();
            File pkg = new File(dir, safeName(displayName(c, uri), BuildConfig.TITLE_ID + ".package"));
            long total = size(c, uri);
            ui.status("Copying the package (" + (total > 0 ? Fs.human(total) : "unknown size") + ")…");
            try (InputStream in = c.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(pkg)) {
                if (in == null) throw new Failure("could not read the selected file");
                Fs.copy(in, out, done -> ui.progress(done, total));
            }
            ui.status("Unpacking the game package…");
            ui.progress(0, 0);
            rc = NativeBridge.runTool(new String[] {
                    "--extract-package", pkg.getAbsolutePath(), game.getAbsolutePath() }, log);
            Fs.deleteTree(dir);   // the unpacked copy is all the game reads
        }
        if (rc != 0 || !GameEnv.gameUnpacked(c)) {
            Fs.deleteTree(game);
            throw new Failure("This is not a " + BuildConfig.GAME_TITLE + " package, or it is damaged.\n\n"
                    + Fs.tail(GameEnv.toolLog(c), 1500));
        }
        generateOverlays(c, ui);
    }

    /** The runtime's patched-asset overlays (PC options screen, prompts, strings). */
    static void generateOverlays(Context c, Ui ui) {
        ui.status("Generating the menu and prompt overlays…");
        ui.progress(0, 0);
        NativeBridge.runTool(new String[] { "--gen-overlays" }, GameEnv.toolLog(c).getAbsolutePath());
        // A failure is not fatal (the runtime says so too): the game then uses its
        // shipped menus. The tool log says what happened.
    }

    /**
     * Zips made by dead-rising-2/scripts/prepare-assets.sh on a PC: the shader cache,
     * and optionally the unpacked game — as one zip, or as parts of one set (made with
     * --split-mib for transfers that cap file size), which must be picked together.
     * Every manifest is checked first (right game, one complete set), and every entry
     * must stay inside assets/.
     */
    static void importPreparedAssets(Context c, List<Uri> uris, Ui ui) throws Failure, IOException {
        if (uris.isEmpty()) return;
        ui.status("Checking the selected file" + (uris.size() > 1 ? "s" : "") + "…");
        ui.progress(0, 0);
        List<Map<String, String>> ms = new ArrayList<>();
        for (Uri u : uris) ms.add(peekManifest(c, u));
        int parts = Integer.parseInt(ms.get(0).getOrDefault("parts", "1"));
        String set = ms.get(0).getOrDefault("set", "");
        Set<String> seen = new HashSet<>();
        for (Map<String, String> m : ms) {
            if (!BuildConfig.GAME_ID.equals(m.get("game")))
                throw new Failure("These assets were prepared for '" + m.get("game")
                        + "', not " + BuildConfig.GAME_TITLE + ".");
            if (!set.equals(m.getOrDefault("set", "")))
                throw new Failure("The selected files are parts of different sets. Pick the "
                        + "parts that were made together.");
            seen.add(m.getOrDefault("part", "1"));
        }
        if (seen.size() != uris.size() || uris.size() != parts)
            throw new Failure("This set has " + parts + " parts, and " + seen.size()
                    + " different one" + (seen.size() == 1 ? " was" : "s were")
                    + " selected. Select all " + parts + " parts together (long-press to "
                    + "select more than one file).");

        long total = 0;
        for (Uri u : uris) total = (total < 0 || size(c, u) < 0) ? -1 : total + size(c, u);
        long[] read = { 0 };
        Set<String> cleared = new HashSet<>();
        boolean sawGame = false;
        for (int i = 0; i < uris.size(); i++) {
            ui.status("Importing prepared assets" + (parts > 1 ? " (" + (i + 1) + " of " + parts + ")" : "") + "…");
            sawGame |= extractPrepared(c, uris.get(i), cleared, read, total, ui);
        }
        if (sawGame && !GameEnv.overlaysGenerated(c)) generateOverlays(c, ui);
    }

    /** The DR2-ANDROID-ASSETS.txt that must open every prepared zip. */
    private static Map<String, String> peekManifest(Context c, Uri uri) throws Failure, IOException {
        try (InputStream raw = c.getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new Failure("could not read the selected file");
            ZipInputStream zin = new ZipInputStream(raw);
            ZipEntry e = zin.getNextEntry();
            if (e == null || !e.getName().equals("DR2-ANDROID-ASSETS.txt"))
                throw new Failure("Not a prepared-assets zip (it must start with "
                        + "DR2-ANDROID-ASSETS.txt; make it with scripts/prepare-assets.sh).");
            return readManifest(zin);
        }
    }

    /** Unpacks one prepared zip (or part); returns whether it carried the game itself. */
    private static boolean extractPrepared(Context c, Uri uri, Set<String> cleared, long[] read,
                                           long total, Ui ui) throws Failure, IOException {
        File root = GameEnv.root(c);
        String rootPath = root.getCanonicalPath() + File.separator;
        boolean sawGame = false;
        try (InputStream raw = c.getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new Failure("could not read the selected file");
            InputStream counted = new java.io.FilterInputStream(raw) {
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    int n = super.read(b, off, len);
                    if (n > 0) read[0] += n;
                    return n;
                }
            };
            try (ZipInputStream zin = new ZipInputStream(counted)) {
                ZipEntry e;
                byte[] buf = new byte[1 << 20];
                while ((e = zin.getNextEntry()) != null) {
                    String name = e.getName();
                    if (!name.startsWith("assets/") || e.isDirectory()) continue;
                    File out = new File(root, name);
                    if (!out.getCanonicalPath().startsWith(rootPath))
                        throw new Failure("Refusing an entry outside the data folder: " + name);
                    // Replace whole top-level folders (shader_spv, game, ...) rather than
                    // mixing an old tree with a new one — once per import, across parts.
                    String top = name.split("/")[1];
                    if (cleared.add(top)) Fs.deleteTree(new File(GameEnv.assets(c), top));
                    if (top.equals("game")) sawGame = true;
                    //noinspection ResultOfMethodCallIgnored
                    out.getParentFile().mkdirs();
                    try (OutputStream os = new FileOutputStream(out)) {
                        int n;
                        while ((n = zin.read(buf)) > 0) os.write(buf, 0, n);
                    }
                    ui.progress(read[0], total);
                }
            }
        }
        return sawGame;
    }

    // ---- game data bundled inside the APK (build-apk.sh --bundle) ---------------------

    static final String BUNDLE_MANIFEST = "bundled/MANIFEST.txt";

    /** The manifest's header (game, stamp, files, bytes), or null for an APK with no bundle. */
    static Map<String, String> bundleHeader(Context c) {
        try (InputStream in = c.getAssets().open(BUNDLE_MANIFEST)) {
            Map<String, String> m = new HashMap<>();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null && !line.equals("--")) {
                int eq = line.indexOf('=');
                if (eq > 0) m.put(line.substring(0, eq), line.substring(eq + 1));
            }
            return m;
        } catch (IOException e) {
            return null;
        }
    }

    static File bundleStamp(Context c) {
        return new File(GameEnv.assets(c), ".bundled-stamp");
    }

    /** True when this APK carries game data that is not (or no longer) what is installed. */
    static boolean bundlePending(Context c) {
        Map<String, String> h = bundleHeader(c);
        if (h == null) return false;
        return !h.getOrDefault("stamp", "?").equals(Fs.readSmall(bundleStamp(c)));
    }

    /**
     * Copies the bundled game data out of the APK into the data root, file by file as
     * the manifest lists it. The top-level folders it carries (game, game_*,
     * shader_spv) are replaced whole, and the stamp is written only once every file
     * is in place, so an interrupted copy is simply redone on the next launch.
     */
    static void installBundled(Context c, Ui ui) throws Failure, IOException {
        Map<String, String> h = bundleHeader(c);
        if (h == null) return;
        if (!BuildConfig.GAME_ID.equals(h.get("game")))
            throw new Failure("This APK's bundled data is for '" + h.get("game") + "'.");
        long total = Long.parseLong(h.getOrDefault("bytes", "0"));
        File root = GameEnv.root(c);
        String rootPath = root.getCanonicalPath() + File.separator;
        //noinspection ResultOfMethodCallIgnored
        bundleStamp(c).delete();
        ui.status("Installing the game data bundled in this app (" + Fs.human(total)
                + ", once)…");
        Set<String> cleared = new HashSet<>();
        long done = 0;
        byte[] buf = new byte[1 << 20];
        try (InputStream min = c.getAssets().open(BUNDLE_MANIFEST)) {
            BufferedReader r = new BufferedReader(new InputStreamReader(min, StandardCharsets.UTF_8));
            String line;
            boolean body = false;
            while ((line = r.readLine()) != null) {
                if (!body) { body = line.equals("--"); continue; }
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String rel = line.substring(tab + 1);
                if (!rel.startsWith("assets/")) continue;
                File out = new File(root, rel);
                if (!out.getCanonicalPath().startsWith(rootPath))
                    throw new Failure("Refusing a bundled entry outside the data folder: " + rel);
                String top = rel.split("/")[1];
                if (cleared.add(top)) Fs.deleteTree(new File(GameEnv.assets(c), top));
                //noinspection ResultOfMethodCallIgnored
                out.getParentFile().mkdirs();
                try (InputStream in = c.getAssets().open("bundled/" + rel);
                     OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        done += n;
                        if ((done & ((8 << 20) - 1)) < n) ui.progress(done, total);
                    }
                }
            }
        }
        ui.progress(total, total);
        if (!GameEnv.gameUnpacked(c))
            throw new Failure("The bundled data did not contain the game (default.xex).");
        Fs.writeSmall(bundleStamp(c), h.getOrDefault("stamp", "?"));
    }

    private static Map<String, String> readManifest(InputStream in) throws IOException {
        Map<String, String> m = new HashMap<>();
        // Do not close: it is the zip stream.
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null) {
            int eq = line.indexOf('=');
            if (eq > 0) m.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return m;
    }

    private static String displayName(Context c, Uri uri) {
        try (Cursor cur = c.getContentResolver().query(uri,
                new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (Exception ignored) { }
        return null;
    }

    static long size(Context c, Uri uri) {
        try (Cursor cur = c.getContentResolver().query(uri,
                new String[] { OpenableColumns.SIZE }, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getLong(0);
        } catch (Exception ignored) { }
        return -1;
    }

    private static String safeName(String n, String fallback) {
        if (n == null || n.isEmpty()) return fallback;
        n = n.replaceAll("[^A-Za-z0-9._-]", "_");
        return n.startsWith(".") ? fallback : n;
    }
}
