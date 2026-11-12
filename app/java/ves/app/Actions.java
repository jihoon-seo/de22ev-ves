package ves.app;

import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

import java.io.IOException;

import ves.hmg.HmgClient;
import ves.hmg.Prefs;
import ves.hmg.VesLink;

/**
 * Settings changes from the app, the panel widget and the star key: read the
 * mod's settings from hmgapid, change one key, save all of them, tell vesd to
 * reload. Call off the UI thread.
 */
final class Actions {
    static final String ACTION_TOGGLE = "io.github.jihoonseo.ves.TOGGLE";
    static final String ACTION_VOL_UP = "io.github.jihoonseo.ves.VOL_UP";
    static final String ACTION_VOL_DOWN = "io.github.jihoonseo.ves.VOL_DOWN";

    static final String[] PROFILES = { "ice_smooth", "ice_shift", "ev_future" };
    static final String[] PROFILE_NAMES = { "내연기관 (부드럽게)", "내연기관 (가상 변속)", "EV 미래형" };
    static final String[] MODES = { "idle_regen", "stop_silent_regen", "accel_only", "drive_all" };
    static final String[] MODE_NAMES = { "정차 공회전 + 회생음", "정차 무음 + 회생음", "가속할 때만", "주행 중 항상 (회생음 없음)" };
    static final String[] SOUNDS = { "synth", "i4t", "v6", "v8", "i6", "user1", "user2", "user3", "user4", "user5", "user6", "user7", "user8" };
    static final String[] SOUND_NAMES = { "합성음", "4기통 터보", "V6", "V8", "직렬 6기통", "사용자 1", "사용자 2", "사용자 3",
            "사용자 4", "사용자 5", "사용자 6", "사용자 7", "사용자 8" };
    /** the user sound-pack slots (vesd's PackStore.USER_SLOTS) */
    static final String[] USER_SLOTS = { "user1", "user2", "user3", "user4", "user5", "user6", "user7", "user8" };
    static final String[] BEHAVIORS = { "ice", "classic" };
    static final String[] BEHAVIOR_NAMES = { "내연기관처럼", "예전 방식" };
    static final String[] PROMPTS = { "duck", "pause" };
    static final String[] PROMPTS_NAMES = { "작게 계속", "멈춤" };
    static final String[] POPS = { "off", "soft", "strong" };
    static final String[] POPS_NAMES = { "끔", "약하게", "강하게" };
    static final String[] IDLE = { "10", "20", "30", "40", "50", "60" };
    static final String[] REGEN = { "0", "25", "50", "75", "100" };
    static final String[] RESPONSE = { "soft", "normal", "sport" };
    static final String[] RESPONSE_NAMES = { "부드럽게", "보통", "스포티" };
    static final String[] FADE = { "0", "60", "80", "100", "120" };

    private static final HmgClient HMG = new HmgClient(5000);

    private Actions() {}

    static JSONObject load() throws IOException {
        JSONObject o = Prefs.load(HMG);
        // every key, with the defaults of mod/settings.json, so a save writes all of them
        try {
            if (!o.has(Prefs.ENABLED)) o.put(Prefs.ENABLED, false);
            if (!o.has(Prefs.VOLUME)) o.put(Prefs.VOLUME, 40);
            if (!o.has(Prefs.PROFILE)) o.put(Prefs.PROFILE, "ice_smooth");
            if (!o.has(Prefs.MODE)) o.put(Prefs.MODE, "idle_regen");
            if (!o.has(Prefs.IDLE_LEVEL)) o.put(Prefs.IDLE_LEVEL, "30");
            if (!o.has(Prefs.REGEN_LEVEL)) o.put(Prefs.REGEN_LEVEL, "50");
            if (!o.has(Prefs.RESPONSE)) o.put(Prefs.RESPONSE, "normal");
            if (!o.has(Prefs.FADE_ABOVE)) o.put(Prefs.FADE_ABOVE, "0");
            if (!o.has(Prefs.BEHAVIOR)) o.put(Prefs.BEHAVIOR, "ice");
            if (!o.has(Prefs.POPS)) o.put(Prefs.POPS, "soft");
            if (!o.has(Prefs.PROMPTS)) o.put(Prefs.PROMPTS, "duck");
            if (!o.has(Prefs.SOUND)) o.put(Prefs.SOUND, "synth");
        } catch (org.json.JSONException e) {
            throw new IOException(e.getMessage());
        }
        return o;
    }

    static void save(JSONObject o) throws IOException {
        if (!Prefs.save(HMG, o)) throw new IOException("hmgapid refused setting-set");
        reload();
    }

    static void reload() {
        try {
            VesLink.send("RELOAD", 3000);
        } catch (IOException ignored) {
            // vesd picks the change up within 2 s anyway
        }
    }

    static boolean enabled(JSONObject o) {
        return o.optBoolean(Prefs.ENABLED, "true".equals(o.optString(Prefs.ENABLED)));
    }

    static int volume(JSONObject o) {
        return Prefs.clamp(o.optInt(Prefs.VOLUME, 40), 0, 100);
    }

    /** returns the new on/off state */
    static boolean toggle() throws IOException {
        JSONObject o = load();
        boolean on = !enabled(o);
        put(o, Prefs.ENABLED, on);
        save(o);
        return on;
    }

    static int changeVolume(int delta) throws IOException {
        JSONObject o = load();
        int v = Prefs.clamp(volume(o) + delta, 0, 100);
        put(o, Prefs.VOLUME, v);
        save(o);
        return v;
    }

    /** next value of a choice key; returns it */
    /** sets a choice setting to value */
    static void set(String key, String value) throws IOException {
        JSONObject o = load();
        put(o, key, value);
        save(o);
    }

    static String cycle(String key, String[] values) throws IOException {
        JSONObject o = load();
        String cur = o.optString(key);
        int i = 0;
        for (int k = 0; k < values.length; k++) if (values[k].equals(cur)) i = k + 1;
        String next = values[i % values.length];
        put(o, key, next);
        save(o);
        return next;
    }

    static void put(JSONObject o, String k, Object v) throws IOException {
        try {
            o.put(k, v);
        } catch (org.json.JSONException e) {
            throw new IOException(e.getMessage());
        }
    }

    static String name(String value, String[] values, String[] names) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return names[i];
        return value;
    }

    static String status() {
        try {
            return VesLink.send("STATUS", 3000);
        } catch (IOException e) {
            return "error 엔진 사운드 서비스(vesd)에 연결할 수 없음: " + e.getMessage();
        }
    }

    /** the head unit's own on-screen notice */
    static void toast(Context c, String text) {
        c.sendBroadcast(new Intent("com.hkmc.intent.action.TOAST")
                .putExtra("com.hkmc.intent.extra.TOAST_MESSAGE", text)
                .putExtra("com.hkmc.intent.extra.TOAST_POPUPSHOWING", true));
    }

    /** one short line for the widget and the main screen (ves.core.StatusLabel, host-tested) */
    static String shortStatus(String status) {
        return ves.core.StatusLabel.of(status);
    }

    static String field(String line, String key) {
        return ves.core.StatusLabel.field(line, key);
    }
}
