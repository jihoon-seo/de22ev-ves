package ves.core;

/**
 * Plays a sound pack: for each load layer the two loops nearest the rpm are crossfaded at constant power on
 * log rpm, each pitch-shifted by rpm / its own rpm (linear interpolation, ratio held to 0.5..2 beyond the pack's
 * range). The two layers around the effective load e = smoothstep(load) x (1 - regen) are blended (load 1: the
 * top layer only, load 0 or full overrun: the load-0 layer only; a two-layer pack plays exactly as before 0.2.8). The mix (plus pops) goes through
 * OutputStage, times gain*30000: |sample| never exceeds gain*30000, as with Synth. Parameters are
 * interpolated across the block; loop indices and weights are worked out every SEG samples.
 * Aligned packs (Pack.aligned: rendered, every loop starting at the same crank angle) are played on one
 * shared engine-cycle phase, so neighbouring loops sum in phase: linear crossfades, no comb-filter dips.
 * The pack's level trim (Pack.level) scales every loop after the level matching.
 * Events (0.2.8): a pack with pop or bubble sounds plays one of them at each burst of the pops episodes
 * (Pops.trigger, instead of the synthesized Pops.next): a pop at or above Pack.popRpm, a bubble below (the one
 * family a pack has at every rpm), at Pack.eventGain x strength / 0.9; shift() plays a shift sound. Up to
 * VOICES at once (the oldest gives way), mixed before the output stage, so they share its cap.
 * Allocation-free after setPack; one thread.
 */
public final class PackVoice {
    static final double FS = 30000;
    static final int SEG = 16;
    /**
     * every loop is brought to the synth's voice level (RMS 0.30, gain at most 16x = 24 dB): the model's gain shapes
     * loudness (idle level, load) as for the synth, the pack gives the timbre. Rendered idle loops are much
     * quieter than full-load ones (12-18 dB under the synth in the first comparison renders).
     */
    static final double LOOP_RMS = 0.30, MAX_LOOP_GAIN = 16;

    private final OutputStage stage;
    private Pops pops;
    private Pack pack;
    private double[][] ph = new double[0][], g = new double[0][]; // per layer
    private final int sr;
    private double cyc; // engine cycles since start (aligned packs)
    private boolean havePrev;
    private double pRpm = 800, pLoad, pRegen, pGain, pMk = 1;
    // event voices
    static final int VOICES = 4;
    private final short[][] evPcm = new short[VOICES][];
    private final int[] evPos = new int[VOICES];
    private final double[] evAmp = new double[VOICES];
    private final int[] evStarted = new int[Pack.EVENT_KINDS.length];
    private int evActive, evRng = 0x51F7;
    private boolean popEvents; // the pack has pop or bubble sounds
    /** tests: when set (at least a block long), render() leaves its mix before the output stage here */
    float[] tap;

    public PackVoice(int sampleRate) {
        sr = sampleRate > 0 ? sampleRate : 22050;
        stage = new OutputStage(sr);
    }

    public void setPack(Pack p) {
        pack = p;
        eStarted = false;
        ph = new double[p == null ? 0 : p.layers.length][];
        for (int k = 0; k < ph.length; k++) ph[k] = new double[p.layers[k].length];
        // the gains come with the pack (worked out on the loader thread): this runs on the audio thread
        g = p == null ? new double[0][] : p.gains;
        clearEvents();
        popEvents = p != null && p.events[Pack.POP].length + p.events[Pack.BUBBLE].length > 0;
    }

    static double[] gains(Pack.Loop[] L) {
        double[] g = new double[L.length];
        for (int k = 0; k < L.length; k++) {
            double ss = 0;
            short[] s = L[k].pcm;
            for (short v : s) ss += (double) v * v;
            double rms = Math.sqrt(ss / Math.max(1, s.length)) / 32768.0;
            g[k] = rms < 1e-6 ? 0 : Math.min(MAX_LOOP_GAIN, LOOP_RMS / rms);
        }
        return g;
    }

