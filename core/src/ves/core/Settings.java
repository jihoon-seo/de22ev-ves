package ves.core;

import java.util.Map;

/**
 * User settings. fromMap() accepts the mod/settings.json keys as strings and never throws: missing or garbage
 * values fall back to defaults, numbers are clamped (decimals are rounded, e.g. "40.0").
 * <pre>
 * enabled        true|false|1|0|on|off|yes|no      default false
 * volume         0..100                            default 40
 * profile        ice_smooth|ice_shift|ev_future    default ice_smooth
 * mode           idle_regen|stop_silent_regen|accel_only|drive_all   default idle_regen
 * idle_level     0..60  (% of drive gain)          default 30   (alias idleLevel)
 * regen_level    0..100                            default 50   (alias regenLevel)
 * response       soft|normal|sport                 default normal
 * fade_above     0 = off, else km/h (fade to silence over +20 km/h)  default 0
 *                (aliases fade_above_kph, fadeAboveKph; clamp 0..250)
 * </pre>
 */
public final class Settings {
    public static final String[] MODES = {"idle_regen", "stop_silent_regen", "accel_only", "drive_all"};
    public static final String[] RESPONSES = {"soft", "normal", "sport"};

    public boolean enabled = false;
    public int volume = 40;
    public Profile profile = Profile.ICE_SMOOTH;
    public String mode = "idle_regen";
    public int idleLevel = 30;
    public int regenLevel = 50;
    public String response = "normal";
    public int fadeAboveKph = 0;

    public Settings() {}

    public static Settings fromMap(Map<String, String> m) {
        Settings s = new Settings();
        if (m == null) return s;
        s.enabled = bool(get(m, "enabled", null), false);
        s.volume = integer(get(m, "volume", null), 40, 0, 100);
        s.profile = Profile.parse(get(m, "profile", null));
        s.mode = choice(get(m, "mode", null), MODES, "idle_regen");
        s.idleLevel = integer(get(m, "idle_level", "idleLevel"), 30, 0, 60);
        s.regenLevel = integer(get(m, "regen_level", "regenLevel"), 50, 0, 100);
        s.response = choice(get(m, "response", null), RESPONSES, "normal");
        String f = get(m, "fade_above", "fade_above_kph");
        if (f == null) f = get(m, "fadeAboveKph", null);
        s.fadeAboveKph = integer(f, 0, 0, 250);
        return s;
    }

    /** A sanitized copy (fields clamped / unknown strings replaced by defaults). */
    public Settings copy() {
        Settings s = new Settings();
        s.enabled = enabled;
        s.volume = clamp(volume, 0, 100);
        s.profile = profile != null ? profile : Profile.ICE_SMOOTH;
        s.mode = choice(mode, MODES, "idle_regen");
        s.idleLevel = clamp(idleLevel, 0, 60);
        s.regenLevel = clamp(regenLevel, 0, 100);
        s.response = choice(response, RESPONSES, "normal");
        s.fadeAboveKph = clamp(fadeAboveKph, 0, 250);
        return s;
    }

    private static String get(Map<String, String> m, String k1, String k2) {
        Object v = m.get(k1);
        if (v == null && k2 != null) v = m.get(k2);
        return v == null ? null : String.valueOf(v);
    }

    static boolean bool(String v, boolean def) {
        if (v == null) return def;
        String s = v.trim().toLowerCase(java.util.Locale.US);
        if (s.equals("true") || s.equals("1") || s.equals("on") || s.equals("yes")) return true;
        if (s.equals("false") || s.equals("0") || s.equals("off") || s.equals("no")) return false;
        return def;
    }

    static int integer(String v, int def, int lo, int hi) {
        if (v == null) return def;
        try {
            double d = Double.parseDouble(v.trim());
            if (Double.isNaN(d)) return def;
            if (d > hi) return hi;
            if (d < lo) return lo;
            return (int) Math.round(d);
        } catch (RuntimeException e) {
            return def;
        }
    }

    static String choice(String v, String[] allowed, String def) {
        if (v == null) return def;
        String s = v.trim().toLowerCase(java.util.Locale.US);
        for (int i = 0; i < allowed.length; i++) if (allowed[i].equals(s)) return allowed[i];
        return def;
    }

    static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Settings)) return false;
        Settings s = (Settings) o;
        return enabled == s.enabled && volume == s.volume && profile == s.profile && eqs(mode, s.mode)
                && idleLevel == s.idleLevel && regenLevel == s.regenLevel && eqs(response, s.response)
                && fadeAboveKph == s.fadeAboveKph;
    }

    private static boolean eqs(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    public int hashCode() {
        int h = enabled ? 1 : 0;
        h = h * 31 + volume;
        h = h * 31 + (profile == null ? 0 : profile.ordinal() + 1);
        h = h * 31 + (mode == null ? 0 : mode.hashCode());
        h = h * 31 + idleLevel;
        h = h * 31 + regenLevel;
        h = h * 31 + (response == null ? 0 : response.hashCode());
        h = h * 31 + fadeAboveKph;
        return h;
    }

    public String toString() {
        return "enabled=" + enabled + " volume=" + volume + " profile=" + (profile == null ? "null" : profile.key())
                + " mode=" + mode + " idle_level=" + idleLevel + " regen_level=" + regenLevel + " response=" + response
                + " fade_above=" + fadeAboveKph;
    }
}
