/*
 * ves.render: offline engine-sound renderer (host tool, not shipped to the car).
 * Ported from DasEtwas/enginesound <https://github.com/DasEtwas/enginesound>, src/gen.rs and
 * src/utils.rs (fix_engine), upstream commit e5fcca587397c0c8ba9c9d24874b951fed74d260.
 * Copyright (c) 2020 DasEtwas. MIT License; the full notice is in THIRD_PARTY.md.
 * The Java port and its throttle extension are MIT as well.
 */
package ves.render;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Line-based engine config (the .esc schema without RON), see tools/render/README.md:
 * <pre>
 * # comment
 * [engine]       scalars of gen.rs Engine; filters as name.delay (= 1 / cutoff Hz)
 * [cylinders]    count = N; every other key is one value for all cylinders or a list of N
 *                (comma separated); crank_offset or firing_order (+ firing_angle in degrees)
 * [muffler]      straight_pipe.*, elements = M, element.* (scalar or list of M)
 * [throttle]     off_* factors at throttle 0 and off_lowpass_hz (all optional)
 * </pre>
 * Waveguide delays are seconds, both chambers: x.delay, or x.chamber0.delay / x.chamber1.delay.
 * Files ending in .esc are upstream RON configs and are converted on the fly by {@link EscConvert}.
 */
public final class EngineConfig {
    private final Map<String, String> kv = new LinkedHashMap<String, String>();
    private final Set<String> used = new LinkedHashSet<String>();
    private final String source;
    public final String name;
    public final int cylinders;
    public final float defaultRpm;

