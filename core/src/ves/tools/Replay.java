package ves.tools;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ves.core.AudioPolicy;
import ves.core.EngineParams;
import ves.core.Settings;
import ves.core.Sig;
import ves.core.VesCore;

/**
 * Renders a trace through VesCore in 10 ms blocks and reports metrics.
 * <pre>java ves.tools.Replay &lt;trace.csv|capture.hscan&gt; &lt;out.wav&gt; [k=v ...]</pre>
 * Settings k=v use the Settings.fromMap keys (profile=, mode=, volume=, ...); Replay defaults to enabled=true,
 * plus sr=&lt;Hz&gt; (default 22050).
 * CSV header {@code t_ms,speed,pedal,motor_kw,accel_g,gear,ready,brake[,event]}; an empty cell is no update; gear
 * is the raw code (0 P, 1 B, 2 ECO, 5 D, 6 N, 7 R) or a letter; events: yield_start:SRC, yield_end:SRC, mark
 * (several separated by ';').
 * .hscan: repeated [u64 LE t_ms][u32 LE len][len bytes] chunks fed to the framer.
 * The last stdout line is one JSON object of metrics (no spaces).
 */
public final class Replay {

    public static final class Result {
        public double durationS, rms, peakCap, silentFraction, renderMs, rtf;
        public int peak, maxDelta, opens, closes, nanBlocks, maxGear, shiftCount;
        public long yieldMs, yieldClosedMs, yieldAudibleMs, packets, badPackets;
        /** {t_ms, virtualGear} at every gear change (first entry = first block) */
        public final List<long[]> gearLog = new ArrayList<long[]>();
        /** times of "mark" events in order (Long.MAX_VALUE if absent) */
        public final long[] markMs = new long[8];
        public String profile = "", mode = "";

        Result() {
            for (int i = 0; i < markMs.length; i++) markMs[i] = Long.MAX_VALUE;
        }

        public String json(String file) {
            StringBuilder b = new StringBuilder(400);
            b.append('{');
            str(b, "file", file);
            str(b, "profile", profile);
            str(b, "mode", mode);
            num(b, "duration_s", durationS);
            num(b, "rms", rms);
            num(b, "peak", peak);
            num(b, "peak_cap", peakCap);
            num(b, "max_delta", maxDelta);
            num(b, "silent_fraction", silentFraction);
            num(b, "opens", opens);
            num(b, "closes", closes);
            num(b, "nan_blocks", nanBlocks);
            num(b, "yield_ms", yieldMs);
            num(b, "yield_closed_ms", yieldClosedMs);
            num(b, "yield_audible_ms", yieldAudibleMs);
            num(b, "max_gear", maxGear);
            num(b, "shifts", shiftCount);
            num(b, "packets", packets);
            num(b, "bad_packets", badPackets);
            num(b, "render_ms", renderMs);
            b.append("\"rtf\":").append(fmt(rtf)).append('}');
            return b.toString();
        }

        private static void str(StringBuilder b, String k, String v) {
            b.append('"').append(k).append("\":\"").append(v == null ? "" : v.replace("\\", "/").replace("\"", "'"))
                    .append("\",");
        }

        private static void num(StringBuilder b, String k, double v) {
            b.append('"').append(k).append("\":").append(fmt(v)).append(',');
        }
    }

    // ------------------------------------------------------------------ renderer

    private static final class Renderer {
        final VesCore core;
        final int sr;
        final Result r = new Result();
        long t, t0;
        boolean started;
        final short[] buf;
        short[] pcm = new short[1 << 16];
        int pcmN;
        double sumSq, prevGain;
        long zeros, n, renderNs;
        short prev;
        boolean havePrev;
        int lastGear = Integer.MIN_VALUE;
        int yieldsActive;
        long lastYieldStart = Long.MIN_VALUE, quietUntil = Long.MIN_VALUE;
        final Map<String, Boolean> active = new HashMap<String, Boolean>();
        int marks;

