package ves.resynth;

import java.util.Arrays;

/**
 * Cycle-frequency tracker for engine recordings that are harmonic stacks over the engine cycle (fc = rpm / 120
 * for a 4-stroke) plus broadband noise.
 * <ol>
 * <li>Coarse comb: an STFT (about 93 ms Hann, 11.6 ms hop at 22.05 kHz), each bin's level above its local mean
 * (peakiness), a harmonic-sum score of the comb F, 2F, .. (mean over up to 20 harmonics below 4 kHz) for F on a
 * 0.2 % log grid, and a Viterbi path through the frames. Each step follows the spectral flow (the shift of the
 * log-frequency peak map between frames 93 ms apart, which moves rigidly with the rpm) within +-0.4 % where the
 * flow is sure, +-2 % where it is less sure, and +-5 % (the gate) where there is none: the path cannot drift
 * onto another multiple of the comb while the flow is sure.</li>
 * <li>Segments: runs of frames joined by sure flow carry one comb multiple each (an unsure stretch that could
 * have let the path slide 25 % splits them). Per segment: phase refinement on the comb (below, three passes,
 * phase steps averaged over 24 frames), then the subharmonic test in the angle domain (64-cycle frames, sweeps
 * do not smear): the finest comb F / d whose positions stand out from the midpoints between them gives fc.
 * The cylinder count (given, or the dominant order of fc found in at least a quarter of the time) corrects a
 * segment whose test found a coarser comb than its dominant order implies (short idle holds, weak in-between
 * orders). Segments under 0.25 s (shifts) take a neighbour's ratio if that keeps fc continuous, else fc is
 * interpolated.</li>
 * <li>Phase refinement: resample to crank angle, take the phase of the strongest orders in 4-cycle Hann frames
 * one cycle apart; the frame-to-frame phase change of order n is 2 pi n times the angle error (unwrapped from
 * the lowest order up); the summed error corrects theta. On fc, three passes over the whole file.</li>
 * </ol>
 */
public final class Tracker {
    private Tracker() {
    }

    public static final class Result {
        public Track track;
        /** in the longest segment: fc = comb / divisor (with a cylinder hint: the ratio rounded) */
        public int divisor;
        /** fc / comb in the longest segment */
        public double ratio;
        /** the cylinder count used (given, or estimated as the dominant order of fc) */
        public int cylinders;
        public final StringBuilder log = new StringBuilder();
    }

    static final double FMIN = 6, FMAX = 320, GRID = 1.002, GATE = 1.05, COMB_FMAX = 4000, PEAK_CLIP = 30;
    static final int HMAX = 20, DMAX = 24;
    /** spectral flow: log-frequency cross-correlation of peak maps FLOW_D hops apart, 40 Hz-4 kHz, up to 30 % */
    static final double FLOW_FMIN = 40, FLOW_MAX = 1.3, FLOW_GRID = 1.004, FLOW_PEAK = 6;
    static final int FLOW_D = 8;
    static final double FLOW_SURE = 0.7, FLOW_SOME = 0.25;
    /** segments shorter than this (s) take their comb ratio from a neighbour */
    static final double MIN_SEG = 0.25;
    /** the subharmonic test looks at harmonics up to this (Hz): higher ones may sink into the noise */
    static final double DIV_FMAX = 1200, DIV_ORDERS = 8;
    /**
     * the comb may be a multiple of fc: harmonics of fc between its orders rotate against the comb's own from one
     * frame to the next (by j / d of a turn) and leak into its bins; averaging the phase steps over 24 frames
     * cancels that for every d dividing 24
     */
    static final int SMOOTH_F = 24;
    /** cycles per analysis frame needed per step of the comb divisor (midpoints clear of the Hann main lobe) */
    static final int RESOLVE = 6;

