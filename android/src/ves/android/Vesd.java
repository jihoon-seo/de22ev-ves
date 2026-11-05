package ves.android;

import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.Map;

import ves.core.AudioPolicy;
import ves.core.Settings;
import ves.core.SharedChannel;
import ves.core.Sig;
import ves.core.SignalState;
import ves.core.VehicleText;
import ves.core.WritePacer;
import ves.core.VesCore;
import ves.hmg.HmgClient;
import ves.hmg.Prefs;

/**
 * vesd: the virtual engine sound service (HMG-API mod service, app_process,
 * user system). Reads the car's data stream (hscan_mux; HMG-API vehicle readings
 * as a fallback), renders the sound with ves.core and plays it on Android stream
 * 12 (navigation), opening the MICOM's mixing channel only while there is sound and
 * no voice (navigation prompt, call, voice recognition ...) is speaking.
 */
public final class Vesd implements MicomAudio.Events {
    static final int RATE = 22050, BLOCK = 220; // 10 ms
    static final int ANDROID_STREAM_NAVI = 12;

    final VesCore core = new VesCore(RATE);
    final MicomAudio micom = new MicomAudio();
    final HmgClient hmg = new HmgClient(5000);
    final HscanReader hscan = new HscanReader(core.framer(), core);
    final Probe probe = new Probe(this);
    final PackStore packs = PackStore.fromEnv();
    volatile String packSlot = ""; // the slot whose pack is loaded (or was tried)
    volatile String packNote = "";

    volatile int ownType = MicomAudio.NAVI_SOUNDEFFECT;
    volatile boolean pathOpen;
    volatile String settingsText = "";
    volatile String lastError = "";
    volatile boolean fallbackActive;
    volatile long fallbackPolls;
    volatile long underruns, opens, closes, blocks;
    volatile WritePacer pacer;
    volatile boolean exclusive; // a probe owns the audio path
    volatile Settings override; // a probe's settings (null: the owner's)
    volatile boolean skipStartupClose; // probe stuck test: leave a crashed holder's channel as it is, once
    private Settings ownerSettings = Settings.fromMap(new java.util.HashMap<String, String>());

