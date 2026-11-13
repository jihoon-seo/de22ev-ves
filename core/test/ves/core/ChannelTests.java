package ves.core;

import java.util.List;

import ves.core.TestMain.Rig;
import ves.core.TestMain.T;

/**
 * Sharing MICOM channel 0x45 with the navigation (0.2.7). Drive log 2026-11-13: the navigation's own effect
 * sounds end with AudioEnd(0x45) while we hold the channel (about one every 1.8 min), and MICOM sends one 2-28 ms
 * before every NavigationVoiceStart. Until 0.2.6 each one closed our path: the track was released, 500 ms of
 * hold-off, a new track and a 250 ms attack (the owner heard the sound stop and start again).
 * Registered from TestMain.all().
 */
final class ChannelTests {
    private ChannelTests() {
    }

    /** a rig playing steadily for 3 s (ICE behaviour, 40 km/h, 30 % pedal, no pops) */
    static Rig playing(String prompts) {
        Settings st = TestMain.iceSettings(Profile.ICE_SMOOTH, 60);
        st.pops = "off";
        st.prompts = prompts;
        Rig r = new Rig(st);
        r.speed = 40;
        r.pedal = 30;
        r.run(3000);
        return r;
    }

    static double rmsOver(Rig r, long ms) {
        r.resetStats();
        r.run(ms);
        return r.rms();
    }

    /** steps one block; true if any sample was non-zero */
    static boolean audibleStep(Rig r) {
        long z = r.zeros;
        r.step();
        return r.zeros - z < TestMain.BLOCK;
    }

    /** steps until the first audible block (its start time) or until; -1 if none */
    static long firstAudible(Rig r, long until) {
        while (r.t < until) {
            long bt = r.t;
            if (audibleStep(r)) return bt;
        }
        return -1;
    }

    /** steps until the first block whose action is act (its start time) or until; -1 if none */
    static long stepUntil(Rig r, int act, long until) {
        while (r.t < until) {
            long bt = r.t;
            r.step();
            if (r.lastAction == act) return bt;
        }
        return -1;
    }

