/*
 * ves.render: offline engine-sound renderer (host tool, not shipped to the car).
 * Ported from DasEtwas/enginesound <https://github.com/DasEtwas/enginesound>, src/gen.rs,
 * upstream commit e5fcca587397c0c8ba9c9d24874b951fed74d260.
 * Copyright (c) 2020 DasEtwas. MIT License; the full notice is in THIRD_PARTY.md.
 * The Java port and its throttle extension are MIT as well.
 */
package ves.render;

/**
 * gen.rs WaveGuide: two delay lines (chamber0 goes x0 to x1, chamber1 x1 to x0) with reflection
 * factors alpha (x0 end) and beta (x1 end). Upstream's pop() returns a tuple; here it sets
 * {@link #out0}, {@link #out1} and {@link #dampened} (tuple .0, .1, .2) to avoid allocation.
 */
final class WaveGuide {
    /** goes from x0 to x1 */
    final DelayLine chamber0;
    /** goes from x1 to x0 */
    final DelayLine chamber1;
    /** reflection factor for the first value of the return tuple of pop */
    float alpha;
    /** reflection factor for the second value of the return tuple of pop */
    float beta;

    // running values
    private float c1Out;
    private float c0Out;

    // pop() "tuple"
    float out0;
    float out1;
    boolean dampened;

    WaveGuide(int delay0, int delay1, float alpha, float beta, int samplesPerSecond) {
        this.chamber0 = new DelayLine(delay0, samplesPerSecond);
        this.chamber1 = new DelayLine(delay1, samplesPerSecond);
        this.alpha = alpha;
        this.beta = beta;
    }

    void pop() {
        float s1 = chamber1.pop();
        float s0 = chamber0.pop();
        boolean dampenedC1 = Math.abs(s1) > Gen.WAVEGUIDE_MAX_AMP;
        boolean dampenedC0 = Math.abs(s0) > Gen.WAVEGUIDE_MAX_AMP;
        c1Out = dampen(s1);
        c0Out = dampen(s0);

        out0 = c1Out * (1.0f - Math.abs(alpha));
        out1 = c0Out * (1.0f - Math.abs(beta));
        dampened = dampenedC1 | dampenedC0;
    }

    static float dampen(float sample) {
        float sampleAbs = Math.abs(sample);
        if (sampleAbs > Gen.WAVEGUIDE_MAX_AMP) {
            return Math.signum(sample)
                    * (-1.0f / (sampleAbs - Gen.WAVEGUIDE_MAX_AMP + 1.0f) + 1.0f + Gen.WAVEGUIDE_MAX_AMP);
        }
        return sample;
    }

    void push(float x0In, float x1In) {
        float c0In = c1Out * alpha + x0In;
        float c1In = c0Out * beta + x1In;

        chamber0.push(c0In);
        chamber1.push(c1In);
        chamber0.samples.advance();
        chamber1.samples.advance();
    }

    /** part of Generator::reset: zero both chambers (upstream keeps c0_out/c1_out). */
    void clear() {
        chamber0.samples.clear();
        chamber1.samples.clear();
    }
}
