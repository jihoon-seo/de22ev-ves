package ves.core;

/**
 * Linear gain ramp between blocks: attack 0 to 1 in 250 ms (so never faster than 200 ms to full), release 1 to 0
 * in 150 ms (the same rates per unit up to TOTAL_MAX). next() returns the gain for the end of the block; Synth
 * interpolates from the previous value. Above 1 VesCore turns the gain into output-stage makeup.
 */
public final class GainEnvelope {
    public static final double CAP = 1.0;
    /** the whole volume curve +6 dB (0.2.7; the car check found 0.2.6 too quiet under road noise) */
    public static final double VOLUME_BOOST = Math.pow(10, 6 / 20.0);
    /** the most the envelope carries (gain x makeup) */
    public static final double TOTAL_MAX = 4.0;
    public static final double ATTACK_S = 0.25, RELEASE_S = 0.15;

    private final double sampleRate;
    private double g;

    public GainEnvelope(int sampleRate) {
        this.sampleRate = sampleRate > 0 ? sampleRate : 22050;
    }

    /**
     * (v/100)^1.5 x 10^(6/20): 100 is +6 dB over full scale (the part above 1 becomes makeup), 0 is 0. (0.1.1 had
     * 0.5 * (v/100)^2, which the car check found almost inaudible: the navigation channel plays it at the
     * navigation volume, under road noise; 0.2.6 had (v/100)^1.5.)
     */
    public static double volumeToLinear(int volume0to100) {
        int v = volume0to100 < 0 ? 0 : volume0to100 > 100 ? 100 : volume0to100;
        double x = v / 100.0;
        return CAP * x * Math.sqrt(x) * VOLUME_BOOST;
    }

    public double next(double target, int frames) {
        if (!(target > 0)) target = 0; // also NaN
        if (target > TOTAL_MAX) target = TOTAL_MAX;
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
