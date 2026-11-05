package ves.core;

/**
 * Path state machine for the head unit's shared navigation audio channel.
 *
 * <ul>
 * <li>The very first tick() returns ACT_CLOSE once (cleanup of a path left open by a previous process).</li>
 * <li>ACT_OPEN only when wantSound and not yielding; never two OPENs without a CLOSE in between.</li>
 * <li>Yield (another source owns the channel: navi, tts, ring, vr, vui, btvoice, dipo, foreign, ...):
 *     yieldGain() fades linearly to 0 within FADE_MS (120 ms) of the start; once it is 0 the next tick returns
 *     ACT_CLOSE. Sources are tracked separately (the end of one does not end another; a repeated start of the same
 *     source is one yield). After the last end, yielding() stays true and yieldGain() stays 0 for HOLDOFF_MS
 *     (500 ms), then yieldGain ramps back to 1 over RAMP_MS (200 ms). A yield without an end expires EXPIRY_MS
 *     (60 s) after its (last) start.</li>
 * <li>Ducking (0.2.2): a yield started with duck=true (navigation prompts, alerts, TTS: see duckable()) lowers
 *     yieldGain() to DUCK_GAIN within FADE_MS and keeps the path open; after it ends the gain ramps back over
 *     RAMP_MS with no hold-off. Any non-duck source (call, ringing, voice recognition ...) turns the episode into
 *     a full yield. Never an OPEN while any yield is active: a startAudio could cut the prompt.</li>
 * <li>channelLost(): the channel was closed under us (MICOM preempted it): the next tick returns ACT_CLOSE.</li>
 * <li>ACT_CLOSE after SILENCE_CLOSE_MS (2000 ms) of continuous silence (wantSound false or gain ~0).</li>
 * </ul>
 * Own echo: the caller must not report the VES's own playback (its own audio-focus / stream-type / mixer
 * callbacks caused by our OPEN) as a yield. This class cannot tell the sources apart; it trusts every
 * onYieldStart.
 * Thread-safe (synchronized): yields arrive from binder/listener threads, tick() from the audio thread.
 */
public final class AudioPolicy {
    public static final int ACT_NONE = 0, ACT_OPEN = 1, ACT_CLOSE = 2;
    /** the navigation's chimes on our shared channel are short: a "shared" yield whose end is lost expires sooner */
    public static final long SHARED_EXPIRY_MS = 5000;
    // HOLDOFF 0.5 s since 0.2.2 (owner after the first drive: back sooner after a prompt; was 1.5 s)
    public static final long FADE_MS = 120, HOLDOFF_MS = 500, RAMP_MS = 200, EXPIRY_MS = 60000,
            SILENCE_CLOSE_MS = 2000;
    static final double SILENT_GAIN = 1e-4;
    /** the level under a prompt when ducking (about the music's level under a navigation prompt) */
    public static final double DUCK_GAIN = 0.3;
    private static final int MAX_SOURCES = 16;

    private final String[] srcName = new String[MAX_SOURCES];
    private final long[] srcStart = new long[MAX_SOURCES];
    private final boolean[] srcActive = new boolean[MAX_SOURCES];
    private final boolean[] srcDuck = new boolean[MAX_SOURCES];
    private boolean lastEpisodeDuck, lostPending;
    private long lostAt = Long.MIN_VALUE / 2;
    private double endGain;

    private boolean firstDone, open;
    private boolean episode; // at least one yield is active
    private long episodeStart;
    private double episodeFrom = 1; // yieldGain at the start of the episode
    private boolean hadEnd;
    private long lastEnd;
    private long silentSince = Long.MIN_VALUE;
    private long opens, closes, yields;

    public synchronized void onYieldStart(String source, long nowMs) {
        onYieldStart(source, nowMs, false);
    }

