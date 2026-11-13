package ves.core;

/**
 * A loaded sound pack: loops recorded (or rendered) at fixed rpm, on-load (throttle) and off-load (overrun),
 * PCM16 mono at 22050 Hz, sorted by rpm. Immutable once loaded; PackVoice plays it.
 */
public final class Pack {
    public static final class Loop {
        public final int rpm;
        /** the loop, start..end of its file: plays end to start without a seam */
        public final short[] pcm;

        Loop(int rpm, short[] pcm) {
            this.rpm = rpm;
            this.pcm = pcm;
        }
    }

    public final String name;
    public final int cylinders, idleRpm, redlineRpm;
    /**
     * every loop starts at the same crank angle and holds whole engine cycles (rendered packs): PackVoice keeps
     * all loops on one engine-cycle phase and crossfades linearly. false (recordings): free-running loops,
     * constant-power crossfade.
     */
    public final boolean aligned;
    /**
     * the pack's level trim (pack.txt "level <dB>", -24..12; 1 without one): PackVoice multiplies every loop's
     * level-matching gain by it. Loops are matched on raw RMS, but the output stage lifts 400-2500 Hz by 11 dB,
     * so a pack with its energy there (resynthesized recordings) sounds louder and drives the soft clip harder.
     */
    public final double level;
    /** on-load loops by rising rpm */
    public final Loop[] on;
    /** off-load (overrun) loops by rising rpm; the same array as on when the pack has none */
    public final Loop[] off;
    /**
     * each loop's final gain (PackVoice.gains, the level matching, times level), worked out here on the loader
     * thread: PackVoice.setPack runs on the audio thread and only takes them. Not to be changed.
     */
    final double[] gainOn, gainOff;

    Pack(String name, int cylinders, int idleRpm, int redlineRpm, boolean aligned, double level, Loop[] on, Loop[] off) {
        this.aligned = aligned;
        this.level = level;
        this.name = name;
        this.cylinders = cylinders;
        this.idleRpm = idleRpm;
        this.redlineRpm = redlineRpm;
        this.on = on;
        this.off = off;
        gainOn = PackVoice.gains(on);
        gainOff = off == on ? gainOn : PackVoice.gains(off);
        for (int k = 0; k < gainOn.length; k++) gainOn[k] *= level;
        if (gainOff != gainOn) for (int k = 0; k < gainOff.length; k++) gainOff[k] *= level;
    }
}
