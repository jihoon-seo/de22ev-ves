package ves.core;

import java.util.List;

/**
 * 0.2.8 pack format: more loops, load layers between off and on, one-shot events (sampled pops, bubbles, shift
 * sounds). Registered from TestMain.all().
 */
final class PackFormatTests {
    private PackFormatTests() {
    }

    static final int SR = TestMain.SR, BLOCK = TestMain.BLOCK;

    static void check(boolean c, String msg) {
        if (!c) throw new AssertionError(msg);
    }

    static void near(double actual, double expected, double tol, String msg) {
        if (!(Math.abs(actual - expected) <= tol))
            throw new AssertionError(msg + " actual=" + actual + " expected=" + expected + " tol=" + tol);
    }

    // ------------------------------------------------------------------ fixtures

    /** sinePack without its "aligned yes" line: free-running loops, constant-power crossfades */
    static TestMain.MapSource nonAligned() {
        TestMain.MapSource s = TestMain.sinePack(false, false);
        String t = new String(s.files.get("pack.txt")).replace("aligned yes\n", "");
        s.files.put("pack.txt", t.getBytes());
        return s;
    }

    /** PL1's marked pack (three on-load loops, whole cycles rounded down) with an optional extra line */
    static TestMain.MapSource marked(String line) {
        java.util.Map<String, byte[]> m = new java.util.HashMap<String, byte[]>();
        StringBuilder t = new StringBuilder("name lv\ncylinders 4\nidle 800\nredline 6500\naligned yes\n");
        if (line != null) t.append(line).append('\n');
        for (int[] lk : new int[][] { { 1100, 7 }, { 2000, 9 }, { 6500, 43 } }) {
            String f = "m" + lk[0] + ".wav";
            m.put(f, TestMain.wavPcm(TestMain.markedLoop(lk[0], lk[1])));
            t.append("loop ").append(f).append(" rpm=").append(lk[0]).append(" load=on\n");
        }
        m.put("pack.txt", t.toString().getBytes());
        return new TestMain.MapSource(m);
    }

    /** the PK0 packs: aligned sine, free-running sine, no off layer, marked at level -6 */
    static Pack[] pinPacks() throws PackError {
        return new Pack[] {PackLoader.load(TestMain.sinePack(false, false)), PackLoader.load(nonAligned()),
                PackLoader.load(TestMain.testPack("noff")), PackLoader.load(marked("level -6"))};
    }

    /** on-load sine loops of the given lengths (rpm 800 + 100 k), then off-load ones */
    static TestMain.MapSource sized(int[] onLen, int[] offLen) {
        java.util.Map<String, byte[]> m = new java.util.HashMap<String, byte[]>();
        StringBuilder t = new StringBuilder("name big\ncylinders 4\nidle 800\nredline 6500\n");
        for (int layer = 0; layer < 2; layer++) {
            int[] len = layer == 0 ? onLen : offLen;
            for (int k = 0; k < len.length; k++) {
                String f = "b" + layer + "_" + k + ".wav";
                m.put(f, TestMain.wav(22050, 1, 16, len[k], 110 + k));
                t.append("loop ").append(f).append(" rpm=").append(800 + 100 * k).append(" load=").append(layer == 0 ? "on" : "off").append('\n');
            }
        }
        m.put("pack.txt", t.toString().getBytes());
        return new TestMain.MapSource(m);
    }

    static int[] fill(int n, int v) {
        int[] a = new int[n];
        java.util.Arrays.fill(a, v);
        return a;
    }

    static String refusal(TestMain.MapSource s) {
        try {
            PackLoader.load(s);
            return null;
        } catch (PackError e) {
            return e.getMessage();
        }
    }

    static final int[] TONE_RPM = {1050, 2100, 4200, 6300};
    static final int TONE_LEN = 10080; // 4, 8, 16, 24 whole cycles

    /** one aligned whole-cycle loop: engine order ord at amplitude amp */
    static short[] toneLoop(int rpm, int ord, double amp) {
        double spc = 2646000.0 / rpm;
        short[] x = new short[TONE_LEN];
        for (int i = 0; i < x.length; i++) x[i] = (short) Math.round(amp * Math.sin(2 * Math.PI * ord * i / spc));
        return x;
    }

    /**
     * an aligned pack with one layer per loads[k] ("off", "0.45", "on", ...): the first count[k] of TONE_RPM, engine
     * order ord[k] at amplitude amp[k]; extra lines appended to pack.txt
     */
    static TestMain.MapSource layered(String[] loads, int[] count, int[] ord, double[] amp, String extra) {
        java.util.Map<String, byte[]> m = new java.util.HashMap<String, byte[]>();
        StringBuilder t = new StringBuilder("name layers\ncylinders 4\nidle 800\nredline 6500\naligned yes\n");
        for (int k = 0; k < loads.length; k++)
            for (int i = 0; i < count[k]; i++) {
                String f = "t" + TONE_RPM[i] + "_" + k + ".wav";
                m.put(f, TestMain.wavPcm(toneLoop(TONE_RPM[i], ord[k], amp[k])));
                t.append("loop ").append(f).append(" rpm=").append(TONE_RPM[i]).append(" load=").append(loads[k]).append('\n');
            }
        if (extra != null) t.append(extra);
        m.put("pack.txt", t.toString().getBytes());
        return new TestMain.MapSource(m);
    }

