package ves.resynth;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Complex FFT: in-place radix-2 for powers of two, Bluestein (chirp z) for any other length. No scaling:
 * inverse(forward(x)) = n * x. Twiddles come from StrictMath, so results are the same on every JVM.
 */
final class Fft {
    private Fft() {
    }

    private static final ConcurrentHashMap<Integer, double[]> TW = new ConcurrentHashMap<Integer, double[]>();

    static boolean pow2(int n) {
        return n > 0 && (n & (n - 1)) == 0;
    }

    static int nextPow2(int n) {
        int m = 1;
        while (m < n) m <<= 1;
        return m;
    }

    /** cos and sin of 2 pi k / n, k < n / 2, interleaved */
    private static double[] twiddles(int n) {
        double[] t = TW.get(n);
        if (t == null) {
            t = new double[n];
            for (int k = 0; k < n / 2; k++) {
                double a = 2 * Math.PI * k / n;
                t[2 * k] = StrictMath.cos(a);
                t[2 * k + 1] = StrictMath.sin(a);
            }
            TW.put(n, t);
        }
        return t;
    }

    /** any length */
    static void dft(double[] re, double[] im, boolean inverse) {
        int n = re.length;
        if (pow2(n)) fft(re, im, inverse);
        else bluestein(re, im, inverse);
    }

    static void fft(double[] re, double[] im, boolean inverse) {
        int n = re.length;
        if (n <= 1) return;
        if (!pow2(n)) throw new IllegalArgumentException("fft size " + n);
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
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
        double[] tw = twiddles(n);
        double sg = inverse ? 1 : -1;
        for (int len = 2; len <= n; len <<= 1) {
            int half = len >> 1, step = n / len;
            for (int i = 0; i < n; i += len) {
                for (int k = 0; k < half; k++) {
                    double wr = tw[2 * k * step], wi = sg * tw[2 * k * step + 1];
                    int a = i + k, b = a + half;
                    double xr = re[b] * wr - im[b] * wi, xi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                }
            }
        }
    }

    private static void bluestein(double[] re, double[] im, boolean inverse) {
        int n = re.length, m = nextPow2(2 * n - 1);
        double[] cr = new double[n], ci = new double[n];
        double sg = inverse ? 1 : -1;
        long twoN = 2L * n;
        for (int k = 0; k < n; k++) {
            long q = ((long) k * k) % twoN;
            double a = Math.PI * q / n;
            cr[k] = StrictMath.cos(a);
            ci[k] = sg * StrictMath.sin(a);
        }
        double[] ar = new double[m], ai = new double[m], br = new double[m], bi = new double[m];
        for (int k = 0; k < n; k++) {
            ar[k] = re[k] * cr[k] - im[k] * ci[k];
            ai[k] = re[k] * ci[k] + im[k] * cr[k];
        }
        br[0] = cr[0];
        bi[0] = -ci[0];
        for (int k = 1; k < n; k++) {
            br[k] = br[m - k] = cr[k];
            bi[k] = bi[m - k] = -ci[k];
        }
        fft(ar, ai, false);
        fft(br, bi, false);
        for (int k = 0; k < m; k++) {
            double r = ar[k] * br[k] - ai[k] * bi[k];
            ai[k] = ar[k] * bi[k] + ai[k] * br[k];
            ar[k] = r;
        }
        fft(ar, ai, true);
        for (int k = 0; k < n; k++) {
            double r = ar[k] / m, i = ai[k] / m;
            re[k] = r * cr[k] - i * ci[k];
            im[k] = r * ci[k] + i * cr[k];
        }
    }

    /** periodic Hann window of length n (exact zeros of the transform at +-2 bins and beyond for whole periods) */
    static double[] hann(int n) {
        double[] w = new double[n];
        for (int i = 0; i < n; i++) w[i] = 0.5 - 0.5 * StrictMath.cos(2 * Math.PI * i / n);
        return w;
    }
}
