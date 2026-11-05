/*
 * ves.render: offline engine-sound renderer (host tool, not shipped to the car).
 * Ported from DasEtwas/enginesound <https://github.com/DasEtwas/enginesound>, src/gen.rs,
 * upstream commit e5fcca587397c0c8ba9c9d24874b951fed74d260.
 * Copyright (c) 2020 DasEtwas. MIT License; the full notice is in THIRD_PARTY.md.
 * The Java port and its throttle extension are MIT as well.
 */
package ves.render;

/**
 * gen.rs Engine (and Muffler) plus the throttle extension. Built by {@link EngineConfig} for a
 * sample rate (upstream: deserialize + utils::fix_engine).
 *
 * <p>Throttle / load (not in upstream, which always renders a firing engine): {@code throttle}
 * in [0, 1]. Each affected term is scaled by {@code s = 1 - (1 - off) * (1 - throttle)}, so it is
 * exactly 1.0f at throttle 1 (bit-identical to upstream) and {@code off} at throttle 0:
 * <ul>
 * <li>ignition (Cylinder.ignition_factor): fuel cut on overrun, only a residual pulse remains;</li>
 * <li>piston motion (Cylinder.piston_motion_factor): lower cylinder pressure under vacuum;</li>
 * <li>intake channel (intake_volume): the closed throttle plate hides the induction roar;</li>
 * <li>intake noise (intake_noise_factor): little air flow, little hiss (and no turbo whoosh);</li>
 * <li>overrun low pass: the output is blended with a one-pole low pass at off_lowpass_hz by
 * (1 - throttle), which dulls the sound; at throttle 1 the blend weight is exactly 0.</li>
 * </ul>
 */
final class Engine {
    /** gen.rs Muffler */
    static final class Muffler {
        final WaveGuide straightPipe;
        final WaveGuide[] mufflerElements;

        Muffler(WaveGuide straightPipe, WaveGuide[] mufflerElements) {
            this.straightPipe = straightPipe;
            this.mufflerElements = mufflerElements;
        }
    }

    float rpm;
    final float intakeVolume;
    final float exhaustVolume;
    final float engineVibrationsVolume;

    final Cylinder[] cylinders;
    final Noise intakeNoise;
    final float intakeNoiseFactor;
    final LowPassFilter intakeNoiseLp;
    final LowPassFilter engineVibrationFilter;
    final Muffler muffler;
    /** valve timing -0.5 - 0.5 */
    final float intakeValveShift;
    /** valve timing -0.5 - 0.5 */
    final float exhaustValveShift;
    final float crankshaftFluctuation;
    final LowPassFilter crankshaftFluctuationLp;
    final Noise crankshaftNoise;

    // running values
    /** crankshaft position, 0.0-1.0 (one 4-stroke cycle = two revolutions) */
    float crankshaftPos;
    float exhaustCollector;
    float intakeCollector;

    // ---- throttle extension
    final float offIgnition;
    final float offPiston;
    final float offIntake;
    final float offIntakeNoise;
    final LowPassFilter overrunLp;
    float throttle = 1.0f;
    float ignitionScale = 1.0f;
    float pistonScale = 1.0f;
    float intakeScale = 1.0f;
    float intakeNoiseScale = 1.0f;

    Engine(float rpm, float intakeVolume, float exhaustVolume, float engineVibrationsVolume,
            Cylinder[] cylinders, long seed, float intakeNoiseFactor, LowPassFilter intakeNoiseLp,
            LowPassFilter engineVibrationFilter, Muffler muffler, float intakeValveShift,
            float exhaustValveShift, float crankshaftFluctuation, LowPassFilter crankshaftFluctuationLp,
            float offIgnition, float offPiston, float offIntake, float offIntakeNoise,
            LowPassFilter overrunLp) {
        this.rpm = rpm;
        this.intakeVolume = intakeVolume;
        this.exhaustVolume = exhaustVolume;
        this.engineVibrationsVolume = engineVibrationsVolume;
        this.cylinders = cylinders;
        this.intakeNoise = new Noise(seed, 1);
        this.intakeNoiseFactor = intakeNoiseFactor;
        this.intakeNoiseLp = intakeNoiseLp;
        this.engineVibrationFilter = engineVibrationFilter;
        this.muffler = muffler;
        this.intakeValveShift = intakeValveShift;
        this.exhaustValveShift = exhaustValveShift;
        this.crankshaftFluctuation = crankshaftFluctuation;
        this.crankshaftFluctuationLp = crankshaftFluctuationLp;
        this.crankshaftNoise = new Noise(seed, 2);
        this.offIgnition = offIgnition;
        this.offPiston = offPiston;
        this.offIntake = offIntake;
        this.offIntakeNoise = offIntakeNoise;
        this.overrunLp = overrunLp;
    }

    /** Sets the load: 1 = on-load (upstream behaviour), 0 = overrun / fuel cut. */
    void setThrottle(float t) {
        if (!(t >= 0.0f)) {
            t = 0.0f;
        } else if (t > 1.0f) {
            t = 1.0f;
        }
        throttle = t;
        ignitionScale = scale(offIgnition, t);
        pistonScale = scale(offPiston, t);
        intakeScale = scale(offIntake, t);
        intakeNoiseScale = scale(offIntakeNoise, t);
    }

    private static float scale(float off, float t) {
        return 1.0f - (1.0f - off) * (1.0f - t);
    }
}
