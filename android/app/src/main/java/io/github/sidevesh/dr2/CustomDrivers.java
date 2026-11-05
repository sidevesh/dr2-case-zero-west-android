package io.github.sidevesh.dr2;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * GPU driver packages the player brings: the zip format libadrenotools defines and
 * the Android emulators that use it (Yuzu's successors, Skyline, Winlator, …) share —
 * a meta.json beside the driver library:
 *
 *   { "schemaVersion": 1, "name": "Turnip …", "description": "…", "author": "…",
 *     "packageVersion": "1", "vendor": "Mesa", "driverVersion": "Vulkan 1.3.x",
 *     "minApi": 28, "libraryName": "libvulkan_freedreno.so" }
 *
 * Each import is unpacked into files/drivers/custom/&lt;id&gt;/ (private storage, where
 * dlopen is allowed), and loaded the way the bundled Turnip is: libadrenotools
 * redirects the system Vulkan loader to it (native/android_vulkan.cpp). That hook
 * replaces whatever driver the system loader would open, so it is not Adreno-only in
 * principle — but nearly every package in circulation is built for Adreno.
 */
final class CustomDrivers {
    private CustomDrivers() {}

    static final String PREFIX = "custom:";

    static final class Driver {
        String id, name, version, vendor, description, library;
        File dir;
        String label() {
            String v = version == null || version.isEmpty() ? "" : " (" + version + ")";
            return name + v;
        }
    }

    static File root(Context c) { return new File(new File(c.getFilesDir(), "drivers"), "custom"); }

    static List<Driver> list(Context c) {
        List<Driver> out = new ArrayList<>();
        File[] dirs = root(c).listFiles(File::isDirectory);
        if (dirs == null) return out;
        java.util.Arrays.sort(dirs);
        for (File d : dirs) {
            Driver drv = read(d);
            if (drv != null) out.add(drv);
        }
        return out;
    }

    static Driver get(Context c, String id) {
        if (id == null) return null;
        File d = new File(root(c), id);
        return d.isDirectory() ? read(d) : null;
    }

    private static Driver read(File d) {
        try {
            String text = Fs.readSmall(new File(d, "meta.json"));
            if (text == null) return null;
            JSONObject j = new JSONObject(text);
            Driver drv = new Driver();
            drv.id = d.getName();
            drv.dir = d;
            drv.name = j.optString("name", d.getName());
            drv.version = j.optString("driverVersion", j.optString("packageVersion", ""));
            drv.vendor = j.optString("vendor", "");
            drv.description = j.optString("description", "");
            drv.library = j.optString("libraryName", "");
            if (drv.library.isEmpty() || !new File(d, drv.library).isFile()) return null;
            return drv;
        } catch (Exception e) {
            return null;
        }
    }

    /** Unpacks a driver package; returns it, or throws a Failure that says what is wrong. */
    static Driver importZip(Context c, Uri src) throws Exception {
        File stage = new File(c.getCacheDir(), "driver_import");
        Fs.deleteTree(stage);
        if (!stage.mkdirs()) throw new java.io.IOException("cannot create " + stage);
        try (InputStream raw = c.getContentResolver().openInputStream(src);
             ZipInputStream z = new ZipInputStream(raw)) {
            if (raw == null) throw new java.io.IOException("cannot open " + src);
            ZipEntry e;
            long total = 0;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                // Flat, as the format is: keep only the file name, so no entry can
                // reach outside the folder.
                String name = new File(e.getName()).getName();
                if (name.isEmpty() || name.startsWith(".")) continue;
                File out = new File(stage, name);
                try (OutputStream o = new FileOutputStream(out)) {
                    total += Fs.copy(z, o, null);
                }
                if (total > 512L << 20) throw new Importer.Failure("That zip is too large to be a driver package.");
            }
        }
        File meta = new File(stage, "meta.json");
        if (!meta.isFile())
            throw new Importer.Failure("No meta.json in that zip: it is not a driver package in the "
                    + "adrenotools format (the one Android emulators use).");
        JSONObject j = new JSONObject(Fs.readSmall(meta));
        String lib = j.optString("libraryName", "");
        if (lib.isEmpty() || !new File(stage, lib).isFile())
            throw new Importer.Failure("meta.json names the driver library \"" + lib
                    + "\", but the zip does not contain it.");
        if (!isArm64Elf(new File(stage, lib)))
            throw new Importer.Failure(lib + " is not a 64-bit ARM library, so it cannot load here.");
        String name = j.optString("name", lib);
        String id = (name + "-" + j.optString("driverVersion", j.optString("packageVersion", "")))
                .replaceAll("[^A-Za-z0-9._-]+", "_");
        if (id.length() > 80) id = id.substring(0, 80);
        File dst = new File(root(c), id);
        Fs.deleteTree(dst);
        root(c).mkdirs();
        if (!stage.renameTo(dst)) throw new java.io.IOException("cannot move the driver into " + dst);
        Driver d = read(dst);
        if (d == null) throw new Importer.Failure("The driver package could not be read back.");
        return d;
    }

    static void remove(Context c, String id) {
        Fs.deleteTree(new File(root(c), id));
    }

    private static boolean isArm64Elf(File f) {
        byte[] h = new byte[20];
        try (InputStream in = new FileInputStream(f)) {
            if (in.read(h) != h.length) return false;
        } catch (Exception e) {
            return false;
        }
        // \x7FELF, 64-bit class, e_machine (little-endian) = 183 (AArch64)
        return h[0] == 0x7F && h[1] == 'E' && h[2] == 'L' && h[3] == 'F' && h[4] == 2
                && (h[18] & 0xFF) == 183 && h[19] == 0;
    }
}