    public static Result track(double[] x, int sr, int cylHint) {
        Result res = new Result();
        int n = x.length;
        int win = Fft.nextPow2((int) (0.093 * sr * 0.75)), hop = win / 8;
        double[] conf = new double[n / hop + 1];
        double[] f = coarse(x, sr, win, hop, conf);
        int frames = f.length;
        // segments: runs of frames joined by sure flow
        java.util.List<int[]> segs = new java.util.ArrayList<int[]>();
        int start = 0;
        // an unsure stretch may let the path slide onto another multiple of the comb (the nearest, 3/4 or 4/3,
        // is 29 % away): split where the stretch allows 25 %; single unsure hops do not split
        double slack = 0;
        for (int k = 1; k < frames; k++) {
            if (conf[k] >= FLOW_SURE) {
                slack = 0;
                continue;
            }
            slack += conf[k] >= FLOW_SOME ? 0.02 : GATE - 1;
            if (slack >= 0.25) {
                segs.add(new int[] {start, k - 1});
                while (k + 1 < frames && conf[k + 1] < FLOW_SURE) k++;
                start = k;
                slack = 0;
            }
        }
        segs.add(new int[] {start, frames - 1});
        double[] ratio = new double[frames];
        Arrays.fill(ratio, Double.NaN);
        int minFrames = (int) Math.ceil(MIN_SEG * sr / hop), longest = -1, longLen = 0;
        java.util.List<int[]> used = new java.util.ArrayList<int[]>();
        java.util.List<double[]> info = new java.util.ArrayList<double[]>(); // {dom, divisor, cycles per frame, lo, hi}
        for (int[] s : segs) {
            int len = s[1] - s[0] + 1;
            if (len < minFrames) continue;
            int i0 = s[0] * hop, i1 = Math.min(n, (s[1] + 1) * hop);
            double[] xs = Arrays.copyOfRange(x, i0, i1), fsg = Arrays.copyOfRange(f, s[0], s[1] + 1);
            double[] th = integrate(fsg, hop, xs.length, sr);
            double[] srt = fsg.clone();
            Arrays.sort(srt);
            double fLow = srt[srt.length / 20], fMed = srt[srt.length / 2];
            int sF = clampPow2(2.2 * COMB_FMAX / fLow, 16, 4096);
            for (int it = 0; it < 3; it++) refine(xs, sr, th, sF, 4, 6, COMB_FMAX, SMOOTH_F);
            PeakFn pf = peaks(xs, th, sF, 64);
            int dom = dominantOrder(pf, fMed), d = divisor(pf, sF, fMed);
            used.add(s);
            info.add(new double[] {dom, d, pf.m, srt[0], srt[srt.length - 1]});
            if (len > longLen) {
                longLen = len;
                longest = used.size() - 1;
            }
        }
        // the cylinder count: given, or the dominant order of fc in the longest segment. A segment too short to
        // resolve the divisor that count implies (RESOLVE cycles per frame for each step of d; in practice a short
        // idle hold) uses it instead: fc = dominant comb order / cylinders
        int cyl = cylHint > 0 ? cylHint : cylinders(used, info);
        for (int q = 0; q < used.size(); q++) {
            int[] s = used.get(q);
            double[] in = info.get(q);
            double r;
            String how;
            double dPrime = cyl / in[0];
            if (cyl > 0 && dPrime > in[1] && Math.abs(dPrime - Math.rint(dPrime)) < 1e-9) {
                // the comb test found a coarser comb than the cylinder count implies (short segment, or the
                // in-between orders weak here): fc = dominant comb order / cylinders
                r = in[0] / cyl;
                how = "comb test divisor " + (int) in[1] + " (" + (int) in[2] + " cycles per frame) below dominant order "
                        + (int) in[0] + " / " + cyl + " cylinders" + (cylHint > 0 ? "" : " (estimated)");
            } else {
                r = 1.0 / in[1];
                how = "finest comb: divisor " + (int) in[1] + ", dominant comb order " + (int) in[0];
            }
            for (int k = s[0]; k <= s[1]; k++) ratio[k] = r;
            res.log.append(String.format(java.util.Locale.ROOT, "segment %.2f-%.2f s: comb %.1f-%.1f Hz, %s, fc ratio %.4f%n",
                    s[0] * hop / (double) sr, s[1] * hop / (double) sr, in[3], in[4], how, r));
            if (q == longest) {
                res.ratio = r;
                res.divisor = (int) Math.round(1 / r);
            }
        }
        res.cylinders = cyl;
        if (longest < 0) { // nothing long enough: one segment over everything
            Arrays.fill(ratio, cylHint > 0 ? 1.0 / cylHint : 1.0);
            res.ratio = ratio[0];
            res.divisor = (int) Math.round(1 / ratio[0]);
        }
        double[] fc = new double[frames];
        for (int k = 0; k < frames; k++) fc[k] = f[k] * ratio[k];
        fillShort(f, ratio, fc);
        double[] th = integrate(fc, hop, n, sr);
        double[] srt = fc.clone();
        Arrays.sort(srt);
        int sC = clampPow2(2.2 * COMB_FMAX / srt[srt.length / 50], 16, 8192);
        for (int it = 0; it < 3; it++) refine(x, sr, th, sC, 4, 8, COMB_FMAX, 1);
        res.track = toTrack(th, hop, sr);
        return res;
    }

