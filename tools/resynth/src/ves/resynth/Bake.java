package ves.resynth;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converts a physics bake (manifest.json, spec.txt beside it, wav/) into an aligned pack for ves.core.PackVoice.
 * Every loop of the bake starts at crank phase 0 and holds a whole number of engine cycles in its frames (frames =
 * round(cycles x 120 x rate / ref_rpm)); the pack names it at R = round(ref_rpm) and N = floor(cycles x 2646000 /
 * R) samples at 22050 Hz. The loop is resampled as one period of a periodic signal: its frames-point DFT (Bluestein)
 * goes into an N-point inverse DFT, DC dropped, raised-cosine taper from 8.5 to 9.5 kHz and nothing above (PackVoice
 * plays loops up to about 1.1x faster between grid points). The cycles and crank phase 0 stay; the pitch moves by
 * ref_rpm / R (under 0.1 %), and a shortfall under one sample is PackVoice.playAligned's to handle. Loads 0 and 1
 * become off and on, others a load layer of their own. Events (pops, bubbles, shift sounds) go through the
 * Resampler. pack.txt's level line trims the pack to PackRenderer.TARGET_DB as PackRenderer writes it. The pack is
 * built and checked with PackLoader in memory before anything is written; the output is refused inside a git
 * worktree. Deterministic: two runs give the same bytes.
 */
public final class Bake {
    private Bake() {
    }

    static final String HEADER = "# converted by ves.resynth bake (private: never commit)";
    static final double TAPER_LO = 8500, TAPER_HI = 9500;
    static final int SR = PackRenderer.SR;

    /** the pack's files (pack.txt first), its level trim (dB) and samples */
    public static final class Result {
        public final Map<String, byte[]> files = new LinkedHashMap<String, byte[]>();
        public double trim;
        public long samples;
        public int loops, events;
    }

