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
import java.util.HashMap;
import java.util.HashSet;
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
     * A zip made by dead-rising-2/scripts/prepare-assets.sh on a PC: the shader cache,
     * and optionally the unpacked game. Checked for the right game, and every entry
     * must stay inside assets/.
     */
    static void importPreparedAssets(Context c, Uri uri, Ui ui) throws Failure, IOException {
        File root = GameEnv.root(c);
        String rootPath = root.getCanonicalPath() + File.separator;
        long total = size(c, uri);
        long[] read = { 0 };
        Set<String> cleared = new HashSet<>();
        boolean sawManifest = false, sawGame = false;
        ui.status("Importing prepared assets…");
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
                    if (name.equals("DR2-ANDROID-ASSETS.txt")) {
                        Map<String, String> m = readManifest(zin);
                        String game = m.get("game");
                        if (!BuildConfig.GAME_ID.equals(game))
                            throw new Failure("These assets were prepared for '" + game
                                    + "', not " + BuildConfig.GAME_TITLE + ".");
                        sawManifest = true;
                        continue;
                    }
                    if (!sawManifest)
                        throw new Failure("Not a prepared-assets zip (it must start with "
                                + "DR2-ANDROID-ASSETS.txt; make it with scripts/prepare-assets.sh).");
                    if (!name.startsWith("assets/") || e.isDirectory()) continue;
                    File out = new File(root, name);
                    if (!out.getCanonicalPath().startsWith(rootPath))
                        throw new Failure("Refusing an entry outside the data folder: " + name);
                    // Replace whole top-level folders (shader_spv, game, ...) rather than
                    // mixing an old tree with a new one.
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
        if (!sawManifest) throw new Failure("Not a prepared-assets zip (no DR2-ANDROID-ASSETS.txt).");
        if (sawGame && !GameEnv.overlaysGenerated(c)) generateOverlays(c, ui);
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