    /**
     * the dominant order of fc (dominant comb order x divisor) found in segments covering at least a quarter of
     * the analysed time; the largest such (a failed comb test only ever gives a coarser comb)
     */
    static int cylinders(java.util.List<int[]> used, java.util.List<double[]> info) {
        java.util.Map<Integer, Integer> dur = new java.util.TreeMap<Integer, Integer>();
        int total = 0;
        for (int q = 0; q < used.size(); q++) {
            int len = used.get(q)[1] - used.get(q)[0] + 1, c = (int) Math.round(info.get(q)[0] * info.get(q)[1]);
            Integer v = dur.get(c);
            dur.put(c, (v == null ? 0 : v) + len);
            total += len;
        }
        int best = 0;
        for (java.util.Map.Entry<Integer, Integer> e : dur.entrySet()) if (e.getValue() >= 0.25 * total) best = e.getKey();
        return best;
    }

    /**
     * frames without a ratio (short segments): the neighbour's ratio that keeps fc continuous at both ends
     * (within 25 %), else fc log-interpolated between the neighbours
     */
    static void fillShort(double[] f, double[] ratio, double[] fc) {
        int frames = f.length;
        int k = 0;
        while (k < frames) {
            if (!Double.isNaN(ratio[k])) {
                k++;
                continue;
            }
            int a = k;
            while (k < frames && Double.isNaN(ratio[k])) k++;
            int b = k - 1;
            double rp = a > 0 ? ratio[a - 1] : Double.NaN, rn = b < frames - 1 ? ratio[b + 1] : Double.NaN;
            double fp = a > 0 ? fc[a - 1] : Double.NaN, fn = b < frames - 1 ? fc[b + 1] : Double.NaN;
            double best = Double.POSITIVE_INFINITY, br = Double.NaN;
            for (double r : new double[] {rp, rn}) {
                if (Double.isNaN(r)) continue;
                double e = 0;
                if (!Double.isNaN(fp)) e = Math.max(e, Math.abs(Math.log(f[a] * r / fp)));
                if (!Double.isNaN(fn)) e = Math.max(e, Math.abs(Math.log(f[b] * r / fn)));
                if (e < best) {
                    best = e;
                    br = r;
                }
            }
            for (int j = a; j <= b; j++) {
                if (best <= Math.log(1.25)) fc[j] = f[j] * br;
                else if (!Double.isNaN(fp) && !Double.isNaN(fn))
                    fc[j] = Math.exp(Math.log(fp) + (Math.log(fn) - Math.log(fp)) * (j - a + 1) / (double) (b - a + 2));
                else fc[j] = !Double.isNaN(fp) ? fp : !Double.isNaN(fn) ? fn : f[j];
            }
        }
    }

    static int clampPow2(double v, int lo, int hi) {
        int p = Fft.nextPow2((int) Math.ceil(v));
        return Math.max(lo, Math.min(hi, p));
    }

    // ------------------------------------------------------------------ 1. coarse comb path

