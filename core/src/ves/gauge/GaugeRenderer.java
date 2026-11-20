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
 */
public final class GaugeRenderer {
    static final int WHITE = 0xFFF2F6FA, DIM = 0x59FFFFFF, FAINT = 0x24FFFFFF, RED = 0xFFFF3B30, RED_ZONE = 0x66FF3B30,
            GREEN = 0xFF46D17A, AMBER = 0xFFFFB020, LAMP_OFF = 0x2EFFFFFF;
    static final int LAMPS = 9;

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

        float x(double deg, double rad) {
            return (float) (cx + rad * Math.cos(Math.toRadians(deg)));
        }

        float y(double deg, double rad) {
            return (float) (cy + rad * Math.sin(Math.toRadians(deg)));
        }
    }

    public static void drawStatic(Painter p, String profile, int theme, float w, float h) {
        Geo g = new Geo(w, h);
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
        // the blade needle: outer part only
        double na = Math.toRadians(a), nx = Math.cos(na), ny = Math.sin(na), bw = r * 0.04;
        float ri = r * (g.compact ? 0.62f : 0.56f), ro = r * 0.99f;
        p.color(accent);
        p.poly(new float[] {(float) (g.cx + ro * nx), (float) (g.cy + ro * ny),
                (float) (g.cx + ri * nx - bw * ny), (float) (g.cy + ri * ny + bw * nx),
                (float) (g.cx + ri * nx + bw * ny), (float) (g.cy + ri * ny - bw * nx)});
        p.color(WHITE);
        p.line(g.x(Math.toDegrees(na), r * 0.80), g.y(Math.toDegrees(na), r * 0.80), g.x(Math.toDegrees(na), ro), g.y(Math.toDegrees(na), ro),
                Math.max(1.5f, r * 0.01f));
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
            // side readouts
            float lx = (g.cx - r * 1.12f) / 2;
            p.color(WHITE);
            p.text(rpmText(d.rpm), lx, g.cy, r * 0.20f, true, false, 0);
            p.color(DIM);
            p.text("RPM", lx, g.cy + r * 0.14f, r * 0.065f, false, false, 0);
            float rx = g.w - lx;
            String mode = Theme.name(theme);
            p.color(mode.length() > 0 ? accent : DIM);
            p.text(mode.length() > 0 ? mode : "-", rx, g.cy, r * 0.17f, true, true, 0);
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
            for (int k = 1; k <= 6; k++) {
                boolean on = k == d.gear;
                p.color(on ? accent : DIM);
                p.text(String.valueOf(k), sx + (k - 1) * step, sy, on ? big : small, on, true, 0);
            }
        }
        // shift lamps
        int lit = GaugeMath.shiftLights(d.rpm, d.shiftRpm, LAMPS);
        boolean flash = GaugeMath.shiftBlink(d.rpm, d.shiftRpm);
        for (int i = 0; i < LAMPS; i++) {
            boolean on = flash ? blinkOn : i < lit;
            int c = flash ? RED : i < 3 ? GREEN : i < 6 ? AMBER : RED;
            p.color(on ? c : LAMP_OFF);
            float lx, ly, lr;
            if (g.compact) {
                float[] b = bar(g);
                lx = b[0] + (b[1] - b[0]) * (i + 0.5f) / LAMPS;
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

    static String rpmText(double rpm) {
        if (Double.isNaN(rpm)) return "--";
        long v = Math.round(rpm);
        return v >= 1000 ? (v / 1000) + "," + String.valueOf(1000 + v % 1000).substring(1) : String.valueOf(v);
    }

    static String profileName(String profile) {
        if ("ice_shift".equals(profile)) return "VIRTUAL SHIFT";
        if ("ice_smooth".equals(profile)) return "SMOOTH";
        if ("ev_future".equals(profile)) return "EV FUTURE";
        return "";
    }
}
