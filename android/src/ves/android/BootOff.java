package ves.android;

import java.util.Map;

import ves.core.BootReset;

/**
 * VES off at head-unit boot (0.2.13, D3), once per vesd process. Plain Java: tests/android/bootoff.sh runs it on
 * the host.
 *
 * <p>vesd's first successful settings read decides (hmgapid may refuse the first tries): inside the boot window
 * (uptime at vesd's START, BootReset) enabled is forced false before the core sees the map, and when it was on,
 * enabled=false is written back on a background thread (now, then every RETRY_MS, TRIES at most). Until the
 * stored value is settled the later reads keep the forcing:
 * <ul>
 * <li>a read that started after our write landed is the owner's truth (turned on right after boot: honoured);</li>
 * <li>a read that says off ends it (our write, or the owner turned it off);</li>
 * <li>a read whose text differs from the one at boot is the owner's change (honoured; the writer then skips);</li>
 * <li>otherwise (the boot text still stored, write pending or refused) enabled stays forced false.</li>
 * </ul>
 * The writer re-reads before each write and writes only while the boot text is still stored, so it never
 * overwrites an owner's change. vesd keeps re-parsing the same text while active() (its cache would hide a
 * post-write read that happens to be byte-identical to the boot text).
 */
final class BootOff {
    /** where vesd's settings live (hmgapid setting-get / setting-set) */
    interface Store {
        /** stored JSON text, null when refused */
        String read() throws Exception;

        boolean write(String json) throws Exception;
    }

    interface Sleeper {
        void sleep(long ms);
    }

    static final int TRIES = 6;
    static final long RETRY_MS = 5000;

    /** what a read did */
    static final int NONE = 0, KEPT = 1, ALREADY_OFF = 2, WAS_ON = 3, FORCED = 4, SETTLED = 5, OWNER = 6;

    private final long startUptimeMs;
    private boolean decided, forcing, landed;
    private String bootText;

    BootOff(long startUptimeMs) {
        this.startUptimeMs = startUptimeMs;
    }

    /** true while reads may be forced (vesd re-parses unchanged text meanwhile) */
    synchronized boolean active() {
        return !decided || forcing;
    }

    /** taken by vesd BEFORE each setting-get: a read that started after the write landed is the owner's truth */
    synchronized boolean landed() {
        return landed;
    }

    /**
     * Every successful settings read, before Settings.fromMap: m (the read's map) is changed in place. afterWrite is
     * landed() taken before the read started. WAS_ON: the caller starts the writer.
     */
    synchronized int onRead(String text, Map<String, String> m, boolean afterWrite) {
        if (!decided) {
            decided = true;
            if (!BootReset.shouldReset(startUptimeMs)) return KEPT;
            if (!BootReset.apply(m, startUptimeMs)) return ALREADY_OFF;
            forcing = true;
            bootText = text;
            return WAS_ON;
        }
        if (!forcing) return NONE;
        if (afterWrite || !BootReset.mustWriteOff(m)) {
            forcing = false;
            return SETTLED;
        }
        if (!bootText.equals(text)) {
            forcing = false;
            return OWNER;
        }
        m.put("enabled", "false");
        return FORCED;
    }

    /** the writer may write only while the boot text is still stored and nobody settled it */
    synchronized boolean mayWrite(String stored) {
        return forcing && !landed && bootText != null && bootText.equals(stored);
    }

    synchronized void wrote() {
        landed = true;
    }

    /**
     * The writer (its own thread): offJson is the boot text with enabled=false (every key, as the app saves).
     * Returns a log line about the outcome.
     */
    String writeOff(Store s, String offJson, Sleeper sl) {
        String why = "";
        for (int i = 1; i <= TRIES; i++) {
            try {
                String stored = s.read();
                if (stored == null) {
                    why = "hmgapid refused setting-get";
                } else if (!mayWrite(stored.trim())) {
                    return "boot: write skipped (settings changed meanwhile, try " + i + ")";
                } else if (s.write(offJson)) {
                    wrote();
                    return "boot: enabled=false written (try " + i + ")";
                } else {
                    why = "hmgapid refused setting-set";
                }
            } catch (Throwable e) {
                why = String.valueOf(e);
            }
            if (i < TRIES) sl.sleep(RETRY_MS);
        }
        return "boot: write failed after " + TRIES + " tries (" + why + "); VES stays off until the settings change";
    }
}