        Renderer(Settings s, int sr) {
            this.sr = sr;
            core = new VesCore(sr);
            core.setSettings(s);
            if (packForRun != null) core.setPack(packForRun);
            buf = new short[sr / 100 + 2];
            r.profile = s.profile.key();
            r.mode = s.mode;
        }

        void start(long time) {
            if (started) return;
            started = true;
            t0 = time - (time % 10 + 10) % 10;
            t = t0;
        }

        void until(long time) {
            while (t < time) block();
        }

        void block() {
            long s0 = (t - t0) * sr / 1000, s1 = (t + 10 - t0) * sr / 1000;
            int frames = (int) (s1 - s0);
            long a = System.nanoTime();
            int act = core.renderBlock(buf, frames, t);
            renderNs += System.nanoTime() - a;
            if (act == AudioPolicy.ACT_OPEN) r.opens++;
            if (act == AudioPolicy.ACT_CLOSE) r.closes++;
            double g = core.lastGain();
            if (Double.isNaN(g)) r.nanBlocks++;
            double cap = Math.max(g, prevGain) * 30000;
            if (cap > r.peakCap) r.peakCap = cap;
            prevGain = g;
            boolean nonZero = false;
            for (int i = 0; i < frames; i++) {
                short v = buf[i];
                int av = Math.abs(v);
                if (av > r.peak) r.peak = av;
                if (v == 0) zeros++;
                else nonZero = true;
                sumSq += (double) v * v;
                if (havePrev) {
                    int d = Math.abs(v - prev);
                    if (d > r.maxDelta) r.maxDelta = d;
                }
                prev = v;
                havePrev = true;
            }
            n += frames;
            if (pcmN + frames > pcm.length) {
                short[] np = new short[pcm.length * 2];
                System.arraycopy(pcm, 0, np, 0, pcmN);
                pcm = np;
            }
            System.arraycopy(buf, 0, pcm, pcmN, frames);
            pcmN += frames;
            EngineParams p = core.lastParams();
            if (p.virtualGear != lastGear) {
                if (lastGear != Integer.MIN_VALUE) r.shiftCount++;
                lastGear = p.virtualGear;
                r.gearLog.add(new long[]{t, p.virtualGear});
                if (p.virtualGear > r.maxGear) r.maxGear = p.virtualGear;
            }
            if (yieldsActive > 0) {
                r.yieldMs += 10;
                if (!core.isOpen()) r.yieldClosedMs += 10;
            }
            boolean mustBeQuiet = (yieldsActive > 0 && t >= lastYieldStart + 150) || t < quietUntil;
            if (mustBeQuiet && nonZero) r.yieldAudibleMs += 10;
            t += 10;
        }

        void event(String ev, long time) {
            String[] parts = ev.split(";");
            for (int i = 0; i < parts.length; i++) {
                String e = parts[i].trim();
                if (e.length() == 0) continue;
                if (e.equals("mark")) {
                    if (marks < r.markMs.length) r.markMs[marks++] = time;
                } else if (e.startsWith("yield_start:")) {
                    String src = e.substring(12);
                    core.onYieldStart(src, time);
                    if (!Boolean.TRUE.equals(active.get(src))) {
                        active.put(src, Boolean.TRUE);
                        yieldsActive++;
                    }
                    lastYieldStart = time;
                } else if (e.startsWith("yield_end:")) {
                    String src = e.substring(10);
                    core.onYieldEnd(src, time);
                    if (Boolean.TRUE.equals(active.get(src))) {
                        active.put(src, Boolean.FALSE);
                        yieldsActive--;
                        if (yieldsActive == 0) quietUntil = time + 1500;
                    }
                }
            }
        }

        Result finish(OutputStream wav) throws IOException {
            r.durationS = n / (double) sr;
            r.rms = n == 0 ? 0 : Math.sqrt(sumSq / n) / 32768.0;
            r.silentFraction = n == 0 ? 1 : zeros / (double) n;
            r.renderMs = renderNs / 1e6;
            r.rtf = renderNs == 0 ? 0 : r.durationS / (renderNs / 1e9);
            r.packets = core.framer().packets();
            r.badPackets = core.framer().badPackets();
            if (wav != null) writeWav(wav, pcm, pcmN, sr);
            return r;
        }
    }

