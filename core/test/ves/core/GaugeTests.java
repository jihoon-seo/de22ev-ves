package ves.core;

import java.util.ArrayList;
import java.util.List;

import ves.gauge.GaugeMath;
import ves.gauge.GaugeRenderer;
import ves.gauge.LiveData;
import ves.gauge.Needle;
import ves.gauge.Painter;
import ves.gauge.Theme;

import static ves.core.TestMain.check;
import static ves.core.TestMain.near;

/** 0.2.12 RPM + gear gauge: the model's upshift point, vesd's LIVE line, the drive-mode log and the gauge code */
final class GaugeTests {
    private GaugeTests() {}

    static void add(List<TestMain.T> l) {
        // ---------------- GA1 EngineParams.shiftRpm: in ICE_SHIFT the model's own upshift point for the gear it is in
        // (2200 + 3800 x load^1.3, as EngineModel.gearbox), none in 6th; fixed near the top of the scale elsewhere
        l.add(new TestMain.T("GA1_shift_rpm") {
            void run() {
                TestMain.Rig r = new TestMain.Rig(TestMain.iceSettings(Profile.ICE_SHIFT, 70));
                r.speed = 0;
                r.pedal = 35;
                r.kw = 30;
                int checked = 0;
                boolean sixth = false;
                for (int i = 0; i < 4000; i++) { // 40 s: 0 -> 130 km/h
                    r.speed = Math.min(130, i * 0.04);
                    r.step();
                    EngineParams p = r.c.lastParams();
                    if (p.shifting || p.virtualGear < 1) continue;
                    if (p.virtualGear < 6) {
                        near(p.shiftRpm, 2200 + 3800 * Math.pow(p.load, 1.3), 1e-6, "shiftRpm in gear " + p.virtualGear);
                        checked++;
                    } else {
                        check(Double.isNaN(p.shiftRpm), "6th has no upshift: " + p.shiftRpm);
                        sixth = true;
                    }
                }
                check(checked > 100 && sixth, "gears 1-5 checked " + checked + ", 6th reached " + sixth);
                TestMain.Rig s = new TestMain.Rig(TestMain.iceSettings(Profile.ICE_SMOOTH, 70));
                s.speed = 50;
                s.pedal = 30;
                s.run(1000);
                near(s.c.lastParams().shiftRpm, 6000, 1e-9, "ICE_SMOOTH: near the 6500 redline");
                TestMain.Rig e = new TestMain.Rig(TestMain.iceSettings(Profile.EV_FUTURE, 70));
                e.speed = 50;
                e.pedal = 30;
                e.run(1000);
                near(e.c.lastParams().shiftRpm, 8500, 1e-9, "EV_FUTURE: near the top of its 9000 scale");
                TestMain.Rig q = new TestMain.Rig(TestMain.iceSettings(Profile.ICE_SHIFT, 70));
                q.gear = Sig.GEAR_P;
                q.run(1000);
                near(q.c.lastParams().shiftRpm, 6000, 1e-9, "ICE_SHIFT parked: no gearbox, near the redline");
            }
        });

        // ---------------- GA2 VesCore.liveLine(): one key=value line the app parses back to what the core has
        l.add(new TestMain.T("GA2_live_line_roundtrip") {
            void one(TestMain.Rig r, String what) {
                String line = r.c.liveLine();
                check(line.indexOf('\n') < 0, what + ": one line: " + line);
                LiveData d = LiveData.parse("ok\n" + line + "\n");
                check(d != null, what + ": parses: " + line);
                EngineParams p = r.c.lastParams();
                near(d.rpm, Math.round(p.rpm), 0.5, what + " rpm (" + line + ")");
                check(d.gear == p.virtualGear, what + " gear " + d.gear + " vs " + p.virtualGear);
                check(d.shifting == p.shifting, what + " shifting");
                near(d.kph, p.speedKph, 0.06, what + " kph");
                double kw = r.c.signals().get(Sig.MOTOR_KW, r.t - 10);
                check(Double.isNaN(kw) ? Double.isNaN(d.kw) : Math.abs(d.kw - kw) < 0.06, what + " kw " + d.kw + " vs " + kw);
                check(Double.isNaN(p.shiftRpm) ? Double.isNaN(d.shiftRpm) : Math.abs(d.shiftRpm - Math.round(p.shiftRpm)) < 0.5,
                        what + " shiftRpm " + d.shiftRpm + " vs " + p.shiftRpm);
                check(d.profile.equals(r.c.settings().profile.key()), what + " profile " + d.profile);
                check(d.state.equals(p.reason), what + " state " + d.state + " vs " + p.reason);
            }

            void run() {
                TestMain.Rig r = new TestMain.Rig(TestMain.iceSettings(Profile.ICE_SHIFT, 70));
                r.gear = Sig.GEAR_P;
                r.run(1000);
                one(r, "P idle");
                LiveData p = LiveData.parse("ok\n" + r.c.liveLine());
                check(p.selector == Sig.GEAR_P, "P selector " + p.selector);
                near(p.max, 8000, 0, "ICE scale 8000");
                near(p.redline, 6500, 0, "ICE redline 6500");
                r.gear = Sig.GEAR_D;
                r.pedal = 20;
                r.kw = 18;
                for (int i = 0; i < 2500; i++) {
                    r.speed = Math.min(70, i * 0.05);
                    r.step();
                }
                one(r, "D cruise");
                check(LiveData.parse("ok\n" + r.c.liveLine()).gear >= 3, "D cruise in a higher gear");
                r.gear = Sig.GEAR_R;
                r.speed = 3;
                r.pedal = 5;
                r.kw = 2;
                r.run(1000);
                one(r, "R");
                // stale: no signals for a while -> kw and selector unknown, drive mode unknown
                TestMain.Rig s = new TestMain.Rig(TestMain.iceSettings(Profile.EV_FUTURE, 70));
                s.run(500);
                s.feedSpeed = false;
                s.speed = s.pedal = s.kw = s.gear = s.ready = Double.NaN;
                s.run(3000);
                LiveData q = LiveData.parse("ok\n" + s.c.liveLine());
                check(q != null && Double.isNaN(q.kw) && q.selector < 0 && q.driveMode < 0, "stale signals come back unknown: " + s.c.liveLine());
                near(q.max, 9000, 0, "EV scale 9000");
                check(Double.isNaN(q.redline), "EV has no redline");
                check(LiveData.parse("error not allowed") == null && LiveData.parse(null) == null && LiveData.parse("ok\n") == null,
                        "errors parse to null");
            }
        });

        // ---------------- GA3 drive-mode raw value changes go to vesd's log (the ECO/NORMAL/SPORT values are unknown)
        l.add(new TestMain.T("GA3_drive_mode_logged") {
            void run() {
                TestMain.Rig r = new TestMain.Rig(TestMain.iceSettings(Profile.ICE_SHIFT, 70));
                StringBuilder all = new StringBuilder();
                double[] seq = {1, 1, 3};
                for (double v : seq) {
                    for (int i = 0; i < 30; i++) {
                        r.c.signals().set(Sig.DRIVE_MODE, v, r.t);
                        r.step();
                        all.append(r.c.takeTrace());
                    }
                }
                int n = 0;
                for (String line : all.toString().split("\n")) if (line.startsWith("drive_mode raw=")) n++;
                check(n == 2 && all.indexOf("drive_mode raw=1") >= 0 && all.indexOf("drive_mode raw=3") >= 0,
                        "two rows (1 then 3): " + all.toString().replace('\n', '|'));
            }
        });

        // ---------------- GA4 gauge maths
        l.add(new TestMain.T("GA4_gauge_math") {
            void run() {
                near(GaugeMath.maxRpm("ice_shift"), 8000, 0, "ICE scale");
                near(GaugeMath.maxRpm("ev_future"), 9000, 0, "EV scale");
                near(GaugeMath.redline("ice_smooth"), 6500, 0, "ICE redline");
                check(Double.isNaN(GaugeMath.redline("ev_future")), "EV no redline");
                near(GaugeMath.angle(0, 8000), GaugeMath.START_DEG, 1e-9, "0 rpm at the start");
                near(GaugeMath.angle(8000, 8000), GaugeMath.START_DEG + GaugeMath.SWEEP_DEG, 1e-9, "max at the end");
                near(GaugeMath.angle(12000, 8000), GaugeMath.START_DEG + GaugeMath.SWEEP_DEG, 1e-9, "clamped above max");
                near(GaugeMath.angle(-5, 8000), GaugeMath.START_DEG, 1e-9, "clamped below 0");
                near(GaugeMath.angle(Double.NaN, 8000), GaugeMath.START_DEG, 1e-9, "NaN at the start");
                check(GaugeMath.shiftLights(2000, 3000, 9) == 0, "below 80 % of the shift point: dark");
                check(GaugeMath.shiftLights(2700, 3000, 9) > 0 && GaugeMath.shiftLights(2700, 3000, 9) < 9, "between: some lit");
                check(GaugeMath.shiftLights(3000, 3000, 9) == 9 && GaugeMath.shiftLights(3500, 3000, 9) == 9, "at and above: all lit");
                check(GaugeMath.shiftLights(5000, Double.NaN, 9) == 0, "no shift point: dark");
                check(!GaugeMath.shiftBlink(2999, 3000) && GaugeMath.shiftBlink(3000, 3000) && !GaugeMath.shiftBlink(4000, Double.NaN),
                        "blink at the shift point");
                check("D4".equals(GaugeMath.gearLabel("ice_shift", Sig.GEAR_D, 4)), "ICE_SHIFT D4");
                check("D".equals(GaugeMath.gearLabel("ice_smooth", Sig.GEAR_D, 0)), "ICE_SMOOTH D");
                check("D".equals(GaugeMath.gearLabel("ev_future", Sig.GEAR_D, 3)), "EV D only");
                check("D".equals(GaugeMath.gearLabel("ice_smooth", Sig.GEAR_B, 0)) && "D3".equals(GaugeMath.gearLabel("ice_shift", Sig.GEAR_ECO, 3)),
                        "B and ECO show as D (owner's choice)");
                check("P".equals(GaugeMath.gearLabel("ice_shift", Sig.GEAR_P, 1)) && "R".equals(GaugeMath.gearLabel("ice_shift", Sig.GEAR_R, 1))
                        && "N".equals(GaugeMath.gearLabel("ice_shift", Sig.GEAR_N, 1)), "P R N without numbers");
                check("-".equals(GaugeMath.gearLabel("ice_shift", 9, 2)) && "-".equals(GaugeMath.gearLabel("ice_shift", -1, 2)), "unknown -");
                check(GaugeMath.showGearStrip("ice_shift") && !GaugeMath.showGearStrip("ice_smooth") && !GaugeMath.showGearStrip("ev_future"),
                        "the 1-6 strip only in ICE_SHIFT");
                near(GaugeMath.kwBar(-40), -1, 1e-9, "-40 kW full regen");
                near(GaugeMath.kwBar(-80), -1, 1e-9, "regen clamped");
                near(GaugeMath.kwBar(150), 1, 1e-9, "+150 kW full drive");
                near(GaugeMath.kwBar(75), 0.5, 1e-9, "75 kW half");
                near(GaugeMath.kwBar(Double.NaN), 0, 0, "unknown kW: empty");
                check(Theme.forDriveMode(-1) == Theme.NEUTRAL && Theme.forDriveMode(99) == Theme.NEUTRAL, "unknown drive mode: neutral");
                Theme.Hold h = new Theme.Hold();
                int t0 = h.update(-1, 0);
                check(t0 == Theme.NEUTRAL, "starts neutral");
                Needle n = new Needle();
                n.jump(800);
                double v = 800;
                for (int i = 0; i < 10; i++) v = n.step(4800, 0.03);
                check(Math.abs(v - 4800) <= 0.02 * 4000, "settles within 2 % in 300 ms: " + v);
                check(n.step(4800, 0.03) <= 4800, "no overshoot");
                n.jump(Double.NaN);
                check(!Double.isNaN(n.step(1000, 0.03)), "NaN start recovers");
            }
        });

        // ---------------- GA5 the renderer draws every state inside the view with finite numbers; D4 only in ICE_SHIFT
        l.add(new TestMain.T("GA5_renderer_calls") {
            void run() {
                String[][] states = {
                        {"P idle", "rpm=820 gear=1 sh=0 sel=0 kph=0 kw=0 shift=6000 dm=- profile=ice_shift state=park red=6500 max=8000"},
                        {"ECO D4", "rpm=2100 gear=4 sh=0 sel=5 kph=64 kw=12 shift=2950 dm=- profile=ice_shift state=drive red=6500 max=8000"},
                        {"SPORT near shift", "rpm=5600 gear=2 sh=0 sel=5 kph=55 kw=120 shift=5800 dm=- profile=ice_shift state=drive red=6500 max=8000"},
                        {"regen", "rpm=1500 gear=3 sh=0 sel=5 kph=40 kw=-35 shift=- dm=- profile=ice_shift state=overrun red=6500 max=8000"},
                        {"R", "rpm=900 gear=1 sh=0 sel=7 kph=3 kw=2 shift=6000 dm=- profile=ice_smooth state=reverse_quiet red=6500 max=8000"},
                        {"EV", "rpm=5200 gear=0 sh=0 sel=5 kph=84 kw=60 shift=8500 dm=- profile=ev_future state=drive red=- max=9000"}};
                float[][] sizes = {{1920, 720}, {1240, 260}};
                for (String[] st : states) {
                    LiveData d = LiveData.parse("ok\n" + st[1]);
                    check(d != null, st[0] + " parses");
                    for (float[] wh : sizes) {
                        final float w = wh[0], hgt = wh[1];
                        final List<String> texts = new ArrayList<String>();
                        final int[] calls = {0};
                        final String[] bad = {null};
                        Painter rec = new Painter() {
                            void in(String what, float... v) {
                                calls[0]++;
                                for (int i = 0; i < v.length; i++) {
                                    float lim = i % 2 == 0 ? w : hgt;
                                    if (Float.isNaN(v[i]) || Float.isInfinite(v[i]) || v[i] < -1 || v[i] > lim + 1) bad[0] = what + " " + i + "=" + v[i];
                                }
                            }

                            public void color(int argb) {}

                            public void line(float x1, float y1, float x2, float y2, float width) {
                                in("line", x1, y1, x2, y2);
                            }

                            public void arc(float cx, float cy, float r, float startDeg, float sweepDeg, float width) {
                                // only the drawn part has to be inside: sample it
                                for (int k = 0; k <= 16; k++) {
                                    double a = Math.toRadians(startDeg + sweepDeg * k / 16.0);
                                    in("arc", (float) (cx + r * Math.cos(a)), (float) (cy + r * Math.sin(a)));
                                }
                            }

                            public void poly(float[] xy) {
                                in("poly", xy);
                            }

                            public void circle(float cx, float cy, float r) {
                                in("circle", cx - r, cy - r, cx + r, cy + r);
                            }

                            public void text(String s, float x, float y, float size, boolean bold, boolean italic, int align) {
                                in("text", x, y);
                                texts.add(s);
                            }
                        };
                        GaugeRenderer.draw(rec, d, d.rpm, Theme.NEUTRAL, true, w, hgt);
                        String what = st[0] + " at " + (int) w + "x" + (int) hgt;
                        check(calls[0] > 30 && bad[0] == null, what + ": " + calls[0] + " calls, bad " + bad[0]);
                        check(texts.contains("D4") == st[0].equals("ECO D4"), what + ": D4 only in the ICE_SHIFT D4 state " + texts);
                    }
                }
            }
        });
    }
}