    private EngineConfig(String text, String source) {
        this.source = source;
        String section = null;
        String[] lines = text.split("\n", -1);
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash);
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim();
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0 || section == null) {
                throw new IllegalArgumentException(source + ":" + (n + 1) + ": expected key = value inside a [section]");
            }
            String key = section + "." + line.substring(0, eq).trim();
            if (kv.put(key, line.substring(eq + 1).trim()) != null) {
                throw new IllegalArgumentException(source + ":" + (n + 1) + ": duplicate key " + key);
            }
        }
        this.name = str("engine.name", null);
        this.cylinders = (int) f("cylinders.count");
        this.defaultRpm = f("engine.rpm", 1000.0f);
        generator(48000, 1L); // validates every key once
        for (String k : kv.keySet()) {
            if (!used.contains(k)) {
                throw new IllegalArgumentException(source + ": unknown key " + k);
            }
        }
    }

    public static EngineConfig parse(String text, String source) {
        return new EngineConfig(text.replace("\r", ""), source);
    }

    public static EngineConfig load(String path) throws IOException {
        String text = readUtf8(new File(path));
        if (path.toLowerCase(java.util.Locale.ROOT).endsWith(".esc")) {
            text = EscConvert.convert(text, path);
        }
        return parse(text, path);
    }

    static String readUtf8(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int r;
            while ((r = in.read(b)) > 0) {
                bo.write(b, 0, r);
            }
            return new String(bo.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    /** Builds a fresh engine for a sample rate (upstream: deserialize + utils::fix_engine). */
    public Engine build(int sampleRate, long seed) {
        int n = cylinders;
        if (n < 1 || n > 16) { // constants.rs MAX_CYLINDERS
            throw new IllegalArgumentException(source + ": cylinders.count must be 1..16");
        }
        float[] offsets = crankOffsets(n);
        Cylinder[] cyl = new Cylinder[n];
        for (int i = 0; i < n; i++) {
            cyl[i] = new Cylinder(offsets[i],
                    waveGuide("cylinders.exhaust_waveguide", i, n, sampleRate, "cylinders.exhaust_closed_refl"),
                    waveGuide("cylinders.intake_waveguide", i, n, sampleRate, "cylinders.intake_closed_refl"),
                    waveGuide("cylinders.extractor_waveguide", i, n, sampleRate, null),
                    at("cylinders.intake_open_refl", i, n), at("cylinders.intake_closed_refl", i, n),
                    at("cylinders.exhaust_open_refl", i, n), at("cylinders.exhaust_closed_refl", i, n),
                    at("cylinders.piston_motion_factor", i, n), at("cylinders.ignition_factor", i, n),
                    at("cylinders.ignition_time", i, n));
        }
        int m = (int) f("muffler.elements");
        if (m < 1) {
            throw new IllegalArgumentException(source + ": muffler.elements must be >= 1");
        }
        WaveGuide[] elements = new WaveGuide[m];
        for (int i = 0; i < m; i++) {
            elements[i] = waveGuide("muffler.element", i, m, sampleRate, null);
        }
        Engine.Muffler muffler = new Engine.Muffler(waveGuide("muffler.straight_pipe", 0, 1, sampleRate, null), elements);
        Engine e = new Engine(defaultRpm, f("engine.intake_volume"), f("engine.exhaust_volume"),
                f("engine.engine_vibrations_volume"), cyl, seed, f("engine.intake_noise_factor"),
                lpf("engine.intake_noise_lp", sampleRate), lpf("engine.engine_vibration_filter", sampleRate),
                muffler, f("engine.intake_valve_shift"), f("engine.exhaust_valve_shift"),
                f("engine.crankshaft_fluctuation"), lpf("engine.crankshaft_fluctuation_lp", sampleRate),
                f("throttle.off_ignition", 0.4f), f("throttle.off_piston", 0.85f),
                f("throttle.off_intake", 0.3f), f("throttle.off_intake_noise", 0.3f),
                new LowPassFilter(f("throttle.off_lowpass_hz", 2000.0f), sampleRate));
        e.setThrottle(1.0f);
        return e;
    }

    /**
     * A ready generator: fresh engine at its config rpm and throttle 1, master volume
     * engine.volume (upstream CLI --volume, default 0.1) and the DC blocker of main.rs,
     * LowPassFilter::new(0.5, sample_rate) (engine.dc_lp.delay, default 2 s).
     */
    Generator generator(int sampleRate, long seed) {
        Generator g = new Generator(sampleRate, build(sampleRate, seed), lpf("engine.dc_lp", sampleRate, 2.0f));
        g.volume = f("engine.volume", 0.1f);
        return g;
    }

    private float[] crankOffsets(int n) {
        float[] off = new float[n];
        if (kv.containsKey("cylinders.firing_order")) {
            float[] order = list("cylinders.firing_order", n);
            float[] angle = new float[n];
            if (kv.containsKey("cylinders.firing_angle")) {
                angle = list("cylinders.firing_angle", n);
            } else {
                for (int k = 0; k < n; k++) {
                    angle[k] = 720.0f * k / n;
                }
            }
            boolean[] seen = new boolean[n];
            for (int k = 0; k < n; k++) {
                int c = (int) order[k] - 1;
                if (c < 0 || c >= n || seen[c] || order[k] != c + 1) {
                    throw new IllegalArgumentException(source + ": firing_order must list 1.." + n + " once each");
                }
                seen[c] = true;
                // cylinder fires when fract(pos + offset) passes 0.5: firing at angle a => offset -a/720
                off[c] = Gen.fract(2.0f - angle[k] / 720.0f);
            }
        } else {
            for (int i = 0; i < n; i++) {
                off[i] = at("cylinders.crank_offset", i, n);
            }
        }
        return off;
    }

    private WaveGuide waveGuide(String p, int i, int n, int sr, String alphaDefaultKey) {
        float d0;
        float d1;
        if (kv.containsKey(p + ".delay")) {
            d0 = at(p + ".delay", i, n);
            d1 = d0;
        } else {
            d0 = at(p + ".chamber0.delay", i, n);
            d1 = at(p + ".chamber1.delay", i, n);
        }
        // a valve-side alpha is overwritten on every pop: optional, defaults to the closed refl
        float alpha = alphaDefaultKey != null && !kv.containsKey(p + ".alpha")
                ? at(alphaDefaultKey, i, n) : at(p + ".alpha", i, n);
        return new WaveGuide(samples(d0, sr), samples(d1, sr), alpha, at(p + ".beta", i, n), sr);
    }

    /**
     * utils::fix_engine: len = (delay * sample_rate as f32) as usize. Deviation: at least 1
     * (upstream would divide by zero on an empty buffer at low sample rates).
     */
    static int samples(float delaySeconds, int sr) {
        int len = (int) (delaySeconds * (float) sr);
        return Math.max(1, len);
    }

    private LowPassFilter lpf(String p, int sr) {
        return new LowPassFilter(1.0f / f(p + ".delay"), sr);
    }

    private LowPassFilter lpf(String p, int sr, float def) {
        return new LowPassFilter(1.0f / f(p + ".delay", def), sr);
    }

    private String str(String key, String def) {
        used.add(key);
        String v = kv.get(key);
        return v == null ? def : v;
    }

    private float f(String key) {
        String v = str(key, null);
        if (v == null) {
            throw new IllegalArgumentException(source + ": missing key " + key);
        }
        return num(key, v);
    }

    private float f(String key, float def) {
        String v = str(key, null);
        return v == null ? def : num(key, v);
    }

    private float num(String key, String v) {
        try {
            float x = Float.parseFloat(v.trim());
            if (Float.isNaN(x) || Float.isInfinite(x)) {
                throw new NumberFormatException();
            }
            return x;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(source + ": " + key + " is not a number: " + v);
        }
    }

    private float[] list(String key, int n) {
        String v = str(key, null);
        if (v == null) {
            throw new IllegalArgumentException(source + ": missing key " + key);
        }
        String[] parts = v.split(",");
        if (parts.length != n) {
            throw new IllegalArgumentException(source + ": " + key + " needs " + n + " values, has " + parts.length);
        }
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = num(key, parts[i]);
        }
        return out;
    }

    /** element i of a per-cylinder / per-element key: one value for all, or a list of n */
    private float at(String key, int i, int n) {
        String v = str(key, null);
        if (v == null) {
            throw new IllegalArgumentException(source + ": missing key " + key);
        }
        if (v.indexOf(',') < 0) {
            return num(key, v);
        }
        return list(key, n)[i];
    }
}
