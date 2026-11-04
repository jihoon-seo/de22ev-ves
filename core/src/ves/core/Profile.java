package ves.core;

/** Sound profile. parse() accepts "ice_smooth", "ice_shift", "ev_future" (case-insensitive); default ICE_SMOOTH. */
public enum Profile {
    ICE_SMOOTH("ice_smooth"), ICE_SHIFT("ice_shift"), EV_FUTURE("ev_future");

    private final String key;

    Profile(String key) {
        this.key = key;
    }

    /** settings value, e.g. "ice_shift" */
    public String key() {
        return key;
    }

    public boolean isIce() {
        return this != EV_FUTURE;
    }

    public static Profile parse(String v) {
        if (v == null) return ICE_SMOOTH;
        String s = v.trim().toLowerCase(java.util.Locale.US);
        Profile[] all = values();
        for (int i = 0; i < all.length; i++) if (all[i].key.equals(s)) return all[i];
        return ICE_SMOOTH;
    }
}
