package ves.core;

/**
 * Procedural engine voice, mono PCM16. render() is allocation-free; all parameters (rpm, load, regen, gain) are
 * interpolated linearly across the block from the previous call, so block-rate control never steps.
 *
 * <h3>ICE (ICE_SMOOTH, ICE_SHIFT)</h3>
 * 4-cylinder 4-stroke: firing frequency rpm/30. A crank-rate phase (rpm/60) reads one of NT additive wavetables
 * holding crank harmonics 1..16, i.e. firing orders 0.5, 1, 1.5, 2, 2.5 ... 8. The firing orders 1..4 (crank
 * harmonics 2, 4, 6, 8) carry most of the energy because the cabin speakers do not reproduce below ~60 Hz (idle
 * firing is 27 Hz); half orders are weak. Tables differ in spectral tilt (dark/overrun to bright/full load) and
 * are cross-faded per sample by load. Cycle-to-cycle jitter: per crank revolution +-0.3% frequency and +-6%
 * amplitude (smoothed). Exhaust noise: band-passed (~3x firing) noise pulsed by the firing phase. Intake roar:
 * band-passed noise above ~45% load. Regen/overrun: darker table, less noise, lower level.
 *
 * <h3>EV_FUTURE</h3>
 * Pitch f = 120..900 Hz from the model's rpm (1000..9000). Sine at f plus two detuned band-limited saws (pad)
 * through a 2-pole low-pass whose cutoff opens with load (closes with regen), slow LFO on detune and level, and a
 * quiet sine whine at 5 f (faded out near Nyquist).
 *
 * <h3>Output</h3>
 * The voice mix goes through {@link OutputStage} (speaker high-pass, presence band, soft clip, |y| &lt;= 1),
 * times gain*30000, truncated toward zero: |sample| never exceeds
 * gain*30000 (gain interpolated within the block). A profile switch between ICE and EV cross-fades the voices
 * over 80 ms.
 */
public final class Synth {
    static final int TBL = 2048, MASK = TBL - 1, NT = 6, CH = 512;
    static final double FS = 30000;

    static final float[] SINE = new float[TBL];
    static final float[] SAW = new float[TBL];
    static final float[][] ICE = new float[NT][TBL];

    /** crank-harmonic weights h = 1..16 (firing order h/2) */
    static final double[] W = {0.30, 0.55, 0.22, 1.0, 0.12, 0.75, 0.08, 0.60, 0.05, 0.13, 0.04, 0.28, 0.03, 0.06,
            0.02, 0.15};

    static {
        for (int i = 0; i < TBL; i++) SINE[i] = (float) Math.sin(2 * Math.PI * i / TBL);
        // band-limited saw, 10 harmonics (10 x 900 Hz < 11025 Hz), normalized to peak 1
        double[] saw = new double[TBL];
        double pk = 0;
        for (int i = 0; i < TBL; i++) {
            double x = 0;
            for (int h = 1; h <= 10; h++) x += Math.sin(2 * Math.PI * h * i / TBL) / h * (1 - 0.04 * h);
            saw[i] = x;
            pk = Math.max(pk, Math.abs(x));
        }
        for (int i = 0; i < TBL; i++) SAW[i] = (float) (saw[i] / pk);
        // fixed phases so the engine character does not depend on the seed
        java.util.Random r = new java.util.Random(1234);
        double[] ph = new double[W.length];
        for (int h = 0; h < W.length; h++) ph[h] = r.nextDouble() * 2 * Math.PI;
        for (int j = 0; j < NT; j++) {
            double b = j / (double) (NT - 1);
            double tilt = 1.8 - 1.5 * b;
            double[] t = new double[TBL];
            double ss = 0;
            for (int i = 0; i < TBL; i++) {
                double x = 0;
                for (int h = 1; h <= W.length; h++) {
                    double w = W[h - 1];
                    if (h > 4) w *= Math.pow(h / 4.0, -tilt);
                    x += w * Math.sin(2 * Math.PI * h * i / TBL + ph[h - 1]);
                }
                t[i] = x;
                ss += x * x;
            }
            double norm = 0.30 / Math.sqrt(ss / TBL);
            for (int i = 0; i < TBL; i++) ICE[j][i] = (float) (t[i] * norm);
        }
    }

