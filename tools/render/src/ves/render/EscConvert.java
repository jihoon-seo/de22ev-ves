/*
 * ves.render: offline engine-sound renderer (host tool, not shipped to the car).
 * Converts the RON engine configs (.esc) of DasEtwas/enginesound
 * <https://github.com/DasEtwas/enginesound>, upstream commit e5fcca587397c0c8ba9c9d24874b951fed74d260.
 * Copyright (c) 2020 DasEtwas. MIT License; the full notice is in THIRD_PARTY.md.
 * The Java port and its throttle extension are MIT as well.
 */
package ves.render;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Upstream .esc (RON, serde layout of gen.rs Engine) to the ves.render text format. Numbers keep
 * their source text, so both formats parse to the same floats. Unknown fields are an error.
 * <p>CLI: {@code java -cp <classes> ves.render.EscConvert <in.esc> <out.txt>}
 */
public final class EscConvert {
    private EscConvert() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: EscConvert <in.esc> <out.txt>");
            System.exit(2);
        }
        String out = convert(EngineConfig.readUtf8(new File(args[0])), args[0]);
        EngineConfig.parse(out, args[1]); // must load
        FileOutputStream fo = new FileOutputStream(args[1]);
        try {
            fo.write(out.getBytes("UTF-8"));
        } finally {
            fo.close();
        }
    }

    private static final List<String> ENGINE_KEYS = Arrays.asList("rpm", "intake_volume", "exhaust_volume",
            "engine_vibrations_volume", "cylinders", "intake_noise_factor", "intake_noise_lp",
            "engine_vibration_filter", "muffler", "intake_valve_shift", "exhaust_valve_shift",
            "crankshaft_fluctuation", "crankshaft_fluctuation_lp");
    private static final List<String> CYL_SCALARS = Arrays.asList("intake_open_refl", "intake_closed_refl",
            "exhaust_open_refl", "exhaust_closed_refl", "piston_motion_factor", "ignition_factor", "ignition_time");
    private static final List<String> CYL_WG = Arrays.asList("exhaust_waveguide", "intake_waveguide",
            "extractor_waveguide");

    static String convert(String ron, String source) {
        Map<String, Object> e = map(Ron.parse(ron, source), "engine");
        for (String k : e.keySet()) {
            if (!ENGINE_KEYS.contains(k)) {
                throw new IllegalArgumentException(source + ": unsupported field " + k);
            }
        }
        StringBuilder sb = new StringBuilder();
        String base = new File(source).getName();
        sb.append("# converted by ves.render.EscConvert from ").append(base).append('\n');
        sb.append("# upstream: DasEtwas/enginesound e5fcca587397c0c8ba9c9d24874b951fed74d260, MIT\n\n");
        sb.append("[engine]\n");
        for (String k : new String[] {"rpm", "intake_volume", "exhaust_volume", "engine_vibrations_volume",
                "intake_noise_factor"}) {
            kv(sb, k, scalar(e.get(k), k));
        }
        kv(sb, "intake_noise_lp.delay", lpfDelay(e.get("intake_noise_lp"), "intake_noise_lp"));
        kv(sb, "engine_vibration_filter.delay", lpfDelay(e.get("engine_vibration_filter"), "engine_vibration_filter"));
        kv(sb, "intake_valve_shift", scalar(e.get("intake_valve_shift"), "intake_valve_shift"));
        kv(sb, "exhaust_valve_shift", scalar(e.get("exhaust_valve_shift"), "exhaust_valve_shift"));
        kv(sb, "crankshaft_fluctuation", scalar(e.get("crankshaft_fluctuation"), "crankshaft_fluctuation"));
        kv(sb, "crankshaft_fluctuation_lp.delay",
                lpfDelay(e.get("crankshaft_fluctuation_lp"), "crankshaft_fluctuation_lp"));

        List<Object> cyls = list(e.get("cylinders"), "cylinders");
        List<Map<String, Object>> cm = new ArrayList<Map<String, Object>>();
        for (Object c : cyls) {
            Map<String, Object> m = map(c, "cylinder");
            for (String k : m.keySet()) {
                if (!k.equals("crank_offset") && !CYL_SCALARS.contains(k) && !CYL_WG.contains(k)) {
                    throw new IllegalArgumentException(source + ": unsupported cylinder field " + k);
                }
            }
            cm.add(m);
        }
        sb.append("\n[cylinders]\n");
        kv(sb, "count", Integer.toString(cm.size()));
        column(sb, cm, "crank_offset");
        for (String wg : CYL_WG) {
            waveGuides(sb, wg, cm);
        }
        for (String k : CYL_SCALARS) {
            column(sb, cm, k);
        }

        Map<String, Object> muffler = map(e.get("muffler"), "muffler");
        List<Map<String, Object>> sp = new ArrayList<Map<String, Object>>();
        sp.add(map(muffler.get("straight_pipe"), "straight_pipe"));
        List<Map<String, Object>> el = new ArrayList<Map<String, Object>>();
        for (Object o : list(muffler.get("muffler_elements"), "muffler_elements")) {
            el.add(map(o, "muffler_element"));
        }
        sb.append("\n[muffler]\n");
        waveGuides(sb, "straight_pipe", wrap(sp, "straight_pipe"));
        kv(sb, "elements", Integer.toString(el.size()));
        waveGuides(sb, "element", wrap(el, "element"));
        return sb.toString();
    }

    private static List<Map<String, Object>> wrap(List<Map<String, Object>> wgs, String key) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> wg : wgs) {
            Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
            m.put(key, wg);
            out.add(m);
        }
        return out;
    }

    /** prefix.delay (or chamber0/chamber1), prefix.alpha, prefix.beta over all owners */
    private static void waveGuides(StringBuilder sb, String key, List<Map<String, Object>> owners) {
        List<String> d0 = new ArrayList<String>();
        List<String> d1 = new ArrayList<String>();
        List<String> a = new ArrayList<String>();
        List<String> b = new ArrayList<String>();
        for (Map<String, Object> o : owners) {
            Map<String, Object> wg = map(o.get(key), key);
            for (String k : wg.keySet()) {
                if (!Arrays.asList("chamber0", "chamber1", "alpha", "beta").contains(k)) {
                    throw new IllegalArgumentException("unsupported waveguide field " + key + "." + k);
                }
            }
            d0.add(chamberDelay(wg.get("chamber0"), key));
            d1.add(chamberDelay(wg.get("chamber1"), key));
            a.add(scalar(wg.get("alpha"), key + ".alpha"));
            b.add(scalar(wg.get("beta"), key + ".beta"));
        }
        if (d0.equals(d1)) {
            kv(sb, key + ".delay", join(d0));
        } else {
            kv(sb, key + ".chamber0.delay", join(d0));
            kv(sb, key + ".chamber1.delay", join(d1));
        }
        kv(sb, key + ".alpha", join(a));
        kv(sb, key + ".beta", join(b));
    }

    private static void column(StringBuilder sb, List<Map<String, Object>> cm, String k) {
        List<String> v = new ArrayList<String>();
        for (Map<String, Object> m : cm) {
            v.add(scalar(m.get(k), k));
        }
        kv(sb, k, join(v));
    }

    /** one value if all are the same, else a comma list */
    private static String join(List<String> v) {
        boolean same = true;
        for (String x : v) {
            same &= Float.parseFloat(x) == Float.parseFloat(v.get(0));
        }
        if (same) {
            return v.get(0);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(v.get(i));
        }
        return sb.toString();
    }

    private static String chamberDelay(Object chamber, String what) {
        Map<String, Object> samples = map(map(chamber, what + ".chamber").get("samples"), what + ".samples");
        return scalar(samples.get("delay"), what + ".delay");
    }

    private static String lpfDelay(Object o, String what) {
        return scalar(map(o, what).get("delay"), what + ".delay");
    }

    private static void kv(StringBuilder sb, String k, String v) {
        sb.append(k).append(" = ").append(v).append('\n');
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o, String what) {
        if (!(o instanceof Map)) {
            throw new IllegalArgumentException("expected a struct for " + what);
        }
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o, String what) {
        if (!(o instanceof List)) {
            throw new IllegalArgumentException("expected a list for " + what);
        }
        return (List<Object>) o;
    }

    private static String scalar(Object o, String what) {
        if (!(o instanceof String)) {
            throw new IllegalArgumentException("expected a number for " + what);
        }
        Float.parseFloat((String) o);
        return (String) o;
    }
}
