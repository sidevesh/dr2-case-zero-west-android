package io.github.sidevesh.dr2;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The player's progress: save games and achievements.
 *
 * SAVES are what the game itself wrote, file for file: the runtime mounts each save as
 * a folder (kernel/content.cpp), named by the save's XCONTENT file name, under
 * saves/&lt;game&gt;/default/ for the offline default profile. Xenia keeps a title's saves
 * the same way — one folder per save under content/.../&lt;TitleID&gt;/00000001/ — so the
 * export is that tree, zipped, and the import accepts one.
 *
 * ACHIEVEMENTS: the runtime appends "id&lt;TAB&gt;unix-time" to achievements.txt as the game
 * unlocks them (the patched kernel/imports.cpp); their names, descriptions, gamerscore
 * and icons come from the game's own SPA, which --dump-spa reads out of default.xex.
 */
final class Progress {
    private Progress() {}

    /** saves/&lt;game&gt;/ — the runtime's ContentSettingsDir(). */
    static File gameDir(Context c) { return new File(GameEnv.saves(c), BuildConfig.SAVE_FOLDER); }
    /** The offline default profile's saves (HostPaths::SavedGames() / "default"). */
    static File profileDir(Context c) { return new File(gameDir(c), "default"); }
    static File achievementsLog(Context c) { return new File(gameDir(c), "achievements.txt"); }
    static File spaFile(Context c) { return new File(c.getFilesDir(), "title.spa"); }
    static File backups(Context c) { return new File(GameEnv.root(c), "save_backups"); }

    // ---- saves -------------------------------------------------------------------------

    static final class Save {
        File dir;
        long bytes, modified;
        int files;
    }

    /** Every save folder holding at least one file (the runtime's own rule, ScanSaves). */
    static List<Save> saves(Context c) {
        List<Save> out = new ArrayList<>();
        File[] dirs = profileDir(c).listFiles(File::isDirectory);
        if (dirs == null) return out;
        Arrays.sort(dirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File d : dirs) {
            Save s = new Save();
            s.dir = d;
            File[] fs = d.listFiles(File::isFile);
            if (fs == null || fs.length == 0) continue;
            for (File f : fs) {
                s.files++;
                s.bytes += f.length();
                s.modified = Math.max(s.modified, f.lastModified());
            }
            out.add(s);
        }
        return out;
    }

