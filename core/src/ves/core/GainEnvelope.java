package ves.core;

/**
 * Linear gain ramp between blocks: attack 0 to 1 in 250 ms (so never faster than 200 ms to full), release 1 to 0
 * in 150 ms. next() returns the gain for the end of the block; Synth interpolates from the previous value.
 */
public final class GainEnvelope {
    public static final double CAP = 0.5;
    public static final double ATTACK_S = 0.25, RELEASE_S = 0.15;

    private final double sampleRate;
    private double g;

    public GainEnvelope(int sampleRate) {
        this.sampleRate = sampleRate > 0 ? sampleRate : 22050;
    }

    /** cap * (v/100)^2 with cap 0.5: 100 is 0.5 FS, 0 is 0. */
    public static double volumeToLinear(int volume0to100) {
        int v = volume0to100 < 0 ? 0 : volume0to100 > 100 ? 100 : volume0to100;
        double x = v / 100.0;
        return CAP * x * x;
    }

    public double next(double target, int frames) {
        if (!(target > 0)) target = 0; // also NaN
        if (target > 1) target = 1;
        double dt = Math.max(0, frames) / sampleRate;
        if (target > g) {
            g = Math.min(target, g + dt / ATTACK_S);
        } else if (target < g) {
            g = Math.max(target, g - dt / RELEASE_S);
        }
        return g;
    }

    public double value() {
        return g;
    }

    public void reset() {
        g = 0;
    }
}
