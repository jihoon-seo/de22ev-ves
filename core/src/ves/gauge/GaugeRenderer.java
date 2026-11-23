package ves.gauge;

/**
 * Draws the RPM + gear gauge through a Painter. Two layouts by aspect: full (the gauge screen, 1920x720) and compact
 * (a strip on the main screen, wider than 3.2:1). The static layer (rail, scale, numerals, redline, bar track) depends
 * only on the size, the profile's scale and the theme, so the app can draw it once into a bitmap; the dynamic layer is
 * redrawn per frame.
 *
 * Design: a 260 degree scale from 8 to 4 o'clock with thin blade ticks; a theme-coloured ribbon fills behind the ticks
 * up to the needle and the ticks it passes light up; a hatched redline; the needle is an outer blade only, so the
 * centre holds the gear (D4) and the speed; nine round shift lamps ride above the scale; a split bar shows regen
 * (left, green) and power (right, theme colour).
 *
 * 0.2.13: drawExtras adds the full gauge's clock, VES on/off button (buttonRect) and camera panel; drawDial,
 * drawNeedle, drawLamps and drawBar draw the split screen's panel page art (ves.tools.PanelArt, PNGs at build time).
 */
public final class GaugeRenderer {
    static final int WHITE = 0xFFF2F6FA, DIM = 0x59FFFFFF, FAINT = 0x24FFFFFF, RED = 0xFFFF3B30, RED_ZONE = 0x66FF3B30,
            GREEN = 0xFF46D17A, AMBER = 0xFFFFB020, LAMP_OFF = 0x2EFFFFFF;
    /** text on light fills (the accent button, the limit sign); the panel dial's disc */
    static final int INK = 0xFF0B0E12, DIAL_BG = 0xFF0B0E12;
    /** the on/off pill while off / unknown: opaque (its circles and rect overlap, a translucent fill would band) */
    static final int BUTTON_OFF = 0xFF3A3E44, BUTTON_UNKNOWN = 0xFF1C2024;
    static final int LAMPS = 9;
    /** the panel dial's scale radius as a share of its square (the disc reaches 1.08 x this: inside the square) */
    static final float DIAL_R = 0.44f;

    private GaugeRenderer() {}

    public static void draw(Painter p, LiveData d, double needleRpm, int theme, boolean blinkOn, float w, float h) {
        drawStatic(p, d.profile, theme, w, h);
        drawDynamic(p, d, needleRpm, theme, blinkOn, w, h);
    }

    /** the layout: centre and radius of the scale, compact or full */
    static final class Geo {
        final boolean compact;
        final float w, h, cx, cy, r;

        Geo(float w, float h) {
            this.w = w;
            this.h = h;
            compact = w > 3.2f * h;
            if (compact) {
                r = h * 0.42f;
                cx = r * 1.22f + h * 0.04f;
                cy = h * 0.55f;
            } else {
                r = Math.min(h * 0.44f, w * 0.24f);
                cx = w / 2;
                cy = h * 0.54f;
            }
        }

        /** the panel page's art: the scale alone, centred in a size x size square */
        Geo(float size) {
            w = h = size;
            compact = false;
            cx = cy = size / 2;
            r = size * DIAL_R;
        }

        float x(double deg, double rad) {
            return (float) (cx + rad * Math.cos(Math.toRadians(deg)));
        }

        float y(double deg, double rad) {
            return (float) (cy + rad * Math.sin(Math.toRadians(deg)));
        }
    }

    public static void drawStatic(Painter p, String profile, int theme, float w, float h) {
        Geo g = new Geo(w, h);
        float r = g.r;
        scale(p, g, profile);
        p.color(DIM);
        if (!g.compact) p.text("rpm x1000", g.cx, g.cy + r * 0.74f, r * 0.065f, false, false, 0);
        float[] bar = bar(g);
        p.color(FAINT);
        p.line(bar[0], bar[2], bar[1], bar[2], bar[3]);
        p.color(DIM);
        float mid = (bar[0] + bar[1]) / 2;
        p.line(mid, bar[2] - bar[3] * 1.4f, mid, bar[2] + bar[3] * 1.4f, 2);
        p.text("REGEN", bar[0] - bar[3] * 1.5f, bar[2] + bar[4] * 0.36f, bar[4], false, false, 1);
    }

