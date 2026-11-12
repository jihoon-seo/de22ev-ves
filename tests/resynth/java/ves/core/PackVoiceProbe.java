package ves.core;

/** Test access to PackVoice's per-loop level-matching gains (package-private in ves.core). */
public final class PackVoiceProbe {
    public double[] gains(Pack.Loop[] loops) {
        return PackVoice.gains(loops);
    }
}
