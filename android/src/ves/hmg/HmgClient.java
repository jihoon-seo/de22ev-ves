package ves.hmg;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small client for hmgapid, HMG-API's root daemon (abstract socket "hmgapid").
 * Request: "LFD1", u32 argc, argc x (u32 len, UTF-8 bytes). Answer: frames of
 * type 'O' (output text) and a final 'X' carrying the u32 exit code; all u32 are
 * little endian. hmgapid identifies the caller by its socket credentials, so the
 * mod's app and its services each see their own mod's settings and readings.
 */
public final class HmgClient {
    private final int timeoutMs;

    public HmgClient(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public static final class Answer {
        public int code = -1;
        public String text = "";

        public boolean ok() {
            return code == 0;
        }
    }

    /** settings as raw JSON text ("{...}"), or null when hmgapid says no */
    public String settingsJson() throws IOException {
        Answer a = call("setting-get");
        return a.ok() ? a.text.trim() : null;
    }

    /** replaces the owner's values with this JSON object text */
    public boolean saveSettingsJson(String json) throws IOException {
        return call("setting-set", json).ok();
    }

    public List<String> usbFolders() throws IOException {
        List<String> out = new ArrayList<String>();
        Answer a = call("usb");
        if (!a.ok()) return out;
        for (String l : a.text.split("\n")) if (l.trim().length() > 0) out.add(l.trim());
        return out;
    }

    /** hmgapid's answer when the last vehicle() call was refused ("" when it wasn't) */
    public volatile String lastVehicleError = "";

    /** name -> value text ("42.5", "0"/"1", "-" when the car doesn't answer) */
    public Map<String, String> vehicle(String... names) throws IOException {
        String[] args = new String[names.length + 1];
        args[0] = "vehicle";
        System.arraycopy(names, 0, args, 1, names.length);
        Map<String, String> out = new LinkedHashMap<String, String>();
        Answer a = call(args);
        if (!a.ok()) {
            lastVehicleError = "code " + a.code + ": " + a.text.trim();
            return out;
        }
        lastVehicleError = "";
        for (String l : a.text.split("\n")) {
            int sp = l.indexOf(' ');
            if (sp > 0) out.put(l.substring(0, sp), l.substring(sp + 1).trim());
        }
        return out;
    }

    public Answer call(String... args) throws IOException {
        LocalSocket s = new LocalSocket();
        try {
            s.connect(new LocalSocketAddress("hmgapid", LocalSocketAddress.Namespace.ABSTRACT));
            s.setSoTimeout(timeoutMs);
            ByteArrayOutputStream req = new ByteArrayOutputStream();
            req.write(new byte[] { 'L', 'F', 'D', '1' });
            u32(req, args.length);
            for (String a : args) {
                byte[] b = a.getBytes("UTF-8");
                u32(req, b.length);
                req.write(b);
            }
            OutputStream out = s.getOutputStream();
            out.write(req.toByteArray());
            out.flush();
            DataInputStream in = new DataInputStream(s.getInputStream());
            Answer r = new Answer();
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            while (true) {
                int type = in.read();
                if (type < 0) break;
                byte[] n = new byte[4];
                in.readFully(n);
                int len = (n[0] & 0xff) | (n[1] & 0xff) << 8 | (n[2] & 0xff) << 16 | (n[3] & 0xff) << 24;
                if (len < 0 || len > (4 << 20)) throw new IOException("hmgapid: bad frame");
                byte[] data = new byte[len];
                in.readFully(data);
                if (type == 'O') {
                    text.write(data);
                } else if (type == 'X' && len == 4) {
                    r.code = (data[0] & 0xff) | (data[1] & 0xff) << 8 | (data[2] & 0xff) << 16 | (data[3] & 0xff) << 24;
                    break;
                }
            }
            r.text = text.toString("UTF-8");
            return r;
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void u32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write(v >> 8 & 0xff);
        o.write(v >> 16 & 0xff);
        o.write(v >> 24 & 0xff);
    }
}
