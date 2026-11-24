package ves.gauge;

/**
 * One frame of the split-screen panel page (0.2.13, AppWidget RemoteViews): drawable levels and texts made from a
 * LIVE answer, and diff() telling which views changed so the app sends partial updates only.
 * <pre>
 * dial    ev: the 0-9000 EV dial, else the 0-8000 ICE dial with the 6500 redline
 * needle  RotateDrawable level 0..10000 (GaugeMath.needleLevel, steps of 25)
 * lamps   0..9 lamps lit; at the shift point 10 (flash on) or 0 (flash off); the ring's two ClipDrawables take
 *         lampsLevel() (lit lamps) and flashLevel() (all red) (0.2.14)
 * gear    P / R / N / D / D4 / "-"   speed  km/h rounded, "-" unknown   on  1 / 0 / -1 unknown
 * </pre>
 */
public final class PanelFrame {
    public static final int F_DIAL = 1, F_NEEDLE = 2, F_LAMPS = 4, F_GEAR = 8, F_SPEED = 16, F_ON = 32;
    public static final int ALL = F_DIAL | F_NEEDLE | F_LAMPS | F_GEAR | F_SPEED | F_ON;
    /** lamps on the ring; LAMPS_FLASH is lamps' value for the flash's on phase (all of them red) */
    public static final int LAMPS = 9, LAMPS_FLASH = 10;

    public boolean ev;
    public int needle, lamps, on = -1;
    public String gear = "-", speed = "-";

    /** flashOn: the blink phase while at or past the shift point (the app toggles it, 250 ms) */
    public static PanelFrame of(LiveData d, boolean flashOn) {
        PanelFrame f = new PanelFrame();
        f.ev = d.max > 8000;
        f.needle = GaugeMath.needleLevel(d.rpm, d.max);
        if (GaugeMath.shiftBlink(d.rpm, d.shiftRpm)) f.lamps = flashOn ? LAMPS_FLASH : 0;
        else f.lamps = GaugeMath.shiftLights(d.rpm, d.shiftRpm, LAMPS);
        f.gear = GaugeMath.gearLabel(d.profile, d.selector, d.gear);
        f.speed = Double.isNaN(d.kph) ? "-" : String.valueOf(Math.round(d.kph));
        f.on = d.on;
        return f;
    }

    /** the lit-lamps ClipDrawable's level for lit lamps (GaugeRenderer.lampClip) */
    public static int lampClip(int lit) {
        return GaugeRenderer.lampClip(lit);
    }

    /** the lit-lamps overlay's level: the first lamps lit, nothing during the flash */
    public int lampsLevel() {
        return lamps == LAMPS_FLASH ? 0 : lampClip(lamps);
    }

    /** the all-red overlay's level: everything in the flash's on phase, else nothing */
    public int flashLevel() {
        return lamps == LAMPS_FLASH ? 10000 : 0;
    }

    /** the fields that differ from prev (ALL when there is none) */
    public int diff(PanelFrame prev) {
        if (prev == null) return ALL;
        int m = 0;
        if (ev != prev.ev) m |= F_DIAL;
        if (needle != prev.needle) m |= F_NEEDLE;
        if (lamps != prev.lamps) m |= F_LAMPS;
        if (!gear.equals(prev.gear)) m |= F_GEAR;
        if (!speed.equals(prev.speed)) m |= F_SPEED;
        if (on != prev.on) m |= F_ON;
        return m;
    }
}
