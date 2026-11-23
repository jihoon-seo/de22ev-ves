package ves.android;

import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import ves.core.NavCam;

/**
 * Speed cameras from the stock navigation (0.2.13): a long-running, read-only logcat child on the system buffer,
 * ClusterService lines only (com.hkmc.micom.ClusterService logs every NaviCluster call), fed to NavCam; NavCam's raw
 * camera lines go to vesd.log. The child is restarted 5 s after it ends (logged once per burst of quick restarts);
 * when it can't be started at all that is logged once and retried every minute. tests/android/allowlist.sh: these
 * two commands are vesd's only child processes besides the car check's.
 */
final class NavReader implements Runnable {
    static final String[] LOGCAT = {"logcat", "-v", "time", "-b", "system", "ClusterService:D", "*:S"};
    static final String[] DUMPSYS = {"dumpsys", "com.hkmc.micom.cluster"};
    static final long RESTART_MS = 5000, UNAVAILABLE_MS = 60000, LONG_RUN_MS = 60000;
    static final int DUMP_LINES = 20;

    private final NavCam nav;
    private volatile Process child;

    NavReader(NavCam nav) {
        this.nav = nav;
    }

    public void run() {
        Runtime.getRuntime().addShutdownHook(new Thread("ves-nav-stop") {
            public void run() {
                Process p = child;
                if (p != null) p.destroy();
            }
        });
        boolean unavailableLogged = false, started = false, quiet = false;
        while (true) {
            Process p;
            try {
                p = Runtime.getRuntime().exec(LOGCAT);
            } catch (Throwable e) {
                if (!unavailableLogged) Out.log("nav: logcat unavailable (" + e + "), retry every 60 s");
                unavailableLogged = true;
                SystemClock.sleep(UNAVAILABLE_MS);
                continue;
            }
            child = p;
            if (!started) {
                started = true;
                Out.log("nav: reading ClusterService (logcat)");
                Vesd.thread("nav-dump", new Runnable() {
                    public void run() {
                        dump();
                    }
                });
            } else if (unavailableLogged) {
                Out.log("nav: logcat started");
            }
            unavailableLogged = false;
            long t0 = SystemClock.uptimeMillis();
            String why = "ended";
            BufferedReader r = null;
            try {
                p.getOutputStream().close();
                r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"), 4096);
                String l;
                while ((l = r.readLine()) != null) {
                    nav.onLine(l, System.currentTimeMillis(), SystemClock.uptimeMillis());
                    drain();
                }
            } catch (Throwable e) {
                why = String.valueOf(e);
            } finally {
                close(r);
                p.destroy();
                child = null;
            }
            String code = "";
            try {
                code = " exit " + p.waitFor();
            } catch (Throwable ignored) {
            }
            // a run that lasted a while ends the burst: its end is logged again
            if (SystemClock.uptimeMillis() - t0 >= LONG_RUN_MS) quiet = false;
            if (!quiet) Out.log("nav: logcat " + why + code + ", restarting every 5 s");
            quiet = true;
            SystemClock.sleep(RESTART_MS);
        }
    }

    /** NavCam's raw camera lines to vesd.log */
    private void drain() {
        String t = nav.takeLog();
        if (t.length() == 0) return;
        for (String row : t.split("\n")) if (row.length() > 0) Out.log(row);
    }

    /** once at start: the cluster service's own state, first lines only (the camera capture) */
    static void dump() {
        Process p = null;
        BufferedReader r = null;
        try {
            p = Runtime.getRuntime().exec(DUMPSYS);
            p.getOutputStream().close();
            r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"), 4096);
            String l;
            int n = 0;
            while (n < DUMP_LINES && (l = r.readLine()) != null) {
                if (l.trim().length() == 0) continue;
                Out.log("nav: dump " + l);
                n++;
            }
            if (n == 0) Out.log("nav: dump (empty)");
        } catch (Throwable e) {
            Out.log("nav: dump failed (" + e + ")");
        } finally {
            close(r);
            if (p != null) p.destroy();
        }
    }

    private static void close(BufferedReader r) {
        if (r == null) return;
        try {
            r.close();
        } catch (Throwable ignored) {
        }
    }
}
