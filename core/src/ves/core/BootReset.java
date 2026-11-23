package ves.core;

import java.util.Map;

/**
 * VES starts off at head-unit boot (0.2.13, owner decision D3). When vesd starts within BOOT_WINDOW_MS of boot, its
 * FIRST settings read forces enabled=false before the core ever sees it, and writes false back (hmgapid setting-set)
 * if it was true. A later vesd restart (crash, kit update) keeps the owner's state; turning on is always manual.
 */
public final class BootReset {
    /** system uptime below this at vesd's start = the head unit just booted */
    public static final long BOOT_WINDOW_MS = 300000;

    private BootReset() {}

    public static boolean shouldReset(long uptimeMs) {
        return uptimeMs >= 0 && uptimeMs < BOOT_WINDOW_MS;
    }

    /** the settings read as on (Settings.fromMap's reading of "enabled"): false must be written back */
    public static boolean mustWriteOff(Map<String, String> settings) {
        return settings != null && Settings.bool(settings.get("enabled"), false);
    }

    /**
     * vesd's first settings read: inside the boot window enabled is put to "false" in the map (what the core gets).
     * True when it was on, i.e. vesd must write enabled=false back to the mod settings.
     */
    public static boolean apply(Map<String, String> settings, long uptimeMs) {
        if (settings == null || !shouldReset(uptimeMs)) return false;
        boolean write = mustWriteOff(settings);
        if (write) settings.put("enabled", "false");
        return write;
    }
}
