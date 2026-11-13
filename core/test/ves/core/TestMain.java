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
    // 0.1.2 output was ICE_SMOOTH=4bdf3f9889a03759 ...; 0.2.0 faster normal response (L1) changes it on purpose
    // 0.2.7 launch slip held in 1st (M2) changed ICE_SHIFT only (0.2.6: b73cf032a167a19f -> e262d54caaa95598); the
    // +6 dB volume curve (S5, VC1) changes all three (0.2.6: ICE_SMOOTH=4afaa3acacd639b1 EV_FUTURE=54180ef1274a9222),
    // and again the makeup dropping at once on an onset (OS2; before: ICE_SMOOTH=41530d7a3f993c94
    // ICE_SHIFT=0d9a6b3857c4099c EV_FUTURE=4f69dc23df282e10)
    // 0.2.10 launch slip climbing with speed (M3) changes ICE_SHIFT only (before: 615b8d00311dc3b0)
    static final String O1_WANT = "ICE_SMOOTH=5017ab23cb7b714b ICE_SHIFT=119dcc9b0bf40b07 EV_FUTURE=91ac0fb068a924ea";
    static final int BLOCK = 220; // 10 ms

    /** the classic modes (0.1.x behaviour: P/N/R silent, mode decides stops and regen) */
    static Settings settings(Profile p, int volume, String mode) {
        Settings s = new Settings();
        s.enabled = true;
        s.volume = volume;
        s.profile = p;
        s.mode = mode;
        s.behavior = "classic";
        s.pops = "off"; // 0.1.x had none; P_pops tests them
        s.prompts = "pause"; // 0.1.x stepped aside for prompts; Y_ tests the ducking
        s.speedBoost = "off"; // 0.1.x level did not rise with speed; SB_ tests it
        return s;
    }

    /** the ICE-like default (0.2.0): sound whenever Ready */
    static Settings iceSettings(Profile p, int volume) {
        Settings s = new Settings();
        s.enabled = true;
        s.volume = volume;
        s.profile = p;
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
        int opens, closes, lastAction, regrabs;
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
            if (lastAction == AudioPolicy.ACT_OPEN || lastAction == AudioPolicy.ACT_REGRAB) c.ownStartCalled(t);
            if (lastAction == AudioPolicy.ACT_REGRAB) regrabs++;
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
            opens = closes = regrabs = 0;
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

    /** ms from a 0 -> 60% pedal step (30 km/h, ICE_SMOOTH) until rpm covers 63% of its rise */
    static long rpmRise(String response) {
        Settings st = settings(Profile.ICE_SMOOTH, 60, "drive_all");
        st.response = response;
        Rig r = new Rig(st);
        r.speed = 30;
        r.pedal = 0;
        r.run(4000);
        double rpm0 = r.c.lastParams().rpm;
        Rig q = new Rig(st);
        q.speed = 30;
        q.pedal = 0;
        q.run(4000);
        q.pedal = 60;
        q.run(4000);
        double rpm1 = q.c.lastParams().rpm;
        r.pedal = 60;
        long t0 = r.t;
        double goal = rpm0 + 0.63 * (rpm1 - rpm0);
        while (r.t - t0 < 4000) {
            r.step();
            if (r.c.lastParams().rpm >= goal) return r.t - t0;
        }
        return 4000;
    }

    /** at t0 + 1 s: full load at rpm/70 km/h, then the foot comes off; 2 s of samples pulled through */
    static void liftOff(Pops p, double rpm, long t0) {
        long t = t0;
        for (int b = 0; b < 300; b++) { // 3 s in 10 ms blocks
            double load = b < 100 ? 0.9 : 0.0;
            p.update(rpm, load, 70, t);
            for (int i = 0; i < BLOCK; i++) p.next();
            t += 10;
        }
    }

    // ------------------------------------------------------------------ pack fixtures

    static final class MapSource implements PackLoader.Source {
        final java.util.Map<String, byte[]> files;

        MapSource(java.util.Map<String, byte[]> f) {
            files = f;
        }

        public byte[] read(String name, int maxBytes) throws java.io.IOException {
            byte[] b = files.get(name);
            if (b == null) throw new java.io.FileNotFoundException(name);
            if (b.length > maxBytes) throw new java.io.IOException(name + " too big");
            return b;
        }
    }

    static byte[] wav(int rate, int ch, int bits, int samples, double hz) {
        int bps = bits / 8, data = samples * ch * bps;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + data).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) ch).putInt(rate)
                .putInt(rate * ch * bps).putShort((short) (ch * bps)).putShort((short) bits);
        b.put("data".getBytes()).putInt(data);
        for (int i = 0; i < samples; i++) {
            double v = Math.sin(2 * Math.PI * hz * i / rate) * 12000;
            for (int c = 0; c < ch; c++) {
                if (bits == 16) b.putShort((short) v);
                else b.put((byte) (128 + v / 256));
            }
        }
        return b.array();
    }

    /** a valid 5 x 2 sine pack, or one broken in the named way */
    static MapSource testPack(String broken) {
        java.util.Map<String, byte[]> m = new java.util.HashMap<String, byte[]>();
        StringBuilder t = new StringBuilder("# test pack\nname test\ncylinders 4\nidle 800\nredline 6500\n");
        int[] rpms = {"narrow".equals(broken) ? 2000 : 800, 1500, 2500, 4000, "narrow".equals(broken) ? 3000 : 6500};
        int n = "fewloops".equals(broken) ? 2 : rpms.length;
        for (int i = 0; i < n; i++) {
            for (String load : new String[] {"on", "off"}) {
                if (load.equals("off") && "noff".equals(broken)) continue;
                String f = "l" + rpms[i] + "_" + load + ".wav";
                int rate = "rate44100".equals(broken) && i == 1 ? 44100 : 22050;
                int ch = "stereo".equals(broken) && i == 2 ? 2 : 1;
                int bits = "bits8".equals(broken) && i == 3 ? 8 : 16;
                int samples = "toolong".equals(broken) && i == 0 ? 22050 * 3 : "tooshort".equals(broken) && i == 0 ? 1000 : 11025;
                double hz = rpms[i] / 30.0;
                m.put(f, wav(rate, ch, bits, samples, hz));
                String name = "traversal".equals(broken) && i == 0 ? "../" + f : "missing".equals(broken) && i == 0 ? "nope.wav" : f;
                t.append("loop ").append(name).append(" rpm=").append(rpms[i]).append(" load=").append(load);
                if ("endpast".equals(broken) && i == 0) t.append(" start=0 end=999999");
                t.append('\n');
            }
        }
        if ("badline".equals(broken)) t.append("loop x.wav rpm=abc load=on\n");
        if ("oversize".equals(broken)) for (int i = 0; i < 60; i++) {
            String f = "big" + i + ".wav";
            m.put(f, wav(22050, 1, 16, 44100, 100));
            t.append("loop ").append(f).append(" rpm=").append(900 + i * 10).append(" load=on\n");
        }
        m.put("pack.txt", t.toString().getBytes());
        return new MapSource(m);
    }

    /** sine loops at rpm / 10 Hz (whole cycles in 0.5 s), on and off layers; either layer can be silent */
    static MapSource sinePack(boolean onSilent, boolean offSilent) {
        java.util.Map<String, byte[]> m = new java.util.HashMap<String, byte[]>();
        StringBuilder t = new StringBuilder("name sine\ncylinders 4\nidle 800\nredline 6500\naligned yes\n");
        int[] rpms = {840, 1500, 2520, 3960, 6480};
        for (int r : rpms) {
            for (String load : new String[] {"on", "off"}) {
                boolean silent = load.equals("on") ? onSilent : offSilent;
                String f = "s" + r + "_" + load + ".wav";
                byte[] w = wav(22050, 1, 16, 11025, r / 10.0);
                if (silent) java.util.Arrays.fill(w, 44, w.length, (byte) 0);
                m.put(f, w);
                t.append("loop ").append(f).append(" rpm=").append(r).append(" load=").append(load).append('\n');
            }
        }
        m.put("pack.txt", t.toString().getBytes());
        return new MapSource(m);
    }

    /** mono 16-bit 22050 Hz WAV of the given samples */
    static byte[] wavPcm(short[] x) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44 + 2 * x.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + 2 * x.length).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1).putInt(22050).putInt(44100)
                .putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes()).putInt(2 * x.length);
        for (short v : x) b.putShort(v);
        return b.array();
    }

    /**
     * an aligned loop of k whole cycles at rpm, its length rounded down to a sample (as tools/render's
     * Grid.loopSamples can): a quiet order-4 tone, the last cycle loud
     */
    static short[] markedLoop(int rpm, int k) {
        double spc = 2646000.0 / rpm; // 120 * 22050 / rpm
        int len = (int) Math.floor(k * spc);
        short[] x = new short[len];
        for (int i = 0; i < len; i++) {
            double cyc = i / spc;
            double amp = cyc >= k - 1 ? 12000 : 1000;
            x[i] = (short) (amp * Math.sin(2 * Math.PI * 4 * cyc));
        }
        return x;
    }

    static double rmsOf(PackVoice v, double rpm, double load, double regen) {
        float[] x = new float[SR / 2];
        v.renderRaw(x, x.length, rpm, load, regen);
        v.renderRaw(x, x.length, rpm, load, regen);
        double ss = 0;
        for (float f : x) ss += f * f;
        return Math.sqrt(ss / x.length);
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

    /**
     * One MODE_STREAM track on Android 4.4's AudioFlinger, in 5 ms mixer steps. A new or restarted track plays only
     * once framesReady() >= mFrameCount, the whole buffer (Tracks.cpp Track::isReady; addTrack_l sets FS_FILLING),
     * and an underrun puts it back to filling. AudioTrack.write blocks while the buffer is full. frames: the track's
     * real frame count; dead: a track that never plays.
     */
    static final class Flinger {
        final int frames;
        final boolean dead;
        long written, played;
        boolean running, blocked;
        int starts;

        int discard; // the first frames written vanish (4.4's lazy flush racing the first mix)

        Flinger(int frames, boolean dead) {
            this.frames = frames;
            this.dead = dead;
        }

        int write(int n) {
            if (discard > 0) {
                int d = Math.min(discard, n);
                discard -= d;
                return d + (n > d ? write(n - d) : 0); // accepted, but never reaches the server
            }
            long space = frames - (written - played);
            if (n <= space) {
                written += n;
                start();
                return n;
            }
            written += Math.max(0, space);
            start();
            if (!running) { // full and still not playing: a blocking write waits forever (vesd's audio thread hangs)
                blocked = true;
                return (int) Math.max(0, space);
            }
            written += n - Math.max(0, space); // room comes as it plays: a wait of a few ms
            return n;
        }

        void start() {
            if (!running && !dead && written - played >= frames) {
                running = true;
                starts++;
            }
        }

        void step() {
            if (!running) return;
            played += 110; // 5 ms at 22050 Hz
            if (played >= written) {
                played = written;
                running = false;
            }
        }

        int fill() {
            return (int) (written - played);
        }
    }

    /**
     * vesd's audio loop (Vesd.audioLoop) driven by the production TrackFeeder against Flinger tracks, in 5 ms
     * steps for ms. make: a new track (OPEN, RESET). reopenEvery: CLOSE and OPEN again every so many ms (0: never).
     * Returns {frames played, resets, rendered blocks, hung writes, worst ms from an OPEN to playing, dropped}.
     */
    interface Tracks {
        Flinger make(int n);
    }

    static long[] loop(TrackFeeder fd, Tracks make, int ms, long reopenEvery) {
        int made = 0;
        Flinger f = make.make(made++);
        fd.newTrack(f.frames);
        long played = 0, rendered = 0, resets = 0, worstStart = 0, openedAt = 0;
        boolean waitingStart = true;
        long sleepUntil = 0;
        for (long t = 0; t < ms; t += 5) {
            if (reopenEvery > 0 && t > 0 && t % reopenEvery == 0) { // CLOSE (release) then OPEN (a fresh track)
                played += f.played;
                f = make.make(made++);
                fd.newTrack(f.frames);
                openedAt = t;
                waitingStart = true;
            }
            for (int k = 0; k < 64 && t >= sleepUntil; k++) {
                int d = fd.next(f.played, t);
                if (d > 0) {
                    fd.wrote(f.write(d));
                    if (f.blocked) return new long[] { played + f.played, resets, rendered, 1, worstStart, fd.dropped() };
                    continue;
                }
                if (d == TrackFeeder.WAIT) break;
                if (d == TrackFeeder.RESET) {
                    played += f.played;
                    f = make.make(made++);
                    fd.newTrack(f.frames);
                    fd.resetDone(t);
                    resets++;
                    continue;
                }
                rendered++; // RENDER or DROP: a block is rendered (the engine model, prompts and status go on)
                if (d == TrackFeeder.DROP) {
                    fd.droppedBlock();
                    sleepUntil = t + 10;
                    break;
                }
                fd.rendered();
            }
            f.step();
            if (waitingStart && f.running) {
                worstStart = Math.max(worstStart, t - openedAt);
                waitingStart = false;
            }
        }
        if (waitingStart) worstStart = Math.max(worstStart, ms - openedAt);
        return new long[] { played + f.played, resets, rendered, 0, worstStart, fd.dropped() };
    }

    /**
     * vesd's audio loop (Vesd.audioLoop) against a Flinger for ms milliseconds: render a block, write as much of it
     * as the pacer allows, wait 5 ms when it allows nothing. Returns {frames played, max queue after the first
     * second, starts, 1 if a write hung}.
     */
    static long[] feed(WritePacer w, Flinger f, int block, int ms) {
        return feed(w, f, block, ms, -1, 0);
    }

    /** with a gap: the writer stalls for gapMs from gapAt (a slow block, a busy CPU) */
    static long[] feed(WritePacer w, Flinger f, int block, int ms, long gapAt, long gapMs) {
        int pending = 0;
        long lateFill = 0;
        for (long t = 0; t < ms; t += 5) {
            boolean gap = t >= gapAt && t < gapAt + gapMs;
            for (int k = 0; k < 64 && !f.blocked && !gap; k++) {
                int n = w.framesToWrite(f.written, f.played, t);
                if (n <= 0) break;
                if (pending == 0) pending = block; // the next block is rendered
                pending -= f.write(Math.min(n, pending));
            }
            if (f.blocked) break;
            f.step();
            if (t >= 1000) lateFill = Math.max(lateFill, f.fill());
        }
        return new long[] { f.played, lateFill, f.starts, f.blocked ? 1 : 0 };
    }

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
                // the ICE-like default keeps sounding in P, N and R (only Ready off / stale data silence it)
                for (double g : new double[] {Sig.GEAR_P, Sig.GEAR_N, Sig.GEAR_R}) {
                    Rig ri = new Rig(iceSettings(Profile.ICE_SMOOTH, 70));
                    ri.speed = 0;
                    ri.gear = g;
                    ri.run(3000);
                    ri.resetStats();
                    ri.run(1000);
                    check(ri.rms() > 50, "ice: gear " + g + " not silent, rms " + Math.round(ri.rms()));
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
                        if (t < ye + 500) { // 0.2.2: back 0.5 s after a prompt (owner's choice; was 1.5 s)
                            check(p.yieldGain(t) == 0, "yield gain held 0 until 500 ms after end");
                            check(p.yielding(t), "still yielding during hold-off");
                        }
                        int a = p.tick(t, true, 0);
                        if (a == AudioPolicy.ACT_OPEN && openAt < 0) openAt = t;
                    }
                    check(openAt >= ye + 500 && openAt <= ye + 600, "resume 500-600 ms after end: " + (openAt - ye));
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
                check(openAt >= 6500 && openAt <= 6600, "open only after last end + 500: " + openAt);
                // same source started twice is one yield; unknown end ignored
                AudioPolicy q = new AudioPolicy();
                q.onYieldStart("tts", 0);
                q.onYieldStart("tts", 100);
                q.onYieldEnd("navi", 200);
                check(q.yielding(300), "unrelated end ignored");
                q.onYieldEnd("tts", 400);
                check(q.yielding(800) && !q.yielding(901), "single end clears duplicate start");
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
                check(openAt >= 100 + 60000 + 500 && openAt <= 100 + 60000 + 600, "auto-expired, reopened at " + openAt);
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
                r.run(490);
                eq(r.opens, 0, "no open before 500 ms");
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
        ChannelTests.add(l);

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
                eq(ProbeResume.decide("pack_v8", "probe open /x running hold", null), ProbeResume.REATTACH, "a pack demo is a hold too");
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

        PackFormatTests.add(l); // 0.2.8: pack limits, load layers, events, shift sounds
        // ---------------- K1-K4 sound packs: pack.txt + PCM16 mono 22050 Hz WAV loops (built-in packs and
        // the owner's packs from USB, so every input is untrusted)
        l.add(new T("K1_pack_parses") {
            void run() throws Exception {
                Pack p = PackLoader.load(testPack(null));
                check("test".equals(p.name) && p.cylinders == 4 && p.idleRpm == 800 && p.redlineRpm == 6500, "header " + p.name);
                eq(p.on.length, 5, "on loops");
                eq(p.off.length, 5, "off loops");
                check(p.on[0].rpm < p.on[1].rpm && p.on[4].rpm == 6500, "sorted by rpm");
                eq(p.on[0].pcm.length, 11025, "loop samples");
                Pack q = PackLoader.load(testPack("noff"));
                check(q.off == q.on, "no off-load loops: the on-load ones stand in");
            }
        });
        l.add(new T("K2_pack_rejects_bad_files") {
            void run() throws Exception {
                String[] bad = {"rate44100", "stereo", "bits8", "traversal", "missing", "toolong", "tooshort", "oversize", "badline", "endpast"};
                for (String b : bad) {
                    try {
                        PackLoader.load(testPack(b));
                        check(false, b + " accepted");
                    } catch (PackError e) {
                        check(e.getMessage() != null && e.getMessage().length() > 0, b + " has a reason");
                    }
                }
            }
        });
        l.add(new T("K3_pack_rpm_coverage") {
            void run() throws Exception {
                for (String b : new String[] {"narrow", "fewloops"}) {
                    try {
                        PackLoader.load(testPack(b));
                        check(false, b + " accepted");
                    } catch (PackError expected) {
                    }
                }
            }
        });
        l.add(new T("K4_pack_fuzz") {
            void run() throws Exception {
                java.util.Random rnd = new java.util.Random(7);
                final java.util.Map<String, byte[]> good = testPack(null).files;
                int ok = 0;
                for (int i = 0; i < 3000; i++) {
                    final java.util.Map<String, byte[]> m = new java.util.HashMap<String, byte[]>();
                    String victim = new java.util.ArrayList<String>(new java.util.TreeSet<String>(good.keySet())).get(rnd.nextInt(good.size()));
                    for (java.util.Map.Entry<String, byte[]> e : good.entrySet()) {
                        byte[] b = e.getValue().clone();
                        if (!e.getKey().equals(victim)) {
                            m.put(e.getKey(), b);
                            continue;
                        }
                        int flips = rnd.nextInt(6);
                        for (int k = 0; k < flips && b.length > 0; k++) b[rnd.nextInt(Math.min(b.length, 80))] = (byte) rnd.nextInt(256);
                        if (rnd.nextInt(10) == 0) b = java.util.Arrays.copyOf(b, rnd.nextInt(b.length + 1));
                        m.put(e.getKey(), b);
                    }
                    try {
                        PackLoader.load(new MapSource(m));
                        ok++;
                    } catch (PackError expected) {
                    } catch (RuntimeException e) {
                        check(false, "fuzz " + i + " threw " + e);
                    }
                }
                System.out.println("    fuzz: " + ok + " of 3000 mutated packs still valid");
            }
        });

        // ---------------- L1-L3 response (car check 2: pedal response "조금 늦음"; AudioTrack's minimum buffer on
        // the car is 8192 bytes = 186 ms, all of it queued by a blocking write)
        l.add(new T("L1_pedal_response_normal") {
            void run() {
                long ms = rpmRise("normal");
                System.out.println("    rpm 63% rise after a 0->60% pedal step: normal " + ms + " ms, soft " + rpmRise("soft") + " ms, sport " + rpmRise("sport") + " ms");
                check(ms <= 220, "normal response " + ms + " ms > 220");
            }
        });
        l.add(new T("L2_pedal_response_soft_unchanged") {
            void run() {
                long ms = rpmRise("soft");
                check(ms >= 600 && ms <= 720, "soft unchanged (0.1.2: 660 ms): " + ms + " ms");
            }
        });
        l.add(new T("L3_write_pacer") {
            void run() {
                WritePacer w = new WritePacer(1764, 220, 4096);
                long written = 0, head = 0;
                int minFill = Integer.MAX_VALUE, maxFill = 0;
                java.util.Random rnd = new java.util.Random(3);
                for (int i = 0; i < 20000; i++) {
                    int n = w.framesToWrite(written, head);
                    written += n;
                    head += rnd.nextInt(3) == 0 ? 0 : 110; // playback runs ~10 ms per 2 polls, unevenly
                    if (head > written) head = written;
                    int fill = (int) (written - head);
                    if (i > 100) {
                        minFill = Math.min(minFill, fill);
                        maxFill = Math.max(maxFill, fill);
                    }
                }
                check(maxFill <= 1764 + 220, "never more than target + a block queued: " + maxFill);
                check(minFill > 0, "never runs dry: " + minFill);
                eq(w.framesToWrite(5000, 0), 0, "buffer ahead: write nothing");
                for (int k = 0; k < 4; k++) w.underrun();
                check(w.target() > 1764 && w.target() <= 4096, "underruns raise the target: " + w.target());
            }
        });

        // ---------------- B1-B5 ICE-like behaviour (owner after car check 2: silence while stopped or slowing
        // down felt odd; idle in P, N and D whenever Ready, quiet in R, engine braking when coasting)
        l.add(new T("B1_ice_idles_in_P_N_D") {
            void run() {
                double[] gears = {Sig.GEAR_P, Sig.GEAR_N, Sig.GEAR_D};
                for (double g : gears) {
                    Rig r = new Rig(iceSettings(Profile.ICE_SMOOTH, 60));
                    r.speed = 0;
                    r.pedal = 0;
                    r.gear = g;
                    r.run(3000);
                    EngineParams o = r.c.lastParams();
                    check("idle".equals(o.reason), "gear " + g + " reason " + o.reason);
                    near(o.targetGain, 0.30, 0.03, "gear " + g + " idle gain");
                    r.resetStats();
                    r.run(1000);
                    check(r.rms() > 100, "gear " + g + " audible idle rms " + Math.round(r.rms()));
                }
            }
        });
        l.add(new T("B2_ice_reverse_quiet") {
            void run() {
                Rig r = new Rig(iceSettings(Profile.ICE_SMOOTH, 60));
                r.gear = Sig.GEAR_R;
                r.speed = 4;
                r.run(3000);
                EngineParams o = r.c.lastParams();
                check(o.targetGain > 0 && o.targetGain <= 0.5 * 0.30 + 0.01, "reverse gain " + o.targetGain + " (" + o.reason + ")");
                // its own reason (0.2.5): "reverse" is classic's silent gate, shown as "P/N/R: 무음"
                check("reverse_quiet".equals(o.reason), "ICE reverse reason " + o.reason);
                Settings cs = iceSettings(Profile.ICE_SMOOTH, 60);
                cs.behavior = "classic";
                Rig c = new Rig(cs);
                c.gear = Sig.GEAR_R;
                c.speed = 4;
                c.run(3000);
                check("reverse".equals(c.c.lastParams().reason) && c.c.lastParams().targetGain == 0,
                        "classic reverse: silent gate " + c.c.lastParams().reason);
            }
        });
        l.add(new T("B3_ice_free_rev_parked") {
            void run() {
                Rig r = new Rig(iceSettings(Profile.ICE_SMOOTH, 60));
                r.gear = Sig.GEAR_P;
                r.speed = 0;
                r.pedal = 50;
                r.run(3000);
                check(r.c.lastParams().rpm > 2500, "50% pedal in P revs: " + r.c.lastParams().rpm);
                r.pedal = 100;
                r.run(3000);
                check(r.c.lastParams().rpm <= 4500 + 1, "rev limit in P: " + r.c.lastParams().rpm);
                r.pedal = 0;
                r.run(3000);
                check(r.c.lastParams().rpm < 1000, "falls back to idle: " + r.c.lastParams().rpm);
            }
        });
        l.add(new T("B4_ice_engine_braking") {
            void run() {
                for (Profile p : new Profile[] {Profile.ICE_SMOOTH, Profile.ICE_SHIFT}) {
                    Rig r = new Rig(iceSettings(p, 60));
                    r.speed = 60;
                    r.pedal = 30;
                    r.kw = 25;
                    r.run(4000);
                    r.pedal = 0;
                    r.kw = -20;
                    double prev = r.c.lastParams().rpm, maxRise = 0, minGain = 1, maxOverrun = 0;
                    boolean rpmAboveIdle = true;
                    for (int i = 0; i < 1000; i++) { // 10 s, 60 -> 0 km/h
                        r.speed = Math.max(0, 60 - 6.0 * (i / 100.0));
                        r.step();
                        EngineParams o = r.c.lastParams();
                        if (i > 30) maxRise = Math.max(maxRise, (o.rpm - prev) / prev);
                        prev = o.rpm;
                        if (i > 20) minGain = Math.min(minGain, o.targetGain);
                        if (i > 100 && r.speed > 5) maxOverrun = Math.max(maxOverrun, o.regen);
                        if (r.speed > 15 && i > 20 && o.rpm < 850) rpmAboveIdle = false;
                    }
                    check(maxRise <= 0.02, p + " rpm never jumps up while coasting: " + maxRise);
                    check(minGain > 0, p + " sound all the way down: " + minGain);
                    check(maxOverrun > 0.5, p + " overrun timbre: " + maxOverrun);
                    check(rpmAboveIdle, p + " rpm above idle while above 15 km/h");
                    check("idle".equals(r.c.lastParams().reason), p + " idles once stopped: " + r.c.lastParams().reason);
                }
            }
        });
        l.add(new T("B5_settings_behavior") {
            void run() {
                eq(new Settings().behavior.equals("ice") ? 1 : 0, 1, "ice is the default");
                java.util.Map<String, String> m = new java.util.HashMap<String, String>();
                m.put("behavior", "classic");
                check("classic".equals(Settings.fromMap(m).behavior), "classic parses");
                m.put("behavior", "nonsense");
                check("ice".equals(Settings.fromMap(m).behavior), "garbage -> ice");
                Settings s = new Settings();
                s.behavior = "classic";
                check(s.copy().equals(s) && s.toString().contains("behavior=classic"), "copy/equals/toString");
                Rig r = new Rig(settings(Profile.ICE_SMOOTH, 60, "drive_all"));
                r.gear = Sig.GEAR_P;
                r.run(3000);
                check(r.c.lastParams().targetGain == 0, "classic keeps P silent");
            }
        });

        // ---------------- P1-P5 exhaust pops on lift-off (owner's choice after car check 2: engine braking plus
        // pops, with a setting)
        l.add(new T("P_pops") {
            void run() {
                // P1 off: none
                Pops p = new Pops(SR, 1);
                p.setLevel(0);
                liftOff(p, 4000, 0);
                eq(p.episodes(), 0, "P1 off");
                // P2 a lift-off at 4000 rpm: 3..8 bursts within 1.5 s
                p = new Pops(SR, 1);
                p.setLevel(1);
                liftOff(p, 4000, 0);
                eq(p.episodes(), 1, "P2 one episode");
                check(p.bursts() >= 3 && p.bursts() <= 8, "P2 bursts " + p.bursts());
                check(p.lastBurstMs() - p.episodeStartMs() <= 1500, "P2 within 1.5 s: " + (p.lastBurstMs() - p.episodeStartMs()));
                // P3 a second lift-off 2 s later: still one episode (4 s apart at least)
                liftOff(p, 4000, 2000);
                eq(p.episodes(), 1, "P3 rate limit");
                liftOff(p, 4000, 6000);
                eq(p.episodes(), 2, "P3 again after 4 s");
                // P4 low rpm: none
                Pops q = new Pops(SR, 1);
                q.setLevel(2);
                liftOff(q, 1800, 0);
                eq(q.episodes(), 0, "P4 none at 1800 rpm");
                // P5 inside the output bounds, through the whole chain
                Settings st = iceSettings(Profile.ICE_SHIFT, 100);
                st.pops = "strong";
                Rig r = new Rig(st);
                for (int k = 0; k < 6; k++) {
                    r.speed = 70;
                    r.pedal = 100;
                    r.kw = 80;
                    r.run(2500);
                    r.pedal = 0;
                    r.kw = -10;
                    r.run(2500);
                }
                check(r.capViolations == 0 && r.peak <= 30000, "P5 bounds: violations " + r.capViolations + " peak " + r.peak);
                check(r.c.pops().episodes() >= 2, "P5 pops happened in the chain: " + r.c.pops().episodes());
            }
        });

        // ---------------- G1-G6 PackVoice: the rpm x load grid player (two nearest loops per layer,
        // constant-power crossfade on log rpm, pitch-shifted by rpm / loop rpm; on/off layers by load)
        l.add(new T("G_pack_voice") {
            void run() throws Exception {
                Pack pk = PackLoader.load(sinePack(false, false));
                PackVoice v = new PackVoice(SR);
                v.setPack(pk);
                // G1 pitch follows rpm between and beyond the loops
                for (double rpm : new double[] {1000, 2000, 3000, 5000, 6200}) {
                    float[] x = new float[SR];
                    v.renderRaw(x, x.length, rpm, 1, 0); // settle
                    v.renderRaw(x, x.length, rpm, 1, 0);
                    double f = pitch(x, 0, x.length, rpm / 10 * 0.8, rpm / 10 * 1.25, 1, SR);
                    near(f, rpm / 10, rpm / 10 * 0.02, "G1 pitch at " + rpm);
                }
                // G2 + G3: a 10 s sweep 1000 -> 6000 rpm keeps its level and has no clicks
                float[] sweep = new float[SR * 10];
                PackVoice w = new PackVoice(SR);
                w.setPack(pk);
                for (int b = 0; b < sweep.length / 220; b++) {
                    double rpm = 1000 + 5000.0 * b / (sweep.length / 220);
                    float[] blk = new float[220];
                    w.renderRaw(blk, 220, rpm, 1, 0);
                    System.arraycopy(blk, 0, sweep, b * 220, 220);
                }
                double lo = 1e9, hi = 0, maxD = 0;
                for (int k = 1; k < 100; k++) {
                    double ss = 0;
                    for (int i = k * 2205; i < (k + 1) * 2205; i++) ss += sweep[i] * sweep[i];
                    double rms = Math.sqrt(ss / 2205);
                    lo = Math.min(lo, rms);
                    hi = Math.max(hi, rms);
                }
                for (int i = 1; i < sweep.length; i++) maxD = Math.max(maxD, Math.abs(sweep[i] - sweep[i - 1]));
                double db = 20 * Math.log10(hi / lo);
                System.out.println("    pack sweep: level spread " + Math.round(db * 100) / 100.0 + " dB, max step " + maxD);
                check(db < 1.0, "G2 level spread " + db + " dB");
                double slope = 2 * Math.PI * 650 / SR * (12000 / 32768.0); // steepest loop (650 Hz) at its amplitude
                check(maxD <= slope * 1.3, "G3 no clicks: max step " + maxD + " vs " + slope);
                // G4 load picks the layer
                PackVoice on = new PackVoice(SR), off = new PackVoice(SR);
                on.setPack(PackLoader.load(sinePack(false, true)));   // off layer silent
                off.setPack(PackLoader.load(sinePack(true, false)));  // on layer silent
                check(rmsOf(on, 3000, 1, 0) > 0.05 && rmsOf(on, 3000, 0, 0) < 1e-4, "G4 load 1 = on layer only, load 0 = off layer only");
                check(rmsOf(off, 3000, 0, 0) > 0.05 && rmsOf(off, 3000, 1, 0) < 1e-4, "G4 mirror");
                check(rmsOf(off, 3000, 1, 1) > 0.05, "G4 overrun uses the off layer");
                // G5 cost: no more than 1.5x the procedural synth
                PackVoice pv = new PackVoice(SR);
                pv.setPack(pk);
                Synth sy = new Synth(SR, Profile.ICE_SMOOTH, 1);
                EngineParams ep = new EngineParams();
                ep.rpm = 3000;
                ep.load = 0.5;
                short[] out = new short[220];
                for (int i = 0; i < 3000; i++) { // warm both up (JIT) before timing
                    ep.rpm = 1000 + 4000 * Math.abs(Math.sin(i / 500.0));
                    pv.render(out, 0, 220, ep, 0.5);
                    sy.render(out, 0, 220, ep, 0.5);
                }
                long t0 = System.nanoTime();
                for (int i = 0; i < 6000; i++) {
                    ep.rpm = 1000 + 4000 * Math.abs(Math.sin(i / 500.0));
                    pv.render(out, 0, 220, ep, 0.5);
                }
                long tp = System.nanoTime() - t0;
                t0 = System.nanoTime();
                for (int i = 0; i < 6000; i++) {
                    ep.rpm = 1000 + 4000 * Math.abs(Math.sin(i / 500.0));
                    sy.render(out, 0, 220, ep, 0.5);
                }
                long ts = System.nanoTime() - t0;
                System.out.println("    60 s render: pack " + tp / 1000000 + " ms, synth " + ts / 1000000 + " ms");
                check(tp <= ts * 1.5 + 20000000L, "G5 pack " + tp / 1000000 + " ms vs synth " + ts / 1000000 + " ms");
                // G6 bounds of the short output
                PackVoice bv = new PackVoice(SR);
                bv.setPack(pk);
                int peak = 0;
                for (int i = 0; i < 2000; i++) {
                    ep.rpm = 900 + 5800 * Math.abs(Math.sin(i / 97.0));
                    ep.load = Math.abs(Math.cos(i / 31.0));
                    double g = 0.7;
                    bv.render(out, 0, 220, ep, g);
                    for (short s : out) peak = Math.max(peak, Math.abs(s));
                }
                check(peak <= 0.7 * 30000 + 1, "G6 |sample| <= gain x 30000: " + peak);
            }
        });

        // ---------------- I1-I2 the sound setting: a pack plays whenever one is loaded, the synth only without one
        // SL1 eight user slots (0.2.6): user1..user8 parse, anything else falls back to the default vex4 (0.2.8)
        l.add(new T("SL1_eight_user_slots") {
            void run() {
                for (int i = 1; i <= 8; i++) {
                    java.util.Map<String, String> m = new java.util.HashMap<String, String>();
                    m.put("sound", "user" + i);
                    eq(Settings.fromMap(m).sound.equals("user" + i) ? 1 : 0, 1, "user" + i + " parses");
                }
                for (String bad : new String[] { "user0", "user9", "user10" }) {
                    java.util.Map<String, String> m = new java.util.HashMap<String, String>();
                    m.put("sound", bad);
                    check("vex4".equals(Settings.fromMap(m).sound), bad + " falls back to vex4: " + Settings.fromMap(m).sound);
                }
            }
        });
        l.add(new T("I1_pack_or_synth") {
            void run() throws Exception {
                Settings st = iceSettings(Profile.ICE_SMOOTH, 60);
                st.sound = "vex4";
                Rig r = new Rig(st);
                r.speed = 30;
                r.pedal = 30;
                r.run(2000);
                check(r.c.status().contains("voice=synth"), "no pack loaded: synth " + r.c.status());
                check(r.rms() > 100, "and it plays");
                r.c.setPack(PackLoader.load(sinePack(false, false)));
                r.resetStats();
                r.run(1000);
                check(r.c.status().contains("voice=pack:sine"), "pack plays " + r.c.status());
                check(r.rms() > 100, "pack audible " + Math.round(r.rms()));
                st.sound = "synth"; // hidden since 0.2.8: no longer a way past a loaded pack
                r.c.setSettings(st);
                r.run(200);
                check(r.c.status().contains("voice=pack:sine"), "sound=synth with a pack loaded plays the pack " + r.c.status());
                st.sound = "vex4";
                r.c.setSettings(st);
                r.c.setPack(null);
                r.run(200);
                check(r.c.status().contains("voice=synth"), "pack dropped: synth again");
                java.util.Map<String, String> m = new java.util.HashMap<String, String>();
                m.put("sound", "user2");
                check("user2".equals(Settings.fromMap(m).sound), "user slots parse");
                m.put("sound", "../x");
                check("vex4".equals(Settings.fromMap(m).sound), "garbage -> vex4");
            }
        });
        l.add(new T("I2_voice_switch_smooth") {
            void run() throws Exception {
                Settings st = iceSettings(Profile.ICE_SMOOTH, 100);
                st.sound = "vex4";
                st.pops = "off";
                Rig r = new Rig(st);
                r.speed = 50;
                r.pedal = 60;
                r.kw = 40;
                r.run(2000);
                Pack pk = PackLoader.load(sinePack(false, false));
                for (int k = 0; k < 6; k++) {
                    r.c.setPack(k % 2 == 0 ? pk : null);
                    r.run(700);
                }
                check(r.capViolations == 0, "inside the gain cap while switching: " + r.capViolations);
                check(r.maxDelta < 46300, "no click on a switch: " + r.maxDelta);
            }
        });

        // ---------------- G7 pack loops are level-matched to the synth: loudness comes from the model (idle
        // level, load), the pack gives the timbre. The first rendered packs played 12-18 dB under the synth
        // at idle (their idle loops are quiet): comparison WAVs, 2026-11-05.
        l.add(new T("G7_pack_level_matched") {
            void run() throws Exception {
                MapSource src = sinePack(false, false);
                int k = 0;
                for (java.util.Map.Entry<String, byte[]> e : src.files.entrySet()) {
                    if (!e.getKey().endsWith(".wav")) continue;
                    double f = e.getKey().startsWith("s840") ? 0.08 : e.getKey().startsWith("s1500") ? 0.2 : 1.0; // -22 dB, -14 dB
                    byte[] b = e.getValue();
                    for (int i = 44; i + 1 < b.length; i += 2) {
                        int v = (short) ((b[i] & 0xff) | b[i + 1] << 8);
                        v = (int) (v * f);
                        b[i] = (byte) v;
                        b[i + 1] = (byte) (v >> 8);
                    }
                    k++;
                }
                PackVoice v = new PackVoice(SR);
                v.setPack(PackLoader.load(src));
                double low = rmsOf(v, 900, 1, 0), high = rmsOf(v, 5000, 1, 0), offl = rmsOf(v, 900, 0, 0);
                System.out.println("    level-matched pack: 900 rpm " + low + ", 5000 rpm " + high + ", off " + offl);
                check(Math.abs(20 * Math.log10(low / high)) < 1.5, "G7 quiet loops brought up: " + low + " vs " + high);
                near(low, 0.30, 0.06, "G7 about the synth's voice level (0.30 rms)");
                check(offl > 0.2, "G7 off layer too: " + offl);
            }
        });

        // ---------------- D1 the car check's pack demo revs the engine sound: only parked
        l.add(new T("D1_pack_demo_only_parked") {
            void run() {
                check(ProbeGuard.demoRefusal(0, Sig.GEAR_P, 1) == null, "P, standing, Ready: allowed");
                check(ProbeGuard.demoRefusal(3, Sig.GEAR_P, 1) != null, "rolling: refused");
                check(ProbeGuard.demoRefusal(0, Sig.GEAR_D, 1) != null, "in D: refused");
                check(ProbeGuard.demoRefusal(0, Sig.GEAR_N, 1) != null, "in N: refused");
                check(ProbeGuard.demoRefusal(Double.NaN, Sig.GEAR_P, 1) != null, "unknown speed: refused");
                check(ProbeGuard.demoRefusal(0, Double.NaN, 1) != null, "unknown gear: refused");
                check(ProbeGuard.demoRefusal(0, Sig.GEAR_P, 0) != null, "not Ready: refused");
                double[] ped = ProbeGuard.demoPedal();
                double max = 0;
                for (double p : ped) max = Math.max(max, p);
                check(ped.length == 60 && ped[0] == 0 && ped[59] == 0 && max <= 80 && max >= 60, "6 s pedal ramp 0 -> " + max + " -> 0");
            }
        });

        // ---------------- Y1-Y5 ducking (owner after the first drive: play quieter under prompts, like music)
        l.add(new T("Y_duck_under_prompts") {
            void run() {
                // Y1 a navigation prompt: down to the duck level within 150 ms, stays open, back up quickly
                AudioPolicy p = new AudioPolicy();
                p.tick(0, true, 0);
                p.tick(10, true, 0.3);
                check(p.isOpen(), "open");
                p.onYieldStart("navi", 1000, true);
                for (long t = 1000; t < 4000; t += 10) {
                    int a = p.tick(t, true, 0.3 * p.yieldGain(t));
                    check(a != AudioPolicy.ACT_CLOSE, "Y1 no close while ducking at " + t);
                    if (t >= 1150) near(p.yieldGain(t), AudioPolicy.DUCK_GAIN, 1e-9, "Y1 ducked at " + t);
                }
                p.onYieldEnd("navi", 4000);
                check(!p.yielding(4010), "Y1 no hold-off after a duck");
                near(p.yieldGain(4000 + AudioPolicy.RAMP_MS), 1, 1e-9, "Y1 back to full within the ramp");
                // Y2 a call (not duckable) on top: silence and close as before
                AudioPolicy q = new AudioPolicy();
                q.tick(0, true, 0);
                q.tick(10, true, 0.3);
                q.onYieldStart("navi", 1000, true);
                q.onYieldStart("btvoice", 2000, false);
                boolean closed = false;
                for (long t = 1000; t < 2300; t += 10) if (q.tick(t, true, 0.3 * q.yieldGain(t)) == AudioPolicy.ACT_CLOSE) closed = true;
                check(closed && q.yieldGain(2200) == 0, "Y2 a call silences and closes");
                // Y3 closed when a prompt starts: no OPEN until it ends (a startAudio could cut the prompt)
                AudioPolicy c = new AudioPolicy();
                c.tick(0, false, 0);
                c.onYieldStart("navi", 1000, true);
                for (long t = 1000; t < 3000; t += 10) check(c.tick(t, true, 0) != AudioPolicy.ACT_OPEN, "Y3 no open during a prompt");
                c.onYieldEnd("navi", 3000);
                boolean opened = false;
                for (long t = 3000; t < 3100; t += 10) if (c.tick(t, true, 0) == AudioPolicy.ACT_OPEN) opened = true;
                check(opened, "Y3 opens right after the prompt");
                // Y4 duck disabled (prompts=pause): the old fade-to-silence
                AudioPolicy d = new AudioPolicy();
                d.tick(0, true, 0);
                d.tick(10, true, 0.3);
                d.onYieldStart("navi", 1000, false);
                check(d.yieldGain(1200) == 0, "Y4 pause mode silences");
                // Y7 car check 2: MICOM closes our channel ~6 ms BEFORE NavigationVoiceStart arrives: no reopen in
                // that gap (a startAudio then could cut the prompt)
                AudioPolicy lp = new AudioPolicy();
                lp.tick(0, true, 0);
                lp.tick(10, true, 0.3);
                lp.channelLost(1000);
                eq(lp.tick(1000, true, 0.3), AudioPolicy.ACT_CLOSE, "Y7 lost: close");
                for (long t = 1010; t < 1000 + AudioPolicy.HOLDOFF_MS; t += 10) check(lp.tick(t, true, 0) != AudioPolicy.ACT_OPEN, "Y7 no reopen at " + t);
                boolean re = false;
                for (long t = 1000 + AudioPolicy.HOLDOFF_MS; t < 1000 + AudioPolicy.HOLDOFF_MS + 100; t += 10) if (lp.tick(t, true, 0) == AudioPolicy.ACT_OPEN) re = true;
                check(re, "Y7 reopens after the pause when nothing speaks");
                // Y5 the classification: prompts duck, calls / ringing / voice recognition don't
                for (String s : new String[] {"navi", "tts", "shared", "dipo_alt", "a18", "a69"}) check(AudioPolicy.duckable(s), "Y5 " + s + " ducks");
                for (String s : new String[] {"btvoice", "ring", "vr", "vui", "dipo_call", "dipo_siri", "btsiri", "lost", "a9", null}) check(!AudioPolicy.duckable(s), "Y5 " + s + " stops");
            }
        });
        l.add(new T("Y6_duck_end_to_end") {
            void run() {
                Settings st = iceSettings(Profile.ICE_SMOOTH, 60);
                st.pops = "off";
                Rig r = new Rig(st);
                r.speed = 40;
                r.pedal = 30;
                r.run(3000);
                r.resetStats();
                r.run(1000);
                double full = r.rms();
                r.c.onYieldStart("navi", r.t);
                r.run(300);
                r.resetStats();
                r.run(1500);
                double ducked = r.rms();
                check(r.closes == 0 && ducked > full * 0.2 && ducked < full * 0.45, "ducked to ~30%: " + Math.round(ducked) + " of " + Math.round(full));
                check(r.c.status().contains(" duck=1"), "status says ducking: " + r.c.status());
                r.c.onYieldEnd("navi", r.t);
                r.run(400);
                r.resetStats();
                r.run(500);
                check(r.rms() > full * 0.8, "full again soon after: " + Math.round(r.rms()));
                st.prompts = "pause";
                r.c.setSettings(st);
                r.c.onYieldStart("navi", r.t);
                r.run(500);
                r.resetStats();
                r.run(500);
                check(r.rms() == 0, "prompts=pause: silent under the prompt");
                java.util.Map<String, String> m = new java.util.HashMap<String, String>();
                check("duck".equals(Settings.fromMap(m).prompts), "duck is the default");
                m.put("prompts", "pause");
                check("pause".equals(Settings.fromMap(m).prompts), "pause parses");
            }
        });

        // ---------------- L4 the real AudioTrack (Android 4.4 AudioFlinger): a streaming track starts playing
        // only once its whole buffer is full, and goes back to filling after an underrun. 0.2.0-0.2.2 kept 80 ms
        // queued in a 186 ms buffer: playback never started, vesd waited forever (stuck on "idle", silent;
        // drive 2026-11-05). 0.2.3 filled whole blocks only, 3960 of the car's 4096 frames: the same (stuck on
        // "drive", or on a prompt that came after; drives 2026-11-05/06). Its test started the track at
        // max - block, a block short of full.
        l.add(new T("L4_pacer_with_fill_to_start_track") {
            void run() {
                int max = 4096, block = 220; // the car: 8192 B minimum buffer, 10 ms blocks
                long[] r = feed(new WritePacer(1764, block, max), new Flinger(max, false), block, 20000);
                eq(r[3], 0, "no write hangs");
                check(r[0] > 22050 * 15, "it plays (" + r[0] + " frames in 20 s)");
                check(r[1] <= 1764 + 2 * block, "once running, about 80 ms queued: " + r[1]);
            }
        });
        // L5 any buffer size: a frame count that is not a multiple of the block, or smaller than vesd thinks
        l.add(new T("L5_pacer_any_frame_count") {
            void run() {
                int block = 220;
                int[] sizes = { 4096, 4410, 3528, 4180, 2048, 8192 };
                for (int f : sizes) {
                    long[] r = feed(new WritePacer(1764, block, f), new Flinger(f, false), block, 20000);
                    eq(r[3], 0, f + " frames: no write hangs");
                    check(r[0] > 22050 * 15, f + " frames: it plays (" + r[0] + " frames in 20 s)");
                }
                long[] r = feed(new WritePacer(1764, block, 4096), new Flinger(4000, false), block, 20000);
                eq(r[3], 0, "real buffer smaller than vesd thinks: no write hangs");
                check(r[0] > 22050 * 15, "real buffer smaller than vesd thinks: it plays (" + r[0] + ")");
                // a 300 ms gap in the writer mid-drive: the track runs dry, must be filled whole again
                Flinger g = new Flinger(4096, false);
                r = feed(new WritePacer(1764, block, 4096), g, block, 20000, 8000, 300);
                eq(r[3], 0, "after a gap: no write hangs");
                check(r[2] >= 2, "after a gap: started again (" + r[2] + " starts)");
                check(r[0] > 22050 * 15, "after a gap: it plays (" + r[0] + ")");
            }
        });
        // L6 a track that never plays (dead, or its buffer bigger than vesd thinks): stuck, never a hung write
        l.add(new T("L6_pacer_stuck") {
            void run() {
                int block = 220;
                WritePacer w = new WritePacer(1764, block, 4096);
                Flinger dead = new Flinger(4096, true);
                long stuckAt = -1, fullAt = -1;
                int pending = 0;
                for (long t = 0; t < 3000 && stuckAt < 0; t += 5) {
                    for (int k = 0; k < 64; k++) {
                        int n = w.framesToWrite(dead.written, dead.played, t);
                        if (n <= 0) break;
                        if (pending == 0) pending = block;
                        pending -= dead.write(Math.min(n, pending));
                    }
                    if (fullAt < 0 && dead.fill() >= 4096) fullAt = t;
                    if (w.stuck(t)) stuckAt = t;
                }
                check(!dead.blocked, "dead track: no write hangs");
                eq(fullAt, 0, "dead track: filled at once");
                check(stuckAt >= 1000 && stuckAt <= 1100, "dead track: stuck after ~1 s: " + stuckAt + " ms");
                w.reset(4096);
                check(!w.stuck(stuckAt), "a new track: not stuck");
                eq(w.max(), 4096, "reset keeps the frame count given");
                int[] sizes = { 4096, 4410, 4180, 2048 };
                for (int f : sizes) {
                    WritePacer h = new WritePacer(1764, block, f);
                    Flinger fl = new Flinger(f, false);
                    pending = 0;
                    for (long t = 0; t < 20000; t += 5) {
                        boolean gap = t >= 8000 && t < 8300;
                        for (int k = 0; k < 64 && !gap; k++) {
                            int n = h.framesToWrite(fl.written, fl.played, t);
                            if (n <= 0) break;
                            if (pending == 0) pending = block;
                            pending -= fl.write(Math.min(n, pending));
                        }
                        check(!h.stuck(t), f + " frames: a playing track is never stuck (t=" + t + ")");
                        fl.step();
                    }
                }
            }
        });

        // L7 the audio loop (TrackFeeder): a fresh track per OPEN plays within a block of filling; a track that
        // never plays is replaced with a backoff, and the loop keeps rendering meanwhile (no frozen status, the
        // channel still closes); a lazy-flush loss on the first track costs at most one reset
        l.add(new T("L7_track_feeder") {
            void run() {
                final int block = 220;
                long[] r = loop(new TrackFeeder(new WritePacer(1764, block, 4096), block), new Tracks() {
                    public Flinger make(int n) { return new Flinger(4096, false); }
                }, 20000, 2000);
                eq(r[3], 0, "reopen x10: no write hangs");
                eq(r[1], 0, "reopen x10: no resets");
                check(r[4] <= 1000, "reopen x10: every OPEN plays within 1 s: " + r[4] + " ms");
                check(r[0] > 22050 * 15, "reopen x10: it plays (" + r[0] + ")");
                r = loop(new TrackFeeder(new WritePacer(1764, block, 4096), block), new Tracks() {
                    public Flinger make(int n) {
                        Flinger f = new Flinger(4096, false);
                        if (n == 0) f.discard = 880;
                        return f;
                    }
                }, 20000, 0);
                eq(r[3], 0, "lazy flush: no write hangs");
                eq(r[1], 1, "lazy flush: one reset brings it back");
                check(r[0] > 22050 * 15, "lazy flush: it plays (" + r[0] + ")");
                r = loop(new TrackFeeder(new WritePacer(1764, block, 4096), block), new Tracks() {
                    public Flinger make(int n) { return new Flinger(4096, true); }
                }, 20000, 0);
                eq(r[3], 0, "dead tracks: no write hangs");
                check(r[1] >= 3 && r[1] <= 5, "dead tracks: resets back off (1+2+4+8 s): " + r[1]);
                check(r[2] >= (20000 - 1500) / 20, "dead tracks: still rendering a block per 20 ms: " + r[2]);
                check(r[5] > 0, "dead tracks: blocks dropped meanwhile: " + r[5]);
                TrackFeeder fd = new TrackFeeder(new WritePacer(1764, block, 4096), block);
                fd.newTrack(4096);
                fd.wrote(-6); // a failed write (dead object)
                eq(fd.next(0, 0), TrackFeeder.RESET, "a failed write: make a new track");
                // writes that return 0 (interrupted, would block): no busy loop of underruns, then a new track
                fd = new TrackFeeder(new WritePacer(1764, block, 4096), block);
                fd.newTrack(4096);
                long t = 0, head = 0;
                for (int i = 0; i < 40; i++, t += 5) { // play a while, then the head catches up: dry
                    int d = fd.next(head, t);
                    if (d == TrackFeeder.RENDER) {
                        fd.rendered();
                        d = fd.next(head, t);
                    }
                    if (d > 0) fd.wrote(d);
                    head = Math.min(fd.written(), head + 110);
                }
                head = fd.written();
                int zeroAt = -1;
                for (int i = 0; i < 400; i++, t += 4) {
                    int d = fd.next(head, t);
                    if (d == TrackFeeder.RENDER) {
                        fd.rendered();
                        continue;
                    }
                    if (d == TrackFeeder.RESET) {
                        zeroAt = i;
                        break;
                    }
                    if (d > 0) fd.wrote(0);
                }
                check(fd.underruns() <= 1, "zero writes: one underrun, not one per try: " + fd.underruns());
                check(zeroAt > 0 && zeroAt <= 60, "zero writes: a new track after ~50 tries: " + zeroAt);
            }
        });

        // E1 the app's one-line status (StatusLabel): unchanged labels, and a frozen or silent audio loop is shown
        // as such, never as the state it froze in (0.2.3: "주행 중 · 재생" or "안내 음성 중 (작게)" for hours)
        l.add(new T("E1_status_label") {
            void run() {
                String[][] cases = {
                        { "error x", "서비스 연결 안 됨" },
                        { "state=drive rpm=1 open=1 yield=0 gear=1", "주행 중 · 재생" },
                        { "state=drive rpm=1 open=0 yield=0", "주행 중" },
                        { "state=idle open=1 yield=0", "공회전" },
                        { "state=regen open=1 yield=0", "회생제동" },
                        { "state=park open=0 yield=0", "P/N/R: 무음" },
                        { "state=not_ready open=0 yield=0", "Ready 아님" },
                        { "state=stale open=0 yield=0", "차량 신호 없음" },
                        { "state=idle open=1 yield=1(shared) voice=synth duck=1", "안내 음성 중 (작게)" },
                        { "state=idle open=0 yield=1(navi) voice=synth", "안내 음성 중 (잠시 멈춤)" },
                        { "state=drive open=1 yield=0\naudio loop_age_ms=12 play_age_ms=30", "주행 중 · 재생" },
                        { "state=drive open=1 yield=0\naudio loop_age_ms=3000 play_age_ms=3000", "주행 중 · 재생" },
                        { "state=drive open=1 yield=1(shared) duck=1\naudio loop_age_ms=4000 play_age_ms=-1",
                                "소리 처리 멈춤" },
                        { "state=idle open=1 yield=0\naudio loop_age_ms=20 play_age_ms=4000", "소리 출력 안 됨" },
                        { "state=idle open=1 yield=1(shared) duck=1\naudio loop_age_ms=20 play_age_ms=3500", "소리 출력 안 됨" },
                        { "state=idle open=0 yield=0\naudio loop_age_ms=20 play_age_ms=-1", "공회전" },
                        { "state=idle open=1 yield=0\naudio loop_age_ms=20 play_age_ms=-1 resets=0", "공회전" },
                        // 0.2.5: the ICE states have their own words; "P/N/R: 무음" only for classic's gates
                        { "state=reverse_quiet open=1 yield=0", "후진: 작게" },
                        { "state=rev open=1 yield=0", "공회전 (가속)" },
                        { "state=overrun open=1 yield=0", "엔진 브레이크" },
                        { "state=gear_none open=0 yield=0", "기어 신호 없음" },
                        { "state=reverse open=0 yield=0", "P/N/R: 무음" },
                        { "state=neutral open=0 yield=0", "P/N/R: 무음" },
                };
                for (String[] c : cases) {
                    String got = StatusLabel.of(c[0]);
                    check(c[1].equals(got), "[" + c[0].replace('\n', '|') + "] -> " + got + " (want " + c[1] + ")");
                }
                VesCore core = new VesCore(22050);
                core.renderBlock(new short[220], 220, 500);
                eq(core.lastRenderMs(), 500, "the last rendered block's time");
            }
        });

        // R1 startAudio refused (car log 11-06 08:52:47, "startAudio(0x45)=false"): the path is not open, no
        // endAudio for it, a new try after 1, 2, 4, 8, 16 then every 30 s; a yield holds the try off
        l.add(new T("R1_open_refused_retry") {
            void run() {
                AudioPolicy p = new AudioPolicy();
                long t = 0;
                eq(p.tick(t, true, 0), AudioPolicy.ACT_CLOSE, "first tick: cleanup");
                t += 10;
                eq(p.tick(t, true, 0), AudioPolicy.ACT_OPEN, "opens");
                p.openResult(false, t);
                check(!p.isOpen(), "refused: not open");
                eq(p.retries(), 1, "one refusal");
                t += 10;
                eq(p.tick(t, true, 0.5), AudioPolicy.ACT_NONE, "refused: no CLOSE (an endAudio could cut someone's sound)");
                long[] waits = { 1000, 2000, 4000, 8000, 16000, 30000, 30000 };
                long refusedAt = t - 10;
                for (int k = 0; k < waits.length; k++) {
                    long due = refusedAt + waits[k];
                    long u = refusedAt + 10;
                    int act = AudioPolicy.ACT_NONE;
                    for (; u < due + 200 && act != AudioPolicy.ACT_OPEN; u += 10) act = p.tick(u, true, 0);
                    long openedAt = u - 10;
                    check(act == AudioPolicy.ACT_OPEN && openedAt >= due && openedAt < due + 20,
                            "try " + (k + 2) + " after " + waits[k] + " ms: at +" + (openedAt - refusedAt));
                    refusedAt = openedAt;
                    p.openResult(false, refusedAt);
                }
                p.openResult(true, refusedAt); // (the next OPEN's answer)
                long u = refusedAt + 30000;
                int act = AudioPolicy.ACT_NONE;
                for (; act != AudioPolicy.ACT_OPEN && u < refusedAt + 31000; u += 10) act = p.tick(u, true, 0);
                p.openResult(true, u);
                eq(p.retries(), 0, "accepted: the backoff starts over");
                check(p.isOpen(), "accepted: open");
                // a yield holds the retry off
                AudioPolicy q = new AudioPolicy();
                q.tick(0, true, 0);
                q.tick(10, true, 0);
                q.openResult(false, 10);
                q.onYieldStart("navi", 500, false);
                boolean opened = false;
                for (long v = 520; v < 3000; v += 10) opened |= q.tick(v, true, 0) == AudioPolicy.ACT_OPEN;
                check(!opened, "no retry while a prompt speaks");
                VesCore c = new VesCore(22050);
                short[] b = new short[220];
                c.renderBlock(b, 220, 0);
                c.openResult(false, 0);
                check(c.status().contains(" retry=1"), "status shows the retry: " + c.status());
                check("소리 채널 재시도 중".equals(StatusLabel.of("state=idle open=0 yield=0 retry=1")), "label");
                check("공회전".equals(StatusLabel.of("state=idle open=1 yield=0")), "label without retry unchanged");
            }
        });

        // G8 aligned loops whose length was rounded down to a sample still play their last whole cycle
        // (0.2.5 truncated len/spc to whole cycles: 6500 rpm, 43 cycles = 17504.3 samples -> 42 played)
        // PL1 pack level (0.2.6): "level <dB>" in pack.txt scales every loop after level matching; packs whose
        // energy sits in the speaker's 400-2500 Hz lift (resynthesized ones) otherwise drive the soft clip hard
        l.add(new T("PL1_pack_level") {
            Pack withLevel(String line) throws PackError {
                java.util.Map<String, byte[]> m = new java.util.HashMap<String, byte[]>();
                StringBuilder t = new StringBuilder("name lv\ncylinders 4\nidle 800\nredline 6500\naligned yes\n");
                if (line != null) t.append(line).append('\n');
                for (int[] lk : new int[][] { { 1100, 7 }, { 2000, 9 }, { 6500, 43 } }) {
                    String f = "m" + lk[0] + ".wav";
                    m.put(f, wavPcm(markedLoop(lk[0], lk[1])));
                    t.append("loop ").append(f).append(" rpm=").append(lk[0]).append(" load=on\n");
                }
                m.put("pack.txt", t.toString().getBytes());
                return PackLoader.load(new MapSource(m));
            }

            double rms(Pack p) {
                PackVoice v = new PackVoice(SR);
                v.setPack(p);
                float[] out = new float[22050];
                v.renderRaw(out, out.length, 2000, 1, 0);
                double ss = 0;
                for (float y : out) ss += (double) y * y;
                return Math.sqrt(ss / out.length);
            }

            void run() throws Exception {
                Pack none = withLevel(null), m6 = withLevel("level -6"), z = withLevel("level 0"), p35 = withLevel("level 3.5");
                check(none.level == 1.0, "no level line: 1.0 (" + none.level + ")");
                double r0 = rms(none);
                check(Math.abs(rms(m6) / r0 - Math.pow(10, -6 / 20.0)) < 0.002, "level -6: RMS x 0.501 (" + rms(m6) / r0 + ")");
                check(rms(z) == r0, "level 0: unchanged");
                check(Math.abs(rms(p35) / r0 - Math.pow(10, 3.5 / 20.0)) < 0.002, "level 3.5: RMS x 1.496 (" + rms(p35) / r0 + ")");
                for (String bad : new String[] { "level", "level x", "level -24.5", "level 12.5", "level 1e1", "level NaN", "level 3 4" }) {
                    try {
                        withLevel(bad);
                        check(false, "refused: " + bad);
                    } catch (PackError e) {
                        check(true, "refused: " + bad + " (" + e.getMessage() + ")");
                    }
                }
                check(withLevel("level -24").level > 0 && withLevel("level 12").level > 3.9, "the range ends -24 and 12 load");
            }
        });
        l.add(new T("G8_aligned_last_cycle") {
            void run() throws Exception {
                int[][] loops = { { 1100, 7 }, { 2000, 9 }, { 6500, 43 } };
                java.util.Map<String, byte[]> m = new java.util.HashMap<String, byte[]>();
                StringBuilder t = new StringBuilder("name marked\ncylinders 4\nidle 800\nredline 6500\naligned yes\n");
                for (int[] lk : loops) {
                    String f = "m" + lk[0] + ".wav";
                    m.put(f, wavPcm(markedLoop(lk[0], lk[1])));
                    t.append("loop ").append(f).append(" rpm=").append(lk[0]).append(" load=on\n");
                }
                m.put("pack.txt", t.toString().getBytes());
                Pack p = PackLoader.load(new MapSource(m));
                check(p.aligned, "aligned pack");
                for (int[] lk : loops) {
                    if (lk[0] == 2000) continue; // a whole number of samples per loop: fine either way
                    PackVoice v = new PackVoice(SR);
                    v.setPack(p);
                    double spc = 2646000.0 / lk[0];
                    int frames = (int) (3 * lk[1] * spc);
                    float[] out = new float[frames];
                    v.renderRaw(out, frames, lk[0], 1, 0);
                    double peak = 0, quiet = 0;
                    for (float y : out) peak = Math.max(peak, Math.abs(y));
                    // the loud last cycle comes round once per loop: about 1/k of the samples near the peak
                    int loud = 0;
                    for (float y : out) if (Math.abs(y) > 0.5 * peak) loud++;
                    double share = loud / (double) frames;
                    for (int i = 0; i < (int) spc; i++) quiet = Math.max(quiet, Math.abs(out[i]));
                    check(peak > 5 * quiet, lk[0] + " rpm: the last (loud) cycle is played (peak " + peak + " vs quiet " + quiet + ")");
                    check(share > 0.3 / lk[1], lk[0] + " rpm: loud share " + share + " (one cycle in " + lk[1] + ")");
                }
            }
        });

        // ---------------- S5 volume and envelope
        l.add(new T("S5_volume_envelope") {
            void run() {
                // 0.2.7: the whole curve 6 dB louder (0.2.6: (v/100)^1.5, 100 => 1.0)
                double up6 = Math.pow(10, 6 / 20.0);
                near(GainEnvelope.volumeToLinear(100), up6, 1e-9, "100 => +6 dB over full scale");
                near(GainEnvelope.volumeToLinear(0), 0, 0, "0 => 0");
                double prev = -1;
                for (int v = 0; v <= 100; v++) {
                    double g = GainEnvelope.volumeToLinear(v);
                    near(g, Math.pow(v / 100.0, 1.5) * up6, 1e-12, "1.5 power law +6 dB at " + v);
                    check(g > prev, "monotonic at " + v);
                    prev = g;
                }
                near(GainEnvelope.volumeToLinear(150), up6, 1e-9, "clamp high");
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

        LevelTests.add(l); // 0.2.7: loudness, launch, makeup, volume curve, speed boost
        BuiltinTests.add(l); // 0.2.8: built-in packs by default, the synth and the CC0 packs hidden
        return l;
    }
}
