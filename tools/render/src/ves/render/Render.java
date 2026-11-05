/*
 * ves.render: offline engine-sound renderer (host tool, not shipped to the car).
 * Part of the Java port of DasEtwas/enginesound <https://github.com/DasEtwas/enginesound>,
 * upstream commit e5fcca587397c0c8ba9c9d24874b951fed74d260 (CLI after main.rs headless mode).
 * Copyright (c) 2020 DasEtwas. MIT License; the full notice is in THIRD_PARTY.md.
 * The Java port and its throttle extension are MIT as well.
 */
package ves.render;

import java.io.IOException;

/**
 * {@code Render <engine.txt|.esc> <rpm> <throttle> <seconds> <out.wav> [samplerate=22050] [seed=1]}
 * <p>Fixed rpm and throttle; 2 s of warm-up are rendered and dropped (upstream headless default
 * is 3 s), then {@code seconds} are written as mono PCM16, peak-normalised to -1 dBFS.
 * Deterministic: same arguments give byte-identical files.
 */
public final class Render {
    private Render() {
    }

    public static final float WARMUP_SECONDS = 2.0f;

    public static void main(String[] args) throws IOException {
        if (args.length < 5 || args.length > 7) {
            System.err.println("usage: Render <engine.txt> <rpm> <throttle> <seconds> <out.wav> [samplerate=22050] [seed=1]");
            System.exit(2);
        }
        EngineConfig cfg = EngineConfig.load(args[0]);
        float rpm = Float.parseFloat(args[1]);
        float throttle = Float.parseFloat(args[2]);
        float seconds = Float.parseFloat(args[3]);
        int sr = args.length > 5 ? (int) opt(args[5]) : 22050;
        long seed = args.length > 6 ? opt(args[6]) : 1L;

        Generator g = cfg.generator(sr, seed);
        float[] out = render(g, rpm, throttle, Math.round(WARMUP_SECONDS * sr), Math.round(seconds * sr));
        float peak = Wav.peak(out);
        if (Float.isNaN(peak)) {
            System.err.println("Render: NaN/inf in output");
            System.exit(1);
        }
        double gain = peak > 0 ? Wav.MINUS_1_DBFS / peak : 1.0;
        Wav.write(args[4], out, gain, sr);
        System.out.println("rendered " + args[4] + " samples=" + out.length + " rate=" + sr
                + " raw_peak=" + peak + " gain=" + (float) gain + " dampened=" + g.everDampened);
    }

    /** "22050" or "samplerate=22050" */
    static long opt(String a) {
        int eq = a.indexOf('=');
        return Long.parseLong(eq >= 0 ? a.substring(eq + 1) : a);
    }

    /** Sets rpm and throttle, renders and drops warmup samples, then returns length samples. */
    static float[] render(Generator g, float rpm, float throttle, int warmup, int length) {
        g.engine.rpm = rpm;
        g.engine.setThrottle(throttle);
        float[] w = new float[4096];
        for (int left = warmup; left > 0; left -= w.length) {
            g.generate(w, 0, Math.min(left, w.length));
        }
        float[] out = new float[length];
        g.generate(out, 0, length);
        return out;
    }
}