    /** the scale: rail, hatched redline (ICE), ticks, numerals */
    static void scale(Painter p, Geo g, String profile) {
        double max = GaugeMath.maxRpm(profile), red = GaugeMath.redline(profile);
        float r = g.r;
        p.color(FAINT);
        p.arc(g.cx, g.cy, r, (float) GaugeMath.START_DEG, (float) GaugeMath.SWEEP_DEG, Math.max(2, r * 0.012f));
        if (red > 0) {
            float a0 = (float) GaugeMath.angle(red, max), a1 = (float) (GaugeMath.START_DEG + GaugeMath.SWEEP_DEG);
            p.color(RED_ZONE);
            p.arc(g.cx, g.cy, r * 0.93f, a0, a1 - a0, r * 0.06f);
            p.color(RED);
            for (float a = a0 + 1.5f; a < a1 - 0.5f; a += 3f) // hatch: short slanted strokes
                p.line(g.x(a, r * 0.90), g.y(a, r * 0.90), g.x(a + 1.6, r * 0.965), g.y(a + 1.6, r * 0.965), Math.max(1.5f, r * 0.008f));
        }
        for (int rpm = 0; rpm <= max; rpm += 250) tick(p, g, rpm, max, red, false, 0);
        int top = (int) (max / 1000);
        for (int k = 0; k <= top; k++) {
            double a = GaugeMath.angle(k * 1000, max);
            p.color(red > 0 && k * 1000 >= red ? RED : WHITE);
            float s = r * (g.compact ? 0.17f : 0.14f), rr = r * (g.compact ? 0.66f : 0.70f);
            p.text(String.valueOf(k), g.x(a, rr), g.y(a, rr) + s * 0.36f, s, true, true, 0);
        }
    }

    /** one tick; lit: in the theme colour (the needle passed it) */
    static void tick(Painter p, Geo g, int rpm, double max, double red, boolean lit, int accent) {
        boolean major = rpm % 1000 == 0, half = rpm % 500 == 0;
        if (g.compact && !half) return;
        double a = GaugeMath.angle(rpm, max);
        float r = g.r, r0 = r * (major ? 0.81f : half ? 0.85f : 0.87f), r1 = r * 0.965f;
        boolean inRed = red > 0 && rpm >= red;
        p.color(lit ? (inRed ? RED : accent) : inRed ? RED : major ? WHITE : DIM);
        p.line(g.x(a, r0), g.y(a, r0), g.x(a, r1), g.y(a, r1), major ? Math.max(2, r * 0.016f) : Math.max(1.2f, r * 0.007f));
    }

    /** the power bar: x0, x1, y, thickness, label size */
    static float[] bar(Geo g) {
        if (g.compact) {
            float x0 = g.cx + g.r * 1.3f + g.h * 1.25f, x1 = g.w - g.h * 0.55f;
            return new float[] {x0, Math.max(x0 + 40, x1), g.h * 0.80f, Math.max(3, g.h * 0.03f), g.h * 0.075f};
        }
        float half = g.w * 0.17f;
        return new float[] {g.cx - half, g.cx + half, g.h * 0.935f, Math.max(4, g.h * 0.012f), g.h * 0.03f};
    }

