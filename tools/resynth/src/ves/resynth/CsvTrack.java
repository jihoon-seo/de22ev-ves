package ves.resynth;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A track and segments from a driving log (CSV with t_s, rpm, throttle_pct and optionally gear; '#' lines are
 * comments) instead of from the audio alone. fc = rpm / 120 on the tracker's hop, integrated to theta, then the
 * tracker's phase refinement against the recording locks theta to the crank phase the audio carries. Segments
 * come from the throttle: on at 50 % or more, off at 6 % or less above 1000 rpm, the rest excluded, as are
 * 0.3 s either side of every gear change and throttle transition (a change between on, off and part throttle),
 * the first and last 0.1 s and runs shorter than 0.25 s. Audio time = log time + offset.
 */
public final class CsvTrack {
    private CsvTrack() {
    }

    static final double ON_THR = 50, OFF_THR = 6, OFF_RPM = 1000, GUARD = 0.3, EDGE = 0.1, LAG_MAX = 0.3, LAG_STEP = 0.01;
    /** the refined fc stays within 2 % of the log's */
    static final double MAX_DEV = 0.02;

    /** the log's rows (times increasing) */
    public static final class Log {
        public final double[] t, rpm, thr;
        /** null when the log has no gear column */
        public final int[] gear;

        Log(double[] t, double[] rpm, double[] thr, int[] gear) {
            this.t = t;
            this.rpm = rpm;
            this.thr = thr;
            this.gear = gear;
        }

        static double at(double[] t, double[] v, double u) {
            if (u <= t[0]) return v[0];
            int n = t.length;
            if (u >= t[n - 1]) return v[n - 1];
            int k = Arrays.binarySearch(t, u);
            if (k >= 0) return v[k];
            k = -k - 2;
            return v[k] + (v[k + 1] - v[k]) * (u - t[k]) / (t[k + 1] - t[k]);
        }

        public double rpmAt(double u) {
            return at(t, rpm, u);
        }

        public double thrAt(double u) {
            return at(t, thr, u);
        }

        static int cls(double thr) {
            return thr >= ON_THR ? 1 : thr <= OFF_THR ? 2 : 0;
        }

        /** log times of gear changes */
        List<Double> shifts() {
            List<Double> e = new ArrayList<Double>();
            if (gear != null) for (int k = 1; k < t.length; k++) if (gear[k] != gear[k - 1]) e.add(t[k]);
            return e;
        }

        /** log times of gear changes and throttle transitions */
        List<Double> events() {
            List<Double> e = shifts();
            for (int k = 1; k < t.length; k++) if (cls(thr[k]) != cls(thr[k - 1])) e.add(t[k]);
            return e;
        }
    }

    static double nearest(List<Double> ev, double u) {
        double m = Double.POSITIVE_INFINITY;
        for (double e : ev) m = Math.min(m, Math.abs(u - e));
        return m;
    }