    static void add(List<T> l) {
        // S10 a navigation effect sound ends on our channel, no voice after it: take the channel back after
        // REGRAB_WAIT_MS with the track and the envelope kept (0.2.6: close, 500 ms hold-off, re-attack)
        l.add(new T("S10_chime_end_regrab") {
            void run() {
                Rig r = playing("duck");
                double before = rmsOver(r, 500);
                TestMain.check(r.c.isOpen(), "S10 playing");
                long t = r.t;
                TestMain.eq(r.c.ownTypeEvent(false, t), SharedChannel.FOREIGN_END, "S10 foreign end");
                r.resetStats();
                audibleStep(r); // the synth fades the block's gain from the previous block's
                TestMain.check(!audibleStep(r), "S10 silent within one block after the end");
                long back = firstAudible(r, t + 250);
                TestMain.eq(r.closes, 0, "S10 closes after a chime end");
                TestMain.eq(r.regrabs, 1, "S10 regrabs");
                TestMain.check(back >= 0 && back - t <= 160, "S10 sound back within 160 ms: " + (back < 0 ? -1 : back - t));
                double after = rmsOver(r, 500);
                TestMain.check(after >= 0.9 * before, "S10 level back: " + Math.round(after) + " of " + Math.round(before));
                r.resetStats();
                r.run(3000);
                TestMain.check(r.closes == 0 && r.regrabs == 0 && r.opens == 0 && r.c.isOpen(), "S10 then it just plays");
            }
        });
        // S11 the end comes 2-28 ms before NavigationVoiceStart: closed for the voice, no regrab, no open
        // before its end (as 0.2.6, Y7 and S9)
        l.add(new T("S11_voice_after_end") {
            void run() {
                Rig r = playing("duck");
                long t = r.t;
                r.c.ownTypeEvent(false, t);
                r.resetStats();
                r.run(20);
                r.c.onYieldStart("navi", t + 20);
                r.run(20);
                TestMain.check(!r.c.isOpen(), "S11 closed by t+40");
                TestMain.eq(r.closes, 1, "S11 one close");
                r.resetStats();
                r.run(3000 - 40);
                TestMain.eq(r.regrabs, 0, "S11 regrabs");
                TestMain.eq(r.opens, 0, "S11 opens during the voice");
                TestMain.eq(r.zeros, r.n, "S11 silent during the voice");
                r.c.onYieldEnd("navi", r.t);
                r.resetStats();
                r.run(1000);
                TestMain.eq(r.opens, 1, "S11 back after the voice");
            }
        });
        // S12 a voice that starts just after our regrab: closed, never ducked (a startAudio of ours may be in its way)
        l.add(new T("S12_late_voice_after_regrab") {
            void run() {
                Rig r = playing("duck");
                long t = r.t;
                r.c.ownTypeEvent(false, t);
                r.resetStats();
                r.run(AudioPolicy.REGRAB_WAIT_MS + 20);
                TestMain.eq(r.regrabs, 1, "S12 regrabbed before the voice");
                r.c.onYieldStart("navi", r.t);
                r.run(20);
                TestMain.check(!r.c.isOpen(), "S12 closed for a voice right after the regrab");
                r.resetStats();
                r.run(2000);
                TestMain.eq(r.zeros, r.n, "S12 silent under the voice (not ducked)");
                TestMain.eq(r.opens, 0, "S12 no open during the voice");
                r.c.onYieldEnd("navi", r.t);
                r.resetStats();
                r.run(1000);
                TestMain.eq(r.opens, 1, "S12 back after the voice");
            }
        });
        // S13 MICOM refuses the regrab: not open, no endAudio, the usual 1-30 s backoff
        l.add(new T("S13_regrab_refused") {
            void run() {
                Rig r = playing("duck");
                long t = r.t;
                r.c.ownTypeEvent(false, t);
                r.resetStats();
                long rg = stepUntil(r, AudioPolicy.ACT_REGRAB, t + 300);
                TestMain.check(rg >= 0 && rg - t <= 150, "S13 regrab within 150 ms: " + (rg < 0 ? -1 : rg - t));
                r.c.regrabResult(false, rg);
                TestMain.check(!r.c.isOpen(), "S13 refused: not open");
                TestMain.eq(r.c.policy().retries(), 1, "S13 retries");
                long op = stepUntil(r, AudioPolicy.ACT_OPEN, rg + 5000);
                TestMain.check(op >= 0 && op - rg >= 1000, "S13 next open after 1 s: " + (op < 0 ? -1 : op - rg));
                TestMain.eq(r.closes, 0, "S13 no CLOSE after a refused regrab");
            }
        });
        // S14 another end within REGRAB_GUARD_MS after a regrab: the 0.2.6 close and hold-off (no thrash)
        l.add(new T("S14_thrash_guard") {
            void run() {
                Rig r = playing("duck");
                long t = r.t;
                r.c.ownTypeEvent(false, t);
                r.resetStats();
                r.run(600);
                TestMain.eq(r.regrabs, 1, "S14 the first end regrabs");
                TestMain.eq(r.closes, 0, "S14 the first end does not close");
                long t2 = r.t;
                TestMain.eq(r.c.ownTypeEvent(false, t2), SharedChannel.FOREIGN_END, "S14 second end");
                r.resetStats();
                long cl = stepUntil(r, AudioPolicy.ACT_CLOSE, t2 + 100);
                TestMain.check(cl >= 0 && cl - t2 <= 20, "S14 closed at once: " + (cl < 0 ? -1 : cl - t2));
                long op = stepUntil(r, AudioPolicy.ACT_OPEN, t2 + 2000);
                TestMain.check(op - t2 >= AudioPolicy.HOLDOFF_MS && op - t2 <= AudioPolicy.HOLDOFF_MS + 100,
                        "S14 reopened after the hold-off: " + (op - t2));
                TestMain.eq(r.regrabs, 0, "S14 no second regrab");
                r.run(2000);
                r.resetStats();
                r.c.ownTypeEvent(false, r.t);
                r.run(300);
                TestMain.check(r.regrabs == 1 && r.closes == 0, "S14 a later end regrabs again");
            }
        });
        // S15 MICOM restores our channel (AudioStart within RESTORE_MS) during the grace window: no startAudio
        l.add(new T("S15_restore_in_grace") {
            void run() {
                Rig r = playing("duck");
                double before = rmsOver(r, 500);
                long t = r.t;
                r.c.ownTypeEvent(false, t);
                r.resetStats();
                r.run(10);
                r.resetStats();
                r.run(20);
                TestMain.eq(r.zeros, r.n, "S15 silent in the grace window");
                TestMain.eq(r.c.ownTypeEvent(true, t + 30), SharedChannel.RESTORE, "S15 restore");
                long back = firstAudible(r, t + 1000);
                TestMain.check(back >= 0 && back - t <= 30 + 60, "S15 back soon after the restore: " + (back < 0 ? -1 : back - t));
                r.run(t + 1000 - r.t);
                TestMain.eq(r.closes, 0, "S15 closes");
                TestMain.eq(r.regrabs, 0, "S15 regrabs");
                double after = rmsOver(r, 500);
                TestMain.check(after >= 0.9 * before, "S15 level back: " + Math.round(after) + " of " + Math.round(before));
            }
        });

        // ---------------- VR prompts=voice (experimental, off by default): after the AudioEnd that comes before a
        // navigation voice, take the channel back VOICE_REGRAB_DELAY_MS into the voice and play at DUCK_GAIN

        // VR1 with the flag off nothing changes: duck and pause close for the voice; voice is duck byte for byte
        // wherever no end comes before a voice
        l.add(new T("VR1_voice_flag_off") {
            void run() {
                String[] modes = {"duck", "pause"};
                for (int i = 0; i < modes.length; i++) {
                    Rig r = playing(modes[i]);
                    TestMain.check("off".equals(r.c.voiceRegrabState()), "VR1 " + modes[i] + " state " + r.c.voiceRegrabState());
                    voiceAfterEndCloses(r, "VR1 " + modes[i]);
                }
                Rig d = playing("duck"), v = playing("voice");
                long t0 = d.t;
                for (int k = 0; k < 1200; k++) {
                    long t = d.t;
                    if (t == t0 + 1000) { // a prompt with no end before it: ducked
                        d.c.onYieldStart("navi", t);
                        v.c.onYieldStart("navi", t);
                    }
                    if (t == t0 + 3000) {
                        d.c.onYieldEnd("navi", t);
                        v.c.onYieldEnd("navi", t);
                    }
                    if (t == t0 + 5000) { // a chime end: regrab
                        d.c.ownTypeEvent(false, t);
                        v.c.ownTypeEvent(false, t);
                    }
                    if (t == t0 + 8000) { // a call
                        d.c.onYieldStart("btvoice", t);
                        v.c.onYieldStart("btvoice", t);
                    }
                    if (t == t0 + 9000) {
                        d.c.onYieldEnd("btvoice", t);
                        v.c.onYieldEnd("btvoice", t);
                    }
                    d.step();
                    v.step();
                    TestMain.eq(v.lastAction, d.lastAction, "VR1 action at " + (t - t0));
                    for (int j = 0; j < TestMain.BLOCK; j++)
                        if (v.buf[j] != d.buf[j]) throw new AssertionError("VR1 voice differs from duck at " + (t - t0) + " ms");
                }
                TestMain.check(d.regrabs == 1, "VR1 the chime end regrabbed");
            }
        });
        // VR2 the voice regrab: silent until 400 ms into the voice, one REGRAB, 30 % under it, full after it
        l.add(new T("VR2_voice_regrab") {
            void run() {
                Rig r = playing("voice");
                double before = rmsOver(r, 500);
                TestMain.check("on".equals(r.c.voiceRegrabState()), "VR2 state " + r.c.voiceRegrabState());
                long rg = voiceRegrab(r);
                TestMain.check(rg >= 0, "VR2 no regrab during the voice");
                TestMain.eq(r.zeros, r.n, "VR2 silent until the regrab");
                TestMain.eq(r.closes, 0, "VR2 path kept open");
                r.run(100);
                double ducked = rmsOver(r, 1500);
                TestMain.check(ducked > before * 0.2 && ducked < before * 0.45,
                        "VR2 ducked to ~30%: " + Math.round(ducked) + " of " + Math.round(before));
                r.c.onYieldEnd("navi", r.t);
                r.run(400);
                double after = rmsOver(r, 500);
                TestMain.check(after > before * 0.8, "VR2 full again after the voice: " + Math.round(after) + " of " + Math.round(before));
                TestMain.check(r.closes == 0 && r.opens == 0 && r.regrabs == 0, "VR2 no more path actions");
                TestMain.check("on".equals(r.c.voiceRegrabState()), "VR2 still on: " + r.c.voiceRegrabState());
                // closed by an end (here: the thrash guard after a chime regrab), the voice 20 ms later: the
                // regrab opens the closed path
                Rig c = playing("voice");
                c.c.ownTypeEvent(false, c.t);
                c.run(1000);
                TestMain.eq(c.regrabs, 1, "VR2b chime regrab");
                long e = c.t;
                c.c.ownTypeEvent(false, e);
                c.run(20);
                TestMain.check(!c.c.isOpen(), "VR2b closed by the second end");
                c.c.onYieldStart("navi", e + 20);
                c.resetStats();
                long rg2 = stepUntil(c, AudioPolicy.ACT_REGRAB, e + 1000);
                TestMain.check(rg2 - (e + 20) >= AudioPolicy.VOICE_REGRAB_DELAY_MS && rg2 - (e + 20) <= AudioPolicy.VOICE_REGRAB_DELAY_MS + 10,
                        "VR2b regrab 400 ms into the voice: " + (rg2 - e - 20));
                TestMain.check(c.c.isOpen(), "VR2b open after the regrab");
                c.run(500);
                double d2 = rmsOver(c, 1000);
                TestMain.check(d2 > before * 0.2 && d2 < before * 0.45, "VR2b ducked: " + Math.round(d2) + " of " + Math.round(before));
            }
        });
        // VR3 any sign that the regrab cut the voice gives up for the rest of the process: refused, our channel ended
        // within 2 s, or the voice ended within 300 ms of our startAudio. Later voices close as with duck.
        l.add(new T("VR3_voice_give_up") {
            void run() {
                Rig a = playing("voice");
                long rg = voiceRegrab(a);
                TestMain.check(rg >= 0, "VR3a regrab");
                a.c.regrabResult(false, rg);
                TestMain.check(!a.c.isOpen(), "VR3a refused: not open");
                TestMain.check(a.c.voiceRegrabState().startsWith("given up (refused"), "VR3a state " + a.c.voiceRegrabState());
                a.run(1500);
                a.c.onYieldEnd("navi", a.t);
                a.run(3000);
                voiceAfterEndCloses(a, "VR3a");

                Rig b = playing("voice");
                rg = voiceRegrab(b);
                TestMain.check(rg >= 0, "VR3b regrab");
                b.run(500);
                long e = b.t;
                b.c.ownTypeEvent(false, e);
                TestMain.check(b.c.voiceRegrabState().startsWith("given up (channel ended"), "VR3b state " + b.c.voiceRegrabState());
                long cl = stepUntil(b, AudioPolicy.ACT_CLOSE, e + 100);
                TestMain.check(cl >= 0 && cl - e <= 20, "VR3b closed at once: " + (cl < 0 ? -1 : cl - e));
                TestMain.eq(b.regrabs, 1, "VR3b no second regrab");
                b.run(1000);
                b.c.onYieldEnd("navi", b.t);
                b.run(3000);
                voiceAfterEndCloses(b, "VR3b");

                Rig c = playing("voice");
                rg = voiceRegrab(c);
                TestMain.check(rg >= 0, "VR3c regrab");
                c.run(200);
                c.c.onYieldEnd("navi", c.t);
                TestMain.check(c.c.voiceRegrabState().startsWith("given up (voice ended"), "VR3c state " + c.c.voiceRegrabState());
                c.run(3000);
                voiceAfterEndCloses(c, "VR3c");
                c.c.setSettings(c.c.settings()); // a settings reload keeps the latch
                TestMain.check(c.c.voiceRegrabState().startsWith("given up"), "VR3c latched: " + c.c.voiceRegrabState());
            }
        });
    }