    public static void drawDynamic(Painter p, LiveData d, double needleRpm, int theme, boolean blinkOn, float w, float h) {
        Geo g = new Geo(w, h);
        double max = GaugeMath.maxRpm(d.profile), red = GaugeMath.redline(d.profile);
        int accent = Theme.accent(theme);
        float r = g.r;
        double rpm = Double.isNaN(needleRpm) ? 0 : needleRpm, a = GaugeMath.angle(rpm, max);
        float a0 = (float) GaugeMath.START_DEG;
        // ribbon up to the needle, then the ticks it passed light up
        if (a - a0 > 0.3) {
            p.color(0x8C000000 | (accent & 0xFFFFFF));
            p.arc(g.cx, g.cy, r * 0.93f, a0, (float) (a - a0), r * 0.06f);
        }
        for (int k = 0; k <= rpm && k <= max; k += 250) tick(p, g, k, max, red, true, accent);
        needle(p, g, a, accent);
        // centre: gear and speed
        String gear = GaugeMath.gearLabel(d.profile, d.selector, d.gear);
        p.color(d.shifting ? accent : WHITE);
        p.text(gear, g.cx, g.cy + r * (g.compact ? 0.17f : 0.12f), r * (g.compact ? 0.52f : 0.44f), true, true, 0);
        String kph = Double.isNaN(d.kph) ? "--" : String.valueOf(Math.round(d.kph));
        if (g.compact) {
            float xs = g.cx + g.r * 1.3f + g.h * 0.95f;
            p.color(WHITE);
            p.text(kph, xs, g.cy + r * 0.32f, g.h * 0.36f, true, false, 1);
            p.color(DIM);
            p.text("km/h", xs + g.h * 0.04f, g.cy + r * 0.32f, g.h * 0.09f, false, false, -1);
            p.color(WHITE);
            p.text(rpmText(d.rpm), xs, g.cy - r * 0.55f, g.h * 0.12f, true, false, 1);
            p.color(DIM);
            p.text("rpm", xs + g.h * 0.04f, g.cy - r * 0.55f, g.h * 0.07f, false, false, -1);
        } else {
            p.color(WHITE);
            p.text(kph, g.cx, g.cy + r * 0.45f, r * 0.21f, true, false, 0);
            p.color(DIM);
            p.text("km/h", g.cx, g.cy + r * 0.57f, r * 0.065f, false, false, 0);
            // side readouts: the rpm in the lower half of the left-hand zone, under drawExtras' camera panel (the
            // owner's priority, 0.2.13); it stays there without a camera so the readout never jumps
            float lx = (g.cx - r * 1.12f) / 2;
            p.color(WHITE);
            p.text(rpmText(d.rpm), lx, g.cy + RPM_DY * r, r * 0.20f, true, false, 0);
            p.color(DIM);
            p.text("RPM", lx, g.cy + (RPM_DY + 0.14f) * r, r * 0.065f, false, false, 0);
            float rx = g.w - lx;
            String mode = Theme.name(theme);
            if (mode.length() > 0) { // unknown drive mode: just the profile
                p.color(accent);
                p.text(mode, rx, g.cy, r * 0.17f, true, true, 0);
            }
            p.color(DIM);
            p.text(profileName(d.profile), rx, g.cy + r * 0.14f, r * 0.065f, false, false, 0);
        }
        // gear strip
        if (GaugeMath.showGearStrip(d.profile)) {
            float sy, sx, step, big, small;
            if (g.compact) {
                sy = g.h * 0.52f;
                step = g.h * 0.16f;
                sx = bar(g)[0] + step * 0.5f;
                big = g.h * 0.15f;
                small = g.h * 0.10f;
            } else {
                sy = g.cy - r * 0.30f;
                step = r * 0.12f;
                sx = g.cx - step * 2.5f;
                big = r * 0.11f;
                small = r * 0.075f;
            }
            boolean drive = GaugeMath.gearLabel(d.profile, d.selector, d.gear).startsWith("D");
            for (int k = 1; k <= 6; k++) {
                boolean on = drive && k == d.gear; // P, R, N: the strip stays dim
                p.color(on ? accent : DIM);
                p.text(String.valueOf(k), sx + (k - 1) * step, sy, on ? big : small, on, true, 0);
            }
        }
        // shift lamps
        int lit = GaugeMath.shiftLights(d.rpm, d.shiftRpm, LAMPS);
        boolean flash = GaugeMath.shiftBlink(d.rpm, d.shiftRpm);
        for (int i = 0; i < LAMPS; i++) {
            boolean on = flash ? blinkOn : i < lit;
            p.color(on ? lampColor(i, flash) : LAMP_OFF);
            float lx, ly, lr;
            if (g.compact) { // a cluster at the start of the bar, not spread over its whole width
                float[] b = bar(g);
                float span = Math.min(b[1] - b[0], g.h * 2.6f);
                lx = b[0] + span * (i + 0.5f) / LAMPS;
                ly = g.h * 0.20f;
                lr = g.h * 0.045f;
            } else {
                double la = 270 - 36 + 72.0 * i / (LAMPS - 1);
                lx = g.x(la, r * 1.11);
                ly = g.y(la, r * 1.11);
                lr = r * 0.034f;
            }
            p.circle(lx, ly, lr);
        }
        // power bar
        float[] b = bar(g);
        float mid = (b[0] + b[1]) / 2, half = (b[1] - b[0]) / 2;
        double f = GaugeMath.kwBar(d.kw);
        if (f != 0) {
            p.color(f < 0 ? GREEN : accent);
            p.line(mid, b[2], (float) (mid + f * half), b[2], b[3] * 2.2f);
        }
        p.color(WHITE);
        String kw = Double.isNaN(d.kw) ? "-- kW" : Math.round(d.kw) + " kW";
        p.text(kw, b[1] + b[3] * 1.5f, b[2] + b[4] * 0.36f, b[4], true, false, -1);
    }

