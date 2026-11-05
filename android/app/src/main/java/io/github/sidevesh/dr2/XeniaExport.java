package io.github.sidevesh.dr2;

import android.content.Context;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * EXPORT TO XENIA: the player's saves and achievements, in the formats Xenia Canary
 * itself reads and writes. Everything here follows Canary's own source (checked at
 * xenia-canary 838850f, 2026-11-04) and Xenia Manager's (a905dfe, 2026-09-22):
 *
 * SAVES — Canary lists a save folder (content/&lt;XUID&gt;/&lt;TitleID&gt;/00000001/&lt;save&gt;/)
 * only when a header file sits beside it at Headers/00000001/&lt;save&gt;.header
 * (xcontent_package_directory.cc ComputeHeaderPath / ReadContentHeaderFile;
 * ContentPackage::IsValidPackage wants a valid magic and a content type). That file is
 * an XContentContainerHeader: the standard 0x971A-byte package header and metadata,
 * then Xenia's own 42-byte file name, padded to 0xA000. Xenia Manager's IMPORT SAVES
 * takes exactly this tree, zipped with &lt;TitleID&gt; at the top (SaveManager.ImportSave).
 *
 * ACHIEVEMENTS — a title GPD (&lt;TitleID&gt;.gpd), laid out the way Canary's
 * GpdInfo::Serialize writes one: XDBF header (version 0x10000), a 512-slot entry
 * table sorted by section then id, a 512-slot free table whose one used slot marks the
 * end of the data, then the entries in the order they were added — achievements
 * (section 1: X_XDBF_GPD_ACHIEVEMENT, 0x1C bytes, then label, description and
 * unachieved description as NUL-terminated UTF-16BE), the title icon and name (id
 * 0x8000), and the icons of earned achievements. Earned = flag 0x20000 and an unlock
 * time (FILETIME). Nothing in the file names a profile: it fits any XUID's folder.
 */
final class XeniaExport {
    private XeniaExport() {}

    private static final int SECTION_ACHIEVEMENT = 1, SECTION_IMAGE = 2, SECTION_STRING = 5;
    private static final long ID_TITLE = 0x8000;
    private static final int FLAG_ACHIEVED = 0x20000;
    private static final int BASE_ENTRY_COUNT = 512;
    /** 100 ns intervals between 1601-01-01 and 1970-01-01. */
    private static final long FILETIME_UNIX_EPOCH = 116444736000000000L;

    private static final class Entry {
        final int section;
        final long id;
        final byte[] data;
        int offset;
        Entry(int section, long id, byte[] data) { this.section = section; this.id = id; this.data = data; }
    }

