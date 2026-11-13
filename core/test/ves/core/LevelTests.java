package ves.core;

import java.util.List;

import ves.tools.Replay;
import ves.tools.Sweep;

/** 0.2.7 level tests (loudness metrics, launch, makeup, volume curve, speed boost); TestMain runs them. */
final class LevelTests {
    private LevelTests() {}

    static final int SR = TestMain.SR;

    static void check(boolean c, String msg) {
        TestMain.check(c, msg);
    }

    static void near(double actual, double expected, double tol, String msg) {
        TestMain.near(actual, expected, tol, msg);
    }

    static double db(double ratio) {
        return 20 * Math.log10(ratio);
    }

    /** the trace's columns at the first row with t_ms >= t: {speed, pedal} */
    static double[] traceAt(String csv, long t) {
        String[] rows = csv.split("\n");
        for (int i = 1; i < rows.length; i++) {
            String[] f = rows[i].split(",", -1);
            if (Long.parseLong(f[0]) >= t) return new double[]{Double.parseDouble(f[1]), Double.parseDouble(f[2])};
        }
        return new double[]{Double.NaN, Double.NaN};
    }

    static void add(List<TestMain.T> l) {
        // ---------------- T0: loudness and hard-limit share in Replay
        l.add(new TestMain.T("Tools_replay_metrics") {
            void run() throws Exception {
                OutputStage st = new OutputStage(SR);
                for (int i = 0; i < 4000; i++) st.process(0.3 * Math.sin(2 * Math.PI * 600 * i / SR));
                TestMain.eq(st.clamped(), 0, "a quiet sine never reaches the clamp");
                st = new OutputStage(SR);
                for (int i = 0; i < 4000; i++) {
                    double x = 4 * Math.sin(2 * Math.PI * 600 * i / SR);
                    double y = st.process(x);
                    check(Math.abs(y) <= 1, "soft clip output within +-1");
                }
                check(st.clamped() > 400, "a loud sine is counted at the clamp: " + st.clamped());

                List<String> sc = java.util.Arrays.asList(Sweep.SCENARIOS);
                check(sc.contains("launch") && sc.contains("highway"), "launch and highway scenarios: " + sc);
                for (String name : new String[] {"launch", "highway", "accel"}) {
                    String csv = Sweep.generate(name);
                    Replay.Result r = Replay.run(csv, TestMain.iceSettings(Profile.ICE_SMOOTH, 100), SR, null);
                    String j = r.json("x");
                    check(j.contains("\"rms_dbfs\":") && j.contains("\"limit_fraction\":") && j.contains("\"rms_mark_dbfs\":"),
                            name + ": json fields " + j);
                    near(r.rmsDbfs, db(r.rms), 1e-9, name + " rms_dbfs");
                    check(r.limitFraction >= 0 && r.limitFraction <= 1, name + " limit_fraction " + r.limitFraction);
                    near(r.limitFraction, r.clamped / (r.durationS * SR), 1e-6, name + " limit_fraction = clamped / samples");
                    check(r.markMs[0] != Long.MAX_VALUE && r.markMs[1] > r.markMs[0], name + " marks");
                    check(r.rmsMarkDbfs > -40 && r.rmsMarkDbfs < 0, name + " rms_mark_dbfs " + r.rmsMarkDbfs);
                    System.out.println("    " + name + " rms_dbfs=" + r.rmsDbfs + " rms_mark_dbfs=" + r.rmsMarkDbfs
                            + " limit_fraction=" + r.limitFraction);
                    if (name.equals("launch")) {
                        double[] a = traceAt(csv, r.markMs[0] - 200), b = traceAt(csv, r.markMs[0] + 3000),
                                c = traceAt(csv, r.markMs[1] - 100);
                        near(a[0], 0, 0, "launch: standing before the first mark");
                        near(a[1], 0, 0, "launch: foot off before the first mark");
                        near(b[1], 40, 0, "launch: pedal 40 % while pulling away");
                        near(b[0], 15, 0.6, "launch: speed rises linearly (15 km/h after 3 s)");
                        near(c[0], 30, 0.6, "launch: 30 km/h at the second mark");
                        near(r.markMs[1] - r.markMs[0], 6000, 100, "launch: marks around the 6 s pull-away");
                    } else if (name.equals("highway")) {
                        long mid = (r.markMs[0] + r.markMs[1]) / 2;
                        double[] m = traceAt(csv, mid);
                        near(m[0], 110, 0.5, "highway: 110 km/h between the marks");
                        near(m[1], 15, 4, "highway: pedal 15 % between the marks");
                        check(r.markMs[1] - r.markMs[0] >= 10000, "highway: a long steady part");
                    }
                }
                // a VesCore counts its voices' clamps
                VesCore c = new VesCore(SR);
                check(c.clampedSamples() == 0, "no clamps before rendering");
            }
        });

        // ---------------- T1a: pulling away with the pedal down keeps the drive level (behaviour ice)
        l.add(new TestMain.T("M1_launch_level_no_dip") {
            void run() {
                TestMain.Rig r = new TestMain.Rig(TestMain.iceSettings(Profile.ICE_SMOOTH, 70));
                r.speed = 0;
                r.pedal = 0;
                r.kw = 0;
                r.run(2000);
                near(r.c.lastParams().targetGain, 0.30, 1e-9, "standing in D: idle level");
                r.pedal = 40;
                long t0 = r.t;
                double runMax = 0, minAfter = 9, maxDip = 0;
                while (r.t - t0 < 4000) {
                    double el = (r.t - t0) / 1000.0;
                    r.speed = 20 * el / 4; // 0 -> 20 km/h linearly in 4 s
                    r.kw = 1750 * (20 / 3.6 / 4) * (r.speed / 3.6) / 1000 + 1; // the force for that acceleration
                    r.step();
                    double g = r.c.lastParams().targetGain;
                    runMax = Math.max(runMax, g);
                    if (r.t - t0 >= 500) {
                        minAfter = Math.min(minAfter, g);
                        maxDip = Math.max(maxDip, runMax - g);
                    }
                }
                System.out.println("    launch targetGain max=" + runMax + " min(0.5..4 s)=" + minAfter);
                check(maxDip <= 0.02, "the drive level dips while pulling away: running max " + runMax + " min " + minAfter);
                // creeping with the foot off stays at idle; lifting off at 10 km/h is engine braking
                TestMain.Rig q = new TestMain.Rig(TestMain.iceSettings(Profile.ICE_SMOOTH, 70));
                q.speed = 3;
                q.pedal = 0;
                q.kw = 0;
                q.run(3000);
                check("idle".equals(q.c.lastParams().reason), "creeping foot-off: " + q.c.lastParams().reason);
                near(q.c.lastParams().targetGain, 0.30, 1e-9, "creeping foot-off: idle level");
                q.pedal = 30;
                q.speed = 10;
                q.kw = 15;
                q.run(2000);
                check("drive".equals(q.c.lastParams().reason), "driving at 10 km/h: " + q.c.lastParams().reason);
                q.pedal = 0;
                q.kw = -5;
                q.run(1000);
                check("overrun".equals(q.c.lastParams().reason), "lift-off at 10 km/h: " + q.c.lastParams().reason);
            }
        });

        // ---------------- T1b: the launch slip rpm holds until first gear catches up (ICE_SHIFT)
        l.add(new TestMain.T("M2_launch_rpm_monotonic") {
            void run() {
                TestMain.Rig r = new TestMain.Rig(TestMain.iceSettings(Profile.ICE_SHIFT, 70));
                r.speed = 0;
                r.pedal = 0;
                r.kw = 0;
                r.run(2000);
                r.pedal = 40;
                long t0 = r.t;
                double runMax = 0, maxDrop = 0, dropAt = -1, shiftKph = -1, maxAtDrop = 0;
                while (r.t - t0 < 8000) {
                    double el = (r.t - t0) / 1000.0;
                    r.speed = Math.min(40, 30 * el / 6); // 0 -> 30 km/h in 6 s, on to 40
                    r.kw = 1750 * (30 / 3.6 / 6) * (r.speed / 3.6) / 1000 + 1;
                    r.step();
                    EngineParams p = r.c.lastParams();
                    if (p.virtualGear != 1) {
                        shiftKph = p.speedKph;
                        break;
                    }
                    runMax = Math.max(runMax, p.rpm);
                    if (runMax - p.rpm > maxDrop) {
                        maxDrop = runMax - p.rpm;
                        dropAt = p.speedKph;
                        maxAtDrop = runMax;
                    }
                }
                System.out.println("    launch rpm max=" + Math.round(runMax) + " drop=" + Math.round(maxDrop) + " at "
                        + dropAt + " km/h, first upshift at " + shiftKph + " km/h");
                check(maxDrop <= 15, "rpm falls in first gear: " + Math.round(maxAtDrop) + " -> " + Math.round(maxAtDrop - maxDrop)
                        + " at " + dropAt + " km/h");
                check(shiftKph >= 15 && shiftKph <= 30, "first upshift at 15-30 km/h: " + shiftKph);
            }
        });

        // ---------------- T3: the loop gains are worked out when the pack loads, not on the audio thread
        l.add(new TestMain.T("PV1_setpack_reads_no_pcm") {
            void run() throws Exception {
                Pack p = PackLoader.load(TestMain.sinePack(false, false));
                PackVoice ref = new PackVoice(SR);
                ref.setPack(p);
                double before = TestMain.rmsOf(ref, 2520, 1, 0);
                near(before, PackVoice.LOOP_RMS, 0.01, "a loaded loop plays at the voice level");
                for (Pack.Loop lp : p.on) for (int i = 0; i < lp.pcm.length; i++) lp.pcm[i] /= 2;
                for (Pack.Loop lp : p.off) if (p.off != p.on) for (int i = 0; i < lp.pcm.length; i++) lp.pcm[i] /= 2;
                PackVoice v = new PackVoice(SR);
                v.setPack(p); // must not read the (now halved) PCM to work out gains
                double after = TestMain.rmsOf(v, 2520, 1, 0);
                System.out.println("    rms loaded=" + before + " after halving the pcm=" + after);
                check(after < 0.2, "setPack re-normalized the loops from their PCM: rms " + after);
                near(after, before / 2, 0.01, "halved PCM plays at half level with the load-time gains");
            }
        });

        // ---------------- T4a: an upward makeup stage before the soft clip
        l.add(new TestMain.T("OS1_makeup_bounded") {
            void run() {
                // makeup 1 is the 0.2.6 stage, bit for bit
                OutputStage a = new OutputStage(SR), b = new OutputStage(SR);
                java.util.Random rnd = new java.util.Random(7);
                for (int i = 0; i < 20000; i++) {
                    double x = 1.5 * Math.sin(2 * Math.PI * 140 * i / SR) + 0.8 * rnd.nextGaussian();
                    double ya = a.process(x), yb = b.process(x, 1);
                    check(Double.doubleToLongBits(ya) == Double.doubleToLongBits(yb), "process(x, 1) != process(x) at " + i);
                }
                Synth sa = new Synth(SR, Profile.ICE_SMOOTH, 3), sb = new Synth(SR, Profile.ICE_SMOOTH, 3);
                EngineParams p = new EngineParams();
                p.rpm = 2500;
                p.load = 0.6;
                short[] oa = new short[TestMain.BLOCK], ob = new short[TestMain.BLOCK];
                for (int k = 0; k < 100; k++) {
                    sa.render(oa, 0, oa.length, p, 0.5);
                    sb.render(ob, 0, ob.length, p, 0.5, 1);
                    check(java.util.Arrays.equals(oa, ob), "Synth.render(.., 1) != render(..) in block " + k);
                }
                // the 600 Hz gain from the input to the soft clip (small signal: the clip is linear there)
                OutputStage cal = new OutputStage(SR);
                double pk = 0;
                for (int i = 0; i < SR; i++) {
                    double y = cal.process(0.001 * Math.sin(2 * Math.PI * 600 * i / SR));
                    if (i > SR / 2) pk = Math.max(pk, Math.abs(y));
                }
                double gain600 = pk / 0.001;
                // peak 0.5 into the clip: x2 makes 1.0, still nearly linear there (at 0.8 the soft clip itself
                // takes the lift down to +3.6 dB)
                double[] m1 = sine(gain600, 0.5, 1, 1), m2 = sine(gain600, 0.5, 2, 2);
                double dbUp = db(m2[0] / m1[0]);
                System.out.println("    600 Hz peak 0.5: rms m=1 " + m1[0] + " m=2 " + m2[0] + " (+" + dbUp + " dB), max " + m2[1]);
                check(dbUp >= 4, "makeup 2 lifts a quiet sine by >= 4 dB: " + dbUp);
                check(m2[1] <= 1 && m1[1] <= 1, "output within +-1: " + m2[1]);
                double[] q1 = sine(gain600, 0.8, 1, 1), q2 = sine(gain600, 0.8, 2, 2);
                System.out.println("    600 Hz peak 0.8: +" + db(q2[0] / q1[0]) + " dB, max " + q2[1]);
                check(db(q2[0] / q1[0]) >= 3.5 && q2[1] <= 1, "makeup 2 at peak 0.8: " + db(q2[0] / q1[0]));
                // a signal over TPK gets no makeup: no more clamping than without
                double[] h1 = sine(gain600, 4, 1, 1), h2 = sine(gain600, 4, 2, 2);
                check(h1[2] > 0, "the loud sine reaches the clamp");
                check(h2[2] <= h1[2], "makeup adds clamping to a loud signal: " + h2[2] + " > " + h1[2]);
                near(h2[0], h1[0], 1e-9, "a loud signal is not lifted");
                // makeup stepping 1 -> 3 inside one block: no bigger sample steps than steady makeup 3
                double[] steady = sine(gain600, 0.8, 3, 3);
                double[] step = sine(gain600, 0.8, 1, 3);
                System.out.println("    max delta steady m=3 " + steady[3] + ", stepping 1->3 " + step[3]);
                check(step[3] <= 1.1 * steady[3], "makeup step clicks: " + step[3] + " vs steady " + steady[3]);
                check(step[1] <= 1, "stepping output within +-1");
            }

            /**
             * a 600 Hz sine whose peak into the soft clip is peakIn, 1 s at makeup m0 (stepping to m1 over the
             * first 10 ms block at 0.5 s): {rms and max |y| of the last 0.4 s, clamped samples, max sample delta}
             */
            double[] sine(double gain600, double peakIn, double m0, double m1) {
                OutputStage st = new OutputStage(SR);
                double ss = 0, mx = 0, md = 0, prev = 0;
                int n = 0, half = SR / 2, blk = TestMain.BLOCK;
                for (int i = 0; i < SR; i++) {
                    double m = i < half ? m0 : i >= half + blk ? m1 : m0 + (m1 - m0) * (i - half + 1) / (double) blk;
                    double y = st.process(peakIn / gain600 * Math.sin(2 * Math.PI * 600 * i / SR), m);
                    if (i > SR / 4) md = Math.max(md, Math.abs(y - prev));
                    prev = y;
                    if (i >= SR - (int) (0.4 * SR)) {
                        ss += y * y;
                        n++;
                    }
                    if (i > SR / 4) mx = Math.max(mx, Math.abs(y));
                }
                return new double[]{Math.sqrt(ss / n), mx, st.clamped(), md};
            }
        });

        // a sudden loud onset (a pop, a kickdown) after quiet lifted sound: the makeup drops at once, so the onset
        // is never pushed past TPK into the hard limit
        l.add(new TestMain.T("OS2_makeup_onset") {
            void run() {
                long[] clamps = new long[2];
                double[] mx = new double[2];
                for (int k = 0; k < 2; k++) {
                    OutputStage st = new OutputStage(SR);
                    double m = k == 0 ? 1 : 3;
                    for (int i = 0; i < SR; i++) {
                        double a = i < SR / 2 ? 0.02 : 0.6; // about peak 0.25 then 2.9 into the clip at 600 Hz
                        double y = st.process(a * Math.sin(2 * Math.PI * 600 * i / SR), m);
                        mx[k] = Math.max(mx[k], Math.abs(y));
                    }
                    clamps[k] = st.clamped();
                }
                System.out.println("    onset: clamped m=1 " + clamps[0] + " m=3 " + clamps[1] + ", max " + mx[1]);
                check(clamps[1] <= clamps[0], "the makeup pushes an onset into the hard limit: " + clamps[1] + " > " + clamps[0]);
            }
        });

        // ---------------- T4b: the whole volume curve 6 dB louder
        l.add(new TestMain.T("VC1_volume_louder") {
            void run() throws Exception {
                // 0.2.6 output RMS (dc7a1b8, this rig, Vc1Probe): synth / sine pack at volume 70 and 100
                double[][] old = {{70, 2299.054655386545, 3942.9569864026175}, {100, 3925.8571823947705, 6732.804557036954}};
                for (double[] o : old) {
                    for (int pack = 0; pack < 2; pack++) {
                        Settings s = TestMain.iceSettings(Profile.ICE_SMOOTH, (int) o[0]);
                        if (pack == 1) s.sound = "user1";
                        TestMain.Rig r = new TestMain.Rig(s);
                        if (pack == 1) r.c.setPack(PackLoader.load(TestMain.sinePack(false, false)));
                        r.speed = 35;
                        r.pedal = 5;
                        r.run(3000);
                        r.resetStats();
                        r.run(3000);
                        double up = db(r.rms() / o[1 + pack]);
                        String what = (pack == 1 ? "pack" : "synth") + " volume " + (int) o[0];
                        System.out.println("    " + what + ": rms " + Math.round(r.rms()) + " (+" + up + " dB over 0.2.6), peak "
                                + r.peak + ", mk=" + r.c.lastMakeup());
                        check(up >= 4, what + " not 4 dB louder than 0.2.6: " + up);
                        TestMain.eq(r.capViolations, 0, what + " above the gain cap");
                        check(r.peak <= 30000, what + " above full scale: " + r.peak);
                    }
                }
                // the status line carries the makeup
                check(new VesCore(SR).status().contains(" mk="), "status has mk=");
            }
        });

        // ---------------- T5: the level rises with speed (speed_boost off/low/mid/high)
        l.add(new TestMain.T("SB1_curve") {
            void run() {
                near(GainEnvelope.speedBoost("high", 0), 1, 0, "high at 0 km/h");
                near(GainEnvelope.speedBoost("high", 40), 1, 0, "high at 40 km/h");
                near(GainEnvelope.speedBoost("high", 110), Math.pow(10, 9 / 20.0), 1e-12, "high at 110 km/h: +9 dB");
                near(GainEnvelope.speedBoost("high", 150), Math.pow(10, 9 / 20.0), 1e-12, "high at 150 km/h: +9 dB");
                near(db(GainEnvelope.speedBoost("mid", 75)), 3, 0.05, "mid at 75 km/h: +3 dB");
                near(db(GainEnvelope.speedBoost("low", 110)), 3, 1e-9, "low at 110 km/h: +3 dB");
                near(db(GainEnvelope.speedBoost("mid", 110)), 6, 1e-9, "mid at 110 km/h: +6 dB");
                for (int v = 0; v <= 200; v += 10) near(GainEnvelope.speedBoost("off", v), 1, 0, "off at " + v);
                near(GainEnvelope.speedBoost("high", Double.NaN), 1, 0, "NaN speed");
                double prev = 0;
                for (int v = 0; v <= 200; v++) {
                    double g = GainEnvelope.speedBoost("high", v);
                    check(g >= prev, "monotonic at " + v);
                    prev = g;
                }
            }
        });

        l.add(new TestMain.T("SB2_settings") {
            void run() {
                check(new Settings().speedBoost.equals("high"), "default high");
                check(java.util.Arrays.equals(Settings.SPEED_BOOSTS, new String[] {"off", "low", "mid", "high"}), "choices");
                java.util.Map<String, String> m = new java.util.HashMap<String, String>();
                check(Settings.fromMap(m).speedBoost.equals("high"), "missing: high");
                for (String v : Settings.SPEED_BOOSTS) {
                    m.put("speed_boost", v);
                    check(Settings.fromMap(m).speedBoost.equals(v), "parses " + v);
                }
                m.put("speed_boost", " MID ");
                check(Settings.fromMap(m).speedBoost.equals("mid"), "trims and lower-cases");
                m.put("speed_boost", "loud");
                check(Settings.fromMap(m).speedBoost.equals("high"), "garbage: high");
                Settings a = new Settings(), b = new Settings();
                b.speedBoost = "off";
                check(!a.equals(b) && a.hashCode() != b.hashCode(), "equals/hashCode see speed_boost");
                check(b.copy().equals(b) && b.copy().speedBoost.equals("off"), "copy keeps it");
                b.speedBoost = "nonsense";
                check(b.copy().speedBoost.equals("high"), "copy sanitizes");
                check(a.toString().contains("speed_boost=high"), "toString: " + a);
                check(TestMain.settings(Profile.ICE_SMOOTH, 50, "idle_regen").speedBoost.equals("off"), "0.1.x rig settings: off");
            }
        });

        l.add(new TestMain.T("SB3_highway_louder") {
            void run() {
                double[] rms = new double[2];
                for (double kph : new double[] {100, 30}) {
                    TestMain.Rig[] r = new TestMain.Rig[2];
                    boolean same = true;
                    for (int k = 0; k < 2; k++) {
                        Settings s = TestMain.iceSettings(Profile.ICE_SMOOTH, 60);
                        s.speedBoost = k == 0 ? "off" : "high";
                        r[k] = new TestMain.Rig(s);
                        r[k].speed = kph;
                        r[k].pedal = 15;
                    }
                    for (int b = 0; b < 600; b++) {
                        if (b == 300) for (TestMain.Rig q : r) q.resetStats();
                        r[0].step();
                        r[1].step();
                        if (!java.util.Arrays.equals(r[0].buf, r[1].buf)) same = false;
                    }
                    for (int k = 0; k < 2; k++) {
                        TestMain.eq(r[k].capViolations, 0, kph + " km/h cap violations (" + k + ")");
                        rms[k] = r[k].rms();
                    }
                    System.out.println("    " + kph + " km/h volume 60 pedal 15: rms off " + Math.round(rms[0]) + " high "
                            + Math.round(rms[1]) + " (+" + db(rms[1] / rms[0]) + " dB), mk=" + r[1].c.lastMakeup());
                    if (kph == 100) check(db(rms[1] / rms[0]) >= 6.5, "high at 100 km/h: +" + db(rms[1] / rms[0]) + " dB");
                    else check(same, "30 km/h: high must be identical to off");
                }
            }
        });

        l.add(new TestMain.T("SB4_bounds") {
            void run() {
                // a pull from 60 to 110 km/h at full pedal, volume 100: off and high on the same trace
                TestMain.Rig[] r = new TestMain.Rig[2];
                for (int k = 0; k < 2; k++) {
                    Settings s = TestMain.iceSettings(Profile.ICE_SMOOTH, 100);
                    s.speedBoost = k == 0 ? "off" : "high";
                    r[k] = new TestMain.Rig(s);
                    r[k].speed = 60;
                    r[k].pedal = 0;
                    r[k].kw = -10;
                    r[k].run(3000);
                    r[k].pedal = 100; // in one update
                    r[k].kw = 150;
                    for (int b = 0; b < 500; b++) {
                        r[k].speed = Math.min(110, 60 + b * 0.2);
                        r[k].step();
                    }
                    r[k].run(3000);
                    r[k].pedal = 0; // lift-off at 110
                    r[k].kw = -40;
                    r[k].run(2000);
                }
                // a click is a step bigger than the loudest steady sound's own steepest step (as S1d)
                int steady = 0;
                for (Profile sp : Profile.values()) {
                    for (double kph : new double[] {30, 80, 110}) {
                        Settings s = TestMain.iceSettings(sp, 100);
                        s.speedBoost = "high";
                        TestMain.Rig q = new TestMain.Rig(s);
                        q.speed = kph;
                        q.pedal = 100;
                        q.run(3000);
                        q.resetStats();
                        q.havePrev = false;
                        q.run(2000);
                        steady = Math.max(steady, q.maxDelta);
                    }
                }
                long cOff = r[0].c.clampedSamples(), cHigh = r[1].c.clampedSamples();
                System.out.println("    110 km/h full pedal volume 100: peak off " + r[0].peak + " high " + r[1].peak + ", maxDelta high "
                        + r[1].maxDelta + " (steady " + steady + "), clamped off " + cOff + " high " + cHigh);
                for (int k = 0; k < 2; k++) {
                    check(r[k].peak <= 30000, "peak above full scale: " + r[k].peak);
                    TestMain.eq(r[k].capViolations, 0, "cap violations (" + k + ")");
                    check(r[k].maxDelta <= steady * 1.1 + 300, "click: max sample delta " + r[k].maxDelta + " > steady " + steady);
                }
                check(cHigh <= cOff, "speed boost limits harder: clamped " + cHigh + " > " + cOff);
            }
        });

        l.add(new TestMain.T("SB5_fade_still_silences") {
            void run() {
                Settings s = TestMain.iceSettings(Profile.ICE_SMOOTH, 100);
                s.speedBoost = "high";
                s.fadeAboveKph = 60;
                TestMain.Rig r = new TestMain.Rig(s);
                r.speed = 100;
                r.pedal = 30;
                r.run(3000);
                r.resetStats();
                r.run(1000);
                check("fade_speed".equals(r.c.lastParams().reason), "reason " + r.c.lastParams().reason);
                near(r.c.lastGain(), 0, 0, "fade_above still reaches 0 with speed_boost high");
                TestMain.eq(r.zeros, r.n, "silent above the fade");
            }
        });
    }
}
