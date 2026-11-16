package ves.core;

/**
 * The speaker stage every voice goes through (the procedural synth and the sample packs alike): a 120 Hz
 * 2nd-order high-pass (saves headroom for what the speaker cannot play), +11 dB on 400-2500 Hz (the
 * navigation channel's speaker barely plays the 30-130 Hz firing frequency: car check 1 heard nothing of
 * it at low rpm, but heard 660/880 Hz test tones clearly), 1.6x drive into a soft clip
 * x(27+x^2)/(27+9x^2) on [-3,3]: process() returns |y| &lt;= 1. Packs are rendered neutral; the speaker
 * shaping lives here only. Per-sample, allocation-free, not thread-safe.
 */
public final class OutputStage {
    /** into the soft clip: +6 dB of level, the peaks rounded off rather than cut */
    static final double DRIVE = 1.6;
    static final double HP_HZ = 120, PRES_LO_HZ = 400, PRES_HI_HZ = 2500, PRES_K = 2.5; // band 400-2500 Hz +11 dB

    private final double hb0, hb1, hb2, ha1, ha2;
    private double hx1, hx2, hy1, hy2;
    private final double presLoC, presHiC;
    private double presLo, presHi;
    private long clamped;
    /** makeup: the peak it lifts quiet sound towards (into the soft clip: about 0.98 out), and its time constants */
    static final double TPK = 2.0, PEAK_REL_S = 0.2, MG_UP_S = 0.1;
    /**
     * 0.2.11: the lift's peak target follows the crest factor (peak follower / RMS follower, both 200 ms): TPK x
     * crest / CREST_REF (9 dB), held to 0.8 .. 1.15 x TPK. At the ceiling every sound then has about the same RMS:
     * S2000 Baked's loaded layer (crest 9.6 dB through these filters) played 1 dB under its off layer (8.5 dB) at
     * 90 km/h, volume 100, speed_boost high, and now within 0.3 dB. A very smooth sound loses at most 2 dB of lift;
     * an onset (the RMS lags, so it looks peaky) goes at most to 1.15 x TPK (2.3, under the hard limit at 3).
     */
    static final double CREST_REF = 2.82, CREST_FIX_LO = 0.8, CREST_FIX_HI = 1.15;
    private final double peakRel, mgUp;
    private double peak, ms, mg = 1;

    public OutputStage(int sampleRate) {
        int sr = sampleRate > 0 ? sampleRate : 22050;
        // RBJ biquad high-pass, Q = 0.7071
        double w0 = 2 * Math.PI * HP_HZ / sr, cs = Math.cos(w0), al = Math.sin(w0) / (2 * 0.70710678);
        double a0 = 1 + al;
        hb0 = (1 + cs) / 2 / a0;
        hb1 = -(1 + cs) / a0;
        hb2 = (1 + cs) / 2 / a0;
        ha1 = -2 * cs / a0;
        ha2 = (1 - al) / a0;
        presLoC = 1 - Math.exp(-2 * Math.PI * PRES_LO_HZ / sr);
        presHiC = 1 - Math.exp(-2 * Math.PI * Math.min(PRES_HI_HZ, 0.4 * sr) / sr);
        peakRel = Math.exp(-1.0 / (PEAK_REL_S * sr));
        mgUp = 1 - Math.exp(-1.0 / (MG_UP_S * sr));
    }

    /** one sample of the voice mix (about +-1) to the soft-clipped output in [-1, 1] */
    public double process(double x) {
        return process(x, 1);
    }

    /**
     * process() with up to makeup x of upward gain after the drive: a peak follower on |y| (instant attack, 200 ms
     * release) sets the gain to TPK / peak (times the crest correction, see CREST_REF), at least 1 and at most
     * makeup; the applied gain rises towards it in
     * 100 ms and drops to it at once (0.2.7 first had 1 ms, which lifted pop onsets into the hard limit), so quiet
     * sound is lifted towards TPK while loud sound (peaks at or above TPK) is left as it is: still |result| &lt;= 1,
     * and no more hard limiting on loud sound. makeup 1: exactly process(x).
     */
    public double process(double x, double makeup) {
        double y = filter(x) * DRIVE;
        double ay = Math.abs(y);
        peak = ay > peak ? ay : peak * peakRel;
        ms += (y * y - ms) * (1 - peakRel);
        double tg = 1;
        if (makeup > 1) {
            tg = makeup;
            if (peak > 0) {
                double rms = Math.sqrt(ms), crestFix = rms > 0 ? peak / rms / CREST_REF : 1;
                crestFix = crestFix < CREST_FIX_LO ? CREST_FIX_LO : crestFix > CREST_FIX_HI ? CREST_FIX_HI : crestFix;
                tg = Math.min(tg, TPK / peak * crestFix);
            }
            if (tg < 1) tg = 1;
        }
        mg = tg < mg ? tg : mg + (tg - mg) * mgUp; // down at once: an onset is never lifted past TPK
        y *= mg;
        if (y > 3) {
            y = 3;
            clamped++;
        } else if (y < -3) {
            y = -3;
            clamped++;
        }
        double y2 = y * y;
        return y * (27 + y2) / (27 + 9 * y2);
    }

    /**
     * RMS of a loop (full scale 1) through the stage's filters only (the high-pass and the presence band, no drive
     * or clip): how loud it is on the speaker. The loop is run twice and the second pass measured, so the filters
     * have settled into its cycle.
     */
    static double speakerRms(short[] pcm, int sampleRate) {
        if (pcm.length == 0) return 0;
        OutputStage s = new OutputStage(sampleRate);
        double ss = 0;
        for (int pass = 0; pass < 2; pass++)
            for (short v : pcm) {
                double y = s.filter(v / 32768.0);
                if (pass == 1) ss += y * y;
            }
        return Math.sqrt(ss / pcm.length);
    }

    /** the stage's linear part: the 120 Hz high-pass and the +11 dB presence band (before the drive and the clip) */
    double filter(double x) {
        double y = hb0 * x + hb1 * hx1 + hb2 * hx2 - ha1 * hy1 - ha2 * hy2;
        hx2 = hx1;
        hx1 = x;
        hy2 = hy1;
        hy1 = y;
        presLo += (y - presLo) * presLoC;
        presHi += (y - presHi) * presHiC;
        return y + PRES_K * (presHi - presLo);
    }

    /** samples that reached the hard limit (|y| &gt; 3 into the soft clip) since construction */
    public long clamped() {
        return clamped;
    }

    /** flush denormals / tiny states (call once per block) */
    public void flush() {
        if (Math.abs(hy1) < 1e-20) hy1 = 0;
        if (Math.abs(hy2) < 1e-20) hy2 = 0;
        if (Math.abs(presLo) < 1e-20) presLo = 0;
        if (Math.abs(presHi) < 1e-20) presHi = 0;
        if (peak < 1e-20) peak = 0;
        if (ms < 1e-30) ms = 0;
    }
}
