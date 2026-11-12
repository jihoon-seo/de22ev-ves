package ves.resynth;

import java.io.File;
import java.io.IOException;
import java.util.Random;

/**
 * Renders a {@link Model} into a pack of crank-aligned loops for ves.core.PackVoice ({@code aligned yes}):
 * on and off layers at the {@link #GRID} rpm, 800-7350. Every loop holds K whole engine cycles (K a multiple of
 * 4: even, so half orders close too, and whole periods of the crank wobble) and starts at crank phase 0:
 * N * rpm = K * 2646000 exactly (2646000 = 120 * 22050), 0.9 s to about 1.4 s. Synthesis is additive in the loop-length DFT at 22050 Hz (no decimation): order n on bin K n,
 * half order n + 1/2 on K n + K/2, so a loop is exactly periodic. Orders below 40 Hz are dropped and nothing
 * goes above 8.5 kHz (PackVoice plays the top loop up to 1.22x faster). Phases per order are the same in every
 * loop and both layers (measured where coherent, else seeded random), so PackVoice's linear crossfade sums
 * neighbours in phase. Noise is shaped per 1/3-octave band in the same DFT (periodic in the loop) with random
 * phases, plus the crank-wobble skirt beside each order: the Bessel sidebands of one phase wobble shared by all
 * orders, loops and layers, so envelopes stay flat, also through a crossfade. Each loop peaks at -1 dBFS
 * (PackVoice brings every loop to the same RMS anyway). Seeded and StrictMath-based: two renders are byte-identical.
 */
public final class PackRenderer {
    private PackRenderer() {
    }

    public static final int SR = 22050, CYCLE = 120 * SR;
    public static final int[] GRID = {800, 900, 1000, 1125, 1260, 1400, 1575, 1764, 2000, 2250, 2520, 2800, 3150, 3500, 3920, 4410,
            4900, 5400, 6000, 6615, 7350};
    public static final double CAP = 8500, LOW = 40, PEAK = 0.8913, MIN_RMS = 0.0188, MIN_SECONDS = 0.9;
    /** the crank wobble repeats every WOBBLE cycles, so every loop holds a multiple of it; Bessel sidebands kept */
    /**
     * the mean level (dB) of the pack through the car's speaker stage (ves.core.OutputStage: 120 Hz high-pass, +11 dB
     * on 400-2500 Hz, drive), over 800-6000 rpm on and off load; pack.txt's level line trims the pack to it. The
     * built-in packs measure -9.9 (v6) to -4.8 dB (v8); recordings with more energy in the lifted band would sound
     * louder and drive the soft clip harder.
     */
    public static final double TARGET_DB = -7;
    static final int WOBBLE = 4, SIDEBANDS = 4;

    public static final class Options {
        public boolean noise = true, skirt = true;
        public String prefix = "rs";
        public int idle = 800;
    }

    /** cycles in the loop at rpm: the smallest K, a multiple of WOBBLE, with N whole and at least MIN_SECONDS long */
    public static int cycles(int rpm) {
        int min = (int) Math.ceil(MIN_SECONDS * SR);
        for (int k = WOBBLE; ; k += WOBBLE) {
            long p = (long) k * CYCLE;
            if (p % rpm == 0 && p / rpm >= min) return k;
        }
    }

    public static int length(int rpm) {
        return (int) ((long) cycles(rpm) * CYCLE / rpm);
    }

    /** seeded random phases, one per order (and one per half order) */
    static double[] randomPhases(long seed, int n) {
        Random r = new Random(seed);
        double[] p = new double[n];
        for (int i = 0; i < n; i++) p[i] = 2 * Math.PI * r.nextDouble();
        return p;
    }

    /** Bessel function of the first kind J_m(x), m >= 0, by its power series (|x| small here) */
    static double besselJ(int m, double x) {
        double t = 1, s;
        for (int i = 1; i <= m; i++) t *= x / 2 / i;
        s = t;
        for (int k = 1; k < 60; k++) {
            t *= -(x * x / 4) / (k * (double) (k + m));
            s += t;
            if (Math.abs(t) < 1e-17 * Math.abs(s)) break;
        }
        return s;
    }

