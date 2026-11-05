/*
 * ves.render: offline engine-sound renderer (host tool, not shipped to the car).
 * Ported from DasEtwas/enginesound <https://github.com/DasEtwas/enginesound>, src/gen.rs,
 * upstream commit e5fcca587397c0c8ba9c9d24874b951fed74d260.
 * Copyright (c) 2020 DasEtwas. MIT License; the full notice is in THIRD_PARTY.md.
 * The Java port and its throttle extension are MIT as well.
 */
package ves.render;

/** gen.rs LowPassFilter: one-pole low pass; config stores delay = 1 / cutoff (seconds). */
final class LowPassFilter {
    /** 1 / cutoff frequency */
    final float delay;
    final float alpha;
    float last;

    LowPassFilter(float freq, int samplesPerSecond) {
        this.delay = 1.0f / freq;
        float w = Gen.PI2F * (1.0f / (float) samplesPerSecond) * freq;
        this.alpha = w / (Gen.PI2F * (1.0f / (float) samplesPerSecond) * freq + 1.0f);
    }

    float getFreq() {
        return 1.0f / delay;
    }

    float filter(float sample) {
        // upstream: (sample - last).mul_add(alpha, last), a fused multiply-add. Java 8 has no
        // Math.fma: the float product is exact in double, so only the final add rounds (twice:
        // to double, then to float; can differ from a true fma by 1 ulp in rare ties).
        float ret = (float) ((double) (sample - last) * (double) alpha + (double) last);
        last = ret;
        return ret;
    }
}
