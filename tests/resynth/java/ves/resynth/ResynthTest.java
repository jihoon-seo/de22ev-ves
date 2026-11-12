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

        return l;
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
