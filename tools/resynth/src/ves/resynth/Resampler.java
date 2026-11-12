package ves.resynth;

/**
 * Rational sample-rate converter (polyphase, Kaiser-windowed sinc). For 48000 to 22050 Hz: L/M = 147/320, pass
 * band to 0.9 x the output Nyquist (9.9 kHz), stop band from the output Nyquist at about -90 dB. The time grid
 * is exact (output sample m sits at input time m * M / L), so a tone keeps its frequency.
 */
public final class Resampler {
    private Resampler() {
    }

    static final double BETA = 9.0, ATTEN_DB = 90;

    public static double[] resample(double[] x, int inRate, int outRate) {
        if (inRate <= 0 || outRate <= 0) throw new IllegalArgumentException("rates");
        if (inRate == outRate) return x.clone();
        int g = gcd(inRate, outRate), L = outRate / g, M = inRate / g;
        double nyq = 0.5 * Math.min(inRate, outRate);
        double cut = 0.95 * nyq / inRate; // cycles per input sample, -6 dB point
        double trans = 0.1 * nyq / inRate;
        int h = (int) Math.ceil((ATTEN_DB - 8) / (2.285 * 2 * Math.PI * trans) / 2) + 1; // half-width, input samples
        int taps = 2 * h;
        double[][] table = new double[L][taps];
        double i0b = bessel0(BETA);
        for (int p = 0; p < L; p++) {
            for (int j = 0; j < taps; j++) {
                double u = (h - 1 - j) + p / (double) L;
                double r = u / h;
                double w = Math.abs(r) >= 1 ? 0 : bessel0(BETA * Math.sqrt(1 - r * r)) / i0b;
                double a = 2 * cut * u;
                double s = a == 0 ? 1 : StrictMath.sin(Math.PI * a) / (Math.PI * a);
                table[p][j] = 2 * cut * s * w;
            }
        }
        int n = x.length;
        int outLen = n == 0 ? 0 : (int) (((long) (n - 1) * L) / M) + 1;
        double[] y = new double[outLen];
        for (int m = 0; m < outLen; m++) {
            long num = (long) m * M;
            int i = (int) (num / L), p = (int) (num % L);
            int k0 = i - h + 1;
            double[] t = table[p];
            double acc = 0;
            int j0 = Math.max(0, -k0), j1 = Math.min(taps, n - k0);
            for (int j = j0; j < j1; j++) acc += t[j] * x[k0 + j];
            y[m] = acc;
        }
        return y;
    }

    static double bessel0(double x) {
        double s = 1, t = 1, q = x * x / 4;
        for (int k = 1; k < 200; k++) {
            t *= q / ((double) k * k);
            s += t;
            if (t < 1e-17 * s) break;
        }
        return s;
    }

    static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }
}
