/*
 * ves.render: offline engine-sound renderer (host tool, not shipped to the car).
 * Ported from DasEtwas/enginesound <https://github.com/DasEtwas/enginesound>, src/gen.rs,
 * upstream commit e5fcca587397c0c8ba9c9d24874b951fed74d260.
 * Copyright (c) 2020 DasEtwas. MIT License; the full notice is in THIRD_PARTY.md.
 * The Java port and its throttle extension are MIT as well.
 */
package ves.render;

/** gen.rs LoopBuffer: a ring buffer; push writes at pos, pop reads pos + 1, advance moves on. */
final class LoopBuffer {
    /** in seconds */
    final float delay;
    final float[] data;
    /** kept in [0, len) instead of upstream's unbounded usize; pos % len is the same */
    int pos;

    LoopBuffer(int len, int samplesPerSecond) {
        this.delay = len / (float) samplesPerSecond;
        this.data = new float[len];
    }

    /** Sets the value at the current position. Must be called with pop. */
    void push(float value) {
        data[pos % data.length] = value;
    }

    /** Gets the value len samples prior. Must be called with push. */
    float pop() {
        return data[(pos + 1) % data.length];
    }

    /** Advances the position of this loop buffer. */
    void advance() {
        pos++;
        if (pos == data.length) {
            pos = 0;
        }
    }

    void clear() {
        java.util.Arrays.fill(data, 0.0f);
    }
}