    /** comb frequency per STFT frame (frame k centred on sample k * hop) */
    static double[] coarse(double[] x, int sr, int win, int hop, double[] confOut) {
        int n = x.length, frames = n / hop + 1;
        int nfft = 4 * win;
        double binHz = sr / (double) nfft;
        int w = (int) Math.round(60 / binHz);
        int kmax = (int) Math.ceil(COMB_FMAX * 1.06 / binHz) + w + 2;
        kmax = Math.min(kmax, nfft / 2 - w - 1);
        int grid = (int) (Math.log(FMAX / FMIN) / Math.log(GRID)) + 1;
        double[] fg = new double[grid];
        int[] hn = new int[grid];
        for (int g = 0; g < grid; g++) {
            fg[g] = FMIN * Math.pow(GRID, g);
            hn[g] = Math.max(3, Math.min(HMAX, (int) (COMB_FMAX / fg[g])));
        }
        int lg = (int) (Math.log(COMB_FMAX / FLOW_FMIN) / Math.log(FLOW_GRID)) + 1;
        double[] lf = new double[lg];
        for (int j = 0; j < lg; j++) lf[j] = FLOW_FMIN * Math.pow(FLOW_GRID, j);
        double[] hw = Fft.hann(win);
        double[] re = new double[nfft], im = new double[nfft];
        double[] lv = new double[kmax + w + 2], pk = new double[kmax + 1], pre = new double[kmax + w + 3];
        float[][] score = new float[frames][grid];
        float[][] map = new float[frames][lg];
        // pass 1: peakiness, comb scores and log-frequency peak maps
        for (int k = 0; k < frames; k++) {
            Arrays.fill(re, 0);
            Arrays.fill(im, 0);
            int c = k * hop - win / 2;
            for (int i = 0; i < win; i++) {
                int s = c + i;
                if (s >= 0 && s < n) re[i] = x[s] * hw[i];
            }
            Fft.fft(re, im, false);
            for (int b = 0; b < lv.length; b++) lv[b] = 10 * Math.log10(re[b] * re[b] + im[b] * im[b] + 1e-20);
            pre[0] = 0;
            for (int b = 0; b < lv.length; b++) pre[b + 1] = pre[b] + lv[b];
            for (int b = 0; b <= kmax; b++) {
                int a0 = Math.max(0, b - w), a1 = Math.min(lv.length - 1, b + w);
                double mean = (pre[a1 + 1] - pre[a0]) / (a1 - a0 + 1);
                double p = lv[b] - mean;
                pk[b] = p < -6 ? -6 : p > PEAK_CLIP ? PEAK_CLIP : p;
            }
            float[] sc = score[k];
            for (int g = 0; g < grid; g++) {
                double acc = 0;
                int h = hn[g];
                for (int j = 1; j <= h; j++) {
                    double pos = j * fg[g] / binHz;
                    int b = (int) pos;
                    if (b >= kmax) {
                        h = j - 1;
                        break;
                    }
                    acc += pk[b] + (pk[b + 1] - pk[b]) * (pos - b);
                }
                sc[g] = (float) (h > 0 ? acc / h : 0);
            }
            for (int j = 0; j < lg; j++) {
                double pos = lf[j] / binHz;
                int b = (int) pos;
                double v = pk[b] + (pk[b + 1] - pk[b]) * (pos - b) - FLOW_PEAK;
                map[k][j] = (float) (v > 0 ? v : 0);
            }
        }
        // pass 2: spectral flow. The log-frequency peak map of a harmonic stack shifts rigidly with the rpm;
        // frames FLOW_D hops apart do not overlap, so noise structure does not pin the shift to 0
        int lagMax = (int) Math.ceil(Math.log(FLOW_MAX) / Math.log(FLOW_GRID));
        double[] flowD = new double[frames], confD = new double[frames];
        double[] cr = new double[2 * lagMax + 1];
        for (int k = FLOW_D; k < frames; k++) {
            float[] q = map[k], qp = map[k - FLOW_D];
            double e0 = 0, e1 = 0;
            for (int j = 0; j < lg; j++) {
                e0 += q[j] * (double) q[j];
                e1 += qp[j] * (double) qp[j];
            }
            if (e0 <= 0 || e1 <= 0) continue;
            double best = -1;
            int arg = 0;
            for (int lag = -lagMax; lag <= lagMax; lag++) {
                double acc = 0;
                int j0 = Math.max(0, lag), j1 = Math.min(lg, lg + lag);
                for (int j = j0; j < j1; j++) acc += q[j] * qp[j - lag];
                cr[lag + lagMax] = acc;
                if (acc > best) {
                    best = acc;
                    arg = lag;
                }
            }
            double off = 0;
            if (arg > -lagMax && arg < lagMax) {
                double a = cr[arg + lagMax - 1], b = cr[arg + lagMax], cc = cr[arg + lagMax + 1];
                double den = a - 2 * b + cc;
                if (den < 0) off = Math.max(-0.5, Math.min(0.5, 0.5 * (a - cc) / den));
            }
            flowD[k] = (arg + off) * Math.log(FLOW_GRID) / Math.log(GRID); // comb grid steps over FLOW_D hops
            confD[k] = best / Math.sqrt(e0 * e1);
        }
        // pass 3: Viterbi; a step k-1 -> k follows the flow measured around k (gate +-2.4 %, sigma 0.5 %), or,
        // where the flow is unsure, may move up to GATE per hop
        int gate = (int) Math.round(Math.log(GATE) / Math.log(GRID));
        short[][] back = new short[frames][grid];
        double[] prev = new double[grid], cur = new double[grid];
        for (int g = 0; g < grid; g++) prev[g] = score[0][g];
        for (int k = 1; k < frames; k++) {
            int kc = Math.min(frames - 1, Math.max(FLOW_D, k + FLOW_D / 2));
            double flow = flowD[kc] / FLOW_D, conf = confD[kc];
            if (confOut != null && k < confOut.length) confOut[k] = conf;
            // a sure flow pins the step to +-0.4 %: drifting onto another multiple of the comb (2x needs 35 %
            // per ... 173 hops of bad scores) never pays; less sure (shifts) +-2 %; no flow at all: the gate
            boolean sure = conf >= FLOW_SURE, some = conf >= FLOW_SOME;
            double sig = sure ? 1.5 : some ? 4 : 12;
            int reach = sure ? 2 : some ? 10 : gate;
            float[] sc = score[k];
            for (int g = 0; g < grid; g++) {
                double best = -1e300;
                int arg = g;
                double qs = some ? g - flow : g;
                int lo = Math.max(0, (int) Math.round(qs) - reach), hi = Math.min(grid - 1, (int) Math.round(qs) + reach);
                for (int qq = lo; qq <= hi; qq++) {
                    double dd = (qq - qs) / sig;
                    double v = prev[qq] - 0.5 * dd * dd;
                    if (v > best) {
                        best = v;
                        arg = qq;
                    }
                }
                cur[g] = sc[g] + (best > -1e299 ? best : -1e6);
                back[k][g] = (short) arg;
            }
            double[] t = prev;
            prev = cur;
            cur = t;
        }
        int g = 0;
        for (int gg = 1; gg < grid; gg++) if (prev[gg] > prev[g]) g = gg;
        double[] out = new double[frames];
        for (int k = frames - 1; k >= 0; k--) {
            float[] sc = score[k];
            double off = 0;
            if (g > 0 && g < grid - 1) {
                double a = sc[g - 1], b = sc[g], cc = sc[g + 1];
                double den = a - 2 * b + cc;
                if (den < 0) off = Math.max(-0.5, Math.min(0.5, 0.5 * (a - cc) / den));
            }
            out[k] = FMIN * Math.pow(GRID, g + off);
            if (k > 0) g = back[k][g];
        }
        return out;
    }

