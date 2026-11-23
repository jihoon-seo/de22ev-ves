package ves.gauge;

/**
 * One frame of the split-screen panel page (0.2.13, AppWidget RemoteViews): drawable levels and texts made from a
 * LIVE answer, and diff() telling which views changed so the app sends partial updates only.
 * <pre>
 * dial    ev: the 0-9000 EV dial, else the 0-8000 ICE dial with the 6500 redline
 * needle  RotateDrawable level 0..10000 (GaugeMath.needleLevel, steps of 25)
 * lamps   level-list: 0..9 lamps lit; at the shift point 10 (flash on) or 0 (flash off)
 * drive   ClipDrawable level: kwBar x 10000 in steps of 100 (drive side), regen the same for regen
 * gear    P / R / N / D / D4 / "-"   speed  km/h rounded, "-" unknown   on  1 / 0 / -1 unknown
 * </pre>
 */
public final class PanelFrame {
    public static final int F_DIAL = 1, F_NEEDLE = 2, F_LAMPS = 4, F_DRIVE = 8, F_REGEN = 16, F_GEAR = 32, F_SPEED = 64, F_ON = 128;
    public static final int ALL = F_DIAL | F_NEEDLE | F_LAMPS | F_DRIVE | F_REGEN | F_GEAR | F_SPEED | F_ON;
    /** lamps in the strip; LAMPS_FLASH is the level-list item with all of them lit for the flash's on phase */
    public static final int LAMPS = 9, LAMPS_FLASH = 10;
    static final int BAR_STEP = 100;

    public boolean ev;
    public int needle, lamps, drive, regen, on = -1;
    public String gear = "-", speed = "-";

    /** flashOn: the blink phase while at or past the shift point (the app toggles it, 250 ms) */
    public static PanelFrame of(LiveData d, boolean flashOn) {
        PanelFrame f = new PanelFrame();
        f.ev = d.max > 8000;
        f.needle = GaugeMath.needleLevel(d.rpm, d.max);
        if (GaugeMath.shiftBlink(d.rpm, d.shiftRpm)) f.lamps = flashOn ? LAMPS_FLASH : 0;
        else f.lamps = GaugeMath.shiftLights(d.rpm, d.shiftRpm, LAMPS);
        double bar = GaugeMath.kwBar(d.kw);
        f.drive = bar > 0 ? bar(bar) : 0;
        f.regen = bar < 0 ? bar(-bar) : 0;
        f.gear = GaugeMath.gearLabel(d.profile, d.selector, d.gear);
        f.speed = Double.isNaN(d.kph) ? "-" : String.valueOf(Math.round(d.kph));
        f.on = d.on;
        return f;
    }

    private static int bar(double share) {
        return (int) Math.round(share * 10000 / BAR_STEP) * BAR_STEP;
    }

    /** the fields that differ from prev (ALL when there is none) */
    public int diff(PanelFrame prev) {
        if (prev == null) return ALL;
        int m = 0;
        if (ev != prev.ev) m |= F_DIAL;
        if (needle != prev.needle) m |= F_NEEDLE;
        if (lamps != prev.lamps) m |= F_LAMPS;
        if (drive != prev.drive) m |= F_DRIVE;
        if (regen != prev.regen) m |= F_REGEN;
        if (!gear.equals(prev.gear)) m |= F_GEAR;
        if (!speed.equals(prev.speed)) m |= F_SPEED;
        if (on != prev.on) m |= F_ON;
        return m;
    }
}
