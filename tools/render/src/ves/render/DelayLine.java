/*
 * ves.render: offline engine-sound renderer (host tool, not shipped to the car).
 * Ported from DasEtwas/enginesound <https://github.com/DasEtwas/enginesound>, src/gen.rs,
 * upstream commit e5fcca587397c0c8ba9c9d24874b951fed74d260.
 * Copyright (c) 2020 DasEtwas. MIT License; the full notice is in THIRD_PARTY.md.
 * The Java port and its throttle extension are MIT as well.
 */
package ves.render;

/** gen.rs DelayLine: a LoopBuffer of a fixed number of samples. */
final class DelayLine {
    final LoopBuffer samples;

    DelayLine(int delay, int samplesPerSecond) {
        this.samples = new LoopBuffer(delay, samplesPerSecond);
    }

    float pop() {
        return samples.pop();
    }

    void push(float sample) {
        samples.push(sample);
    }
}