    public Pack pack() {
        return pack;
    }

    /** a virtual gear change: plays one of the pack's shift sounds (none while silent or without them) */
    public void shift(boolean up) {
        int kind = up ? Pack.SHIFT_UP : Pack.SHIFT_DOWN;
        if (pack == null || pGain == 0 || pack.events[kind].length == 0) return;
        startEvent(kind, pack.eventGain[kind]);
    }

    /** events of the kind (Pack.POP ...) started since this voice was made */
    public int eventsStarted(int kind) {
        return kind >= 0 && kind < evStarted.length ? evStarted[kind] : 0;
    }

    /** one of the pack's sounds of this kind (chosen at random) at amp, in a free voice or the oldest one's */
    private void startEvent(int kind, double amp) {
        short[][] e = pack.events[kind];
        evRng ^= evRng << 13;
        evRng ^= evRng >>> 17;
        evRng ^= evRng << 5;
        int v = 0;
        for (int i = 1; i < VOICES; i++) {
            if (evPcm[v] == null) break;
            if (evPcm[i] == null || evPos[i] > evPos[v]) v = i;
        }
        if (evPcm[v] == null) evActive++;
        evPcm[v] = e[(evRng >>> 1) % e.length];
        evPos[v] = 0;
        evAmp[v] = amp / 32768.0;
        evStarted[kind]++;
    }

    /** the event voices' next sample */
    private double events() {
        double y = 0;
        for (int v = 0; v < VOICES; v++) {
            short[] s = evPcm[v];
            if (s == null) continue;
            y += s[evPos[v]] * evAmp[v];
            if (++evPos[v] >= s.length) {
                evPcm[v] = null;
                evActive--;
            }
        }
        return y;
    }

    private void clearEvents() {
        for (int v = 0; v < VOICES; v++) evPcm[v] = null;
        evActive = 0;
    }

    public void setPops(Pops p) {
        pops = p;
    }

    /** samples the output stage sent into its hard limit so far */
    public long clamped() {
        return stage.clamped();
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
        render(out, off, frames, p, gain, 1);
    }

