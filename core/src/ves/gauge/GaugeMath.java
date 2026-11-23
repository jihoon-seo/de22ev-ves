package ves.gauge;

/** The gauge's numbers: scale, angles, shift light, gear label, power bar. Pure Java (shared by the app and the PC). */
public final class GaugeMath {
    /** the tachometer sweeps 260 degrees clockwise from 8 o'clock (140) to 4 o'clock (40) */
    public static final double START_DEG = 140, SWEEP_DEG = 260;
    /** power bar ends: full regen and full drive in kW */
    public static final double REGEN_FULL_KW = 40, DRIVE_FULL_KW = 150;
    /** the shift light starts filling at this share of the shift point */
    static final double SHIFT_FROM = 0.8;
    // Sig.GEAR_* codes (the car's selector)
    static final int SEL_P = 0, SEL_B = 1, SEL_ECO = 2, SEL_D = 5, SEL_N = 6, SEL_R = 7;

    private GaugeMath() {}

    /** EV_FUTURE's rpm is a pitch value up to 9000 (owner: its own 0-9 scale); the ICE profiles 0-8 */
    public static double maxRpm(String profile) {
        return "ev_future".equals(profile) ? 9000 : 8000;
    }

    /** the model's 6500 limit; EV_FUTURE has none */
    public static double redline(String profile) {
        return "ev_future".equals(profile) ? Double.NaN : 6500;
    }

    public static double angle(double rpm, double max) {
        if (Double.isNaN(rpm) || !(max > 0)) return START_DEG;
        double f = rpm <= 0 ? 0 : rpm >= max ? 1 : rpm / max;
        return START_DEG + SWEEP_DEG * f;
    }

    /** lamps lit of n: none below 80 % of the shift point, all at it */
    public static int shiftLights(double rpm, double shiftRpm, int n) {
        if (Double.isNaN(rpm) || !(shiftRpm > 0)) return 0;
        double lo = SHIFT_FROM * shiftRpm;
        if (rpm < lo) return 0;
        if (rpm >= shiftRpm) return n;
        int k = (int) Math.ceil((rpm - lo) / (shiftRpm - lo) * n);
        return Math.max(1, Math.min(n - 1, k));
    }

    /** at or past the shift point: the lamps flash */
    public static boolean shiftBlink(double rpm, double shiftRpm) {
        return shiftRpm > 0 && rpm >= shiftRpm;
    }

    /** P / R / N / D (B and ECO show as D: owner's choice), "-" unknown; ICE_SHIFT in D adds the virtual gear: D4 */
    public static String gearLabel(String profile, int selector, int gear) {
        switch (selector) {
            case SEL_P:
                return "P";
            case SEL_N:
                return "N";
            case SEL_R:
                return "R";
            case SEL_D:
            case SEL_B:
            case SEL_ECO:
                return "ice_shift".equals(profile) && gear >= 1 && gear <= 6 ? "D" + gear : "D";
            default:
                return "-";
        }
    }

    public static boolean showGearStrip(String profile) {
        return "ice_shift".equals(profile);
    }

    /** -1 (full regen) .. 0 .. 1 (full drive); unknown 0 */
    /** the panel page's needle (RotateDrawable 140..400 degrees): level 0..10000 in steps of NEEDLE_STEP */
    public static int needleLevel(double rpm, double max) {
        double level = (angle(rpm, max) - START_DEG) / SWEEP_DEG * 10000;
        return (int) Math.round(level / NEEDLE_STEP) * NEEDLE_STEP;
    }

    /** needle level quantum: 260 degrees / 400 steps = 0.65 degree, so a still needle sends no update */
    public static final int NEEDLE_STEP = 25;

    public static double kwBar(double kw) {
        if (Double.isNaN(kw)) return 0;
        return kw < 0 ? Math.max(-1, kw / REGEN_FULL_KW) : Math.min(1, kw / DRIVE_FULL_KW);
    }
}
