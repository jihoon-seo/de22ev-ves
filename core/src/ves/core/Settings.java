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
 * behavior       ice|classic: ice (0.2.0 default) sounds whenever Ready (idle in P/N/D, quiet in R, engine
 *                braking when coasting); classic keeps the 0.1.x modes (P/N/R silent, mode decides)  default ice
 * sound          vex4|vex_v10|ae86|amg_v8|gt3|gtr|s2000|user1..user8: a built-in pack (sounds by 히릿, inside
 *                vesd.jar) or a pack imported from USB into a user slot. The synth and the CC0 packs (synth, i4t,
 *                v6, v8, i6) are no longer offered (0.2.8): those values, like garbage, give the default.
 *                The synth only plays while no pack is loaded   default vex4
 * prompts        duck|pause|voice: under navigation prompts and alerts play at 30 % or stop (calls, ringing and
 *                voice recognition always stop); voice (experimental, 0.2.7) is duck and also plays at 30 % under
 *                the stock navigation's voice, which takes our channel (AudioPolicy.VOICE_REGRAB_DELAY_MS)
 *                default duck
 * pops           off|soft|strong: exhaust pops on lift-off (behaviour ice and classic)  default soft
 * fade_above     0 = off, else km/h (fade to silence over +20 km/h)  default 0
 *                (aliases fade_above_kph, fadeAboveKph; clamp 0..250)
 * speed_boost    off|low|mid|high: louder with speed, from 40 km/h to +0/3/6/9 dB at 110 km/h  default high
 * </pre>
 */
public final class Settings {
    public static final String[] MODES = {"idle_regen", "stop_silent_regen", "accel_only", "drive_all"};
    public static final String[] RESPONSES = {"soft", "normal", "sport"};
    public static final String[] BEHAVIORS = {"ice", "classic"};
    public static final String[] POPS = {"off", "soft", "strong"};
    public static final String[] PROMPTS = {"duck", "pause", "voice"};
    public static final String[] SPEED_BOOSTS = {"off", "low", "mid", "high"};
    public static final String[] SOUNDS = {"vex4", "vex_v10", "ae86", "amg_v8", "gt3", "gtr", "s2000", "user1", "user2", "user3", "user4", "user5", "user6", "user7", "user8"};
    public static final String DEFAULT_SOUND = "vex4";

    public boolean enabled = false;
    public int volume = 40;
    public Profile profile = Profile.ICE_SMOOTH;
    public String mode = "idle_regen";
    public String behavior = "ice";
    public String pops = "soft";
    public String prompts = "duck";
    public String sound = DEFAULT_SOUND;
    public int idleLevel = 30;
    public int regenLevel = 50;
    public String response = "normal";
    public int fadeAboveKph = 0;
    public String speedBoost = "high";

    public Settings() {}

    public static Settings fromMap(Map<String, String> m) {
        Settings s = new Settings();
        if (m == null) return s;
        s.enabled = bool(get(m, "enabled", null), false);
        s.volume = integer(get(m, "volume", null), 40, 0, 100);
        s.profile = Profile.parse(get(m, "profile", null));
        s.mode = choice(get(m, "mode", null), MODES, "idle_regen");
        s.behavior = choice(get(m, "behavior", null), BEHAVIORS, "ice");
        s.pops = choice(get(m, "pops", null), POPS, "soft");
        s.prompts = choice(get(m, "prompts", null), PROMPTS, "duck");
        s.sound = choice(get(m, "sound", null), SOUNDS, DEFAULT_SOUND);
        s.idleLevel = integer(get(m, "idle_level", "idleLevel"), 30, 0, 60);
        s.regenLevel = integer(get(m, "regen_level", "regenLevel"), 50, 0, 100);
        s.response = choice(get(m, "response", null), RESPONSES, "normal");
        String f = get(m, "fade_above", "fade_above_kph");
        if (f == null) f = get(m, "fadeAboveKph", null);
        s.fadeAboveKph = integer(f, 0, 0, 250);
        s.speedBoost = choice(get(m, "speed_boost", null), SPEED_BOOSTS, "high");
        return s;
    }

    /** A sanitized copy (fields clamped / unknown strings replaced by defaults). */
    public Settings copy() {
        Settings s = new Settings();
        s.enabled = enabled;
        s.volume = clamp(volume, 0, 100);
        s.profile = profile != null ? profile : Profile.ICE_SMOOTH;
        s.mode = choice(mode, MODES, "idle_regen");
        s.behavior = choice(behavior, BEHAVIORS, "ice");
        s.pops = choice(pops, POPS, "soft");
        s.prompts = choice(prompts, PROMPTS, "duck");
        s.sound = choice(sound, SOUNDS, DEFAULT_SOUND);
        s.idleLevel = clamp(idleLevel, 0, 60);
        s.regenLevel = clamp(regenLevel, 0, 100);
        s.response = choice(response, RESPONSES, "normal");
        s.fadeAboveKph = clamp(fadeAboveKph, 0, 250);
        s.speedBoost = choice(speedBoost, SPEED_BOOSTS, "high");
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
        return enabled == s.enabled && volume == s.volume && profile == s.profile && eqs(mode, s.mode) && eqs(behavior, s.behavior) && eqs(pops, s.pops) && eqs(prompts, s.prompts) && eqs(sound, s.sound)
                && idleLevel == s.idleLevel && regenLevel == s.regenLevel && eqs(response, s.response)
                && fadeAboveKph == s.fadeAboveKph && eqs(speedBoost, s.speedBoost);
    }

    private static boolean eqs(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    public int hashCode() {
        int h = enabled ? 1 : 0;
        h = h * 31 + volume;
        h = h * 31 + (profile == null ? 0 : profile.ordinal() + 1);
        h = h * 31 + (mode == null ? 0 : mode.hashCode());
        h = h * 31 + (behavior == null ? 0 : behavior.hashCode());
        h = h * 31 + (pops == null ? 0 : pops.hashCode());
        h = h * 31 + (prompts == null ? 0 : prompts.hashCode());
        h = h * 31 + (sound == null ? 0 : sound.hashCode());
        h = h * 31 + idleLevel;
        h = h * 31 + regenLevel;
        h = h * 31 + (response == null ? 0 : response.hashCode());
        h = h * 31 + fadeAboveKph;
        h = h * 31 + (speedBoost == null ? 0 : speedBoost.hashCode());
        return h;
    }

    public String toString() {
        return "enabled=" + enabled + " volume=" + volume + " profile=" + (profile == null ? "null" : profile.key())
                + " behavior=" + behavior + " pops=" + pops + " prompts=" + prompts + " sound=" + sound + " mode=" + mode + " idle_level=" + idleLevel + " regen_level=" + regenLevel + " response=" + response
                + " fade_above=" + fadeAboveKph + " speed_boost=" + speedBoost;
    }
}
