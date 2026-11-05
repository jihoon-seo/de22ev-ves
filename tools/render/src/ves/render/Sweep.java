/*
 * ves.render: offline engine-sound renderer (host tool, not shipped to the car).
 * Part of the Java port of DasEtwas/enginesound <https://github.com/DasEtwas/enginesound>,
 * upstream commit e5fcca587397c0c8ba9c9d24874b951fed74d260.
 * Copyright (c) 2020 DasEtwas. MIT License; the full notice is in THIRD_PARTY.md.
 * The Java port and its throttle extension are MIT as well.
 */
package ves.render;

import java.io.IOException;

/**
 * {@code Sweep <engine.txt|.esc> <out.wav> [seconds=12] [rpm_lo=800] [rpm_hi=6000] [samplerate=22050] [seed=1]}
 * <p>Demo straight from the model (no loops): rpm rises linearly lo to hi over the first half
 * on load (throttle 1), then falls back to lo on overrun (throttle 0). rpm and throttle are
 * updated every sample; the throttle step is smoothed with a 60 ms one-pole. 2 s warm-up at
 * rpm_lo first, output peak-normalised to -1 dBFS.
 */
public final class Sweep {
    private Sweep() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2 || args.length > 7) {
            System.err.println("usage: Sweep <engine.txt> <out.wav> [seconds=12] [rpm_lo=800] [rpm_hi=6000] [samplerate=22050] [seed=1]");
            System.exit(2);
        }
        EngineConfig cfg = EngineConfig.load(args[0]);
        float seconds = args.length > 2 ? num(args[2]) : 12.0f;
        float lo = args.length > 3 ? num(args[3]) : 800.0f;
        float hi = args.length > 4 ? num(args[4]) : 6000.0f;
        int sr = args.length > 5 ? (int) Render.opt(args[5]) : 22050;
        long seed = args.length > 6 ? Render.opt(args[6]) : 1L;

        Generator g = cfg.generator(sr, seed);
        Render.render(g, lo, 1.0f, Math.round(Render.WARMUP_SECONDS * sr), 0);
        int n = Math.round(seconds * sr);
        int half = n / 2;
        float[] out = new float[n];
        float throttle = 1.0f;
        float k = 1.0f - (float) StrictMath.exp(-1.0 / (0.060 * sr));
        for (int i = 0; i < n; i++) {
            float target;
            float rpm;
            if (i < half) {
                rpm = lo + (hi - lo) * i / half;
                target = 1.0f;
            } else {
                rpm = hi - (hi - lo) * (i - half) / (n - half);
                target = 0.0f;
            }
            throttle += (target - throttle) * k;
            g.engine.rpm = rpm;
            g.engine.setThrottle(throttle);
            g.generate(out, i, 1);
        }
        float peak = Wav.peak(out);
        if (Float.isNaN(peak)) {
            System.err.println("Sweep: NaN/inf in output");
            System.exit(1);
        }
        Wav.write(args[1], out, peak > 0 ? Wav.MINUS_1_DBFS / peak : 1.0, sr);
        System.out.println("sweep " + args[1] + " " + lo + "->" + hi + "->" + lo + " rpm, " + seconds + " s, raw_peak="
                + peak + " dampened=" + g.everDampened);
    }

    private static float num(String a) {
        int eq = a.indexOf('=');
        return Float.parseFloat(eq >= 0 ? a.substring(eq + 1) : a);
    }
}
