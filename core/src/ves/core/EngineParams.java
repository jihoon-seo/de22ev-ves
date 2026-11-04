package ves.core;

/** Output of {@link EngineModel#update}: what the synth should sound like now. */
public final class EngineParams {
    /** virtual engine rpm (ICE) or pitch driver (EV_FUTURE, 1000 + 50 * km/h) */
    public double rpm = 800;
    /** 0..1 */
    public double load;
    /** 0..1 regen / overrun amount (0 when the mode has no regen voice) */
    public double regen;
    /** 0..1 before master volume */
    public double targetGain;
    public double speedKph;
    /** ICE_SHIFT 1..6, other profiles 0 */
    public int virtualGear;
    public boolean shifting;
    /** short state token: not_ready, park, neutral, reverse, gear_none, stale, stopped_silent, idle, drive, regen,
     *  accel_only_wait, fade_speed */
    public String reason = "stale";

    public void copyFrom(EngineParams o) {
        rpm = o.rpm;
        load = o.load;
        regen = o.regen;
        targetGain = o.targetGain;
        speedKph = o.speedKph;
        virtualGear = o.virtualGear;
        shifting = o.shifting;
        reason = o.reason;
    }

    public String toString() {
        return "reason=" + reason + " rpm=" + Math.round(rpm) + " load=" + r2(load) + " regen=" + r2(regen)
                + " gain=" + r2(targetGain) + " kph=" + r2(speedKph) + " gear=" + virtualGear + (shifting ? " shifting" : "");
    }

    static String r2(double v) {
        if (Double.isNaN(v)) return "nan";
        return String.valueOf(Math.round(v * 100) / 100.0);
    }
}