    /** one loop as doubles (before the -1 dBFS normalisation) */
    static double[] synth(Model m, Model.Layer l, int rpm, int layer, Options o) {
        int k = cycles(rpm), n = (int) ((long) k * CYCLE / rpm);
        double fc = rpm / 120.0;
        double[] re = new double[n], im = new double[n];
        int nMax = (int) Math.floor(CAP / fc);
        double[] ph = randomPhases(m.seed, nMax + 2), phHalf = randomPhases(m.seed ^ 0x5DEECE66DL, nMax + 2);
        Random noise = new Random(m.seed * 1000003L + rpm * 2L + layer);
        double strongest = Double.NEGATIVE_INFINITY;
        for (int o2 = 1; o2 <= nMax; o2++) {
            double d = l.predict(o2, rpm);
            if (!Double.isNaN(d) && o2 * fc >= LOW) strongest = Math.max(strongest, d);
        }
        // crank wobble: one sinusoidal phase wobble of WOBBLE cycles, its phase the same in every loop and layer
        // (every K is a multiple of WOBBLE, so PackVoice's aligned crossfade sees the same wobble in both loops),
        // applied as true phase modulation: order n plays A cos(2 pi n theta + phi + mu sin(2 pi theta / WOBBLE
        // + psi)), i.e. Bessel sidebands J_j(mu) every K / WOBBLE bins, with mu from the band's skirt power:
        // 2 J_1^2 / J_0^2 = kappa for small mu (mu = sqrt(2 ln(1 + kappa))). The envelope stays flat.
        double psi = randomPhases(m.seed ^ 0x2545F4914F6CDD1DL, 1)[0];
        int side = k / WOBBLE;
        for (int o2 = (int) Math.ceil(LOW / fc); o2 <= nMax; o2++) {
            double d = l.predict(o2, rpm);
            if (Double.isNaN(d)) continue;
            double a = Math.pow(10, d / 20), phi = m.phaseOf(o2, ph);
            double kappa = o.skirt ? Math.min(1, l.coh(clampBand(o2 * fc))) : 0;
            double mu = Math.sqrt(2 * Math.log(1 + kappa));
            int jMax = mu > 0 ? SIDEBANDS : 0;
            for (int j = -jMax; j <= jMax; j++) {
                int b = k * o2 + j * side;
                if (b <= 0 || b * (double) SR / n > CAP) continue;
                double bj = besselJ(Math.abs(j), mu) * (j < 0 && (j & 1) != 0 ? -1 : 1);
                add(re, im, b, Math.abs(bj) * a * n / 2, phi + j * psi + (bj < 0 ? Math.PI : 0));
            }
        }
        for (int o2 = 0; o2 < l.halfRel.length; o2++) {
            double f = (o2 + 0.5) * fc;
            if (f < LOW || f > CAP) continue;
            double d = l.half(o2, rpm, strongest);
            if (!Double.isNaN(d)) add(re, im, k * o2 + k / 2, Math.pow(10, d / 20) * n / 2, phHalf[o2]);
        }
        if (o.noise) {
            double df = SR / (double) n;
            for (int b = 1; b < n / 2; b++) {
                double f = b * df;
                if (f > CAP) break;
                if (b % k == 0) continue;
                double psd = Math.pow(10, l.noise(clampBand(f), rpm) / 10);
                add(re, im, b, n * Math.sqrt(psd * df / 2), 2 * Math.PI * noise.nextDouble());
            }
        }
        Fft.dft(re, im, true);
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = re[i] / n;
        return x;
    }

    /** the 1/3-octave band of f, the end bands below and above */
    static int clampBand(double f) {
        int b = Orders.band(f);
        return b >= 0 ? b : f < Orders.bandCentre(0) ? 0 : Orders.BANDS - 1;
    }

    /** adds magnitude mag at phase ph to bin b and its conjugate */
    private static void add(double[] re, double[] im, int b, double mag, double ph) {
        int n = re.length;
        if (b <= 0 || b >= n - b) return;
        double c = mag * StrictMath.cos(ph), s = mag * StrictMath.sin(ph);
        re[b] += c;
        im[b] += s;
        re[n - b] += c;
        im[n - b] -= s;
    }

    /** one loop as PCM16, peak at -1 dBFS */
    public static short[] loop(Model m, Model.Layer l, int rpm, int layer, Options o) throws IOException {
        double[] x = synth(m, l, rpm, layer, o);
        double pk = 0, ss = 0;
        for (double v : x) {
            pk = Math.max(pk, Math.abs(v));
            ss += v * v;
        }
        if (!(pk > 0)) throw new IOException(rpm + " rpm: silent loop");
        double g = PEAK / pk;
        for (int i = 0; i < x.length; i++) x[i] *= g;
        double rms = Math.sqrt(ss / x.length) * g;
        if (rms < MIN_RMS) throw new IOException(rpm + " rpm: loop RMS " + rms + " below " + MIN_RMS + " (crest factor too high)");
        return WavIO.toPcm(x);
    }

