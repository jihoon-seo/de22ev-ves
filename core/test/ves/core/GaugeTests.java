package ves.core;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import ves.gauge.Clock;
import ves.gauge.GaugeMath;
import ves.gauge.GaugeRenderer;
import ves.gauge.LiveData;
import ves.gauge.NavText;
import ves.gauge.Needle;
import ves.gauge.Painter;
import ves.gauge.SplitLayout;
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
                check(d.on == (r.c.settings().enabled ? 1 : 0), what + " on " + d.on + " (" + line + ")");
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

        // ---------------- GA3 drive-mode raw changes are NOT logged any more (0.2.13, owner D6): CF_Vcu_EvMod is the
        // power flow (1/2/6 flipping every few seconds), not ECO/NORMAL/SPORT; the launch trace still works
        l.add(new TestMain.T("GA3_drive_mode_not_logged") {
            void run() {
                TestMain.Rig r = new TestMain.Rig(TestMain.iceSettings(Profile.ICE_SHIFT, 70));
                r.gear = Sig.GEAR_D;
                StringBuilder all = new StringBuilder();
                double[] seq = {1, 2, 6, 2, 1};
                for (double v : seq) {
                    for (int i = 0; i < 30; i++) {
                        r.c.signals().set(Sig.DRIVE_MODE, v, r.t);
                        r.step();
                        all.append(r.c.takeTrace());
                    }
                }
                check(all.indexOf("drive_mode") < 0, "no drive_mode rows: " + all.toString().replace('\n', '|'));
                r.pedal = 40;
                r.kw = 40;
                for (int i = 0; i < 100; i++) {
                    r.c.signals().set(Sig.DRIVE_MODE, i % 2 == 0 ? 2 : 6, r.t);
                    r.step();
                    all.append(r.c.takeTrace());
                }
                check(all.indexOf("launch +") >= 0 && all.indexOf("drive_mode") < 0, "launch rows only: " + all.length());
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

        // ---------------- GA6 LIVE carries on=1|0 (settings.enabled); LiveData: missing keys (old vesd) are unknown
        l.add(new TestMain.T("GA6_live_on_and_nav") {
            void run() {
                Settings off = TestMain.iceSettings(Profile.ICE_SMOOTH, 70);
                off.enabled = false;
                TestMain.Rig r = new TestMain.Rig(off);
                r.run(300);
                String line = r.c.liveLine();
                check(line.endsWith(" max=8000 on=0"), "on=0 appended after max: " + line);
                check(LiveData.parse("ok\n" + line).on == 0, "parses on=0");
                TestMain.Rig q = new TestMain.Rig(TestMain.iceSettings(Profile.ICE_SMOOTH, 70));
                q.run(300);
                check(q.c.liveLine().endsWith(" on=1") && LiveData.parse("ok\n" + q.c.liveLine()).on == 1, "on=1: " + q.c.liveLine());
                String old = "rpm=820 gear=1 sh=0 sel=0 kph=0 kw=0 shift=6000 dm=- profile=ice_shift state=park red=6500 max=8000";
                LiveData o = LiveData.parse("ok\n" + old);
                check(o != null && o.on == -1 && o.navAge == -1 && "-".equals(o.cam) && o.camLimit == -1 && o.camDist == -1 && o.zone == -1
                        && o.section == -1 && o.sectionLimit == -1 && o.sectionAvg == -1 && "-".equals(o.sectionSrc) && o.sectionDist == -1,
                        "0.2.12 line: all new fields unknown");
                LiveData n = LiveData.parse("ok\n" + old + " on=1 nav=3 cam=speed lim=60 dist=350 zone=0 sec=1 slim=80 savg=87 ssrc=nav sdist=1500\n");
                check(n != null && n.on == 1 && n.navAge == 3 && "speed".equals(n.cam) && n.camLimit == 60 && n.camDist == 350 && n.zone == 0
                        && n.section == 1 && n.sectionLimit == 80 && n.sectionAvg == 87 && "nav".equals(n.sectionSrc) && n.sectionDist == 1500,
                        "0.2.13 line parses every field");
                near(n.rpm, 820, 0, "old fields still parse");
                LiveData u = LiveData.parse("ok\n" + old + " on=0 nav=- cam=- lim=- dist=- zone=- sec=0 slim=- savg=- ssrc=- sdist=-");
                check(u.on == 0 && u.navAge == -1 && "-".equals(u.cam) && u.camLimit == -1 && u.camDist == -1 && u.zone == -1 && u.section == 0
                        && u.sectionLimit == -1 && u.sectionAvg == -1 && "-".equals(u.sectionSrc) && u.sectionDist == -1, "dashes are unknown");
            }
        });

        // ---------------- GA7 the clock (0.2.14, owner): M월 d일 (요일) HH:mm, weekday from a fixed table (not the locale)
        l.add(new TestMain.T("GA7_clock_format") {
            void run() {
                Locale was = Locale.getDefault();
                try {
                    Locale.setDefault(Locale.US);
                    Calendar c = new GregorianCalendar(2026, Calendar.NOVEMBER, 23, 14, 5, 9);
                    check("11월 23일 (월) 14:05".equals(Clock.format(c)), "Monday: " + Clock.format(c));
                    c = new GregorianCalendar(2026, Calendar.NOVEMBER, 23, 23, 59, 59);
                    c.add(Calendar.SECOND, 1);
                    check("11월 24일 (화) 00:00".equals(Clock.format(c)), "rolls to Tuesday: " + Clock.format(c));
                    c = new GregorianCalendar(2027, Calendar.JANUARY, 4, 7, 8, 9);
                    check("1월 4일 (월) 07:08".equals(Clock.format(c)), "month and day unpadded, time padded: " + Clock.format(c));
                    String[] week = {"일", "월", "화", "수", "목", "금", "토"};
                    for (int i = 0; i < 7; i++) {
                        c = new GregorianCalendar(2026, Calendar.NOVEMBER, 22 + i, 12, 0, 0); // 22 = Sunday
                        check(Clock.format(c).indexOf("(" + week[i] + ")") > 0, "weekday " + i + ": " + Clock.format(c));
                    }
                    TimeZone seoul = TimeZone.getTimeZone("Asia/Seoul");
                    Calendar z = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
                    z.clear();
                    z.set(2026, Calendar.NOVEMBER, 23, 5, 5, 9);
                    check("11월 23일 (월) 14:05".equals(Clock.format(z.getTimeInMillis(), seoul)), "epoch ms in KST: "
                            + Clock.format(z.getTimeInMillis(), seoul));
                    check("M월 d일 (E) HH:mm".equals(Clock.PATTERN), "TextClock pattern " + Clock.PATTERN);
                } finally {
                    Locale.setDefault(was);
                }
            }
        });

        // ---------------- GA8 split screen: the stock apps narrow their content to 1281 px while split_screen_status == 1
        l.add(new TestMain.T("GA8_split_width") {
            void run() {
                check(SplitLayout.contentWidth(1, 1920) == 1281, "split on: 1281");
                check(SplitLayout.contentWidth(0, 1920) == 1920, "split off: full");
                check(SplitLayout.contentWidth(5, 1920) == 1920 && SplitLayout.contentWidth(-1, 1920) == 1920, "other values: full");
                check(SplitLayout.contentWidth(1, 1000) == 1000, "never wider than the screen");
                check(SplitLayout.SPLIT_WIDTH == 1281, "SPLIT_WIDTH " + SplitLayout.SPLIT_WIDTH);
            }
        });

        // ---------------- GA10 the full gauge's extras (0.2.13): clock top-left, the VES on/off button top-right (its
        // rect is the touch target), the camera panel in the right-hand zone; all inside the view at 1920x720 and at the
        // split width 1281x720, clear of the dial, the shift lamps and the readouts; nothing on the compact strip
        l.add(new TestMain.T("GA10_extras_layout") {
            void run() {
                String base = "rpm=5900 gear=2 sh=0 sel=5 kph=72 kw=125 shift=5800 dm=- profile=ice_shift state=drive red=6500 max=8000";
                String[][] cams = {
                        {"none", ""},
                        {"speed over", " nav=1 cam=speed lim=60 dist=350 zone=0 sec=0 slim=- savg=- ssrc=- sdist=-"},
                        {"mobile under", " nav=1 cam=mobile lim=80 dist=1200 zone=0 sec=0 slim=- savg=- ssrc=- sdist=-"},
                        {"section over", " nav=1 cam=section lim=80 dist=- zone=0 sec=1 slim=80 savg=87 ssrc=nav sdist=1500"},
                        {"zone", " nav=1 cam=zone lim=- dist=350 zone=2 sec=0 slim=- savg=- ssrc=- sdist=-"}};
                String[] ons = {" on=1", " on=0", ""};
                String clock = "11월 23일 (월) 14:05";
                float[][] sizes = {{1920, 720}, {1281, 720}};
                for (float[] wh : sizes) {
                    float w = wh[0], h = wh[1];
                    float[] b = GaugeRenderer.buttonRect(w, h);
                    String at = " at " + (int) w + "x" + (int) h;
                    check(b.length == 4 && b[0] >= 0 && b[1] >= 0 && b[2] <= w && b[3] <= h && b[2] - b[0] >= 0.15f * h && b[3] - b[1] >= 0.06f * h,
                            "button inside the view and big enough" + at + ": " + rect(b));
                    // the dial circle (the static layer's first arc), the lamps (circles) and the readouts (text anchors)
                    Rec st = new Rec(w, h);
                    GaugeRenderer.drawStatic(st, "ice_shift", Theme.NEUTRAL, w, h);
                    float[] dial = st.arcs.get(0);
                    for (String[] cam : cams) {
                        for (String on : ons) {
                            LiveData d = LiveData.parse("ok\n" + base + on + cam[1]);
                            String what = cam[0] + on + at;
                            Rec dy = new Rec(w, h);
                            GaugeRenderer.drawDynamic(dy, d, d.rpm, Theme.NEUTRAL, true, w, h);
                            check(dy.circles.size() == 9, what + ": 9 lamps " + dy.circles.size());
                            for (float[] c : dy.circles)
                                check(!overlaps(b, c[0] - c[2], c[1] - c[2], c[0] + c[2], c[1] + c[2]), what + ": button over a lamp " + rect(b));
                            for (float[] t : dy.anchors) check(!inside(b, t[0], t[1]), what + ": button over a readout at " + t[0] + "," + t[1]);
                            Rec ex = new Rec(w, h);
                            GaugeRenderer.drawExtras(ex, d, clock, w, h);
                            check(ex.calls > 3 && ex.bad == null, what + ": " + ex.calls + " calls, bad " + ex.bad);
                            for (float[] pt : ex.points)
                                check(Math.hypot(pt[0] - dial[0], pt[1] - dial[1]) > dial[2] * 1.02, what + ": extras inside the dial at " + pt[0] + "," + pt[1]);
                            for (float[] c : dy.circles)
                                for (float[] pt : ex.points)
                                    check(Math.hypot(pt[0] - c[0], pt[1] - c[1]) > c[2] * 2, what + ": extras on a lamp at " + pt[0] + "," + pt[1]);
                            int ci = ex.texts.indexOf(clock);
                            check(ci >= 0 && ex.anchors.get(ci)[0] < b[0] && ex.anchors.get(ci)[1] < 0.12f * h, what + ": clock top-left " + ex.texts);
                            String label = on.equals(" on=1") ? "켜짐" : on.equals(" on=0") ? "꺼짐" : "--";
                            int li = -1;
                            for (int i = 0; i < ex.texts.size(); i++) if (ex.texts.get(i).indexOf(label) >= 0 && i != ci) li = i;
                            check(li >= 0 && inside(b, ex.anchors.get(li)[0], ex.anchors.get(li)[1]), what + ": button label " + label + " in its rect " + ex.texts);
                            String line = NavText.line(d);
                            check(line.length() > 0 == !cam[0].equals("none"), what + ": NavText " + line);
                            if (line.length() == 0) {
                                check(ex.texts.size() == 2, what + ": no camera, clock and button only " + ex.texts);
                                check(dy.texts.contains("VIRTUAL SHIFT"), what + ": the profile label stays without a camera " + dy.texts);
                            } else {
                                // 0.2.13 owner: the camera panel is the most important, so it takes the left-hand zone (above the
                                // rpm readout, which moves below it); the profile label keeps the right-hand zone
                                int ni = ex.texts.indexOf(NavText.signDetail(d)), si = ex.texts.indexOf("현재 72"), ri = dy.texts.indexOf("5,900");
                                float leftEdge = dial[0] - dial[2];
                                check(ni >= 0 && ex.anchors.get(ni)[0] < leftEdge, what + ": camera line in the left-hand zone " + ex.texts);
                                check(si >= 0 && ex.anchors.get(si)[0] < leftEdge, what + ": current speed in the left-hand zone " + ex.texts);
                                check(ri >= 0 && dy.anchors.get(ri)[0] < leftEdge && dy.anchors.get(ri)[1] > ex.anchors.get(si)[1] + 0.05f * h,
                                        what + ": rpm below the camera panel " + dy.texts);
                                boolean over = NavText.over(d);
                                check(over == cam[0].endsWith("over"), what + ": over " + over);
                                int red = 0xFFFF3B30;
                                check((ex.textColors.get(ni) == red) == over, what + ": camera line red exactly when over");
                                // the current speed is red only above the limit itself; a section's average gets its own line
                                // between the camera line and the current speed, as big and red above the section limit
                                check((ex.textColors.get(si) == red) == (d.kph > 80 || (cam[0].startsWith("speed") && d.kph > 60)),
                                        what + ": current speed red exactly above the limit");
                                int ai = ex.texts.indexOf("평균 87");
                                if (cam[0].startsWith("section")) {
                                    check(ai >= 0 && ex.anchors.get(ai)[0] < leftEdge, what + ": section average on its own line " + ex.texts);
                                    check(ai >= 0 && ex.anchors.get(ni)[1] < ex.anchors.get(ai)[1] && ex.anchors.get(ai)[1] < ex.anchors.get(si)[1],
                                            what + ": average between the camera line and the current speed");
                                    check(ai >= 0 && ex.textColors.get(ai) == red, what + ": average 87 above 80 in red");
                                    check("구간단속".equals(ex.texts.get(ni)), what + ": camera line is just 구간단속 " + ex.texts);
                                } else {
                                    check(ai < 0, what + ": no average line " + ex.texts);
                                }
                                check(dy.texts.contains("VIRTUAL SHIFT"), what + ": the profile label stays on the right " + dy.texts);
                            }
                        }
                    }
                }
                for (float[] wh : new float[][] {{1856, 300}, {1217, 300}}) {
                    Rec ex = new Rec(wh[0], wh[1]);
                    GaugeRenderer.drawExtras(ex, LiveData.parse("ok\n" + base + " on=1" + cams[1][1]), clock, wh[0], wh[1]);
                    check(ex.calls == 0, "compact strip " + (int) wh[0] + "x" + (int) wh[1] + ": no extras, " + ex.calls + " calls");
                }
            }

            boolean inside(float[] b, float x, float y) {
                return x >= b[0] && x <= b[2] && y >= b[1] && y <= b[3];
            }

            boolean overlaps(float[] b, float x0, float y0, float x1, float y1) {
                return x0 < b[2] && x1 > b[0] && y0 < b[3] && y1 > b[1];
            }
        });

        // ---------------- GA11 the panel page's art: the dial in a square (ticks, numerals 0-8 / 0-9, redline only on
        // the ICE scale) and the needle alone pointing at the START angle (0 rpm) for a <rotate> drawable
        l.add(new TestMain.T("GA11_dial_and_needle_art") {
            void run() {
                float size = 480;
                for (String prof : new String[] {"ice_shift", "ev_future"}) {
                    Rec r = new Rec(size, size);
                    GaugeRenderer.drawDial(r, prof, size);
                    boolean ev = prof.equals("ev_future");
                    check(r.calls > 40 && r.bad == null, prof + " dial inside the square: " + r.calls + " calls, bad " + r.bad);
                    for (int k = 0; k <= 9; k++)
                        check(r.texts.contains(String.valueOf(k)) == (k <= 8 || ev), prof + ": numeral " + k + " " + r.texts);
                    check(r.colors.contains(0xFFFF3B30) == !ev, prof + ": redline only on the ICE dial");
                    check(r.arcs.size() > 0, prof + ": the rail arc");
                    for (float[] a : r.arcs) check(Math.abs(a[0] - size / 2) < 0.01 && Math.abs(a[1] - size / 2) < 0.01, prof + ": arcs centred");
                }
                Rec n = new Rec(size, size);
                GaugeRenderer.drawNeedle(n, size);
                check(n.calls > 0 && n.bad == null && n.polys.size() > 0, "needle inside the square: bad " + n.bad);
                double far = 0, farDeg = 0;
                for (float[] xy : n.polys)
                    for (int i = 0; i + 1 < xy.length; i += 2) {
                        double dx = xy[i] - size / 2, dy = xy[i + 1] - size / 2, dist = Math.hypot(dx, dy);
                        double deg = (Math.toDegrees(Math.atan2(dy, dx)) + 360) % 360;
                        check(Math.abs(deg - GaugeMath.START_DEG) < 12, "needle vertex off the START ray: " + deg);
                        if (dist > far) {
                            far = dist;
                            farDeg = deg;
                        }
                    }
                near(farDeg, GaugeMath.START_DEG, 0.01, "the tip at the START angle (level 0)");
                check(far > size * 0.40 && far < size * 0.5, "the tip near the scale: " + far);
                check(n.texts.isEmpty() && n.circles.isEmpty(), "the needle only");
            }
        });
    }

    static String rect(float[] b) {
        return b.length == 4 ? "[" + b[0] + "," + b[1] + "," + b[2] + "," + b[3] + "]" : "len " + b.length;
    }

    /** records every Painter call; bad: the first coordinate outside 0..w x 0..h (1 px slack) or not finite */
    static final class Rec implements Painter {
        final float w, h;
        int calls, color;
        String bad;
        final List<String> texts = new ArrayList<String>();
        /** every colour set; the colour each text was drawn in */
        final List<Integer> colors = new ArrayList<Integer>(), textColors = new ArrayList<Integer>();
        /** text anchors (x, baseline y); every point checked; arcs cx, cy, r; circles cx, cy, r */
        final List<float[]> anchors = new ArrayList<float[]>(), points = new ArrayList<float[]>(), arcs = new ArrayList<float[]>(),
                circles = new ArrayList<float[]>(), polys = new ArrayList<float[]>();

        Rec(float w, float h) {
            this.w = w;
            this.h = h;
        }

        void in(String what, float... v) {
            calls++;
            for (int i = 0; i < v.length; i++) {
                float lim = i % 2 == 0 ? w : h;
                if ((Float.isNaN(v[i]) || Float.isInfinite(v[i]) || v[i] < -1 || v[i] > lim + 1) && bad == null) bad = what + " " + i + "=" + v[i];
            }
            for (int i = 0; i + 1 < v.length; i += 2) points.add(new float[] {v[i], v[i + 1]});
        }

        public void color(int argb) {
            color = argb;
            colors.add(argb);
        }

        public void line(float x1, float y1, float x2, float y2, float width) {
            in("line", x1, y1, x2, y2);
        }

        public void arc(float cx, float cy, float r, float startDeg, float sweepDeg, float width) {
            arcs.add(new float[] {cx, cy, r});
            for (int k = 0; k <= 16; k++) {
                double a = Math.toRadians(startDeg + sweepDeg * k / 16.0);
                in("arc", (float) (cx + r * Math.cos(a)), (float) (cy + r * Math.sin(a)));
            }
        }

        public void poly(float[] xy) {
            polys.add(xy.clone());
            in("poly", xy);
        }

        public void circle(float cx, float cy, float r) {
            circles.add(new float[] {cx, cy, r});
            in("circle", cx - r, cy - r, cx + r, cy + r);
        }

        public void text(String s, float x, float y, float size, boolean bold, boolean italic, int align) {
            in("text", x, y);
            texts.add(s);
            textColors.add(color);
            anchors.add(new float[] {x, y});
        }
    }
}