    private static byte[] utf16z(String s) {
        byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_16BE);
        byte[] out = new byte[b.length + 2];
        System.arraycopy(b, 0, out, 0, b.length);
        return out;
    }

    static long fileTime(long unixMs) { return unixMs * 10_000L + FILETIME_UNIX_EPOCH; }

    /** The title GPD Canary would hold had these achievements been earned there. */
    static byte[] titleGpd(Xdbf spa, Map<Integer, Long> unlocked, int language) {
        List<Entry> entries = new ArrayList<>();
        Map<Integer, String> str = spa.strings(language);
        if (str.isEmpty()) str = spa.strings(1);
        List<Xdbf.Achievement> list = spa.achievements(language);
        for (Xdbf.Achievement a : list) {
            Long when = unlocked.get(a.id);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            ByteBuffer h = ByteBuffer.allocate(0x1C).order(ByteOrder.BIG_ENDIAN);
            h.putInt(0x1C);                       // magic = sizeof(X_XDBF_GPD_ACHIEVEMENT)
            h.putInt(a.id);
            h.putInt((int) a.imageId);
            h.putInt(a.score);
            h.putInt(a.flags | (when != null ? FLAG_ACHIEVED : 0));
            // An unlock with no recorded time still needs a nonzero one to read as earned
            // in tools that look at the time; the export moment is the honest stand-in.
            h.putLong(when == null ? 0L : fileTime(when > 0 ? when : System.currentTimeMillis()));
            o.write(h.array(), 0, 0x1C);
            byte[] s1 = utf16z(a.name), s2 = utf16z(a.unlockedText), s3 = utf16z(a.lockedText);
            o.write(s1, 0, s1.length);
            o.write(s2, 0, s2.length);
            o.write(s3, 0, s3.length);
            entries.add(new Entry(SECTION_ACHIEVEMENT, a.id, o.toByteArray()));
        }
        byte[] icon = spa.image(ID_TITLE);
        if (icon != null && icon.length > 0) entries.add(new Entry(SECTION_IMAGE, ID_TITLE, icon));
        String title = str.get((int) ID_TITLE);
        if (title != null && !title.isEmpty()) entries.add(new Entry(SECTION_STRING, ID_TITLE, utf16z(title)));
        // Canary adds an achievement's icon when it is unlocked; do the same.
        Set<Long> images = new LinkedHashSet<>();
        if (icon != null) images.add(ID_TITLE);
        for (Xdbf.Achievement a : list) {
            if (!unlocked.containsKey(a.id) || !images.add(a.imageId)) continue;
            byte[] png = spa.image(a.imageId);
            if (png != null && png.length > 0) entries.add(new Entry(SECTION_IMAGE, a.imageId, png));
        }
        return serialize(entries);
    }

    private static byte[] serialize(List<Entry> entries) {
        int contentSize = 0;
        for (Entry e : entries) { e.offset = contentSize; contentSize += e.data.length; }
        int entryCount = Math.max(BASE_ENTRY_COUNT,
                ((entries.size() + BASE_ENTRY_COUNT - 1) / BASE_ENTRY_COUNT) * BASE_ENTRY_COUNT);
        int freeCount = BASE_ENTRY_COUNT;
        int tables = 24 + entryCount * 18 + freeCount * 8;
        ByteBuffer b = ByteBuffer.allocate(tables + contentSize).order(ByteOrder.BIG_ENDIAN);
        b.putInt(0x58444246);          // 'XDBF'
        b.putInt(0x10000);             // version
        b.putInt(entryCount);
        b.putInt(entries.size());      // entries used
        b.putInt(freeCount);
        b.putInt(1);                   // free entries used: the end-of-data marker
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort((x, y) -> x.section != y.section ? Integer.compare(x.section, y.section)
                                                     : Long.compareUnsigned(x.id, y.id));
        for (Entry e : sorted) {
            b.putShort((short) e.section);
            b.putLong(e.id);
            b.putInt(e.offset);
            b.putInt(e.data.length);
        }
        b.position(24 + entryCount * 18);
        b.putInt(contentSize);                       // the free space starts where the data ends
        b.putInt((int) (0xFFFFFFFFL - contentSize));
        b.position(tables);
        for (Entry e : entries) b.put(e.data);
        return b.array();
    }

    /** Xenia's Headers/00000001/&lt;save&gt;.header for one save folder. */
    static byte[] saveHeader(String saveName, String displayName, long contentSize,
                             int titleId, String titleName, byte[] icon) {
        ByteBuffer b = ByteBuffer.allocate(0xA000).order(ByteOrder.BIG_ENDIAN);
        b.putInt(0x0, 0x434F4E20);        // 'CON '
        b.putInt(0x340, 0x971A);          // header_size = XContentHeader + XContentMetadata
        b.putInt(0x344, 1);               // content type: saved game
        b.putInt(0x348, 2);               // metadata version
        b.putLong(0x34C, contentSize);
        b.putInt(0x360, titleId);         // execution info: title id
        b.putInt(0x39D, 1);               // data file count
        b.putInt(0x3A9, 0);               // volume type: STFS
        putUtf16(b, 0x411, displayName, 128);   // display name, English
        putUtf16(b, 0x1691, titleName, 64);     // title name
        if (icon != null && icon.length > 0 && icon.length <= 0x3D00) {
            b.putInt(0x1712, icon.length);      // thumbnail
            b.putInt(0x1716, icon.length);      // title thumbnail
            System.arraycopy(icon, 0, b.array(), 0x171A, icon.length);
            System.arraycopy(icon, 0, b.array(), 0x571A, icon.length);
        }
        byte[] fn = saveName.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(fn, 0, b.array(), 0x971A, Math.min(fn.length, 42));   // Xenia's file_name_raw
        return b.array();
    }

    private static void putUtf16(ByteBuffer b, int at, String s, int maxChars) {
        if (s == null) return;
        int n = Math.min(s.length(), maxChars - 1);
        for (int i = 0; i < n; i++) b.putChar(at + i * 2, s.charAt(i));
    }

    // ---- the export --------------------------------------------------------------------

    static final class Result { int saves, unlocked, achievements; }

    /**
     * One zip, to unpack on the PC:
     *   HOW TO - Xenia.txt
     *   Saves/&lt;TitleID&gt;/00000001/&lt;save&gt;/...           to copy by hand
     *   Saves/&lt;TitleID&gt;/Headers/00000001/&lt;save&gt;.header
     *   Saves for Xenia Manager - &lt;TitleID&gt;.zip            its IMPORT SAVES
     *   Achievements/&lt;TitleID&gt;.gpd
     */
    static Result export(Context c, Uri dest) throws Exception {
        Result r = new Result();
        String tid = BuildConfig.TITLE_ID.toUpperCase();
        int titleId = (int) Long.parseLong(tid, 16);
        Xdbf spa = Progress.spa(c);
        int lang = RuntimeSettings.load(c).getInt("language", 1);
        String titleName = BuildConfig.GAME_TITLE;
        byte[] icon = null;
        if (spa != null) {
            String t = spa.strings(1).get((int) ID_TITLE);
            if (t != null && !t.isEmpty()) titleName = t;
            icon = spa.image(ID_TITLE);
        }
        List<Progress.Save> saves = Progress.saves(c);
        Map<Integer, Long> unlocked = Progress.unlocked(c);
        if (saves.isEmpty() && spa == null)
            throw new Importer.Failure("Nothing to export yet: no saves, and the achievement list could not be read.");

        // The save tree, once, as entries both zips share.
        List<String> names = new ArrayList<>();
        List<byte[]> blobs = new ArrayList<>();
        List<File> files = new ArrayList<>();
        for (Progress.Save s : saves) {
            File[] fs = s.dir.listFiles(File::isFile);
            if (fs == null) continue;
            for (File f : fs) {
                names.add(tid + "/00000001/" + s.dir.getName() + "/" + f.getName());
                blobs.add(null);
                files.add(f);
            }
            names.add(tid + "/Headers/00000001/" + s.dir.getName() + ".header");
            blobs.add(saveHeader(s.dir.getName(), s.dir.getName(), s.bytes, titleId, titleName, icon));
            files.add(null);
            r.saves++;
        }

        try (OutputStream raw = c.getContentResolver().openOutputStream(dest);
             ZipOutputStream z = new ZipOutputStream(raw)) {
            if (raw == null) throw new java.io.IOException("cannot open " + dest);
            put(z, "HOW TO - Xenia.txt", instructions().getBytes(StandardCharsets.UTF_8));
            if (r.saves > 0) {
                for (int i = 0; i < names.size(); i++) put(z, "Saves/" + names.get(i), blobs.get(i), files.get(i));
                // The same tree as its own zip, <TitleID>/ at the top: Xenia Manager's IMPORT SAVES.
                ByteArrayOutputStream inner = new ByteArrayOutputStream();
                try (ZipOutputStream zi = new ZipOutputStream(inner)) {
                    for (int i = 0; i < names.size(); i++) put(zi, names.get(i), blobs.get(i), files.get(i));
                }
                put(z, "Saves for Xenia Manager - " + tid + ".zip", inner.toByteArray());
            }
            if (spa != null) {
                byte[] gpd = titleGpd(spa, unlocked, lang);
                put(z, "Achievements/" + tid + ".gpd", gpd);
                r.achievements = spa.achievements(lang).size();
                for (Xdbf.Achievement a : spa.achievements(lang)) if (unlocked.containsKey(a.id)) r.unlocked++;
            }
        }
        return r;
    }

    private static void put(ZipOutputStream z, String name, byte[] data) throws java.io.IOException {
        put(z, name, data, null);
    }

    private static void put(ZipOutputStream z, String name, byte[] data, File file) throws java.io.IOException {
        ZipEntry e = new ZipEntry(name);
        if (file != null) e.setTime(file.lastModified());
        z.putNextEntry(e);
        if (data != null) {
            z.write(data);
        } else if (file != null) {
            try (InputStream in = new FileInputStream(file)) { Fs.copy(in, z, null); }
        }
        z.closeEntry();
    }

    /** Shown in the launcher and written into the zip. */
    static String instructions() {
        String t = BuildConfig.TITLE_ID.toUpperCase();
        String game = BuildConfig.GAME_TITLE;
        return "EXPORT TO XENIA — " + game + " (title ID " + t + ")\n"
                + "Unzip this on your PC first. Close Xenia before copying anything in.\n"
                + "\n"
                + "In Xenia Canary your profile has a folder named by its XUID: 16 hex characters,\n"
                + "starting with E0, inside <xenia folder>/content/. Below, <XUID> means that name.\n"
                + "\n"
                + "========================================================================\n"
                + "SAVES\n"
                + "========================================================================\n"
                + "\n"
                + "WITH XENIA MANAGER\n"
                + "  1. Open the game's content/saves view, pick your profile, choose IMPORT SAVES.\n"
                + "  2. Select \"Saves for Xenia Manager - " + t + ".zip\". It goes into that profile.\n"
                + "\n"
                + "XENIA CANARY ONLY (by hand)\n"
                + "  Copy the folder Saves/" + t + " into <xenia folder>/content/<XUID>/, merging with\n"
                + "  what is there, so you end up with:\n"
                + "    content/<XUID>/" + t + "/00000001/<save name>/...            the save files\n"
                + "    content/<XUID>/" + t + "/Headers/00000001/<save name>.header  Xenia needs this\n"
                + "  Without its .header file Xenia does not list a save, so copy both.\n"
                + "\n"
                + "A save with the same name in Xenia is replaced: back it up first if it matters.\n"
                + "\n"
                + "========================================================================\n"
                + "ACHIEVEMENTS\n"
                + "========================================================================\n"
                + "\n"
                + "The file Achievements/" + t + ".gpd is the game's achievement record, with what you\n"
                + "unlocked on the phone marked as earned at the time you earned it. It goes in:\n"
                + "    content/<XUID>/FFFE07D1/00010000/<XUID>/" + t + ".gpd\n"
                + "\n"
                + "WITH XENIA MANAGER\n"
                + "  1. Copy " + t + ".gpd into the folder above (Xenia closed).\n"
                + "  2. In Xenia Manager, run the game's achievements repair/create action. It adds\n"
                + "     the game to your profile's game list and keeps your unlocks.\n"
                + "  3. Open Xenia Canary, go to your profile's list of played games, open this\n"
                + "     game's menu and choose \"Refresh title stats\" (with no game running).\n"
                + "     Until then the game shows 0 unlocked and 0 G there and on Xenia Manager's\n"
                + "     game card, though the achievements themselves are already unlocked.\n"
                + "\n"
                + "XENIA CANARY ONLY\n"
                + "  1. FIRST start the game in Xenia Canary once, signed in to your profile, then\n"
                + "     quit it and close Xenia. This adds the game to your profile's game list.\n"
                + "     Do not skip this: Canary only reads a game's .gpd if the game is already on\n"
                + "     that list, and otherwise creates an empty one on the first start that\n"
                + "     OVERWRITES the file you copied in.\n"
                + "  2. Copy " + t + ".gpd into the folder above, replacing the one Canary made.\n"
                + "  3. Start Canary and choose \"Refresh title stats\" for the game, as above.\n"
                + "\n"
                + "THE PROFILE'S TOTAL GAMERSCORE DOES NOT CHANGE.\n"
                + "  Either way, the game's own unlocked count and gamerscore are right after\n"
                + "  \"Refresh title stats\", but your profile's overall gamerscore will not include\n"
                + "  this game's points. Canary only adds to that total when an achievement unlocks\n"
                + "  inside Canary, and nothing recalculates it.\n";
    }
}
