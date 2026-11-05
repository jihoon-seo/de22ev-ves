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
    /** on-load loops by rising rpm */
    public final Loop[] on;
    /** off-load (overrun) loops by rising rpm; the same array as on when the pack has none */
    public final Loop[] off;

    Pack(String name, int cylinders, int idleRpm, int redlineRpm, boolean aligned, Loop[] on, Loop[] off) {
        this.aligned = aligned;
        this.name = name;
        this.cylinders = cylinders;
        this.idleRpm = idleRpm;
        this.redlineRpm = redlineRpm;
        this.on = on;
        this.off = off;
    }
}
