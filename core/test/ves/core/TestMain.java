package ves.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import ves.tools.Replay;
import ves.tools.Sweep;

/**
 * Tiny dependency-free test harness for the VES core (no JUnit on purpose).
 * Prints "PASS name" / "FAIL name: msg" per test and exits nonzero on any failure.
 * Usage: java ves.core.TestMain [substring-filter]
 */
public final class TestMain {

    // ------------------------------------------------------------------ harness

    abstract static class T {
        final String name;

        T(String name) {
            this.name = name;
        }

        abstract void run() throws Exception;
    }

    static void check(boolean c, String msg) {
        if (!c) throw new AssertionError(msg);
    }

    static void near(double actual, double expected, double tol, String msg) {
        if (!(Math.abs(actual - expected) <= tol))
            throw new AssertionError(msg + " actual=" + actual + " expected=" + expected + " tol=" + tol);
    }

    static void eq(long actual, long expected, String msg) {
        if (actual != expected) throw new AssertionError(msg + " actual=" + actual + " expected=" + expected);
    }

    public static void main(String[] args) {
        String filter = args.length > 0 ? args[0] : null;
        List<T> tests = all();
        int pass = 0, fail = 0;
        for (int i = 0; i < tests.size(); i++) {
            T t = tests.get(i);
            if (filter != null && t.name.indexOf(filter) < 0) continue;
            try {
                t.run();
                System.out.println("PASS " + t.name);
                pass++;
            } catch (Throwable e) {
                String m = e.getMessage() != null ? e.getMessage() : e.toString();
                System.out.println("FAIL " + t.name + ": " + m);
                StackTraceElement[] st = e.getStackTrace();
                for (int k = 0; k < st.length && k < 4; k++) System.out.println("    at " + st[k]);
                fail++;
            }
        }
        System.out.println("TESTS pass=" + pass + " fail=" + fail);
        System.exit(fail == 0 && pass > 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ packet helpers

    /** Builds one 264-byte hscan_mux record packet: recs[i] = {fn, d0, d1, ...}. */
    static byte[] packet(int[][] recs) {
        byte[] p = new byte[264];
        int[] h = {0xFF, 0x0B, 0x8E, 0x01, 0x8B, 0xC7, 0x01, 0x00, 0xC7};
        for (int i = 0; i < h.length; i++) p[i] = (byte) h[i];
        p[9] = (byte) (recs.length & 0xFF);
        p[10] = (byte) (recs.length >> 8);
        int pos = 11;
        for (int i = 0; i < recs.length; i++) {
            int[] r = recs[i];
            p[pos++] = (byte) r[0];
            p[pos++] = (byte) (r.length - 1);
            for (int k = 1; k < r.length; k++) p[pos++] = (byte) r[k];
        }
        checksum(p);
        return p;
    }

    static void checksum(byte[] p) {
        int x = 0;
        for (int i = 0; i < 263; i++) x ^= p[i] & 0xFF;
        p[263] = (byte) x;
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (int i = 0; i < parts.length; i++) n += parts[i].length;
        byte[] o = new byte[n];
        int p = 0;
        for (int i = 0; i < parts.length; i++) {
            System.arraycopy(parts[i], 0, o, p, parts[i].length);
            p += parts[i].length;
        }
        return o;
    }

    static final class Collect implements RecordListener {
        final List<int[]> recs = new ArrayList<int[]>();

        public void onRecord(int fn, byte[] buf, int off, int len, long tMs) {
            int[] r = new int[len + 1];
            r[0] = fn;
            for (int i = 0; i < len; i++) r[i + 1] = buf[off + i] & 0xFF;
            recs.add(r);
        }
    }

    static int[][] samplePacketRecords(int k) {
        return new int[][]{
                {0x21, k, 5 << 3, 0, 0, 100, 0x30, 0, 0},
                {0x11, (k * 7) & 0xFF, 0},
                {0x3E, 1, 2, 3, 4, 5, 6, 7, k & 0xFF}};
    }

    static void feedChunked(HscanFramer f, byte[] stream, Random rnd, int fixedChunk) {
        int pos = 0;
        while (pos < stream.length) {
            int n = fixedChunk > 0 ? fixedChunk : 1 + rnd.nextInt(600);
            n = Math.min(n, stream.length - pos);
            // copy into a fresh, offset buffer to exercise off != 0
            byte[] b = new byte[n + 5];
            System.arraycopy(stream, pos, b, 3, n);
            f.feed(b, 3, n, pos);
            pos += n;
        }
    }

    // ------------------------------------------------------------------ VesCore rig

    static final int SR = 22050;
    static final String O1_WANT = "ICE_SMOOTH=4bdf3f9889a03759 ICE_SHIFT=71d45634fd6d8230 EV_FUTURE=ee9a9545bf225a56"; // 0.1.2 output
    static final int BLOCK = 220; // 10 ms

    static Settings settings(Profile p, int volume, String mode) {
        Settings s = new Settings();
        s.enabled = true;
        s.volume = volume;
        s.profile = p;
        s.mode = mode;
        return s;
    }

    /** Drives a VesCore at 10 ms blocks while refreshing signals every 100 ms. */
    static final class Rig {
        final VesCore c;
        long t = 0;
        final short[] buf = new short[BLOCK];
        double speed = 0, pedal = 0, kw = Double.NaN, acc = Double.NaN, gear = Sig.GEAR_D, ready = 1;
        boolean feedSpeed = true;
        // stats (reset by resetStats)
        int opens, closes, lastAction;
        long firstCloseT = -1;
        double sumSq;
        long n, zeros;
        int peak, maxDelta, capViolations;
        short prevSample;
        boolean havePrev;
        double prevGain;
        long lastNonZeroT = -1;

        Rig(Settings s) {
            c = new VesCore(SR);
            c.setSettings(s);
        }

        void push() {
            SignalState s = c.signals();
            if (feedSpeed && !Double.isNaN(speed)) s.set(Sig.SPEED_KPH, speed, t);
            if (!Double.isNaN(pedal)) s.set(Sig.PEDAL_PCT, pedal, t);
            if (!Double.isNaN(kw)) s.set(Sig.MOTOR_KW, kw, t);
            if (!Double.isNaN(acc)) s.set(Sig.ACCEL_G, acc, t);
            if (!Double.isNaN(gear)) s.set(Sig.GEAR, gear, t);
            if (!Double.isNaN(ready)) s.set(Sig.READY, ready, t);
        }

        void step() {
            if (t % 100 == 0) push();
            lastAction = c.renderBlock(buf, BLOCK, t);
            // what vesd does with the action: tell the shared-channel tracker about its own calls
            if (lastAction == AudioPolicy.ACT_OPEN) c.ownStartCalled(t);
            if (lastAction == AudioPolicy.ACT_CLOSE && !c.skipEnd(t)) c.ownEndCalled(t);
            if (lastAction == AudioPolicy.ACT_OPEN) opens++;
            if (lastAction == AudioPolicy.ACT_CLOSE) {
                closes++;
                if (firstCloseT < 0) firstCloseT = t;
            }
            double g = c.lastGain();
            double cap = Math.max(g, prevGain) * 30000 + 1;
            int bp = 0;
            for (int i = 0; i < BLOCK; i++) {
                short s = buf[i];
                int a = Math.abs(s);
                if (a > bp) bp = a;
                if (s == 0) zeros++;
                else lastNonZeroT = t;
                sumSq += (double) s * s;
                if (havePrev) {
                    int d = Math.abs(s - prevSample);
                    if (d > maxDelta) maxDelta = d;
                }
                prevSample = s;
                havePrev = true;
            }
            n += BLOCK;
            if (bp > peak) peak = bp;
            if (bp > cap) capViolations++;
            prevGain = g;
            t += 10;
        }

        void run(long ms) {
            long end = t + ms;
            while (t < end) step();
        }

        void resetStats() {
            opens = closes = 0;
            firstCloseT = -1;
            sumSq = 0;
            n = zeros = 0;
            peak = maxDelta = capViolations = 0;
            lastNonZeroT = -1;
        }

        double rms() {
            return n == 0 ? 0 : Math.sqrt(sumSq / n);
        }
    }

    // ------------------------------------------------------------------ spectral helpers

    /** 2nd-order Butterworth high-pass (RBJ), for measuring what a small speaker can play. */
    static final class HighPass {
        final double b0, b1, b2, a1, a2;
        double x1, x2, y1, y2;

        HighPass(double fc, int sr) {
            double w = 2 * Math.PI * fc / sr, cs = Math.cos(w), al = Math.sin(w) / (2 * Math.sqrt(0.5));
            double a0 = 1 + al;
            b0 = (1 + cs) / 2 / a0;
            b1 = -(1 + cs) / a0;
            b2 = (1 + cs) / 2 / a0;
            a1 = -2 * cs / a0;
            a2 = (1 - al) / a0;
        }

        double next(double x) {
            double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = y;
            return y;
        }
    }

    /** Magnitude of the (Hann-windowed) DTFT of x[off..off+len) at frequency f (Goertzel). */
    static double mag(float[] x, int off, int len, double f, int sr) {
        double w = 2 * Math.PI * f / sr;
        double coeff = 2 * Math.cos(w);
        double s1 = 0, s2 = 0;
        for (int i = 0; i < len; i++) {
            double win = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (len - 1));
            double s0 = x[off + i] * win + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        double re = s1 - s2 * Math.cos(w), im = s2 * Math.sin(w);
        return Math.sqrt(re * re + im * im);
    }

    /**
     * Harmonic-sum pitch estimate over [lo, hi] Hz using the first nh harmonics: Hann window, FFT zero-padded
     * >= 2x (smooth, linearly interpolated magnitude), log grid with 0.05% steps (finer than the main lobe).
     */
    static double pitch(float[] x, int off, int len, double lo, double hi, int nh, int sr) {
        int nfft = 1;
        while (nfft < len * 2) nfft <<= 1;
        double[] re = new double[nfft], im = new double[nfft];
        for (int i = 0; i < len; i++) re[i] = x[off + i] * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (len - 1)));
        fft(re, im);
        double[] m = new double[nfft / 2 + 1];
        for (int i = 0; i < m.length; i++) m[i] = Math.sqrt(re[i] * re[i] + im[i] * im[i]);
        double best = lo, bestS = -1;
        for (double f = lo; f <= hi; f *= 1.0005) {
            double s = 0;
            for (int k = 1; k <= nh; k++) {
                double bin = k * f * nfft / sr;
                int b = (int) bin;
                if (b + 1 >= m.length) break;
                s += (m[b] + (m[b + 1] - m[b]) * (bin - b)) / Math.sqrt(k);
            }
            if (s > bestS) {
                bestS = s;
                best = f;
            }
        }
        return best;
    }