    /** the blade needle at angle a (degrees): its outer part only, so the centre stays free for the gear */
    static void needle(Painter p, Geo g, double a, int accent) {
        float r = g.r;
        double na = Math.toRadians(a), nx = Math.cos(na), ny = Math.sin(na), bw = r * 0.04;
        float ri = r * (g.compact ? 0.62f : 0.56f), ro = r * 0.99f;
        p.color(accent);
        p.poly(new float[] {(float) (g.cx + ro * nx), (float) (g.cy + ro * ny),
                (float) (g.cx + ri * nx - bw * ny), (float) (g.cy + ri * ny + bw * nx),
                (float) (g.cx + ri * nx + bw * ny), (float) (g.cy + ri * ny - bw * nx)});
        p.color(WHITE);
        p.line(g.x(a, r * 0.80), g.y(a, r * 0.80), g.x(a, ro), g.y(a, ro), Math.max(1.5f, r * 0.01f));
    }

    /** lamp i of LAMPS lit: three green, three amber, three red; all red while flashing at the shift point */
    static int lampColor(int i, boolean flash) {
        return flash ? RED : i < 3 ? GREEN : i < 6 ? AMBER : RED;
    }

    // ------------------------------------------------------------------ the full gauge's extras (0.2.13)

    /**
     * The full gauge's (GaugeActivity, 1920x720 or the split 1281x720) extras over drawStatic + drawDynamic: the clock
     * top-left (clockText from Clock.format, passed in so the output is deterministic), the VES on/off button
     * top-right (at buttonRect: the app toggles on a tap inside it) and, while NavText.line(d) is not "", the camera
     * panel in the left-hand zone above the rpm readout (the owner's priority; drawDynamic draws the rpm lower): a
     * speed-limit sign, the camera line and "현재 72", both red when NavText.over. Nothing on the compact strip (the main
     * screen shows the clock and the camera line as TextViews). d may be null (vesd silent): the button shows "--".
     */
    public static void drawExtras(Painter p, LiveData d, String clockText, float w, float h) {
        Geo g = new Geo(w, h);
        if (g.compact) return;
        float m = margin(h), cs = h * 0.044f;
        if (clockText != null && clockText.length() > 0) {
            p.color(WHITE);
            p.text(clockText, m, m + cs * 0.9f, cs, false, false, -1);
        }
        // the on/off button: a pill, filled in the accent while on
        int on = d == null ? -1 : d.on, accent = Theme.accent(Theme.NEUTRAL), ink;
        float[] b = buttonRect(w, h);
        float bh = b[3] - b[1];
        String label;
        if (on == 1) {
            p.color(accent);
            ink = INK;
            label = "VES 켜짐";
        } else if (on == 0) {
            p.color(BUTTON_OFF);
            ink = WHITE;
            label = "VES 꺼짐";
        } else {
            p.color(BUTTON_UNKNOWN);
            ink = 0xFF8A9096;
            label = "VES --";
        }
        pill(p, b[0], b[1], b[2], b[3]);
        p.color(ink);
        float ts = bh * 0.42f;
        p.text(label, (b[0] + b[2]) / 2, (b[1] + b[3]) / 2 + ts * 0.36f, ts, true, false, 0);
        // the camera panel
        String line = NavText.line(d);
        if (line.length() == 0) return;
        boolean over = NavText.over(d);
        // the left-hand zone, above the rpm readout (drawDynamic): sign, camera line, current speed
        float r = g.r, zone = g.cx - r * 1.12f, rx = zone / 2;
        float rs = Math.min(r * 0.20f, zone * 0.22f), sy = g.cy - r * 0.62f;
        int lim = d.section == 1 || "section".equals(d.cam) ? (d.sectionLimit > 0 ? d.sectionLimit : d.camLimit) : d.camLimit;
        if (lim > 0) { // the round limit sign
            p.color(RED);
            p.circle(rx, sy, rs);
            p.color(WHITE);
            p.circle(rx, sy, rs * 0.78f);
            p.color(INK);
            float ns = rs * (lim >= 100 ? 0.70f : 0.88f);
            p.text(String.valueOf(lim), rx, sy + ns * 0.36f, ns, true, false, 0);
        } else { // a warning triangle
            p.color(AMBER);
            p.poly(new float[] {rx, sy - rs, rx + rs * 1.1f, sy + rs * 0.85f, rx - rs * 1.1f, sy + rs * 0.85f});
            p.color(INK);
            float ns = rs * 0.9f;
            p.text("!", rx, sy + rs * 0.2f + ns * 0.36f, ns, true, false, 0);
        }
        float ly = g.cy - r * 0.24f;
        p.color(over ? RED : WHITE);
        String detail = NavText.signDetail(d); // the sign already shows the limit
        p.text(detail, rx, ly, fit(detail, r * 0.105f, zone * 0.92f), true, false, 0);
        String kph = d.kph == d.kph ? String.valueOf(Math.round(d.kph)) : "--";
        String now = "현재 " + kph;
        p.color(over ? RED : WHITE);
        p.text(now, rx, ly + r * 0.24f, fit(now, r * 0.15f, zone * 0.92f), true, false, 0);
    }

