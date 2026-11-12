package ves.resynth;

import java.util.Arrays;

/**
 * Fits a {@link Model.Layer} to order frames in one regularized least-squares pass: the unknowns s_n (observed
 * orders), h at 25 Hz nodes over the observed frequencies, a and b; normal equations, Cholesky. The model has
 * exact gauge freedoms (a constant moved between s, h and l) and a near one (a log-tilt: h + beta ln f,
 * s_n - beta ln n, b - beta), all fixed explicitly: mean s = 0, mean h = 0 (or, gauge 1, one s and one h node
 * pinned at 0) and no ln-n tilt in s. A small second-difference penalty keeps h smooth where data are thin.
 * Orders within 10 dB of the local floor are masked; an order masked in most frames is absent.
 */
public final class Fitter {
    private Fitter() {
    }

    public static final class Options {
        /** 0: mean s = 0 and mean h = 0; 1: first s and middle h node = 0 (same predictions) */
        public int gauge;
        /** highest frequency used (Hz) and lowest */
        public double cap = 8500, fLo = 20;
        /** orders are masked within this many dB of the floor */
        public double mask = 10;
        /** h smoothness: weight of a second difference, relative to the data rows per node */
        public double smooth = 0.1;
    }

    static final double STEP = 25;

    static boolean[] all(Orders.Frames fr, boolean[] use) {
        if (use != null) return use;
        boolean[] u = new boolean[fr.count()];
        Arrays.fill(u, true);
        return u;
    }

    static boolean unmasked(Orders.Frames fr, int k, int n, double mask) {
        float a = fr.amp[k][n];
        if (Float.isNaN(a)) return false;
        float fl = fr.floor == null ? Float.NaN : fr.floor[k][n];
        return Float.isNaN(fl) || a >= fl + mask;
    }

    public static Model.Layer fit(Orders.Frames fr, boolean[] use, int cyl, Options o) {
        use = all(fr, use);
        int maxN = fr.maxOrder;
        int[] inBand = new int[maxN + 1], used = new int[maxN + 1];
        double fMin = Double.POSITIVE_INFINITY, fMax = 0, lnSum = 0, rMin = Double.POSITIVE_INFINITY, rMax = 0;
        int rows = 0;
        for (int k = 0; k < fr.count(); k++) {
            if (!use[k]) continue;
            double r = fr.rpm[k];
            rMin = Math.min(rMin, r);
            rMax = Math.max(rMax, r);
            for (int n = 1; n <= maxN; n++) {
                double f = n * r / 120;
                if (f < o.fLo || f > o.cap || Float.isNaN(fr.amp[k][n])) continue;
                inBand[n]++;
                if (unmasked(fr, k, n, o.mask)) used[n]++;
            }
        }
        Model.Layer L = new Model.Layer();
        L.cyl = cyl;
        L.rMin = rMin;
        L.rMax = rMax;
        int nObs = 0;
        for (int n = 1; n <= maxN; n++) if (inBand[n] > 0) nObs = n;
        L.nObs = nObs;
        L.s = new double[nObs + 1];
        L.absent = new boolean[nObs + 1];
        Arrays.fill(L.s, Double.NaN);
        int[] sIdx = new int[nObs + 1];
        Arrays.fill(sIdx, -1);
        int ns = 0;
        for (int n = 1; n <= nObs; n++) {
            if (inBand[n] >= 3 && used[n] < 0.5 * inBand[n]) L.absent[n] = true;
            else if (used[n] >= 3) sIdx[n] = ns++;
        }
        for (int k = 0; k < fr.count(); k++) {
            if (!use[k]) continue;
            for (int n = 1; n <= nObs; n++) {
                if (sIdx[n] < 0) continue;
                double f = n * fr.rpm[k] / 120;
                if (f < o.fLo || f > o.cap || !unmasked(fr, k, n, o.mask)) continue;
                fMin = Math.min(fMin, f);
                fMax = Math.max(fMax, f);
                lnSum += Math.log(fr.rpm[k]);
                rows++;
            }
        }
        if (rows == 0) throw new IllegalArgumentException("no unmasked orders to fit");
        L.rRef = Math.exp(lnSum / rows);
        L.fLo = Math.floor(fMin / STEP) * STEP;
        int nh = (int) Math.ceil((fMax - L.fLo) / STEP) + 1;
        L.step = STEP;
        int ia = ns + nh, ib = ia + 1, N = ib + 1;
        double[][] A = new double[N][N];
        double[] rhs = new double[N];
        int[] idx = new int[5];
        double[] val = new double[5];
        for (int k = 0; k < fr.count(); k++) {
            if (!use[k]) continue;
            double r = fr.rpm[k], x = Math.log(r / L.rRef);
            for (int n = 1; n <= nObs; n++) {
                if (sIdx[n] < 0) continue;
                double f = n * r / 120;
                if (f < o.fLo || f > o.cap || !unmasked(fr, k, n, o.mask)) continue;
                double p = (f - L.fLo) / STEP;
                int j = Math.min(nh - 2, (int) p);
                double al = p - j;
                idx[0] = sIdx[n];
                val[0] = 1;
                idx[1] = ns + j;
                val[1] = 1 - al;
                idx[2] = ns + j + 1;
                val[2] = al;
                idx[3] = ia;
                val[3] = 1;
                idx[4] = ib;
                val[4] = x;
                double y = fr.amp[k][n];
                for (int u = 0; u < 5; u++) {
                    rhs[idx[u]] += val[u] * y;
                    for (int v = 0; v < 5; v++) A[idx[u]][idx[v]] += val[u] * val[v];
                }
            }
        }
        // h smoothness (invariant to the gauges: constant and linear trends cost nothing)
        double lam = o.smooth * rows / (double) nh;
        for (int j = 1; j < nh - 1; j++) {
            int[] q = {ns + j - 1, ns + j, ns + j + 1};
            double[] c = {1, -2, 1};
            for (int u = 0; u < 3; u++) for (int v = 0; v < 3; v++) A[q[u]][q[v]] += lam * c[u] * c[v];
        }
        double mu = 0;
        for (int i = 0; i < N; i++) mu = Math.max(mu, A[i][i]);
        mu *= 1e3;
        double[] c1 = new double[N], c2 = new double[N], c3 = new double[N];
        double meanLn = 0;
        for (int n = 1; n <= nObs; n++) if (sIdx[n] >= 0) meanLn += Math.log(n) / ns;
        for (int n = 1; n <= nObs; n++) if (sIdx[n] >= 0) c3[sIdx[n]] = Math.log(n) - meanLn;
        if (o.gauge == 1) {
            c1[0] = 1;
            c2[ns + nh / 2] = 1;
        } else {
            for (int i = 0; i < ns; i++) c1[i] = 1.0 / ns;
            for (int j = 0; j < nh; j++) c2[ns + j] = 1.0 / nh;
        }
        for (double[] c : new double[][] {c1, c2, c3}) addOuter(A, c, mu);
        double[] sol = solve(A, rhs);
        for (int n = 1; n <= nObs; n++) if (sIdx[n] >= 0) L.s[n] = sol[sIdx[n]];
        L.h = Arrays.copyOfRange(sol, ns, ns + nh);
        L.a = sol[ia];
        L.b = sol[ib];
        tail(L, cyl);
        noise(L, fr, use);
        coherence(L, fr, use, o);
        halves(L, fr, use, o);
        return L;
    }

