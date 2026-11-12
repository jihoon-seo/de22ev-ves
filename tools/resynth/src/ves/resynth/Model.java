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
 * The engine-sound model fitted by {@link Fitter}, per load layer (on, off):
 * <pre>
 * log A(n, r) = s_n + h(n fc(r)) + l(r)      dB re amplitude 1, fc = r / 120, l(r) = a + b ln(r / rRef)
 * </pre>
 * s_n: the order's own level (the cylinder comb); h: a resonance curve over frequency (linear between 25 Hz
 * nodes, held beyond them); l: the level against rpm. Orders above the observed ones follow a tail, linear in
 * ln n (fitted over the last observed octave) plus the mean residual of n mod cylinders; orders the analysis
 * found masked (within 10 dB of the floor) most of the time are absent: never synthesized. Noise:
 * log N(band, r) = q(band) + m ln(r / rRef) per 1/3-octave band (PSD, dB re 1/Hz); coh: the crank-wobble skirt
 * per band (power beside each order over its own). Half orders: level relative to the strongest order. Phases
 * (shared by both layers): per order, the circular mean measured at the low end on load, or seeded random
 * where it is not coherent. Kept as a private text file (*.vesmodel).
 */
public final class Model {
    public int cyl = 4;
    public long seed = 1;
    /** per order n: phase (radians) and its coherence R (resultant length); NaN = none measured */
    public double[] phase = new double[0], phaseR = new double[0];
    public Layer on, off;

    public static final class Layer {
        public int cyl = 4;
        public double rMin, rMax, a, b, rRef = 1000;
        public double fLo, step = 25;
        public double[] h = new double[0];
        /** s[n], n = 1..nObs; NaN: not fitted (the tail stands in) */
        public int nObs;
        public double[] s = new double[1];
        public boolean[] absent = new boolean[1];
        public double tailA, tailB;
        public double[] tailRes = new double[0];
        /** half order n + 1/2: dB relative to the strongest order; NaN = not included */
        public double[] halfRel = new double[0];
        public double noiseM;
        public double[] noiseQ = new double[Orders.BANDS], coh = new double[Orders.BANDS];
        /**
         * optional: below lowBelow rpm the orders come from this layer instead (M0, the held envelope, where it
         * extrapolated better than this fit); orders absent here stay absent
         */
        public Layer low;
        public double lowBelow;

        public boolean absent(int n) {
            if (n >= 1 && n <= nObs) return absent[n];
            return n > nObs && tailRes.length > 0 && Double.isNaN(tailRes[n % tailRes.length]);
        }

        public double sOf(int n) {
            if (n >= 1 && n <= nObs && !Double.isNaN(s[n])) return s[n];
            double r = tailRes.length > 0 ? tailRes[n % tailRes.length] : 0;
            return tailA + tailB * Math.log(n) + r;
        }

        public double h(double f) {
            if (h.length == 0) return 0;
            double p = (f - fLo) / step;
            if (p <= 0) return h[0];
            int j = (int) p;
            if (j >= h.length - 1) return h[h.length - 1];
            return h[j] + (h[j + 1] - h[j]) * (p - j);
        }

        public double level(double rpm) {
            return a + b * Math.log(rpm / rRef);
        }

        /** dB re amplitude 1 of whole order n at rpm; NaN if absent */
        public double predict(double n, double rpm) {
            int k = (int) Math.round(n);
            if (absent(k)) return Double.NaN;
            if (low != null && rpm < lowBelow) return low.predict(k, rpm);
            return sOf(k) + h(k * rpm / 120) + level(rpm);
        }

        /** dB of half order n + 1/2 at rpm, NaN if not included */
        public double half(int n, double rpm, double strongestDb) {
            if (n < 0 || n >= halfRel.length || Double.isNaN(halfRel[n])) return Double.NaN;
            return strongestDb + halfRel[n];
        }

        /**
         * noise PSD dB re 1/Hz in a band at rpm (an unobserved band takes the nearest observed one). Below the
         * measured rpm the noise follows the orders' level trend from its value at rMin, so the measured balance
         * of orders and noise carries on down to idle instead of the two extrapolated slopes drifting apart
         */
        public double noise(int band, double rpm) {
            if (rMin > 0 && rpm < rMin) {
                Layer lo = low != null && rpm < lowBelow ? low : this;
                return noiseAt(band, rMin) + lo.level(rpm) - lo.level(rMin);
            }
            return noiseAt(band, rpm);
        }