    /**
     * Our channel ends, NavigationVoiceStart 20 ms later; returns the time of the REGRAB block within 1 s of the
     * voice start (-1: none). Stats are reset at the voice start.
     */
    static long voiceRegrab(Rig r) {
        long t = r.t;
        r.c.ownTypeEvent(false, t);
        r.run(20);
        r.c.onYieldStart("navi", t + 20);
        r.resetStats();
        long rg = stepUntil(r, AudioPolicy.ACT_REGRAB, t + 1020);
        if (rg >= 0) {
            long d = rg - (t + 20);
            TestMain.check(d >= AudioPolicy.VOICE_REGRAB_DELAY_MS && d <= AudioPolicy.VOICE_REGRAB_DELAY_MS + 10,
                    "regrab 400 ms after the voice start: " + d);
        }
        return rg;
    }

    /** the 0.2.6/T2 behaviour: an end, a voice 20 ms later: closed, silent, no regrab, back after the voice */
    static void voiceAfterEndCloses(Rig r, String id) {
        TestMain.check(r.c.isOpen(), id + " playing before");
        long t = r.t;
        r.c.ownTypeEvent(false, t);
        r.run(20);
        r.c.onYieldStart("navi", t + 20);
        r.run(20);
        TestMain.check(!r.c.isOpen(), id + " closed by t+40");
        r.resetStats();
        r.run(2000);
        TestMain.check(r.regrabs == 0 && r.opens == 0 && r.zeros == r.n,
                id + " silent and closed under the voice: regrabs " + r.regrabs + " opens " + r.opens + " audible " + (r.n - r.zeros));
        r.c.onYieldEnd("navi", r.t);
        r.resetStats();
        r.run(1000);
        TestMain.eq(r.opens, 1, id + " back after the voice");
    }
}
