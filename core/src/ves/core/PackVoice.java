package ves.core;

/**
 * Plays a sound pack: for each layer (on-load, off-load) the two loops nearest the rpm are crossfaded at
 * constant power on log rpm, each pitch-shifted by rpm / its own rpm (linear interpolation, ratio held to
 * 0.5..2 beyond the pack's range). The layers are blended by load (load 1: on only, load 0: off only); the
 * overrun amount (EngineParams.regen) moves towards the off layer. The mix (plus pops) goes through
 * OutputStage, times gain*30000: |sample| never exceeds gain*30000, as with Synth. Parameters are
 * interpolated across the block; loop indices and weights are worked out every SEG samples.
 * Aligned packs (Pack.aligned: rendered, every loop starting at the same crank angle) are played on one
 * shared engine-cycle phase, so neighbouring loops sum in phase: linear crossfades, no comb-filter dips.
 * Allocation-free after setPack; one thread.
 */
public final class PackVoice {
    static final double FS = 30000;
    static final int SEG = 16;
    /** pack loops peak at -1 dBFS; this brings their mix to about the synth's level into the output stage */
    static final double PACK_GAIN = 0.55;

    private final OutputStage stage;
    private Pops pops;
    private Pack pack;
    private double[] phOn = new double[0], phOff = new double[0];
    private final int sr;
    private double cyc; // engine cycles since start (aligned packs)
    private boolean havePrev;
    private double pRpm = 800, pLoad, pRegen, pGain;

    public PackVoice(int sampleRate) {
        sr = sampleRate > 0 ? sampleRate : 22050;
        stage = new OutputStage(sr);
    }

    public void setPack(Pack p) {
        pack = p;
        phOn = new double[p == null ? 0 : p.on.length];
        phOff = new double[p == null ? 0 : p.off.length];
    }

    public Pack pack() {
        return pack;
    }

    public void setPops(Pops p) {
        pops = p;
    }

    /** the voice mix before the output stage (about +-1), for tests and tools; parameters held for the call */
    public void renderRaw(float[] out, int frames, double rpm, double load, double regen) {
        for (int pos = 0; pos < frames; pos += SEG) {
            int n = Math.min(SEG, frames - pos);
            mix(out, pos, n, rpm, load, regen);
        }
    }

    private final float[] seg = new float[SEG];

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
        if (pack == null || gT == 0 && pGain == 0) {
            for (int i = 0; i < frames; i++) out[off + i] = 0;
            pRpm = rpmT;
            pLoad = loadT;
            pRegen = regenT;
            pGain = gT;
            return;
        }
        for (int pos = 0; pos < frames; pos += SEG) {
            int n = Math.min(SEG, frames - pos);
            double f = (pos + n * 0.5) / frames;
            mix(seg, 0, n, lerp(pRpm, rpmT, f), lerp(pLoad, loadT, f), lerp(pRegen, regenT, f));
            for (int i = 0; i < n; i++) {
                double x = seg[i] * PACK_GAIN;
                if (pops != null) x += pops.next();
                double s = stage.process(x);
                double g = pGain + (gT - pGain) * ((pos + i + 1) / (double) frames);
                out[off + pos + i] = (short) (int) (s * g * FS);
            }
        }
        stage.flush();
        pRpm = rpmT;
        pLoad = loadT;
        pRegen = regenT;
        pGain = gT;
    }

    private void mix(float[] out, int at, int n, double rpm, double load, double regen) {
        if (pack == null) {
            for (int i = 0; i < n; i++) out[at + i] = 0;
            return;
        }
        double l = clamp(load, 0, 1);
        double wOn = l * l * (3 - 2 * l) * (1 - clamp(regen, 0, 1)); // smoothstep, overrun pulls to off
        boolean al = pack.aligned;
        double aOn = al ? wOn : Math.sqrt(wOn), aOff = al ? 1 - wOn : Math.sqrt(1 - wOn);
        for (int i = 0; i < n; i++) out[at + i] = 0;
        double dc = rpm / 120.0 / sr; // engine cycles per sample (4-stroke)
        if (aOn > 1e-3) layer(pack.on, phOn, rpm, aOn, out, at, n, al, cyc, dc, sr);
        if (aOff > 1e-3) layer(pack.off, phOff, rpm, aOff, out, at, n, al, cyc, dc, sr);
        cyc += n * dc;
    }

    /** adds amp x (the two loops nearest rpm) into out[at .. at+n) */
    private static void layer(Pack.Loop[] L, double[] ph, double rpm, double amp, float[] out, int at, int n,
                              boolean aligned, double cyc, double dc, int sr) {
        int last = L.length - 1;
        int i = 0;
        while (i < last - 1 && rpm >= L[i + 1].rpm) i++;
        int j = Math.min(i + 1, last);
        double w;
        if (rpm <= L[0].rpm) w = 0;
        else if (rpm >= L[last].rpm) {
            i = last - 1 < 0 ? 0 : last - 1;
            j = last;
            w = 1;
        } else w = (Math.log(rpm) - Math.log(L[i].rpm)) / (Math.log(L[j].rpm) - Math.log(L[i].rpm));
        w = clamp(w, 0, 1);
        double a = (aligned ? 1 - w : Math.cos(w * Math.PI / 2)) * amp, b = (aligned ? w : Math.sin(w * Math.PI / 2)) * amp;
        if (aligned) {
            if (a > 1e-4) playAligned(L[i], a, out, at, n, cyc, dc, sr);
            if (b > 1e-4 && j != i) playAligned(L[j], b, out, at, n, cyc, dc, sr);
            return;
        }
        if (a > 1e-4) play(L[i], ph, i, rpm, a, out, at, n);
        if (b > 1e-4 && j != i) play(L[j], ph, j, rpm, b, out, at, n);
    }

    /** position = the shared crank phase within the loop's whole cycles */
    private static void playAligned(Pack.Loop lp, double amp, float[] out, int at, int n, double cyc, double dc, int sr) {
        short[] s = lp.pcm;
        int len = s.length;
        double spc = 120.0 * sr / lp.rpm; // samples per engine cycle in this loop
        int cycles = Math.max(1, (int) (len / spc + 1e-6));
        double scale = amp / 32768.0;
        double c = cyc % cycles;
        for (int i = 0; i < n; i++) {
            double p = c * spc;
            int i0 = (int) p;
            if (i0 >= len) i0 = len - 1;
            double fr = p - i0;
            int i1 = i0 + 1 >= len ? 0 : i0 + 1;
            out[at + i] += (float) ((s[i0] + (s[i1] - s[i0]) * fr) * scale);
            c += dc;
            if (c >= cycles) c -= cycles;
        }
    }

    private static void play(Pack.Loop lp, double[] ph, int k, double rpm, double amp, float[] out, int at, int n) {
        short[] s = lp.pcm;
        int len = s.length;
        double step = clamp(rpm / lp.rpm, 0.5, 2.0);
        double p = ph[k];
        double scale = amp / 32768.0;
        for (int i = 0; i < n; i++) {
            int i0 = (int) p;
            double fr = p - i0;
            int i1 = i0 + 1 == len ? 0 : i0 + 1;
            out[at + i] += (float) ((s[i0] + (s[i1] - s[i0]) * fr) * scale);
            p += step;
            if (p >= len) p -= len;
        }
        ph[k] = p;
    }

    private static boolean fin(double x) {
        return !Double.isNaN(x) && !Double.isInfinite(x);
    }

    private static double clamp(double x, double lo, double hi) {
        return x < lo ? lo : x > hi ? hi : x;
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }
}
