package ves.core;

/** Signal indices of {@link SignalState} and the gear codes of VCU1 CF_Vcu_GarSelDisp. */
public final class Sig {
    public static final int SPEED_KPH = 0, PEDAL_PCT = 1, MOTOR_KW = 2, TORQUE_PCT = 3, ACCEL_G = 4, GEAR = 5,
            READY = 6, BRAKE_BAR = 7, MOTOR_SPEED_KPH = 8, DRIVE_MODE = 9, COUNT = 10;

    /** CF_Vcu_GarSelDisp: P0 B1 ECO2 D5 N6 R7 (9 = none / not displayed). */
    public static final int GEAR_P = 0, GEAR_B = 1, GEAR_ECO = 2, GEAR_D = 5, GEAR_N = 6, GEAR_R = 7;

    private static final String[] NAMES = {"SPEED_KPH", "PEDAL_PCT", "MOTOR_KW", "TORQUE_PCT", "ACCEL_G", "GEAR",
            "READY", "BRAKE_BAR", "MOTOR_SPEED_KPH", "DRIVE_MODE"};

    private Sig() {}

    public static String name(int sig) {
        return sig >= 0 && sig < COUNT ? NAMES[sig] : "SIG" + sig;
    }

    /** Index for a name as printed by {@link #name(int)} (case-insensitive), or -1. */
    public static int byName(String n) {
        if (n == null) return -1;
        String u = n.trim().toUpperCase(java.util.Locale.US);
        for (int i = 0; i < COUNT; i++) if (NAMES[i].equals(u)) return i;
        return -1;
    }

    /** true for the forward drive gears (D, B, ECO). */
    public static boolean isDrive(int gear) {
        return gear == GEAR_D || gear == GEAR_B || gear == GEAR_ECO;
    }
}