    /** makeup (&gt;= 1): OutputStage.process's upward gain, interpolated across the block like gain */
    public void render(short[] out, int off, int frames, EngineParams p, double gain, double makeup) {
        if (out == null || frames <= 0 || off < 0 || off > out.length - frames) return;
        double mT = makeup > 1 && fin(makeup) ? Math.min(makeup, 16) : 1;
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
            clearEvents();
            pRpm = rpmT;
            pLoad = loadT;
            pRegen = regenT;
            pGain = gT;
            pMk = mT;
            return;
        }
        for (int pos = 0; pos < frames; pos += SEG) {
            int n = Math.min(SEG, frames - pos);
            double f = (pos + n * 0.5) / frames, rpm = lerp(pRpm, rpmT, f);
            mix(seg, 0, n, rpm, lerp(pLoad, loadT, f), lerp(pRegen, regenT, f));
            for (int i = 0; i < n; i++) {
                double x = seg[i];
                if (pops != null) {
                    if (!popEvents) x += pops.next();
                    else {
                        double st = pops.trigger();
                        if (st > 0) {
                            boolean pop = pack.events[Pack.BUBBLE].length == 0 || pack.events[Pack.POP].length > 0 && rpm >= pack.popRpm;
                            int kind = pop ? Pack.POP : Pack.BUBBLE;
                            startEvent(kind, pack.eventGain[kind] * st / 0.9);
                        }
                    }
                }
                if (evActive > 0) x += events();
                if (tap != null) tap[pos + i] = (float) x;
                double fi = (pos + i + 1) / (double) frames;
                double s = stage.process(x, pMk + (mT - pMk) * fi);
                double g = pGain + (gT - pGain) * fi;
                out[off + pos + i] = (short) (int) (s * g * FS);
            }
        }
        stage.flush();
        pRpm = rpmT;
        pLoad = loadT;
        pRegen = regenT;
        pGain = gT;
        pMk = mT;
    }

    /** 3+ layer packs: time constants of the effective layer load going up and down */
    static final double LAYER_UP_S = 0.4, LAYER_DOWN_S = 0.08;
    private double lastE, eS;
    private boolean eStarted;

    /** the effective layer load the last mix used (tests) */
    double layerLoad() {
        return lastE;
    }

    private void mix(float[] out, int at, int n, double rpm, double load, double regen) {
        if (pack == null) {
            for (int i = 0; i < n; i++) out[at + i] = 0;
            return;
        }
        double l = clamp(load, 0, 1);
        double[] lam = pack.loads;
        int top = lam.length - 1, k = 0;
        // smoothstep, overrun pulls to off; held to the layers' range
        double e = clamp(l * l * (3 - 2 * l) * (1 - clamp(regen, 0, 1)), lam[0], lam[top]);
        if (top >= 2) {
            // 3+ layers: up slowly, down fast (the loaded layers can be darker than the off layer: on a pull-away the
            // rpm rises first and the loaded timbre follows, instead of the sound dropping within 0.1 s)
            if (!eStarted) {
                eS = e; // a fresh voice or pack starts at the current load
                eStarted = true;
            } else {
                eS += (e - eS) * (1 - Math.exp(-n / (sr * (e > eS ? LAYER_UP_S : LAYER_DOWN_S))));
            }
            e = eS;
        }
        lastE = e;
        while (k < top - 1 && e >= lam[k + 1]) k++;
        double w = (e - lam[k]) / (lam[k + 1] - lam[k]); // two layers: w = e exactly
        boolean al = pack.aligned;
        double aHi = al ? w : Math.sqrt(w), aLo = al ? 1 - w : Math.sqrt(1 - w);
        for (int i = 0; i < n; i++) out[at + i] = 0;
        double dc = rpm / 120.0 / sr; // engine cycles per sample (4-stroke)
        // the upper layer first: the order the on and off layers were summed in before 0.2.8
        if (aHi > 1e-3) layer(pack.layers[k + 1], ph[k + 1], g[k + 1], rpm, aHi, out, at, n, al, cyc, dc, sr);
        if (aLo > 1e-3) layer(pack.layers[k], ph[k], g[k], rpm, aLo, out, at, n, al, cyc, dc, sr);
        cyc += n * dc;
    }

    /** adds amp x (the two loops nearest rpm) into out[at .. at+n) */
    private static void layer(Pack.Loop[] L, double[] ph, double[] g, double rpm, double amp, float[] out, int at, int n,
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
            if (a > 1e-4) playAligned(L[i], a * g[i], out, at, n, cyc, dc, sr);
            if (b > 1e-4 && j != i) playAligned(L[j], b * g[j], out, at, n, cyc, dc, sr);
            return;
        }
        if (a > 1e-4) play(L[i], ph, i, rpm, a * g[i], out, at, n);
        if (b > 1e-4 && j != i) play(L[j], ph, j, rpm, b * g[j], out, at, n);
    }

    /** position = the shared crank phase within the loop's whole cycles */
    private static void playAligned(Pack.Loop lp, double amp, float[] out, int at, int n, double cyc, double dc, int sr) {
        short[] s = lp.pcm;
        int len = s.length;
        double spc = 120.0 * sr / lp.rpm; // samples per engine cycle in this loop
        // whole cycles in the loop; a length rounded down by under a sample still holds its last cycle (0.2.5
        // truncated: 43 cycles at 6500 rpm are 17504.3 samples, so only 42 played and the wrap missed the seam)
        double k = len / spc;
        long kr = Math.round(k);
        int cycles = Math.max(1, (int) ((kr - k) * spc < 1.0 && kr > k - 1e-9 ? kr : Math.floor(k + 1e-6)));
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
