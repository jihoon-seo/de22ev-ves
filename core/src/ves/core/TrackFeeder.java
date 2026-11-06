package ves.core;

/**
 * What vesd's audio loop does next while its path is open (Vesd.audioLoop; TestMain L7 drives it against a
 * model of Android 4.4's AudioFlinger). One block is rendered at a time and written in parts as the pacer
 * allows, so the track's buffer can be filled exactly (it starts only when full).
 * <ul>
 * <li>RENDER: render the next block, then rendered();</li>
 * <li>n &gt; 0: write n frames of the block from offset(), then wrote(result);</li>
 * <li>WAIT: sleep a few ms and ask again;</li>
 * <li>RESET: the track has been full for a second and doesn't play, or a write failed: release it, make a new
 * one, newTrack(its frames), resetDone(now). Resets back off (1 s, doubling to 30 s) until a track plays;</li>
 * <li>DROP: no track plays and no reset is due: render a block anyway, throw it away (droppedBlock()) and
 * sleep a block's time. The engine model, the prompt policy and the status go on, and the path still closes.
 * (0.2.0-0.2.3 rendered nothing while waiting: all of it froze.)</li>
 * </ul>
 * Not thread-safe: the audio thread only (the counters are read by the status thread, as plain values).
 */
public final class TrackFeeder {
    public static final int RENDER = -1, WAIT = 0, RESET = -2, DROP = -3;
    static final long BACKOFF_MS = 1000, BACKOFF_MAX_MS = 30000;

    private final WritePacer pacer;
    private final int block;
    private int pending; // frames of the rendered block not written yet
    private long written;
    private boolean broken; // a write failed, or wrote nothing ZERO_WRITES times in a row
    private boolean dry; // the queue ran empty (counted once)
    private int zeros;
    static final int ZERO_WRITES = 50;
    private int fails; // resets since a track last played
    private long nextResetMs = Long.MIN_VALUE;
    private long lastHead = -1;
    private volatile long lastMoveMs = Long.MIN_VALUE, headStep, dropped, underruns, trackFrames;

    public TrackFeeder(WritePacer pacer, int block) {
        this.pacer = pacer;
        this.block = Math.max(1, block);
    }

    public int next(long head, long nowMs) {
        if (head != lastHead) {
            if (lastHead >= 0 && head > lastHead) {
                headStep = Math.max(headStep, head - lastHead);
                lastMoveMs = nowMs;
                fails = 0; // this track plays
            }
            lastHead = head;
        }
        if (broken) return resetDue(nowMs) ? RESET : DROP;
        int n = pacer.framesToWrite(written, head, nowMs); // also tracks whether the track plays
        if (pacer.stuck(nowMs)) return resetDue(nowMs) ? RESET : DROP;
        if (pending == 0) return RENDER;
        if (n <= 0) return WAIT;
        boolean dryNow = written > block * 4L && written - head <= 0;
        if (dryNow && !dry) { // once per time it runs dry
            underruns++;
            pacer.underrun();
        }
        dry = dryNow;
        return Math.min(n, pending);
    }

    /** whether a new track may be made now (also for a track that couldn't be created) */
    public boolean resetDue(long nowMs) {
        return nextResetMs == Long.MIN_VALUE || nowMs >= nextResetMs;
    }

    public void rendered() {
        pending = block;
    }

    public int offset() {
        return block - pending;
    }

    /** the result of the write: frames written, or a negative error (0: nothing; the caller waits a little) */
    public void wrote(int n) {
        if (n < 0) {
            broken = true;
            pending = 0;
            return;
        }
        if (n == 0) {
            if (++zeros >= ZERO_WRITES) broken = true;
            return;
        }
        zeros = 0;
        n = Math.min(n, pending);
        pending -= n;
        written += n;
    }

    public void droppedBlock() {
        pending = 0;
        dropped++;
    }

    /** a new (playing) track of frames: OPEN, or a reset */
    public void newTrack(int frames) {
        pacer.reset(frames);
        trackFrames = frames;
        written = 0;
        pending = 0;
        broken = false;
        dry = false;
        zeros = 0;
        lastHead = -1;
    }

    /** a reset was made (or tried): the next one waits longer until a track plays */
    public void resetDone(long nowMs) {
        long wait = BACKOFF_MS << Math.min(fails, 5);
        nextResetMs = nowMs + Math.min(BACKOFF_MAX_MS, wait);
        fails++;
    }

    /** the path opened with a new track: play_age counts from now until the track plays */
    public void opened(long nowMs) {
        lastMoveMs = nowMs;
    }

    /** ms since the play position last moved or the path opened (-1: neither yet) */
    public long playAge(long nowMs) {
        long m = lastMoveMs;
        return m == Long.MIN_VALUE ? -1 : nowMs - m;
    }

    public long written() {
        return written;
    }

    public long headStep() {
        return headStep;
    }

    public long dropped() {
        return dropped;
    }

    public long underruns() {
        return underruns;
    }

    public long trackFrames() {
        return trackFrames;
    }

    public WritePacer pacer() {
        return pacer;
    }
}
