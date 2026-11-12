package ves.resynth;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * Command line; see tools/resynth/README.md. Exit 0 on success, 1 on an error or bad arguments, 2 when an output
 * path is inside a git worktree (private data never goes there).
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static final String USAGE = "usage:\n"
            + "  track    <in.wav> <out.track.txt> [cyl=N]\n"
            + "  segments <in.wav> <in.track.txt> <out.segments.txt>\n"
            + "  fit      <in.wav> <in.track.txt> <in.segments.txt> <out.vesmodel> [cyl=N] [seed=N] [model=m1|m0|auto]\n"
            + "  render   <in.vesmodel> <out-pack-dir> name=<pack name> [off=<other.vesmodel>] [noise=on|off] [seed=N]\n";

    public static int run(String[] args) {
        try {
            if (args.length == 0) throw new IllegalArgumentException("no command");
            String c = args[0];
            if (c.equals("track")) return track(args);
            if (c.equals("segments")) return segments(args);
            if (c.equals("fit")) return fit(args);
            if (c.equals("render")) return render(args);
            throw new IllegalArgumentException("unknown command " + c);
        } catch (GitGuard.Refused e) {
            System.err.println(e.getMessage());
            return 2;
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.print(USAGE);
            return 1;
        } catch (IOException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
    }

    // ------------------------------------------------------------------ helpers

    static String opt(String[] a, String key, String def) {
        for (String s : a) if (s.startsWith(key + "=")) return s.substring(key.length() + 1);
        return def;
    }

    static void need(String[] a, int n) {
        int plain = 0;
        for (String s : a) if (s.indexOf('=') < 0) plain++;
        if (plain < n) throw new IllegalArgumentException(a[0] + ": needs " + (n - 1) + " paths");
    }

    /** a recording at the analysis rate (22050 Hz) */
    static double[] audio(String path) throws IOException {
        WavIO.Audio a = WavIO.read(new File(path));
        return a.rate == PackRenderer.SR ? a.x : Resampler.resample(a.x, a.rate, PackRenderer.SR);
    }

    static File out(String path) throws IOException {
        File f = new File(path);
        GitGuard.check(f);
        File p = f.getAbsoluteFile().getParentFile();
        if (p != null && !p.isDirectory() && !p.mkdirs()) throw new IOException("cannot make " + p);
        return f;
    }

    static String f2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    // ------------------------------------------------------------------ commands

    static int track(String[] a) throws IOException {
        need(a, 3);
        File o = out(a[2]);
        double[] x = audio(a[1]);
        int cyl = Integer.parseInt(opt(a, "cyl", "0"));
        Tracker.Result r = Tracker.track(x, PackRenderer.SR, cyl);
        System.out.print(r.log);
        Track t = r.track;
        double lo = Double.POSITIVE_INFINITY, hi = 0;
        for (double v : t.fc) {
            lo = Math.min(lo, 120 * v);
            hi = Math.max(hi, 120 * v);
        }
        System.out.println("cylinders " + r.cylinders + ", rpm " + Math.round(lo) + ".." + Math.round(hi) + ", " + t.frames() + " frames");
        t.write(o, "cylinders " + r.cylinders);
        return 0;
    }

    static int segments(String[] a) throws IOException {
        need(a, 4);
        File o = out(a[3]);
        double[] x = audio(a[1]);
        Track t = Track.read(new File(a[2]));
        Segments s = Segments.propose(x, PackRenderer.SR, t);
        s.write(o);
        System.out.println("on " + f2(s.seconds(Segments.ON)) + " s, off " + f2(s.seconds(Segments.OFF)) + " s, excluded "
                + f2(s.seconds(Segments.EXCLUDED)) + " s in " + s.count() + " segments");
        return 0;
    }

    static int fit(String[] a) throws IOException {
        need(a, 5);
        File o = out(a[4]);
        double[] x = audio(a[1]);
        Track t = Track.read(new File(a[2]));
        Segments sg = Segments.read(new File(a[3]));
        int cyl = Integer.parseInt(opt(a, "cyl", "4"));
        String which = opt(a, "model", "auto");
        Fitter.Options fo = new Fitter.Options();
        Orders.Frames fr = Orders.analyse(x, PackRenderer.SR, t, fo.cap);
        Model m = new Model();
        m.cyl = cyl;
        m.seed = Long.parseLong(opt(a, "seed", "1"));
        for (int layer = Segments.ON; layer <= Segments.OFF; layer++) {
            boolean[] use = new boolean[fr.count()];
            int c = 0;
            for (int k = 0; k < use.length; k++) if (use[k] = sg.labelAt(fr.time[k]) == layer) c++;
            String ln = layer == Segments.ON ? "on" : "off";
            if (c < 20) {
                System.out.println(ln + ": " + c + " frames, not fitted");
                continue;
            }
            Fitter.Report rep = Fitter.report(fr, use, cyl, fo);
            boolean m0 = which.equals("m0") || which.equals("auto") && rep.holdM0 < rep.holdM1;
            Model.Layer l = m0 ? rep.m0Layer : rep.m1;
            System.out.println(ln + ": " + c + " frames, rpm " + Math.round(rep.m1.rMin) + ".." + Math.round(rep.m1.rMax)
                    + ", in-range RMSE " + f2(rep.inRange) + " dB, low-end holdout M1 " + f2(rep.holdM1) + " dB, M0 "
                    + f2(rep.holdM0) + " dB (" + rep.holdRows + " order levels below " + Math.round(rep.split) + " rpm) -> "
                    + (m0 ? "M0" : "M1"));
            if (layer == Segments.ON) m.on = l;
            else m.off = l;
            if (layer == Segments.ON) {
                double[][] ph = Fitter.phases(fr, use, fo.mask);
                m.phase = ph[0];
                m.phaseR = ph[1];
                int coherent = 0, measured = 0;
                for (int n = 1; n < ph[0].length; n++)
                    if (!Double.isNaN(ph[0][n])) {
                        measured++;
                        if (ph[1][n] >= 0.5) coherent++;
                    }
                System.out.println("phases: " + coherent + " of " + measured + " measured orders coherent (R >= 0.5)");
            }
        }
        if (m.on == null) throw new IOException("no on-load frames to fit");
        m.write(o, "fitted from a private recording; cylinders " + cyl);
        return 0;
    }

    static int render(String[] a) throws IOException {
        need(a, 3);
        File dir = new File(a[2]);
        GitGuard.check(dir);
        Model m = Model.read(new File(a[1]));
        String offPath = opt(a, "off", null);
        Model off = offPath == null ? null : Model.read(new File(offPath));
        String seed = opt(a, "seed", null);
        if (seed != null) m.seed = Long.parseLong(seed);
        PackRenderer.Options o = new PackRenderer.Options();
        o.noise = !"off".equals(opt(a, "noise", "on"));
        String name = opt(a, "name", null);
        if (name == null) throw new IllegalArgumentException("render: name=<pack name> missing");
        PackRenderer.write(dir, name, m, off, o);
        long bytes = 0;
        File[] fs = dir.listFiles();
        for (File f : fs) bytes += f.length();
        System.out.println("wrote " + (fs.length - 1) + " loops + pack.txt, " + bytes + " bytes, to " + dir);
        return 0;
    }
}