    /** per-sample phase (cycles) of a frequency given at frame centres k * hop */
    static double[] integrate(double[] f, int hop, int n, int sr) {
        double[] th = new double[n];
        double prev = f[0];
        for (int i = 1; i < n; i++) {
            double p = i / (double) hop;
            int k = (int) p;
            double fi = k >= f.length - 1 ? f[f.length - 1] : f[k] + (f[k + 1] - f[k]) * (p - k);
            th[i] = th[i - 1] + 0.5 * (prev + fi) / sr;
            prev = fi;
        }
        return th;
    }

    // ------------------------------------------------------------------ 2. phase refinement

    /**
     * One pass: theta += the angle error measured from the phases of the nRefs strongest orders (of the unit
     * that theta counts) in kw-cycle Hann frames, one cycle apart, s samples per cycle.
     */
    static void refine(double[] x, int sr, double[] th, int s, int kw, int nRefs, double fmax, int smooth) {
        Angle.Signal a = Angle.resample(x, th, s);
        int cyc = a.cycles(), frames = cyc - kw + 1;
        if (frames < 3) return;
        int n = Math.min(x.length, th.length);
        double unit = (th[n - 1] - th[0]) * sr / (n - 1);
        int nMax = Math.max(1, Math.min(s / 2 - 1, (int) (fmax / unit)));
        int len = kw * s;
        double[] w = Fft.hann(len), re = new double[len], im = new double[len];
        float[][] ar = new float[frames][nMax + 1], ai = new float[frames][nMax + 1];
        double[] mean = new double[nMax + 1];
        for (int c = 0; c < frames; c++) {
            for (int i = 0; i < len; i++) {
                re[i] = a.y[c * s + i] * w[i];
                im[i] = 0;
            }
            Fft.fft(re, im, false);
            for (int o = 1; o <= nMax; o++) {
                ar[c][o] = (float) re[kw * o];
                ai[c][o] = (float) im[kw * o];
                mean[o] += Math.hypot(re[kw * o], im[kw * o]);
            }
        }
        int[] refs = topOrders(mean, nRefs);
        double[] den = new double[frames], del = new double[frames];
        for (int c = 1; c < frames; c++) {
            double num = 0, dn = 0;
            for (int o : refs) {
                double zr = ar[c][o] * (double) ar[c - 1][o] + ai[c][o] * (double) ai[c - 1][o];
                double zi = ai[c][o] * (double) ar[c - 1][o] - ar[c][o] * (double) ai[c - 1][o];
                double wt = Math.hypot(zr, zi);
                if (wt <= 0) continue;
                double ph = Math.atan2(zi, zr) / (2 * Math.PI);
                if (dn > 0) {
                    double pred = o * num / dn;
                    ph += Math.rint(pred - ph);
                }
                num += wt * o * ph;
                dn += wt * (double) o * o;
            }
            den[c] = dn;
            del[c] = dn > 0 ? num / dn : 0;
        }
        double[] ds = den.clone();
        Arrays.sort(ds);
        double floor = 1e-4 * ds[ds.length / 2];
        for (int c = 1; c < frames; c++) if (!(den[c] > floor)) del[c] = 0;
        if (smooth > 1) del = boxcar(del, smooth);
        double[] eps = new double[frames];
        for (int c = 1; c < frames; c++) eps[c] = eps[c - 1] + del[c];
        double base = a.c0 + kw / 2.0;
        for (int i = 0; i < n; i++) {
            double p = th[i] - base;
            double e;
            if (p <= 0) e = eps[0];
            else {
                int k = (int) p;
                e = k >= frames - 1 ? eps[frames - 1] : eps[k] + (eps[k + 1] - eps[k]) * (p - k);
            }
            th[i] += e;
        }
    }

