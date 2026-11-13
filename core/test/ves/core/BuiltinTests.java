package ves.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 0.2.8 tests: the built-in packs (sounds by 히릿) are the sounds on offer, vex4 by default; TestMain runs them. */
final class BuiltinTests {
    private BuiltinTests() {}

    static void check(boolean c, String msg) {
        TestMain.check(c, msg);
    }

    static Map<String, String> sound(String v) {
        Map<String, String> m = new HashMap<String, String>();
        if (v != null) m.put("sound", v);
        return m;
    }

    static void add(List<TestMain.T> l) {
        // ---------------- LT1 (0.2.9) launch trace: the pedal pressed while standing in a drive gear gives 30 rows
        // (every 100 ms for 3 s) of speed, pedal, kW, rpm, load, layer, gain and gear for vesd's log; nothing while
        // cruising, parked or standing; armed again once stopped
        l.add(new TestMain.T("LT1_launch_trace") {
            String drive(TestMain.Rig r, int steps, double pedal, double kph0, double kph1) {
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < steps; i++) {
                    r.pedal = pedal;
                    r.speed = kph0 + (kph1 - kph0) * i / (double) steps;
                    r.kw = pedal > 0 ? 0.6 * pedal : 0;
                    r.step();
                    b.append(r.c.takeTrace());
                }
                return b.toString();
            }

            int rows(String s) {
                int n = 0;
                for (String line : s.split("\n")) if (line.startsWith("launch +")) n++;
                return n;
            }

            void run() {
                TestMain.Rig r = new TestMain.Rig(TestMain.iceSettings(Profile.ICE_SHIFT, 100));
                check(rows(drive(r, 300, 0, 0, 0)) == 0, "standing 3 s: no trace");
                String a = drive(r, 400, 40, 0, 20);
                System.out.println("    " + a.split("\n")[0] + " | " + a.split("\n")[Math.max(0, rows(a) - 1)]);
                check(rows(a) == 30, "a pull-away: 30 rows (" + rows(a) + ")");
                check(a.contains("rpm=") && a.contains("load=") && a.contains("layer=") && a.contains("pedal=") && a.contains("kw=")
                        && a.contains("gain=") && a.contains("gear="), "row fields: " + a.split("\n")[0]);
                long last = -1;
                boolean rising = true;
                for (String line : a.split("\n")) {
                    if (!line.startsWith("launch +")) continue;
                    long ms = Long.parseLong(line.substring(8, line.indexOf("ms")));
                    rising &= ms > last;
                    last = ms;
                }
                check(rising && last <= 3000, "row times rise, last " + last + " ms");
                check(rows(drive(r, 500, 20, 20, 20)) == 0, "cruising: no trace");
                drive(r, 200, 0, 20, 0);
                check(rows(drive(r, 300, 0, 0, 0)) == 0, "stopped: no trace");
                check(rows(drive(r, 400, 60, 0, 25)) == 30, "the next pull-away: 30 rows again");
                TestMain.Rig p = new TestMain.Rig(TestMain.iceSettings(Profile.ICE_SHIFT, 100));
                p.gear = Sig.GEAR_P;
                check(rows(drive(p, 400, 50, 0, 0)) == 0, "revving in P: no trace");
            }
        });

        // ---------------- HS1 the synth and the CC0 packs are no longer offered: those values (and garbage) give
        // the default vex4; every offered value parses to itself
        l.add(new TestMain.T("HS1_hidden_sounds") {
            void run() {
                check("vex4".equals(new Settings().sound), "new Settings().sound is vex4: " + new Settings().sound);
                for (String v : new String[] { "synth", "i4t", "v6", "v8", "i6", "", "../x", null }) {
                    String from = Settings.fromMap(sound(v)).sound;
                    check("vex4".equals(from), "fromMap sound=" + v + " -> vex4, got " + from);
                    Settings s = new Settings();
                    s.sound = v;
                    check("vex4".equals(s.copy().sound), "copy() sound=" + v + " -> vex4, got " + s.copy().sound);
                }
                for (String v : Settings.SOUNDS) {
                    for (String hidden : new String[] { "synth", "i4t", "v6", "v8", "i6" }) check(!hidden.equals(v), "offered " + v);
                    check(v.equals(Settings.fromMap(sound(v)).sound), v + " parses to itself");
                    Settings s = new Settings();
                    s.sound = v;
                    check(v.equals(s.copy().sound), v + " survives copy()");
                }
            }
        });
    }
}