    // ------------------------------------------------------------------ API

    public static Result run(String csv, Settings s, int sampleRate, OutputStream wav) throws IOException {
        Renderer rr = new Renderer(s, sampleRate);
        BufferedReader in = new BufferedReader(new StringReader(csv));
        String header = in.readLine();
        if (header == null) return rr.finish(wav);
        String[] h = header.trim().split(",", -1);
        int cT = col(h, "t_ms"), cSpeed = col(h, "speed"), cPedal = col(h, "pedal"), cKw = col(h, "motor_kw"),
                cAcc = col(h, "accel_g"), cGear = col(h, "gear"), cReady = col(h, "ready"), cBrake = col(h, "brake"),
                cEv = col(h, "event"), cMs = col(h, "motor_speed");
        if (cT < 0) throw new IOException("trace has no t_ms column");
        String line;
        long last = Long.MIN_VALUE;
        while ((line = in.readLine()) != null) {
            if (line.trim().length() == 0 || line.startsWith("#")) continue;
            String[] f = line.split(",", -1);
            long t;
            try {
                t = (long) Double.parseDouble(f[cT].trim());
            } catch (RuntimeException e) {
                continue;
            }
            rr.start(t);
            rr.until(t);
            set(rr.core, Sig.SPEED_KPH, f, cSpeed, t);
            set(rr.core, Sig.PEDAL_PCT, f, cPedal, t);
            set(rr.core, Sig.MOTOR_KW, f, cKw, t);
            set(rr.core, Sig.ACCEL_G, f, cAcc, t);
            set(rr.core, Sig.READY, f, cReady, t);
            set(rr.core, Sig.BRAKE_BAR, f, cBrake, t);
            set(rr.core, Sig.MOTOR_SPEED_KPH, f, cMs, t);
            if (cGear >= 0 && cGear < f.length) {
                double g = gear(f[cGear]);
                if (!Double.isNaN(g)) rr.core.signals().set(Sig.GEAR, g, t);
            }
            if (cEv >= 0 && cEv < f.length && f[cEv].trim().length() > 0) rr.event(f[cEv], t);
            last = t;
        }
        if (last != Long.MIN_VALUE) rr.until(last + 10);
        return rr.finish(wav);
    }

    public static Result runHscan(byte[] data, Settings s, int sampleRate, OutputStream wav) throws IOException {
        Renderer rr = new Renderer(s, sampleRate);
        int p = 0;
        long last = Long.MIN_VALUE;
        while (p + 12 <= data.length) {
            long t = 0;
            for (int i = 0; i < 8; i++) t |= (data[p + i] & 0xFFL) << (8 * i);
            long len = 0;
            for (int i = 0; i < 4; i++) len |= (data[p + 8 + i] & 0xFFL) << (8 * i);
            p += 12;
            if (len > data.length - p) len = data.length - p;
            rr.start(t);
            rr.until(t);
            rr.core.framer().feed(data, p, (int) len, t);
            p += (int) len;
            last = t;
        }
        if (last != Long.MIN_VALUE) rr.until(last + 10);
        return rr.finish(wav);
    }

    /** pack=<folder>: the sound pack the run plays (sound defaults to user1 then) */
    static ves.core.Pack packForRun;