    /** centred moving average over m points (shorter at the ends) */
    static double[] boxcar(double[] v, int m) {
        double[] pre = new double[v.length + 1], out = new double[v.length];
        for (int i = 0; i < v.length; i++) pre[i + 1] = pre[i] + v[i];
        for (int i = 0; i < v.length; i++) {
            int a = Math.max(0, i - (m - 1) / 2), b = Math.min(v.length, i + m / 2 + 1);
            out[i] = (pre[b] - pre[a]) / (b - a);
        }
        return out;
    }

    /** indices of the k largest values (index >= 1), ascending */
    static int[] topOrders(double[] v, int k) {
        Integer[] idx = new Integer[v.length - 1];
        for (int i = 1; i < v.length; i++) idx[i - 1] = i;
        final double[] vv = v;
        Arrays.sort(idx, new java.util.Comparator<Integer>() {
            public int compare(Integer a, Integer b) {
                return Double.compare(vv[b], vv[a]);
            }
        });
        int m = Math.min(k, idx.length);
        int[] r = new int[m];
        for (int i = 0; i < m; i++) r[i] = idx[i];
        Arrays.sort(r);
        return r;
    }

    // ------------------------------------------------------------------ 3. subharmonic test

    static final int ZP = 2;

    /**
     * Mean power spectrum in the angle domain of theta's unit: Hann frames of m cycles (fewer if the signal is
     * shorter), half overlap, zero-padded to at least 2x. Returns {power[], {bins per unit order, m}}.
     */
    static double[][] orderPower(double[] x, double[] th, int s, int m) {
        Angle.Signal a = Angle.resample(x, th, s);
        int cyc = a.cycles();
        m = Math.max(1, Math.min(m, cyc));
        int len = m * s, nfft = Fft.nextPow2(ZP * len);
        double[] w = Fft.hann(len), re = new double[nfft], im = new double[nfft], p = new double[nfft / 2];
        int frames = 0, step = Math.max(1, m / 2);
        for (int c = 0; c + m <= cyc; c += step) {
            Arrays.fill(re, 0);
            Arrays.fill(im, 0);
            for (int i = 0; i < len; i++) re[i] = a.y[c * s + i] * w[i];
            Fft.fft(re, im, false);
            for (int b = 0; b < p.length; b++) p[b] += re[b] * re[b] + im[b] * im[b];
            frames++;
        }
        for (int b = 0; b < p.length; b++) p[b] /= Math.max(1, frames);
        return new double[][] {p, {nfft / (double) s, m}};
    }

