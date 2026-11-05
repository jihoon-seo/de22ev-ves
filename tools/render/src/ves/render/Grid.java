/*
 * ves.render: offline engine-sound renderer (host tool, not shipped to the car).
 * Part of the Java port of DasEtwas/enginesound <https://github.com/DasEtwas/enginesound>,
 * upstream commit e5fcca587397c0c8ba9c9d24874b951fed74d260 (loop recipe after its README).
 * Copyright (c) 2020 DasEtwas. MIT License; the full notice is in THIRD_PARTY.md.
 * The Java port and its throttle extension are MIT as well.
 */
package ves.render;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * {@code Grid <engine.txt|.esc> <outdir> [samplerate=22050] [seed=1]}: renders the loop grid
 * rpm {@link #RPMS} x load {on = throttle 1, off = throttle 0} into
 * {@code <outdir>/<engine>_<rpm>_<on|off>.wav} plus {@code pack.txt}.
 * <p>Each loop: fresh engine, 2 s warm-up dropped, run on to the next crank-phase wrap, then a
 * whole number N of engine cycles (round(N * 120 * sr / rpm) samples, N closest to
 * {@link #LOOP_SECONDS}), so all loops, on and off, start at crank phase 0 ({@code aligned yes}
 * in pack.txt). A {@link #CROSSFADE_SECONDS} crossfade from the samples following the loop end
 * (again phase 0) into the loop start evens out the noise at the seam.
 * One gain for the whole pack: the loudest loop peaks at -1 dBFS, relative levels are kept.
 */
public final class Grid {
    private Grid() {
    }

    public static final int[] RPMS = {800, 1100, 1500, 2000, 2600, 3300, 4000, 4800, 5600, 6500};
    public static final float LOOP_SECONDS = 0.8f;
    public static final float CROSSFADE_SECONDS = 0.005f;

    public static void main(String[] args) throws IOException {
        if (args.length < 2 || args.length > 4) {
            System.err.println("usage: Grid <engine.txt> <outdir> [samplerate=22050] [seed=1]");
            System.exit(2);
        }
        long t0 = System.nanoTime(); // timing printout only, never reaches the output
        EngineConfig cfg = EngineConfig.load(args[0]);
        String engine = baseName(args[0]);
        File dir = new File(args[1]);
        int sr = args.length > 2 ? (int) Render.opt(args[2]) : 22050;
        long seed = args.length > 3 ? Render.opt(args[3]) : 1L;

        float[][] loops = new float[RPMS.length * 2][];
        String[] names = new String[loops.length];
        boolean dampened = false;
        float maxPeak = 0.0f;
        double maxStartPhase = 0;
        double[] phase = new double[1];
        for (int r = 0; r < RPMS.length; r++) {
            for (int l = 0; l < 2; l++) {
                int k = r * 2 + l;
                Generator g = cfg.generator(sr, seed);
                loops[k] = loop(g, RPMS[r], l == 0 ? 1.0f : 0.0f, sr, phase);
                maxStartPhase = Math.max(maxStartPhase, phase[0]);
                dampened |= g.everDampened;
                names[k] = engine + "_" + RPMS[r] + "_" + (l == 0 ? "on" : "off") + ".wav";
                float p = Wav.peak(loops[k]);
                if (Float.isNaN(p)) {
                    System.err.println("Grid: NaN/inf in " + names[k]);
                    System.exit(1);
                }
                maxPeak = Math.max(maxPeak, p);
            }
        }
        double gain = maxPeak > 0 ? Wav.MINUS_1_DBFS / maxPeak : 1.0;
        StringBuilder pack = new StringBuilder();
        pack.append("name ").append(engine).append('\n');
        pack.append("cylinders ").append(cfg.cylinders).append('\n');
        pack.append("idle ").append(RPMS[0]).append('\n');
        pack.append("redline ").append(RPMS[RPMS.length - 1]).append('\n');
        pack.append("aligned yes\n"); // every loop starts at crank phase 0 and holds whole cycles
        int total = 0;
        for (int k = 0; k < loops.length; k++) {
            Wav.write(new File(dir, names[k]).getPath(), loops[k], gain, sr);
            pack.append("loop ").append(names[k]).append(" rpm=").append(RPMS[k / 2])
                    .append(" load=").append(k % 2 == 0 ? "on" : "off")
                    .append(" start=0 end=").append(loops[k].length).append('\n');
            total += loops[k].length;
        }
        FileOutputStream fo = new FileOutputStream(new File(dir, "pack.txt"));
        try {
            fo.write(pack.toString().getBytes("UTF-8"));
        } finally {
            fo.close();
        }
        long ms = (System.nanoTime() - t0) / 1000000L;
        System.out.println("pack " + engine + ": " + loops.length + " loops, " + total + " samples ("
                + String.format(java.util.Locale.ROOT, "%.2f", total / (double) sr) + " s) at " + sr
                + " Hz, raw_peak=" + maxPeak + " gain=" + (float) gain + " nonfinite=0 dampened=" + dampened
                + " start_phase_max=" + String.format(java.util.Locale.ROOT, "%.3f", maxStartPhase)
                + " samples time_ms=" + ms);
    }

    /** loop length in samples for an rpm: whole engine cycles closest to LOOP_SECONDS */
    public static int loopSamples(float rpm, int sr) {
        double cycle = 120.0 / rpm;
        long cycles = Math.max(1L, Math.round(LOOP_SECONDS / cycle));
        return (int) Math.round(cycles * cycle * sr);
    }

    /**
     * One aligned loop. After the warm-up the model runs on (output dropped) until its crank
     * phase (Engine.crankshaftPos, 0..1 per 4-stroke cycle) wraps to 0, so sample 0 of every loop
     * is the first sample of a cycle. startPhase[0] receives how far past the wrap sample 0 is, in
     * samples (0 &lt;= x &lt; 1). The loop holds loopSamples() samples, i.e. N whole cycles; sample n
     * is again at crank phase ~0, and the CROSSFADE_SECONDS that follow are faded into the start
     * (same crank angles, so the fade only evens out noise).
     */
    static float[] loop(Generator g, float rpm, float throttle, int sr, double[] startPhase) {
        int n = loopSamples(rpm, sr);
        int x = Math.round(CROSSFADE_SECONDS * sr);
        Render.render(g, rpm, throttle, Math.round(Render.WARMUP_SECONDS * sr), 0);
        Engine e = g.engine;
        float inc = e.rpm / ((float) sr * 120.0f); // as Generator.generate
        float[] one = new float[1];
        while (!(Gen.fract(e.crankshaftPos + inc) < e.crankshaftPos)) { // this sample does not wrap
            g.generate(one, 0, 1);
        }
        if (startPhase != null) {
            startPhase[0] = Gen.fract(e.crankshaftPos + inc) / (double) inc; // crank phase of sample 0
        }
        float[] y = new float[n + x];
        g.generate(y, 0, n + x);
        float[] out = new float[n];
        System.arraycopy(y, 0, out, 0, n);
        // out[0] continues y[n - 1] (it is mostly y[n]), and fades to y[x] (the loop's own start)
        for (int i = 0; i < x; i++) {
            float w = (float) (i + 0.5) / x;
            out[i] = y[i] * w + y[n + i] * (1.0f - w);
        }
        return out;
    }

    static String baseName(String path) {
        String b = new File(path).getName();
        int dot = b.lastIndexOf('.');
        return dot > 0 ? b.substring(0, dot) : b;
    }
}
