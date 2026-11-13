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
            + "  fit      <in.wav> <in.track.txt> <in.segments.txt> <out.vesmodel> [cyl=N] [seed=N] [model=auto|m1|m0]\n"
            + "  render   <in.vesmodel> <out-pack-dir> name=<pack name> [off=<other.vesmodel>] [noise=on|off|<dB>] [seed=N]\n"
            + "  bake     <manifest.json> <out-pack-dir> name=<pack name>\n"
            + "  ab bands <a.wav> <b.wav>\n"
            + "  ab track <in.wav> <in.track.txt> <in.segments.txt> <in.vesmodel> <out-dir> <name> [off=<other.vesmodel>] [noise=on|off|<dB>]\n"
            + "  demo     <pack-dir> <out-dir> <name> [track=<in.track.txt> segments=<in.segments.txt>]\n"
            + "  noise=<dB>: the model's noise at -40..0 dB (e.g. -12); on = 0\n";

    public static int run(String[] args) {
        try {
            if (args.length == 0) throw new IllegalArgumentException("no command");
            String c = args[0];
            if (c.equals("track")) return track(args);
            if (c.equals("segments")) return segments(args);
            if (c.equals("fit")) return fit(args);
            if (c.equals("render")) return render(args);
            if (c.equals("bake")) return bake(args);
            if (c.equals("ab")) return ab(args);
            if (c.equals("demo")) return demo(args);
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

    /** noise=on|off|<dB>: the noise's level re the model in dB, -40..0 (on = 0, off = negative infinity) */
    static double noiseDb(String[] a) {
        String v = opt(a, "noise", "on");
        if (v.equals("on")) return 0;
        if (v.equals("off")) return Double.NEGATIVE_INFINITY;
        if (v.matches("-?[0-9]{1,3}(\\.[0-9]{1,3})?")) {
            double d = Double.parseDouble(v);
            if (d >= -40 && d <= 0) return d;
        }
        throw new IllegalArgumentException(a[0] + ": noise=" + v + ": on, off or a level in dB from -40 to 0");
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
            // M0 is the extrapolation baseline: where it holds out better, it takes over below the measured rpm
            boolean m0 = which.equals("m0") || which.equals("auto") && rep.holdM0 < rep.holdM1;
            Model.Layer l = rep.m1;
            if (m0) {
                l.low = rep.m0Layer;
                l.lowBelow = rep.m1.rMin;
            }
            System.out.println(ln + ": " + c + " frames, rpm " + Math.round(rep.m1.rMin) + ".." + Math.round(rep.m1.rMax)
                    + ", in-range RMSE " + f2(rep.inRange) + " dB, low-end holdout M1 " + f2(rep.holdM1) + " dB, M0 "
                    + f2(rep.holdM0) + " dB (" + rep.holdRows + " order levels below " + Math.round(rep.split) + " rpm); shape only "
                    + f2(rep.shapeIn) + " / " + f2(rep.shapeM1) + " / " + f2(rep.shapeM0) + " dB -> "
                    + (m0 ? "M0 below " + Math.round(rep.m1.rMin) + " rpm, M1 above" : "M1"));
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

    /** the 100 ms A/B frames whose centre lies in a segment with this label */
    static boolean[] frames(Segments sg, int label, int samples) {
        boolean[] b = new boolean[samples / ThirdOctave.FRAME];
        for (int k = 0; k < b.length; k++) b[k] = sg.labelAt((k + 0.5) * ThirdOctave.FRAME / (double) PackRenderer.SR) == label;
        return b;
    }

    static int ab(String[] a) throws IOException {
        if (a.length < 2) throw new IllegalArgumentException("ab: bands or track");
        if (a[1].equals("bands")) {
            need(a, 4);
            double[] x = audio(a[2]), y = audio(a[3]);
            System.out.println("mean 1/3-octave distance " + f2(ThirdOctave.distance(x, y, PackRenderer.SR, null)) + " dB");
            return 0;
        }
        if (!a[1].equals("track")) throw new IllegalArgumentException("ab: bands or track");
        need(a, 8);
        double noise = noiseDb(a);
        File dir = new File(a[6]);
        GitGuard.check(dir);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot make " + dir);
        String name = a[7];
        if (!name.matches("[A-Za-z0-9_-]{1,60}")) throw new IllegalArgumentException("ab track: bad name " + name);
        double[] x = audio(a[2]);
        Track t = Track.read(new File(a[3]));
        Segments sg = Segments.read(new File(a[4]));
        Model m = Model.read(new File(a[5]));
        String offPath = opt(a, "off", null);
        Model off = offPath == null ? null : Model.read(new File(offPath));
        double[] y = TrackSynth.render(m, off, t, sg, x.length, PackRenderer.SR, m.seed, noise);
        WavIO.write(new File(dir, name + "_orig22k.wav"), x, PackRenderer.SR);
        WavIO.write(new File(dir, name + "_resynth_track.wav"), y, PackRenderer.SR);
        double on = ThirdOctave.distance(x, y, PackRenderer.SR, frames(sg, Segments.ON, x.length));
        double of = ThirdOctave.distance(x, y, PackRenderer.SR, frames(sg, Segments.OFF, x.length));
        System.out.println("mean 1/3-octave distance: on " + f2(on) + " dB, off " + f2(of) + " dB; wrote " + name
                + "_orig22k.wav and " + name + "_resynth_track.wav to " + dir);
        return 0;
    }

    static ves.core.Pack loadPack(final File dir) throws IOException {
        try {
            return ves.core.PackLoader.load(new ves.core.PackLoader.Source() {
                public byte[] read(String name, int maxBytes) throws IOException {
                    File f = new File(dir, name);
                    if (f.length() > maxBytes) throw new IOException(name + " is larger than " + maxBytes + " bytes");
                    return java.nio.file.Files.readAllBytes(f.toPath());
                }
            });
        } catch (ves.core.PackError e) {
            throw new IOException("PackLoader rejects " + dir + ": " + e.getMessage());
        }
    }

    /** PackVoice (with its output stage) along rpm and load per 256-sample block */
    static short[] play(ves.core.Pack p, double[] rpm, double[] load, double gain) {
        ves.core.PackVoice v = new ves.core.PackVoice(PackRenderer.SR);
        v.setPack(p);
        int blk = 256;
        short[] out = new short[rpm.length * blk];
        ves.core.EngineParams ep = new ves.core.EngineParams();
        for (int k = 0; k < rpm.length; k++) {
            ep.rpm = rpm[k];
            ep.load = load[k];
            ep.regen = 0;
            v.render(out, k * blk, blk, ep, gain);
        }
        return out;
    }

    static int demo(String[] a) throws IOException {
        need(a, 4);
        File dir = new File(a[2]);
        GitGuard.check(dir);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot make " + dir);
        String name = a[3];
        if (!name.matches("[A-Za-z0-9_-]{1,60}")) throw new IllegalArgumentException("demo: bad name " + name);
        ves.core.Pack p = loadPack(new File(a[1]));
        double bs = 256.0 / PackRenderer.SR;
        // 800 -> 7000 -> 800 rpm (6 s each way) on load, the same off load, then 3 s idle at 800
        int sweep = (int) Math.round(6 / bs), idle = (int) Math.round(3 / bs);
        int n = 4 * sweep + idle;
        double[] rpm = new double[n], load = new double[n];
        for (int k = 0; k < 4 * sweep; k++) {
            int q = k % (2 * sweep);
            double u = q < sweep ? q / (double) sweep : 2 - q / (double) sweep;
            rpm[k] = 800 * Math.pow(7000 / 800.0, u);
            load[k] = k < 2 * sweep ? 1 : 0;
        }
        for (int k = 4 * sweep; k < n; k++) {
            rpm[k] = 800;
            load[k] = 1;
        }
        WavIO.write16(new File(dir, name + "_demo_sweep.wav"), play(p, rpm, load, 1), PackRenderer.SR);
        String tp = opt(a, "track", null), sp = opt(a, "segments", null);
        if (tp != null) {
            Track t = Track.read(new File(tp));
            Segments sg = sp == null ? null : Segments.read(new File(sp));
            int m = (int) (t.tEnd() / bs);
            double[] r2 = new double[m], l2 = new double[m];
            double last = 1;
            for (int k = 0; k < m; k++) {
                double tt = (k + 0.5) * bs;
                r2[k] = t.rpmAt(tt);
                int lab = sg == null ? Segments.ON : sg.labelAt(tt);
                if (lab == Segments.ON) last = 1;
                else if (lab == Segments.OFF) last = 0;
                l2[k] = last;
            }
            WavIO.write16(new File(dir, name + "_demo_track.wav"), play(p, r2, l2, 1), PackRenderer.SR);
        }
        System.out.println("wrote " + name + "_demo_sweep.wav" + (tp != null ? " and " + name + "_demo_track.wav" : "") + " to " + dir
                + " (" + p.name + ")");
        return 0;
    }

    /** a physics bake (manifest.json, spec.txt, wav/) into an aligned pack: see Bake */
    static int bake(String[] a) throws IOException {
        need(a, 3);
        File dir = new File(a[2]);
        GitGuard.check(dir);
        String name = opt(a, "name", null);
        if (name == null) throw new IllegalArgumentException("bake: name=<pack name> missing");
        Bake.Result r = Bake.convert(new File(a[1]), name);
        Bake.write(dir, r);
        long bytes = 0;
        for (byte[] b : r.files.values()) bytes += b.length;
        System.out.println(String.format(Locale.ROOT, "level %.1f", r.trim));
        System.out.println("wrote " + r.loops + " loops, " + r.events + " events + pack.txt, " + r.samples + " samples, " + bytes
                + " bytes, to " + dir);
        return 0;
    }

    static int render(String[] a) throws IOException {
        need(a, 3);
        File dir = new File(a[2]);
        GitGuard.check(dir);
        double noise = noiseDb(a);
        Model m = Model.read(new File(a[1]));
        String offPath = opt(a, "off", null);
        Model off = offPath == null ? null : Model.read(new File(offPath));
        String seed = opt(a, "seed", null);
        if (seed != null) m.seed = Long.parseLong(seed);
        PackRenderer.Options o = new PackRenderer.Options();
        o.noise = noise > Double.NEGATIVE_INFINITY;
        if (o.noise) o.noiseDb = noise;
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