    public static Log read(File f) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        try {
            int it = -1, ir = -1, ith = -1, ig = -1, line = 0, cols = 0;
            List<double[]> rows = new ArrayList<double[]>();
            String l;
            while ((l = r.readLine()) != null) {
                line++;
                l = l.trim();
                if (l.length() == 0 || l.startsWith("#")) continue;
                String[] p = l.split(",", -1);
                if (cols == 0) {
                    cols = p.length;
                    for (int c = 0; c < p.length; c++) {
                        String h = p[c].trim();
                        if (h.equals("t_s")) it = c;
                        else if (h.equals("rpm")) ir = c;
                        else if (h.equals("throttle_pct")) ith = c;
                        else if (h.equals("gear")) ig = c;
                    }
                    String miss = it < 0 ? "t_s" : ir < 0 ? "rpm" : ith < 0 ? "throttle_pct" : null;
                    if (miss != null) throw new IOException(f + ": no " + miss + " column (needs t_s, rpm, throttle_pct; gear optional)");
                    continue;
                }
                if (p.length < cols) throw new IOException(f + ":" + line + ": " + p.length + " fields, header has " + cols);
                try {
                    double t = Double.parseDouble(p[it].trim());
                    if (!rows.isEmpty() && !(t > rows.get(rows.size() - 1)[0])) throw new IOException(f + ":" + line + ": t_s not increasing");
                    rows.add(new double[] {t, Double.parseDouble(p[ir].trim()), Double.parseDouble(p[ith].trim()),
                            ig < 0 ? 0 : Double.parseDouble(p[ig].trim())});
                } catch (NumberFormatException e) {
                    throw new IOException(f + ":" + line + ": not a number: " + e.getMessage());
                }
            }
            if (rows.size() < 2) throw new IOException(f + ": fewer than 2 rows");
            int n = rows.size();
            double[] t = new double[n], rpm = new double[n], thr = new double[n];
            int[] gear = ig < 0 ? null : new int[n];
            for (int k = 0; k < n; k++) {
                double[] v = rows.get(k);
                t[k] = v[0];
                rpm[k] = v[1];
                thr[k] = v[2];
                if (gear != null) gear[k] = (int) Math.round(v[3]);
            }
            return new Log(t, rpm, thr, gear);
        } finally {
            r.close();
        }
    }

    /** the tracker's analysis hop (Tracker.track: an eighth of its STFT window) */
    static int hop(int sr) {
        return Fft.nextPow2((int) (0.093 * sr * 0.75)) / 8;
    }

    /**
     * fc from the log on the tracker's hop, integrated, then refined on the recording (three passes). The log is
     * the rpm truth: after each pass fc is held within MAX_DEV of it and integrated again, so the refinement
     * locks the phase but cannot slip where the recording's order phases jump (granular or crossfaded engine
     * sounds); a whole-cycle slip that is cut off this way only moves theta by a constant.
     */
    public static Track track(double[] x, int sr, Log log, double offset) {
        return track(x, sr, log, offset, true);
    }

    /** refine false: the log's fc integrated, nothing taken from the audio (for audio without a steady crank phase) */
    public static Track track(double[] x, int sr, Log log, double offset, boolean refine) {
        int hop = hop(sr), n = x.length, frames = (n - 1) / hop + 1; // as Tracker.toTrack
        double[] ref = new double[frames];
        for (int k = 0; k < frames; k++) ref[k] = Math.max(Tracker.FMIN, log.rpmAt(k * hop / (double) sr - offset) / 120);
        double[] fc = ref.clone(), th = Tracker.integrate(fc, hop, n, sr);
        double[] srt = ref.clone();
        Arrays.sort(srt);
        int s = Tracker.clampPow2(2.2 * Tracker.COMB_FMAX / srt[srt.length / 50], 16, 8192);
        for (int it = 0; refine && it < 3; it++) {
            Tracker.refine(x, sr, th, s, 4, 8, Tracker.COMB_FMAX, 1);
            double[] got = Tracker.toTrack(th, hop, sr).fc;
            for (int k = 0; k < frames; k++) fc[k] = Math.max(ref[k] * (1 - MAX_DEV), Math.min(ref[k] * (1 + MAX_DEV), got[k]));
            th = Tracker.integrate(fc, hop, n, sr);
        }
        double[] tf = new double[frames];
        for (int k = 0; k < frames; k++) tf[k] = th[Math.min(n - 1, k * hop)];
        return new Track(0, hop / (double) sr, fc, tf);
    }

    /** the share of the track's frames held at the MAX_DEV limit (where the recording's phase left the log) */
    static double held(Track tr, Log log, double offset) {
        int c = 0;
        for (int k = 0; k < tr.frames(); k++) {
            double ref = Math.max(Tracker.FMIN, log.rpmAt(tr.time(k) - offset) / 120);
            if (Math.abs(tr.fc[k] / ref - 1) >= MAX_DEV * (1 - 1e-6)) c++;
        }
        return c / (double) tr.frames();
    }

    /** on / off / excluded per track frame from the log's throttle, rpm and gear */
    public static Segments segments(Log log, Track tr, double offset) {
        List<Double> ev = log.events();
        int frames = tr.frames();
        int[] lab = new int[frames];
        double u0 = log.t[0] + EDGE, u1 = log.t[log.t.length - 1] - EDGE;
        for (int k = 0; k < frames; k++) {
            double t = tr.time(k), u = t - offset;
            if (t < EDGE || t > tr.tEnd() - EDGE || u < u0 || u > u1 || nearest(ev, u) < GUARD) continue;
            double thr = log.thrAt(u);
            lab[k] = thr >= ON_THR ? Segments.ON : thr <= OFF_THR && log.rpmAt(u) > OFF_RPM ? Segments.OFF : Segments.EXCLUDED;
        }
        return Segments.fromLabels(tr, lab);
    }

    /**
     * |rpm_other / rpm_log - 1| over the other track's frames inside the log, 0.3 s or more from a gear change:
     * {median, p95, best lag (s, audio behind the log: offset + lag fits better), median at that lag, frames}
     */
    public static double[] compare(Log log, Track other, double offset) {
        List<Double> sh = log.shifts();
        double u0 = log.t[0] + EDGE, u1 = log.t[log.t.length - 1] - EDGE;
        List<double[]> use = new ArrayList<double[]>(); // {u, rpm_other}
        for (int k = 0; k < other.frames(); k++) {
            double t = other.time(k), u = t - offset;
            if (t < EDGE || u < u0 || u > u1 || nearest(sh, u) < GUARD) continue;
            use.add(new double[] {u, 120 * other.fc[k]});
        }
        if (use.isEmpty()) return new double[] {Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0};
        double[] e = errors(log, use, 0);
        Arrays.sort(e);
        double med = e[e.length / 2], p95 = e[Math.min(e.length - 1, (int) (0.95 * e.length))];
        double bestLag = 0, bestMed = Double.POSITIVE_INFINITY;
        int steps = (int) Math.round(LAG_MAX / LAG_STEP);
        for (int j = -steps; j <= steps; j++) {
            double[] el = errors(log, use, j * LAG_STEP);
            Arrays.sort(el);
            double m = el[el.length / 2];
            if (m < bestMed - 1e-12 || m <= bestMed + 1e-12 && Math.abs(j * LAG_STEP) < Math.abs(bestLag)) {
                bestMed = m;
                bestLag = j * LAG_STEP;
            }
        }
        return new double[] {med, p95, bestLag, bestMed, e.length};
    }

    static double[] errors(Log log, List<double[]> use, double lag) {
        double[] e = new double[use.size()];
        for (int i = 0; i < e.length; i++) {
            double[] v = use.get(i);
            e[i] = Math.abs(v[1] / Math.max(1, log.rpmAt(v[0] - lag)) - 1);
        }
        return e;
    }

    static String pct(double v) {
        return String.format(Locale.ROOT, "%.2f %%", 100 * v);
    }

    /** csvtrack <log.csv> <in.wav> <out.track.txt> <out.segments.txt> [cyl=N] [offset=<s>] [refine=on|off] [compare=<track>] */
    static int run(String[] a) throws IOException {
        Main.need(a, 5);
        GitGuard.check(new File(a[3]));
        GitGuard.check(new File(a[4]));
        double offset = Double.parseDouble(Main.opt(a, "offset", "0"));
        int cyl = Integer.parseInt(Main.opt(a, "cyl", "0"));
        String rf = Main.opt(a, "refine", "on");
        if (!rf.equals("on") && !rf.equals("off")) throw new IllegalArgumentException("csvtrack: refine=" + rf + ": on or off");
        boolean refine = rf.equals("on");
        Log log = read(new File(a[1]));
        String cmp = Main.opt(a, "compare", null);
        Track other = cmp == null ? null : Track.read(new File(cmp));
        double[] x = Main.audio(a[2]);
        Track t = track(x, PackRenderer.SR, log, offset, refine);
        double lo = Double.POSITIVE_INFINITY, hi = 0;
        for (double v : t.fc) {
            lo = Math.min(lo, 120 * v);
            hi = Math.max(hi, 120 * v);
        }
        System.out.println("log: " + log.t.length + " rows, " + Main.f2(log.t[0]) + ".." + Main.f2(log.t[log.t.length - 1]) + " s, "
                + log.shifts().size() + " gear changes" + (log.gear == null ? " (no gear column)" : "") + ", " + log.events().size()
                + " gear/throttle events; offset " + Main.f2(offset) + " s");
        System.out.println("track: rpm " + Math.round(lo) + ".." + Math.round(hi) + ", " + t.frames() + " frames "
                + (refine ? "(log rpm, phase refined on the audio); " + pct(held(t, log, offset)) + " of frames held at the +-2 % limit"
                        : "(log rpm integrated, refine=off)"));
        t.write(Main.out(a[3]), (cyl > 0 ? "cylinders " + cyl + "; " : "") + "fc from a driving log"
                + (refine ? ", phase refined on the audio" : ", not refined"));
        Segments s = segments(log, t, offset);
        s.write(Main.out(a[4]));
        System.out.println("on " + Main.f2(s.seconds(Segments.ON)) + " s, off " + Main.f2(s.seconds(Segments.OFF)) + " s, excluded "
                + Main.f2(s.seconds(Segments.EXCLUDED)) + " s in " + s.count() + " segments");
        if (other != null) {
            double[] c = compare(log, other, offset);
            System.out.println("compare " + new File(cmp).getName() + ": |rpm_tracker / rpm_csv - 1| median " + pct(c[0]) + ", p95 "
                    + pct(c[1]) + " over " + (int) c[4] + " frames 0.3 s or more from a gear change; best lag "
                    + String.format(Locale.ROOT, "%+.2f s", c[2]) + " (median " + pct(c[3]) + ")");
        }
        return 0;
    }
}