    /** reads and converts the bake; nothing is written */
    public static Result convert(File manifest, String name) throws IOException {
        if (name == null || name.trim().length() == 0 || name.trim().length() > 40) throw new IOException("pack name must be 1-40 characters");
        File dir = manifest.getAbsoluteFile().getParentFile();
        Object root = Json.parse(new String(java.nio.file.Files.readAllBytes(manifest.toPath()), "UTF-8"));
        if (!(root instanceof Map)) throw new IOException("manifest: not an object");
        Map<?, ?> m = (Map<?, ?>) root;
        int rate = (int) whole(m, "sample_rate");
        if (rate <= 0 || whole(m, "channels") != 1 || !"pcm16".equals(m.get("sample_format")))
            throw new IOException("manifest: needs a positive sample_rate, channels 1, sample_format pcm16");
        Map<String, String> spec = spec(new File(dir, "spec.txt"));
        int cyl = Math.max(1, Math.min(16, specInt(spec, "cylinders", 4)));
        int redline = specInt(spec, "rev_limit_rpm", 6500), popRpm = specInt(spec, "pop_min_rpm", 4000);
        Object clips = m.get("clips");
        if (!(clips instanceof List)) throw new IOException("manifest: no clips");
        Result r = new Result();
        StringBuilder loops = new StringBuilder(), events = new StringBuilder();
        List<double[]> evData = new ArrayList<double[]>();
        List<String> evNames = new ArrayList<String>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (Object o : (List<?>) clips) {
            if (!(o instanceof Map)) throw new IOException("manifest: a clip is not an object");
            Map<?, ?> c = (Map<?, ?>) o;
            String type = String.valueOf(c.get("type")), file = String.valueOf(c.get("file"));
            if (!file.matches("([A-Za-z0-9_-]+/)*[a-z0-9_-]{1,60}\\.wav")) throw new IOException("clip file " + file + ": not a plain [a-z0-9_-].wav path");
            String base = file.substring(file.lastIndexOf('/') + 1);
            if (!seen.add(base)) throw new IOException("clip file " + base + " twice");
            WavIO.Audio a = WavIO.read(new File(dir, file));
            if (a.rate != rate) throw new IOException(file + ": " + a.rate + " Hz, the manifest says " + rate);
            if (type.equals("loop")) {
                double ref = num(c, "ref_rpm"), cycles = num(c, "cycles"), load = num(c, "ref_load");
                long frames = whole(c, "frames");
                if (whole(c, "loop_start") != 0 || whole(c, "loop_end") != frames || frames != a.x.length)
                    throw new IOException(file + ": loop_start 0 and loop_end = frames = the file's length needed (" + c.get("loop_start") + ", "
                            + c.get("loop_end") + ", " + frames + ", " + a.x.length + ")");
                if (whole(c, "phase_zero_frame") != 0) throw new IOException(file + ": phase_zero_frame " + c.get("phase_zero_frame") + ", not 0");
                if (cycles != Math.rint(cycles) || cycles < 1) throw new IOException(file + ": cycles " + cycles + " not a whole number");
                int rpm = (int) Math.round(ref);
                if (!(ref > 0) || rpm < 300 || rpm > 12000) throw new IOException(file + ": ref_rpm " + ref);
                int n = (int) ((long) cycles * 2646000L / rpm);
                short[] pcm = toPcm(periodic(a.x, n, rate), file);
                r.files.put(base, WavIO.encode(pcm, SR));
                r.samples += n;
                r.loops++;
                loops.append("loop ").append(base).append(" rpm=").append(rpm).append(" load=").append(load(load, file)).append('\n');
            } else if (type.equals("event")) {
                String kind = kind(String.valueOf(c.get("name")));
                if (kind == null) {
                    System.out.println("skipped event " + c.get("name") + " (" + file + "): not a pop, bubble or shift");
                    continue;
                }
                double[] y = Resampler.resample(a.x, rate, SR);
                evData.add(y);
                evNames.add(base);
                events.append("event ").append(kind).append(' ').append(base).append('\n');
                r.samples += y.length;
                r.events++;
            } else throw new IOException(file + ": clip type " + type);
        }
        // events over full scale after resampling: all of them down by one factor (they keep their levels)
        double pk = 0;
        for (double[] y : evData) for (double v : y) pk = Math.max(pk, Math.abs(v));
        double g = pk > 0.999 ? 0.999 / pk : 1;
        for (int e = 0; e < evData.size(); e++) {
            double[] y = evData.get(e);
            for (int i = 0; i < y.length; i++) y[i] *= g;
            r.files.put(evNames.get(e), WavIO.encode(WavIO.toPcm(y), SR));
        }
        StringBuilder txt = new StringBuilder();
        txt.append(HEADER).append('\n');
        txt.append("name ").append(name.trim()).append('\n');
        txt.append("cylinders ").append(cyl).append('\n');
        txt.append("idle 800\n");
        txt.append("redline ").append(redline).append('\n');
        txt.append("aligned yes\n");
        txt.append("pop_rpm ").append(popRpm).append('\n');
        txt.append(loops).append(events);
        Map<String, byte[]> files = new LinkedHashMap<String, byte[]>();
        files.put("pack.txt", txt.toString().getBytes("UTF-8"));
        files.putAll(r.files);
        // the level trim, as PackRenderer.write measures it
        double db = PackRenderer.stageLevelDb(PackRenderer.load(files));
        r.trim = Math.max(-24, Math.min(12, Math.round((PackRenderer.TARGET_DB - db) * 10) / 10.0));
        txt.insert(txt.indexOf("aligned yes\n"), String.format(Locale.ROOT, "level %.1f\n", r.trim));
        files.put("pack.txt", txt.toString().getBytes("UTF-8"));
        PackRenderer.load(files);
        r.files.clear();
        r.files.putAll(files);
        return r;
    }