    public static void main(String[] args) {
        Out.log("vesd starting (rate " + RATE + ")");
        try {
            new Vesd().run(args);
        } catch (Throwable t) {
            Out.log("vesd: fatal " + t);
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }

    void run(String[] args) throws Exception {
        if (!micom.connect()) Out.log("micom: " + MicomAudio.SERVICE + " not found (will retry)");
        loadSettings(true);
        boolean skipCleanup = probe.consumeStuckFlag();
        if (!skipCleanup) cleanupPath("startup");
        else {
            skipStartupClose = true;
            Out.log("startup: cleanup skipped once (probe stuck test)");
        }
        registerListener();
        thread("hscan", hscan);
        thread("settings", new Runnable() {
            public void run() {
                while (true) {
                    SystemClock.sleep(2000);
                    loadSettings(false);
                    if (!micom.alive()) {
                        if (micom.connect()) registerListener();
                    }
                }
            }
        });
        thread("fallback", new Runnable() {
            public void run() {
                fallbackLoop();
            }
        });
        thread("control", new Control(this));
        audioLoop();
    }

    static Thread thread(String name, Runnable r) {
        Thread t = new Thread(r, "ves-" + name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ---- settings

    void loadSettings(boolean first) {
        try {
            String t = hmg.settingsJson();
            if (t == null) {
                if (first) Out.log("settings: hmgapid refused (is the mod enabled?)");
                return;
            }
            if (t.equals(settingsText)) return;
            settingsText = t;
            JSONObject o = new JSONObject(t.length() == 0 ? "{}" : t);
            Map<String, String> m = Prefs.toMap(o);
            Settings s = Settings.fromMap(m);
            int type = parseType(m.get(Prefs.AUDIO_TYPE), MicomAudio.NAVI_SOUNDEFFECT);
            synchronized (core) {
                ownerSettings = s;
                if (override == null) core.setSettings(s);
            }
            if (!s.sound.equals(packSlot)) loadPack(s.sound);
            if (type != ownType) {
                cleanupPath("audio type change");
                ownType = type;
                micom.setOwnType(type);
            }
            Out.log("settings: " + s + " type=0x" + Integer.toHexString(type));
        } catch (Throwable e) {
            lastError = "settings: " + e;
            if (first) Out.log(lastError);
        }
    }

    /** loads the pack of a slot off the audio thread; the synth plays until (or unless) it is ready */
    void loadPack(final String slot) {
        packSlot = slot;
        if ("synth".equals(slot)) {
            core.setPack(null);
            packNote = "";
            return;
        }
        thread("pack", new Runnable() {
            public void run() {
                try {
                    ves.core.Pack p = packs.load(slot);
                    if (!slot.equals(packSlot)) return; // changed meanwhile
                    core.setPack(p);
                    packNote = slot + ": " + p.name;
                    Out.log("pack: " + packNote);
                } catch (Throwable e) {
                    if (!slot.equals(packSlot)) return;
                    core.setPack(null);
                    packNote = slot + ": " + e.getMessage() + " (synth plays)";
                    Out.log("pack: " + packNote);
                }
            }
        });
    }

    static int parseType(String v, int def) {
        if (v == null) return def;
        try {
            v = v.trim();
            int t = v.startsWith("0x") ? Integer.parseInt(v.substring(2), 16) : Integer.parseInt(v);
            switch (t) {
                // 0x39 (TTS effect) takes the music source over and 0x3 (navigation voice) reports a
                // navigation prompt (car check 2026-11-05): not used, an old setting falls back to 0x45
                case MicomAudio.NAVI_SOUNDEFFECT:
                case MicomAudio.SYSTEM_ALARM:
                    return t;
                default:
                    return def;
            }
        } catch (NumberFormatException e) {
            return def;
        }
    }

    void setOverride(Settings s) {
        synchronized (core) {
            override = s;
            core.setSettings(s != null ? s : ownerSettings);
        }
    }

    Settings ownerSettings() {
        synchronized (core) {
            return ownerSettings;
        }
    }

    // ---- MICOM listener

    void registerListener() {
        try {
            micom.register(this, ownType);
            Out.log("micom: listener registered");
        } catch (Throwable e) {
            lastError = "micom register: " + e;
            Out.log(lastError);
        }
    }

    public void onYield(String source, boolean start, String raw) {
        long now = SystemClock.uptimeMillis();
        synchronized (core) {
            if (start) core.onYieldStart(source, now);
            else core.onYieldEnd(source, now);
        }
        Out.log("micom: " + raw + " -> " + (start ? "yield " : "resume ") + source);
        probe.event(raw + (start ? " yield " : " end ") + source);
    }

    public void onOther(String raw) {
        probe.event(raw);
    }

    /**
     * The navigation plays its effect sounds on our type (0x45) too. Its start: step aside and leave the
     * channel to it (no endAudio, which would cut its chime); its end, or the channel closed under our open
     * path: step back in after the usual pause, with a fresh startAudio.
     */
    public void onOwnType(boolean start, String raw) {
        long now = SystemClock.uptimeMillis();
        int who = core.ownTypeEvent(start, now);
        if (who == SharedChannel.OURS) {
            probe.event(raw + " (ours)");
            return;
        }
        if (who == SharedChannel.RESTORE) {
            Out.log("micom: " + raw + " -> channel restored (ours)");
            probe.event(raw + " restore");
            return;
        }
        Out.log("micom: " + raw + " by another user -> " + (start ? "yield" : "resume") + " shared");
        probe.event(raw + (start ? " foreign yield shared" : " foreign end shared"));
    }

    // ---- the path

    void openPath(String why) {
        try {
            core.ownStartCalled(SystemClock.uptimeMillis()); // before the call: the event may beat its return
            boolean ok = micom.startAudio(ownType);
            pathOpen = true;
            opens++;
            Out.log("path: startAudio(0x" + Integer.toHexString(ownType) + ")=" + ok + " (" + why + ")");
            probe.event("startAudio 0x" + Integer.toHexString(ownType) + " " + ok);
        } catch (Throwable e) {
            lastError = "startAudio: " + e;
            Out.log(lastError);
        }
    }

    void cleanupPath(String why) {
        try {
            boolean skip = core.skipEnd(SystemClock.uptimeMillis()) && !"startup".equals(why);
            if (!skip) core.ownEndCalled(SystemClock.uptimeMillis());
            if (skip) {
                // another user holds the channel (or already closed it): endAudio would cut its sound
                if (pathOpen) Out.log("path: closed without endAudio (" + why + "; the channel is another user's)");
                probe.event("endAudio skipped " + why);
            } else {
                boolean ok = micom.endAudio(ownType);
                if (pathOpen || !"startup".equals(why)) Out.log("path: endAudio(0x" + Integer.toHexString(ownType) + ")=" + ok + " (" + why + ")");
                probe.event("endAudio 0x" + Integer.toHexString(ownType) + " " + ok + " " + why);
            }
        } catch (Throwable e) {
            lastError = "endAudio: " + e;
        }
        if (pathOpen) closes++;
        pathOpen = false;
    }

    // ---- fallback: HMG-API's readings when the stream is silent

    void fallbackLoop() {
        while (true) {
            long now = SystemClock.uptimeMillis();
            boolean stale = !hscan.connected || now - hscan.lastDataMs > 1000;
            if (!stale || hscan.paused) {
                if (fallbackActive) {
                    Out.log("fallback: off (stream is back)");
                    VehicleText.setFallbackStale(core.signals(), false, 0);
                }
                fallbackActive = false;
                SystemClock.sleep(500);
                continue;
            }
            if (!fallbackActive) Out.log("fallback: on (no hscan data)");
            fallbackActive = true;
            try {
                long t0 = SystemClock.uptimeMillis();
                HmgClient.Answer a = hmg.vehicleAnswer("speed", "pedal.accel", "hv.ready", "gear.park", "brake.pressure");
                Map<String, String> v = VehicleText.parse(a.code, a.text);
                long t = SystemClock.uptimeMillis();
                fallbackPolls++;
                if (v.isEmpty()) lastError = "fallback: hmgapid code " + a.code + ": " + a.text.trim();
                // one poll takes ~3.2 s on the car: keep the readings fresh until the next one
                VehicleText.setFallbackStale(core.signals(), true, t - t0);
                synchronized (core) {
                    SignalState s = core.signals();
                    put(s, Sig.SPEED_KPH, v.get("speed"), t);
                    put(s, Sig.PEDAL_PCT, v.get("pedal.accel"), t);
                    put(s, Sig.READY, v.get("hv.ready"), t);
                    put(s, Sig.BRAKE_BAR, v.get("brake.pressure"), t);
                    String p = v.get("gear.park");
                    // the fallback only knows "in P or not": not-P counts as D
                    if ("1".equals(p)) s.set(Sig.GEAR, Sig.GEAR_P, t);
                    else if ("0".equals(p)) s.set(Sig.GEAR, Sig.GEAR_D, t);
                }
            } catch (Throwable e) {
                lastError = "fallback: " + e;
            }
            SystemClock.sleep(200); // a poll already takes seconds on the car
        }
    }

    static void put(SignalState s, int sig, String v, long t) {
        if (v == null || v.equals("-")) return;
        try {
            s.set(sig, Double.parseDouble(v), t);
        } catch (NumberFormatException ignored) {
        }
    }

    // ---- the sound

    void audioLoop() {
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bytes = Math.max(min, RATE / 10 * 2); // >= 100 ms
        AudioTrack track = null;
        short[] buf = new short[BLOCK];
        boolean playing = false;
        long written = 0;
        // keep ~80 ms queued, not the whole 186 ms minimum buffer (car check 2: response "a little late")
        pacer = new WritePacer(RATE * 80 / 1000, BLOCK, bytes / 2);
        while (true) {
            if (playing && track != null && !exclusive) {
                long head = track.getPlaybackHeadPosition() & 0xffffffffL;
                if (pacer.framesToWrite(written, head) == 0) {
                    SystemClock.sleep(4);
                    continue;
                }
                if (written > BLOCK * 4 && written - head <= 0) {
                    underruns++;
                    pacer.underrun();
                }
            }
            long now = SystemClock.uptimeMillis();
            int act;
            synchronized (core) {
                act = core.renderBlock(buf, BLOCK, now);
            }
            blocks++;
            if (exclusive) {
                // a probe test owns the path: keep the model running, stay closed and quiet
                if (pathOpen) cleanupPath("probe");
                if (playing && track != null) {
                    track.pause();
                    track.flush();
                    playing = false;
                }
                SystemClock.sleep(10);
                continue;
            }
            if (act == AudioPolicy.ACT_OPEN) {
                if (track == null) {
                    try {
                        track = new AudioTrack(ANDROID_STREAM_NAVI, RATE, AudioFormat.CHANNEL_OUT_MONO,
                                AudioFormat.ENCODING_PCM_16BIT, bytes, AudioTrack.MODE_STREAM);
                        Out.log("track: stream 12, " + bytes + " bytes, state " + track.getState());
                    } catch (Throwable e) {
                        lastError = "AudioTrack: " + e;
                        Out.log(lastError);
                        track = null;
                    }
                }
                openPath("sound");
                if (track != null) {
                    track.play();
                    playing = true;
                    written = 0;
                }
            } else if (act == AudioPolicy.ACT_CLOSE && skipStartupClose && !pathOpen) {
                skipStartupClose = false; // the core's first-tick cleanup, skipped for the stuck test
            } else if (act == AudioPolicy.ACT_CLOSE) {
                if (playing && track != null) {
                    track.pause();
                    track.flush();
                }
                playing = false;
                cleanupPath(pathOpen ? "silence or voice" : "startup");
            }
            if (playing && track != null) {
                int n = track.write(buf, 0, BLOCK);
                if (n > 0) written += n;
                else SystemClock.sleep(10);
            } else {
                SystemClock.sleep(10);
            }
        }
    }

    String status() {
        StringBuilder b = new StringBuilder();
        synchronized (core) {
            b.append(core.status()).append('\n');
            SignalState s = core.signals();
            long now = SystemClock.uptimeMillis();
            b.append("signals");
            for (int i = 0; i < Sig.COUNT; i++) b.append(' ').append(Sig.name(i)).append('=').append(fmt(s.get(i, now)));
            b.append('\n');
            b.append("settings ").append(core.settings()).append(override != null ? " (probe)" : "").append('\n');
        }
        b.append("path open=").append(pathOpen ? 1 : 0).append(" type=0x").append(Integer.toHexString(ownType))
                .append(" opens=").append(opens).append(" closes=").append(closes).append(" underruns=").append(underruns)
                .append(" queue_target_ms=").append(pacer == null ? -1 : pacer.target() * 1000 / RATE).append('\n');
        b.append("hscan connected=").append(hscan.connected ? 1 : 0).append(" bytes=").append(hscan.bytes)
                .append(" age_ms=").append(hscan.lastDataMs == 0 ? -1 : SystemClock.uptimeMillis() - hscan.lastDataMs)
                .append(" fallback=").append(fallbackActive ? 1 : 0).append(" polls=").append(fallbackPolls).append('\n');
        if (packNote.length() > 0) b.append("pack ").append(packNote).append('\n');
        b.append("micom alive=").append(micom.alive() ? 1 : 0).append('\n');
        b.append("probe ").append(probe.state()).append('\n');
        if (lastError.length() > 0) b.append("last_error ").append(lastError).append('\n');
        return b.toString();
    }

    static String fmt(double v) {
        if (Double.isNaN(v)) return "-";
        return String.valueOf(Math.round(v * 100) / 100.0);
    }
}