    private final int sr;
    private final double invSr;
    private Profile profile, fromProfile;
    private double xfade = 1, xStep;

    private boolean havePrev;
    private double pRpm = 800, pLoad, pRegen, pGain;

    private int rng;
    // ICE state
    private double crank, jitF = 1, jitA = 1, jitAT = 1;
    private double exLow, exBand, inLow, inBand;
    private double iceLp;
    private final double iceLpC;
    // EV state
    private double evPh, padA, padB, whPh, lfoPh, lp1, lp2;
    private final OutputStage stage;

    private final float[] bufA = new float[CH], bufB = new float[CH];

    public Synth(int sampleRate, Profile p, long seed) {
        sr = sampleRate > 0 ? sampleRate : 22050;
        invSr = 1.0 / sr;
        profile = p != null ? p : Profile.ICE_SMOOTH;
        fromProfile = profile;
        rng = (int) (seed ^ (seed >>> 32)) ^ 0x5DEECE6;
        if (rng == 0) rng = 1;
        stage = new OutputStage(sr);
        xStep = 1.0 / (0.08 * sr);
        iceLpC = 1 - Math.exp(-2 * Math.PI * Math.min(4500.0, 0.3 * sr) / sr);
    }

    public void setProfile(Profile p) {
        if (p == null || p == profile) return;
        if (p.isIce() == profile.isIce()) {
            profile = p;
            return;
        }
        fromProfile = profile;
        profile = p;
        xfade = 1 - xfade; // keeps the voice weights continuous when switching mid-fade
    }

    public Profile profile() {
        return profile;
    }

    public void render(short[] out, int off, int frames, EngineParams p, double gain) {
        if (out == null || frames <= 0 || off < 0 || off > out.length - frames) return;
        double rpmT = p != null && fin(p.rpm) ? clamp(p.rpm, 300, 12000) : pRpm;
        double loadT = p != null && fin(p.load) ? clamp(p.load, 0, 1) : 0;
        double regenT = p != null && fin(p.regen) ? clamp(p.regen, 0, 1) : 0;
        double gT = gain > 0 && fin(gain) ? Math.min(gain, 1) : 0;
        if (!havePrev) {
            havePrev = true;
            pRpm = rpmT;
            pLoad = loadT;
            pRegen = regenT;
            pGain = 0;
        }
        if (gT == 0 && pGain == 0) {
            for (int i = 0; i < frames; i++) out[off + i] = 0;
            pRpm = rpmT;
            pLoad = loadT;
            pRegen = regenT;
            xfade = 1;
            return;
        }
        boolean curIce = profile.isIce();
        int pos = 0;
        while (pos < frames) {
            int n = Math.min(CH, frames - pos);
            double f0 = pos / (double) frames, f1 = (pos + n) / (double) frames;
            double r0 = lerp(pRpm, rpmT, f0), r1 = lerp(pRpm, rpmT, f1);
            double l0 = lerp(pLoad, loadT, f0), l1 = lerp(pLoad, loadT, f1);
            double g0 = lerp(pRegen, regenT, f0), g1 = lerp(pRegen, regenT, f1);
            double a0 = lerp(pGain, gT, f0), a1 = lerp(pGain, gT, f1);
            boolean fading = xfade < 1;
            boolean needIce = curIce || fading, needEv = !curIce || fading;
            if (needIce) ice(bufA, n, r0, r1, l0, l1, g0, g1);
            if (needEv) ev(bufB, n, r0, r1, l0, l1, g0, g1);
            float[] cur = curIce ? bufA : bufB, old = curIce ? bufB : bufA;
            double invN = 1.0 / n;
            for (int i = 0; i < n; i++) {
                double x = cur[i];
                if (fading) {
                    double w = xfade;
                    x = w * x + (1 - w) * old[i];
                    xfade += xStep;
                    if (xfade >= 1) {
                        xfade = 1;
                        fading = false;
                    }
                }
                double s = stage.process(x);
                double g = a0 + (a1 - a0) * ((i + 1) * invN);
                out[off + pos + i] = (short) (int) (s * g * FS);
            }
            pos += n;
        }
        // flush denormals / tiny states
        stage.flush();
        pRpm = rpmT;
        pLoad = loadT;
        pRegen = regenT;
        pGain = gT;
    }

