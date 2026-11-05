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
 *     (1500 ms), then yieldGain ramps back to 1 over RAMP_MS (200 ms). A yield without an end expires EXPIRY_MS
 *     (60 s) after its (last) start.</li>
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
    public static final long FADE_MS = 120, HOLDOFF_MS = 1500, RAMP_MS = 200, EXPIRY_MS = 60000,
            SILENCE_CLOSE_MS = 2000;
    static final double SILENT_GAIN = 1e-4;
    private static final int MAX_SOURCES = 16;

    private final String[] srcName = new String[MAX_SOURCES];
    private final long[] srcStart = new long[MAX_SOURCES];
    private final boolean[] srcActive = new boolean[MAX_SOURCES];

    private boolean firstDone, open;
    private boolean episode; // at least one yield is active
    private long episodeStart;
    private double episodeFrom = 1; // yieldGain at the start of the episode
    private boolean hadEnd;
    private long lastEnd;
    private long silentSince = Long.MIN_VALUE;
    private long opens, closes, yields;

    public synchronized void onYieldStart(String source, long nowMs) {
        String s = norm(source);
        expire(nowMs);
        double from = gainAt(nowMs);
        int i = find(s);
        if (i < 0) {
            i = freeSlot();
            srcName[i] = s;
        }
        srcStart[i] = nowMs;
        if (!srcActive[i]) yields++;
        srcActive[i] = true;
        if (!episode) {
            episode = true;
            episodeStart = nowMs;
            episodeFrom = from;
        }
    }

    public synchronized void onYieldEnd(String source, long nowMs) {
        String s = norm(source);
        expire(nowMs);
        int i = find(s);
        if (i < 0 || !srcActive[i]) return;
        srcActive[i] = false;
        if (!anyActive()) endEpisode(nowMs);
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
        return episode || (hadEnd && now - lastEnd < HOLDOFF_MS);
    }

    private int close() {
        open = false;
        closes++;
        return ACT_CLOSE;
    }

    private double gainAt(long now) {
        if (episode) {
            double g = episodeFrom - (now - episodeStart) / (double) FADE_MS;
            return g <= 0 ? 0 : g > 1 ? 1 : g;
        }
        if (!hadEnd) return 1;
        long t = now - lastEnd - HOLDOFF_MS;
        if (t < 0) return 0;
        if (t >= RAMP_MS) return 1;
        return t / (double) RAMP_MS;
    }

    private void endEpisode(long t) {
        episode = false;
        hadEnd = true;
        lastEnd = t;
    }

    private void expire(long now) {
        if (!episode) return;
        long latestExpiry = Long.MIN_VALUE;
        boolean any = false;
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
        if (!any) endEpisode(latestExpiry == Long.MIN_VALUE ? now : latestExpiry);
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
