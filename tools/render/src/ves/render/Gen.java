/*
 * ves.render: offline engine-sound renderer (host tool, not shipped to the car).
 * Ported from DasEtwas/enginesound <https://github.com/DasEtwas/enginesound>, src/gen.rs,
 * upstream commit e5fcca587397c0c8ba9c9d24874b951fed74d260.
 * Copyright (c) 2020 DasEtwas. MIT License; the full notice is in THIRD_PARTY.md.
 * The Java port and its throttle extension are MIT as well.
 */
package ves.render;

/**
 * The free functions and constants of gen.rs: valve lift curves, piston motion, fuel ignition.
 * All math is single precision like upstream (f32); sin/cos go through StrictMath so the output
 * does not depend on the JVM's intrinsics (determinism), then round to float.
 */
public final class Gen {
    private Gen() {
    }

    public static final float PI2F = 2.0f * (float) Math.PI;
    public static final float PI4F = 4.0f * (float) Math.PI;
    /** at this amplitude, a damping function is applied to fight feedback loops */
    public static final float WAVEGUIDE_MAX_AMP = 20.0f;

    static float sin(float x) {
        return (float) StrictMath.sin(x);
    }

    static float cos(float x) {
        return (float) StrictMath.cos(x);
    }

    /** Rust f32::fract: x - trunc(x) (keeps the sign of x). Exact, like Java's float remainder. */
    static float fract(float x) {
        return x % 1.0f;
    }

    static float exhaustValve(float crankPos) {
        if (0.75f < crankPos && crankPos < 1.0f) {
            return -sin(crankPos * PI4F);
        }
        return 0.0f;
    }

    static float intakeValve(float crankPos) {
        if (0.0f < crankPos && crankPos < 0.25f) {
            return sin(crankPos * PI4F);
        }
        return 0.0f;
    }

    static float pistonMotion(float crankPos) {
        return cos(crankPos * PI4F);
    }

    static float fuelIgnition(float crankPos, float ignitionTime) {
        if (0.5f < crankPos && crankPos < ignitionTime / 2.0f + 0.5f) {
            return sin(PI2F * ((crankPos - 0.5f) / ignitionTime));
        }
        return 0.0f;
    }
}
