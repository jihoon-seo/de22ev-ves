package ves.core;

import java.util.List;

import ves.gauge.GaugeMath;
import ves.gauge.GaugeRenderer;
import ves.gauge.LiveData;
import ves.gauge.Painter;
import ves.gauge.PanelFrame;

import static ves.core.TestMain.check;
import static ves.core.TestMain.near;

/** 0.2.13 split-screen panel page (RemoteViews): one frame of drawable levels and texts, and what changed */
final class PanelTests {
    private PanelTests() {}

    static LiveData d(String rpm, String sel, String kph, String kw, String shift, String profile, String on) {
        boolean ev = "ev_future".equals(profile);
        return LiveData.parse("ok\nrpm=" + rpm + " gear=3 sh=0 sel=" + sel + " kph=" + kph + " kw=" + kw + " shift=" + shift + " dm=- profile="
                + profile + " state=drive red=" + (ev ? "-" : "6500") + " max=" + (ev ? 9000 : 8000) + " on=" + on + "\n");
    }

    static LiveData d(String rpm, String kw, String shift) {
        return d(rpm, "5", "50", kw, shift, "ice_smooth", "1");
    }

    static void add(List<TestMain.T> l) {
        // ---------------- PF1 the needle's RotateDrawable level: 0 at 0 rpm, 10000 at the top, steps of 25
        l.add(new TestMain.T("PF1_needle_level") {
            void run() {
                check(GaugeMath.needleLevel(0, 8000) == 0 && GaugeMath.needleLevel(8000, 8000) == 10000, "ends");
                check(GaugeMath.needleLevel(12000, 8000) == 10000 && GaugeMath.needleLevel(-50, 8000) == 0, "clamped");
                check(GaugeMath.needleLevel(Double.NaN, 8000) == 0, "NaN at 0");
                check(GaugeMath.needleLevel(4000, 8000) == 5000 && GaugeMath.needleLevel(4500, 9000) == 5000, "half scale");
                check(GaugeMath.needleLevel(1013, 8000) == 1275, "1266.25 -> 1275: " + GaugeMath.needleLevel(1013, 8000));
                int prev = -1;
                for (int rpm = 0; rpm <= 8000; rpm += 7) {
                    int lv = GaugeMath.needleLevel(rpm, 8000);
                    check(lv % 25 == 0 && lv >= prev, "rpm " + rpm + " level " + lv);
                    // the drawable's rotation (140 + 260 x level / 10000) stays within half a step of the gauge angle
                    near(140 + 260.0 * lv / 10000, GaugeMath.angle(rpm, 8000), 260.0 * 25 / 10000 / 2 + 1e-9, "angle at " + rpm);
                    prev = lv;
                }
                check(PanelFrame.of(d("4000", "0", "6000"), true).needle == 5000, "frame carries the needle level");
                check(PanelFrame.of(d("4500", "5", "50", "0", "8500", "ev_future", "1"), true).needle == 5000
                        && PanelFrame.of(d("4500", "5", "50", "0", "8500", "ev_future", "1"), true).ev, "EV: 9000 scale and its dial");
            }
        });

        // ---------------- PF2 the shift lamps' level-list: 0..9 lit, at the shift point 10 (flash on) / 0 (flash off)
        l.add(new TestMain.T("PF2_lamp_level") {
            void run() {
                check(PanelFrame.of(d("2000", "0", "3000"), true).lamps == 0, "below 80 %: dark");
                int mid = PanelFrame.of(d("2700", "0", "3000"), true).lamps;
                check(mid == GaugeMath.shiftLights(2700, 3000, 9) && mid > 0 && mid < 9, "between: " + mid);
                check(PanelFrame.of(d("3000", "0", "3000"), true).lamps == 10, "flash on");
                check(PanelFrame.of(d("3200", "0", "3000"), false).lamps == 0, "flash off");
                check(PanelFrame.of(d("5000", "0", "-"), true).lamps == 0, "no shift point: dark");
                for (int rpm = 0; rpm <= 4000; rpm += 50) {
                    int a = PanelFrame.of(d(String.valueOf(rpm), "0", "3000"), true).lamps;
                    int b = PanelFrame.of(d(String.valueOf(rpm), "0", "3000"), false).lamps;
                    check(a >= 0 && a <= 10 && b >= 0 && b <= 9, "range at " + rpm + ": " + a + "/" + b);
                }
            }
        });

        // ---------------- PF3 no power bar on the page (0.2.14, owner): the power alone changes nothing
        l.add(new TestMain.T("PF3_no_power_bar") {
            void run() {
                PanelFrame a = PanelFrame.of(d("2000", "75", "6000"), true);
                for (String kw : new String[] {"-80", "-20", "0", "1", "151", "-"})
                    check(PanelFrame.of(d("2000", kw, "6000"), true).diff(a) == 0, "kW " + kw + " changes nothing");
            }
        });

        // ---------------- PF6 the ring lamps' ClipDrawables (0.2.14): the lit overlay clipped from the left shows exactly
        // the first lit lamps (they run left to right on the ring); the flash overlay all (on phase) or nothing
        l.add(new TestMain.T("PF6_lamp_clip_levels") {
            void run() {
                final float size = 480;
                final List<float[]> lamps = new java.util.ArrayList<float[]>();
                GaugeRenderer.drawLamps(new Painter() {
                    public void color(int argb) {}
                    public void line(float x1, float y1, float x2, float y2, float w) {}
                    public void arc(float cx, float cy, float r, float s, float sw, float w) {}
                    public void circle(float cx, float cy, float r) { lamps.add(new float[] {cx, cy, r}); }
                    public void poly(float[] xy) {}
                    public void text(String s, float x, float y, float sz, boolean b, boolean i, int a) {}
                }, false, size);
                check(lamps.size() == PanelFrame.LAMPS, "lamps " + lamps.size());
                check(PanelFrame.lampClip(0) == 0 && PanelFrame.lampClip(PanelFrame.LAMPS) == 10000, "ends 0 / 10000");
                for (int k = 0; k <= PanelFrame.LAMPS; k++) {
                    int lv = PanelFrame.lampClip(k);
                    float cut = size * lv / 10000f;
                    for (int i = 0; i < lamps.size(); i++) {
                        float[] m = lamps.get(i);
                        if (i < k) check(m[0] + m[2] < cut, k + " lit: lamp " + i + " shown, cut " + cut);
                        else check(m[0] - m[2] > cut, k + " lit: lamp " + i + " hidden, cut " + cut);
                    }
                }
                PanelFrame on = PanelFrame.of(d("3000", "0", "3000"), true), off = PanelFrame.of(d("3200", "0", "3000"), false);
                check(on.lampsLevel() == 0 && on.flashLevel() == 10000, "flash on: all red " + on.lampsLevel() + "/" + on.flashLevel());
                check(off.lampsLevel() == 0 && off.flashLevel() == 0, "flash off: dark");
                PanelFrame mid = PanelFrame.of(d("2700", "0", "3000"), true);
                check(mid.lampsLevel() == PanelFrame.lampClip(mid.lamps) && mid.flashLevel() == 0, "between: clip of " + mid.lamps);
            }
        });
        // ---------------- PF4 diff(prev): a bit per changed field, nothing for an identical frame, all for the first
        // ---------------- PF5 the split page shows the virtual gear like the full gauge: D3 in the virtual-shift profile
        l.add(new TestMain.T("PF5_gear_with_virtual_gear") {
            void run() {
                check("D3".equals(PanelFrame.of(d("3000", "5", "50", "40", "3500", "ice_shift", "1"), false).gear), "ice_shift D gear 3 -> D3");
                check("D3".equals(PanelFrame.of(d("3000", "1", "50", "40", "3500", "ice_shift", "1"), false).gear), "B as D -> D3");
                check("R".equals(PanelFrame.of(d("1000", "7", "5", "5", "3500", "ice_shift", "1"), false).gear), "R");
                check("D".equals(PanelFrame.of(d("3000", "5", "50", "40", "3500", "ice_smooth", "1"), false).gear), "no virtual gears: D");
            }
        });
        l.add(new TestMain.T("PF4_diff_mask") {
            void run() {
                PanelFrame a = PanelFrame.of(d("2000", "5", "50", "20", "6000", "ice_smooth", "1"), true);
                check(a.diff(PanelFrame.of(d("2000", "5", "50", "20", "6000", "ice_smooth", "1"), true)) == 0, "same: 0");
                check(a.diff(null) == PanelFrame.ALL, "first frame: all");
                check(PanelFrame.of(d("2400", "5", "50", "20", "6000", "ice_smooth", "1"), true).diff(a) == PanelFrame.F_NEEDLE, "rpm");
                check(PanelFrame.of(d("2000", "0", "50", "20", "6000", "ice_smooth", "1"), true).diff(a) == PanelFrame.F_GEAR, "selector");
                check(PanelFrame.of(d("2000", "5", "51", "20", "6000", "ice_smooth", "1"), true).diff(a) == PanelFrame.F_SPEED, "kph");
                check(PanelFrame.of(d("2000", "5", "50", "20", "6000", "ice_smooth", "0"), true).diff(a) == PanelFrame.F_ON, "on");
                check(PanelFrame.of(d("5000", "5", "50", "20", "4500", "ice_smooth", "1"), true).diff(a) == (PanelFrame.F_NEEDLE | PanelFrame.F_LAMPS),
                        "needle + lamps");
                int ev = PanelFrame.of(d("2000", "5", "50", "20", "6000", "ev_future", "1"), true).diff(a);
                check((ev & PanelFrame.F_DIAL) != 0 && (ev & PanelFrame.F_NEEDLE) != 0, "EV: dial and needle " + ev);
                int[] bits = {PanelFrame.F_DIAL, PanelFrame.F_NEEDLE, PanelFrame.F_LAMPS, PanelFrame.F_GEAR, PanelFrame.F_SPEED,
                        PanelFrame.F_ON};
                int all = 0;
                for (int b : bits) {
                    check(b != 0 && (all & b) == 0, "distinct bit " + b);
                    all |= b;
                }
                check(all == PanelFrame.ALL, "ALL is every field");
                check("D".equals(a.gear) && "50".equals(a.speed) && a.on == 1, "texts " + a.gear + " " + a.speed + " " + a.on);
                check("-".equals(PanelFrame.of(d("2000", "5", "-", "20", "6000", "ice_smooth", "-"), true).speed), "unknown speed -");
            }
        });
    }
}