    /**
     * The on/off button's rect {left, top, right, bottom} in the full layout: top-right, clear of the shift lamps and
     * the readouts at 1920x720 and 1281x720 (GA10). The app hit-tests taps against it.
     */
    public static float[] buttonRect(float w, float h) {
        float m = margin(h), bw = h * 0.27f, bh = h * 0.085f, top = m * 0.8f;
        return new float[] {w - m - bw, top, w - m, top + bh};
    }

    static float margin(float h) {
        return h * 0.035f;
    }

    /** a filled pill in the current colour */
    static void pill(Painter p, float x0, float y0, float x1, float y1) {
        float rr = (y1 - y0) / 2, my = (y0 + y1) / 2;
        p.circle(x0 + rr, my, rr);
        p.circle(x1 - rr, my, rr);
        p.poly(new float[] {x0 + rr, y0, x1 - rr, y0, x1 - rr, y1, x0 + rr, y1});
    }

    // ------------------------------------------------------------------ the split screen's panel page art (0.2.13)

    /**
     * The panel page's dial (rendered to PNG at build time by ves.tools.PanelArt): a dark disc with the scale (rail,
     * ticks, numerals, the hatched redline on the ICE scale), a faint accent ribbon track and "rpm x1000", centred in a
     * size x size square; outside the disc nothing is drawn (transparent). drawNeedle matches it.
     */
    public static void drawDial(Painter p, String profile, float size) {
        Geo g = new Geo(size);
        float r = g.r;
        p.color(DIAL_BG);
        p.circle(g.cx, g.cy, r * 1.08f);
        p.color(0x1AFFFFFF); // a faint rim, so the disc reads on any page colour
        p.arc(g.cx, g.cy, r * 1.07f, 0, 360, Math.max(1.5f, r * 0.008f));
        p.color(0x2E000000 | (Theme.accent(Theme.NEUTRAL) & 0xFFFFFF));
        p.arc(g.cx, g.cy, r * 0.93f, (float) GaugeMath.START_DEG, (float) GaugeMath.SWEEP_DEG, r * 0.06f);
        scale(p, g, profile);
        p.color(DIM);
        p.text("rpm x1000", g.cx, g.cy + r * 0.74f, r * 0.065f, false, false, 0);
    }