    /** off / 0.45 / on, orders 8 / 12 / 20, amplitudes 9000 / 6000 / 3000 */
    static TestMain.MapSource threeLayers(String extra) {
        return layered(new String[] {"off", "0.45", "on"}, new int[] {4, 4, 4}, new int[] {8, 12, 20}, new double[] {9000, 6000, 3000}, extra);
    }

    static TestMain.MapSource replace(TestMain.MapSource s, String a, String b) {
        String t = new String(s.files.get("pack.txt"));
        check(t.indexOf(a) >= 0, "fixture has " + a);
        s.files.put("pack.txt", t.replace(a, b).getBytes());
        return s;
    }

    static int peak(short[] x) {
        int p = 0;
        for (short v : x) p = Math.max(p, Math.abs(v));
        return p;
    }

    /** the load whose smoothstep is e (bisection) */
    static double loadFor(double e) {
        double lo = 0, hi = 1;
        for (int i = 0; i < 200; i++) {
            double mid = (lo + hi) / 2;
            if (mid * mid * (3 - 2 * mid) < e) lo = mid;
            else hi = mid;
        }
        return (lo + hi) / 2;
    }

    /** amplitude of DFT bin b over y[from .. from+n) */
    static double binAmp(float[] y, int from, int n, int b) {
        double re = 0, im = 0;
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * b * (double) i / n;
            re += y[from + i] * Math.cos(a);
            im += y[from + i] * Math.sin(a);
        }
        return 2 * Math.hypot(re, im) / n;
    }

    /** {off, partial, high} tone amplitudes at 2100 rpm (8 cycles = 10080 samples: orders 8/12/20 on bins 64/96/160) */
    static double[] layerAmps(Pack p, double load, double regen) {
        PackVoice v = new PackVoice(SR);
        v.setPack(p);
        float[] y = new float[2 * TONE_LEN];
        v.renderRaw(y, y.length, 2100, load, regen);
        return new double[] {binAmp(y, TONE_LEN, TONE_LEN, 64), binAmp(y, TONE_LEN, TONE_LEN, 96), binAmp(y, TONE_LEN, TONE_LEN, 160)};
    }

    /** the pack, or an assertion failure with the loader's reason */
    static Pack loadOk(TestMain.MapSource s) throws PackError {
        String r = refusal(s);
        check(r == null, "pack refused: " + r);
        return PackLoader.load(s);
    }

    /** a decaying cosine: its peak (a share of full scale) on sample 0 */
    static short[] burst(int n, double peak, double hz) {
        short[] x = new short[n];
        for (int i = 0; i < n; i++) x[i] = (short) Math.round(peak * 32767 * Math.cos(2 * Math.PI * hz * i / SR) * Math.exp(-i / (0.004 * SR)));
        return x;
    }

    /** adds "event kind[i] e<i>.wav" lines and files (peak peaks[i], lens[i] samples) to a pack */
    static TestMain.MapSource withEvents(TestMain.MapSource s, String[] kinds, double[] peaks, int[] lens) {
        StringBuilder t = new StringBuilder(new String(s.files.get("pack.txt")));
        for (int i = 0; i < kinds.length; i++) {
            String f = "e" + i + ".wav";
            s.files.put(f, TestMain.wavPcm(burst(lens[i], peaks[i], 700 + 50 * i)));
            t.append("event ").append(kinds[i]).append(' ').append(f).append('\n');
        }
        s.files.put("pack.txt", t.toString().getBytes());
        return s;
    }

    /** the three-layer pack with 2 pops, 2 bubbles and one sound per shift direction */
    static TestMain.MapSource eventPack(String extra) {
        return withEvents(threeLayers(extra), new String[] {"pop", "pop", "bubble", "bubble", "shift_up", "shift_down"},
                new double[] {0.9, 0.7, 0.5, 0.4, 0.35, 0.3}, new int[] {2205, 1500, 3000, 2600, 4000, 5000});
    }

    /** the same loops, silent (level-matching gain 0): render()'s mix holds only the events */
    static TestMain.MapSource silentLoops(String extra) {
        return layered(new String[] {"off", "on"}, new int[] {4, 4}, new int[] {8, 20}, new double[] {0, 0}, extra);
    }

    /** render() through lift-offs (1 s full load, 5 s off, at 70 km/h) at a constant rpm, Pops 0x9095 strong */
    static final class Drive {
        final PackVoice v = new PackVoice(SR);
        final Pops pops = new Pops(SR, 0x9095), twin = new Pops(SR, 0x9095);
        /** the pre-stage mix of every sample */
        float[] tap;
        /** {sample index, strength} of every burst, from a twin Pops pulled in step */
        final List<double[]> starts = new java.util.ArrayList<double[]>();

        Drive(Pack p, double rpm, int blocks) {
            v.setPack(p);
            pops.setLevel(2);
            twin.setLevel(2);
            v.setPops(pops);
            v.tap = new float[BLOCK];
            tap = new float[blocks * BLOCK];
            EngineParams ep = new EngineParams();
            short[] out = new short[BLOCK];
            long t = 0;
            for (int b = 0; b < blocks; b++) {
                ep.rpm = rpm;
                ep.load = b % 600 < 100 ? 0.9 : 0;
                ep.speedKph = 70;
                pops.update(rpm, ep.load, 70, t);
                twin.update(rpm, ep.load, 70, t);
                v.render(out, 0, BLOCK, ep, 0.7);
                System.arraycopy(v.tap, 0, tap, b * BLOCK, BLOCK);
                for (int i = 0; i < BLOCK; i++) {
                    double s = twin.trigger();
                    if (s > 0) starts.add(new double[] {b * BLOCK + i, s});
                }
                t += 10;
            }
        }

        /** peak |mix| over n samples from each burst, divided by its strength */
        double[] peaksPerStrength(int n) {
            double[] r = new double[starts.size()];
            for (int k = 0; k < r.length; k++) {
                int at = (int) starts.get(k)[0];
                double pk = 0;
                for (int i = at; i < Math.min(tap.length, at + n); i++) pk = Math.max(pk, Math.abs(tap[i]));
                r[k] = pk / starts.get(k)[1];
            }
            return r;
        }
    }

    /** a VesCore playing pack p (pops off) whose virtual gear changes are counted per block */
    static final class ShiftRig {
        final TestMain.Rig r;
        int up, down, resets, resetEvents;
        boolean prevShifting;
        int prevGear;
        final java.security.MessageDigest out;

        ShiftRig(Profile prof, Pack p) throws Exception {
            Settings st = TestMain.iceSettings(prof, 60);
            st.pops = "off";
            st.sound = "user1";
            r = new TestMain.Rig(st);
            r.c.setPack(p);
            out = java.security.MessageDigest.getInstance("SHA-256");
        }

        int events(int kind) {
            return r.c.packVoice().eventsStarted(kind);
        }

        void step() {
            int before = events(Pack.SHIFT_UP) + events(Pack.SHIFT_DOWN);
            r.step();
            for (short v : r.buf) out.update(new byte[] {(byte) v, (byte) (v >> 8)});
            EngineParams o = r.c.lastParams();
            if (o.shifting && !prevShifting) {
                if (o.virtualGear > prevGear) up++;
                else down++;
            } else if (o.virtualGear < prevGear) {
                resets++; // a gear change without a shift (standstill)
                resetEvents += events(Pack.SHIFT_UP) + events(Pack.SHIFT_DOWN) - before;
            }
            prevShifting = o.shifting;
            prevGear = o.virtualGear;
        }

        /** pull away at 50 % pedal to 100 km/h (25 s), coast down to 10 km/h (20 s), then stop within 0.3 s */
        ShiftRig drive() {
            r.pedal = 50;
            r.kw = 40;
            for (int i = 0; i < 2500; i++) {
                r.speed = Math.min(100, i * 0.04);
                step();
            }
            r.pedal = 0;
            r.kw = -20;
            for (int i = 0; i < 2000; i++) {
                r.speed = 100 - 90 * i / 2000.0;
                step();
            }
            r.speed = 40;
            r.pedal = 30;
            r.kw = 20;
            for (int i = 0; i < 1000; i++) step();
            r.pedal = 0;
            for (int i = 0; i < 300; i++) {
                r.speed = Math.max(0, 40 - i * 4);
                step();
            }
            return this;
        }
    }

    static String hex(byte[] d, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(String.format("%02x", d[i] & 0xff));
        return b.toString();
    }

    /** block b of 600 (6 s): rpm 700 -> 7000 -> 700, load 0..1, regen in steps of 0.5 */
    static void path(int b, EngineParams ep) {
        double u = b < 300 ? b / 300.0 : (600 - b) / 300.0;
        ep.rpm = 700 + 6300 * u;
        ep.load = 0.5 + 0.5 * Math.sin(b / 23.0);
        ep.regen = (b / 70) % 3 * 0.5;
    }

    /** SHA-256 (first 8 bytes) of renderRaw's float bits and of render()'s shorts (gain 0.7, makeup 1.3) */
    static String pinRaw(Pack p) throws Exception {
        java.security.MessageDigest raw = java.security.MessageDigest.getInstance("SHA-256");
        java.security.MessageDigest out = java.security.MessageDigest.getInstance("SHA-256");
        PackVoice a = new PackVoice(SR), b = new PackVoice(SR);
        a.setPack(p);
        b.setPack(p);
        EngineParams ep = new EngineParams();
        float[] f = new float[BLOCK];
        short[] s = new short[BLOCK];
        for (int k = 0; k < 600; k++) {
            path(k, ep);
            a.renderRaw(f, BLOCK, ep.rpm, ep.load, ep.regen);
            for (float v : f) {
                int bits = Float.floatToIntBits(v);
                raw.update(new byte[] {(byte) bits, (byte) (bits >> 8), (byte) (bits >> 16), (byte) (bits >> 24)});
            }
            b.render(s, 0, BLOCK, ep, 0.7, 1.3);
            for (short v : s) out.update(new byte[] {(byte) v, (byte) (v >> 8)});
        }
        return hex(raw.digest(), 8) + "/" + hex(out.digest(), 8);
    }

    /** render() with Pops (seed 0x9095, strong) through a lift-off at 4000 rpm, 70 km/h */
    static String pinPops(Pack p) throws Exception {
        java.security.MessageDigest out = java.security.MessageDigest.getInstance("SHA-256");
        PackVoice v = new PackVoice(SR);
        v.setPack(p);
        Pops pops = new Pops(SR, 0x9095);
        pops.setLevel(2);
        v.setPops(pops);
        EngineParams ep = new EngineParams();
        short[] s = new short[BLOCK];
        long t = 0;
        for (int k = 0; k < 300; k++) {
            ep.rpm = 4000;
            ep.load = k < 100 ? 0.9 : 0;
            ep.speedKph = 70;
            pops.update(ep.rpm, ep.load, ep.speedKph, t);
            v.render(s, 0, BLOCK, ep, 0.7, 1.3);
            for (short x : s) out.update(new byte[] {(byte) x, (byte) (x >> 8)});
            t += 10;
        }
        check(pops.bursts() >= 3, "the lift-off popped: " + pops.bursts());
        return hex(out.digest(), 8);
    }

    // 0.2.7's output for two-layer packs (sine aligned, sine free-running, no off layer, marked level -6)
    static final String[] PK0A_WANT = {"f20eec7431596b72/d603ed14cc2c58f8", "b3f800d29699d8bd/bc0524672e4dad7b",
            "8fbab212fc1dd547/8e47dc35bcb4cb5e", "3a2e16408d8d6e03/1630fc1f4b3caf93"};
    static final String[] PK0B_WANT = {"43e43689eb5ad2ff", "4d804ce4264a7e8a", "5a833ef42472bbcf", "585485a6e0469c42"};

    static void add(List<TestMain.T> l) {
        // PK0 two-layer packs play exactly as 0.2.7 did: the load layers, events and shift sounds leave them alone
        l.add(new TestMain.T("PK0a_two_layer_output_pinned") {
            void run() throws Exception {
                Pack[] ps = pinPacks();
                StringBuilder got = new StringBuilder();
                for (int i = 0; i < ps.length; i++) got.append(i == 0 ? "" : " ").append(pinRaw(ps[i]));
                System.out.println("    PK0a " + got);
                check(got.toString().equals(join(PK0A_WANT)), "output changed: " + got + " vs " + join(PK0A_WANT));
            }
        });
        l.add(new TestMain.T("PK0b_two_layer_pops_pinned") {
            void run() throws Exception {
                Pack[] ps = pinPacks();
                StringBuilder got = new StringBuilder();
                for (int i = 0; i < ps.length; i++) got.append(i == 0 ? "" : " ").append(pinPops(ps[i]));
                System.out.println("    PK0b " + got);
                check(got.toString().equals(join(PK0B_WANT)), "output changed: " + got + " vs " + join(PK0B_WANT));
            }
        });

        // K6 0.2.8 limits: a physics bake has 24 rpm x 3 loads = 72 loops of about 1 s (about 1.6M samples)
        l.add(new TestMain.T("K6_pack_limits_72_loops") {
            void run() throws Exception {
                String r = refusal(sized(fill(36, 22050), fill(36, 22050)));
                check(r == null, "72 loops, 1.59M samples refused: " + r);
                r = refusal(sized(fill(49, 4410), fill(48, 4410)));
                check(r != null && r.indexOf("96") >= 0, "97 loops: " + r);
                check(refusal(sized(fill(48, 4410), fill(48, 4410))) == null, "96 loops load");
                // 45 x 44100 + 15500 = 2,000,000 samples exactly; one more is too many
                int[] on = fill(46, 44100), off = {};
                on[45] = 15500;
                r = refusal(sized(on, off));
                check(r == null, "exactly MAX_SAMPLES refused: " + r);
                on[45] = 15501;
                r = refusal(sized(on, off));
                check(r != null && r.indexOf("samples") >= 0, "one sample over MAX_SAMPLES: " + r);
                check(PackLoader.MAX_LOOPS == 96 && PackLoader.MAX_SAMPLES == 2000000, "limits " + PackLoader.MAX_LOOPS + " " + PackLoader.MAX_SAMPLES);
                check(refusal(TestMain.testPack("oversize")) != null, "K2 oversize still refused");
            }
        });

        // K5 load=on|off|<0..1>: up to 4 load layers (a physics bake: off, partial 0.45, high)
        l.add(new TestMain.T("K5_load_layers_parse") {
            void run() throws Exception {
                String r = refusal(threeLayers(null));
                check(r == null, "off/0.45/on refused: " + r);
                Pack p = PackLoader.load(threeLayers(null));
                check(java.util.Arrays.equals(p.loads, new double[] {0, 0.45, 1}), "loads " + java.util.Arrays.toString(p.loads));
                check(p.layers.length == 3 && p.layers[0] == p.off && p.layers[2] == p.on, "on = top layer, off = load 0");
                check(Math.abs(peak(p.off[0].pcm) - 9000) < 20 && Math.abs(peak(p.layers[1][0].pcm) - 6000) < 20 && Math.abs(peak(p.on[0].pcm) - 3000) < 20, "layers hold their loops");
                check(p.on.length == 4 && p.layers[1].length == 4 && p.off.length == 4, "4 loops per layer");
                // on = 1 = 1.000, off = 0 = 0.0: one layer each
                TestMain.MapSource s = threeLayers(null);
                replace(s, "rpm=4200 load=on", "rpm=4200 load=1");
                replace(s, "rpm=6300 load=on", "rpm=6300 load=1.000");
                replace(s, "rpm=1050 load=off", "rpm=1050 load=0");
                replace(s, "rpm=6300 load=off", "rpm=6300 load=0.0");
                Pack q = PackLoader.load(s);
                check(q.layers.length == 3 && q.on.length == 4 && q.off.length == 4, "on/1 and off/0 share their layers: " + q.layers.length);
                // no load-0 layer: the lowest stands in (as off = on in a pack without off-load loops)
                Pack n = PackLoader.load(layered(new String[] {"0.45", "on"}, new int[] {4, 4}, new int[] {12, 20}, new double[] {6000, 3000}, null));
                check(n.off == n.layers[0] && n.off == n.layers[1] && Math.abs(peak(n.off[0].pcm) - 6000) < 20, "0.45 stands in for off");
                check(java.util.Arrays.equals(n.loads, new double[] {0, 0.45, 1}), "stand-in loads " + java.util.Arrays.toString(n.loads));
                Pack two = PackLoader.load(TestMain.testPack("noff"));
                check(two.off == two.on && two.layers.length == 2, "no off layer: on stands in");
                // refused
                for (String bad : new String[] {"1.5", "-0.1", "0.4567", "0.45.1", ".5", "1.1"}) {
                    r = refusal(layered(new String[] {"off", bad, "on"}, new int[] {4, 4, 4}, new int[] {8, 12, 20}, new double[] {9000, 6000, 3000}, null));
                    check(r != null, "load=" + bad + " accepted");
                }
                r = refusal(layered(new String[] {"off", "0.2", "0.4", "0.6", "on"}, new int[] {4, 4, 4, 4, 4}, new int[] {8, 9, 10, 11, 20},
                        new double[] {9000, 8000, 7000, 6000, 3000}, null));
                check(r != null, "a 5th load accepted");
                check(refusal(layered(new String[] {"off", "0.2", "0.6", "on"}, new int[] {4, 4, 4, 4}, new int[] {8, 9, 10, 20},
                        new double[] {9000, 8000, 7000, 3000}, null)) == null, "4 loads refused");
                r = refusal(layered(new String[] {"off", "0.45", "on"}, new int[] {4, 1, 4}, new int[] {8, 12, 20}, new double[] {9000, 6000, 3000}, null));
                check(r != null, "a middle layer with one loop accepted");
                r = refusal(layered(new String[] {"off", "0.45", "on"}, new int[] {4, 4, 2}, new int[] {8, 12, 20}, new double[] {9000, 6000, 3000}, null));
                check(r != null, "a top layer of two loops accepted");
            }
        });

        // G9 PackVoice blends the two load layers around the effective load e = smoothstep(load) x (1 - regen)
        l.add(new TestMain.T("G9_load_layers_blend") {
            void run() throws Exception {
                Pack p = loadOk(threeLayers(null));
                double[] off = layerAmps(p, 0, 0), par = layerAmps(p, loadFor(0.45), 0), high = layerAmps(p, 1, 0);
                double a = high[2];
                System.out.println("    solo amplitudes off " + off[0] + " partial " + par[1] + " high " + a);
                check(a > 0.3, "the top layer plays at load 1: " + a);
                near(par[1], a, 0.02 * a, "e = 0.45: the partial layer alone");
                check(par[0] < 1e-3 * a && par[2] < 1e-3 * a, "e = 0.45: off " + par[0] + " high " + par[2] + " (< -60 dB)");
                near(off[0], a, 0.02 * a, "load 0: the off layer");
                check(off[1] < 1e-3 * a && off[2] < 1e-3 * a, "load 0: off only (" + off[1] + ", " + off[2] + ")");
                check(high[0] < 1e-3 * a && high[1] < 1e-3 * a, "load 1: high only");
                double[] mid = layerAmps(p, loadFor(0.725), 0);
                near(mid[1] / par[1], 0.5, 0.01, "e = 0.725: partial at half");
                near(mid[2] / a, 0.5, 0.01, "e = 0.725: high at half");
                check(mid[0] < 1e-3 * a, "e = 0.725: no off layer " + mid[0]);
                double[] rg = layerAmps(p, 1, 1);
                near(rg[0], a, 0.02 * a, "regen 1: the off layer");
                check(rg[1] < 1e-3 * a && rg[2] < 1e-3 * a, "regen 1: off only");
                // a 10 s load ramp at 3000 rpm through layers of the same tone: no clicks, level held
                Pack same = loadOk(layered(new String[] {"off", "0.45", "on"}, new int[] {4, 4, 4}, new int[] {8, 8, 8},
                        new double[] {12000, 6000, 3000}, null));
                PackVoice v = new PackVoice(SR);
                v.setPack(same);
                float[] y = new float[1000 * 220]; // 10 s in whole blocks
                float[] blk = new float[220];
                for (int b = 0; b < y.length / 220; b++) {
                    v.renderRaw(blk, 220, 3000, b / (double) (y.length / 220 - 1), 0);
                    System.arraycopy(blk, 0, y, b * 220, 220);
                }
                double lo = 1e9, hi = 0, maxD = 0;
                for (int k = 1; k < 99; k++) {
                    double ss = 0;
                    for (int i = k * 2205; i < (k + 1) * 2205; i++) ss += y[i] * y[i];
                    lo = Math.min(lo, Math.sqrt(ss / 2205));
                    hi = Math.max(hi, Math.sqrt(ss / 2205));
                }
                for (int i = 1; i < y.length; i++) maxD = Math.max(maxD, Math.abs(y[i] - y[i - 1]));
                double db = 20 * Math.log10(hi / lo), slope = 2 * Math.PI * 650 / SR * (12000 / 32768.0);
                System.out.println("    load ramp: level spread " + Math.round(db * 100) / 100.0 + " dB, max step " + maxD);
                check(db < 1.0, "ramp level spread " + db + " dB");
                check(maxD <= 1.3 * slope, "ramp max step " + maxD + " vs " + slope);
            }
        });

        // EV1 one-shot events: "event pop|bubble|shift_up|shift_down <file>.wav", "pop_rpm <n>"
        l.add(new TestMain.T("EV1_events_parse") {
            void run() throws Exception {
                Pack p = loadOk(eventPack("pop_rpm 4500\n"));
                check(p.events[Pack.POP].length == 2 && p.events[Pack.BUBBLE].length == 2 && p.events[Pack.SHIFT_UP].length == 1
                        && p.events[Pack.SHIFT_DOWN].length == 1, "events per kind");
                check(p.events[Pack.POP][0].length == 2205 && p.events[Pack.SHIFT_DOWN][0].length == 5000, "event samples");
                check(p.popRpm == 4500, "pop_rpm " + p.popRpm);
                check(loadOk(eventPack(null)).popRpm == 4000, "pop_rpm defaults to 4000");
                check(loadOk(threeLayers(null)).events[Pack.POP].length == 0, "no events: empty");
                // event gains on the loader thread: pops and bubbles share a peak of 0.9 x level, shifts 0.6 x level
                Pack q = loadOk(eventPack("level -6\n"));
                double lv = Math.pow(10, -6 / 20.0);
                near(q.eventGain[Pack.POP] * peak(q.events[Pack.POP][0]) / 32768, 0.9 * lv, 1e-9, "loudest pop at 0.9 x level");
                check(q.eventGain[Pack.BUBBLE] == q.eventGain[Pack.POP], "bubbles keep their level below pops");
                near(q.eventGain[Pack.SHIFT_UP] * peak(q.events[Pack.SHIFT_UP][0]) / 32768, 0.6 * lv, 1e-9, "loudest shift at 0.6 x level");
                check(q.eventGain[Pack.SHIFT_DOWN] == q.eventGain[Pack.SHIFT_UP], "one gain for both shifts");
                // refused
                String[][] bad = {{"crackle"}, {"pop", "440"}, {"pop", "44101"}, {"pop17"}, {"many33"}, {"badname"}, {"rate"}, {"noname"}};
                for (String[] b : bad) {
                    TestMain.MapSource s = threeLayers(null);
                    String k = b[0];
                    if (k.equals("crackle") || k.equals("pop") && b.length > 1)
                        withEvents(s, new String[] {k}, new double[] {0.5}, new int[] {b.length > 1 ? Integer.parseInt(b[1]) : 2205});
                    else if (k.equals("pop17")) {
                        String[] ks = new String[17];
                        java.util.Arrays.fill(ks, "pop");
                        double[] pk = new double[17];
                        java.util.Arrays.fill(pk, 0.5);
                        withEvents(s, ks, pk, fill(17, 1000));
                    } else if (k.equals("many33")) {
                        String[] ks = new String[33];
                        for (int i = 0; i < 33; i++) ks[i] = i < 16 ? "pop" : i < 32 ? "bubble" : "shift_up";
                        double[] pk = new double[33];
                        java.util.Arrays.fill(pk, 0.5);
                        withEvents(s, ks, pk, fill(33, 1000));
                    } else if (k.equals("badname")) {
                        s.files.put("pack.txt", (new String(s.files.get("pack.txt")) + "event pop ../e.wav\n").getBytes());
                    } else if (k.equals("rate")) {
                        s.files.put("e.wav", TestMain.wav(44100, 1, 16, 4000, 500));
                        s.files.put("pack.txt", (new String(s.files.get("pack.txt")) + "event pop e.wav\n").getBytes());
                    } else {
                        s.files.put("pack.txt", (new String(s.files.get("pack.txt")) + "event pop\n").getBytes());
                    }
                    check(refusal(s) != null, java.util.Arrays.toString(b) + " accepted");
                }
                check(refusal(withEvents(threeLayers(null), new String[] {"pop"}, new double[] {0.5}, new int[] {441})) == null, "0.02 s loads");
                check(refusal(threeLayers("pop_rpm x\n")) != null, "pop_rpm x accepted");
                // events count in MAX_SAMPLES: 45 x 44100 + 15000 loop samples, then a 500 / 501-sample pop
                int[] on = fill(46, 44100);
                on[45] = 15000;
                check(refusal(withEvents(sized(on, new int[0]), new String[] {"pop"}, new double[] {0.5}, new int[] {500})) == null, "2,000,000 samples load");
                check(refusal(withEvents(sized(on, new int[0]), new String[] {"pop"}, new double[] {0.5}, new int[] {501})) != null, "events counted");
            }
        });

        // EV2-EV4 the pops episodes fire the pack's sampled pops (at or above pop_rpm) or bubbles (below)
        l.add(new TestMain.T("EV2_event_per_burst") {
            void run() throws Exception {
                Drive d = new Drive(loadOk(eventPack(null)), 4500, 1800);
                int ev = d.v.eventsStarted(Pack.POP) + d.v.eventsStarted(Pack.BUBBLE);
                System.out.println("    bursts " + d.pops.bursts() + ", events " + ev);
                check(d.pops.bursts() >= 6, "three lift-offs pop: " + d.pops.bursts());
                check(ev == d.pops.bursts(), "one event per burst: " + ev + " vs " + d.pops.bursts());
                check(d.starts.size() == d.pops.bursts(), "trigger() follows next()'s schedule: " + d.starts.size());
            }
        });
        l.add(new TestMain.T("EV3_pops_above_pop_rpm_bubbles_below") {
            void run() throws Exception {
                Pack p = loadOk(eventPack(null));
                Drive hi = new Drive(p, 4500, 600), lo = new Drive(p, 3000, 600);
                check(hi.v.eventsStarted(Pack.POP) > 0 && hi.v.eventsStarted(Pack.BUBBLE) == 0, "4500 rpm: pops only "
                        + hi.v.eventsStarted(Pack.POP) + "/" + hi.v.eventsStarted(Pack.BUBBLE));
                check(lo.v.eventsStarted(Pack.BUBBLE) > 0 && lo.v.eventsStarted(Pack.POP) == 0, "3000 rpm: bubbles only "
                        + lo.v.eventsStarted(Pack.POP) + "/" + lo.v.eventsStarted(Pack.BUBBLE));
                Pack bub = loadOk(withEvents(threeLayers(null), new String[] {"bubble"}, new double[] {0.5}, new int[] {2000}));
                Drive b = new Drive(bub, 4500, 600);
                check(b.v.eventsStarted(Pack.BUBBLE) == b.pops.bursts() && b.pops.bursts() > 0, "bubbles only: bubbles at 4500 rpm");
                Pack pop = loadOk(withEvents(threeLayers(null), new String[] {"pop"}, new double[] {0.5}, new int[] {2000}));
                Drive q = new Drive(pop, 3000, 600);
                check(q.v.eventsStarted(Pack.POP) == q.pops.bursts() && q.pops.bursts() > 0, "pops only: pops at 3000 rpm");
            }
        });
        l.add(new TestMain.T("EV4_event_level") {
            void run() throws Exception {
                // one pop (peak 0.8) and one bubble (0.3), 25 ms: bursts at least 30 ms apart never overlap
                Pack p = loadOk(withEvents(silentLoops("level -6\n"), new String[] {"pop", "bubble"}, new double[] {0.8, 0.3}, new int[] {551, 551}));
                double lv = Math.pow(10, -6 / 20.0);
                Drive hi = new Drive(p, 4500, 1200), lo = new Drive(p, 3000, 1200);
                double[] a = hi.peaksPerStrength(551), b = lo.peaksPerStrength(551);
                check(a.length >= 3 && b.length >= 3, "bursts " + a.length + " " + b.length);
                for (double x : a) near(x, lv, 0.02 * lv, "pop peak = 0.9 x s/0.9 x level");
                for (double x : b) near(20 * Math.log10(x / a[0]), 20 * Math.log10(0.3 / 0.8), 0.1, "bubble / pop ratio (dB)");
            }
        });
        l.add(new TestMain.T("EV5_event_pops_inside_cap") {
            void run() throws Exception {
                Pack loud = loadOk(withEvents(threeLayers(null), new String[] {"pop", "pop", "bubble"}, new double[] {1.0, 1.0, 1.0},
                        new int[] {4000, 3000, 3000}));
                Settings st = TestMain.iceSettings(Profile.ICE_SHIFT, 100);
                st.pops = "strong";
                st.sound = "user1";
                TestMain.Rig r = new TestMain.Rig(st);
                r.c.setPack(loud);
                for (int k = 0; k < 6; k++) {
                    r.speed = 70;
                    r.pedal = 100;
                    r.kw = 80;
                    r.run(2500);
                    r.pedal = 0;
                    r.kw = -10;
                    r.run(2500);
                }
                check(r.c.voice().startsWith("pack:"), "the pack played: " + r.c.voice());
                check(r.c.pops().episodes() >= 2, "pops happened: " + r.c.pops().episodes());
                check(r.capViolations == 0 && r.peak <= 30000, "bounds: violations " + r.capViolations + " peak " + r.peak);
            }
        });
        l.add(new TestMain.T("K4b_event_pack_fuzz") {
            void run() throws Exception {
                java.util.Random rnd = new java.util.Random(11);
                final java.util.Map<String, byte[]> good = eventPack("pop_rpm 4200\n").files;
                java.util.List<String> names = new java.util.ArrayList<String>(new java.util.TreeSet<String>(good.keySet()));
                int ok = 0;
                for (int i = 0; i < 1500; i++) {
                    java.util.Map<String, byte[]> m = new java.util.HashMap<String, byte[]>();
                    String victim = rnd.nextInt(3) == 0 ? "pack.txt" : names.get(rnd.nextInt(names.size()));
                    for (java.util.Map.Entry<String, byte[]> e : good.entrySet()) {
                        byte[] b = e.getValue().clone();
                        if (e.getKey().equals(victim)) {
                            int flips = 1 + rnd.nextInt(6);
                            int span = victim.equals("pack.txt") ? b.length : Math.min(b.length, 80);
                            for (int k = 0; k < flips && b.length > 0; k++) b[rnd.nextInt(span)] = (byte) rnd.nextInt(256);
                            if (rnd.nextInt(10) == 0) b = java.util.Arrays.copyOf(b, rnd.nextInt(b.length + 1));
                        }
                        m.put(e.getKey(), b);
                    }
                    try {
                        PackLoader.load(new TestMain.MapSource(m));
                        ok++;
                    } catch (PackError expected) {
                    } catch (RuntimeException e) {
                        check(false, "fuzz " + i + " threw " + e);
                    }
                }
                System.out.println("    event fuzz: " + ok + " of 1500 mutated packs still valid");
            }
        });

        // SH1-SH4 the pack's shift sounds on ice_shift's virtual gear changes (rising edge of EngineParams.shifting)
        l.add(new TestMain.T("SH1_shift_sounds_on_gear_changes") {
            void run() throws Exception {
                ShiftRig s = new ShiftRig(Profile.ICE_SHIFT, loadOk(eventPack(null))).drive();
                System.out.println("    shifts up " + s.up + " down " + s.down + " resets " + s.resets + "; events up "
                        + s.events(Pack.SHIFT_UP) + " down " + s.events(Pack.SHIFT_DOWN));
                check(s.up >= 3, "through 4 gears: " + s.up + " upshifts");
                check(s.events(Pack.SHIFT_UP) == s.up, "one shift_up per upshift: " + s.events(Pack.SHIFT_UP) + " vs " + s.up);
                check(s.down > 0 && s.events(Pack.SHIFT_DOWN) == s.down, "shift_down on the way down: " + s.events(Pack.SHIFT_DOWN) + " vs " + s.down);
            }
        });
        l.add(new TestMain.T("SH2_no_shift_sounds_ice_smooth") {
            void run() throws Exception {
                ShiftRig s = new ShiftRig(Profile.ICE_SMOOTH, loadOk(eventPack(null))).drive();
                check(s.events(Pack.SHIFT_UP) + s.events(Pack.SHIFT_DOWN) == 0, "ice_smooth never shifts: " + s.events(Pack.SHIFT_UP));
            }
        });
        l.add(new TestMain.T("SH3_standstill_reset_silent") {
            void run() throws Exception {
                ShiftRig s = new ShiftRig(Profile.ICE_SHIFT, loadOk(eventPack(null))).drive();
                check(s.resets > 0, "the stop reset the gear to 1st without a shift: " + s.resets);
                check(s.resetEvents == 0, "no shift sound on the reset: " + s.resetEvents);
            }
        });
        l.add(new TestMain.T("SH4_pack_without_shift_sounds_unchanged") {
            void run() throws Exception {
                Pack withPops = loadOk(withEvents(threeLayers(null), new String[] {"pop", "bubble"}, new double[] {0.8, 0.4}, new int[] {2000, 2000}));
                ShiftRig a = new ShiftRig(Profile.ICE_SHIFT, withPops).drive(), b = new ShiftRig(Profile.ICE_SHIFT, loadOk(threeLayers(null))).drive();
                check(a.up > 0, "the drive shifts");
                check(java.util.Arrays.equals(a.out.digest(), b.out.digest()), "output differs from the pack without event lines");
            }
        });
        // G5 cost (as G_pack_voice) for a 3-layer pack playing pops and shift sounds
        l.add(new TestMain.T("G5b_event_pack_cost") {
            void run() throws Exception {
                Pack pk = loadOk(eventPack(null));
                PackVoice pv = new PackVoice(SR);
                pv.setPack(pk);
                Pops pops = new Pops(SR, 3);
                pops.setLevel(2);
                pv.setPops(pops);
                Synth sy = new Synth(SR, Profile.ICE_SMOOTH, 1);
                EngineParams ep = new EngineParams();
                short[] out = new short[220];
                for (int i = 0; i < 3000; i++) { // warm both up (JIT) before timing
                    drive(pv, pops, ep, i, out);
                    sy.render(out, 0, 220, ep, 0.5);
                }
                long t0 = System.nanoTime();
                for (int i = 0; i < 6000; i++) drive(pv, pops, ep, i, out);
                long tp = System.nanoTime() - t0;
                t0 = System.nanoTime();
                for (int i = 0; i < 6000; i++) {
                    ep.rpm = 1000 + 4000 * Math.abs(Math.sin(i / 500.0));
                    sy.render(out, 0, 220, ep, 0.5);
                }
                long ts = System.nanoTime() - t0;
                System.out.println("    60 s render: event pack " + tp / 1000000 + " ms (" + pv.eventsStarted(Pack.POP) + " pops, "
                        + pv.eventsStarted(Pack.SHIFT_UP) + " shifts), synth " + ts / 1000000 + " ms");
                check(pv.eventsStarted(Pack.POP) + pv.eventsStarted(Pack.BUBBLE) > 0 && pv.eventsStarted(Pack.SHIFT_UP) > 0, "events played");
                check(tp <= ts * 1.5 + 20000000L, "G5 pack " + tp / 1000000 + " ms vs synth " + ts / 1000000 + " ms");
            }

            void drive(PackVoice pv, Pops pops, EngineParams ep, int i, short[] out) {
                ep.rpm = 1000 + 4000 * Math.abs(Math.sin(i / 500.0));
                ep.load = i % 500 < 100 ? 0.9 : 0.5 + 0.4 * Math.sin(i / 37.0);
                ep.speedKph = 70;
                pops.update(ep.rpm, ep.load, 70, i * 10L);
                if (i % 150 == 0) pv.shift(i % 300 == 0);
                pv.render(out, 0, 220, ep, 0.5);
            }
        });
    }

    static String join(String[] a) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.length; i++) b.append(i == 0 ? "" : " ").append(a[i]);
        return b.toString();
    }
}
