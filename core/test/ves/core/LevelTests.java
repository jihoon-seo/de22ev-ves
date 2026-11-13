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
    }
}
