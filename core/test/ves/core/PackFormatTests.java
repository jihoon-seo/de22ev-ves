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
    }

    static String join(String[] a) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.length; i++) b.append(i == 0 ? "" : " ").append(a[i]);
        return b.toString();
    }
}
