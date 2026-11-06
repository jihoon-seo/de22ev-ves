package ves.core;

/**
 * Keeps only about target frames queued in the AudioTrack. Its minimum buffer on the car is 8192 bytes
 * (186 ms), and a blocking write fills it whole, which made the sound follow the pedal late (car check 2).
 * The audio loop asks before each block: write one block while the queue (written - played) is at most
 * target, else wait. Each underrun raises the target by half a block's worth of headroom (x1.25), up to
 * the whole buffer: a car that can't keep up gets the old behaviour back.
 */
public final class WritePacer {
    private final int block;
    private int max;
    private int target;
    private int underruns;

    /** frames: target queue, block size, the track's buffer */
    public WritePacer(int target, int block, int max) {
        this.block = Math.max(1, block);
        this.max = Math.max(this.block, max);
        this.target = Math.max(this.block, Math.min(target, this.max - this.block));
    }

    /** frames to write now: a block, or 0 (wait a little and ask again); positions in frames */
    public int framesToWrite(long written, long played) {
        long fill = written - played;
        return fill <= target ? block : 0;
    }

    /**
     * With the clock: Android 4.4's AudioFlinger starts a streaming track only once its whole buffer is full
     * (framesReady() >= frameCount), and after an underrun waits to be filled again. While the play position
     * doesn't move (STALL_MS), fill the buffer exactly to max, the last block in part (the caller writes the
     * rest of a block later); once it moves, pace to the target. (0.2.0-0.2.2 paced from the start, 0.2.3
     * filled whole blocks only, 3960 of 4096 frames: the track never started, vesd waited forever, stuck and
     * silent.) max must not be above the track's real frame count: a blocking write on a full track that
     * never starts would hang.
     */
    public int framesToWrite(long written, long played, long nowMs) {
        if (played != lastPlayed || lastMoveMs == Long.MIN_VALUE) {
            lastPlayed = played;
            lastMoveMs = nowMs;
        }
        boolean stalled = nowMs - lastMoveMs >= STALL_MS || played == 0;
        long fill = written - played;
        if (stalled) {
            if (fill < max) {
                fullSince = Long.MIN_VALUE;
                return (int) Math.min(block, max - fill);
            }
            if (fullSince == Long.MIN_VALUE) fullSince = nowMs;
            return 0;
        }
        fullSince = Long.MIN_VALUE;
        return fill <= target ? block : 0;
    }

    /** the buffer has been full for STUCK_MS and the track still doesn't play: the caller makes a new one */
    public boolean stuck(long nowMs) {
        return fullSince != Long.MIN_VALUE && nowMs - fullSince >= STUCK_MS;
    }

    /** a new track of maxFrames (its real frame count): forget the old one's positions, keep the target */
    public void reset(int maxFrames) {
        max = Math.max(block, maxFrames);
        target = Math.max(block, Math.min(target, max - block));
        lastPlayed = -1;
        lastMoveMs = Long.MIN_VALUE;
        fullSince = Long.MIN_VALUE;
    }

    public int max() {
        return max;
    }

    static final long STUCK_MS = 1000;
    private long fullSince = Long.MIN_VALUE;

    static final long STALL_MS = 60;
    private long lastPlayed = -1, lastMoveMs = Long.MIN_VALUE;

    public void underrun() {
        underruns++;
        target = Math.min(max - block, (int) (target * 1.25) + 1);
    }

    public int target() {
        return target;
    }

    public int underruns() {
        return underruns;
    }
}
