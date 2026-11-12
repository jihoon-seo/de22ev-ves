package ves.resynth;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * On-load / off-load / excluded segments of a recording, kept in a private sidecar ({@code <name>.segments.txt},
 * never in git) so they can be reviewed and edited by hand:
 * <pre>
 * # ves.resynth segments v1
 * seg 0.000 1.250 excluded
 * seg 1.250 3.100 on
 * </pre>
 * Times not covered by a line are excluded. {@link #propose} suggests them from the level and the rpm slope:
 * shifts (rpm falling faster than 100 %/s) are cut out with 0.2 s either side. Loud frames (two-class split of
 * the level) with steady or rising rpm are on-load. Frames with falling rpm are off-load (overrun) when they are
 * at least 2 dB under the median on-load level; quiet frames with steady or rising rpm (part load, e.g. a launch
 * hold) and loud falling ones are excluded, as are 0.2 s either side of every change between on and off
 * (lift-off, tip-in), the first and last 0.1 s, and runs shorter than 0.25 s.
 */
public final class Segments {
    public static final int EXCLUDED = 0, ON = 1, OFF = 2;
    static final String[] NAMES = {"excluded", "on", "off"};
    /** ln rpm per second: shift below SHIFT_SLOPE, falling below FALLING (steady or rising above) */
    static final double SHIFT_SLOPE = -1.0, FALLING = -0.005, GUARD = 0.2, MIN_RUN = 0.25, EDGE = 0.1, OFF_UNDER = 2;

    private final List<double[]> segs = new ArrayList<double[]>(); // {t0, t1, label}

    public int count() {
        return segs.size();
    }

    public void add(double t0, double t1, int label) {
        if (t1 > t0) segs.add(new double[] {t0, t1, label});
    }

    public int labelAt(double t) {
        for (double[] s : segs) if (t >= s[0] && t < s[1]) return (int) s[2];
        return EXCLUDED;
    }

    /** total seconds labelled l */
    public double seconds(int l) {
        double s = 0;
        for (double[] g : segs) if ((int) g[2] == l) s += g[1] - g[0];
        return s;
    }

    public static Segments propose(double[] x, int sr, Track tr) {
        int frames = tr.frames();
        double[] lev = new double[frames], slope = new double[frames];
        int half = (int) (0.025 * sr);
        double[] pre = new double[x.length + 1];
        for (int i = 0; i < x.length; i++) pre[i + 1] = pre[i] + x[i] * x[i];
        for (int k = 0; k < frames; k++) {
            double t = tr.time(k);
            int c = (int) Math.round(t * sr), a = Math.max(0, c - half), b = Math.min(x.length, c + half);
            lev[k] = b > a ? 10 * Math.log10((pre[b] - pre[a]) / (b - a) + 1e-20) : -200;
            double r0 = tr.rpmAt(t - 0.05), r1 = tr.rpmAt(t + 0.05);
            slope[k] = r0 > 0 && r1 > 0 ? Math.log(r1 / r0) / 0.1 : 0;
        }
        boolean[] shift = new boolean[frames];
        List<Double> lv = new ArrayList<Double>();
        for (int k = 0; k < frames; k++) {
            shift[k] = slope[k] < SHIFT_SLOPE;
            if (!shift[k] && lev[k] > -90) lv.add(lev[k]);
        }
        double thr = otsu(lv);
        int[] lab = new int[frames];
        List<Double> onLev = new ArrayList<Double>();
        for (int k = 0; k < frames; k++) {
            boolean ok = !shift[k] && lev[k] > -90 && tr.time(k) >= EDGE && tr.time(k) <= tr.tEnd() - EDGE;
            lab[k] = ok && lev[k] >= thr && slope[k] >= FALLING ? ON : EXCLUDED;
            if (lab[k] == ON) onLev.add(lev[k]);
        }
        java.util.Collections.sort(onLev);
        double offBelow = onLev.isEmpty() ? thr : onLev.get(onLev.size() / 2) - OFF_UNDER;
        for (int k = 0; k < frames; k++) {
            boolean ok = !shift[k] && lev[k] > -90 && tr.time(k) >= EDGE && tr.time(k) <= tr.tEnd() - EDGE;
            if (ok && lab[k] == EXCLUDED && slope[k] < FALLING && lev[k] < offBelow) lab[k] = OFF;
        }
        int guard = (int) Math.round(GUARD / tr.dt);
        boolean[] cut = new boolean[frames];
        for (int k = 0; k < frames; k++) {
            boolean edge = shift[k] || k > 0 && lab[k] != lab[k - 1] && lab[k] != EXCLUDED && lab[k - 1] != EXCLUDED;
            if (!edge) continue;
            for (int j = Math.max(0, k - guard); j <= Math.min(frames - 1, k + guard); j++) cut[j] = true;
        }
        // an on run that turns into an off run through a short excluded gap is a lift-off: guard it too
        int last = EXCLUDED, lastAt = -1;
        for (int k = 0; k < frames; k++) {
            if (lab[k] == EXCLUDED) continue;
            if (last != EXCLUDED && last != lab[k] && k - lastAt <= 2 * guard)
                for (int j = Math.max(0, lastAt - guard); j <= Math.min(frames - 1, k + guard); j++) cut[j] = true;
            last = lab[k];
            lastAt = k;
        }
        for (int k = 0; k < frames; k++) if (cut[k]) lab[k] = EXCLUDED;
        int minRun = (int) Math.round(MIN_RUN / tr.dt);
        for (int k = 0; k < frames; ) {
            int j = k;
            while (j < frames && lab[j] == lab[k]) j++;
            if (lab[k] != EXCLUDED && j - k < minRun) for (int q = k; q < j; q++) lab[q] = EXCLUDED;
            k = j;
        }
        Segments s = new Segments();
        for (int k = 0; k < frames; ) {
            int j = k;
            while (j < frames && lab[j] == lab[k]) j++;
            double t0 = k == 0 ? Math.min(0, tr.time(0)) : tr.time(k) - tr.dt / 2;
            double t1 = j == frames ? tr.time(frames - 1) + tr.dt : tr.time(j) - tr.dt / 2;
            s.add(round(t0), round(t1), lab[k]);
            k = j;
        }
        return s;
    }

    static double round(double t) {
        return Math.rint(t * 1000) / 1000;
    }

    /** two-class threshold (Otsu) of the values, on a 0.25 dB histogram */
    static double otsu(List<Double> v) {
        if (v.isEmpty()) return 0;
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (double x : v) {
            lo = Math.min(lo, x);
            hi = Math.max(hi, x);
        }
        int bins = Math.max(2, (int) Math.ceil((hi - lo) / 0.25) + 1);
        double[] h = new double[bins];
        for (double x : v) h[(int) ((x - lo) / 0.25)]++;
        double total = v.size(), sumAll = 0;
        for (int i = 0; i < bins; i++) sumAll += i * h[i];
        double wB = 0, sumB = 0, best = -1;
        int arg = 0;
        for (int i = 0; i < bins - 1; i++) {
            wB += h[i];
            sumB += i * h[i];
            double wF = total - wB;
            if (wB == 0 || wF == 0) continue;
            double mB = sumB / wB, mF = (sumAll - sumB) / wF;
            double between = wB * wF * (mB - mF) * (mB - mF);
            if (between > best) {
                best = between;
                arg = i;
            }
        }
        return lo + (arg + 1) * 0.25;
    }

    public void write(File f) throws IOException {
        StringBuilder b = new StringBuilder("# ves.resynth segments v1\n# seg <t0 s> <t1 s> <on|off|excluded>\n");
        for (double[] s : segs)
            b.append(String.format(Locale.ROOT, "seg %.3f %.3f %s%n", s[0], s[1], NAMES[(int) s[2]]));
        Files.writeText(f, b.toString());
    }

    public static Segments read(File f) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        try {
            Segments s = new Segments();
            String l;
            int line = 0;
            while ((l = r.readLine()) != null) {
                line++;
                l = l.trim();
                if (l.length() == 0 || l.startsWith("#")) continue;
                String[] p = l.split("\\s+");
                int lab = p.length == 4 && p[0].equals("seg") ? indexOf(p[3]) : -1;
                if (lab < 0) throw new IOException(f + ":" + line + ": expected seg <t0> <t1> on|off|excluded");
                s.add(Double.parseDouble(p[1]), Double.parseDouble(p[2]), lab);
            }
            return s;
        } finally {
            r.close();
        }
    }

    static int indexOf(String name) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) return i;
        return -1;
    }
}
