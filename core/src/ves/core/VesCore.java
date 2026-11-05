package ves.core;

/**
 * Facade for the Android service: hscan records into signals, and renderBlock() for the audio thread.
 *
 * renderBlock(): model update, then AudioPolicy.tick (wantSound = enabled and volume &gt; 0 and the model or
 * the envelope is non-zero; currentGain = previous block's gain), then the gain chain
 * envelope(targetGain * volumeToLinear(volume)) * policy.yieldGain (the yield fade is applied after the envelope so
 * a prompt silences us within 120 ms), then the synth. While the path is closed the output is zeros and the envelope
 * is reset (it re-attacks after the next OPEN). Returns the policy action for the caller to execute.
 *
 * Threading: setSettings/onYield* may be called from any thread; renderBlock from one audio thread; the framer
 * from one reader thread (SignalState and AudioPolicy are synchronized).
 */
public final class VesCore {
    private final int sampleRate;
    private final SignalState signals = new SignalState();
    private final EvDecoder decoder = new EvDecoder(signals);
    private final HscanFramer framer = new HscanFramer(decoder);
    private final AudioPolicy policy = new AudioPolicy();
    private final SharedChannel channel = new SharedChannel(); // guarded by itself
    private final GainEnvelope env;
    private final Synth synth;
    private final EngineModel model;
    private final EngineParams params = new EngineParams();
    private final Pops pops;
    private final PackVoice packVoice;
    private volatile Pack pack; // set by the service after loading the slot that sound names (null: none)
    private boolean usingPack; // the voice of the last block
    private int xfadeLeft; // frames of the voice switch still to cross-fade
    private final short[] xbuf = new short[4096];
    static final int XFADE_MS = 80;
    private volatile Settings settings = new Settings();
    private Profile current;
    private double lastGain;
    private long lastNow;
    private int lastAction;

    public VesCore(int sampleRate) {
        this.sampleRate = sampleRate > 0 ? sampleRate : 22050;
        env = new GainEnvelope(this.sampleRate);
        current = settings.profile;
        model = new EngineModel(current);
        synth = new Synth(this.sampleRate, current, 0x5EED);
        pops = new Pops(this.sampleRate, 0x9095);
        synth.setPops(pops);
        packVoice = new PackVoice(this.sampleRate);
    }

    public int sampleRate() {
        return sampleRate;
    }

    public SignalState signals() {
        return signals;
    }

    public RecordListener recordListener() {
        return decoder;
    }

    public HscanFramer framer() {
        return framer;
    }

    public AudioPolicy policy() {
        return policy;
    }

    /** null is ignored; the settings are copied (later changes to s have no effect until set again). */
    public void setSettings(Settings s) {
        if (s == null) return;
        settings = s.copy();
    }

    public Settings settings() {
        return settings.copy();
    }

    /** another source speaks: duck under it (setting prompts=duck and a duckable source) or step aside */
    public void onYieldStart(String src, long now) {
        policy.onYieldStart(src, now, "duck".equals(settings.prompts) && AudioPolicy.duckable(src));
    }

    public void onYieldEnd(String src, long now) {
        policy.onYieldEnd(src, now);
        if (!"shared".equals(src)) {
            synchronized (channel) {
                channel.yieldEnded(now);
            }
        }
    }

    /**
     * A MICOM AudioStart/AudioEnd of our own stream type. Returns SharedChannel.OURS, FOREIGN_START,
     * FOREIGN_END or RESTORE. Someone else's start makes us step aside ("shared" yield, no endAudio);
     * someone else's end (or our channel closed under us) brings us back after the usual pause with a
     * fresh startAudio; a restore (MICOM putting our channel back after a VUI/prompt) is ours.
     */
    public int ownTypeEvent(boolean start, long now) {
        int who;
        synchronized (channel) {
            who = start ? channel.onStart(now) : channel.onEnd(now, policy.isOpen());
        }
        if (who == SharedChannel.FOREIGN_START) {
            onYieldStart("shared", now);
        } else if (who == SharedChannel.FOREIGN_END) {
            policy.onYieldEnd("shared", now); // its start, if it was seen
            policy.channelLost(now); // the channel is gone either way: close, open again when allowed
        }
        return who;
    }

    /** call before startAudio (its AudioStart may arrive before the call returns) */
    public void ownStartCalled(long now) {
        synchronized (channel) {
            channel.ownStartCalled(now);
        }
    }