        private double noiseAt(int band, double rpm) {
            for (int d = 0; d < Orders.BANDS; d++) {
                for (int sg = -1; sg <= 1; sg += 2) {
                    int b2 = band + sg * d;
                    if (b2 >= 0 && b2 < Orders.BANDS && !Double.isNaN(noiseQ[b2])) return noiseQ[b2] + noiseM * Math.log(rpm / rRef);
                }
            }
            return -200;
        }

        public double coh(int band) {
            return band < 0 || band >= coh.length || Double.isNaN(coh[band]) ? 0 : coh[band];
        }
    }

    public double phaseOf(int n, double[] random) {
        if (n < phase.length && !Double.isNaN(phase[n]) && phaseR[n] >= 0.5) return phase[n];
        return random[n];
    }

    // ------------------------------------------------------------------ text file (private: *.vesmodel)

    public void write(File f, String comment) throws IOException {
        StringBuilder b = new StringBuilder("# ves.resynth model v1\n");
        if (comment != null) b.append("# ").append(comment.replace('\n', ' ')).append('\n');
        b.append("cyl ").append(cyl).append('\n');
        b.append("seed ").append(seed).append('\n');
        for (int n = 1; n < phase.length; n++)
            if (!Double.isNaN(phase[n])) b.append(String.format(Locale.ROOT, "phase %d %.6f %.4f%n", n, phase[n], phaseR[n]));
        for (int i = 0; i < 2; i++) {
            Layer l = i == 0 ? on : off;
            if (l == null) continue;
            writeLayer(b, i == 0 ? "on" : "off", l);
            if (l.low != null) writeLayer(b, i == 0 ? "on-low" : "off-low", l.low);
        }
        Files.writeText(f, b.toString());
    }

    private static void writeLayer(StringBuilder b, String name, Layer l) {
        b.append("layer ").append(name).append('\n');
        b.append(String.format(Locale.ROOT, "range %.1f %.1f%n", l.rMin, l.rMax));
        if (l.low != null) b.append(String.format(Locale.ROOT, "below %.1f%n", l.lowBelow));
        b.append(String.format(Locale.ROOT, "level %.6f %.6f %.3f%n", l.a, l.b, l.rRef));
        b.append(String.format(Locale.ROOT, "h %.3f %.3f", l.fLo, l.step));
        for (double v : l.h) b.append(String.format(Locale.ROOT, " %.4f", v));
        b.append('\n').append("nobs ").append(l.nObs).append('\n');
        for (int n = 1; n <= l.nObs; n++) {
            if (l.absent[n]) b.append("s ").append(n).append(" absent\n");
            else if (!Double.isNaN(l.s[n])) b.append(String.format(Locale.ROOT, "s %d %.4f%n", n, l.s[n]));
        }
        b.append(String.format(Locale.ROOT, "tail %.6f %.6f", l.tailA, l.tailB));
        for (double v : l.tailRes) b.append(' ').append(Double.isNaN(v) ? "nan" : String.format(Locale.ROOT, "%.4f", v));
        b.append('\n');
        for (int n = 0; n < l.halfRel.length; n++)
            if (!Double.isNaN(l.halfRel[n])) b.append(String.format(Locale.ROOT, "half %d %.3f%n", n, l.halfRel[n]));
        b.append(String.format(Locale.ROOT, "noise %.6f", l.noiseM));
        for (double v : l.noiseQ) b.append(' ').append(Double.isNaN(v) ? "nan" : String.format(Locale.ROOT, "%.4f", v));
        b.append('\n').append("coh");
        for (double v : l.coh) b.append(' ').append(Double.isNaN(v) ? "nan" : String.format(Locale.ROOT, "%.5f", v));
        b.append('\n').append("end\n");
    }