    /**
     * writes pack.txt and the loops into dir (refused inside a git worktree; any earlier loops and pack.txt there
     * are replaced, other files make it fail). The off layer comes from offModel when given, else from m.
     */
    public static void write(File dir, String name, Model m, Model offModel, Options o) throws IOException {
        GitGuard.check(dir);
        if (name == null || name.trim().length() == 0 || name.length() > 40) throw new IOException("pack name must be 1-40 characters");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot make " + dir);
        String pat = "pack\\.txt|" + o.prefix + "_[0-9]{4}_(on|off)\\.wav";
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) if (!f.getName().matches(pat)) throw new IOException(dir + " holds other files (" + f.getName() + ")");
            for (File f : old) if (!f.delete()) throw new IOException("cannot replace " + f);
        }
        Model.Layer off = offModel != null && offModel.off != null ? offModel.off : m.off;
        StringBuilder txt = new StringBuilder();
        txt.append("# rendered by ves.resynth (private: never commit)\n");
        txt.append("name ").append(name.trim()).append('\n');
        txt.append("cylinders ").append(Math.max(1, Math.min(16, m.cyl))).append('\n');
        txt.append("idle ").append(o.idle).append('\n');
        txt.append("redline ").append(GRID[GRID.length - 1]).append('\n');
        txt.append("aligned yes\n");
        for (int layer = 0; layer < 2; layer++) {
            Model.Layer l = layer == 0 ? m.on : off;
            if (l == null) continue;
            String ln = layer == 0 ? "on" : "off";
            for (int rpm : GRID) {
                String f = String.format(java.util.Locale.ROOT, "%s_%04d_%s.wav", o.prefix, rpm, ln);
                WavIO.write16(new File(dir, f), loop(m, l, rpm, layer, o), SR);
                txt.append("loop ").append(f).append(" rpm=").append(rpm).append(" load=").append(ln).append('\n');
            }
        }
        Files.writeText(new File(dir, "pack.txt"), txt.toString());
        // the level trim needs the loops as PackVoice plays them: measure the untrimmed pack, then add the line
        double db = stageLevelDb(load(dir));
        double trim = Math.max(-24, Math.min(12, Math.round((TARGET_DB - db) * 10) / 10.0));
        txt.insert(txt.indexOf("aligned yes\n"), String.format(java.util.Locale.ROOT, "level %.1f\n", trim));
        Files.writeText(new File(dir, "pack.txt"), txt.toString());
    }

    static ves.core.Pack load(final File dir) throws IOException {
        try {
            return ves.core.PackLoader.load(new ves.core.PackLoader.Source() {
                public byte[] read(String name, int maxBytes) throws IOException {
                    File f = new File(dir, name);
                    if (f.length() > maxBytes) throw new IOException(name + " too large");
                    return java.nio.file.Files.readAllBytes(f.toPath());
                }
            });
        } catch (ves.core.PackError e) {
            throw new IOException("rendered pack does not load: " + e.getMessage());
        }
    }

    /**
     * the pack's mean level (dB) through the speaker stage's linear part (fed 60 dB down, far below the soft
     * clip), 1 s per point at 800-6000 rpm, on and off load, as PackVoice renders it (level trim included)
     */
    public static double stageLevelDb(ves.core.Pack p) {
        double sum = 0;
        int n = 0;
        for (double load : new double[] { 1, 0 }) {
            for (int rpm : new int[] { 800, 1200, 1600, 2000, 2500, 3000, 4000, 5000, 6000 }) {
                ves.core.PackVoice v = new ves.core.PackVoice(SR);
                v.setPack(p);
                float[] x = new float[SR];
                v.renderRaw(x, x.length, rpm, load, 0);
                ves.core.OutputStage st = new ves.core.OutputStage(SR);
                double ss = 0;
                for (int i = 0; i < x.length; i++) {
                    double y = st.process(x[i] * 0.001) / 0.001;
                    if (i >= SR / 10) ss += y * y; // past the high-pass's start
                }
                sum += 10 * Math.log10(Math.max(ss / (x.length - SR / 10), 1e-20));
                n++;
            }
        }
        return sum / n;
    }
}
