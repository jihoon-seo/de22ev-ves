/*
 * ves.render: offline engine-sound renderer (host tool, not shipped to the car).
 * Ported from DasEtwas/enginesound <https://github.com/DasEtwas/enginesound>, src/gen.rs,
 * upstream commit e5fcca587397c0c8ba9c9d24874b951fed74d260.
 * Copyright (c) 2020 DasEtwas. MIT License; the full notice is in THIRD_PARTY.md.
 * The Java port and its throttle extension are MIT as well.
 */
package ves.render;

/**
 * gen.rs Generator. Basic working principle: every sample-output generating object (Cylinder,
 * WaveGuide, DelayLine, ..) has to be first popped, its output worked upon and then new input
 * samples are pushed. The recorder and the GUI's clipping flag are left out; WAV output lives
 * in {@link Wav}.
 */
final class Generator {
    float volume = 0.1f;
    final int samplesPerSecond;
    final Engine engine;
    /** LowPassFilter which is subtracted from the sample to reduce dc offset and thus clipping */
    private final LowPassFilter dcLp;
    /** set to true by any waveguide if it is dampening its output to prevent feedback loops */
    boolean waveguidesDampened;
    /** sticky version of waveguidesDampened over the generator's life (diagnostics) */
    boolean everDampened;

    Generator(int samplesPerSecond, Engine engine, LowPassFilter dcLp) {
        this.samplesPerSecond = samplesPerSecond;
        this.engine = engine;
        this.dcLp = dcLp;
    }

    /** main.rs uses LowPassFilter::new(0.5, sample_rate) as the DC filter. */
    Generator(int samplesPerSecond, Engine engine) {
        this(samplesPerSecond, engine, new LowPassFilter(0.5f, samplesPerSecond));
    }

    void generate(float[] buf, int off, int len) {
        float samplesPerSecond120 = (float) samplesPerSecond * 120.0f;

        waveguidesDampened = false;

        Engine e = engine;
        float inc = e.rpm / samplesPerSecond120;
        float intakeVolume = e.intakeVolume * e.intakeScale;
        float wet = 1.0f - e.throttle;

        for (int i = off; i < off + len; i++) {
            e.crankshaftPos = Gen.fract(e.crankshaftPos + inc);

            gen();
            float mixed = (genIntake * intakeVolume
                    + genVibration * e.engineVibrationsVolume
                    + genExhaust * e.exhaustVolume)
                    * volume;
            waveguidesDampened |= genDampened;

            // reduces dc offset
            float sample = mixed - dcLp.filter(mixed);

            // throttle extension: overrun low pass, blended in by (1 - throttle); bypassed at
            // throttle 1 (the filter still runs so a throttle change does not jump)
            float lp = e.overrunLp.filter(sample);
            buf[i] = wet == 0.0f ? sample : sample * e.throttle + lp * wet;
        }
        everDampened |= waveguidesDampened;
    }

    void reset() {
        for (Cylinder cyl : engine.cylinders) {
            cyl.exhaustWaveguide.clear();
            cyl.intakeWaveguide.clear();
            cyl.extractorWaveguide.clear();
            cyl.extractorExhaust = 0.0f;
            cyl.cylSound = 0.0f;
        }
        engine.muffler.straightPipe.clear();
        for (WaveGuide mufflerElement : engine.muffler.mufflerElements) {
            mufflerElement.clear();
        }
        engine.exhaustCollector = 0.0f;
        engine.intakeCollector = 0.0f;
    }

    // gen() "tuple": (intake, engine vibrations, exhaust, waveguides dampened)
    private float genIntake;
    private float genVibration;
    private float genExhaust;
    private boolean genDampened;

    /** generates one sample worth of audio */
    private void gen() {
        Engine e = engine;
        float intakeNoise = e.intakeNoiseLp.filter(e.intakeNoise.step())
                * e.intakeNoiseFactor * e.intakeNoiseScale;

        float engineVibration = 0.0f;

        float numCyl = (float) e.cylinders.length;

        float lastExhaustCollector = e.exhaustCollector / numCyl;
        e.exhaustCollector = 0.0f;
        e.intakeCollector = 0.0f;

        float crankshaftFluctuationOffset = e.crankshaftFluctuationLp.filter(e.crankshaftNoise.step());

        boolean cylinderDampened = false;

        for (Cylinder cylinder : e.cylinders) {
            cylinder.pop(e.crankshaftPos + e.crankshaftFluctuation * crankshaftFluctuationOffset,
                    lastExhaustCollector, e.intakeValveShift, e.exhaustValveShift,
                    e.pistonScale, e.ignitionScale);

            e.intakeCollector += cylinder.popIntake;
            e.exhaustCollector += cylinder.popExhaust;

            engineVibration += cylinder.popSound;
            cylinderDampened |= cylinder.popDampened;
        }

        // parallel input to the exhaust straight pipe
        // alpha end is at exhaust collector
        WaveGuide straightPipe = e.muffler.straightPipe;
        straightPipe.pop();

        // alpha end is at straight pipe end (beta)
        float muffler0 = 0.0f;
        float muffler1 = 0.0f;
        boolean muffler2 = false;
        for (WaveGuide mufflerLine : e.muffler.mufflerElements) {
            mufflerLine.pop();
            muffler0 += mufflerLine.out0;
            muffler1 += mufflerLine.out1;
            muffler2 |= mufflerLine.dampened;
        }

        // pop  //
        //////////
        // push //

        for (Cylinder cylinder : e.cylinders) {
            // modulate intake
            cylinder.push(e.intakeCollector / numCyl
                    + intakeNoise * Gen.intakeValve(Gen.fract(e.crankshaftPos + cylinder.crankOffset)));
        }

        straightPipe.push(e.exhaustCollector, muffler0);

        e.exhaustCollector += straightPipe.out0;

        float mufflerElements = (float) e.muffler.mufflerElements.length;

        for (WaveGuide mufflerDelayLine : e.muffler.mufflerElements) {
            mufflerDelayLine.push(straightPipe.out1 / mufflerElements, 0.0f);
        }

        engineVibration = e.engineVibrationFilter.filter(engineVibration);

        genIntake = e.intakeCollector;
        genVibration = engineVibration;
        genExhaust = muffler1;
        genDampened = straightPipe.dampened | cylinderDampened;
        // upstream ignores the muffler elements' dampened flag here (muffler_wg_ret.2), as do we
    }
}
