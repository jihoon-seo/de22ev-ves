package ves.resynth;

/**
 * Time to crank-angle resampling (computed order tracking): y[j] = x at crank phase theta = c0 + j / S, from
 * the per-sample phase theta[i] (cycles, strictly rising). Band-limited interpolation with a Kaiser-windowed
 * sinc; where the angle grid is coarser than the samples (S * fc below the sample rate) the kernel widens into
 * an anti-alias low pass at S * fc / 2.
 */
final class Angle {
    private Angle() {
    }

    static final int HW = 16, RES = 512;
    private static final double[] PROTO = proto();

    private static double[] proto() {
        double[] p = new double[HW * RES + 2];
        double i0 = Resampler.bessel0(8.0);
        for (int k = 0; k < p.length; k++) {
            double v = k / (double) RES;
            double r = v / HW;
            double w = r >= 1 ? 0 : Resampler.bessel0(8.0 * Math.sqrt(1 - r * r)) / i0;
            p[k] = (v == 0 ? 1 : StrictMath.sin(Math.PI * v) / (Math.PI * v)) * w;
        }
        return p;
    }

    static final class Signal {
        final double[] y;
        final int s;
        /** crank cycle of y[0] (an integer) */
        final long c0;
        /** sample position (fractional index into x) of crank phase c0 + c, c = 0..cycles */
        final double[] tauCycle;

        Signal(double[] y, int s, long c0, double[] tauCycle) {
            this.y = y;
            this.s = s;
            this.c0 = c0;
            this.tauCycle = tauCycle;
        }

        int cycles() {
            return y.length / s;
        }

        /** sample position of a fractional cycle c (relative to c0) */
        double tau(double c) {
            if (c <= 0) return tauCycle[0];
            int k = (int) c;
            if (k >= tauCycle.length - 1) return tauCycle[tauCycle.length - 1];
            return tauCycle[k] + (tauCycle[k + 1] - tauCycle[k]) * (c - k);
        }
    }

    static Signal resample(double[] x, double[] theta, int s) {
        int n = Math.min(x.length, theta.length);
        long c0 = (long) Math.ceil(theta[0]);
        double last = theta[n - 1];
        int cycles = (int) Math.floor(last - c0);
        if (cycles < 1) return new Signal(new double[0], s, c0, new double[] {0});
        double[] y = new double[cycles * s];
        double[] tc = new double[cycles + 1];
        int i = 0;
        for (int j = 0; j <= cycles * s; j++) {
            double th = c0 + j / (double) s;
            while (i < n - 2 && theta[i + 1] <= th) i++;
            double d = theta[i + 1] - theta[i];
            double tau = i + (d > 0 ? (th - theta[i]) / d : 0);
            if (j % s == 0) tc[j / s] = tau;
            if (j == cycles * s) break;
            double rho = Math.min(1.0, s * d); // angle samples per time sample
            y[j] = interp(x, tau, rho);
        }
        return new Signal(y, s, c0, tc);
    }

    /** x at fractional index tau, low-passed at rho * Nyquist */
    static double interp(double[] x, double tau, double rho) {
        double reach = HW / rho;
        int k0 = (int) Math.ceil(tau - reach), k1 = (int) Math.floor(tau + reach);
        if (k0 < 0) k0 = 0;
        if (k1 > x.length - 1) k1 = x.length - 1;
        double acc = 0;
        double sc = rho * RES;
        for (int k = k0; k <= k1; k++) {
            double v = Math.abs(tau - k) * sc;
            int q = (int) v;
            if (q >= HW * RES) continue;
            double f = v - q;
            acc += x[k] * (PROTO[q] + (PROTO[q + 1] - PROTO[q]) * f);
        }
        return acc * rho;
    }
}
