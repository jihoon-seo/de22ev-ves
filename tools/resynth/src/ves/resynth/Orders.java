package ves.resynth;

/**
 * Computed order tracking: the signal is resampled to crank angle (fc(t) integrated, S = 1024 samples per engine
 * cycle), then FFT'd over K = 4 whole cycles with a periodic Hann window, one cycle apart. Order n lands on bin
 * 4n, half order n + 1/2 on bin 4n + 2; a whole-cycle Hann has exact zeros there for every other order, so the
 * bins between carry only noise and jitter (crank wobble). Per frame and order: level (dB re amplitude 1),
 * phase (radians, crank phase 0 = the track's integer cycles), the half order above it, the local floor
 * (median of the half-order bins within +-3 orders, same dB scale), the skirt (power in the +-1 bins beyond the
 * window's own leakage, over the order's power) and, per 1/3-octave band, the noise PSD from the half-order bins
 * (dB re amplitude^2 / Hz, one-sided).
 */
public final class Orders {
    private Orders() {
    }

    public static final int S = 1024, K = 4;
    /** 1/3-octave band centres 10^(i/10) Hz, i = BAND0 .. BAND1 (50 Hz .. 7.9 kHz) */
    public static final int BAND0 = 17, BAND1 = 39, BANDS = BAND1 - BAND0 + 1;

    public static double bandCentre(int b) {
        return Math.pow(10, (BAND0 + b) / 10.0);
    }

    /** the band of a frequency, -1 outside */
    public static int band(double f) {
        if (f <= 0) return -1;
        int b = (int) Math.round(10 * Math.log10(f)) - BAND0;
        return b < 0 || b >= BANDS ? -1 : b;
    }

    public static final class Frames {
        /** frame centre time (s) and rpm there */
        public double[] time, rpm;
        /** orders 1..maxOrder; index 0 unused (half[k][0] is order 0.5) */
        public int maxOrder;
        /** dB re amplitude 1; NaN where the order is above fmax or the band limit */
        public float[][] amp, phase, half, floor, skirt;
        /** [frame][band] noise PSD dB re 1/Hz; NaN where no half-order bin falls in the band */
        public float[][] noise;

        public int count() {
            return time == null ? 0 : time.length;
        }
    }

    public static Frames analyse(double[] x, int sr, Track tr, double fmax) {
        int n = x.length;
        double[] th = tr.thetaSamples(n, sr);
        Angle.Signal a = Angle.resample(x, th, S);
        int cyc = a.cycles(), frames = Math.max(0, cyc - K + 1), len = S * K;
        double fcMin = Double.POSITIVE_INFINITY;
        for (double v : tr.fc) if (v > 0) fcMin = Math.min(fcMin, v);
        int maxOrder = (int) Math.min(S / 2 - 2, Math.ceil(Math.min(fmax, 0.45 * sr) / fcMin));
        Frames fr = new Frames();
        fr.maxOrder = maxOrder;
        fr.time = new double[frames];
        fr.rpm = new double[frames];
        fr.amp = new float[frames][maxOrder + 1];
        fr.phase = new float[frames][maxOrder + 1];
        fr.half = new float[frames][maxOrder + 1];
        fr.floor = new float[frames][maxOrder + 1];
        fr.skirt = new float[frames][maxOrder + 1];
        fr.noise = new float[frames][BANDS];
        double[] w = Fft.hann(len), re = new double[len], im = new double[len];
        double wsum = len / 2.0, wsq = 3.0 * len / 8.0;
        double scale = 2 / wsum; // |X| -> amplitude of a cosine on the bin
        double[] p = new double[len / 2];
        double[] hp = new double[maxOrder + 1];
        double[] bandSum = new double[BANDS];
        int[] bandCnt = new int[BANDS];
        for (int c = 0; c < frames; c++) {
            for (int i = 0; i < len; i++) {
                re[i] = a.y[c * S + i] * w[i];
                im[i] = 0;
            }
            Fft.fft(re, im, false);
            for (int b = 0; b < p.length; b++) p[b] = (re[b] * re[b] + im[b] * im[b]) * scale * scale;
            double t = a.tau(c + K / 2.0) / sr;
            double fc = tr.fcAt(t);
            fr.time[c] = t;
            fr.rpm[c] = 120 * fc;
            double lim = Math.min(fmax, 0.45 * sr);
            for (int o = 0; o <= maxOrder; o++) hp[o] = K * o + 2 < p.length ? p[K * o + 2] : 0;
            java.util.Arrays.fill(bandSum, 0);
            java.util.Arrays.fill(bandCnt, 0);
            double fsA = S * fc, toPsd = 2 / (fsA * wsq) / (scale * scale);
            for (int o = 0; o <= maxOrder; o++) {
                int bd = band((o + 0.5) * fc);
                if (bd >= 0 && (o + 0.5) * fc < lim) {
                    bandSum[bd] += hp[o] * toPsd;
                    bandCnt[bd]++;
                }
            }
            for (int bd = 0; bd < BANDS; bd++)
                fr.noise[c][bd] = bandCnt[bd] > 0 ? (float) (10 * Math.log10(bandSum[bd] / bandCnt[bd] + 1e-30)) : Float.NaN;
            double[] med = new double[7];
            for (int o = 0; o <= maxOrder; o++) {
                boolean in = (o + 0.5) * fc < lim;
                fr.half[c][o] = in ? (float) db(hp[o]) : Float.NaN;
                if (o == 0) {
                    fr.amp[c][0] = fr.phase[c][0] = fr.floor[c][0] = fr.skirt[c][0] = Float.NaN;
                    continue;
                }
                if (o * fc >= lim || K * o + 1 >= p.length) {
                    fr.amp[c][o] = fr.phase[c][o] = fr.floor[c][o] = fr.skirt[c][o] = Float.NaN;
                    continue;
                }
                int m = 0;
                for (int q = o - 3; q <= o + 3; q++) if (q >= 0 && q <= maxOrder && (q + 0.5) * fc < lim) med[m++] = hp[q];
                java.util.Arrays.sort(med, 0, m);
                double floorP = m > 0 ? med[m / 2] : 0;
                double po = p[K * o];
                fr.amp[c][o] = (float) db(po);
                fr.phase[c][o] = (float) Math.atan2(im[K * o], re[K * o]);
                fr.floor[c][o] = (float) db(floorP);
                double sk = p[K * o - 1] + p[K * o + 1] - 0.5 * po - 2 * floorP;
                fr.skirt[c][o] = (float) (po > 0 ? Math.max(0, sk) / po : 0);
            }
        }
        return fr;
    }

    /** power (amplitude^2) to dB re amplitude 1 */
    static double db(double pw) {
        return 10 * Math.log10(pw + 1e-30);
    }
}
