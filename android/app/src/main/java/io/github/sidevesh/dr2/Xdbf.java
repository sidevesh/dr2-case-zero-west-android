package io.github.sidevesh.dr2;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A reader for the title's SPA — the XDBF container an Xbox 360 game carries its
 * achievement list in (names, descriptions, gamerscore, icons). The runtime's
 * --dump-spa writes it from the player's own default.xex.
 *
 * Layout (big-endian), after XenonLive's tools/xdbf.py, which was verified against
 * both games: a 24-byte header ('XDBF', version, entry table length, entry count,
 * free table length, free count), 18-byte entries {u16 namespace, u64 id, u32 offset,
 * u32 length}, 8-byte free entries, then the content the offsets point into.
 * Namespace 1 = sections keyed by four-character code (XACH achievements, XTHD
 * title), 2 = images (PNG), 3 = string tables keyed by language.
 */
final class Xdbf {
    static final class Achievement {
        int id, nameId, unlockedId, lockedId, score, flags;
        long imageId;
        String name, unlockedText, lockedText;
        /** Bit 3 ("show unachieved") clear: the description stays hidden until earned. */
        boolean secret() { return (flags & 0x8) == 0; }
    }

    private final ByteBuffer b;
    private final Map<Long, int[]> mEntries = new HashMap<>();   // key -> {offset, length}

    private static long key(int ns, long id) { return ((long) ns << 56) ^ id; }

    Xdbf(byte[] data) {
        b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        if (data.length < 24 || b.getInt(0) != 0x58444246) throw new IllegalArgumentException("not an XDBF");
        int entLen = b.getInt(8), entCnt = b.getInt(12), freeLen = b.getInt(16);
        if (entCnt > entLen || entLen > 65536 || freeLen > 65536) throw new IllegalArgumentException("bad XDBF header");
        int content = 24 + entLen * 18 + freeLen * 8;
        for (int i = 0; i < entCnt; i++) {
            int p = 24 + i * 18;
            int ns = b.getShort(p) & 0xFFFF;
            long id = b.getLong(p + 2);
            int off = content + b.getInt(p + 10), len = b.getInt(p + 14);
            if (off < 0 || len < 0 || (long) off + len > data.length) continue;
            mEntries.put(key(ns, id), new int[] { off, len });
        }
    }

    private static long fourcc(String s) {
        return ((long) s.charAt(0) << 24) | (s.charAt(1) << 16) | (s.charAt(2) << 8) | s.charAt(3);
    }

    private int[] entry(int ns, long id) { return mEntries.get(key(ns, id)); }

    byte[] image(long id) {
        int[] e = entry(2, id);
        if (e == null) return null;
        byte[] out = new byte[e[1]];
        System.arraycopy(b.array(), e[0], out, 0, e[1]);
        return out;
    }

    Map<Integer, String> strings(int language) {
        Map<Integer, String> out = new HashMap<>();
        int[] e = entry(3, language);
        if (e == null) return out;
        int count = b.getShort(e[0] + 12) & 0xFFFF, p = e[0] + 14, end = e[0] + e[1];
        for (int i = 0; i < count && p + 4 <= end; i++) {
            int sid = b.getShort(p) & 0xFFFF, len = b.getShort(p + 2) & 0xFFFF;
            p += 4;
            if (p + len > end) break;
            out.put(sid, new String(b.array(), p, len, StandardCharsets.UTF_8));
            p += len;
        }
        return out;
    }

    List<Achievement> achievements(int language) {
        List<Achievement> out = new ArrayList<>();
        int[] e = entry(1, fourcc("XACH"));
        if (e == null) return out;
        Map<Integer, String> str = strings(language);
        if (str.isEmpty() && language != 1) str = strings(1);
        int count = b.getShort(e[0] + 12) & 0xFFFF;
        for (int i = 0; i < count; i++) {
            int p = e[0] + 14 + i * 36;
            if (p + 36 > e[0] + e[1]) break;
            Achievement a = new Achievement();
            a.id = b.getShort(p) & 0xFFFF;
            a.nameId = b.getShort(p + 2) & 0xFFFF;
            a.unlockedId = b.getShort(p + 4) & 0xFFFF;
            a.lockedId = b.getShort(p + 6) & 0xFFFF;
            a.imageId = b.getInt(p + 8) & 0xFFFFFFFFL;
            a.score = b.getShort(p + 12) & 0xFFFF;
            a.flags = b.getInt(p + 16);
            a.name = str.getOrDefault(a.nameId, "Achievement " + a.id);
            a.unlockedText = str.getOrDefault(a.unlockedId, "");
            a.lockedText = str.getOrDefault(a.lockedId, a.unlockedText);
            out.add(a);
        }
        return out;
    }
}
