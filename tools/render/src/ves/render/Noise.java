/*
 * ves.render: offline engine-sound renderer (host tool, not shipped to the car).
 * Ported from DasEtwas/enginesound <https://github.com/DasEtwas/enginesound>, src/gen.rs,
 * upstream commit e5fcca587397c0c8ba9c9d24874b951fed74d260.
 * Copyright (c) 2020 DasEtwas. MIT License; the full notice is in THIRD_PARTY.md.
 * The Java port and its throttle extension are MIT as well.
 */
package ves.render;

/**
 * gen.rs Noise: uniform white noise in [-1, 1] from XorShiftRng (rand_xorshift's xorshift128,
 * same recurrence). Deviation: upstream seeds from the system clock; here the 128-bit seed is
 * derived from an explicit 64-bit seed and a stream number via splitmix64, so renders repeat.
 */
final class Noise {
    private int x;
    private int y;
    private int z;
    private int w;

    Noise(long seed, int stream) {
        long s = seed * 0x9E3779B97F4A7C15L + stream * 0xD1B54A32D192ED03L;
        long a = splitmix(s += 0x9E3779B97F4A7C15L);
        long b = splitmix(s += 0x9E3779B97F4A7C15L);
        x = (int) a;
        y = (int) (a >>> 32);
        z = (int) b;
        w = (int) (b >>> 32);
        if ((x | y | z | w) == 0) {
            x = 0x0DDB1A5E; // xorshift must not start all-zero
        }
    }

    private static long splitmix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** rand_xorshift XorShiftRng::next_u32 */
    int nextU32() {
        int t = x ^ (x << 11);
        x = y;
        y = z;
        z = w;
        int w0 = w;
        w = w0 ^ (w0 >>> 19) ^ (t ^ (t >>> 8));
        return w;
    }

    /** next_u32() as f32 / (u32::MAX as f32 / 2.0) - 1.0 */
    float step() {
        return (float) (nextU32() & 0xFFFFFFFFL) / (4294967295.0f / 2.0f) - 1.0f;
    }
}
