package ves.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HMG-API's vehicle readings (hmgapid "vehicle a b c" answers "a value" lines). On the car (car check 2)
 * hmgapid answers the values and then exits with code 1: its read times out after 3 s because the vehicle
 * service keeps the socket open. So the text counts whatever the exit code; a refusal ("not declared ...")
 * has no "name value" line of a known shape and yields nothing.
 */
public final class VehicleText {
    private VehicleText() {}

    /** name -> value text ("42.5", "0"/"1", "-" when the car doesn't answer) */
    public static Map<String, String> parse(int code, String text) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (text == null) return out;
        for (String l : text.split("\n")) {
            l = l.trim();
            int sp = l.indexOf(' ');
            if (sp <= 0 || l.indexOf(' ', sp + 1) >= 0) continue; // exactly "name value"
            String name = l.substring(0, sp), v = l.substring(sp + 1);
            if (!name.matches("[a-z0-9._]{1,40}")) continue;
            if (!(v.equals("-") || v.matches("-?[0-9]+(\\.[0-9]+)?"))) continue;
            out.put(name, v);
        }
        return out;
    }

    static final int[] FALLBACK_SIGS = { Sig.SPEED_KPH, Sig.PEDAL_PCT, Sig.READY, Sig.GEAR, Sig.BRAKE_BAR, Sig.MOTOR_SPEED_KPH };

    /**
     * While the fallback feeds the signals (one poll takes ~3.2 s on the car), they must not go stale between
     * polls: window max(5 s, 2 x call time). Off: the defaults again.
     */
    public static void setFallbackStale(SignalState s, boolean on, long callMs) {
        long w = Math.max(5000, 2 * Math.max(0, callMs));
        for (int sig : FALLBACK_SIGS) {
            if (on) s.setStaleMs(sig, w);
            else s.setStaleMs(sig, sig == Sig.GEAR || sig == Sig.READY ? SignalState.SLOW_STALE_MS : SignalState.DEFAULT_STALE_MS);
        }
    }
}