    /** duck: play on quietly under this source instead of stepping aside (only if every active source ducks) */
    public synchronized void onYieldStart(String source, long nowMs, boolean duck) {
        String s = norm(source);
        expire(nowMs);
        double from = gainAt(nowMs);
        boolean wasDuck = episode && allDuck();
        int i = find(s);
        if (i < 0) {
            i = freeSlot();
            srcName[i] = s;
        }
        srcStart[i] = nowMs;
        if (!srcActive[i]) yields++;
        srcActive[i] = true;
        srcDuck[i] = duck;
        if (!episode) {
            episode = true;
            episodeStart = nowMs;
            episodeFrom = from;
        } else if (wasDuck && !duck) {
            // a call during a ducked prompt: from the ducked level down to silence
            episodeStart = nowMs;
            episodeFrom = from;
        }
    }

    /** the channel was closed under us (MICOM preempted it for a prompt): close now, open again when allowed */
    public synchronized void channelLost(long nowMs) {
        lostPending = true;
        lostAt = nowMs; // the prompt's own start follows a few ms later: wait HOLDOFF_MS before reopening
    }

    /** sources that only speak (prompts, alerts, TTS) and can have the engine sound under them */
    public static boolean duckable(String s) {
        if (s == null) return false;
        if (s.equals("navi") || s.equals("tts") || s.equals("shared") || s.equals("dipo_alt")) return true;
        if (!s.startsWith("a")) return false;
        try {
            int t = Integer.parseInt(s.substring(1));
            // NAVI, TTS, GUIDE, AANAVI, MIRRORLINK_NAVI, PREVIEWAUDIOTTS, CARLIFE_NAVI, TTS_EFFECT, NAVI_SOUNDEFFECT,
            // SYSTEM_ALARM
            return t == 0x3 || t == 0x7 || t == 0xa || t == 0x12 || t == 0x19 || t == 0x1d || t == 0x24 || t == 0x39
                    || t == 0x45 || t == 0x46;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean allDuck() {
        boolean any = false;
        for (int i = 0; i < MAX_SOURCES; i++) {
            if (!srcActive[i]) continue;
            any = true;
            if (!srcDuck[i]) return false;
        }
        return any;
    }

    public synchronized void onYieldEnd(String source, long nowMs) {
        String s = norm(source);
        expire(nowMs);
        int i = find(s);
        if (i < 0 || !srcActive[i]) return;
        boolean duck = allDuck();
        double g = gainAt(nowMs);
        srcActive[i] = false;
        if (!anyActive()) endEpisode(nowMs, duck, g);
        else if (!duck && allDuck()) {
            // the call ended, the prompt goes on: back up to the ducked level
            episodeStart = nowMs;
            episodeFrom = g;
        }
    }

    /** playing on quietly under a prompt (every active source ducks) */
    public synchronized boolean ducking(long nowMs) {
        expire(nowMs);
        return episode && allDuck();
    }

    public synchronized boolean yielding(long nowMs) {
        expire(nowMs);
        return yieldingNow(nowMs);
    }

    /** multiplier for the VES output: 1 normally, 0 while another source owns the channel */
    public synchronized double yieldGain(long nowMs) {
        expire(nowMs);
        return gainAt(nowMs);
    }

    public synchronized int tick(long nowMs, boolean wantSound, double currentGain) {
        if (!firstDone) {
            firstDone = true;
            open = false;
            closes++;
            return ACT_CLOSE;
        }
        expire(nowMs);
        if (lostPending) {
            lostPending = false;
            if (open) return close();
        }
        boolean silent = !wantSound || !(currentGain > SILENT_GAIN);
        if (!silent) silentSince = Long.MIN_VALUE;
        else if (silentSince == Long.MIN_VALUE) silentSince = nowMs;
        if (yieldingNow(nowMs)) {
            if (open && gainAt(nowMs) <= 0) return close();
            return ACT_NONE;
        }
        if (open) {
            if (silentSince != Long.MIN_VALUE && nowMs - silentSince >= SILENCE_CLOSE_MS) return close();
            return ACT_NONE;
        }
        if (nowMs - lostAt < HOLDOFF_MS) return ACT_NONE;
        if (wantSound) {
            open = true;
            opens++;
            // the envelope starts from 0 after an OPEN: the silence timer starts now
            silentSince = currentGain > SILENT_GAIN ? Long.MIN_VALUE : nowMs;
            return ACT_OPEN;
        }
        return ACT_NONE;
    }

    public synchronized boolean isOpen() {
        return open;
    }

    public synchronized long opens() {
        return opens;
    }

    /** includes the first-tick cleanup CLOSE */
    public synchronized long closes() {
        return closes;
    }

    /** number of yield starts of sources that were not already active */
    public synchronized long yields() {
        return yields;
    }

    /** active yield sources, comma separated (for status lines) */
    public synchronized String activeSources(long nowMs) {
        expire(nowMs);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < MAX_SOURCES; i++) {
            if (!srcActive[i]) continue;
            if (b.length() > 0) b.append(',');
            b.append(srcName[i]);
        }
        return b.toString();
    }

    private boolean yieldingNow(long now) {
        return episode || (hadEnd && !lastEpisodeDuck && now - lastEnd < HOLDOFF_MS);
    }

    private int close() {
        open = false;
        closes++;
        return ACT_CLOSE;
    }

    private double gainAt(long now) {
        if (episode) {
            boolean duck = allDuck();
            double floor = duck ? DUCK_GAIN : 0;
            double g;
            if (duck && episodeFrom < floor) { // back up from a call's silence to the ducked level
                g = episodeFrom + (now - episodeStart) / (double) RAMP_MS * (floor - episodeFrom);
                g = Math.min(floor, g);
            } else {
                g = Math.max(floor, episodeFrom - (now - episodeStart) / (double) FADE_MS);
            }
            return g <= 0 ? 0 : g > 1 ? 1 : g;
        }
        if (!hadEnd) return 1;
        if (lastEpisodeDuck) {
            double f = (now - lastEnd) / (double) RAMP_MS;
            return f >= 1 ? 1 : f <= 0 ? endGain : endGain + (1 - endGain) * f;
        }
        long t = now - lastEnd - HOLDOFF_MS;
        if (t < 0) return 0;
        if (t >= RAMP_MS) return 1;
        return t / (double) RAMP_MS;
    }

    private void endEpisode(long t, boolean duck, double g) {
        episode = false;
        hadEnd = true;
        lastEnd = t;
        lastEpisodeDuck = duck;
        endGain = g;
    }

    private void expire(long now) {
        if (!episode) return;
        long latestExpiry = Long.MIN_VALUE;
        boolean any = false;
        boolean duck = allDuck();
        double g0 = gainAt(now);
        for (int i = 0; i < MAX_SOURCES; i++) {
            if (!srcActive[i]) continue;
            long exp = srcStart[i] + ("shared".equals(srcName[i]) ? SHARED_EXPIRY_MS : EXPIRY_MS);
            if (now >= exp) {
                srcActive[i] = false;
                if (exp > latestExpiry) latestExpiry = exp;
            } else {
                any = true;
            }
        }
        if (!any) endEpisode(latestExpiry == Long.MIN_VALUE ? now : latestExpiry, duck, g0);
    }

    private boolean anyActive() {
        for (int i = 0; i < MAX_SOURCES; i++) if (srcActive[i]) return true;
        return false;
    }

    private int find(String s) {
        for (int i = 0; i < MAX_SOURCES; i++) if (s.equals(srcName[i])) return i;
        return -1;
    }

    private int freeSlot() {
        for (int i = 0; i < MAX_SOURCES; i++) if (srcName[i] == null) return i;
        for (int i = 0; i < MAX_SOURCES; i++) if (!srcActive[i]) return i;
        int o = 0; // all active: reuse the oldest
        for (int i = 1; i < MAX_SOURCES; i++) if (srcStart[i] < srcStart[o]) o = i;
        return o;
    }

    private static String norm(String s) {
        if (s == null) return "foreign";
        String t = s.trim().toLowerCase(java.util.Locale.US);
        return t.length() == 0 ? "foreign" : t;
    }
}
