package ves.core;

/** Test access to PackVoice's per-loop level-matching gains (package-private in ves.core). */
public final class PackVoiceProbe {
    /** the gains of these loops as one layer of their own (one loop: its raw-RMS gain) */
    public double[] gains(Pack.Loop[] loops) {
        return PackVoice.gains(new Pack.Loop[][] {loops}, PackLoader.RATE)[0];
    }

    /** the gains the pack plays its top (on) layer with (0.2.11: speaker-weighted across the whole pack, level trim in) */
    public double[] onGains(Pack p) {
        return p.gains[p.gains.length - 1];
    }

    /** y through the output stage's filters (high-pass + presence): the level on the speaker */
    public float[] speaker(float[] y) {
        OutputStage st = new OutputStage(PackLoader.RATE);
        float[] o = new float[y.length];
        for (int i = 0; i < y.length; i++) o[i] = (float) st.filter(y[i]);
        return o;
    }
}
