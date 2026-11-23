package ves.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ves.core.TestMain.check;

/** 0.2.13 VES starts off at head-unit boot (owner decision D3): vesd's first settings read inside the boot window */
final class BootTests {
    private BootTests() {}

    static Map<String, String> settings(String enabled) {
        Map<String, String> m = new HashMap<String, String>();
        m.put("volume", "60");
        if (enabled != null) m.put("enabled", enabled);
        return m;
    }

    static void add(List<TestMain.T> l) {
        // ---------------- IG1 vesd started 20 s after boot with enabled=true: forced off and written back
        l.add(new TestMain.T("IG1_boot_resets_enabled") {
            void run() {
                check(BootReset.shouldReset(20000), "20 s uptime is inside the boot window");
                check(BootReset.shouldReset(0) && BootReset.shouldReset(BootReset.BOOT_WINDOW_MS - 1), "window edges");
                check(BootReset.BOOT_WINDOW_MS == 300000, "5 min window: " + BootReset.BOOT_WINDOW_MS);
                String[] on = {"true", "1", "on", "yes"};
                for (String v : on) {
                    Map<String, String> m = settings(v);
                    check(BootReset.mustWriteOff(m), "enabled=" + v + " must be written false");
                    check(BootReset.apply(m, 20000), "apply reports a write for enabled=" + v);
                    check("false".equals(m.get("enabled")) && !Settings.fromMap(m).enabled, "the core sees enabled=false: " + m);
                    check("60".equals(m.get("volume")), "other keys kept: " + m);
                }
            }
        });

        // ---------------- IG2 vesd restarted 10 min after boot (crash, kit update): the owner's state stays
        l.add(new TestMain.T("IG2_late_restart_keeps_enabled") {
            void run() {
                check(!BootReset.shouldReset(600000) && !BootReset.shouldReset(BootReset.BOOT_WINDOW_MS), "10 min / 5 min: no reset");
                Map<String, String> m = settings("true");
                check(!BootReset.apply(m, 600000), "no write after the window");
                check("true".equals(m.get("enabled")) && Settings.fromMap(m).enabled, "still on: " + m);
            }
        });

        // ---------------- IG3 enabled already false (or missing / garbage): nothing to write at boot
        l.add(new TestMain.T("IG3_boot_already_off") {
            void run() {
                String[] off = {"false", "0", "off", null, "garbage"};
                for (String v : off) {
                    Map<String, String> m = settings(v);
                    check(!BootReset.mustWriteOff(m), "enabled=" + v + ": nothing to write");
                    check(!BootReset.apply(m, 20000), "apply: no write for enabled=" + v);
                    check(!Settings.fromMap(m).enabled, "core sees off for enabled=" + v);
                }
                check(!BootReset.mustWriteOff(null) && !BootReset.apply(null, 1000), "null map");
            }
        });
    }
}
