package ves.resynth;

/**
 * 1/3-octave A/B distance: both signals are cut into 100 ms frames (Hann, FFT 4096 at 22.05 kHz); per frame the
 * power in each 1/3-octave band from 100 Hz to 6.3 kHz (centres 10^(i/10)) in dB; the distance is the mean over
 * frames and bands of |level a - level b|. Frames where both are silent (below -70 dB) are skipped.
 */
public final class ThirdOctave {
    private ThirdOctave() {
    }

    /** frame length in samples at 22050 Hz (100 ms) */
    public static final int FRAME = 2205;
    static final int B0 = 20, B1 = 38;

    /** [frame][band] dB */
    public static double[][] levels(double[] x, int sr) {
        int frame = (int) Math.round(0.1 * sr), nfft = Fft.nextPow2(frame) * 2;
        int frames = x.length / frame, bands = B1 - B0 + 1;
        double[][] out = new double[frames][bands];
        double[] w = Fft.hann(frame), re = new double[nfft], im = new double[nfft];
        double norm = 0;
        for (double v : w) norm += v * v;
        for (int k = 0; k < frames; k++) {
            java.util.Arrays.fill(re, 0);
            java.util.Arrays.fill(im, 0);
            for (int i = 0; i < frame; i++) re[i] = x[k * frame + i] * w[i];
            Fft.fft(re, im, false);
            double[] p = new double[bands];
            for (int b = 1; b < nfft / 2; b++) {
                double f = b * sr / (double) nfft;
                int bd = (int) Math.round(10 * Math.log10(f)) - B0;
                if (bd < 0 || bd >= bands) continue;
                p[bd] += 2 * (re[b] * re[b] + im[b] * im[b]) / (norm * nfft); // band power (Parseval)
            }
            for (int bd = 0; bd < bands; bd++) out[k][bd] = 10 * Math.log10(p[bd] + 1e-12);
        }
        return out;
    }

    /** mean |difference| in dB over the frames (all, or those marked) and bands */
    public static double distance(double[] a, double[] b, int sr, boolean[] frames) {
        double[][] la = levels(a, sr), lb = levels(b, sr);
        int n = Math.min(la.length, lb.length);
        double s = 0;
        long c = 0;
        for (int k = 0; k < n; k++) {
            if (frames != null && (k >= frames.length || !frames[k])) continue;
            double ta = 0, tb = 0;
            for (int bd = 0; bd < la[k].length; bd++) {
                ta += Math.pow(10, la[k][bd] / 10);
                tb += Math.pow(10, lb[k][bd] / 10);
            }
            if (10 * Math.log10(ta + 1e-30) < -70 && 10 * Math.log10(tb + 1e-30) < -70) continue;
            for (int bd = 0; bd < la[k].length; bd++) {
                s += Math.abs(la[k][bd] - lb[k][bd]);
                c++;
            }
        }
        return c > 0 ? s / c : Double.NaN;
    }
}
