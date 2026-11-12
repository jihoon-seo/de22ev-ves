package ves.android;

import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;

import ves.hmg.VesLink;

/**
 * vesd's control socket (abstract "de22ev-ves") for the mod's app: one line in,
 * text out. Accepted callers: root, system and the mod's own app (uid looked up in
 * /data/system/packages.list).
 */
final class Control implements Runnable {
    static final String APP_PACKAGE = "io.github.jihoonseo.ves";
    private final Vesd d;

    Control(Vesd d) {
        this.d = d;
    }

    public void run() {
        LocalServerSocket server;
        try {
            server = new LocalServerSocket(VesLink.SOCKET);
        } catch (IOException e) {
            Out.log("control: cannot listen on @" + VesLink.SOCKET + ": " + e);
            return;
        }
        Out.log("control: listening on @" + VesLink.SOCKET);
        while (true) {
            final LocalSocket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                continue;
            }
            Vesd.thread("conn", new Runnable() {
                public void run() {
                    serve(s);
                }
            });
        }
    }

    void serve(LocalSocket s) {
        try {
            s.setSoTimeout(5000);
            Credentials c = s.getPeerCredentials();
            int uid = c == null ? -1 : c.getUid();
            if (!(uid == 0 || uid == 1000 || uid == appUid())) {
                reply(s, "error not allowed (uid " + uid + ")\n");
                return;
            }
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"), 512);
            String line = in.readLine();
            reply(s, handle(line == null ? "" : line.trim()));
        } catch (Throwable e) {
            try {
                reply(s, "error " + e + "\n");
            } catch (Throwable ignored) {
            }
        } finally {
            Sockets.closeAccepted(s);
        }
    }

    String handle(String line) {
        String[] a = line.split("\\s+");
        String cmd = a.length > 0 ? a[0].toUpperCase() : "";
        if (cmd.equals("STATUS")) return "ok\n" + d.status();
        if (cmd.equals("RELOAD")) {
            d.loadSettings(false);
            return "ok\n" + d.status();
        }
        if (cmd.equals("PROBE")) return d.probe.command(a, line);
        if (cmd.equals("PACK")) return pack(a);
        return "error unknown command\n";
    }

    /** PACK LIST | PACK USB | PACK IMPORT <user1..8> <folder> */
    String pack(String[] a) {
        String sub = a.length > 1 ? a[1].toUpperCase() : "";
        try {
            if (sub.equals("LIST")) return "ok\n" + d.packs.list();
            if (sub.equals("USB")) {
                StringBuilder b = new StringBuilder("ok\n");
                for (String n : PackStore.usbPacks(d.hmg.usbFolders())) b.append(n).append('\n');
                return b.toString();
            }
            if (sub.equals("IMPORT") && a.length == 4) {
                String name = d.packs.importPack(d.hmg.usbFolders(), a[3], a[2]);
                Out.log("pack: imported " + a[3] + " into " + a[2] + " (" + name + ")");
                if (a[2].equals(d.packSlot)) d.loadPack(a[2]);
                return "ok\n" + a[2] + " " + name + "\n";
            }
            return "error usage: PACK LIST | PACK USB | PACK IMPORT <user1..8> <folder>\n";
        } catch (Throwable e) {
            return "error " + e.getMessage() + "\n";
        }
    }

    private static int cachedUid = -2;

    static synchronized int appUid() {
        if (cachedUid != -2) return cachedUid;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/data/system/packages.list"));
            String l;
            while ((l = r.readLine()) != null) {
                String[] f = l.split(" ");
                if (f.length > 1 && f[0].equals(APP_PACKAGE)) {
                    cachedUid = Integer.parseInt(f[1]);
                    return cachedUid;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (IOException ignored) {
                }
            }
        }
        return -1; // not cached: the app may be installed later
    }

    static void reply(LocalSocket s, String text) throws IOException {
        OutputStream o = s.getOutputStream();
        o.write(text.getBytes("UTF-8"));
        o.flush();
    }
}