    public static Model read(File f) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        try {
            Model m = new Model();
            List<double[]> ph = new ArrayList<double[]>();
            Layer cur = null;
            String curName = null;
            List<double[]> ss = new ArrayList<double[]>(), hs = new ArrayList<double[]>();
            String l;
            while ((l = r.readLine()) != null) {
                l = l.trim();
                if (l.length() == 0 || l.startsWith("#")) continue;
                String[] p = l.split("\\s+");
                String k = p[0];
                if (k.equals("cyl")) m.cyl = Integer.parseInt(p[1]);
                else if (k.equals("seed")) m.seed = Long.parseLong(p[1]);
                else if (k.equals("phase")) ph.add(new double[] {Integer.parseInt(p[1]), num(p[2]), num(p[3])});
                else if (k.equals("layer")) {
                    cur = new Layer();
                    cur.cyl = m.cyl;
                    curName = p[1];
                    ss.clear();
                    hs.clear();
                } else if (cur == null) throw new IOException(f + ": " + k + " outside a layer");
                else if (k.equals("range")) {
                    cur.rMin = num(p[1]);
                    cur.rMax = num(p[2]);
                } else if (k.equals("below")) {
                    cur.lowBelow = num(p[1]);
                } else if (k.equals("level")) {
                    cur.a = num(p[1]);
                    cur.b = num(p[2]);
                    cur.rRef = num(p[3]);
                } else if (k.equals("h")) {
                    cur.fLo = num(p[1]);
                    cur.step = num(p[2]);
                    cur.h = new double[p.length - 3];
                    for (int i = 3; i < p.length; i++) cur.h[i - 3] = num(p[i]);
                } else if (k.equals("nobs")) {
                    cur.nObs = Integer.parseInt(p[1]);
                    cur.s = new double[cur.nObs + 1];
                    cur.absent = new boolean[cur.nObs + 1];
                    java.util.Arrays.fill(cur.s, Double.NaN);
                } else if (k.equals("s")) {
                    int n = Integer.parseInt(p[1]);
                    if (p[2].equals("absent")) cur.absent[n] = true;
                    else cur.s[n] = num(p[2]);
                } else if (k.equals("tail")) {
                    cur.tailA = num(p[1]);
                    cur.tailB = num(p[2]);
                    cur.tailRes = new double[p.length - 3];
                    for (int i = 3; i < p.length; i++) cur.tailRes[i - 3] = num(p[i]);
                } else if (k.equals("half")) {
                    int n = Integer.parseInt(p[1]);
                    if (cur.halfRel.length <= n) {
                        double[] nh = new double[n + 1];
                        java.util.Arrays.fill(nh, Double.NaN);
                        System.arraycopy(cur.halfRel, 0, nh, 0, cur.halfRel.length);
                        cur.halfRel = nh;
                    }
                    cur.halfRel[n] = num(p[2]);
                } else if (k.equals("noise")) {
                    cur.noiseM = num(p[1]);
                    for (int i = 0; i < Orders.BANDS && i + 2 < p.length; i++) cur.noiseQ[i] = num(p[i + 2]);
                } else if (k.equals("coh")) {
                    for (int i = 0; i < Orders.BANDS && i + 1 < p.length; i++) cur.coh[i] = num(p[i + 1]);
                } else if (k.equals("end")) {
                    if ("on".equals(curName)) m.on = cur;
                    else if ("off".equals(curName)) m.off = cur;
                    else if ("on-low".equals(curName) && m.on != null) m.on.low = cur;
                    else if ("off-low".equals(curName) && m.off != null) m.off.low = cur;
                    else throw new IOException(f + ": layer " + curName);
                    cur = null;
                } else throw new IOException(f + ": unknown key " + k);
            }
            int maxN = 0;
            for (double[] q : ph) maxN = Math.max(maxN, (int) q[0]);
            m.phase = new double[maxN + 1];
            m.phaseR = new double[maxN + 1];
            java.util.Arrays.fill(m.phase, Double.NaN);
            for (double[] q : ph) {
                m.phase[(int) q[0]] = q[1];
                m.phaseR[(int) q[0]] = q[2];
            }
            return m;
        } finally {
            r.close();
        }
    }

    static double num(String s) {
        return s.equals("nan") ? Double.NaN : Double.parseDouble(s);
    }
}
