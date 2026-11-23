package ves.gauge;

/** Colours by the car's drive mode; neutral for now (CF_Vcu_EvMod is the power flow 1/2/6, see forDriveMode). */
public final class Theme {
    public static final int NEUTRAL = 0, ECO = 1, NORMAL = 2, SPORT = 3;
    /** a drive mode that went stale keeps its colours this long (the VCU5 rate is unknown) */
    public static final long HOLD_MS = 3000;
    static final String[] NAMES = {"", "ECO", "NORMAL", "SPORT"};
    // accent per theme: ice blue, mint, sky, vermilion
    static final int[] ACCENT = {0xFFB8D8EE, 0xFF3FE0A2, 0xFF6EC8FF, 0xFFFF5A36};

    private Theme() {}

    /**
     * Always neutral. CF_Vcu_EvMod (VCU5, Sig.DRIVE_MODE) turned out to be the power flow, not the drive mode: the
     * 2026-11-20 drive log showed only 0/1/2/6, flipping every few seconds while driving (owner decision D6, 0.2.13),
     * so vesd no longer logs its changes. ECO / NORMAL / SPORT would need another signal; until then neutral.
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