    /** call before endAudio */
    public void ownEndCalled(long now) {
        synchronized (channel) {
            channel.ownEndCalled(now);
        }
    }

    /** true: endAudio now would cut another user's sound (or the channel is already closed) */
    public boolean skipEnd(long now) {
        synchronized (channel) {
            return channel.skipEnd(now);
        }
    }

    public int renderBlock(short[] out, int frames, long nowMs) {
        if (out == null) return ACT_NONE;
        if (frames > out.length) frames = out.length;
        if (frames < 0) frames = 0;
        Settings s = settings;
        if (s.profile != current) {
            current = s.profile;
            model.setProfile(current);
            synth.setProfile(current);
        }
        lastNow = nowMs;
        model.update(signals, s, nowMs, params);
        pops.setLevel(Pops.level(s.pops));
        pops.update(params.rpm, params.load, params.speedKph, nowMs);
        boolean enabled = s.enabled && s.volume > 0;
        double target = enabled ? params.targetGain * GainEnvelope.volumeToLinear(s.volume) : 0;
        boolean want = enabled && (target > 0 || env.value() > 0);
        int action = policy.tick(nowMs, want, lastGain);
        lastAction = action;
        double g;
        if (!policy.isOpen()) {
            env.reset();
            g = 0;
        } else {
            g = env.next(target, frames) * policy.yieldGain(nowMs);
        }
        boolean usePack = pack != null && !"synth".equals(s.sound);
        if (usePack && packVoice.pack() != pack) packVoice.setPack(pack);
        if (usePack != usingPack) {
            usingPack = usePack;
            xfadeLeft = XFADE_MS * sampleRate / 1000;
        }
        Synth sy = synth;
        if (usePack) {
            synth.setPops(null);
            packVoice.setPops(pops);
            packVoice.render(out, 0, frames, params, g);
        } else {
            packVoice.setPops(null);
            synth.setPops(pops);
            synth.render(out, 0, frames, params, g);
        }
        if (xfadeLeft > 0 && frames <= xbuf.length) {
            // the other voice fades out under the new one (a convex mix: still inside the gain cap)
            if (usePack) sy.render(xbuf, 0, frames, params, g);
            else packVoice.render(xbuf, 0, frames, params, g);
            int total = XFADE_MS * sampleRate / 1000;
            for (int i = 0; i < frames; i++) {
                double wOld = Math.max(0, xfadeLeft - i) / (double) total;
                out[i] = (short) (int) (out[i] * (1 - wOld) + xbuf[i] * wOld);
            }
            xfadeLeft = Math.max(0, xfadeLeft - frames);
        }
        lastGain = g;
        return action;
    }

    private static final int ACT_NONE = AudioPolicy.ACT_NONE;

    public EngineParams lastParams() {
        EngineParams p = new EngineParams();
        p.copyFrom(params);
        return p;
    }

    /** the pack for the sound setting's slot, loaded by the service (null: none, the synth plays) */
    public void setPack(Pack p) {
        pack = p;
    }

    public String voice() {
        Pack p = pack;
        return usingPack && p != null ? "pack:" + p.name.replace(' ', '_') : "synth";
    }

    public Pops pops() {
        return pops;
    }

    public double lastGain() {
        return lastGain;
    }

    public int lastAction() {
        return lastAction;
    }

    public boolean isOpen() {
        return policy.isOpen();
    }

    /** one line, e.g. "state=drive rpm=2410 load=0.42 gain=0.18 open=1 yield=0 gear=3 kph=48.2" */
    public String status() {
        EngineParams p = params;
        StringBuilder b = new StringBuilder(96);
        b.append("state=").append(p.reason);
        b.append(" rpm=").append(Math.round(p.rpm));
        b.append(" load=").append(EngineParams.r2(p.load));
        b.append(" gain=").append(EngineParams.r2(lastGain));
        b.append(" open=").append(policy.isOpen() ? 1 : 0);
        b.append(" yield=").append(policy.yielding(lastNow) ? 1 : 0);
        String src = policy.activeSources(lastNow);
        if (src.length() > 0) b.append('(').append(src).append(')');
        b.append(" voice=").append(voice());
        if (policy.ducking(lastNow)) b.append(" duck=1");
        b.append(" gear=").append(p.virtualGear);
        if (p.shifting) b.append('*');
        b.append(" kph=").append(EngineParams.r2(p.speedKph));
        b.append(" profile=").append(current.key());
        return b.toString();
    }
}
