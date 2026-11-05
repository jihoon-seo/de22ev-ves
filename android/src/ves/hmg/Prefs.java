package ves.hmg;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The mod's settings (keys of mod/settings.json) as the app and vesd read and
 * write them through hmgapid. settings() returns the owner's values over the
 * defaults; a save replaces the owner's file, so writes always send every key.
 */
public final class Prefs {
    public static final String ENABLED = "enabled", VOLUME = "volume", PROFILE = "profile", MODE = "mode",
            IDLE_LEVEL = "idle_level", REGEN_LEVEL = "regen_level", RESPONSE = "response", FADE_ABOVE = "fade_above",
            BEHAVIOR = "behavior", POPS = "pops", SOUND = "sound", PROMPTS = "prompts";

    private Prefs() {}

    public static JSONObject load(HmgClient c) throws java.io.IOException {
        String t = c.settingsJson();
        if (t == null) throw new java.io.IOException("hmgapid refused setting-get");
        try {
            return new JSONObject(t.length() == 0 ? "{}" : t);
        } catch (JSONException e) {
            throw new java.io.IOException("settings aren't JSON");
        }
    }

    public static boolean save(HmgClient c, JSONObject o) throws java.io.IOException {
        return c.saveSettingsJson(o.toString());
    }

    /** flat string map for ves.core.Settings.fromMap (booleans become "true"/"false") */
    public static Map<String, String> toMap(JSONObject o) {
        Map<String, String> m = new HashMap<String, String>();
        Iterator<?> it = o.keys();
        while (it.hasNext()) {
            String k = String.valueOf(it.next());
            m.put(k, String.valueOf(o.opt(k)));
        }
        return m;
    }

    public static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