    // ------------------------------------------------------------------ voices

    private void ice(float[] buf, int n, double r0, double r1, double l0, double l1, double g0, double g1) {
        double rm = 0.5 * (r0 + r1), lm = 0.5 * (l0 + l1), gm = 0.5 * (g0 + g1);
        double fire = rm / 30.0;
        double fcEx = clamp(fire * 8, 320, 1400); // exhaust rasp where small speakers play, pulsed at the firing rate
        double fEx = 2 * Math.sin(Math.PI * fcEx * invSr), qEx = 1 / 1.8;
        double nEx = 1 / Math.sqrt(1.8 * fEx * 0.5);
        double fcIn = 350 + 450 * clamp(rm / 6500, 0, 1);
        double fIn = 2 * Math.sin(Math.PI * fcIn * invSr), qIn = 1 / 1.3;
        double nIn = 1 / Math.sqrt(1.3 * fIn * 0.5);
        double exLvl = (0.16 + 0.16 * lm) * (1 - 0.5 * gm) * nEx; // 0.1.2: louder rasp (heard on small speakers)
        double over = Math.max(0, lm - 0.45) / 0.55;
        double inLvl = 0.22 * over * Math.sqrt(over) * (0.4 + 0.6 * clamp(rm / 6500, 0, 1)) * nIn;
        double amp0 = 0.60 + 0.40 * l0 - 0.20 * g0, amp1 = 0.60 + 0.40 * l1 - 0.20 * g1;
        double b0 = clamp(0.12 + 0.88 * l0 - 0.12 * g0, 0, 1) * (NT - 1);
        double b1 = clamp(0.12 + 0.88 * l1 - 0.12 * g1, 0, 1) * (NT - 1);
        double inc0 = r0 / 60.0 * invSr, inc1 = r1 / 60.0 * invSr;
        double invN = 1.0 / n;
        double ph = crank, jf = jitF, ja = jitA, jat = jitAT;
        double eL = exLow, eB = exBand, iL = inLow, iB = inBand;
        double lpC = iceLpC, lp = iceLp;
        int r = rng;
        for (int i = 0; i < n; i++) {
            double t = (i + 1) * invN;
            ph += (inc0 + (inc1 - inc0) * t) * jf;
            if (ph >= 1) {
                ph -= 1;
                if (ph >= 1) ph -= Math.floor(ph);
                r ^= r << 13;
                r ^= r >>> 17;
                r ^= r << 5;
                jf = 1 + 0.003 * (r * 4.656612873077393e-10);
                r ^= r << 13;
                r ^= r >>> 17;
                r ^= r << 5;
                jat = 1 + 0.06 * (r * 4.656612873077393e-10);
            }
            ja += (jat - ja) * 0.003;
            double idx = ph * TBL;
            int k0 = (int) idx;
            double fr = idx - k0;
            k0 &= MASK;
            int k1 = (k0 + 1) & MASK;
            double b = b0 + (b1 - b0) * t;
            int j = (int) b;
            if (j >= NT - 1) j = NT - 2;
            double w = b - j;
            float[] ta = ICE[j], tb = ICE[j + 1];
            double sa = ta[k0] + (ta[k1] - ta[k0]) * fr;
            double sb = tb[k0] + (tb[k1] - tb[k0]) * fr;
            double tone = sa + (sb - sa) * w;
            // firing pulse (2 firings per crank revolution)
            double fp = ph * 2;
            if (fp >= 1) fp -= 1;
            double c = SINE[((int) (fp * TBL) + (TBL / 4)) & MASK];
            double pulse = 0.5 + 0.5 * c;
            pulse *= pulse;
            r ^= r << 13;
            r ^= r >>> 17;
            r ^= r << 5;
            double nz = r * 4.656612873077393e-10;
            eL += fEx * eB;
            eB += fEx * (nz - eL - qEx * eB);
            r ^= r << 13;
            r ^= r >>> 17;
            r ^= r << 5;
            double nz2 = r * 4.656612873077393e-10;
            iL += fIn * iB;
            iB += fIn * (nz2 - iL - qIn * iB);
            double amp = amp0 + (amp1 - amp0) * t;
            lp += (amp * ja * tone + exLvl * eB * (0.35 + 0.65 * pulse) + inLvl * iB - lp) * lpC;
            buf[i] = (float) lp;
        }
        crank = ph;
        jitF = jf;
        jitA = ja;
        jitAT = jat;
        exLow = eL;
        exBand = eB;
        inLow = iL;
        inBand = iB;
        iceLp = lp;
        rng = r;
    }