    /**
     * writes the pack into dir (refused inside a git worktree; dir may hold only files of this pack, which are
     * replaced)
     */
    public static void write(File dir, Result r) throws IOException {
        GitGuard.check(dir);
        File[] old = dir.listFiles();
        if (old != null)
            for (File f : old) if (!r.files.containsKey(f.getName())) throw new IOException(dir + " holds other files (" + f.getName() + ")");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot make " + dir);
        for (Map.Entry<String, byte[]> e : r.files.entrySet()) {
            java.io.OutputStream out = new java.io.FileOutputStream(new File(dir, e.getKey()));
            try {
                out.write(e.getValue());
            } finally {
                out.close();
            }
        }
    }

    /** x as one period (DFT), zero DC, tapered 8.5-9.5 kHz at 22050 Hz, back into n points: same cycles, same phase */
    static double[] periodic(double[] x, int n, int rate) {
        int m = x.length;
        double[] re = x.clone(), im = new double[m];
        Fft.dft(re, im, false);
        double[] yr = new double[n], yi = new double[n];
        double scale = (double) n / m;
        for (int b = 1; b < (m + 1) / 2 && b < (n + 1) / 2; b++) {
            double f = b * (double) SR / n, t = f <= TAPER_LO ? 1 : f >= TAPER_HI ? 0 : 0.5 + 0.5 * StrictMath.cos(Math.PI * (f - TAPER_LO) / (TAPER_HI - TAPER_LO));
            if (t == 0) break;
            yr[b] = re[b] * scale * t;
            yi[b] = im[b] * scale * t;
            yr[n - b] = yr[b];
            yi[n - b] = -yi[b];
        }
        Fft.dft(yr, yi, true);
        double[] y = new double[n];
        for (int i = 0; i < n; i++) y[i] = yr[i] / n;
        return y;
    }

    /** PCM16; a loop over full scale is turned down to -0.01 dBFS (PackVoice level-matches every loop anyway) */
    static short[] toPcm(double[] y, String loopName) {
        double pk = 0;
        for (double v : y) pk = Math.max(pk, Math.abs(v));
        if (pk > 0.999) {
            System.out.println(loopName + ": peak " + String.format(Locale.ROOT, "%.3f", pk) + " after resampling, turned down");
            double g = 0.999 / pk;
            y = y.clone();
            for (int i = 0; i < y.length; i++) y[i] *= g;
        }
        return WavIO.toPcm(y);
    }

    static String load(double v, String file) throws IOException {
        if (v == 0) return "off";
        if (v == 1) return "on";
        if (!(v > 0 && v < 1) || Math.abs(v * 1000 - Math.rint(v * 1000)) > 1e-6) throw new IOException(file + ": ref_load " + v + " (0..1, 3 decimals)");
        String s = String.format(Locale.ROOT, "%.3f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** the pack's event kind for a bake event name (decel_pop_N, decel_bubble_N, shift_up, shift_down), else null */
    static String kind(String name) {
        if (name.equals("shift_up") || name.equals("shift_down")) return name;
        if (name.matches("(decel_)?bubble(_[0-9]+)?")) return "bubble";
        if (name.matches("(decel_)?pop(_[0-9]+)?")) return "pop";
        return null;
    }

    static double num(Map<?, ?> m, String key) throws IOException {
        Object v = m.get(key);
        if (!(v instanceof Double)) throw new IOException("manifest: " + key + " missing or not a number");
        return (Double) v;
    }

    static long whole(Map<?, ?> m, String key) throws IOException {
        double v = num(m, key);
        if (v != Math.rint(v) || Math.abs(v) > 1e9) throw new IOException("manifest: " + key + " " + v + " not a whole number");
        return (long) v;
    }

    /** spec.txt: key=value lines */
    static Map<String, String> spec(File f) throws IOException {
        Map<String, String> s = new LinkedHashMap<String, String>();
        if (!f.isFile()) throw new IOException("no spec.txt beside the manifest (" + f + ")");
        for (String line : new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8").split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) s.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return s;
    }

    static int specInt(Map<String, String> s, String key, int def) throws IOException {
        String v = s.get(key);
        if (v == null) return def;
        if (!v.matches("[0-9]{1,6}(\\.0+)?")) throw new IOException("spec.txt: " + key + "=" + v + " not a whole number");
        return (int) Double.parseDouble(v);
    }
}
