package ves.core;

/**
 * The car check's pack demo plays a free rev with each sound pack: only standing in P with Ready on.
 * Pure logic so the host tests cover it (D1).
 */
public final class ProbeGuard {
    private ProbeGuard() {}

    /** null when the demo may play, else why not (for the screen) */
    public static String demoRefusal(double kph, double gear, double ready) {
        if (Double.isNaN(ready) || ready < 0.5) return "Ready가 아님";
        if (Double.isNaN(gear) || (int) Math.round(gear) != Sig.GEAR_P) return "P(주차)에서만 됩니다";
        if (Double.isNaN(kph) || kph > 0.5) return "정차 중에만 됩니다";
        return null;
    }

    /** the demo's pedal, one value per 100 ms for 6 s: 0, up to 75 %, hold, back to 0 */
    public static double[] demoPedal() {
        double[] p = new double[60];
        for (int i = 0; i < 60; i++) {
            double t = i / 10.0;
            double v = t < 1 ? 0 : t < 2.5 ? (t - 1) / 1.5 * 75 : t < 3.5 ? 75 : t < 4.5 ? (4.5 - t) * 75 : 0;
            p[i] = Math.max(0, Math.min(75, v));
        }
        p[59] = 0;
        return p;
    }
}
