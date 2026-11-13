package ves.resynth;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Tests for ves.resynth (no JUnit on purpose, as core/test). Every fixture is synthetic, made here with
 * known parameters; files go to a temp folder outside any git worktree. Prints "PASS id ..." or
 * "FAIL id: why" per test, exit 1 on any failure. Usage: ResynthTest [name filter]
 */
public final class ResynthTest {
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

    static String f3(double x) {
        return String.format(java.util.Locale.ROOT, "%.3f", x);
    }

    static final List<String> NOTES = new ArrayList<String>();

    /** a measured figure, printed after the PASS line */
    static void note(String s) {
        NOTES.add(s);
    }

    public static void main(String[] args) {
        String filter = args.length > 0 ? args[0] : null;
        List<T> tests = all();
        int pass = 0, fail = 0;
        for (T t : tests) {
            if (filter != null && t.name.indexOf(filter) < 0) continue;
            NOTES.clear();
            long t0 = System.nanoTime();
            try {
                t.run();
                System.out.println("PASS " + t.name + " (" + (System.nanoTime() - t0) / 1000000 + " ms)");
                pass++;
            } catch (Throwable e) {
                String m = e.getMessage() != null ? e.getMessage() : e.toString();
                System.out.println("FAIL " + t.name + ": " + m);
                StackTraceElement[] st = e.getStackTrace();
                for (int k = 0; k < st.length && k < 4; k++) System.out.println("    at " + st[k]);
                fail++;
            }
            for (String n : NOTES) System.out.println("     " + n);
        }
        System.out.println("resynth tests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ helpers

    static File tmpDir(String tag) throws IOException {
        File d = new File(System.getProperty("java.io.tmpdir"), "ves-resynth-test-" + tag + "-" + System.nanoTime());
        if (!d.mkdirs()) throw new IOException("cannot make " + d);
        return d;
    }

    static void rmrf(File f) {
        File[] k = f.listFiles();
        if (k != null) for (File c : k) rmrf(c);
        f.delete();
    }

    static void le16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write(v >> 8 & 0xff);
    }

    static void le32(ByteArrayOutputStream o, int v) {
        le16(o, v & 0xffff);
        le16(o, v >>> 16);
    }

    static void tag(ByteArrayOutputStream o, String t) {
        for (int i = 0; i < 4; i++) o.write(t.charAt(i));
    }

    /** a RIFF/WAVE file by hand: fmt, an odd-sized LIST chunk (padded), a junk chunk, then data */
    static byte[] wavBytes(int channels, int bits, int rate, short[] s, int claimedDataBytes, boolean junk) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        tag(body, "WAVE");
        tag(body, "fmt ");
        le32(body, 16);
        le16(body, 1);
        le16(body, channels);
        le32(body, rate);
        le32(body, rate * channels * bits / 8);
        le16(body, channels * bits / 8);
        le16(body, bits);
        if (junk) {
            tag(body, "LIST");
            le32(body, 5);
            for (int i = 0; i < 5; i++) body.write('a' + i);
            body.write(0); // pad byte of the odd chunk
            tag(body, "junk");
            le32(body, 3);
            body.write(1);
            body.write(2);
            body.write(3);
            body.write(0);
        }
        tag(body, "data");
        le32(body, claimedDataBytes);
        for (short v : s) le16(body, v);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        tag(o, "RIFF");
        le32(o, body.size());
        byte[] b = body.toByteArray();
        o.write(b, 0, b.length);
        return o.toByteArray();
    }

