package ves.android;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Host test of BootOff (D3: VES off at head-unit boot, once per vesd process; races with the owner and hmgapid). */
public final class BootOffTest {
    static int failed;

    static void check(boolean c, String what) {
        System.out.println((c ? "PASS " : "FAIL ") + what);
        if (!c) failed++;
    }

    static final String ON = "{\"enabled\":true,\"volume\":40}", OFF = "{\"enabled\":false,\"volume\":40}",
            ON_LOUD = "{\"enabled\":true,\"volume\":70}";

    static Map<String, String> map(boolean on, int vol) {
        Map<String, String> m = new HashMap<String, String>();
        m.put("enabled", String.valueOf(on));
        m.put("volume", String.valueOf(vol));
        return m;
    }

    /** a fake hmgapid: answers from a list (null = refused), records writes */
    static final class FakeStore implements BootOff.Store {
        final List<String> reads = new ArrayList<String>();
        final List<String> writes = new ArrayList<String>();
        boolean writeOk = true;
        int readCalls;

        public String read() {
            String r = reads.get(Math.min(readCalls, reads.size() - 1));
            readCalls++;
            return r;
        }

        public boolean write(String json) {
            if (writeOk) writes.add(json);
            return writeOk;
        }
    }

    static final class FakeSleep implements BootOff.Sleeper {
        final List<Long> slept = new ArrayList<Long>();

        public void sleep(long ms) {
            slept.add(ms);
        }
    }

    public static void main(String[] args) {
        // BO1 vesd restarted long after boot: the owner's state is kept
        BootOff b = new BootOff(600000);
        Map<String, String> m = map(true, 40);
        check(b.onRead(ON, m, false) == BootOff.KEPT && "true".equals(m.get("enabled")), "BO1 restart after the boot window keeps enabled");
        check(!b.active(), "BO1 nothing forced afterwards");
        check(new BootOff(-1).onRead(ON, map(true, 40), false) == BootOff.KEPT, "BO1 bad uptime keeps enabled");

        // BO2 boot, already off: nothing to write, later reads untouched (owner turns it on: honoured)
        b = new BootOff(20000);
        check(b.onRead(OFF, map(false, 40), false) == BootOff.ALREADY_OFF, "BO2 boot with VES off: already off");
        m = map(true, 40);
        check(b.onRead(ON, m, false) == BootOff.NONE && "true".equals(m.get("enabled")), "BO2 owner turns on later: honoured");

        // BO3 boot, on: forced off before the core sees it; forced while the boot text is still stored
        b = new BootOff(20000);
        m = map(true, 40);
        check(b.onRead(ON, m, false) == BootOff.WAS_ON && "false".equals(m.get("enabled")), "BO3 first read forced off (was on)");
        check(b.active(), "BO3 active until settled");
        m = map(true, 40);
        check(b.onRead(ON, m, false) == BootOff.FORCED && "false".equals(m.get("enabled")), "BO3 poll before the write lands: still forced");
        FakeStore st = new FakeStore();
        st.reads.add(ON);
        FakeSleep sl = new FakeSleep();
        String r = b.writeOff(st, OFF, sl);
        check(st.writes.size() == 1 && OFF.equals(st.writes.get(0)) && r.contains("written (try 1)") && sl.slept.isEmpty(), "BO3 writer writes enabled=false at once: " + r);
        check(b.landed(), "BO3 landed after the write");
        // a poll that started before the write landed still reads the boot text: forced
        m = map(true, 40);
        check(b.onRead(ON, m, false) == BootOff.FORCED && "false".equals(m.get("enabled")), "BO3 read begun before the landing: forced");
        // BO4 owner turns it on right after our write (the app writes the same text as at boot): honoured
        m = map(true, 40);
        check(b.onRead(ON, m, true) == BootOff.SETTLED && "true".equals(m.get("enabled")), "BO4 owner on after our write: honoured");
        check(!b.active(), "BO4 settled");
        m = map(true, 40);
        check(b.onRead(ON, m, true) == BootOff.NONE && "true".equals(m.get("enabled")), "BO4 later reads untouched");

        // BO5 hmgapid refuses the first tries: retried every 5 s
        b = new BootOff(20000);
        b.onRead(ON, map(true, 40), false);
        st = new FakeStore();
        st.reads.add(null);
        st.reads.add(null);
        st.reads.add(null);
        st.reads.add(ON);
        sl = new FakeSleep();
        r = b.writeOff(st, OFF, sl);
        check(st.writes.size() == 1 && r.contains("try 4") && sl.slept.size() == 3 && sl.slept.get(0) == BootOff.RETRY_MS, "BO5 refused 3 times, written on try 4: " + r);
        // setting-set refused every time: gives up after 6 tries, stays forced
        b = new BootOff(20000);
        b.onRead(ON, map(true, 40), false);
        st = new FakeStore();
        st.reads.add(ON);
        st.writeOk = false;
        sl = new FakeSleep();
        r = b.writeOff(st, OFF, sl);
        check(st.readCalls == BootOff.TRIES && sl.slept.size() == BootOff.TRIES - 1 && r.contains("failed after 6 tries") && !b.landed(), "BO5 gives up after 6 tries: " + r);
        m = map(true, 40);
        check(b.onRead(ON, m, false) == BootOff.FORCED && "false".equals(m.get("enabled")), "BO5 boot text still stored: still forced");

        // BO6 owner changes the settings before our write: honoured, the writer does not overwrite it
        b = new BootOff(20000);
        b.onRead(ON, map(true, 40), false);
        m = map(true, 70);
        check(b.onRead(ON_LOUD, m, false) == BootOff.OWNER && "true".equals(m.get("enabled")), "BO6 owner's change before the write: honoured");
        st = new FakeStore();
        st.reads.add(ON_LOUD);
        r = b.writeOff(st, OFF, new FakeSleep());
        check(st.writes.isEmpty() && r.contains("skipped"), "BO6 writer skips: " + r);

        // BO7 owner turns it off before our write (app toggle reads the stored "on"): settled, no write
        b = new BootOff(20000);
        b.onRead(ON, map(true, 40), false);
        check(b.onRead(OFF, map(false, 40), false) == BootOff.SETTLED && !b.active(), "BO7 stored off: settled");
        st = new FakeStore();
        st.reads.add(OFF);
        b.writeOff(st, OFF, new FakeSleep());
        check(st.writes.isEmpty(), "BO7 writer skips");

        // BO8 the writer's read sees the boot text with whitespace around it
        b = new BootOff(20000);
        b.onRead(ON, map(true, 40), false);
        st = new FakeStore();
        st.reads.add(" " + ON + "\n");
        b.writeOff(st, OFF, new FakeSleep());
        check(st.writes.size() == 1, "BO8 stored text trimmed before comparing");

        System.out.println(failed == 0 ? "bootoff: all checks passed" : "bootoff: " + failed + " check(s) failed");
        if (failed != 0) System.exit(1);
    }
}