    /**
     * A zip laid out like Xenia's content folder:
     *   &lt;TitleID&gt;/00000001/&lt;save&gt;/&lt;files&gt;   plus a README.txt saying where it goes.
     */
    static int exportXenia(Context c, Uri dest) throws Exception {
        List<Save> list = saves(c);
        if (list.isEmpty()) throw new Importer.Failure("There are no saves to export yet.");
        String tid = BuildConfig.TITLE_ID;
        try (OutputStream raw = c.getContentResolver().openOutputStream(dest);
             ZipOutputStream z = new ZipOutputStream(raw)) {
            if (raw == null) throw new IOException("cannot open " + dest);
            z.putNextEntry(new ZipEntry("README.txt"));
            z.write(xeniaHelp().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            z.closeEntry();
            for (Save s : list) {
                File[] fs = s.dir.listFiles(File::isFile);
                if (fs == null) continue;
                for (File f : fs) {
                    ZipEntry e = new ZipEntry(tid + "/00000001/" + s.dir.getName() + "/" + f.getName());
                    e.setTime(f.lastModified());
                    z.putNextEntry(e);
                    try (InputStream in = new FileInputStream(f)) { Fs.copy(in, z, null); }
                    z.closeEntry();
                }
            }
        }
        return list.size();
    }

    /**
     * Saves from a zip of Xenia's content folder (or of this app's export): every file at
     * .../00000001/&lt;save&gt;/&lt;file&gt; — under this game's title id when the path names one.
     * A save that already exists here is moved to save_backups/ first, never deleted.
     */
    static int importZip(Context c, Uri src) throws Exception {
        File stage = new File(c.getCacheDir(), "save_import");
        Fs.deleteTree(stage);
        if (!stage.mkdirs()) throw new IOException("cannot create " + stage);
        String tid = BuildConfig.TITLE_ID.toUpperCase();
        try (InputStream raw = c.getContentResolver().openInputStream(src);
             ZipInputStream z = new ZipInputStream(raw)) {
            if (raw == null) throw new IOException("cannot open " + src);
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String[] parts = e.getName().replace('\\', '/').split("/");
                int k = -1;
                for (int i = 0; i + 2 < parts.length; i++) if (parts[i].equals("00000001")) k = i;
                if (k < 0 || k + 3 != parts.length) continue;   // .../00000001/<save>/<file>
                // A path naming another title's id is another game's save.
                boolean otherTitle = false;
                for (int i = 0; i < k; i++)
                    if (parts[i].matches("[0-9A-Fa-f]{8}") && !parts[i].equalsIgnoreCase(tid)) otherTitle = true;
                if (otherTitle) continue;
                String save = parts[k + 1], file = parts[k + 2];
                if (!safeName(save) || !safeName(file) || save.equals("Headers")) continue;
                File out = new File(new File(stage, save), file);
                out.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(out)) { Fs.copy(z, o, null); }
            }
        }
        File[] got = stage.listFiles(File::isDirectory);
        if (got == null || got.length == 0)
            throw new Importer.Failure("No " + BuildConfig.GAME_TITLE + " saves in that zip. Expected files at "
                    + tid + "/00000001/<save name>/... (Xenia's layout).");
        File prof = profileDir(c);
        prof.mkdirs();
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
        for (File g : got) {
            File dst = new File(prof, g.getName());
            if (dst.exists()) {
                File bak = new File(new File(backups(c), stamp), g.getName());
                bak.getParentFile().mkdirs();
                if (!dst.renameTo(bak)) {
                    copyTree(dst, bak);
                    Fs.deleteTree(dst);
                }
            }
            if (!g.renameTo(dst)) {
                copyTree(g, dst);
            }
        }
        Fs.deleteTree(stage);
        return got.length;
    }

    private static boolean safeName(String s) {
        return !s.isEmpty() && !s.equals(".") && !s.equals("..") && !s.contains("/") && !s.contains("\\");
    }

    private static void copyTree(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copyTree(k, new File(dst, k.getName()));
        } else {
            Files.copy(src.toPath(), dst.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String xeniaHelp() {
        String t = BuildConfig.TITLE_ID;
        return "Saves for " + BuildConfig.GAME_TITLE + " (title ID " + t + "), in Xenia's layout.\n\n"
                + "Copy the " + t + " folder from this zip into Xenia's content folder:\n\n"
                + "  Xenia Canary (with profiles):\n"
                + "    <xenia folder>/content/<your profile's XUID>/" + t + "/00000001/<save>\n"
                + "    The XUID is the 16-character folder already in content/ (it starts with E0),\n"
                + "    created when you made a profile. Start the game in Xenia once first.\n\n"
                + "  Xenia (older builds, no profiles):\n"
                + "    <xenia folder>/content/" + t + "/00000001/<save>\n\n"
                + "Going the other way, zip the " + t + " folder from Xenia and use\n"
                + "IMPORT FROM XENIA in the Android launcher.\n\n"
                + "The save files are the game's own, byte for byte. Whether the game accepts a save\n"
                + "made under a different profile is up to the game; back up before you overwrite.\n";
    }

    // ---- achievements ------------------------------------------------------------------

    /** id -> first unlock time (ms), from the runtime's log. */
    static Map<Integer, Long> unlocked(Context c) {
        Map<Integer, Long> out = new LinkedHashMap<>();
        File f = achievementsLog(c);
        if (!f.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f)))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.trim().split("\\s+");
                if (p.length < 1 || p[0].isEmpty()) continue;
                try {
                    int id = Integer.parseInt(p[0]);
                    long t = p.length > 1 ? Long.parseLong(p[1]) * 1000L : 0L;
                    out.putIfAbsent(id, t);
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    /** The SPA, read from the game once (and again if the game data was re-imported). */
    static Xdbf spa(Context c) {
        File f = spaFile(c);
        File xex = new File(GameEnv.game(c), "default.xex");
        try {
            if (!f.isFile() || f.lastModified() < xex.lastModified()) {
                if (!xex.isFile()) return null;
                NativeBridge.runTool(new String[] { "--dump-spa", f.getAbsolutePath() },
                        GameEnv.toolLog(c).getAbsolutePath());
            }
            if (!f.isFile()) return null;
            return new Xdbf(Files.readAllBytes(f.toPath()));
        } catch (Throwable t) {
            return null;
        }
    }
}
