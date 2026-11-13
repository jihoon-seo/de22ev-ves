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