    /** amplitude (linear) and frequency offset of a tone near f in x (rate sr): least squares on the phase */
    static double[] tone(double[] x, int sr, double f, int from, int to) {
        int blocks = 20, len = (to - from) / blocks;
        double[] ph = new double[blocks];
        double amp = 0;
        for (int b = 0; b < blocks; b++) {
            double re = 0, im = 0, ws = 0;
            for (int i = 0; i < len; i++) {
                int k = from + b * len + i;
                double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * (i + 0.5) / len);
                double a = 2 * Math.PI * f * k / sr;
                re += w * x[k] * Math.cos(a);
                im -= w * x[k] * Math.sin(a);
                ws += w;
            }
            amp += 2 * Math.hypot(re, im) / ws / blocks;
            ph[b] = Math.atan2(im, re);
        }
        double prev = ph[0], acc = ph[0];
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int b = 0; b < blocks; b++) {
            if (b > 0) {
                double d = ph[b] - prev;
                d -= 2 * Math.PI * Math.rint(d / (2 * Math.PI));
                acc += d;
                prev = ph[b];
            }
            double t = (b * len) / (double) sr;
            sx += t;
            sy += acc;
            sxx += t * t;
            sxy += t * acc;
        }
        double slope = (blocks * sxy - sx * sy) / (blocks * sxx - sx * sx); // rad/s
        return new double[] {amp, slope / (2 * Math.PI)};
    }

    // ------------------------------------------------------------------ synthetic engine fixtures

    static final int SR = 22050;
    static final int HOLD = 0, ON = 1, OFF = 2;

    /** a drive: rpm and load state per sample, plus the times of every step (corners, shifts, load changes) */
    static final class Drive {
        final double[] rpm;
        final int[] state;
        final List<double[]> steps = new ArrayList<double[]>(); // [t0, t1]: rpm or load jumps between t0 and t1
        final List<double[]> shifts = new ArrayList<double[]>();

        Drive(double seconds) {
            rpm = new double[(int) (seconds * SR)];
            state = new int[rpm.length];
        }

        double rpmAt(double t) {
            double p = t * SR;
            int i = (int) Math.floor(p);
            if (i < 0) return rpm[0];
            if (i >= rpm.length - 1) return rpm[rpm.length - 1];
            return rpm[i] + (rpm[i + 1] - rpm[i]) * (p - i);
        }

        int stateAt(double t) {
            int i = (int) Math.round(t * SR);
            return state[Math.max(0, Math.min(state.length - 1, i))];
        }

        /** rpm from a to b over [t0, t1): linear, or a cosine-shaped drop (shift) */
        Drive seg(double t0, double t1, double a, double b, int st, boolean shift) {
            int i0 = (int) Math.round(t0 * SR), i1 = Math.min(rpm.length, (int) Math.round(t1 * SR));
            for (int i = i0; i < i1; i++) {
                double f = (i - i0) / (double) (i1 - i0);
                if (shift) f = 0.5 - 0.5 * Math.cos(Math.PI * f);
                rpm[i] = a + (b - a) * f;
                state[i] = st;
            }
            if (shift) {
                steps.add(new double[] {t0, t1});
                shifts.add(new double[] {t0, t1});
            }
            return this;
        }

        Drive step(double t) {
            steps.add(new double[] {t, t});
            return this;
        }

        boolean nearStep(double t, double margin) {
            for (double[] s : steps) if (t >= s[0] - margin && t <= s[1] + margin) return true;
            return t < margin || t > rpm.length / (double) SR - margin;
        }
    }

    /** launch-like: part-load hold, a 2500 rpm/s pull, 150 ms shift drops, slow pulls, a lift-off and a coast */
    static Drive launch() {
        Drive d = new Drive(14);
        d.seg(0, 1.5, 3400, 3400, HOLD, false).step(1.5);
        d.seg(1.5, 3.1, 3400, 7400, ON, false);
        d.seg(3.1, 3.25, 7400, 4900, ON, true);
        d.seg(3.25, 6.5, 4900, 6900, ON, false);
        d.seg(6.5, 6.65, 6900, 5100, ON, true);
        d.seg(6.65, 10.0, 5100, 6800, ON, false).step(10.0);
        d.seg(10.0, 14.0, 6800, 3200, OFF, false);
        return d;
    }

    /** order level in dB (amplitude re 1) for an engine with cyl cylinders: the firing order and its multiples lead */
    static double orderDb(int n, int cyl, int state) {
        double oct = Math.log(Math.max(n, cyl) / (double) cyl) / Math.log(2);
        double db = (n % cyl == 0 ? 0 : -14) - 5 * oct;
        if (state == OFF) db += -10 - 3 * oct;
        if (state == HOLD) db -= 17;
        return db - 14; // firing order at about 0.2
    }

    interface Levels {
        /** dB re amplitude 1 of order n (may be fractional, e.g. 4.5) at this rpm and state; NaN = absent */
        double db(double n, double rpm, int state);
    }

    /**
     * Additive engine: sum over orders of A cos(2 pi n theta + phase), theta = integral of rpm / 120; orders above
     * 9 kHz left out; white noise at noiseDb (RMS, re 1). Order levels glide over 20 ms when the state changes.
     */
    static double[] engine(Drive d, Levels lv, double[] extraOrders, double noiseDb, long seed) {
        int nmax = 400;
        Random r = new Random(seed);
        double[] ph = new double[nmax + 1];
        for (int n = 0; n <= nmax; n++) ph[n] = 2 * Math.PI * r.nextDouble();
        double[] phx = new double[extraOrders.length];
        for (int k = 0; k < phx.length; k++) phx[k] = 2 * Math.PI * r.nextDouble();
        double[] x = new double[d.rpm.length];
        double theta = 0, g = 1 - Math.exp(-1.0 / (0.02 * SR / 3));
        double[][] amp = new double[3][nmax + 1];
        double[] sm = new double[nmax + 1];
        double lastRpm = -1;
        double noise = Math.pow(10, noiseDb / 20);
        double[] cph = new double[nmax + 1], sph = new double[nmax + 1];
        for (int n = 0; n <= nmax; n++) {
            cph[n] = Math.cos(ph[n]);
            sph[n] = Math.sin(ph[n]);
        }
        for (int i = 0; i < x.length; i++) {
            double rp = d.rpm[i], fc = rp / 120;
            if (Math.abs(rp - lastRpm) > 5) { // levels as a function of rpm, refreshed every 5 rpm
                for (int s = 0; s < 3; s++)
                    for (int n = 1; n <= nmax; n++) {
                        double db = lv.db(n, rp, s);
                        amp[s][n] = Double.isNaN(db) ? 0 : Math.pow(10, db / 20);
                    }
                lastRpm = rp;
            }
            int st = d.state[i];
            double c = Math.cos(2 * Math.PI * theta), s = Math.sin(2 * Math.PI * theta);
            double re = 1, im = 0, acc = 0;
            for (int n = 1; n <= nmax; n++) {
                double nr = re * c - im * s;
                im = re * s + im * c;
                re = nr;
                double target = n * fc < 9000 ? amp[st][n] : 0;
                sm[n] += (target - sm[n]) * g;
                if (sm[n] != 0) acc += sm[n] * (re * cph[n] - im * sph[n]);
            }
            for (int k = 0; k < extraOrders.length; k++) {
                double db = lv.db(extraOrders[k], rp, st);
                if (!Double.isNaN(db)) acc += Math.pow(10, db / 20) * Math.cos(2 * Math.PI * extraOrders[k] * theta + phx[k]);
            }
            x[i] = acc + noise * r.nextGaussian();
            theta += fc / SR;
        }
        return x;
    }

    static Levels comb(final int cyl) {
        return new Levels() {
            public double db(double n, double rpm, int state) {
                return orderDb((int) n, cyl, state);
            }
        };
    }

    // ------------------------------------------------------------------ tests

    static List<T> all() {
        List<T> l = new ArrayList<T>();

        l.add(new T("RS-W1 WAV reader: LIST and odd chunks, exact samples; stereo, 8-bit, truncated throw") {
            void run() throws Exception {
                Random r = new Random(1);
                short[] s = new short[1001];
                for (int i = 0; i < s.length; i++) s[i] = (short) (r.nextInt(65536) - 32768);
                s[0] = Short.MIN_VALUE;
                s[1] = Short.MAX_VALUE;
                WavIO.Audio a = WavIO.read(wavBytes(1, 16, 48000, s, s.length * 2, true));
                check(a.rate == 48000, "rate " + a.rate);
                check(a.x.length == s.length, "length " + a.x.length);
                for (int i = 0; i < s.length; i++) check(a.x[i] * 32768.0 == s[i], "sample " + i);
                String[] bad = {"stereo", "8-bit", "truncated"};
                byte[][] bb = {wavBytes(2, 16, 48000, s, s.length * 2, true), wavBytes(1, 8, 48000, s, s.length * 2, false),
                        wavBytes(1, 16, 48000, s, s.length * 2 + 400, true)};
                for (int k = 0; k < bad.length; k++) {
                    boolean threw = false;
                    try {
                        WavIO.read(bb[k]);
                    } catch (IOException e) {
                        threw = true;
                    }
                    check(threw, bad[k] + " input must throw IOException");
                }
                // write and read back: the same samples
                File d = tmpDir("w1");
                try {
                    File f = new File(d, "x.wav");
                    WavIO.write16(f, s, 22050);
                    WavIO.Audio b = WavIO.read(f);
                    check(b.rate == 22050 && b.x.length == s.length, "write/read shape");
                    for (int i = 0; i < s.length; i++) check(b.x[i] * 32768.0 == s[i], "write/read sample " + i);
                } finally {
                    rmrf(d);
                }
            }
        });

        l.add(new T("RS-W2 48k to 22.05k: 1 kHz keeps its frequency within 0.01 %, 15 kHz alias below -60 dB") {
            void run() {
                int n = 48000 * 2;
                double[] a = new double[n], b = new double[n];
                for (int i = 0; i < n; i++) {
                    a[i] = 0.5 * Math.sin(2 * Math.PI * 1000 * i / 48000.0);
                    b[i] = 0.5 * Math.sin(2 * Math.PI * 15000 * i / 48000.0);
                }
                double[] ya = Resampler.resample(a, 48000, 22050), yb = Resampler.resample(b, 48000, 22050);
                check(Math.abs(ya.length - n * 22050 / 48000) <= 1, "length " + ya.length);
                int from = 2205, to = ya.length - 2205;
                double[] t1 = tone(ya, 22050, 1000, from, to);
                double rel = Math.abs(t1[1]) / 1000;
                note("1 kHz: amplitude " + f3(20 * Math.log10(t1[0] / 0.5)) + " dB, frequency error " + rel * 100 + " %");
                check(rel < 1e-4, "1 kHz frequency off by " + rel * 100 + " %");
                near(20 * Math.log10(t1[0] / 0.5), 0, 0.1, "1 kHz passband level dB");
                double alias = tone(yb, 22050, 22050 - 15000, from, to)[0];
                double db = 20 * Math.log10(alias / 0.5 + 1e-30);
                note("15 kHz alias at 7050 Hz: " + f3(db) + " dB");
                check(db < -60, "alias " + db + " dB");
            }
        });

        l.add(new T("RS-T1 tracker: hold, 2500 rpm/s pull, 150 ms shifts, slow pulls, lift-off, coast") {
            void run() {
                Drive d = launch();
                double[] x = engine(d, comb(4), new double[0], -60, 11);
                Tracker.Result res = Tracker.track(x, SR, 0);
                Track tr = res.track;
                List<Double> errs = new ArrayList<Double>();
                double maxErr = 0, maxAt = 0;
                int octave = 0, frames = 0;
                for (int k = 0; k < tr.fc.length; k++) {
                    double t = tr.time(k);
                    if (t < 0 || t > 14) continue;
                    double truth = d.rpmAt(t), est = 120 * tr.fc[k];
                    double e = Math.abs(est / truth - 1);
                    frames++;
                    if (e > 0.25) octave++;
                    if (d.nearStep(t, 0.05)) continue;
                    errs.add(e);
                    if (e > maxErr) {
                        maxErr = e;
                        maxAt = t;
                    }
                }
                double med = median(errs);
                note("divisor " + res.divisor + ", frames " + frames + ", median error " + f3(med * 100) + " %, max "
                        + f3(maxErr * 100) + " % at " + f3(maxAt) + " s, octave errors " + octave);
                check(octave == 0, octave + " octave errors");
                check(med < 0.001, "median error " + med);
                check(maxErr < 0.005, "max error " + maxErr + " at " + maxAt + " s");
            }
        });

        l.add(new T("RS-T2 tracker: unknown cylinder count, V8 pattern with a weak order 1, not 2x") {
            void run() {
                Drive d = new Drive(6).seg(0, 6, 3000, 5000, ON, false);
                Levels v8 = new Levels() {
                    public double db(double n, double rpm, int state) {
                        int k = (int) n;
                        double base = k % 8 == 0 ? 0 : k % 4 == 0 ? -8 : k % 2 == 0 ? -14 : k == 1 ? -34 : -20;
                        return base - 14 - 5 * Math.log(Math.max(k, 8) / 8.0) / Math.log(2);
                    }
                };
                double[] x = engine(d, v8, new double[0], -60, 12);
                Tracker.Result res = Tracker.track(x, SR, 0);
                List<Double> ratio = new ArrayList<Double>();
                for (int k = 0; k < res.track.fc.length; k++) {
                    double t = res.track.time(k);
                    if (t < 0.2 || t > 5.8) continue;
                    ratio.add(120 * res.track.fc[k] / d.rpmAt(t));
                }
                double med = median(ratio);
                note("divisor " + res.divisor + ", median estimate / truth " + f3(med));
                check(Math.abs(med - 1) < 0.01, "median ratio " + med);
            }
        });

        l.add(new T("RS-C1 order spectra: +-0.5 dB in a 2500 rpm/s sweep, floor below -50 dB, order 4.5 at -20 dB") {
            void run() {
                Drive d = new Drive(2.6).seg(0, 0.5, 3000, 3000, ON, false).seg(0.5, 2.1, 3000, 7000, ON, false)
                        .seg(2.1, 2.6, 7000, 7000, ON, false);
                Levels lv = new Levels() {
                    public double db(double n, double rpm, int state) {
                        if (n == 4.5) return -20;
                        if (n != Math.rint(n) || n > 100) return Double.NaN;
                        return -10 - 25 * Math.log10(n) + (n % 4 == 0 ? 8 : 0);
                    }
                };
                Track tr = trueTrack(d);
                for (int pass = 0; pass < 2; pass++) {
                    boolean half = pass == 1;
                    double[] x = engine(d, lv, half ? new double[] {4.5} : new double[0], -300, 21);
                    Orders.Frames fr = Orders.analyse(x, SR, tr, 9000);
                    double worst = 0, floorMax = -999, halfMin = 999, halfMax = -999;
                    int checked = 0;
                    for (int k = 0; k < fr.count(); k++) {
                        if (fr.time[k] < 0.6 || fr.time[k] > 2.0) continue;
                        for (int n = 1; n <= 100; n++) {
                            double truth = lv.db(n, 0, ON);
                            double a = fr.amp[k][n];
                            if (truth > -40) {
                                worst = Math.max(worst, Math.abs(a - truth));
                                checked++;
                            }
                            if (n != 4) floorMax = Math.max(floorMax, fr.half[k][n]);
                        }
                        halfMin = Math.min(halfMin, fr.half[k][4]);
                        halfMax = Math.max(halfMax, fr.half[k][4]);
                    }
                    note((half ? "with" : "without") + " order 4.5: " + checked + " order levels, worst error " + f3(worst)
                            + " dB, highest half-order bin elsewhere " + f3(floorMax) + " dB, order 4.5 reads " + f3(halfMin)
                            + " .. " + f3(halfMax) + " dB");
                    check(checked > 1000, "too few orders checked: " + checked);
                    check(worst <= 0.5, "order level error " + worst + " dB");
                    check(floorMax < -50, "floor " + floorMax + " dB");
                    if (half) check(halfMin >= -21 && halfMax <= -19, "order 4.5 reads " + halfMin + " .. " + halfMax);
                    else check(halfMax < -50, "absent order 4.5 reads " + halfMax);
                }
            }
        });

        l.add(new T("RS-S1 segments: >= 95 % of frames labelled right on a synthetic run; the sidecar round-trips") {
            void run() throws Exception {
                Drive d = launch();
                double[] x = engine(d, comb(4), new double[0], -60, 31);
                Track tr = trueTrack(d);
                Segments sg = Segments.propose(x, SR, tr);
                int right = 0, all = 0;
                int[][] conf = new int[3][3];
                for (int k = 0; k < tr.frames(); k++) {
                    double t = tr.time(k);
                    int st = d.stateAt(t);
                    int truth = st == ON ? Segments.ON : st == OFF ? Segments.OFF : Segments.EXCLUDED;
                    if (d.nearStep(t, 0.2)) truth = Segments.EXCLUDED;
                    int got = sg.labelAt(t);
                    conf[truth][got]++;
                    if (got == truth) right++;
                    all++;
                }
                note("right " + right + " of " + all + " frames (" + f3(100.0 * right / all) + " %); truth x label "
                        + java.util.Arrays.deepToString(conf) + " (excluded, on, off)");
                check(right >= 0.95 * all, "only " + right + " of " + all + " frames right");
                File dir = tmpDir("s1");
                try {
                    File f = new File(dir, "run.segments.txt");
                    sg.write(f);
                    Segments back = Segments.read(f);
                    for (int k = 0; k < tr.frames(); k++)
                        check(back.labelAt(tr.time(k)) == sg.labelAt(tr.time(k)), "round trip differs at " + tr.time(k));
                    check(back.count() == sg.count(), "segment count " + back.count() + " vs " + sg.count());
                } finally {
                    rmrf(dir);
                }
            }
        });

        l.add(new T("RS-F1 fit recovers the fixture: in range per-order RMSE <= 1 dB; 800/1200/2000 rpm <= 2 dB") {
            void run() {
                Truth tr = new Truth(4, -1);
                Orders.Frames fr = fixtureFrames(tr, 3000, 7000, 300, 0.3, 1);
                Model.Layer m = Fitter.fit(fr, null, 4, new Fitter.Options());
                double worst = 0;
                int worstN = 0;
                for (int n = 1; n <= fr.maxOrder; n++) {
                    double se = 0;
                    int c = 0;
                    for (int k = 0; k < fr.count(); k++) {
                        if (Float.isNaN(fr.amp[k][n])) continue;
                        double e = m.predict(n, fr.rpm[k]) - tr.db(n, fr.rpm[k]);
                        se += e * e;
                        c++;
                    }
                    if (c > 0 && Math.sqrt(se / c) > worst) {
                        worst = Math.sqrt(se / c);
                        worstN = n;
                    }
                }
                note("in range: worst per-order RMSE " + f3(worst) + " dB (order " + worstN + ")");
                check(worst <= 1, "in-range RMSE " + worst + " at order " + worstN);
                for (double rpm : new double[] {800, 1200, 2000}) {
                    double e = rmseAt(m, tr, rpm, 120, 6000);
                    note(rpm + " rpm: RMSE " + f3(e) + " dB over orders in 120-6000 Hz");
                    check(e <= 2, rpm + " rpm RMSE " + e);
                }
            }
        });

        l.add(new T("RS-F2 gauge invariance: two gauge choices predict the same within 0.01 dB") {
            void run() {
                Truth tr = new Truth(4, -1);
                Orders.Frames fr = fixtureFrames(tr, 3000, 7000, 200, 0.3, 2);
                Fitter.Options a = new Fitter.Options(), b = new Fitter.Options();
                b.gauge = 1;
                Model.Layer ma = Fitter.fit(fr, null, 4, a), mb = Fitter.fit(fr, null, 4, b);
                double worst = 0;
                for (double rpm : new double[] {800, 1000, 2000, 3000, 4500, 7000, 7350})
                    for (int n = 1; n * rpm / 120 <= 8500; n++)
                        worst = Math.max(worst, Math.abs(ma.predict(n, rpm) - mb.predict(n, rpm)));
                note("largest difference " + worst + " dB");
                check(worst <= 0.01, "gauge changes predictions by " + worst + " dB");
            }
        });

        l.add(new T("RS-F3 the order model beats the held envelope (M0) at 800 rpm on a cylinder comb") {
            void run() {
                Truth tr = new Truth(4, -1);
                Orders.Frames fr = fixtureFrames(tr, 3000, 7000, 200, 0.3, 3);
                Model.Layer m1 = Fitter.fit(fr, null, 4, new Fitter.Options());
                Fitter.Baseline m0 = Fitter.fitM0(fr, null);
                double e1 = rmseAt(m1, tr, 800, 120, 6000), e0 = 0;
                int c = 0;
                for (int n = 1; n * 800 / 120.0 <= 6000; n++) {
                    if (n * 800 / 120.0 < 120) continue;
                    double e = m0.predict(n, 800) - tr.db(n, 800);
                    e0 += e * e;
                    c++;
                }
                e0 = Math.sqrt(e0 / c);
                note("800 rpm RMSE: model " + f3(e1) + " dB, held envelope " + f3(e0) + " dB");
                check(e1 < e0, "M1 " + e1 + " not better than M0 " + e0);
            }
        });

        l.add(new T("RS-F4 masked orders (within 10 dB of the floor) are not synthesized above the floor") {
            void run() {
                Truth tr = new Truth(4, 2); // orders 2 mod 4 sit 3 dB above the floor: masked
                Orders.Frames fr = fixtureFrames(tr, 3000, 7000, 200, 0.3, 4);
                Model.Layer m = Fitter.fit(fr, null, 4, new Fitter.Options());
                int bad = 0, checked = 0;
                for (double rpm : new double[] {800, 3000, 5000, 7000})
                    for (int n = 2; n * rpm / 120 <= 8500; n += 4) {
                        double p = m.predict(n, rpm);
                        checked++;
                        if (!Double.isNaN(p) && p > Truth.FLOOR) bad++;
                    }
                note(checked + " masked orders checked, " + bad + " synthesized above the floor");
                check(bad == 0, bad + " masked orders above the floor");
            }
        });

        l.add(new T("RS-F5 fit on the upper 60 % of log-rpm: holdout below it within 2 dB RMSE") {
            void run() {
                Truth tr = new Truth(4, -1);
                Orders.Frames all = fixtureFrames(tr, 800, 7000, 400, 0.3, 5);
                double split = Math.exp(Math.log(800) + 0.4 * Math.log(7000 / 800.0));
                boolean[] fit = new boolean[all.count()];
                for (int k = 0; k < fit.length; k++) fit[k] = all.rpm[k] >= split;
                Model.Layer m = Fitter.fit(all, fit, 4, new Fitter.Options());
                double se = 0;
                int c = 0;
                for (int k = 0; k < fit.length; k++) {
                    if (fit[k]) continue;
                    for (int n = 1; n <= all.maxOrder; n++) {
                        if (Float.isNaN(all.amp[k][n]) || n * all.rpm[k] / 120 < 40) continue;
                        double e = m.predict(n, all.rpm[k]) - tr.db(n, all.rpm[k]);
                        se += e * e;
                        c++;
                    }
                }
                double rmse = Math.sqrt(se / c);
                note("split at " + Math.round(split) + " rpm, holdout RMSE " + f3(rmse) + " dB over " + c + " order levels");
                check(rmse <= 2, "holdout RMSE " + rmse);
            }
        });

        l.add(new T("RS-F6 M0 below the measured rpm: the layer switches under lowBelow and round-trips through a .vesmodel") {
            void run() throws Exception {
                Model m = fixtureModel();
                Model.Layer own = fixtureLayer(0);
                m.on.low = fixtureLayer(1);
                m.on.lowBelow = 3000;
                for (int n = 1; n < 40; n++) {
                    check(m.on.predict(n, 2000) == m.on.low.predict(n, 2000), "order " + n + " at 2000 rpm not from the low layer");
                    check(m.on.predict(n, 4000) == own.predict(n, 4000), "order " + n + " at 4000 rpm not from the fit");
                }
                File d = tmpDir("f6");
                try {
                    File f = new File(d, "m.vesmodel");
                    m.write(f, "synthetic fixture");
                    Model b = Model.read(f);
                    check(b.on.low != null && b.on.lowBelow == 3000, "low layer lost in the file");
                    for (double rpm : new double[] {800, 2000, 2999, 3000, 5000})
                        for (int n = 1; n < 40; n++)
                            near(b.on.predict(n, rpm), m.on.predict(n, rpm), 1e-3, "order " + n + " at " + rpm + " after reading back");
                } finally {
                    rmrf(d);
                }
            }
        });

        l.add(new T("RS-F7 below the measured rpm the noise keeps its distance to the orders (no noise-only idle)") {
            void run() {
                Model.Layer l = fixtureLayer(0);
                l.rMin = 3000;
                l.noiseM = 0; // flat noise, orders falling 4 dB per ln(rpm)
                for (int b = 0; b < Orders.BANDS; b++) {
                    double d = (l.noise(b, 1500) - l.noise(b, 3000)) - (l.level(1500) - l.level(3000));
                    near(d, 0, 1e-9, "band " + b + ": noise moved against the orders by");
                    near(l.noise(b, 5000) - l.noise(b, 3000), 0, 1e-9, "band " + b + " in range follows m");
                }
                Model.Layer low = fixtureLayer(1);
                low.b = 12;
                l.low = low;
                l.lowBelow = 3000;
                double d = (l.noise(3, 1000) - l.noise(3, 3000)) - (low.level(1000) - low.level(3000));
                near(d, 0, 1e-9, "with M0 below: noise against M0's level trend");
            }
        });

        l.add(new T("RS-R1 pack: 42 loops, N * rpm = K * 2646000 (K even), 0.2-2 s, within 1.25M samples and 3 MB") {
            void run() throws Exception {
                File dir = fixturePack(true);
                ves.core.Pack p = loadPack(dir);
                int total = 0;
                long bytes = 0;
                for (ves.core.Pack.Loop[] layer : new ves.core.Pack.Loop[][] {p.on, p.off}) {
                    check(layer.length == PackRenderer.GRID.length, "loops per layer " + layer.length);
                    for (ves.core.Pack.Loop lp : layer) {
                        int n = lp.pcm.length;
                        long prod = (long) n * lp.rpm;
                        check(prod % PackRenderer.CYCLE == 0, lp.rpm + " rpm: N * rpm not a whole number of cycles");
                        long k = prod / PackRenderer.CYCLE;
                        check(k % 2 == 0, lp.rpm + " rpm: odd cycle count " + k);
                        check(n >= 4410 && n <= 44100, lp.rpm + " rpm: length " + n);
                        check(n >= 0.9 * 22050 && n <= 1.3 * 22050, lp.rpm + " rpm: " + n / 22050.0 + " s");
                        total += n;
                    }
                }
                File[] files = dir.listFiles();
                for (File f : files) {
                    bytes += f.length();
                    check(f.getName().equals("pack.txt") || f.getName().matches("[a-z0-9_-]{1,60}\\.wav"), "stray file " + f.getName());
                }
                check(files.length == 43, "files in the pack folder: " + files.length);
                note(total + " samples, " + bytes + " bytes");
                check(total <= ves.core.PackLoader.MAX_SAMPLES, "samples " + total);
                check(bytes <= 3 * 1024 * 1024, "bytes " + bytes);
            }
        });

        l.add(new T("RS-L1 the pack's level line puts its mean speaker-stage level at PackRenderer.TARGET_DB (+-0.3 dB), noise on, off, -12 dB") {
            void run() throws Exception {
                for (String noise : new String[] { "on", "off", "-12" }) {
                    File d = fixturePack(noise);
                    String txt = new String(readAll(new File(d, "pack.txt")), "UTF-8");
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^level (-?[0-9]+\\.[0-9])$").matcher(txt);
                    check(m.find(), "pack.txt has a level line with one decimal");
                    ves.core.Pack p = loadPack(d);
                    // the stage's linear part (high-pass, 400-2500 Hz lift, drive) at a level far below the soft clip
                    double sum = 0;
                    int n = 0;
                    for (double load : new double[] { 1, 0 }) {
                        for (int rpm : new int[] { 800, 1200, 1600, 2000, 2500, 3000, 4000, 5000, 6000 }) {
                            ves.core.PackVoice v = new ves.core.PackVoice(22050);
                            v.setPack(p);
                            float[] x = new float[22050];
                            v.renderRaw(x, x.length, rpm, load, 0);
                            ves.core.OutputStage st = new ves.core.OutputStage(22050);
                            double ss = 0;
                            for (int i = 0; i < x.length; i++) {
                                double y = st.process(x[i] * 0.001) / 0.001;
                                if (i >= 2205) ss += y * y;
                            }
                            sum += 10 * Math.log10(ss / (x.length - 2205));
                            n++;
                        }
                    }
                    double mean = sum / n;
                    note("noise " + noise + ": level " + m.group(1) + " dB, mean stage level " + f3(mean) + " dB");
                    check(Math.abs(mean - PackRenderer.TARGET_DB) <= 0.3, "noise " + noise + ": mean stage level " + mean
                            + " dB, target " + PackRenderer.TARGET_DB + ", level " + m.group(1));
                }
            }
        });

        l.add(new T("RS-R2 PackLoader.load accepts the pack: aligned, 21 on and 21 off loops, cylinders") {
            void run() throws Exception {
                ves.core.Pack p = loadPack(fixturePack(true));
                check(p.aligned, "aligned");
                check(p.on.length == 21 && p.off.length == 21, "on " + p.on.length + " off " + p.off.length);
                check(p.cylinders == 4 && p.idleRpm == 800 && p.redlineRpm == 7350, "cyl/idle/redline " + p.cylinders + "/"
                        + p.idleRpm + "/" + p.redlineRpm);
                check(p.on[0].rpm == 800 && p.on[20].rpm == 7350, "rpm range");
                check(p.name.equals("Fixture (resynth)"), "name " + p.name);
            }
        });

        l.add(new T("RS-R3 every loop wraps without a jump: |end - start| <= 1.2 x the largest in-loop step") {
            void run() throws Exception {
                ves.core.Pack p = loadPack(fixturePack(true));
                double worst = 0;
                for (ves.core.Pack.Loop[] layer : new ves.core.Pack.Loop[][] {p.on, p.off})
                    for (ves.core.Pack.Loop lp : layer) {
                        short[] s = lp.pcm;
                        int step = 0;
                        for (int i = 1; i < s.length; i++) step = Math.max(step, Math.abs(s[i] - s[i - 1]));
                        double r = Math.abs(s[0] - s[s.length - 1]) / (double) step;
                        worst = Math.max(worst, r);
                    }
                note("largest wrap jump / largest in-loop step " + f3(worst));
                check(worst <= 1.2, "wrap jump ratio " + worst);
            }
        });

        l.add(new T("RS-R4 order phases are the same in every loop and both layers (within 2 degrees)") {
            void run() throws Exception {
                ves.core.Pack p = loadPack(fixturePack(true));
                double worst = 0;
                int checked = 0;
                double[] ref = new double[2000];
                java.util.Arrays.fill(ref, Double.NaN);
                for (ves.core.Pack.Loop[] layer : new ves.core.Pack.Loop[][] {p.on, p.off})
                    for (ves.core.Pack.Loop lp : layer) {
                        double[][] sp = loopOrders(lp);
                        double top = 0;
                        for (int n = 1; n < sp[0].length; n++) top = Math.max(top, sp[0][n]);
                        for (int n = 1; n < sp[0].length; n++) {
                            if (sp[0][n] < top * 0.01) continue; // orders within 40 dB of the strongest
                            if (Double.isNaN(ref[n])) ref[n] = sp[1][n];
                            double d = Math.abs(Math.IEEEremainder(sp[1][n] - ref[n], 2 * Math.PI));
                            worst = Math.max(worst, Math.toDegrees(d));
                            checked++;
                        }
                    }
                note(checked + " order phases, worst " + f3(worst) + " degrees");
                check(worst <= 2, "phase differs by " + worst + " degrees");
            }
        });

        l.add(new T("RS-R5 PackVoice at a geometric midpoint: top 20 orders interpolate within 0.5 dB, RMS dip <= 1 dB (<= 3 with noise)") {
            void run() throws Exception {
                for (boolean noise : new boolean[] {false, true}) {
                    ves.core.Pack p = loadPack(fixturePack(noise));
                    double worstOrder = 0, worstDip = 0;
                    for (int i : new int[] {0, 7, 14, 19}) {
                        ves.core.Pack.Loop a = p.on[i], b = p.on[i + 1];
                        double mid = Math.sqrt((double) a.rpm * b.rpm);
                        double[] ga = gains(a), gb = gains(b);
                        ga[0] *= p.level; // the pack's level trim, as PackVoice.setPack applies it
                        gb[0] *= p.level;
                        double[] ra = renderAt(p, a.rpm), rb = renderAt(p, b.rpm), rm = renderAt(p, mid);
                        double dip = 20 * Math.log10(rms(rm) / Math.sqrt(0.5 * (rms(ra) * rms(ra) + rms(rb) * rms(rb))));
                        worstDip = Math.max(worstDip, -dip);
                        if (!noise) {
                            double[][] sa = loopOrders(a), sb = loopOrders(b);
                            double[] meas = constantOrders(rm, mid);
                            int no = Math.min(Math.min(sa[0].length, sb[0].length), meas.length);
                            double[] expect = new double[no];
                            for (int n = 1; n < no; n++) {
                                double re = 0.5 * (ga[0] * sa[0][n] * Math.cos(sa[1][n]) + gb[0] * sb[0][n] * Math.cos(sb[1][n]));
                                double im = 0.5 * (ga[0] * sa[0][n] * Math.sin(sa[1][n]) + gb[0] * sb[0][n] * Math.sin(sb[1][n]));
                                expect[n] = Math.hypot(re, im);
                            }
                            Integer[] idx = new Integer[no - 1];
                            for (int n = 1; n < no; n++) idx[n - 1] = n;
                            final double[] ex = expect;
                            java.util.Arrays.sort(idx, new java.util.Comparator<Integer>() {
                                public int compare(Integer x, Integer y) {
                                    return Double.compare(ex[y], ex[x]);
                                }
                            });
                            for (int q = 0; q < 20; q++) {
                                int n = idx[q];
                                worstOrder = Math.max(worstOrder, Math.abs(20 * Math.log10(meas[n] / expect[n])));
                            }
                        }
                    }
                    note((noise ? "with noise" : "harmonic only") + ": worst order error " + f3(worstOrder) + " dB, worst RMS dip "
                            + f3(worstDip) + " dB");
                    if (!noise) check(worstOrder <= 0.5, "order interpolation off by " + worstOrder + " dB");
                    check(worstDip <= (noise ? 3 : 1), "RMS dip " + worstDip + " dB");
                }
            }
        });

        l.add(new T("RS-R6 nothing above the cap (8.5 kHz) above -60 dBFS in any loop") {
            void run() throws Exception {
                ves.core.Pack p = loadPack(fixturePack(true));
                double worst = -999;
                for (ves.core.Pack.Loop[] layer : new ves.core.Pack.Loop[][] {p.on, p.off})
                    for (ves.core.Pack.Loop lp : layer) {
                        int n = lp.pcm.length;
                        double[] re = new double[n], im = new double[n];
                        for (int i = 0; i < n; i++) re[i] = lp.pcm[i] / 32768.0;
                        Fft.dft(re, im, false);
                        for (int k = (int) Math.ceil(PackRenderer.CAP * n / 22050.0) + 1; k <= n / 2; k++)
                            worst = Math.max(worst, 20 * Math.log10(2 * Math.hypot(re[k], im[k]) / n + 1e-30));
                    }
                note("highest bin above the cap " + f3(worst) + " dBFS");
                check(worst < -60, "content above the cap at " + worst + " dBFS");
            }
        });

        l.add(new T("RS-R7 two renders are byte-identical") {
            void run() throws Exception {
                File a = tmpDir("r7a"), b = tmpDir("r7b");
                try {
                    Model m = fixtureModel();
                    PackRenderer.write(new File(a, "p"), "Fixture (resynth)", m, null, new PackRenderer.Options());
                    PackRenderer.write(new File(b, "p"), "Fixture (resynth)", m, null, new PackRenderer.Options());
                    File[] fa = new File(a, "p").listFiles();
                    check(fa.length == 43, "files " + fa.length);
                    for (File f : fa)
                        check(java.util.Arrays.equals(readAll(f), readAll(new File(new File(b, "p"), f.getName()))), f.getName() + " differs");
                } finally {
                    rmrf(a);
                    rmrf(b);
                }
            }
        });

        l.add(new T("RS-N1 render noise=-12: noise-only bins 12 +-0.5 dB under noise=on, order bins within 0.1 dB (peak normalisation undone)") {
            void run() throws Exception {
                File root = tmpDir("n1");
                try {
                    File model = new File(root, "m.vesmodel");
                    fixtureModel().write(model, "synthetic fixture");
                    File on = new File(root, "on"), q = new File(root, "q");
                    check(Main.run(new String[] {"render", model.getPath(), on.getPath(), "name=Fixture", "noise=on"}) == 0, "noise=on render");
                    check(Main.run(new String[] {"render", model.getPath(), q.getPath(), "name=Fixture", "noise=-12"}) == 0, "noise=-12 render");
                    ves.core.Pack pa = loadPack(on), pb = loadPack(q);
                    double lo = 999, hi = -999, worstOrder = 0;
                    int orders = 0;
                    for (int layer = 0; layer < 2; layer++)
                        for (int i = 0; i < PackRenderer.GRID.length; i++) {
                            ves.core.Pack.Loop a = (layer == 0 ? pa.on : pa.off)[i], b = (layer == 0 ? pb.on : pb.off)[i];
                            int n = a.pcm.length;
                            check(b.pcm.length == n, a.rpm + " rpm: lengths differ");
                            // order n on bin K n, half orders on K n + K / 2, the skirt every K / WOBBLE bins
                            // beside the orders: every other bin holds noise only
                            int k = (int) ((long) n * a.rpm / PackRenderer.CYCLE), side = k / PackRenderer.WOBBLE;
                            double[] sa = power(a.pcm), sb = power(b.pcm);
                            int top = k;
                            for (int bin = k; bin < n / 2; bin += k) if (sa[bin] > sa[top]) top = bin;
                            // each loop is normalised to its own peak: the strongest order's ratio is the gain ratio
                            double ref = sb[top] / sa[top], na = 0, nb = 0;
                            for (int bin = 1; bin < n / 2 && bin * (double) PackRenderer.SR / n <= PackRenderer.CAP; bin++) {
                                if (bin % side != 0) {
                                    na += sa[bin];
                                    nb += sb[bin];
                                } else if (bin % k == 0 && sa[bin] > sa[top] * 1e-4) {
                                    worstOrder = Math.max(worstOrder, Math.abs(10 * Math.log10(sb[bin] / sa[bin] / ref)));
                                    orders++;
                                }
                            }
                            double d = 10 * Math.log10(nb / na / ref);
                            lo = Math.min(lo, d);
                            hi = Math.max(hi, d);
                        }
                    note("noise-only bins " + f3(lo) + " .. " + f3(hi) + " dB re noise=on; " + orders + " order bins, worst " + f3(worstOrder) + " dB");
                    near(lo, -12, 0.5, "quietest loop's noise re noise=on");
                    near(hi, -12, 0.5, "loudest loop's noise re noise=on");
                    check(worstOrder <= 0.1, "order bins differ by " + worstOrder + " dB");
                } finally {
                    rmrf(root);
                }
            }
        });

        l.add(new T("RS-N2 render noise=0 is byte-identical to noise=on") {
            void run() throws Exception {
                File root = tmpDir("n2");
                try {
                    File model = new File(root, "m.vesmodel");
                    fixtureModel().write(model, "synthetic fixture");
                    File a = new File(root, "on"), b = new File(root, "zero");
                    check(Main.run(new String[] {"render", model.getPath(), a.getPath(), "name=Fixture", "noise=on"}) == 0, "noise=on render");
                    check(Main.run(new String[] {"render", model.getPath(), b.getPath(), "name=Fixture", "noise=0"}) == 0, "noise=0 render");
                    File[] fa = a.listFiles();
                    check(fa.length == 43 && b.list().length == 43, "files " + fa.length + " / " + b.list().length);
                    for (File f : fa) check(java.util.Arrays.equals(readAll(f), readAll(new File(b, f.getName()))), f.getName() + " differs");
                } finally {
                    rmrf(root);
                }
            }
        });

        l.add(new T("RS-N3 render noise=+3, x, -50, -12d: exit 1 with the usage, nothing written") {
            void run() throws Exception {
                File root = tmpDir("n3");
                try {
                    File model = new File(root, "m.vesmodel");
                    fixtureModel().write(model, "synthetic fixture");
                    for (String v : new String[] {"+3", "x", "-50", "-12d", "", "-40.5"}) {
                        File out = new File(root, "pack");
                        String[] err = new String[1];
                        int rc = runCapturing(new String[] {"render", model.getPath(), out.getPath(), "name=Fixture", "noise=" + v}, err);
                        check(rc == 1, "noise=" + v + ": exit " + rc);
                        check(err[0].contains("usage:") && err[0].contains("noise="), "noise=" + v + ": stderr " + err[0]);
                        check(!out.exists(), "noise=" + v + ": wrote " + out);
                    }
                    for (String v : new String[] {"-40", "-12.5"}) {
                        File out = new File(root, "ok" + v);
                        check(Main.run(new String[] {"render", model.getPath(), out.getPath(), "name=Fixture", "noise=" + v}) == 0, "noise=" + v);
                    }
                } finally {
                    rmrf(root);
                }
            }
        });

        l.add(new T("RS-N4 a loop refused for its crest factor: exit 1 with the reason, the folder's earlier pack untouched") {
            void run() throws Exception {
                File root = tmpDir("n4");
                try {
                    // flat orders all in phase (a pulse train), no skirt: crest factor sqrt(2 x 1270) at 800 rpm
                    Model m = fixtureModel();
                    for (Model.Layer ly : new Model.Layer[] {m.on, m.off}) {
                        ly.h = new double[0];
                        ly.tailB = 0;
                        ly.tailRes = new double[] {0, 0, 0, 0};
                        java.util.Arrays.fill(ly.coh, 0);
                    }
                    m.phase = new double[1300];
                    m.phaseR = new double[1300];
                    java.util.Arrays.fill(m.phaseR, 1);
                    File model = new File(root, "pulse.vesmodel"), good = new File(root, "m.vesmodel");
                    m.write(model, "synthetic pulse train");
                    fixtureModel().write(good, "synthetic fixture");
                    File fresh = new File(root, "fresh"), used = new File(root, "used");
                    check(Main.run(new String[] {"render", good.getPath(), used.getPath(), "name=Fixture"}) == 0, "earlier pack");
                    java.util.Map<String, byte[]> before = new java.util.TreeMap<String, byte[]>();
                    for (File f : used.listFiles()) before.put(f.getName(), readAll(f));
                    for (File out : new File[] {fresh, used}) {
                        String[] err = new String[1];
                        int rc = runCapturing(new String[] {"render", model.getPath(), out.getPath(), "name=Pulse", "noise=off"}, err);
                        check(rc == 1, out.getName() + ": exit " + rc);
                        check(err[0].startsWith("error: ") && err[0].contains("crest factor"), out.getName() + ": stderr " + err[0]);
                        note(err[0].trim());
                    }
                    check(!fresh.exists() || fresh.list().length == 0, "half-written pack: " + java.util.Arrays.toString(fresh.list()));
                    check(used.list().length == before.size(), "the earlier pack lost files: " + java.util.Arrays.toString(used.list()));
                    for (java.util.Map.Entry<String, byte[]> e : before.entrySet())
                        check(java.util.Arrays.equals(e.getValue(), readAll(new File(used, e.getKey()))), e.getKey() + " changed");
                } finally {
                    rmrf(root);
                }
            }
        });

        l.add(new T("RS-N5 ab track's resynthesis at noise -12 dB: the noise 12 dB down, noise 0 dB unchanged") {
            void run() {
                Model m = fixtureModel();
                Track tr = trueTrack(launch());
                int n = 2 * SR;
                double[] ref = TrackSynth.render(m, null, tr, null, n, SR, 99);
                double[] y0 = TrackSynth.render(m, null, tr, null, n, SR, 99, 0);
                double[] y12 = TrackSynth.render(m, null, tr, null, n, SR, 99, -12);
                double[] yOff = TrackSynth.render(m, null, tr, null, n, SR, 99, Double.NEGATIVE_INFINITY);
                check(java.util.Arrays.equals(ref, y0), "noise 0 dB differs from the default");
                double e0 = 0, e12 = 0;
                for (int i = 0; i < n; i++) {
                    e0 += (y0[i] - yOff[i]) * (y0[i] - yOff[i]);
                    e12 += (y12[i] - yOff[i]) * (y12[i] - yOff[i]);
                }
                check(e0 > 0, "no noise at 0 dB");
                double d = 10 * Math.log10(e12 / e0);
                note("noise at -12: " + f3(d) + " dB");
                near(d, -12, 0.01, "noise level");
            }
        });

        l.add(new T("RS-R8 loop levels and crest factor within a built-in pack's + 1 dB") {
            void run() throws Exception {
                String bp = System.getProperty("resynth.builtin");
                File bd = bp == null ? null : new File(bp);
                if (bd == null || !new File(bd, "pack.txt").isFile()) {
                    note("SKIP: no built-in pack rendered (" + bp + ")");
                    return;
                }
                double[] mine = levels(loadPack(fixturePack(true))), theirs = levels(loadPack(bd));
                note("resynth: RMS " + f3(mine[0]) + ".." + f3(mine[1]) + " dBFS, crest up to " + f3(mine[2]) + " dB; built-in: RMS "
                        + f3(theirs[0]) + ".." + f3(theirs[1]) + " dBFS, crest up to " + f3(theirs[2]) + " dB");
                check(mine[2] <= theirs[2] + 1, "crest " + mine[2] + " above built-in " + theirs[2] + " + 1 dB");
                check(mine[0] >= theirs[0] - 1, "quietest loop " + mine[0] + " below built-in " + theirs[0] + " - 1 dB");
                check(mine[1] <= -1, "loudest loop RMS " + mine[1]);
            }
        });

        l.add(new T("RS-R9 10 s 800-7350 rpm sweep through PackVoice: steps <= 1.3 x the steepest loop, 100 ms level spread <= 1.5 dB") {
            void run() throws Exception {
                ves.core.Pack p = loadPack(fixturePack(true));
                double steep = 0;
                double[] g = new ves.core.PackVoiceProbe().gains(p.on);
                for (int k = 0; k < p.on.length; k++) {
                    short[] s = p.on[k].pcm;
                    int st = 0;
                    for (int i = 1; i < s.length; i++) st = Math.max(st, Math.abs(s[i] - s[i - 1]));
                    st = Math.max(st, Math.abs(s[0] - s[s.length - 1]));
                    steep = Math.max(steep, g[k] * st / 32768.0);
                }
                ves.core.PackVoice v = new ves.core.PackVoice(22050);
                v.setPack(p);
                int total = 10 * 22050;
                float[] y = new float[total];
                float[] blk = new float[16];
                for (int i = 0; i < total; i += 16) {
                    double rpm = 800 * Math.pow(7350 / 800.0, i / (double) total);
                    v.renderRaw(blk, 16, rpm, 1, 0);
                    System.arraycopy(blk, 0, y, i, Math.min(16, total - i));
                }
                double maxStep = 0;
                for (int i = 1; i < total; i++) maxStep = Math.max(maxStep, Math.abs(y[i] - y[i - 1]));
                int win = 2205;
                double lo = 999, hi = -999;
                for (int w = 1; w + 1 < total / win; w++) { // skip the first window (start-up)
                    double ss = 0;
                    for (int i = w * win; i < (w + 1) * win; i++) ss += y[i] * y[i];
                    double dbv = 10 * Math.log10(ss / win);
                    lo = Math.min(lo, dbv);
                    hi = Math.max(hi, dbv);
                }
                note("max step " + f3(maxStep) + " vs steepest loop " + f3(steep) + " (x" + f3(maxStep / steep) + "), 100 ms levels "
                        + f3(lo) + ".." + f3(hi) + " dB");
                check(maxStep <= 1.3 * steep, "step " + maxStep + " > 1.3 x " + steep);
                check(hi - lo <= 1.5, "level spread " + (hi - lo) + " dB");
            }
        });

        l.add(new T("RS-R10 a strong crank-wobble skirt (0.3) is phase modulation: the sweep's 100 ms level spread stays <= 1.5 dB") {
            void run() throws Exception {
                Model m = fixtureModel();
                for (Model.Layer ly : new Model.Layer[] {m.on, m.off}) java.util.Arrays.fill(ly.coh, 0.3);
                File root = tmpDir("r10");
                try {
                    PackRenderer.Options o = new PackRenderer.Options();
                    o.noise = false;
                    PackRenderer.write(new File(root, "p"), "Wobble (resynth)", m, null, o);
                    ves.core.Pack p = loadPack(new File(root, "p"));
                    ves.core.PackVoice v = new ves.core.PackVoice(22050);
                    v.setPack(p);
                    int total = 10 * 22050;
                    float[] y = new float[total], blk = new float[16];
                    for (int i = 0; i < total; i += 16) {
                        v.renderRaw(blk, 16, 800 * Math.pow(7350 / 800.0, i / (double) total), 1, 0);
                        System.arraycopy(blk, 0, y, i, Math.min(16, total - i));
                    }
                    double lo = 999, hi = -999;
                    for (int w = 1; w + 1 < total / 2205; w++) {
                        double ss = 0;
                        for (int i = w * 2205; i < (w + 1) * 2205; i++) ss += y[i] * y[i];
                        double dbv = 10 * Math.log10(ss / 2205);
                        lo = Math.min(lo, dbv);
                        hi = Math.max(hi, dbv);
                    }
                    note("100 ms levels " + f3(lo) + ".." + f3(hi) + " dB");
                    check(hi - lo <= 1.5, "level spread " + (hi - lo) + " dB");
                } finally {
                    rmrf(root);
                }
            }
        });

        l.add(new T("RS-P1 the pack writer refuses a folder inside a git worktree (exit 2)") {
            void run() throws Exception {
                File root = tmpDir("p1");
                try {
                    File model = new File(root, "m.vesmodel");
                    fixtureModel().write(model, "synthetic fixture");
                    for (boolean asFile : new boolean[] {false, true}) {
                        File repo = new File(root, asFile ? "wt" : "repo");
                        repo.mkdirs();
                        File git = new File(repo, ".git");
                        if (asFile) Files.writeText(git, "gitdir: elsewhere\n");
                        else git.mkdirs();
                        File out = new File(repo, "deep/pack");
                        int rc = Main.run(new String[] {"render", model.getPath(), out.getPath(), "name=Fixture"});
                        check(rc == 2, (asFile ? ".git file" : ".git folder") + ": exit " + rc);
                        check(!out.exists(), "wrote into " + out);
                    }
                    File ok = new File(root, "free/pack");
                    int rc = Main.run(new String[] {"render", model.getPath(), ok.getPath(), "name=Fixture"});
                    check(rc == 0 && new File(ok, "pack.txt").isFile(), "outside git: exit " + rc);
                } finally {
                    rmrf(root);
                }
            }
        });

        l.add(new T("RS-B1 Json: a valid manifest-like text parses; malformed ones are an IOException") {
            void run() throws Exception {
                Object o = Json.parse(" {\"a\": [1, 2.5, -3e2], \"b\": \"x\\\"y\\u0041\\n\", \"c\": {\"d\": true, \"e\": null, \"f\": false}, \"g\": []} ");
                check(o instanceof java.util.Map, "object");
                java.util.Map<?, ?> m = (java.util.Map<?, ?>) o;
                check(java.util.Arrays.asList(1.0, 2.5, -300.0).equals(m.get("a")), "numbers " + m.get("a"));
                check("x\"yA\n".equals(m.get("b")), "string " + m.get("b"));
                java.util.Map<?, ?> c = (java.util.Map<?, ?>) m.get("c");
                check(Boolean.TRUE.equals(c.get("d")) && c.containsKey("e") && c.get("e") == null && Boolean.FALSE.equals(c.get("f")), "literals " + c);
                check(((java.util.List<?>) m.get("g")).isEmpty(), "empty array");
                for (String bad : new String[] {"", "{\"a\":1", "{\"a\":1,}", "[1 2]", "{\"a\":01}", "\"abc", "{\"a\":1} x", "nul", "{a:1}",
                        "[1.]", "[\"\\x\"]", "[-]"}) {
                    try {
                        Json.parse(bad);
                        check(false, "accepted: " + bad);
                    } catch (IOException e) {
                        check(e.getMessage() != null, bad);
                    }
                }
            }
        });

        l.add(new T("RS-B2 bake: PackLoader accepts 3 layers x 3 loops at round(ref_rpm), length N, seamless, crank phase kept, nothing above 9.5 kHz") {
            void run() throws Exception {
                File root = tmpDir("b2");
                try {
                    File out = new File(root, "pack");
                    int rc = Main.run(new String[] {"bake", bakeFixture(new File(root, "bake"), null).getPath(), out.getPath(), "name=Bake Fixture"});
                    check(rc == 0, "bake exit " + rc);
                    ves.core.Pack p = loadPack(out);
                    check(p.aligned && p.layers.length == 3 && java.util.Arrays.equals(p.loads, new double[] {0, 0.45, 1}),
                            "aligned, loads " + java.util.Arrays.toString(p.loads));
                    check(p.cylinders == 4 && p.redlineRpm == 6700 && p.popRpm == 4000 && p.idleRpm == 800, "header");
                    int loops = 0;
                    for (int layer = 0; layer < 3; layer++) {
                        for (int k = 0; k < BAKE_REF.length; k++) {
                            ves.core.Pack.Loop lp = p.layers[layer][k];
                            loops++;
                            int r = (int) Math.round(BAKE_REF[k]), n = (int) ((long) BAKE_CYC[k] * 2646000 / r);
                            check(lp.rpm == r && lp.pcm.length == n, "loop " + layer + "/" + k + ": rpm " + lp.rpm + " length " + lp.pcm.length + " vs " + r + " " + n);
                            double maxStep = 0;
                            for (int i = 1; i < n; i++) maxStep = Math.max(maxStep, Math.abs(lp.pcm[i] - lp.pcm[i - 1]));
                            double wrap = Math.abs(lp.pcm[0] - lp.pcm[n - 1]);
                            check(wrap <= 1.2 * maxStep, "wrap " + wrap + " vs in-loop " + maxStep);
                            double[] re = new double[n], im = new double[n];
                            for (int i = 0; i < n; i++) re[i] = lp.pcm[i] / 32768.0;
                            Fft.dft(re, im, false);
                            int c = BAKE_CYC[k], best = 1;
                            double top = 0, above = 0;
                            for (int b = 1; b < n / 2; b++) {
                                double pw = re[b] * re[b] + im[b] * im[b];
                                if (pw > top) {
                                    top = pw;
                                    best = b;
                                }
                                if (b * 22050.0 / n > 9500) above = Math.max(above, pw);
                            }
                            check(best == c, "order 1 on bin " + c + ", the peak is on " + best);
                            double ph = Math.atan2(im[c], re[c]), d = Math.abs(Math.IEEEremainder(ph - BAKE_PHASE1, 2 * Math.PI));
                            check(d <= 0.05, "order-1 phase at frame 0 " + ph + " vs " + BAKE_PHASE1);
                            check(10 * Math.log10(above / top) <= -60, "above 9.5 kHz " + 10 * Math.log10(above / top) + " dB");
                        }
                    }
                    check(loops == 9, "3 x 3 loops");
                } finally {
                    rmrf(root);
                }
            }
        });

        l.add(new T("RS-B3 bake: the level line puts the pack's speaker-stage level at TARGET_DB (+-0.15 dB)") {
            void run() throws Exception {
                File root = tmpDir("b3");
                try {
                    File out = new File(root, "pack");
                    check(Main.run(new String[] {"bake", bakeFixture(new File(root, "bake"), null).getPath(), out.getPath(), "name=Bake"}) == 0, "bake");
                    String txt = new String(readAll(new File(out, "pack.txt")), "UTF-8");
                    check(txt.startsWith("# converted by ves.resynth bake (private: never commit)\n"), "header line: " + txt.substring(0, 60));
                    check(txt.matches("(?s).*\nlevel -?[0-9]+\\.[0-9]\naligned yes\n.*"), "level line before aligned yes");
                    double db = PackRenderer.stageLevelDb(loadPack(out));
                    note("stage level " + f3(db) + " dB");
                    near(db, PackRenderer.TARGET_DB, 0.15, "trimmed level");
                } finally {
                    rmrf(root);
                }
            }
        });

        l.add(new T("RS-B4 bake: events at 22.05 kHz, floor(frames/2) +-1 samples, peak within 0.5 dB") {
            void run() throws Exception {
                File root = tmpDir("b4");
                try {
                    File out = new File(root, "pack");
                    check(Main.run(new String[] {"bake", bakeFixture(new File(root, "bake"), null).getPath(), out.getPath(), "name=Bake"}) == 0, "bake");
                    ves.core.Pack p = loadPack(out);
                    int[] kinds = {ves.core.Pack.POP, ves.core.Pack.POP, ves.core.Pack.BUBBLE, ves.core.Pack.SHIFT_UP, ves.core.Pack.SHIFT_DOWN};
                    int[] index = {0, 1, 0, 0, 0};
                    for (int e = 0; e < kinds.length; e++) {
                        short[] s = p.events[kinds[e]][index[e]];
                        check(Math.abs(s.length - BAKE_EV_FRAMES[e] / 2) <= 1, "event " + e + ": " + s.length + " samples for " + BAKE_EV_FRAMES[e]);
                        double pk = 0;
                        for (short v : s) pk = Math.max(pk, Math.abs(v) / 32768.0);
                        double src = 0;
                        for (double v : bakeEvent(e)) src = Math.max(src, Math.abs(v));
                        near(20 * Math.log10(pk / src), 0, 0.5, "event " + e + " peak");
                    }
                } finally {
                    rmrf(root);
                }
            }
        });

        l.add(new T("RS-B5 bake refuses phase_zero != 0, fractional cycles, a frame mismatch (exit 1, nothing written) and a git worktree (exit 2)") {
            void run() throws Exception {
                File root = tmpDir("b5");
                try {
                    for (String bad : new String[] {"phase", "cycles", "frames"}) {
                        File out = new File(root, "out-" + bad);
                        String[] err = new String[1];
                        int rc = runCapturing(new String[] {"bake", bakeFixture(new File(root, "bake-" + bad), bad).getPath(), out.getPath(), "name=Bad"}, err);
                        check(rc == 1, bad + ": exit " + rc);
                        check(!out.exists(), bad + ": wrote " + out);
                        note(bad + ": " + err[0].trim());
                    }
                    File repo = new File(root, "repo");
                    new File(repo, ".git").mkdirs();
                    File out = new File(repo, "pack");
                    int rc = Main.run(new String[] {"bake", bakeFixture(new File(root, "bake-ok"), null).getPath(), out.getPath(), "name=Ok"});
                    check(rc == 2 && !out.exists(), "inside a worktree: exit " + rc);
                } finally {
                    rmrf(root);
                }
            }
        });

        l.add(new T("RS-B6 two bakes are byte-identical") {
            void run() throws Exception {
                File root = tmpDir("b6");
                try {
                    File m = bakeFixture(new File(root, "bake"), null), a = new File(root, "a"), b = new File(root, "b");
                    check(Main.run(new String[] {"bake", m.getPath(), a.getPath(), "name=Same"}) == 0, "first");
                    check(Main.run(new String[] {"bake", m.getPath(), b.getPath(), "name=Same"}) == 0, "second");
                    String[] names = a.list();
                    java.util.Arrays.sort(names);
                    check(names.length == 15, "pack.txt, 9 loops, 5 events: " + names.length);
                    for (String n : names) check(java.util.Arrays.equals(readAll(new File(a, n)), readAll(new File(b, n))), n + " differs");
                } finally {
                    rmrf(root);
                }
            }
        });

        l.add(new T("RS-A1 1/3-octave distance: identical input 0 dB, a +3 dB shelf 3 +- 0.2 dB") {
            void run() {
                Random r = new Random(41);
                int n = 3 * SR;
                double[] a = new double[n];
                for (int i = 0; i < n; i++)
                    a[i] = 0.05 * r.nextGaussian() + 0.2 * Math.sin(2 * Math.PI * 440 * i / SR) + 0.1 * Math.sin(2 * Math.PI * 2500 * i / SR);
                int m = Fft.nextPow2(n);
                double[] re = new double[m], im = new double[m];
                System.arraycopy(a, 0, re, 0, n);
                Fft.fft(re, im, false);
                double g = Math.pow(10, 3 / 20.0);
                for (int k = 0; k < m; k++) {
                    double f = Math.min(k, m - k) * SR / (double) m;
                    if (f >= 80) {
                        re[k] *= g;
                        im[k] *= g;
                    }
                }
                Fft.fft(re, im, true);
                double[] b = new double[n];
                for (int i = 0; i < n; i++) b[i] = re[i] / m;
                double d0 = ThirdOctave.distance(a, a, SR, null), d3 = ThirdOctave.distance(a, b, SR, null);
                note("identical " + d0 + " dB, shelf " + f3(d3) + " dB");
                check(d0 == 0, "identical input gives " + d0);
                near(d3, 3, 0.2, "shelf distance");
            }
        });

        l.add(new T("RS-A2 track A/B: the fixture model driven along its own track, mean distance <= 0.5 dB on and off") {
            void run() {
                final Model m = fixtureModel();
                double noiseDb = -60, psd = 10 * Math.log10(2 * Math.pow(10, noiseDb / 10) / SR);
                for (Model.Layer ly : new Model.Layer[] {m.on, m.off}) {
                    java.util.Arrays.fill(ly.noiseQ, psd);
                    ly.noiseM = 0;
                    java.util.Arrays.fill(ly.coh, 0);
                }
                Drive d = launch();
                Levels lv = new Levels() {
                    public double db(double n, double rpm, int state) {
                        return (state == OFF ? m.off : m.on).predict(n, rpm);
                    }
                };
                double[] x = engine(d, lv, new double[0], noiseDb, 51);
                Track tr = trueTrack(d);
                Segments sg = new Segments();
                sg.add(1.7, 3.0, Segments.ON);
                sg.add(3.5, 6.3, Segments.ON);
                sg.add(6.9, 9.8, Segments.ON);
                sg.add(10.3, 14, Segments.OFF);
                double[] y = TrackSynth.render(m, null, tr, sg, x.length, SR, 99);
                double on = ThirdOctave.distance(x, y, SR, mask(sg, Segments.ON, x.length));
                double off = ThirdOctave.distance(x, y, SR, mask(sg, Segments.OFF, x.length));
                note("mean 1/3-octave distance: on " + f3(on) + " dB, off " + f3(off) + " dB");
                check(on <= 0.5 && off <= 0.5, "distance on " + on + " off " + off);
            }
        });

        l.add(new T("RS-P1b the A/B, demo, track, segments and fit writers refuse a git worktree too (exit 2)") {
            void run() throws Exception {
                File root = tmpDir("p1b");
                try {
                    File repo = new File(root, "repo");
                    new File(repo, ".git").mkdirs();
                    String in = new File(root, "missing.wav").getPath(), o = new File(repo, "out").getPath();
                    String[][] cmds = {{"ab", "track", in, in, in, in, o, "n"}, {"demo", in, o, "n"}, {"track", in, o + ".track.txt"},
                            {"segments", in, in, o + ".segments.txt"}, {"fit", in, in, in, o + ".vesmodel"}};
                    for (String[] c : cmds) {
                        int rc = Main.run(c);
                        check(rc == 2, c[0] + (c[0].equals("ab") ? " " + c[1] : "") + ": exit " + rc);
                    }
                    check(repo.list().length == 1, "something was written: " + java.util.Arrays.toString(repo.list()));
                } finally {
                    rmrf(root);
                }
            }
        });

        return l;
    }

    /** per 100 ms A/B frame: is its centre in a segment with this label */
    static boolean[] mask(Segments sg, int label, int samples) {
        boolean[] b = new boolean[samples / ThirdOctave.FRAME];
        for (int k = 0; k < b.length; k++) b[k] = sg.labelAt((k + 0.5) * ThirdOctave.FRAME / (double) SR) == label;
        return b;
    }

    // ------------------------------------------------------------------ pack fixtures

    /** a synthetic 4-cylinder model: comb tail, two resonances, noise 30 dB under the orders, a small skirt */
    static Model fixtureModel() {
        Model m = new Model();
        m.cyl = 4;
        m.seed = 7;
        m.on = fixtureLayer(0);
        m.off = fixtureLayer(1);
        return m;
    }

    static Model.Layer fixtureLayer(int off) {
        Model.Layer l = new Model.Layer();
        l.cyl = 4;
        l.rMin = 3000;
        l.rMax = 7000;
        l.rRef = 4500;
        l.a = off == 1 ? -32 : -22;
        l.b = 4;
        l.nObs = 0;
        l.tailA = 0;
        l.tailB = off == 1 ? -9 : -6;
        l.tailRes = new double[] {6, -6, -2, -6};
        l.fLo = 0;
        l.step = 25;
        l.h = new double[341];
        for (int j = 0; j < l.h.length; j++) l.h[j] = Truth.h(Math.max(25, j * 25));
        for (int b = 0; b < Orders.BANDS; b++) {
            l.noiseQ[b] = -95 - 0.5 * b - 10 * off;
            l.coh[b] = 0.02;
        }
        l.noiseM = 6;
        return l;
    }

    static final java.util.Map<String, File> PACKS = new java.util.HashMap<String, File>();

    /** the fixture model rendered once per run (with or without noise), in a temp folder */
    static File fixturePack(boolean noise) throws Exception {
        return fixturePack(noise ? "on" : "off");
    }

    /** the same with noise on, off or at a level in dB (as render's noise= takes it) */
    static File fixturePack(String noise) throws Exception {
        File d = PACKS.get(noise);
        if (d != null) return d;
        File root = tmpDir("pack" + noise);
        root.deleteOnExit();
        d = new File(root, "fixture");
        PackRenderer.Options o = new PackRenderer.Options();
        o.noise = !noise.equals("off");
        if (!noise.equals("on") && !noise.equals("off")) o.noiseDb = Double.parseDouble(noise);
        PackRenderer.write(d, "Fixture (resynth)", fixtureModel(), null, o);
        PACKS.put(noise, d);
        return d;
    }

    static byte[] readAll(File f) throws IOException {
        java.io.InputStream in = new java.io.FileInputStream(f);
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            int r;
            while ((r = in.read(b)) > 0) o.write(b, 0, r);
            return o.toByteArray();
        } finally {
            in.close();
        }
    }

    static ves.core.Pack loadPack(final File dir) throws Exception {
        return ves.core.PackLoader.load(new ves.core.PackLoader.Source() {
            public byte[] read(String name, int maxBytes) throws IOException {
                File f = new File(dir, name);
                if (f.length() > maxBytes) throw new IOException(name + " too large");
                return readAll(f);
            }
        });
    }

    // ------------------------------------------------------------------ physics bake fixture (ves.resynth bake)

    static final double[] BAKE_REF = {887.60033545, 2000.3, 4500.7};
    static final int[] BAKE_CYC = {7, 16, 36};
    static final double BAKE_PHASE1 = 0.7;
    static final String[] BAKE_EV = {"decel_pop_0", "decel_pop_1", "decel_bubble_0", "shift_up", "shift_down"};
    static final int[] BAKE_EV_FRAMES = {4851, 4410, 7056, 7056, 11466};
    static final double[] BAKE_EV_PEAK = {0.9, 0.7, 0.5, 0.35, 0.32};

    /** frames of a loop at 44.1 kHz, as the baker rounds them */
    static int bakeFrames(int k) {
        return (int) Math.round(BAKE_CYC[k] * 120 * 44100 / BAKE_REF[k]);
    }

    /**
     * one loop at 44.1 kHz, periodic in its frames: orders 1-6 (order 1 strongest, phase 0.7), every 4th order 8-64
     * (the speaker's band) and a 10 kHz tone
     */
    static double[] bakeLoop(int k, int layer) {
        int fr = bakeFrames(k), c = BAKE_CYC[k];
        double g = 0.4 + 0.2 * layer;
        int b10 = (int) Math.round(10000.0 * fr / 44100);
        double[] x = new double[fr];
        for (int i = 0; i < fr; i++) {
            double t = (double) i / fr, v = 0.05 * Math.cos(2 * Math.PI * b10 * t);
            for (int n = 1; n <= 6; n++) v += (n == 1 ? 0.3 : 0.1 / n) * Math.cos(2 * Math.PI * n * c * t + (n == 1 ? BAKE_PHASE1 : 0.3 * n));
            for (int n = 8; n <= 64; n += 4) v += 0.03 * Math.cos(2 * Math.PI * n * c * t + 0.3 * n);
            x[i] = g * v;
        }
        return x;
    }

    /** event e at 44.1 kHz: a 2 ms attack, then a decaying 500 Hz tone */
    static double[] bakeEvent(int e) {
        double[] x = new double[BAKE_EV_FRAMES[e]];
        for (int i = 0; i < x.length; i++)
            x[i] = BAKE_EV_PEAK[e] * Math.min(1, i / 88.0) * Math.exp(-i / (0.03 * 44100)) * Math.cos(2 * Math.PI * 500 * (i - 88) / 44100);
        return x;
    }

    /**
     * writes a physics bake into dir (manifest.json, spec.txt, wav/): 3 rpm x off/partial/high, 5 events; mutate
     * "phase", "cycles" or "frames" breaks the first loop's entry. Returns the manifest.
     */
    static File bakeFixture(File dir, String mutate) throws IOException {
        File wav = new File(dir, "wav");
        if (!wav.mkdirs()) throw new IOException("cannot make " + wav);
        String[] loadName = {"off", "partial", "high"};
        double[] loadVal = {0, 0.45, 1};
        StringBuilder j = new StringBuilder("{\n \"name\": \"baked\",\n \"source\": \"physics\",\n \"sample_rate\": 44100,\n \"channels\": 1,\n"
                + " \"sample_format\": \"pcm16\",\n \"load_axis\": [0.0, 0.45, 1.0],\n \"clips\": [\n");
        boolean first = true;
        for (int k = 0; k < BAKE_REF.length; k++)
            for (int ly = 0; ly < 3; ly++) {
                String f = String.format(java.util.Locale.ROOT, "wav/loop_r%02d_%s.wav", k, loadName[ly]);
                WavIO.write(new File(dir, f), bakeLoop(k, ly), 44100);
                int fr = bakeFrames(k);
                boolean m = first && mutate != null;
                j.append(first ? "" : ",\n").append("  {\"type\": \"loop\", \"file\": \"").append(f).append("\", \"rpm_index\": ").append(k)
                        .append(", \"load_index\": ").append(ly).append(", \"ref_rpm\": ").append(BAKE_REF[k]).append(", \"ref_load\": ").append(loadVal[ly])
                        .append(", \"frames\": ").append(m && mutate.equals("frames") ? fr + 1 : fr)
                        .append(", \"cycles\": ").append(m && mutate.equals("cycles") ? BAKE_CYC[k] + 0.5 : BAKE_CYC[k])
                        .append(", \"loop_start\": 0, \"loop_end\": ").append(fr)
                        .append(", \"phase_zero_frame\": ").append(m && mutate.equals("phase") ? 5 : 0).append('}');
                first = false;
            }
        for (int e = 0; e < BAKE_EV.length; e++) {
            String f = "wav/event_" + BAKE_EV[e] + ".wav";
            WavIO.write(new File(dir, f), bakeEvent(e), 44100);
            j.append(",\n  {\"type\": \"event\", \"file\": \"").append(f).append("\", \"name\": \"").append(BAKE_EV[e])
                    .append("\", \"frames\": ").append(BAKE_EV_FRAMES[e]).append('}');
        }
        j.append("\n ],\n \"pops_from_engine\": true\n}\n");
        File man = new File(dir, "manifest.json");
        Files.writeText(man, j.toString());
        Files.writeText(new File(dir, "spec.txt"), "cylinders=4\nrev_limit_rpm=6700\npop_min_rpm=4000\nnoise=0.3\n");
        return man;
    }

    /** {amplitude[], phase[]} of whole orders in an aligned loop (K cycles: order n on bin K n) */
    static double[][] loopOrders(ves.core.Pack.Loop lp) {
        int n = lp.pcm.length;
        int k = (int) ((long) n * lp.rpm / PackRenderer.CYCLE);
        double[] re = new double[n], im = new double[n];
        for (int i = 0; i < n; i++) re[i] = lp.pcm[i] / 32768.0;
        Fft.dft(re, im, false);
        int orders = (n / 2 - 1) / k;
        double[][] r = new double[2][orders + 1];
        for (int o = 1; o <= orders; o++) {
            r[0][o] = 2 * Math.hypot(re[k * o], im[k * o]) / n;
            r[1][o] = Math.atan2(im[k * o], re[k * o]);
        }
        return r;
    }

    /** power per DFT bin of a loop (full scale 1) */
    static double[] power(short[] pcm) {
        int n = pcm.length;
        double[] re = new double[n], im = new double[n];
        for (int i = 0; i < n; i++) re[i] = pcm[i] / 32768.0;
        Fft.dft(re, im, false);
        for (int i = 0; i < n; i++) re[i] = re[i] * re[i] + im[i] * im[i];
        return re;
    }

    /** Main.run with its stderr caught into err[0] */
    static int runCapturing(String[] args, String[] err) throws IOException {
        java.io.PrintStream old = System.err;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(b, true, "UTF-8"));
        try {
            return Main.run(args);
        } finally {
            System.setErr(old);
            err[0] = new String(b.toByteArray(), "UTF-8");
        }
    }

    /** PackVoice's level-matching gain of one loop */
    static double[] gains(ves.core.Pack.Loop lp) {
        return new ves.core.PackVoiceProbe().gains(new ves.core.Pack.Loop[] {lp});
    }

    /** 2 s of PackVoice on load at a constant rpm (the first 0.5 s dropped) */
    static double[] renderAt(ves.core.Pack p, double rpm) {
        ves.core.PackVoice v = new ves.core.PackVoice(22050);
        v.setPack(p);
        int total = 2 * 22050;
        float[] y = new float[total];
        v.renderRaw(y, total, rpm, 1, 0);
        double[] d = new double[total - 11025];
        for (int i = 0; i < d.length; i++) d[i] = y[11025 + i];
        return d;
    }

    static double rms(double[] x) {
        double s = 0;
        for (double v : x) s += v * v;
        return Math.sqrt(s / x.length);
    }

    /** mean order amplitudes of a constant-rpm signal (computed order tracking with a constant track) */
    static double[] constantOrders(double[] x, double rpm) {
        double fc = rpm / 120;
        int frames = (x.length - 1) / 256 + 1;
        double[] f = new double[frames];
        java.util.Arrays.fill(f, fc);
        Orders.Frames fr = Orders.analyse(x, 22050, Track.fromFc(0, 256 / 22050.0, f), 10000);
        double[] a = new double[fr.maxOrder + 1];
        for (int n = 1; n <= fr.maxOrder; n++) {
            double s = 0;
            int c = 0;
            for (int k = 0; k < fr.count(); k++)
                if (!Float.isNaN(fr.amp[k][n])) {
                    s += Math.pow(10, fr.amp[k][n] / 20);
                    c++;
                }
            a[n] = c > 0 ? s / c : 0;
        }
        return a;
    }

    /** {lowest loop RMS dBFS, highest loop RMS dBFS, highest crest factor dB} */
    static double[] levels(ves.core.Pack p) {
        double lo = 999, hi = -999, crest = 0;
        for (ves.core.Pack.Loop[] layer : new ves.core.Pack.Loop[][] {p.on, p.off})
            for (ves.core.Pack.Loop lp : layer) {
                double ss = 0, pk = 0;
                for (short v : lp.pcm) {
                    ss += (double) v * v;
                    pk = Math.max(pk, Math.abs((double) v));
                }
                double r = Math.sqrt(ss / lp.pcm.length) / 32768;
                lo = Math.min(lo, 20 * Math.log10(r));
                hi = Math.max(hi, 20 * Math.log10(r));
                crest = Math.max(crest, 20 * Math.log10(pk / 32768 / r));
            }
        return new double[] {lo, hi, crest};
    }

    /** a known order x resonance x level truth for the fit tests (dB re amplitude 1) */
    static final class Truth {
        static final double FLOOR = -90;
        final int cyl, maskedClass;

        Truth(int cyl, int maskedClass) {
            this.cyl = cyl;
            this.maskedClass = maskedClass;
        }

        double s(int n) {
            return (n % cyl == 0 ? 0 : -12) - 4 * Math.log(n) / Math.log(2);
        }

        static double h(double f) {
            double lf = Math.log(f);
            return 6 * Math.exp(-Math.pow((lf - Math.log(400)) / 0.3, 2)) + 4 * Math.exp(-Math.pow((lf - Math.log(2000)) / 0.25, 2))
                    - 6 * f / 6000;
        }

        double db(int n, double rpm) {
            if (maskedClass >= 0 && n % cyl == maskedClass) return FLOOR + 3;
            return s(n) + h(n * rpm / 120) - 20 + 4.3 * Math.log(rpm / 1000);
        }
    }

    /** order frames as the analysis would give them: truth + Gaussian error, log-uniform rpm, f <= 8.5 kHz */
    static Orders.Frames fixtureFrames(Truth tr, double rLo, double rHi, int count, double errDb, long seed) {
        Random r = new Random(seed);
        Orders.Frames fr = new Orders.Frames();
        int maxOrder = (int) (8500 / (rLo / 120));
        fr.maxOrder = maxOrder;
        fr.time = new double[count];
        fr.rpm = new double[count];
        fr.amp = new float[count][maxOrder + 1];
        fr.floor = new float[count][maxOrder + 1];
        for (int k = 0; k < count; k++) {
            double rpm = Math.exp(Math.log(rLo) + (Math.log(rHi) - Math.log(rLo)) * (k + 0.5) / count);
            fr.time[k] = k * 0.02;
            fr.rpm[k] = rpm;
            for (int n = 0; n <= maxOrder; n++) {
                boolean in = n >= 1 && n * rpm / 120 <= 8500;
                fr.amp[k][n] = in ? (float) (tr.db(n, rpm) + errDb * r.nextGaussian()) : Float.NaN;
                fr.floor[k][n] = in ? (float) Truth.FLOOR : Float.NaN;
            }
        }
        return fr;
    }

    /** RMSE over orders with frequency in [fLo, fHi] at one rpm */
    static double rmseAt(Model.Layer m, Truth tr, double rpm, double fLo, double fHi) {
        double se = 0;
        int c = 0;
        for (int n = 1; n * rpm / 120 <= fHi; n++) {
            if (n * rpm / 120 < fLo) continue;
            double e = m.predict(n, rpm) - tr.db(n, rpm);
            se += e * e;
            c++;
        }
        return Math.sqrt(se / c);
    }

    /** the drive's own crank phase, as engine() integrates it, at 256-sample frames */
    static Track trueTrack(Drive d) {
        int hop = 256, frames = (d.rpm.length - 1) / hop + 1;
        double[] fc = new double[frames], th = new double[frames];
        double theta = 0;
        for (int i = 0; i < d.rpm.length; i++) {
            if (i % hop == 0) {
                fc[i / hop] = d.rpm[i] / 120;
                th[i / hop] = theta;
            }
            theta += d.rpm[i] / 120 / SR;
        }
        return new Track(0, hop / (double) SR, fc, th);
    }

    static double median(List<Double> v) {
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        java.util.Arrays.sort(a);
        return a.length == 0 ? Double.NaN : a[a.length / 2];
    }
}
