package ves.gauge;

/** Colours by the car's drive mode (VCU CF_Vcu_EvMod raw value). */
public final class Theme {
    public static final int NEUTRAL = 0, ECO = 1, NORMAL = 2, SPORT = 3;
    /** a drive mode that went stale keeps its colours this long (the VCU5 rate is unknown) */
    public static final long HOLD_MS = 3000;
    static final String[] NAMES = {"", "ECO", "NORMAL", "SPORT"};
    // accent per theme: ice blue, mint, sky, vermilion
    static final int[] ACCENT = {0xFFB8D8EE, 0xFF3FE0A2, 0xFF6EC8FF, 0xFFFF5A36};

    private Theme() {}

    /**
     * Which raw value is which mode is not known yet (the stock firmware never reads it): vesd logs each change
     * ("drive_mode raw=N") so a drive through ECO / NORMAL / SPORT can fill this table; until then neutral.
     */
    public static int forDriveMode(int raw) {
        return NEUTRAL;
    }

    public static int accent(int theme) {
        return ACCENT[theme >= 0 && theme < ACCENT.length ? theme : NEUTRAL];
    }

    public static String name(int theme) {
        return NAMES[theme >= 0 && theme < NAMES.length ? theme : NEUTRAL];
    }

    /** holds the last known theme for HOLD_MS while the drive mode is unknown */
    public static final class Hold {
        private int last = NEUTRAL;
        private long at = Long.MIN_VALUE / 2;

        public int update(int driveMode, long nowMs) {
            if (driveMode >= 0) {
                last = forDriveMode(driveMode);
                at = nowMs;
                return last;
            }
            return nowMs - at <= HOLD_MS ? last : NEUTRAL;
        }
    }
}
