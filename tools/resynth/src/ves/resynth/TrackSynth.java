package ves.resynth;

import java.util.Random;

/**
 * Model-direct resynthesis along a recorded track, for A/B against the recording: orders from 20 Hz to the cap
 * at the model's levels, on the track's own crank phase (so the orders sit where the recording's do), the
 * on-load layer where the segments say on (or excluded), the off-load layer (from offModel when given) where
 * they say off; plus the model's noise per 1/3-octave band (overlap-added sqrt-Hann frames of shaped Gaussian
 * noise, variance kept). Amplitudes are linear between 64-sample blocks, the phase increment constant within one.
 */
public final class TrackSynth {
    private TrackSynth() {
    }

    static final int BLOCK = 64, NOISE = 1024;

    static Model.Layer layer(Model m, Model offModel, Segments sg, double t) {
        if (sg != null && sg.labelAt(t) == Segments.OFF) {
            Model.Layer o = offModel != null && offModel.off != null ? offModel.off : m.off;
            if (o != null) return o;
        }
        return m.on;
    }

    public static double[] render(Model m, Model offModel, Track tr, Segments sg, int samples, int sr, long seed) {
        double[] y = new double[samples];
        int nMax = (int) Math.ceil(PackRenderer.CAP / 3.0);
        double[] ph = PackRenderer.randomPhases(m.seed, nMax + 2);
        double[] phase = new double[nMax + 1];
        for (int n = 1; n <= nMax; n++) phase[n] = m.phaseOf(n, ph);
        double[] a0 = new double[nMax + 1], a1 = new double[nMax + 1];
        amps(m, offModel, tr, sg, 0, a0, nMax);
        for (int s0 = 0; s0 < samples; s0 += BLOCK) {
            int len = Math.min(BLOCK, samples - s0);
            double t0 = s0 / (double) sr, t1 = (s0 + len) / (double) sr;
            amps(m, offModel, tr, sg, t1, a1, nMax);
            double th0 = tr.thetaAt(t0), th1 = tr.thetaAt(t1);
            double inc = (th1 - th0) / len;
            for (int n = 1; n <= nMax; n++) {
                if (a0[n] == 0 && a1[n] == 0) continue;
                double p0 = 2 * Math.PI * n * th0 + phase[n], dp = 2 * Math.PI * n * inc;
                double cr = Math.cos(p0), ci = Math.sin(p0), rr = Math.cos(dp), ri = Math.sin(dp);
                for (int i = 0; i < len; i++) {
                    double a = a0[n] + (a1[n] - a0[n]) * (i / (double) len);
                    y[s0 + i] += a * cr;
                    double nr = cr * rr - ci * ri;
                    ci = cr * ri + ci * rr;
                    cr = nr;
                }
            }
            double[] t = a0;
            a0 = a1;
            a1 = t;
        }
        addNoise(y, m, offModel, tr, sg, sr, seed);
        return y;
    }

    /** linear amplitudes of the orders at time t (0 outside 20 Hz .. cap or absent) */
    static void amps(Model m, Model offModel, Track tr, Segments sg, double t, double[] a, int nMax) {
        Model.Layer l = layer(m, offModel, sg, t);
        double rpm = tr.rpmAt(t), fc = rpm / 120;
        for (int n = 1; n <= nMax; n++) {
            double f = n * fc;
            if (f < 20 || f > PackRenderer.CAP) {
                a[n] = 0;
                continue;
            }
            double d = l.predict(n, rpm);
            a[n] = Double.isNaN(d) ? 0 : Math.pow(10, d / 20);
        }
    }

    static void addNoise(double[] y, Model m, Model offModel, Track tr, Segments sg, int sr, long seed) {
        Random r = new Random(seed);
        int hop = NOISE / 2;
        double[] w = new double[NOISE];
        for (int i = 0; i < NOISE; i++) w[i] = Math.sqrt(0.5 - 0.5 * Math.cos(2 * Math.PI * i / NOISE));
        double[] re = new double[NOISE], im = new double[NOISE];
        double df = sr / (double) NOISE;
        for (int s0 = -hop; s0 < y.length; s0 += hop) {
            double t = (s0 + hop) / (double) sr;
            Model.Layer l = layer(m, offModel, sg, Math.max(0, t));
            double rpm = tr.rpmAt(Math.max(0, t));
            java.util.Arrays.fill(re, 0);
            java.util.Arrays.fill(im, 0);
            for (int b = 1; b < NOISE / 2; b++) {
                double f = b * df;
                if (f > PackRenderer.CAP) break;
                int band = Orders.band(f);
                if (band < 0) band = f < Orders.bandCentre(0) ? 0 : Orders.BANDS - 1;
                double psd = Math.pow(10, l.noise(band, rpm) / 10);
                double sd = Math.sqrt(NOISE * (double) NOISE * psd * df / 4);
                re[b] = sd * r.nextGaussian();
                im[b] = sd * r.nextGaussian();
                re[NOISE - b] = re[b];
                im[NOISE - b] = -im[b];
            }
            Fft.fft(re, im, true);
            for (int i = 0; i < NOISE; i++) {
                int k = s0 + i;
                if (k >= 0 && k < y.length) y[k] += re[i] / NOISE * w[i];
            }
        }
    }
}
