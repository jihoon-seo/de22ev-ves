package ves.core;

/**
 * A loaded sound pack: loops recorded (or rendered) at fixed rpm in up to four load layers (on-load = throttle,
 * off-load = overrun, and loads between), PCM16 mono at 22050 Hz, sorted by rpm. Immutable once loaded;
 * PackVoice plays it.
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
    /**
     * the load of each layer, rising, the first 0 (0.2.8; two-layer packs: {0, 1}). Without a load-0 layer in
     * pack.txt the lowest layer stands in for it: layers[0] is then the same array as layers[1].
     */
    public final double[] loads;
    /** the loops of each layer by rising rpm, one per loads[k] */
    public final Loop[][] layers;
    /** the top (on-load) layer */
    public final Loop[] on;
    /** the load-0 (overrun) layer; the same array as the lowest layer above it when the pack has none */
    public final Loop[] off;
    /**
     * each loop's final gain per layer (PackVoice.gains, the level matching, times level), worked out here on the
     * loader thread: PackVoice.setPack runs on the audio thread and only takes them. Not to be changed.
     */
    final double[][] gains;

    Pack(String name, int cylinders, int idleRpm, int redlineRpm, boolean aligned, double level, double[] loads, Loop[][] layers) {
        this.aligned = aligned;
        this.level = level;
        this.name = name;
        this.cylinders = cylinders;
        this.idleRpm = idleRpm;
        this.redlineRpm = redlineRpm;
        this.loads = loads;
        this.layers = layers;
        on = layers[layers.length - 1];
        off = layers[0];
        gains = new double[layers.length][];
        for (int k = 0; k < layers.length; k++) {
            if (k > 0 && layers[k] == layers[k - 1]) {
                gains[k] = gains[k - 1];
                continue;
            }
            gains[k] = PackVoice.gains(layers[k]);
            for (int i = 0; i < gains[k].length; i++) gains[k][i] *= level;
        }
    }
}
