/*
 * ves.render: offline engine-sound renderer (host tool, not shipped to the car).
 * Ported from DasEtwas/enginesound <https://github.com/DasEtwas/enginesound>, src/gen.rs,
 * upstream commit e5fcca587397c0c8ba9c9d24874b951fed74d260.
 * Copyright (c) 2020 DasEtwas. MIT License; the full notice is in THIRD_PARTY.md.
 * The Java port and its throttle extension are MIT as well.
 */
package ves.render;

/**
 * gen.rs Cylinder: one audio cylinder with two WaveGuides from the cylinder to the exhaust and
 * to the intake collector, plus an extractor WaveGuide from the exhaust to the exhaust collector.
 * <pre>
 * Labels:                                                     \/ Extractor
 *                    b      a            a      b           a    b
 * (Intake Collector) &lt;==|IV|&gt; (Cylinder) &lt;|EV|==&gt; (Exhaust) &lt;====&gt; (Exhaust collector)
 *
 * a   b
 * &lt;===&gt;   - WaveGuide with alpha / beta sides =&gt; alpha controls the reflectiveness of that side
 * |IV|    - Intake valve modulation function for this side of the WaveGuide (alpha)
 * |EV|    - Exhaust valve modulation function for this side of the WaveGuide (alpha)
 * </pre>
 */
final class Cylinder {
    /** offset of this cylinder's piston crank (fraction of the 720 degree cycle) */
    final float crankOffset;
    /** waveguide from the cylinder to the exhaust */
    final WaveGuide exhaustWaveguide;
    /** waveguide from the cylinder to the intake */
    final WaveGuide intakeWaveguide;
    /** waveguide from the other end of the exhaust WG to the exhaust collector */
    final WaveGuide extractorWaveguide;
    // waveguide alpha values for when the valves are closed or opened
    final float intakeOpenRefl;
    final float intakeClosedRefl;
    final float exhaustOpenRefl;
    final float exhaustClosedRefl;

    final float pistonMotionFactor;
    final float ignitionFactor;
    /** the time it takes for the fuel to ignite in crank cycles (0.0 - 1.0) */
    final float ignitionTime;

    // running values
    float cylSound;
    float extractorExhaust;

    // pop() "tuple": (intake, exhaust, piston + ignition, waveguide dampened)
    float popIntake;
    float popExhaust;
    float popSound;
    boolean popDampened;

    Cylinder(float crankOffset, WaveGuide exhaustWaveguide, WaveGuide intakeWaveguide,
            WaveGuide extractorWaveguide, float intakeOpenRefl, float intakeClosedRefl,
            float exhaustOpenRefl, float exhaustClosedRefl, float pistonMotionFactor,
            float ignitionFactor, float ignitionTime) {
        this.crankOffset = crankOffset;
        this.exhaustWaveguide = exhaustWaveguide;
        this.intakeWaveguide = intakeWaveguide;
        this.extractorWaveguide = extractorWaveguide;
        this.intakeOpenRefl = intakeOpenRefl;
        this.intakeClosedRefl = intakeClosedRefl;
        this.exhaustOpenRefl = exhaustOpenRefl;
        this.exhaustClosedRefl = exhaustClosedRefl;
        this.pistonMotionFactor = pistonMotionFactor;
        this.ignitionFactor = ignitionFactor;
        this.ignitionTime = ignitionTime;
    }

    /**
     * Takes in the current exhaust collector pressure; sets the pop* fields.
     * Extension: pistonScale and ignitionScale come from the throttle (exactly 1 at throttle 1).
     */
    void pop(float crankPos, float exhaustCollector, float intakeValveShift,
            float exhaustValveShift, float pistonScale, float ignitionScale) {
        float crank = Gen.fract(crankPos + crankOffset);

        cylSound = Gen.pistonMotion(crank) * pistonMotionFactor * pistonScale
                + Gen.fuelIgnition(crank, ignitionTime) * ignitionFactor * ignitionScale;

        float exValve = Gen.exhaustValve(Gen.fract(crank + exhaustValveShift));
        float inValve = Gen.intakeValve(Gen.fract(crank + intakeValveShift));

        exhaustWaveguide.alpha = exhaustClosedRefl + (exhaustOpenRefl - exhaustClosedRefl) * exValve;
        intakeWaveguide.alpha = intakeClosedRefl + (intakeOpenRefl - intakeClosedRefl) * inValve;

        // the first value of a pop is the cylinder-side valve-modulated side of the waveguide (alpha side)
        exhaustWaveguide.pop();
        intakeWaveguide.pop();

        extractorWaveguide.pop();
        extractorExhaust = extractorWaveguide.out0;
        extractorWaveguide.push(exhaustWaveguide.out1, exhaustCollector);

        popIntake = intakeWaveguide.out1;
        popExhaust = extractorWaveguide.out1;
        popSound = cylSound;
        popDampened = exhaustWaveguide.dampened | intakeWaveguide.dampened | extractorWaveguide.dampened;
    }

    /** called after pop */
    void push(float intake) {
        float exIn = (1.0f - Math.abs(exhaustWaveguide.alpha)) * cylSound * 0.5f;
        exhaustWaveguide.push(exIn, extractorExhaust);
        float inIn = (1.0f - Math.abs(intakeWaveguide.alpha)) * cylSound * 0.5f;
        intakeWaveguide.push(inIn, intake);
    }
}
