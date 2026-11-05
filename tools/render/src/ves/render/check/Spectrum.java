/*
 * ves.render.check: analysis helpers for tests/render/run.sh (host only).
 * Part of the Java port of DasEtwas/enginesound <https://github.com/DasEtwas/enginesound>,
 * upstream commit e5fcca587397c0c8ba9c9d24874b951fed74d260 (this file is new code, no upstream source).
 * Copyright (c) 2020 DasEtwas. MIT License; the full notice is in THIRD_PARTY.md.
 * The Java port and its throttle extension are MIT as well.
 */
package ves.render.check;

/** Goertzel, a radix-2 FFT, RMS, spectral centroid. Double precision, no dependencies. */
public final class Spectrum {
    private Spectrum() {
    }

    /** |DFT| of x at frequency f (Hz), normalised so a full-scale sine reads ~0.5. */
    public static double goertzel(float[] x, double f, int sr) {
        double w = 2.0 * Math.PI * f / sr;
        double c = 2.0 * Math.cos(w);
        double s1 = 0;
        double s2 = 0;
        for (float v : x) {
            double s0 = v + c * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        double re = s1 - s2 * Math.cos(w);
        double im = s2 * Math.sin(w);
        return Math.sqrt(re * re + im * im) / x.length;
    }

    public static double rms(float[] x) {
        double s = 0;
        for (float v : x) {
            s += (double) v * v;
        }
        return Math.sqrt(s / Math.max(1, x.length));
    }

    public static double db(double lin) {
        return 20.0 * Math.log10(Math.max(lin, 1e-12));
    }

    /** Magnitude spectrum (bins 0..n/2) of a Hann-windowed segment of 2^k <= x.length samples. */
    public static double[] magnitudes(float[] x, int start) {
        int n = Integer.highestOneBit(Math.max(2, Math.min(x.length - start, 1 << 16)));
        double[] re = new double[n];
        double[] im = new double[n];
        for (int i = 0; i < n; i++) {
            double w = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / n);
            re[i] = x[start + i] * w;
        }
        fft(re, im);
        double[] m = new double[n / 2 + 1];
        for (int i = 0; i <= n / 2; i++) {
            m[i] = Math.hypot(re[i], im[i]) * 4.0 / n; // Hann gain 0.5, one-sided x2
        }
        return m;
    }

    /** spectral centroid (Hz) of magnitudes between fLo and the Nyquist frequency */
    public static double centroid(double[] mag, int sr, double fLo) {
        int n = (mag.length - 1) * 2;
        double num = 0;
        double den = 0;
        for (int k = 1; k < mag.length; k++) {
            double f = (double) k * sr / n;
            if (f < fLo) {
                continue;
            }
            num += f * mag[k];
            den += mag[k];
        }
        return den > 0 ? num / den : 0;
    }

    /** in-place iterative radix-2 FFT, re.length must be a power of two */
    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            double wr = Math.cos(ang);
            double wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1;
                double ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k;
                    int b = a + len / 2;
                    double xr = re[b] * cr - im[b] * ci;
                    double xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    double t = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = t;
                }
            }
        }
    }
}
