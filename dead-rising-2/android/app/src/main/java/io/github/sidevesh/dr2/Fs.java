package io.github.sidevesh.dr2;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** Small file helpers. */
final class Fs {
    private Fs() {}

    interface Progress { void bytes(long done); }

    static long copy(InputStream in, OutputStream out, Progress p) throws IOException {
        byte[] buf = new byte[1 << 20];
        long total = 0, lastReport = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            total += n;
            if (p != null && total - lastReport >= (8 << 20)) {
                p.bytes(total);
                lastReport = total;
            }
        }
        if (p != null) p.bytes(total);
        return total;
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        // Do not follow symlinks out of the tree.
        if (f.isDirectory() && !isSymlink(f)) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static boolean isSymlink(File f) {
        try {
            return !f.getCanonicalFile().equals(f.getAbsoluteFile());
        } catch (IOException e) {
            return false;
        }
    }

    static String readSmall(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = in.readAllBytes();
            return new String(b, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    static void writeSmall(File f, String s) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** The last {@code maxBytes} of a text file, from a line boundary. */
    static String tail(File f, int maxBytes) {
        if (!f.isFile()) return "(no log yet: " + f.getAbsolutePath() + ")";
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long len = r.length();
            long from = Math.max(0, len - maxBytes);
            r.seek(from);
            byte[] b = new byte[(int) (len - from)];
            r.readFully(b);
            String s = new String(b, StandardCharsets.UTF_8);
            if (from > 0) {
                int nl = s.indexOf('\n');
                if (nl >= 0) s = "…\n" + s.substring(nl + 1);
            }
            return s;
        } catch (IOException e) {
            return "(could not read " + f + ": " + e.getMessage() + ")";
        }
    }

    static String human(long bytes) {
        if (bytes >= (1L << 30)) return String.format("%.2f GB", bytes / (double) (1L << 30));
        if (bytes >= (1L << 20)) return String.format("%.0f MB", bytes / (double) (1L << 20));
        return (bytes >> 10) + " KB";
    }
}