    static PeakFn peaks(double[] x, double[] th, int s, int m) {
        double[][] op = orderPower(x, th, s, m);
        double[] db = new double[op[0].length];
        for (int b = 0; b < db.length; b++) db[b] = 10 * Math.log10(op[0][b] + 1e-30);
        return new PeakFn(db, op[1][0], (int) op[1][1]);
    }

    /**
     * fc = comb / d: the largest d (up to 24, fc at least 2.5 Hz, and resolvable: RESOLVE d cycles per frame) whose comb
     * holds (combHolds)
     */
    static int divisor(PeakFn pf, int s, double fMed) {
        double hmax = Math.max(2, Math.min(DIV_ORDERS, Math.min(0.45 * s, DIV_FMAX / fMed)));
        int best = 1;
        for (int d = 2; d <= DMAX; d++) {
            if (fMed / d < 2.5 || RESOLVE * d > pf.m) break;
            if (combHolds(pf, d, hmax)) best = d;
        }
        return best;
    }

    /** the strongest whole order of the comb (up to 3 kHz) */
    static int dominantOrder(PeakFn pf, double fMed) {
        double hmax = Math.max(1, 3000 / fMed);
        int dom = 1;
        double dv = -1e300;
        for (int j = 1; j <= hmax && j * pf.per < pf.db.length - 2; j++) {
            double v = pf.peak(j);
            if (v > dv) {
                dv = v;
                dom = j;
            }
        }
        return dom;
    }

    static final class PeakFn {
        final double[] db;
        /** bins per unit order */
        final double per;
        /** cycles per frame */
        final int m;

        PeakFn(double[] db, double per, int m) {
            this.db = db;
            this.per = per;
            this.m = m;
        }

        /** the highest level within a fine bin of the position */
        double peak(double order) {
            int c = (int) Math.round(order * per);
            double v = -1e300;
            for (int b = Math.max(0, c - 1); b <= Math.min(db.length - 1, c + 1); b++) v = Math.max(v, db[b]);
            return v;
        }

        /** how far the position stands above the points half a comb step either side */
        double prominence(double order, int d) {
            double h = 0.5 / d;
            return peak(order) - Math.max(peak(order - h), peak(order + h));
        }
    }

    /**
     * the comb unit / d holds: at least 60 % of its positions j / d stand 8 dB above the midpoints to their
     * neighbours. A comb twice too fine fails (every other position is empty), and so does any comb that is not
     * a divisor of the true one; coarser true combs may pass too, so the finest passing comb wins. A skirt of a
     * nearby harmonic is no peak.
     */
    static boolean combHolds(PeakFn pf, int d, double hmax) {
        int count = 0, hit = 0;
        for (int j = 1; j / (double) d <= hmax; j++) {
            if (pf.prominence(j / (double) d, d) >= 8) hit++;
            count++;
        }
        return count > 0 && hit >= 0.6 * count;
    }

    // ------------------------------------------------------------------ 4. output

    static Track toTrack(double[] th, int hop, int sr) {
        int n = th.length, frames = (n - 1) / hop + 1;
        double[] fc = new double[frames], t = new double[frames];
        for (int k = 0; k < frames; k++) {
            int i = k * hop;
            int a = Math.max(0, i - hop / 2), b = Math.min(n - 1, i + hop / 2);
            fc[k] = (th[b] - th[a]) * sr / (b - a);
            t[k] = th[i];
        }
        return new Track(0, hop / (double) sr, fc, t);
    }
}