    private void ev(float[] buf, int n, double r0, double r1, double l0, double l1, double g0, double g1) {
        double fA = evHz(r0), fB = evHz(r1);
        double lm = 0.5 * (l0 + l1), gm = 0.5 * (g0 + g1), fm = 0.5 * (fA + fB);
        double fc = clamp(fm * (1.5 + 5 * lm) * (1 - 0.35 * gm), 80, 0.3 * sr);
        double c = 1 - Math.exp(-2 * Math.PI * fc * invSr);
        double whF = 5 * fm;
        double whFade = clamp((0.42 * sr - whF) / (0.08 * sr), 0, 1);
        double wh0 = 0.045 * (0.3 + 0.7 * l0) * whFade, wh1 = 0.045 * (0.3 + 0.7 * l1) * whFade;
        double amp0 = 0.78 * (0.6 + 0.4 * l0) * (1 - 0.25 * g0), amp1 = 0.78 * (0.6 + 0.4 * l1) * (1 - 0.25 * g1);
        double invN = 1.0 / n;
        double lfoInc = 0.23 * invSr;
        double s0 = evPh, sA = padA, sB = padB, sw = whPh, lf = lfoPh, y1 = lp1, y2 = lp2;
        for (int i = 0; i < n; i++) {
            double t = (i + 1) * invN;
            double f = fA + (fB - fA) * t;
            lf += lfoInc;
            if (lf >= 1) lf -= 1;
            double lfo = SINE[(int) (lf * TBL) & MASK];
            double d = 0.005 + 0.002 * lfo;
            double inc = f * invSr;
            s0 += inc;
            if (s0 >= 1) s0 -= 1;
            sA += inc * (1 + d);
            if (sA >= 1) sA -= 1;
            sB += inc * (1 - d);
            if (sB >= 1) sB -= 1;
            sw += inc * 5;
            if (sw >= 1) sw -= Math.floor(sw);
            double sine = lookup(SINE, s0);
            double pad = 0.5 * (lookup(SAW, sA) + lookup(SAW, sB));
            y1 += (pad - y1) * c;
            y2 += (y1 - y2) * c;
            double wh = SINE[(int) (sw * TBL) & MASK];
            double amp = amp0 + (amp1 - amp0) * t;
            buf[i] = (float) (amp * (0.62 * sine + 0.38 * y2 * (0.9 + 0.1 * lfo)) + (wh0 + (wh1 - wh0) * t) * wh);
        }
        evPh = s0;
        padA = sA;
        padB = sB;
        whPh = sw;
        lfoPh = lf;
        lp1 = y1;
        lp2 = y2;
    }

    /** EV pitch: rpm 1000..9000 maps linearly to 120..900 Hz */
    static double evHz(double rpm) {
        return 120 + 780 * clamp((rpm - 1000) / 8000.0, 0, 1);
    }

    private static double lookup(float[] tb, double ph) {
        double idx = ph * TBL;
        int k0 = (int) idx;
        double fr = idx - k0;
        k0 &= MASK;
        return tb[k0] + (tb[(k0 + 1) & MASK] - tb[k0]) * fr;
    }

    private static boolean fin(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double clamp(double x, double lo, double hi) {
        return x < lo ? lo : x > hi ? hi : x;
    }
}