    static ves.core.Pack loadPack(final File dir) throws IOException {
        try {
            return ves.core.PackLoader.load(new ves.core.PackLoader.Source() {
                public byte[] read(String name, int maxBytes) throws IOException {
                    File f = new File(dir, name);
                    if (f.length() > maxBytes) throw new IOException(name + " too big");
                    return readAll(f);
                }
            });
        } catch (ves.core.PackError e) {
            throw new IOException("pack " + dir + ": " + e.getMessage());
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: java ves.tools.Replay <trace.csv|capture.hscan> <out.wav> [k=v ...] [pack=<folder>]");
            System.exit(2);
        }
        Map<String, String> m = new HashMap<String, String>();
        m.put("enabled", "true");
        int sr = 22050;
        for (int i = 2; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            if (eq <= 0) continue;
            String k = args[i].substring(0, eq), v = args[i].substring(eq + 1);
            if (k.equals("sr")) {
                try {
                    sr = Integer.parseInt(v);
                } catch (RuntimeException e) {
                    sr = 22050;
                }
            } else if (k.equals("pack")) {
                packForRun = loadPack(new File(v));
            } else {
                m.put(k, v);
            }
        }
        if (packForRun != null && !m.containsKey("sound")) m.put("sound", "user1");
        Settings s = Settings.fromMap(m);
        byte[] data = readAll(new File(args[0]));
        OutputStream wav = new FileOutputStream(args[1]);
        Result r;
        try {
            if (args[0].endsWith(".hscan")) r = runHscan(data, s, sr, wav);
            else r = run(new String(data, "UTF-8"), s, sr, wav);
        } finally {
            wav.close();
        }
        System.out.println("replay " + args[0] + " settings: " + s + " sr=" + sr);
        System.out.println(r.json(args[0]));
    }

    // ------------------------------------------------------------------ helpers

    private static int col(String[] h, String name) {
        for (int i = 0; i < h.length; i++) if (h[i].trim().equalsIgnoreCase(name)) return i;
        return -1;
    }

    private static void set(VesCore c, int sig, String[] f, int col, long t) {
        if (col < 0 || col >= f.length) return;
        String v = f[col].trim();
        if (v.length() == 0) return;
        try {
            c.signals().set(sig, Double.parseDouble(v), t);
        } catch (RuntimeException ignored) {
        }
    }

    static double gear(String s) {
        String v = s.trim().toUpperCase(java.util.Locale.US);
        if (v.length() == 0) return Double.NaN;
        if (v.equals("P")) return Sig.GEAR_P;
        if (v.equals("R")) return Sig.GEAR_R;
        if (v.equals("N")) return Sig.GEAR_N;
        if (v.equals("D")) return Sig.GEAR_D;
        if (v.equals("B")) return Sig.GEAR_B;
        if (v.equals("E") || v.equals("ECO")) return Sig.GEAR_ECO;
        try {
            return Double.parseDouble(v);
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    static byte[] readAll(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.max(16, f.length()));
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }

    static void writeWav(OutputStream o, short[] pcm, int n, int sr) throws IOException {
        byte[] h = new byte[44];
        int dataLen = n * 2;
        put(h, 0, "RIFF");
        le32(h, 4, 36 + dataLen);
        put(h, 8, "WAVE");
        put(h, 12, "fmt ");
        le32(h, 16, 16);
        le16(h, 20, 1);
        le16(h, 22, 1);
        le32(h, 24, sr);
        le32(h, 28, sr * 2);
        le16(h, 32, 2);
        le16(h, 34, 16);
        put(h, 36, "data");
        le32(h, 40, dataLen);
        o.write(h);
        byte[] b = new byte[8192];
        int k = 0;
        for (int i = 0; i < n; i++) {
            b[k++] = (byte) pcm[i];
            b[k++] = (byte) (pcm[i] >> 8);
            if (k == b.length) {
                o.write(b, 0, k);
                k = 0;
            }
        }
        if (k > 0) o.write(b, 0, k);
    }

    private static void put(byte[] h, int at, String s) {
        for (int i = 0; i < 4; i++) h[at + i] = (byte) s.charAt(i);
    }

    private static void le16(byte[] h, int at, int v) {
        h[at] = (byte) v;
        h[at + 1] = (byte) (v >> 8);
    }

    private static void le32(byte[] h, int at, int v) {
        le16(h, at, v);
        le16(h, at + 2, v >> 16);
    }

    static String fmt(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "0";
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
        long m = Math.round(v * 1e6);
        String sign = m < 0 ? "-" : "";
        m = Math.abs(m);
        String fs = String.valueOf(1000000 + m % 1000000).substring(1);
        while (fs.endsWith("0")) fs = fs.substring(0, fs.length() - 1);
        return sign + (m / 1000000) + (fs.length() > 0 ? "." + fs : "");
    }
}