    /**
     * The panel page's needle alone, pointing at the START angle (140 degrees: 0 rpm), in a size x size square with
     * the same centre and radius as drawDial. The app rotates it about the centre with a RotateDrawable:
     * <pre>
     * &lt;rotate android:drawable="@drawable/gp_needle" android:fromDegrees="0" android:toDegrees="260"
     *         android:pivotX="50%" android:pivotY="50%" /&gt;
     * </pre>
     * level 0..10000 (PanelFrame.needle = GaugeMath.needleLevel) turns it 0..260 degrees clockwise, i.e. 0..max rpm.
     * Keep the ImageView square (needle and dial the same size, scaleType fitCenter) so the pivot is the dial's centre.
     */
    public static void drawNeedle(Painter p, float size) {
        needle(p, new Geo(size), GaugeMath.START_DEG, Theme.accent(Theme.NEUTRAL));
    }

    /**
     * The panel page's shift-lamp strip for one level of its level-list: LAMPS round lamps across w x h, the first lit
     * of them on (green, amber, red as the full gauge), or all red when flash (PanelFrame.LAMPS_FLASH).
     */
    public static void drawLamps(Painter p, int lit, boolean flash, float w, float h) {
        float lr = Math.min(h * 0.38f, w / LAMPS * 0.34f);
        for (int i = 0; i < LAMPS; i++) {
            p.color(flash || i < lit ? lampColor(i, flash) : LAMP_OFF);
            p.circle(w * (i + 0.5f) / LAMPS, h / 2, lr);
        }
    }

    /**
     * A full power bar for a ClipDrawable (w x h, the bar's middle half of the height): regen green (the widget clips
     * it from the right: gravity right), drive in the accent (clipped from the left: gravity left); the level is
     * PanelFrame.regen / drive.
     */
    public static void drawBar(Painter p, boolean regen, float w, float h) {
        p.color(regen ? GREEN : Theme.accent(Theme.NEUTRAL));
        p.poly(new float[] {0, h * 0.25f, w, h * 0.25f, w, h * 0.75f, 0, h * 0.75f});
    }

    static String rpmText(double rpm) {
        if (Double.isNaN(rpm)) return "--";
        long v = Math.round(rpm);
        return v >= 1000 ? (v / 1000) + "," + String.valueOf(1000 + v % 1000).substring(1) : String.valueOf(v);
    }

    /** the rpm readout's baseline below the centre line, in radii (full layout): room above it for the camera panel */
    static final float RPM_DY = 0.40f;

    /** text size so s fits width: Hangul about 1 em wide, other characters about 0.6 em (no measuring in Painter) */
    static float fit(String s, float size, float width) {
        float em = 0;
        for (int i = 0; i < s.length(); i++) em += s.charAt(i) >= 0xAC00 && s.charAt(i) <= 0xD7A3 ? 1f : 0.6f;
        return em <= 0 ? size : Math.min(size, width / em);
    }

    static String profileName(String profile) {
        if ("ice_shift".equals(profile)) return "VIRTUAL SHIFT";
        if ("ice_smooth".equals(profile)) return "SMOOTH";
        if ("ev_future".equals(profile)) return "EV FUTURE";
        return "";
    }
}