    static void addOuter(double[][] A, double[] c, double w) {
        int N = c.length;
        for (int i = 0; i < N; i++) {
            if (c[i] == 0) continue;
            for (int j = 0; j < N; j++) if (c[j] != 0) A[i][j] += w * c[i] * c[j];
        }
    }

    /** Cholesky solve of a symmetric positive definite system (A is overwritten) */
    static double[] solve(double[][] A, double[] b) {
        int n = b.length;
        for (int j = 0; j < n; j++) {
            double d = A[j][j];
            for (int k = 0; k < j; k++) d -= A[j][k] * A[j][k];
            if (!(d > 0)) throw new IllegalStateException("normal matrix not positive definite at " + j);
            d = Math.sqrt(d);
            A[j][j] = d;
            for (int i = j + 1; i < n; i++) {
                double s = A[i][j];
                for (int k = 0; k < j; k++) s -= A[i][k] * A[j][k];
                A[i][j] = s / d;
            }
        }
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            double s = b[i];
            for (int k = 0; k < i; k++) s -= A[i][k] * y[k];
            y[i] = s / A[i][i];
        }
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double s = y[i];
            for (int k = i + 1; k < n; k++) s -= A[k][i] * x[k];
            x[i] = s / A[i][i];
        }
        return x;
    }

    /**
     * s tail: linear in ln n over the last observed octave, plus the mean residual of n mod cyl (NaN: that class
     * is absent there)
     */
    static void tail(Model.Layer L, int cyl) {
        int top = 0;
        for (int n = 1; n <= L.nObs; n++) if (!Double.isNaN(L.s[n])) top = n;
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        int c = 0;
        for (int n = Math.max(1, top / 2); n <= top; n++) {
            if (Double.isNaN(L.s[n])) continue;
            double x = Math.log(n);
            sx += x;
            sy += L.s[n];
            sxx += x * x;
            sxy += x * L.s[n];
            c++;
        }
        double den = c * sxx - sx * sx;
        L.tailB = c > 1 && den > 0 ? (c * sxy - sx * sy) / den : 0;
        L.tailA = c > 0 ? (sy - L.tailB * sx) / c : -60;
        L.tailRes = new double[Math.max(1, cyl)];
        int[] cnt = new int[L.tailRes.length];
        for (int n = Math.max(1, top / 2); n <= top; n++) {
            if (Double.isNaN(L.s[n])) continue;
            int k = n % L.tailRes.length;
            L.tailRes[k] += L.s[n] - (L.tailA + L.tailB * Math.log(n));
            cnt[k]++;
        }
        int[] gone = new int[cnt.length];
        for (int n = Math.max(1, top / 2); n <= L.nObs; n++) if (L.absent[n]) gone[n % gone.length]++;
        // a class of orders absent over the last octave stays absent above it (NaN)
        for (int k = 0; k < cnt.length; k++)
            L.tailRes[k] = gone[k] > cnt[k] ? Double.NaN : cnt[k] > 0 ? L.tailRes[k] / cnt[k] : 0;
    }

    /** log N(band, r) = q(band) + m ln(r / rRef): one slope m, pooled over the bands */
    static void noise(Model.Layer L, Orders.Frames fr, boolean[] use) {
        Arrays.fill(L.noiseQ, Double.NaN);
        if (fr.noise == null) return;
        int B = Orders.BANDS;
        double[] mx = new double[B], my = new double[B];
        int[] cnt = new int[B];
        for (int k = 0; k < fr.count(); k++) {
            if (!use[k]) continue;
            double x = Math.log(fr.rpm[k] / L.rRef);
            for (int b = 0; b < B; b++) {
                if (Float.isNaN(fr.noise[k][b])) continue;
                mx[b] += x;
                my[b] += fr.noise[k][b];
                cnt[b]++;
            }
        }
        for (int b = 0; b < B; b++) {
            if (cnt[b] == 0) continue;
            mx[b] /= cnt[b];
            my[b] /= cnt[b];
        }
        double sxy = 0, sxx = 0;
        for (int k = 0; k < fr.count(); k++) {
            if (!use[k]) continue;
            double x = Math.log(fr.rpm[k] / L.rRef);
            for (int b = 0; b < B; b++) {
                if (Float.isNaN(fr.noise[k][b])) continue;
                sxy += (x - mx[b]) * (fr.noise[k][b] - my[b]);
                sxx += (x - mx[b]) * (x - mx[b]);
            }
        }
        L.noiseM = sxx > 0 ? sxy / sxx : 0;
        for (int b = 0; b < B; b++) if (cnt[b] >= 3) L.noiseQ[b] = my[b] - L.noiseM * mx[b];
    }

    /** mean skirt ratio of the unmasked orders per band */
    static void coherence(Model.Layer L, Orders.Frames fr, boolean[] use, Options o) {
        Arrays.fill(L.coh, Double.NaN);
        if (fr.skirt == null) return;
        double[] sum = new double[Orders.BANDS];
        int[] cnt = new int[Orders.BANDS];
        for (int k = 0; k < fr.count(); k++) {
            if (!use[k]) continue;
            for (int n = 1; n <= fr.maxOrder; n++) {
                if (!unmasked(fr, k, n, o.mask) || Float.isNaN(fr.skirt[k][n])) continue;
                int b = Orders.band(n * fr.rpm[k] / 120);
                if (b < 0) continue;
                sum[b] += Math.min(1, fr.skirt[k][n]);
                cnt[b]++;
            }
        }
        for (int b = 0; b < Orders.BANDS; b++) if (cnt[b] > 0) L.coh[b] = sum[b] / cnt[b];
    }

    /** half orders whose mean level (the floor where masked) is above -30 dB re the strongest order */
    static void halves(Model.Layer L, Orders.Frames fr, boolean[] use, Options o) {
        L.halfRel = new double[fr.maxOrder + 1];
        Arrays.fill(L.halfRel, Double.NaN);
        if (fr.half == null) return;
        double[] sum = new double[fr.maxOrder + 1];
        int[] cnt = new int[fr.maxOrder + 1];
        for (int k = 0; k < fr.count(); k++) {
            if (!use[k]) continue;
            double top = Double.NEGATIVE_INFINITY;
            for (int n = 1; n <= fr.maxOrder; n++) if (!Float.isNaN(fr.amp[k][n])) top = Math.max(top, fr.amp[k][n]);
            for (int n = 0; n < fr.maxOrder; n++) {
                float hv = fr.half[k][n];
                if (Float.isNaN(hv) || (n + 0.5) * fr.rpm[k] / 120 > o.cap) continue;
                float fl = fr.floor == null ? Float.NaN : fr.floor[k][Math.max(1, n)];
                double v = !Float.isNaN(fl) && hv < fl + o.mask ? fl : hv;
                sum[n] += v - top;
                cnt[n]++;
            }
        }
        for (int n = 0; n < fr.maxOrder; n++) if (cnt[n] >= 3 && sum[n] / cnt[n] > -30) L.halfRel[n] = sum[n] / cnt[n];
    }

    /**
     * per-order phase from the low end of the given (on-load) frames: the lowest 30 % of their log-rpm span.
     * Returns {phase[], R[]} for n = 0..maxOrder (NaN where never unmasked).
     */
    public static double[][] phases(Orders.Frames fr, boolean[] use, double mask) {
        use = all(fr, use);
        double lo = Double.POSITIVE_INFINITY, hi = 0;
        for (int k = 0; k < fr.count(); k++)
            if (use[k]) {
                lo = Math.min(lo, fr.rpm[k]);
                hi = Math.max(hi, fr.rpm[k]);
            }
        double lim = Math.exp(Math.log(lo) + 0.3 * (Math.log(hi) - Math.log(lo)));
        int N = fr.maxOrder + 1;
        double[] re = new double[N], im = new double[N], ph = new double[N], R = new double[N];
        int[] cnt = new int[N];
        for (int k = 0; k < fr.count(); k++) {
            if (!use[k] || fr.rpm[k] > lim) continue;
            for (int n = 1; n < N; n++) {
                if (!unmasked(fr, k, n, mask) || fr.phase == null || Float.isNaN(fr.phase[k][n])) continue;
                re[n] += Math.cos(fr.phase[k][n]);
                im[n] += Math.sin(fr.phase[k][n]);
                cnt[n]++;
            }
        }
        for (int n = 0; n < N; n++) {
            ph[n] = cnt[n] > 0 ? Math.atan2(im[n], re[n]) : Double.NaN;
            R[n] = cnt[n] > 0 ? Math.hypot(re[n], im[n]) / cnt[n] : 0;
        }
        return new double[][] {ph, R};
    }

    // ------------------------------------------------------------------ M0: the held envelope

    /** baseline M0: the order envelope over frequency at the lowest measured rpm, held in Hz, level scaled */
    public static final class Baseline {
        double r0, g, fLo;
        double[] env;

        public double predict(double n, double rpm) {
            double f = n * rpm / 120, p = (f - fLo) / STEP;
            double e;
            if (p <= 0) e = env[0];
            else {
                int j = (int) p;
                e = j >= env.length - 1 ? env[env.length - 1] : env[j] + (env[j + 1] - env[j]) * (p - j);
            }
            return e + g * Math.log(rpm / r0);
        }
    }

    public static Baseline fitM0(Orders.Frames fr, boolean[] use) {
        use = all(fr, use);
        Options o = new Options();
        int c = 0;
        for (boolean u : use) if (u) c++;
        double[] rs = new double[c];
        c = 0;
        for (int k = 0; k < fr.count(); k++) if (use[k]) rs[c++] = fr.rpm[k];
        Arrays.sort(rs);
        double lim = rs[Math.max(2, rs.length / 10) - 1];
        Baseline b = new Baseline();
        b.r0 = rs[Math.max(2, rs.length / 10) / 2];
        b.fLo = 0;
        int nodes = (int) (o.cap / STEP) + 2;
        double[] sum = new double[nodes];
        int[] cnt = new int[nodes];
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        int m = 0;
        for (int k = 0; k < fr.count(); k++) {
            if (!use[k]) continue;
            double tot = 0;
            for (int n = 1; n <= fr.maxOrder; n++) {
                if (!unmasked(fr, k, n, o.mask)) continue;
                double f = n * fr.rpm[k] / 120;
                if (f > o.cap) continue;
                tot += Math.pow(10, fr.amp[k][n] / 10);
                if (fr.rpm[k] <= lim) {
                    int j = (int) Math.round(f / STEP);
                    sum[j] += fr.amp[k][n];
                    cnt[j]++;
                }
            }
            if (tot > 0) {
                double x = Math.log(fr.rpm[k]), y = 10 * Math.log10(tot);
                sx += x;
                sy += y;
                sxx += x * x;
                sxy += x * y;
                m++;
            }
        }
        b.g = m > 1 ? (m * sxy - sx * sy) / (m * sxx - sx * sx) : 0;
        b.env = new double[nodes];
        int first = -1, last = -1;
        for (int j = 0; j < nodes; j++)
            if (cnt[j] > 0) {
                b.env[j] = sum[j] / cnt[j];
                if (first < 0) first = j;
                last = j;
            }
        for (int j = 0; j < nodes; j++) {
            if (cnt[j] > 0) continue;
            if (j < first) b.env[j] = b.env[first];
            else if (j > last) b.env[j] = b.env[last];
            else {
                int a = j - 1, z = j + 1;
                while (cnt[z] == 0) z++;
                b.env[j] = b.env[a] + (b.env[z] - b.env[a]) / (z - a);
            }
        }
        return b;
    }
}