    /** in-place iterative radix-2 FFT (n power of two) */
    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = a + len / 2;
                    double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    double t = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = t;
                }
            }
        }
    }

    static float[] renderSteady(Profile prof, double rpm, double load, double regen, double gain, double seconds) {
        Synth s = new Synth(SR, prof, 7);
        EngineParams p = new EngineParams();
        p.rpm = rpm;
        p.load = load;
        p.regen = regen;
        p.targetGain = 1;
        p.speedKph = 50;
        int total = (int) (seconds * SR);
        float[] out = new float[total];
        short[] b = new short[BLOCK];
        for (int pos = 0; pos < total; pos += BLOCK) {
            int n = Math.min(BLOCK, total - pos);
            s.render(b, 0, n, p, gain);
            for (int i = 0; i < n; i++) out[pos + i] = b[i];
        }
        return out;
    }

    static void steadyModel(EngineModel m, SignalState s, Settings st, EngineParams out, long t0, long ms,
                            double speed, double pedal, double kw, double acc, double gear, double ready) {
        for (long t = t0; t < t0 + ms; t += 10) {
            if (t % 100 == 0) {
                if (!Double.isNaN(speed)) s.set(Sig.SPEED_KPH, speed, t);
                if (!Double.isNaN(pedal)) s.set(Sig.PEDAL_PCT, pedal, t);
                if (!Double.isNaN(kw)) s.set(Sig.MOTOR_KW, kw, t);
                if (!Double.isNaN(acc)) s.set(Sig.ACCEL_G, acc, t);
                if (!Double.isNaN(gear)) s.set(Sig.GEAR, gear, t);
                if (!Double.isNaN(ready)) s.set(Sig.READY, ready, t);
            }
            m.update(s, st, t, out);
        }
    }

    // ------------------------------------------------------------------ tests

    static List<T> all() {
        List<T> l = new ArrayList<T>();

        // ---------------- basics
        l.add(new T("Sig_names") {
            void run() {
                eq(Sig.COUNT, 10, "COUNT");
                check("SPEED_KPH".equals(Sig.name(Sig.SPEED_KPH)), "name speed " + Sig.name(0));
                check("DRIVE_MODE".equals(Sig.name(Sig.DRIVE_MODE)), "name drive mode");
                check(Sig.name(99) != null, "name out of range not null");
            }
        });

        l.add(new T("SignalState_stale") {
            void run() {
                SignalState s = new SignalState();
                check(Double.isNaN(s.get(Sig.SPEED_KPH, 0)), "never set => NaN");
                s.set(Sig.SPEED_KPH, 42, 1000);
                near(s.get(Sig.SPEED_KPH, 1500), 42, 0, "fresh at 500 ms");
                check(Double.isNaN(s.get(Sig.SPEED_KPH, 1501)), "stale after 500 ms");
                s.set(Sig.GEAR, 5, 1000);
                near(s.get(Sig.GEAR, 2999), 5, 0, "gear fresh at 1999 ms");
                check(Double.isNaN(s.get(Sig.GEAR, 3001)), "gear stale after 2000 ms");
                s.set(Sig.READY, 1, 1000);
                near(s.get(Sig.READY, 2900), 1, 0, "ready 2000 ms stale window");
                s.setStaleMs(Sig.SPEED_KPH, 100);
                check(Double.isNaN(s.get(Sig.SPEED_KPH, 1101)), "custom stale");
                eq(s.lastUpdate(Sig.SPEED_KPH), 1000, "lastUpdate");
                eq(s.updates(Sig.SPEED_KPH), 1, "updates");
                s.set(Sig.SPEED_KPH, 43, 1100);
                eq(s.updates(Sig.SPEED_KPH), 2, "updates 2");
                near(s.get(Sig.SPEED_KPH, 1090), 43, 0, "slightly-future sample is fresh (clock race)");
                s.set(99, 1, 0); // ignored, no throw
                check(Double.isNaN(s.get(-1, 0)), "bad index NaN");
            }
        });

        l.add(new T("Profile_Settings_parse") {
            void run() {
                check(Profile.parse("ice_shift") == Profile.ICE_SHIFT, "ice_shift");
                check(Profile.parse(" EV_FUTURE ") == Profile.EV_FUTURE, "ev_future case/trim");
                check(Profile.parse("bogus") == Profile.ICE_SMOOTH, "default");
                check(Profile.parse(null) == Profile.ICE_SMOOTH, "null");
                Settings d = new Settings();
                check(!d.enabled && d.volume == 40 && d.profile == Profile.ICE_SMOOTH && "idle_regen".equals(d.mode)
                        && d.idleLevel == 30 && d.regenLevel == 50 && "normal".equals(d.response) && d.fadeAboveKph == 0,
                        "defaults " + d);
                Map<String, String> m = new HashMap<String, String>();
                check(Settings.fromMap(m).equals(d), "empty map => defaults");
                check(Settings.fromMap(null).equals(d), "null map => defaults");
                m.put("enabled", "true");
                m.put("volume", "250");
                m.put("profile", "ev_future");
                m.put("mode", "accel_only");
                m.put("idle_level", "99");
                m.put("regen_level", "-5");
                m.put("response", "sport");
                m.put("fade_above", "80");
                Settings s = Settings.fromMap(m);
                check(s.enabled && s.volume == 100 && s.profile == Profile.EV_FUTURE && "accel_only".equals(s.mode)
                        && s.idleLevel == 60 && s.regenLevel == 0 && "sport".equals(s.response) && s.fadeAboveKph == 80,
                        "parsed/clamped " + s);
                m.put("volume", "garbage");
                m.put("mode", "warp");
                m.put("response", "");
                m.put("enabled", "nope");
                m.put("fade_above", "null");
                m.put("idle_level", "12.6");
                s = Settings.fromMap(m);
                check(!s.enabled && s.volume == 40 && "idle_regen".equals(s.mode) && "normal".equals(s.response)
                        && s.fadeAboveKph == 0 && s.idleLevel == 13, "garbage tolerant " + s);
                m.clear();
                m.put("fade_above_kph", "90");
                check(Settings.fromMap(m).fadeAboveKph == 90, "fade_above_kph alias");
                Settings a = Settings.fromMap(m), b = Settings.fromMap(m);
                check(a.equals(b) && a.hashCode() == b.hashCode(), "equals/hashCode");
                b.volume = 41;
                check(!a.equals(b), "not equal");
                check(a.toString().indexOf("volume=40") >= 0, "toString " + a);
            }
        });

        // ---------------- framer
        l.add(new T("Framer_basic") {
            void run() {
                Collect c = new Collect();
                HscanFramer f = new HscanFramer(c);
                byte[] p = packet(samplePacketRecords(1));
                f.feed(p, 0, p.length, 5);
                eq(f.packets(), 1, "packets");
                eq(f.records(), 3, "records");
                eq(f.badPackets(), 0, "bad");
                eq(c.recs.size(), 3, "delivered");
                check(c.recs.get(0)[0] == 0x21 && c.recs.get(0).length == 9 && c.recs.get(0)[5] == 100, "rec0");
                check(c.recs.get(1)[0] == 0x11 && c.recs.get(1).length == 3 && c.recs.get(1)[1] == 7, "rec1 dlc3");
                check(c.recs.get(2)[8] == 1, "rec2 last byte");
            }
        });

        l.add(new T("Framer_split") {
            void run() {
                byte[] stream = new byte[0];
                for (int k = 0; k < 7; k++) stream = concat(stream, packet(samplePacketRecords(k)));
                int[] chunks = {1, 2, 7, 11, 263, 264, 265, 527, 4000, 0};
                Random rnd = new Random(3);
                for (int ci = 0; ci < chunks.length; ci++) {
                    Collect c = new Collect();
                    HscanFramer f = new HscanFramer(c);
                    feedChunked(f, stream, rnd, chunks[ci]);
                    eq(f.packets(), 7, "packets chunk=" + chunks[ci]);
                    eq(f.records(), 21, "records chunk=" + chunks[ci]);
                    eq(f.badPackets(), 0, "bad chunk=" + chunks[ci]);
                    eq(f.resyncs(), 0, "resyncs chunk=" + chunks[ci]);
                    for (int k = 0; k < 7; k++) eq(c.recs.get(k * 3)[1], k, "order chunk=" + chunks[ci]);
                }
            }
        });

        l.add(new T("Framer_garbage_resync") {
            void run() {
                Collect c = new Collect();
                HscanFramer f = new HscanFramer(c);
                byte[] garbage = {1, 2, 3, (byte) 0xFF, 0x0B, 0x00, 9, 9};
                // a fake header 100 bytes before a real packet: its 264-byte window swallows the real header
                byte[] fake = new byte[100];
                fake[0] = (byte) 0xFF;
                fake[1] = 0x0B;
                fake[2] = (byte) 0x8E;
                fake[3] = 0x01;
                fake[4] = (byte) 0x8B;
                fake[5] = (byte) 0xC7;
                fake[6] = 0x01;
                fake[7] = 0x00;
                fake[50] = 0x55;
                byte[] stream = concat(garbage, fake, packet(samplePacketRecords(1)), garbage,
                        packet(samplePacketRecords(2)), new byte[]{(byte) 0xFF, 0x0B, (byte) 0x8E});
                feedChunked(f, stream, new Random(1), 0);
                eq(f.packets(), 2, "both real packets found");
                check(f.resyncs() >= 2, "resyncs counted " + f.resyncs());
                check(f.badPackets() >= 1, "fake header counted bad " + f.badPackets());
                eq(c.recs.size(), 6, "records");
                // a corrupted (XOR) copy of a good packet followed by a good packet
                byte[] bad = packet(samplePacketRecords(3));
                bad[30] ^= 0x40;
                f.feed(bad, 0, bad.length, 0);
                byte[] good = packet(samplePacketRecords(4));
                f.feed(good, 0, good.length, 0);
                eq(f.packets(), 3, "recovered after XOR error");
            }
        });

        l.add(new T("Framer_truncated_record") {
            void run() {
                Collect c = new Collect();
                HscanFramer f = new HscanFramer(c);
                byte[] p = packet(samplePacketRecords(1));
                p[9] = 2; // count 2 but 3 records: padding is non-zero -> reject atomically
                checksum(p);
                f.feed(p, 0, p.length, 0);
                // record whose length runs past the record area
                byte[] q = packet(new int[][]{{0x21, 1, 2, 3, 4, 5, 6, 7, 8}});
                q[9] = 2;
                q[20] = 0x18;
                q[21] = (byte) 250;
                checksum(q);
                f.feed(q, 0, q.length, 0);
                // zero-length record
                byte[] z = packet(new int[][]{{0x21, 1, 2, 3, 4, 5, 6, 7, 8}});
                z[12] = 0;
                checksum(z);
                f.feed(z, 0, z.length, 0);
                // length 9 (> CAN DLC)
                byte[] nine = packet(new int[][]{{0x21, 1, 2, 3, 4, 5, 6, 7, 8, 9}});
                f.feed(nine, 0, nine.length, 0);
                eq(f.packets(), 0, "no good packets");
                eq(f.badPackets(), 4, "4 bad packets");
                check(f.badRecords() >= 4, "bad records counted " + f.badRecords());
                eq(c.recs.size(), 0, "atomic: no record from a bad packet");
                byte[] g = packet(samplePacketRecords(5));
                f.feed(g, 0, g.length, 0);
                eq(f.packets(), 1, "good packet after bad ones");
            }
        });

        l.add(new T("Framer_count_mismatch") {
            void run() {
                Collect c = new Collect();
                HscanFramer f = new HscanFramer(c);
                byte[] p = packet(samplePacketRecords(1));
                p[9] = 4; // claims 4, has 3: 4th header is padding (len 0)
                checksum(p);
                f.feed(p, 0, p.length, 0);
                byte[] q = packet(samplePacketRecords(1));
                q[9] = 85; // more than can fit
                checksum(q);
                f.feed(q, 0, q.length, 0);
                byte[] r = packet(samplePacketRecords(1));
                r[10] = 1; // count 0x0103
                checksum(r);
                f.feed(r, 0, r.length, 0);
                byte[] e = packet(new int[0][]); // zero records is valid (empty packet)
                f.feed(e, 0, e.length, 0);
                eq(f.badPackets(), 3, "bad");
                eq(f.packets(), 1, "empty packet ok");
                eq(f.records(), 0, "no records");
                // a valid frame with another MICOM function is ignored, not bad
                byte[] other = packet(samplePacketRecords(1));
                other[5] = (byte) 0xC8;
                checksum(other);
                f.feed(other, 0, other.length, 0);
                eq(f.badPackets(), 3, "other fn not bad");
                eq(f.ignoredPackets(), 1, "other fn ignored");
                eq(c.recs.size(), 0, "nothing delivered");
            }
        });

        l.add(new T("Framer_fuzz_10k") {
            void run() {
                Random rnd = new Random(20261104L);
                final int[] got = new int[1];
                HscanFramer f = new HscanFramer(new RecordListener() {
                    public void onRecord(int fn, byte[] buf, int off, int len, long tMs) {
                        if (fn < 0 || fn > 255 || len < 1 || len > 8 || off < 0 || off + len > buf.length)
                            throw new AssertionError("bad record callback");
                        got[0]++;
                    }
                });
                int inserted = 0;
                byte[] hdr = {(byte) 0xFF, 0x0B, (byte) 0x8E, 0x01, (byte) 0x8B, (byte) 0xC7, 0x01, 0x00, (byte) 0xC7};
                for (int it = 0; it < 10000; it++) {
                    byte[] b = new byte[rnd.nextInt(700)];
                    rnd.nextBytes(b);
                    int sprinkle = rnd.nextInt(10) == 0 ? 1 + rnd.nextInt(3) : 0;
                    for (int k = 0; k < sprinkle && b.length > 12; k++) {
                        int at = rnd.nextInt(b.length - 9);
                        System.arraycopy(hdr, 0, b, at, 4 + rnd.nextInt(6));
                    }
                    if (rnd.nextInt(3) == 0) {
                        int[][] recs = new int[1 + rnd.nextInt(5)][];
                        for (int k = 0; k < recs.length; k++) {
                            recs[k] = new int[2 + rnd.nextInt(8)];
                            for (int q = 0; q < recs[k].length; q++) recs[k][q] = rnd.nextInt(256);
                        }
                        byte[] p = packet(recs);
                        b = concat(b, p);
                        inserted++;
                    }
                    int pos = 0;
                    while (pos < b.length) {
                        int n = Math.min(b.length - pos, 1 + rnd.nextInt(400));
                        f.feed(b, pos, n, it);
                        pos += n;
                    }
                    if (it % 1000 == 0) {
                        f.feed(null, 0, 10, 0);
                        f.feed(b, -1, 5, 0);
                        f.feed(b, 0, b.length + 10, 0);
                        f.feed(b, 0, -3, 0);
                    }
                }
                check(f.packets() >= inserted * 0.95, "found " + f.packets() + " of " + inserted);
                check(f.packets() <= inserted + 5, "phantom packets " + f.packets() + " vs " + inserted);
                check(got[0] == f.records(), "records counter");
                System.out.println("    fuzz: inserted=" + inserted + " packets=" + f.packets() + " bad="
                        + f.badPackets() + " resyncs=" + f.resyncs() + " ignored=" + f.ignoredPackets());
            }
        });

        // ---------------- decoder
        l.add(new T("EvDecoder_signals") {
            void run() {
                SignalState s = new SignalState();
                EvDecoder d = new EvDecoder(s);
                HscanFramer f = new HscanFramer(d);
                byte[] p = packet(new int[][]{
                        // VCU1: gear D (5<<3) | bit7 noise, pedal raw 128, data[5]: Vcu_Rdy(b4)+Ev_Rdy(b5)
                        {0x21, 0xAA, (5 << 3) | 0x80 | 0x07, 0, 0, 128, 0x30, 0, 0},
                        // MCU3: data[2]=77 km/h, torque raw 0x123 (data[5] upper nibble is noise), kW -10
                        {0x18, 0, 0, 77, 0, 0x23, 0xF1, 0, 0xF6},
                        // EV_CP2 dlc 3: (0x2C | 1<<8) * 0.5 = 150 km/h; data[1] bits 1..3 are noise
                        {0x11, 0x2C, 0x01 | 0x0E, 0},
                        // YRS12: raw 36000
                        {0x28, 36000 & 0xFF, 36000 >> 8, 0, 0, 0, 0, 0, 0},
                        // ESP12: data[3]=0xA8 (>>2 = 42), data[4]=0xC5 (&63 = 5) -> 362 -> 36.2 bar
                        {0x3E, 0, 0, 0, 0xA8, 0xC5, 0, 0, 0},
                        // VCU5: data[7] = 3<<2 | 0xC3 noise bits outside the field
                        {0x26, 0, 0, 0, 0, 0, 0, 0, (3 << 2) | 0xC3},
                        {0x99, 1, 2}});
                f.feed(p, 0, p.length, 1000);
                eq(f.packets(), 1, "packet");
                near(s.get(Sig.PEDAL_PCT, 1000), 128 * 100.0 / 255, 1e-6, "pedal");
                near(s.get(Sig.GEAR, 1000), Sig.GEAR_D, 0, "gear");
                near(s.get(Sig.READY, 1000), 1, 0, "ready");
                near(s.get(Sig.MOTOR_SPEED_KPH, 1000), 77, 0, "motor speed");
                near(s.get(Sig.TORQUE_PCT, 1000), 0x123 * 0.048828, 1e-9, "torque");
                near(s.get(Sig.MOTOR_KW, 1000), -10, 0, "signed motor kW");
                near(s.get(Sig.SPEED_KPH, 1000), 150, 1e-9, "speed");
                near(s.get(Sig.ACCEL_G, 1000), 36000 * 1.27465e-4 - 4.17677, 1e-9, "accel g");
                near(s.get(Sig.BRAKE_BAR, 1000), 36.2, 1e-9, "brake");
                near(s.get(Sig.DRIVE_MODE, 1000), 3, 0, "drive mode");
                eq(d.count(0x21), 1, "count 0x21");
                eq(d.count(0x99), 1, "count unknown fn");
                eq(d.count(0x00), 0, "count 0");
                eq(d.count(999), 0, "count out of range");
                int[] k = d.knownFns();
                String ks = "";
                for (int i = 0; i < k.length; i++) ks += Integer.toHexString(k[i]) + ",";
                check(ks.equals("11,18,21,26,28,3e,"), "knownFns " + ks);
                // gears / ready bits / positive kW
                f.feed(packet(new int[][]{{0x21, 0, 7 << 3, 0, 0, 255, 0x10, 0, 0}, {0x18, 0, 0, 0, 0, 0, 0, 0, 100}}),
                        0, 264, 1100);
                near(s.get(Sig.GEAR, 1100), Sig.GEAR_R, 0, "gear R");
                near(s.get(Sig.READY, 1100), 0, 0, "only Vcu_Rdy set => READY 0 (READY follows Ev_Rdy)");
                near(s.get(Sig.PEDAL_PCT, 1100), 100, 1e-6, "pedal 255 => 100%");
                near(s.get(Sig.MOTOR_KW, 1100), 100, 0, "positive kW");
                f.feed(packet(new int[][]{{0x21, 0, 0, 0, 0, 0, 0x20, 0, 0}, {0x18, 0, 0, 0, 0, 0, 0, 0, 0x80}}),
                        0, 264, 1200);
                near(s.get(Sig.GEAR, 1200), Sig.GEAR_P, 0, "gear P");
                near(s.get(Sig.READY, 1200), 1, 0, "Ev_Rdy alone => READY 1");
                near(s.get(Sig.MOTOR_KW, 1200), -128, 0, "kW -128");
                // short record: VCU1 with dlc 3 cannot carry pedal -> pedal unchanged, no exception
                f.feed(packet(new int[][]{{0x21, 0, 6 << 3, 0}}), 0, 264, 1300);
                near(s.get(Sig.GEAR, 1300), Sig.GEAR_N, 0, "gear N from short record");
                near(s.get(Sig.PEDAL_PCT, 1250), 0, 1e-9, "pedal kept from previous record");
                check(d.shortRecords() >= 1, "short records counted");
                // sentinel: EV_CP2 raw 0x1FF => unavailable
                f.feed(packet(new int[][]{{0x11, 0xFF, 0x01, 0}}), 0, 264, 1400);
                check(Double.isNaN(s.get(Sig.SPEED_KPH, 1400)), "speed sentinel => NaN");
            }
        });

        l.add(new T("EvDecoder_table_override") {
            void run() {
                SignalState s = new SignalState();
                EvDecoder d = new EvDecoder(s, "# comment\n0x05 SPEED_KPH 8 8 u 2 1\nbad line here\n0x06 MOTOR_KW 0 16 s 0.5 0\n");
                check(d.tableErrors() == 1, "1 bad line " + d.tableErrors());
                byte[] b = {9, 10, 0, 0};
                d.onRecord(0x05, b, 0, 2, 50);
                near(s.get(Sig.SPEED_KPH, 50), 21, 0, "custom field");
                byte[] c = {(byte) 0x38, (byte) 0xFF}; // -200 * 0.5
                d.onRecord(0x06, c, 0, 2, 50);
                near(s.get(Sig.MOTOR_KW, 50), -100, 0, "custom signed 16");
                check(EvDecoder.DEFAULT_TABLE.indexOf("0x21") >= 0, "default table text exposed");
            }
        });

        l.add(new T("VesCore_hscan_path") {
            void run() {
                VesCore c = new VesCore(SR);
                byte[] p = packet(new int[][]{{0x21, 0, 5 << 3, 0, 0, 51, 0x30, 0, 0}, {0x11, 60, 0, 0}});
                c.framer().feed(p, 0, p.length, 100);
                near(c.signals().get(Sig.SPEED_KPH, 100), 30, 1e-9, "speed via framer");
                near(c.signals().get(Sig.PEDAL_PCT, 100), 20, 1e-6, "pedal via framer");
                check(c.recordListener() instanceof EvDecoder, "record listener is the EvDecoder");
                check(c.settings() != null && !c.settings().enabled, "default settings disabled");
            }
        });

        // ---------------- S1a silence gates
        l.add(new T("S1a_silent_gates") {
            void run() {
                String[] cases = {"ready0", "gearP", "gearN", "gearR", "speedStale", "readyStale"};
                Profile[] profs = Profile.values();
                for (int pi = 0; pi < profs.length; pi++) {
                    for (int ci = 0; ci < cases.length; ci++) {
                        String cs = cases[ci] + "/" + profs[pi];
                        Rig r = new Rig(settings(profs[pi], 70, "drive_all"));
                        r.speed = 40;
                        r.pedal = 25;
                        r.run(2000);
                        check(r.opens == 1, "opened once " + cs + " opens=" + r.opens);
                        check(r.rms() > 50, "audible before " + cs + " rms=" + r.rms());
                        long t0 = r.t;
                        r.resetStats();
                        if (cases[ci].equals("ready0")) r.ready = 0;
                        if (cases[ci].equals("gearP")) r.gear = Sig.GEAR_P;
                        if (cases[ci].equals("gearN")) r.gear = Sig.GEAR_N;
                        if (cases[ci].equals("gearR")) r.gear = Sig.GEAR_R;
                        if (cases[ci].equals("speedStale")) r.feedSpeed = false;
                        if (cases[ci].equals("readyStale")) r.ready = Double.NaN;
                        long staleDelay = cases[ci].equals("speedStale") ? 500 : cases[ci].equals("readyStale") ? 2000 : 0;
                        r.run(staleDelay + 3000);
                        check(r.lastNonZeroT < t0 + staleDelay + 500,
                                "samples exactly 0 after release " + cs + " lastNonZero=" + (r.lastNonZeroT - t0));
                        check(r.closes == 1 && r.firstCloseT >= 0 && r.firstCloseT - t0 <= staleDelay + 2200,
                                "policy closed " + cs + " closes=" + r.closes + " at " + (r.firstCloseT - t0));
                        check(r.opens == 0, "no reopen " + cs);
                        check(!r.c.lastParams().reason.equals("drive"), "reason " + r.c.lastParams().reason);
                    }
                }
                // never opens when not ready from the start
                Rig r = new Rig(settings(Profile.ICE_SMOOTH, 70, "idle_regen"));
                r.ready = 0;
                r.run(3000);
                eq(r.opens, 0, "never opened when not ready");
                eq(r.zeros, r.n, "all zero");
                check("not_ready".equals(r.c.lastParams().reason), "reason not_ready: " + r.c.lastParams().reason);
            }
        });

        // ---------------- S1b pitch
        l.add(new T("S1b_pitch_tracks_rpm") {
            void run() {
                Profile[] ice = {Profile.ICE_SMOOTH, Profile.ICE_SHIFT};
                double[] rpms = {900, 1500, 2500, 4000, 6000};
                for (int pi = 0; pi < ice.length; pi++) {
                    for (int ri = 0; ri < rpms.length; ri++) {
                        double rpm = rpms[ri], fire = rpm / 30;
                        float[] x = renderSteady(ice[pi], rpm, 0.4, 0, 0.5, 2.0);
                        int off = SR / 2, len = (int) (1.5 * SR);
                        double est = pitch(x, off, len, fire * 0.75, fire * 1.33, 4, SR);
                        double err = Math.abs(est / fire - 1);
                        check(err <= 0.03, ice[pi] + " rpm=" + rpm + " firing=" + fire + " est=" + est);
                        // firing-order comb dominates the half orders (perceived pitch = firing frequency)
                        double fireE = mag(x, off, len, fire, SR) + mag(x, off, len, 2 * fire, SR) + mag(x, off, len, 3 * fire, SR);
                        double halfE = mag(x, off, len, 0.5 * fire, SR) + mag(x, off, len, 1.5 * fire, SR) + mag(x, off, len, 2.5 * fire, SR);
                        check(fireE > 2.0 * halfE, ice[pi] + " rpm=" + rpm + " firing comb " + fireE + " vs half " + halfE);
                        System.out.println("    " + ice[pi] + " rpm=" + (int) rpm + " firing=" + String.valueOf(Math.round(fire * 10) / 10.0)
                                + "Hz est=" + String.valueOf(Math.round(est * 10) / 10.0) + "Hz");
                    }
                }
                // EV: pitch increases monotonically with speed (through the model)
                double[] speeds = {5, 20, 40, 60, 90, 120, 150};
                double prev = 0;
                StringBuilder sb = new StringBuilder("    EV pitch:");
                for (int i = 0; i < speeds.length; i++) {
                    EngineModel m = new EngineModel(Profile.EV_FUTURE);
                    SignalState s = new SignalState();
                    EngineParams o = new EngineParams();
                    Settings st = settings(Profile.EV_FUTURE, 50, "drive_all");
                    steadyModel(m, s, st, o, 0, 3000, speeds[i], 20, 15, 0, Sig.GEAR_D, 1);
                    float[] x = renderSteady(Profile.EV_FUTURE, o.rpm, o.load, 0, 0.5, 1.5);
                    double est = pitch(x, SR / 2, SR, 90, 1100, 3, SR);
                    sb.append(" ").append((int) speeds[i]).append("kph=").append(Math.round(est)).append("Hz");
                    check(est > prev * 1.01, "EV pitch not increasing at " + speeds[i] + " est=" + est + " prev=" + prev);
                    check(est >= 100 && est <= 950, "EV pitch range " + est);
                    prev = est;
                }
                System.out.println(sb);
            }
        });

        // ---------------- S1g loud enough in the car: the car check (2026-11-05) heard a 9000-peak sine
        // (rms 6364) clearly over music, but 0.1.1's engine sound at volume 100 cruising (35 km/h,
        // pedal 5%, rms ~2000) was "almost inaudible". Volume 100 must come near the test tone.
        l.add(new T("S1g_loud_enough") {
            void run() {
                Profile[] profs = {Profile.ICE_SMOOTH, Profile.ICE_SHIFT, Profile.EV_FUTURE};
                StringBuilder sb = new StringBuilder("    loudness rms (vol/pedal):");
                for (Profile p : profs) {
                    double[][] cases = {{100, 5, 3500}, {100, 30, 4500}, {45, 30, 1800}};
                    for (double[] cs : cases) {
                        Rig r = new Rig(settings(p, (int) cs[0], "drive_all"));
                        r.speed = 35;
                        r.pedal = cs[1];
                        r.run(4000);
                        r.resetStats();
                        r.run(2000);
                        double rms = r.rms();
                        sb.append(' ').append(p).append(' ').append((int) cs[0]).append('/').append((int) cs[1]).append('=').append(Math.round(rms));
                        check(rms >= cs[2], p + " vol " + (int) cs[0] + " pedal " + (int) cs[1] + " rms " + Math.round(rms) + " < " + (int) cs[2]);
                        check(r.peak <= 30000, p + " peak " + r.peak);
                    }
                }
                System.out.println(sb);
            }
        });

        // ---------------- S1f audible on the navigation speaker: in the car check's slow drive (15-20 km/h,
        // 800-2000 rpm) the sound was not heard at all; most of it was the 30-70 Hz firing frequency, which
        // the speaker playing the navigation channel hardly reproduces. The test tone it did hear was
        // 660/880 Hz. Measure what is left above 300 Hz.
        l.add(new T("S1f_presence_above_300hz") {
            void run() {
                Profile[] profs = {Profile.ICE_SMOOTH, Profile.ICE_SHIFT, Profile.EV_FUTURE};
                StringBuilder sb = new StringBuilder("    rms above 300 Hz (vol 100):");
                for (Profile p : profs) {
                    double[][] cases = {{15, 10, 2500}, {35, 5, 2500}, {0, 0, 0}};
                    for (double[] cs : cases) {
                        Settings st = settings(p, 100, "idle_regen");
                        Rig r = new Rig(st);
                        r.speed = cs[0];
                        r.pedal = cs[1];
                        r.run(4000);
                        double sum = 0;
                        int n = 0;
                        HighPass hp = new HighPass(300, SR);
                        for (int b = 0; b < 200; b++) {
                            r.step();
                            for (int i = 0; i < BLOCK; i++) {
                                double y = hp.next(r.buf[i]);
                                if (b >= 20) { sum += y * y; n++; }
                            }
                        }
                        double rms = Math.sqrt(sum / n);
                        sb.append(' ').append(p).append(' ').append((int) cs[0]).append("kph/").append((int) cs[1]).append("%=").append(Math.round(rms));
                        if (cs[2] > 0) check(rms >= cs[2], p + " " + (int) cs[0] + " km/h pedal " + (int) cs[1] + ": rms above 300 Hz " + Math.round(rms) + " < " + (int) cs[2]);
                    }
                }
                System.out.println(sb);
            }
        });

        // ---------------- S1c loudness
        l.add(new T("S1c_loudness_vs_pedal") {
            void run() {
                Profile[] profs = {Profile.ICE_SMOOTH, Profile.EV_FUTURE};
                double[] pedals = {0, 10, 25, 40, 60, 80, 100};
                for (int pi = 0; pi < profs.length; pi++) {
                    Rig r = new Rig(settings(profs[pi], 60, "drive_all"));
                    r.speed = 50;
                    double prev = 0;
                    StringBuilder sb = new StringBuilder("    " + profs[pi] + " rms:");
                    for (int i = 0; i < pedals.length; i++) {
                        r.pedal = pedals[i];
                        r.run(2500);
                        r.resetStats();
                        r.run(1000);
                        double rms = r.rms();
                        sb.append(" ").append((int) pedals[i]).append("%=").append(Math.round(rms));
                        check(rms > 0, "audible");
                        check(rms >= prev * 0.995, profs[pi] + " rms decreased at pedal " + pedals[i] + ": " + rms + " < " + prev);
                        prev = rms;
                    }
                    System.out.println(sb);
                }
            }
        });

        // ---------------- S1d bounds and continuity under abrupt steps
        l.add(new T("S1d_bounds_continuity") {
            void run() {
                Profile[] start = Profile.values();
                for (int pi = 0; pi < start.length; pi++) {
                    Settings st = settings(start[pi], 100, "idle_regen");
                    Rig r = new Rig(st);
                    r.speed = 20;
                    r.run(2000);
                    r.pedal = 100;           // 0 -> 100 in one update
                    r.run(1500);
                    r.speed = 80;            // speed jump
                    r.run(1500);
                    Settings s2 = settings(Profile.EV_FUTURE, 100, "idle_regen");
                    r.c.setSettings(s2);     // profile switch
                    r.run(1000);
                    r.c.setSettings(settings(Profile.ICE_SHIFT, 100, "idle_regen"));
                    r.run(1000);
                    r.pedal = 0;
                    r.kw = -40;              // regen voice
                    r.run(1000);
                    r.speed = 0;
                    r.kw = 0;
                    r.run(1000);             // idle
                    r.pedal = 100;
                    r.speed = 30;
                    r.run(800);
                    r.gear = Sig.GEAR_N;     // gate
                    r.run(800);
                    r.gear = Sig.GEAR_D;
                    r.c.setSettings(settings(Profile.ICE_SMOOTH, 100, "idle_regen"));
                    r.run(1500);
                    r.speed = Double.NaN;    // input garbage
                    r.pedal = Double.NaN;
                    r.run(500);
                    check(r.capViolations == 0, start[pi] + " peak exceeded gain*30000 in " + r.capViolations + " blocks");
                    check(r.peak <= 30000, start[pi] + " peak " + r.peak); // full scale at volume 100 (0.1.2)
                    // a click is a step bigger than the loudest steady sound's own steepest step
                    int steady = 0;
                    for (Profile sp : Profile.values()) {
                        for (double kph : new double[] {30, 80}) {
                            Rig q = new Rig(settings(sp, 100, "idle_regen"));
                            q.speed = kph;
                            q.pedal = 100;
                            q.run(3000);
                            q.resetStats();
                            q.havePrev = false;
                            q.run(2000);
                            steady = Math.max(steady, q.maxDelta);
                        }
                    }
                    check(r.maxDelta <= steady * 1.1 + 300, start[pi] + " max sample delta " + r.maxDelta + " > steady " + steady);
                    System.out.println("    start=" + start[pi] + " peak=" + r.peak + " maxDelta=" + r.maxDelta + " steadyMaxDelta=" + steady);
                }
                // Synth fed NaN/garbage params directly never emits NaN-derived garbage
                Synth s = new Synth(SR, Profile.ICE_SHIFT, 1);
                EngineParams p = new EngineParams();
                p.rpm = Double.NaN;
                p.load = Double.POSITIVE_INFINITY;
                p.regen = -5;
                short[] b = new short[BLOCK];
                for (int i = 0; i < 50; i++) {
                    s.render(b, 0, BLOCK, p, i % 2 == 0 ? Double.NaN : 0.5);
                    for (int k = 0; k < BLOCK; k++) check(Math.abs(b[k]) <= 15001, "garbage params peak");
                }
            }
        });

        // ---------------- S1e behaviour modes
        l.add(new T("S1e_modes") {
            void run() {
                EngineParams o = new EngineParams();
                // idle_regen: idle at idleLevel% in D at stop
                Settings st = settings(Profile.ICE_SMOOTH, 50, "idle_regen");
                st.idleLevel = 30;
                EngineModel m = new EngineModel(Profile.ICE_SMOOTH);
                SignalState s = new SignalState();
                steadyModel(m, s, st, o, 0, 3000, 0, 0, 0, 0, Sig.GEAR_D, 1);
                near(o.targetGain, 0.30, 0.02, "idle gain");
                check("idle".equals(o.reason), "reason idle " + o.reason);
                near(o.rpm, 800, 60, "idle rpm");
                st.idleLevel = 50;
                steadyModel(m, s, st, o, 3000, 3000, 0, 0, 0, 0, Sig.GEAR_D, 1);
                near(o.targetGain, 0.50, 0.02, "idle gain 50");
                // regen voice in idle_regen
                steadyModel(m, s, st, o, 6000, 3000, 50, 0, -30, -0.12, Sig.GEAR_D, 1);
                check("regen".equals(o.reason), "reason regen " + o.reason);
                check(o.regen > 0.2 && o.targetGain > 0, "regen param " + o.regen + " gain " + o.targetGain);
                double regenGain50 = o.targetGain;
                st.regenLevel = 100;
                steadyModel(m, s, st, o, 9000, 2000, 50, 0, -30, -0.12, Sig.GEAR_D, 1);
                check(o.targetGain > regenGain50 * 1.5, "regenLevel scales gain " + o.targetGain + " vs " + regenGain50);
                // regen detected from accel without pedal when kW is unavailable
                EngineModel ma = new EngineModel(Profile.ICE_SMOOTH);
                SignalState sa = new SignalState();
                steadyModel(ma, sa, st, o, 0, 2000, 50, 0, Double.NaN, -0.15, Sig.GEAR_B, 1);
                check("regen".equals(o.reason), "regen from accel " + o.reason);
                // the car check read a constant -4.18 g (YRS12 bytes 0: not sent on this car): ignored
                EngineModel mb = new EngineModel(Profile.ICE_SMOOTH);
                SignalState sb2 = new SignalState();
                steadyModel(mb, sb2, st, o, 0, 2000, 50, 0, Double.NaN, -4.18, Sig.GEAR_D, 1);
                check(!"regen".equals(o.reason), "implausible accel is no regen: " + o.reason);

                // stop_silent_regen: silent at stop, regen while moving
                st = settings(Profile.ICE_SMOOTH, 50, "stop_silent_regen");
                m = new EngineModel(Profile.ICE_SMOOTH);
                s = new SignalState();
                steadyModel(m, s, st, o, 0, 3000, 0, 0, 0, 0, Sig.GEAR_D, 1);
                eq(Math.round(o.targetGain * 1e6), 0, "stop_silent gain");
                check("stopped_silent".equals(o.reason), "reason " + o.reason);
                steadyModel(m, s, st, o, 3000, 3000, 40, 0, -20, -0.1, Sig.GEAR_D, 1);
                check("regen".equals(o.reason) && o.targetGain > 0, "stop_silent regen " + o.reason);

                // accel_only
                st = settings(Profile.ICE_SMOOTH, 50, "accel_only");
                m = new EngineModel(Profile.ICE_SMOOTH);
                s = new SignalState();
                steadyModel(m, s, st, o, 0, 3000, 60, 0, 0, 0, Sig.GEAR_D, 1);
                eq(Math.round(o.targetGain * 1e6), 0, "accel_only coast");
                check("accel_only_wait".equals(o.reason), "reason " + o.reason);
                steadyModel(m, s, st, o, 3000, 2000, 60, 30, 25, 0.05, Sig.GEAR_D, 1);
                check(o.targetGain > 0.3 && "drive".equals(o.reason), "accel_only with pedal " + o.targetGain + " " + o.reason);
                // ~1 s hang after release
                steadyModel(m, s, st, o, 5000, 600, 60, 0, 0, 0, Sig.GEAR_D, 1);
                check(o.targetGain > 0.1, "hang after release " + o.targetGain);
                steadyModel(m, s, st, o, 5600, 1500, 60, 0, 0, 0, Sig.GEAR_D, 1);
                eq(Math.round(o.targetGain * 1e6), 0, "silent after hang");
                steadyModel(m, s, st, o, 7100, 2000, 0, 0, 0, 0, Sig.GEAR_D, 1);
                eq(Math.round(o.targetGain * 1e6), 0, "accel_only no idle");
                steadyModel(m, s, st, o, 9100, 2000, 50, 0, -30, -0.15, Sig.GEAR_D, 1);
                eq(Math.round(o.targetGain * 1e6), 0, "accel_only no regen");
                steadyModel(m, s, st, o, 11100, 1500, 60, Double.NaN, 10, 0, Sig.GEAR_D, 1);
                check(o.targetGain > 0, "accel_only motor kW>2 activates");

                // drive_all: no regen voice, stopped silent
                st = settings(Profile.ICE_SMOOTH, 50, "drive_all");
                m = new EngineModel(Profile.ICE_SMOOTH);
                s = new SignalState();
                steadyModel(m, s, st, o, 0, 3000, 50, 0, -30, -0.15, Sig.GEAR_D, 1);
                check("drive".equals(o.reason), "drive_all reason " + o.reason);
                near(o.regen, 0, 1e-9, "drive_all regen param");
                check(o.targetGain > 0 && o.load < 0.05, "drive_all low load " + o.load);
                steadyModel(m, s, st, o, 3000, 3000, 0, 0, 0, 0, Sig.GEAR_D, 1);
                eq(Math.round(o.targetGain * 1e6), 0, "drive_all stopped");

                // fadeAboveKph
                st = settings(Profile.ICE_SMOOTH, 50, "drive_all");
                m = new EngineModel(Profile.ICE_SMOOTH);
                s = new SignalState();
                steadyModel(m, s, st, o, 0, 3000, 70, 30, 20, 0, Sig.GEAR_D, 1);
                double g70 = o.targetGain;
                st.fadeAboveKph = 80;
                steadyModel(m, s, st, o, 3000, 3000, 70, 30, 20, 0, Sig.GEAR_D, 1);
                near(o.targetGain, g70, 0.02, "below fade threshold unchanged");
                steadyModel(m, s, st, o, 6000, 3000, 90, 30, 20, 0, Sig.GEAR_D, 1);
                check(o.targetGain < g70 * 0.65 && o.targetGain > g70 * 0.3, "half-faded at +10 km/h " + o.targetGain + " vs " + g70);
                steadyModel(m, s, st, o, 9000, 3000, 105, 30, 20, 0, Sig.GEAR_D, 1);
                eq(Math.round(o.targetGain * 1e6), 0, "fully faded");
                check("fade_speed".equals(o.reason), "reason fade_speed " + o.reason);
                // fade applies to idle_regen's regen voice as well
                st = settings(Profile.ICE_SMOOTH, 50, "idle_regen");
                st.fadeAboveKph = 60;
                m = new EngineModel(Profile.ICE_SMOOTH);
                s = new SignalState();
                steadyModel(m, s, st, o, 0, 3000, 100, 0, -30, -0.15, Sig.GEAR_D, 1);
                eq(Math.round(o.targetGain * 1e6), 0, "fade applies to regen");
            }
        });

        // ---------------- S1f virtual gearbox
        l.add(new T("S1f_ice_shift_gears") {
            void run() throws Exception {
                Settings st = settings(Profile.ICE_SHIFT, 60, "idle_regen");
                Replay.Result a = Replay.run(Sweep.generate("accel"), st, SR, null);
                // gear sequence during the full-pedal run: starts at 1, only increases until max
                int maxG = 0, prevG = 0;
                boolean down = false;
                StringBuilder seq = new StringBuilder();
                for (int i = 0; i < a.gearLog.size(); i++) {
                    long[] e = a.gearLog.get(i);
                    if (e[0] > a.markMs[1]) break; // until the pedal lift
                    int g = (int) e[1];
                    seq.append(g).append('@').append(e[0]).append(' ');
                    if (prevG != 0 && g < prevG) down = true;
                    if (g > maxG) maxG = g;
                    prevG = g;
                }
                System.out.println("    accel gears: " + seq);
                check(a.gearLog.size() > 0 && a.gearLog.get(0)[1] == 1, "starts in 1st");
                check(maxG >= 4, "reached >= 4th gear: " + maxG);
                check(!down, "no downshift during full-pedal accel: " + seq);
                check(a.shiftCount >= 3, "upshifts " + a.shiftCount);
                // steady cruise: <= 1 shift in 20 s
                Replay.Result c = Replay.run(Sweep.generate("cruise"), st, SR, null);
                long from = c.markMs[0], to = from + 20000;
                int shifts = 0;
                for (int i = 1; i < c.gearLog.size(); i++) {
                    long t = c.gearLog.get(i)[0];
                    if (t >= from && t < to) shifts++;
                }
                check(to <= c.markMs[1], "cruise window inside steady part");
                check(shifts <= 1, "gear hunting in cruise: " + shifts + " shifts");
                System.out.println("    cruise shifts in 20 s steady: " + shifts);
            }
        });

        // ---------------- S3 audio policy
        l.add(new T("S3_first_tick_cleanup") {
            void run() {
                AudioPolicy p = new AudioPolicy();
                eq(p.tick(0, true, 0), AudioPolicy.ACT_CLOSE, "first tick CLOSE");
                check(!p.isOpen(), "closed");
                eq(p.tick(10, true, 0), AudioPolicy.ACT_OPEN, "then OPEN");
                AudioPolicy q = new AudioPolicy();
                eq(q.tick(5, false, 0), AudioPolicy.ACT_CLOSE, "first tick CLOSE even when silent");
                eq(q.tick(15, false, 0), AudioPolicy.ACT_NONE, "then nothing");
            }
        });

        l.add(new T("S3_no_double_open") {
            void run() {
                AudioPolicy p = new AudioPolicy();
                Random rnd = new Random(9);
                boolean open = false;
                String[] src = {"navi", "tts", "ring", "vr"};
                for (long t = 0; t < 600000; t += 10) {
                    int r = rnd.nextInt(1000);
                    if (r < 2) p.onYieldStart(src[rnd.nextInt(4)], t);
                    else if (r < 5) p.onYieldEnd(src[rnd.nextInt(4)], t);
                    boolean want = (t / 7000) % 3 != 0;
                    double gain = want ? 0.3 : 0;
                    int a = p.tick(t, want, gain);
                    if (a == AudioPolicy.ACT_OPEN) {
                        check(!open, "OPEN twice at " + t);
                        check(!p.yielding(t), "OPEN while yielding at " + t);
                        open = true;
                    } else if (a == AudioPolicy.ACT_CLOSE) {
                        check(open || t == 0, "CLOSE while closed at " + t);
                        open = false;
                    }
                    check(open == p.isOpen(), "isOpen mirrors actions at " + t);
                }
            }
        });

        l.add(new T("S3_yield_fade_close_resume") {
            void run() {
                String[] sources = {"navi", "tts", "ring", "vr", "vui", "btvoice", "dipo", "foreign"};
                for (int si = 0; si < sources.length; si++) {
                    AudioPolicy p = new AudioPolicy();
                    p.tick(0, true, 0);
                    eq(p.tick(10, true, 0.3), AudioPolicy.ACT_OPEN, "open");
                    long t;
                    for (t = 20; t < 1000; t += 10) p.tick(t, true, 0.3);
                    near(p.yieldGain(t), 1, 0, "yield gain 1 before");
                    long ys = t;
                    p.onYieldStart(sources[si], ys);
                    check(p.yielding(ys), "yielding");
                    long zeroAt = -1, closeAt = -1;
                    for (; t < ys + 4000; t += 10) {
                        double yg = p.yieldGain(t);
                        if (zeroAt < 0 && yg == 0) zeroAt = t;
                        int a = p.tick(t, true, 0.3 * yg);
                        if (a == AudioPolicy.ACT_CLOSE) closeAt = t;
                        check(a != AudioPolicy.ACT_OPEN, "no OPEN while yielding " + sources[si]);
                    }
                    check(zeroAt >= 0 && zeroAt - ys <= 150, "gain 0 within 150 ms: " + (zeroAt - ys));
                    check(closeAt >= 0 && closeAt - ys <= 150, "CLOSE within 150 ms: " + (closeAt - ys));
                    long ye = t;
                    p.onYieldEnd(sources[si], ye);
                    long openAt = -1;
                    for (; t < ye + 4000; t += 10) {
                        if (t < ye + 1500) {
                            check(p.yieldGain(t) == 0, "yield gain held 0 until 1500 ms after end");
                            check(p.yielding(t), "still yielding during hold-off");
                        }
                        int a = p.tick(t, true, 0);
                        if (a == AudioPolicy.ACT_OPEN && openAt < 0) openAt = t;
                    }
                    check(openAt >= ye + 1500 && openAt <= ye + 1600, "resume >= 1500 ms after end: " + (openAt - ye));
                    near(p.yieldGain(ye + 3000), 1, 0, "yield gain back to 1");
                }
            }
        });

        l.add(new T("S3_overlapping_yields") {
            void run() {
                AudioPolicy p = new AudioPolicy();
                p.tick(0, true, 0);
                p.tick(10, true, 0.2);
                p.onYieldStart("navi", 1000);
                p.onYieldStart("ring", 1500);
                p.onYieldEnd("navi", 2000);
                check(p.yielding(5000), "ring still active after navi end");
                check(p.yieldGain(5000) == 0, "gain 0 while ring");
                long openAt = -1;
                for (long t = 1000; t < 12000; t += 10) {
                    if (t == 6000) p.onYieldEnd("ring", t);
                    int a = p.tick(t, true, 0);
                    if (a == AudioPolicy.ACT_OPEN && openAt < 0) openAt = t;
                }
                check(openAt >= 7500 && openAt <= 7600, "open only after last end + 1500: " + openAt);
                // same source started twice is one yield; unknown end ignored
                AudioPolicy q = new AudioPolicy();
                q.onYieldStart("tts", 0);
                q.onYieldStart("tts", 100);
                q.onYieldEnd("navi", 200);
                check(q.yielding(300), "unrelated end ignored");
                q.onYieldEnd("tts", 400);
                check(q.yielding(1800) && !q.yielding(1901), "single end clears duplicate start");
                q.onYieldStart(null, 5000);
                check(q.yielding(5001), "null source tracked as foreign");
            }
        });

        l.add(new T("S3_auto_expiry_60s") {
            void run() {
                AudioPolicy p = new AudioPolicy();
                p.tick(0, true, 0);
                p.tick(10, true, 0.2);
                p.onYieldStart("navi", 100);
                long openAt = -1;
                for (long t = 100; t < 70000; t += 10) {
                    int a = p.tick(t, true, 0.2 * p.yieldGain(t));
                    if (t < 60000) check(p.yielding(t), "yielding before expiry at " + t);
                    if (a == AudioPolicy.ACT_OPEN && openAt < 0) openAt = t;
                }
                check(openAt >= 100 + 60000 + 1500 && openAt <= 100 + 60000 + 1600, "auto-expired, reopened at " + openAt);
                p.onYieldEnd("navi", 70000); // late end after expiry: harmless
                check(!p.yielding(70010) || p.yielding(70010), "no throw");
            }
        });

        l.add(new T("S3_silence_close") {
            void run() {
                AudioPolicy p = new AudioPolicy();
                p.tick(0, true, 0);
                eq(p.tick(10, true, 0.2), AudioPolicy.ACT_OPEN, "open");
                long closeAt = -1;
                for (long t = 20; t < 5000; t += 10) {
                    boolean want = t < 1000;
                    int a = p.tick(t, want, want ? 0.2 : 0);
                    if (a == AudioPolicy.ACT_CLOSE) closeAt = t;
                }
                check(closeAt >= 3000 && closeAt <= 3010, "close after 2000 ms silence: " + closeAt);
                // wantSound true but gain ~0 also counts as silence
                AudioPolicy q = new AudioPolicy();
                q.tick(0, true, 0);
                q.tick(10, true, 0.2);
                closeAt = -1;
                for (long t = 20; t < 5000; t += 10) {
                    int a = q.tick(t, true, t < 500 ? 0.2 : 0.0);
                    // wantSound stays true, so the policy may legitimately reopen later: check the first close
                    if (a == AudioPolicy.ACT_CLOSE && closeAt < 0) closeAt = t;
                }
                check(closeAt >= 2500 && closeAt <= 2510, "close after 2 s of zero gain: " + closeAt);
                // short gaps do not close
                AudioPolicy r = new AudioPolicy();
                r.tick(0, true, 0);
                r.tick(10, true, 0.2);
                for (long t = 20; t < 20000; t += 10) {
                    boolean want = (t / 1500) % 2 == 0;
                    check(r.tick(t, want, want ? 0.2 : 0) == AudioPolicy.ACT_NONE, "no action on short gaps at " + t);
                }
            }
        });

        l.add(new T("S3_vescore_yield") {
            void run() {
                Rig r = new Rig(settings(Profile.ICE_SMOOTH, 80, "drive_all"));
                r.speed = 50;
                r.pedal = 30;
                r.run(2000);
                long ys = r.t;
                r.c.onYieldStart("navi", ys);
                r.resetStats();
                r.run(150);
                check(r.c.lastGain() == 0, "core gain 0 within 150 ms of yield");
                check(r.closes == 1, "closed within 150 ms");
                r.run(3000);
                check(r.lastNonZeroT < ys + 150, "silent during yield");
                long ye = r.t;
                r.c.onYieldEnd("navi", ye);
                r.resetStats();
                r.run(1490);
                eq(r.opens, 0, "no open before 1500 ms");
                eq(r.zeros, r.n, "silent until reopen");
                r.run(1500);
                eq(r.opens, 1, "reopened");
                check(r.rms() > 50, "audible again");
                check(r.c.status().indexOf("open=1") >= 0, "status " + r.c.status());
            }
        });

        // ---------------- S4 safe off
        l.add(new T("S4_safe_off") {
            void run() {
                String[] cases = {"disabled", "volume0", "readyOff"};
                for (int ci = 0; ci < cases.length; ci++) {
                    Rig r = new Rig(settings(Profile.ICE_SHIFT, 70, "idle_regen"));
                    r.speed = 30;
                    r.pedal = 20;
                    r.run(2000);
                    check(r.c.status().indexOf("open=1") >= 0, "open before " + r.c.status());
                    long t0 = r.t;
                    r.resetStats();
                    if (ci == 0) {
                        Settings s = settings(Profile.ICE_SHIFT, 70, "idle_regen");
                        s.enabled = false;
                        r.c.setSettings(s);
                    } else if (ci == 1) {
                        r.c.setSettings(settings(Profile.ICE_SHIFT, 0, "idle_regen"));
                    } else {
                        r.ready = 0;
                    }
                    r.run(3000);
                    check(r.firstCloseT >= 0 && r.firstCloseT - t0 <= 2200, cases[ci] + " CLOSE within 2.2 s: " + (r.firstCloseT - t0));
                    check(r.lastNonZeroT < t0 + 400, cases[ci] + " zeros after release " + (r.lastNonZeroT - t0));
                    eq(r.opens, 0, cases[ci] + " no reopen");
                }
            }
        });

        // ---------------- S8 sharing the MICOM channel with the navigation (car check 2026-11-05: the
        // navigation's own effect sounds use type 0x45 too; MICOM reports AudioStart/AudioEnd(0x45) for
        // our calls and for the navigation's, and endAudio(0x45) cuts whichever sound holds the channel)
        l.add(new T("S8_shared_channel") {
            void run() {
                SharedChannel c = new SharedChannel();
                // our own start and end are recognised by timing
                c.ownStartCalled(1000);
                eq(c.onStart(1010), SharedChannel.OURS, "our start");
                check(!c.skipEnd(1010), "we may end our own sound");
                c.ownEndCalled(5000);
                eq(c.onEnd(5005, true), SharedChannel.OURS, "our end");
                // the navigation starts a chime while we are closed: foreign, and we must not end it
                eq(c.onStart(8000), SharedChannel.FOREIGN_START, "navi start");
                check(c.skipEnd(8000), "endAudio would cut the navigation's chime");
                eq(c.onEnd(9000, false), SharedChannel.FOREIGN_END, "navi end");
                check(!c.skipEnd(9000), "channel free again");
                // the navigation ends the channel under our open path: lost, endAudio is pointless
                c.ownStartCalled(12000);
                eq(c.onStart(12003), SharedChannel.OURS, "our start 2");
                eq(c.onEnd(20000, true), SharedChannel.FOREIGN_END, "closed under us");
                check(c.skipEnd(20000), "already closed by someone else");
                c.ownStartCalled(22000); // reopening clears it
                check(!c.skipEnd(22000), "reopened");
                eq(c.onStart(22004), SharedChannel.OURS, "our start 3");
                // a start long after our call is not ours
                c.ownStartCalled(30000);
                eq(c.onStart(30000 + SharedChannel.ACK_MS + 1), SharedChannel.FOREIGN_START, "late start is foreign");
                // each call acknowledges one event only
                c = new SharedChannel();
                c.ownStartCalled(1000);
                eq(c.onStart(1001), SharedChannel.OURS, "first");
                eq(c.onStart(1002), SharedChannel.FOREIGN_START, "second start in the window is someone else's");
                eq(c.onEnd(1500, true), SharedChannel.FOREIGN_END, "their end");
                // a foreign start whose end never arrives doesn't hold the channel forever
                c = new SharedChannel();
                eq(c.onStart(1000), SharedChannel.FOREIGN_START, "navi start, end lost");
                check(c.skipEnd(1000 + SharedChannel.FOREIGN_MAX_MS - 1), "still theirs");
                check(!c.skipEnd(1000 + SharedChannel.FOREIGN_MAX_MS), "given up on its end");
            }
        });

        // ---------------- S8b-d car check 2 (2026-11-05): after a voice-recognition session MICOM re-sent
        // AudioStart(0x45) 14 ms after VuiEnd (it restores our channel); 0.1.2 took it for a navigation
        // chime and stayed silent until the 60 s expiry
        l.add(new T("S8b_restore_after_yield") {
            void run() {
                SharedChannel c = new SharedChannel();
                c.ownStartCalled(1000);
                eq(c.onStart(1003), SharedChannel.OURS, "ours");
                eq(c.onEnd(5000, true), SharedChannel.FOREIGN_END, "preempted by the VUI");
                c.yieldEnded(10000); // VuiEnd
                eq(c.onStart(10014), SharedChannel.RESTORE, "MICOM restores our channel");
                check(!c.skipEnd(10014), "ours again: endAudio allowed");
                // long after any yield end it is a navigation chime again
                eq(c.onStart(20000), SharedChannel.FOREIGN_START, "a lone start is someone else's");
            }
        });
        l.add(new T("S8c_restore_after_foreign_end") {
            void run() {
                SharedChannel c = new SharedChannel();
                c.ownStartCalled(1000);
                eq(c.onStart(1001), SharedChannel.OURS, "ours");
                eq(c.onEnd(3000, true), SharedChannel.FOREIGN_END, "closed under us");
                eq(c.onStart(3000 + SharedChannel.RESTORE_MS), SharedChannel.RESTORE, "restored within the window");
                check(!c.skipEnd(3000 + SharedChannel.RESTORE_MS), "not lost any more");
            }
        });
        l.add(new T("S8d_shared_yield_is_short") {
            void run() {
                AudioPolicy p = new AudioPolicy();
                p.onYieldStart("shared", 1000);
                check(p.yielding(1000 + 4999), "still yielding at 4.999 s");
                check(!p.yielding(1000 + AudioPolicy.SHARED_EXPIRY_MS + AudioPolicy.HOLDOFF_MS + 1), "a lost end expires in 5 s (+ the usual pause)");
                AudioPolicy q = new AudioPolicy();
                q.onYieldStart("vui", 1000);
                check(q.yielding(1000 + 59000), "vui still yields at 59 s");
                check(!q.yielding(1000 + AudioPolicy.EXPIRY_MS + 1 + AudioPolicy.HOLDOFF_MS), "vui expires at 60 s");
            }
        });
        l.add(new T("S9_vui_restore_end_to_end") {
            void run() {
                Rig r = new Rig(settings(Profile.ICE_SMOOTH, 50, "drive_all"));
                r.speed = 30;
                r.pedal = 30;
                r.run(3000);
                check(r.c.isOpen(), "playing before the VUI");
                long t = r.t;
                r.c.onYieldStart("vui", t);
                eq(r.c.ownTypeEvent(false, t + 2), SharedChannel.FOREIGN_END, "VUI preempts our channel");
                r.run(4000);
                r.resetStats();
                long end = r.t;
                r.c.onYieldEnd("vui", end);
                eq(r.c.ownTypeEvent(true, end + 14), SharedChannel.RESTORE, "MICOM restore");
                r.run(2000);
                r.resetStats();
                r.run(500);
                check(r.rms() > 200, "sound back within 2.5 s of the VUI end, rms " + Math.round(r.rms()));
                r.resetStats();
                r.run(5000);
                check(r.zeros < r.n / 10, "and it stays on");
            }
        });

        // ---------------- V1-V3 HMG-API vehicle readings (car check 2: hmgapid answered the values with exit
        // code 1, its read timing out after 3 s because the vehicle service keeps the socket open)
        l.add(new T("V1_vehicle_text_any_code") {
            void run() {
                java.util.Map<String, String> m = VehicleText.parse(1, "speed 42.5\npedal.accel 7\nhv.ready 1\ngear.park -\n");
                check("42.5".equals(m.get("speed")), "speed " + m);
                check("7".equals(m.get("pedal.accel")) && "1".equals(m.get("hv.ready")) && "-".equals(m.get("gear.park")), "all " + m);
                check(VehicleText.parse(-2, "speed 3\n").containsKey("speed"), "text read before a timeout counts");
            }
        });
        l.add(new T("V2_vehicle_text_refused") {
            void run() {
                check(VehicleText.parse(13, "").isEmpty(), "empty");
                check(VehicleText.parse(13, "not declared in mod.json (vehicle read): speed\n").isEmpty(), "a refusal is no reading");
                check(VehicleText.parse(0, null).isEmpty(), "null");
            }
        });
        l.add(new T("V3_fallback_stale_window") {
            void run() {
                SignalState st = new SignalState();
                VehicleText.setFallbackStale(st, true, 3200);
                st.set(Sig.SPEED_KPH, 30, 1000);
                st.set(Sig.PEDAL_PCT, 10, 1000);
                check(!Double.isNaN(st.get(Sig.SPEED_KPH, 1000 + 3200)), "a 3.2 s poll keeps speed fresh");
                check(!Double.isNaN(st.get(Sig.PEDAL_PCT, 1000 + 6000)), "within 2 x call time");
                VehicleText.setFallbackStale(st, false, 0);
                check(Double.isNaN(st.get(Sig.SPEED_KPH, 1000 + 600)), "back to 500 ms when the stream returns");
            }
        });

        // ---------------- PR1-3 car check resume (car check 2: the VUI screen covered the app during the
        // 60 s hold; the result went to the old screen and the step never advanced)
        l.add(new T("PR_probe_resume") {
            void run() {
                eq(ProbeResume.decide("hold", "ok\nprobe open /x running hold\n", null), ProbeResume.REATTACH, "PR1 hold still running");
                eq(ProbeResume.decide("hold", "ok\nprobe open /x\n", "ok\n(끝)"), ProbeResume.ADVANCE, "PR2 finished while away");
                eq(ProbeResume.decide("hold", "ok\nprobe open /x\n", null), ProbeResume.SHOW, "finished, no result: show the step");
                eq(ProbeResume.decide("chime", "probe open /x running hold", null), ProbeResume.REATTACH, "chime is a hold too");
                eq(ProbeResume.decide("drive_stop", "probe open /x running drive", null), ProbeResume.SHOW, "drive keeps its own screen");
                eq(ProbeResume.decide("tone_fm_r1", "probe open /x", "ok"), ProbeResume.SHOW, "PR3 short steps just show");
                eq(ProbeResume.decide(null, null, null), ProbeResume.SHOW, "nothing saved");
                eq(ProbeResume.decide("hold", "error vesd not reachable", null), ProbeResume.SHOW, "no status");
                eq(ProbeResume.decide("hold", "probe open /x", "error busy"), ProbeResume.SHOW, "a failed run is shown, not skipped");
            }
        });

        // ---------------- O1 characterization: the output of a fixed drive, before and after moving the
        // speaker stage (high-pass, presence band, soft clip) out of Synth into OutputStage
        l.add(new T("O1_output_unchanged") {
            void run() throws Exception {
                Profile[] profs = {Profile.ICE_SMOOTH, Profile.ICE_SHIFT, Profile.EV_FUTURE};
                StringBuilder got = new StringBuilder();
                for (Profile p : profs) {
                    Rig r = new Rig(settings(p, 70, "idle_regen"));
                    java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                    for (int i = 0; i < 1000; i++) {
                        long t = r.t;
                        r.speed = Math.max(0, 60 * Math.sin(t / 4000.0));
                        r.pedal = 50 + 50 * Math.sin(t / 900.0);
                        r.kw = 40 * Math.sin(t / 1300.0);
                        r.step();
                        for (short v : r.buf) { md.update((byte) v); md.update((byte) (v >> 8)); }
                    }
                    byte[] d = md.digest();
                    StringBuilder hx = new StringBuilder();
                    for (int i = 0; i < 8; i++) hx.append(String.format("%02x", d[i] & 0xff));
                    got.append(p).append('=').append(hx).append(' ');
                }
                String want = O1_WANT;
                System.out.println("    O1 " + got);
                check(got.toString().trim().equals(want), "output changed: " + got + " vs " + want);
            }
        });

        // ---------------- S5 volume and envelope
        l.add(new T("S5_volume_envelope") {
            void run() {
                near(GainEnvelope.volumeToLinear(100), 1.0, 0, "100 => full scale");
                near(GainEnvelope.volumeToLinear(0), 0, 0, "0 => 0");
                near(GainEnvelope.volumeToLinear(50), Math.pow(0.5, 1.5), 1e-12, "1.5 power law");
                double prev = -1;
                for (int v = 0; v <= 100; v++) {
                    double g = GainEnvelope.volumeToLinear(v);
                    check(g > prev, "monotonic at " + v);
                    prev = g;
                }
                near(GainEnvelope.volumeToLinear(150), 1.0, 0, "clamp high");
                near(GainEnvelope.volumeToLinear(-3), 0, 0, "clamp low");
                GainEnvelope e = new GainEnvelope(SR);
                int blocks = 0;
                double g = 0;
                while (g < 0.999 && blocks < 1000) {
                    g = e.next(1.0, BLOCK);
                    blocks++;
                }
                double attackMs = blocks * BLOCK * 1000.0 / SR;
                check(attackMs >= 200, "attack >= 200 ms: " + attackMs);
                check(attackMs <= 400, "attack not absurdly slow: " + attackMs);
                blocks = 0;
                while (g > 0 && blocks < 1000) {
                    g = e.next(0.0, BLOCK);
                    blocks++;
                }
                double relMs = blocks * BLOCK * 1000.0 / SR;
                check(relMs >= 100 && relMs <= 200, "release ~150 ms: " + relMs);
                eq(Math.round(g * 1e9), 0, "release reaches exactly 0");
                e.next(0.5, BLOCK);
                e.reset();
                near(e.next(0, 1), 0, 0, "reset");
                System.out.println("    attack=" + Math.round(attackMs) + "ms release=" + Math.round(relMs) + "ms");
            }
        });

        // ---------------- performance
        l.add(new T("Perf_60s_per_profile") {
            void run() {
                Profile[] profs = Profile.values();
                for (int pi = 0; pi < profs.length; pi++) {
                    Synth s = new Synth(SR, profs[pi], 11);
                    EngineParams p = new EngineParams();
                    short[] b = new short[BLOCK];
                    int blocks = 60 * SR / BLOCK;
                    long t0 = System.nanoTime();
                    for (int i = 0; i < blocks; i++) {
                        double t = i * BLOCK / (double) SR;
                        p.rpm = 800 + 2600 * (1 + Math.sin(2 * Math.PI * t / 7));
                        p.load = 0.5 + 0.5 * Math.sin(2 * Math.PI * t / 3);
                        p.regen = Math.max(0, Math.sin(2 * Math.PI * t / 11));
                        p.speedKph = 60 + 50 * Math.sin(2 * Math.PI * t / 13);
                        s.render(b, 0, BLOCK, p, 0.4);
                    }
                    double sec = (System.nanoTime() - t0) / 1e9;
                    System.out.println("    PERF " + profs[pi] + " 60s render=" + Math.round(sec * 1000) + "ms rtf="
                            + Math.round(60 / sec) + "x");
                    check(sec <= 0.6, profs[pi] + " too slow: " + sec + " s");
                }
                // full core path (model + policy + envelope + synth)
                Rig r = new Rig(settings(Profile.ICE_SHIFT, 60, "idle_regen"));
                long t0 = System.nanoTime();
                for (int i = 0; i < 6000; i++) {
                    r.speed = 60 + 40 * Math.sin(i / 500.0);
                    r.pedal = 50 + 50 * Math.sin(i / 170.0);
                    r.step();
                }
                double sec = (System.nanoTime() - t0) / 1e9;
                System.out.println("    PERF VesCore 60s=" + Math.round(sec * 1000) + "ms rtf=" + Math.round(60 / sec) + "x");
                check(sec <= 0.8, "core too slow " + sec);
            }
        });

        // ---------------- tools smoke
        l.add(new T("Tools_sweep_replay") {
            void run() throws Exception {
                String[] sc = Sweep.SCENARIOS;
                for (int i = 0; i < sc.length; i++) {
                    String csv = Sweep.generate(sc[i]);
                    check(csv.startsWith("t_ms,speed,pedal,motor_kw,accel_g,gear,ready,brake,event"), "header " + sc[i]);
                    Replay.Result r = Replay.run(csv, settings(Profile.EV_FUTURE, 60, "idle_regen"), SR, null);
                    check(r.peak <= r.peakCap + 1, sc[i] + " peak " + r.peak + " cap " + r.peakCap);
                    if (sc[i].equals("ready_off")) check(r.silentFraction > 0.999 && r.opens == 0, "ready_off silent");
                    else check(r.rms > 0, sc[i] + " audible");
                }
                // .hscan raw capture path
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                for (int k = 0; k < 300; k++) {
                    byte[] p = packet(new int[][]{{0x21, 0, 5 << 3, 0, 0, 60, 0x30, 0, 0}, {0x11, 80, 0, 0}});
                    long t = k * 20L;
                    for (int q = 0; q < 8; q++) bo.write((int) (t >>> (8 * q)) & 0xFF);
                    int len = 264;
                    for (int q = 0; q < 4; q++) bo.write((len >>> (8 * q)) & 0xFF);
                    bo.write(p, 0, 264);
                }
                Replay.Result h = Replay.runHscan(bo.toByteArray(), settings(Profile.ICE_SMOOTH, 60, "idle_regen"), SR, null);
                check(h.packets == 300, "hscan packets " + h.packets);
                check(h.rms > 0, "hscan replay audible");
            }
        });

        return l;
    }
}
