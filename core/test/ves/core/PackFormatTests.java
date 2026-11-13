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
    }

    static String join(String[] a) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.length; i++) b.append(i == 0 ? "" : " ").append(a[i]);
        return b.toString();
    }
}
