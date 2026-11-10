package io.github.sidevesh.dr2;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Locale;

/**
 * A small flight recorder for the game process: watchdog.log in the data folder, read by
 * the launcher (SYSTEM › Performance log of the last session).
 *
 * It answers the questions a blackout or a stutter raises that the runtime's own log
 * cannot, because they are about the phone, not the game:
 *   - did the renderer stop presenting (a STALL: no new frame for a second or more), or
 *     did frames keep arriving while the picture was black?
 *   - how much memory is left, how much of the game has been swapped out, how much GPU
 *     memory the process holds — and how each grows over the session;
 *   - is the phone throttling (thermal status and headroom);
 *   - did Android warn the process about memory (onTrimMemory), or take its surface?
 *
 * One line every 10 s and one at each stall's start and end: a few KB an hour. Reads
 * only /proc and /sys files and the frame counter the FPS readout uses.
 */
final class PerfWatchdog {
    private static final long TICK_MS = 250, STATUS_MS = 10_000, STALL_MS = 1_000;

    private final Context mCtx;
    private final HandlerThread mThread = new HandlerThread("dr2-watchdog");
    private Handler mHandler;
    private Writer mOut;
    private final long mStart = SystemClock.elapsedRealtime();
    private long mLastCount = -1, mLastChange, mLastStatus, mStatusCount;
    private boolean mInStall;
    private long mStallStart;
    private int mStalls;
    private long mStallTotalMs, mStallWorstMs;

    PerfWatchdog(Context c) { mCtx = c.getApplicationContext(); }

    static File file(Context c) { return new File(GameEnv.root(c), "watchdog.log"); }

    void start() {
        File f = file(mCtx);
        File old = new File(f.getPath() + ".1");
        old.delete();
        f.renameTo(old);
        try {
            mOut = new FileWriter(f, false);
        } catch (IOException e) {
            return;
        }
        line("watchdog: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + ", Android " + android.os.Build.VERSION.RELEASE + ", " + GameEnv.socDescription()
                + ", driver " + GameEnv.label(mCtx, GameEnv.driver(mCtx)));
        line("columns: fps = frames presented per second since the last line; mem = MemAvailable"
                + "/MemTotal; rss/swap = this process; gpu = KGSL memory of this process (if readable);"
                + " thermal = PowerManager status (0 none .. 6 shutdown), headroom 1.0 = throttling");
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
        mHandler.post(mTick);
    }

    void stop() {
        if (mHandler == null) return;
        mHandler.removeCallbacks(mTick);
        mHandler.post(() -> {
            if (mInStall) endStall(SystemClock.elapsedRealtime(), "at exit");
            line(String.format(Locale.US, "summary: %d stall(s), %.1f s in total, worst %.1f s",
                    mStalls, mStallTotalMs / 1000.0, mStallWorstMs / 1000.0));
            try { mOut.close(); } catch (IOException ignored) {}
            mThread.quitSafely();
        });
    }

    /** Lifecycle notes from the activity (pause, trim-memory, surface lost...). */
    void note(String what) {
        if (mHandler != null) mHandler.post(() -> line("event: " + what + "  " + snapshot()));
    }

    private final Runnable mTick = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            long n;
            try {
                n = NativeBridge.presentCount();
            } catch (UnsatisfiedLinkError e) {
                return;
            }
            if (mLastCount < 0) {
                mLastCount = n; mLastChange = now; mLastStatus = now; mStatusCount = n;
            } else if (n != mLastCount) {
                if (mInStall) endStall(now, "");
                mLastCount = n;
                mLastChange = now;
            } else if (!mInStall && n > 0 && now - mLastChange >= STALL_MS) {
                mInStall = true;
                mStallStart = mLastChange;
                line("STALL: no new frame for " + (now - mLastChange) + " ms  " + snapshot());
            }
            if (now - mLastStatus >= STATUS_MS) {
                double fps = (n - mStatusCount) * 1000.0 / (now - mLastStatus);
                line(String.format(Locale.US, "status: fps %.1f  ", fps) + snapshot());
                mLastStatus = now;
                mStatusCount = n;
            }
            mHandler.postDelayed(this, TICK_MS);
        }
    };

    private void endStall(long now, String how) {
        long d = now - mStallStart;
        mInStall = false;
        mStalls++;
        mStallTotalMs += d;
        mStallWorstMs = Math.max(mStallWorstMs, d);
        line(String.format(Locale.US, "STALL ended %s: %.1f s without a new frame  ", how, d / 1000.0)
                + snapshot());
    }

    private void line(String s) {
        if (mOut == null) return;
        long t = (SystemClock.elapsedRealtime() - mStart) / 1000;
        try {
            mOut.write(String.format(Locale.US, "[%02d:%02d:%02d] %s\n", t / 3600, (t / 60) % 60, t % 60, s));
            mOut.flush();
        } catch (IOException ignored) {
        }
    }

    // ---- what the phone says ----------------------------------------------------------

    private String snapshot() {
        StringBuilder b = new StringBuilder();
        long avail = kb("/proc/meminfo", "MemAvailable:"), total = kb("/proc/meminfo", "MemTotal:");
        long rss = kb("/proc/self/status", "VmRSS:"), swap = kb("/proc/self/status", "VmSwap:");
        b.append("mem ").append(mb(avail)).append('/').append(mb(total))
                .append(" MB  rss ").append(mb(rss)).append(" MB  swap ").append(mb(swap)).append(" MB");
        long gpu = gpuBytes();
        if (gpu >= 0) b.append("  gpu ").append(gpu >> 20).append(" MB");
        PowerManager pm = mCtx.getSystemService(PowerManager.class);
        if (pm != null) {
            b.append("  thermal ").append(pm.getCurrentThermalStatus());
            float h = pm.getThermalHeadroom(10);
            if (!Float.isNaN(h)) b.append(String.format(Locale.US, " headroom %.2f", h));
        }
        return b.toString();
    }

    private static String mb(long kb) { return kb < 0 ? "?" : Long.toString(kb / 1024); }

    /** A "Name:   12345 kB" line of a /proc file, in kB; -1 if absent. */
    private static long kb(String path, String key) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            for (String l; (l = r.readLine()) != null; ) {
                if (!l.startsWith(key)) continue;
                String[] p = l.substring(key.length()).trim().split("\\s+");
                return Long.parseLong(p[0]);
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return -1;
    }

    private static boolean sGpuUnreadable;

    /** KGSL's per-process GPU memory (Adreno), where SELinux lets an app read it. */
    private static long gpuBytes() {
        if (sGpuUnreadable) return -1;
        File dir = new File("/sys/class/kgsl/kgsl/proc/" + android.os.Process.myPid());
        long sum = -1;
        for (String n : new String[] { "gpumem_mapped", "gpumem_unmapped", "imported_mem" }) {
            String s = Fs.readSmall(new File(dir, n));
            if (s == null) continue;
            try {
                sum = Math.max(sum, 0) + Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (sum < 0) sGpuUnreadable = true;
        return sum;
    }
}
