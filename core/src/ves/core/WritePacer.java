package ves.core;

/**
 * Keeps only about target frames queued in the AudioTrack. Its minimum buffer on the car is 8192 bytes
 * (186 ms), and a blocking write fills it whole, which made the sound follow the pedal late (car check 2).
 * The audio loop asks before each block: write one block while the queue (written - played) is at most
 * target, else wait. Each underrun raises the target by half a block's worth of headroom (x1.25), up to
 * the whole buffer: a car that can't keep up gets the old behaviour back.
 */
public final class WritePacer {
    private final int block, max;
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
     * With the clock: Android 4.4's AudioFlinger starts a streaming track only once its buffer is full, and
     * after an underrun waits to be filled again. While the play position doesn't move (STALL_MS), fill the
     * whole buffer; once it moves, pace to the target. (0.2.0-0.2.2 paced from the start: the track never
     * started, vesd waited forever, stuck and silent.)
     */
    public int framesToWrite(long written, long played, long nowMs) {
        if (played != lastPlayed || lastMoveMs == Long.MIN_VALUE) {
            lastPlayed = played;
            lastMoveMs = nowMs;
        }
        boolean stalled = nowMs - lastMoveMs >= STALL_MS || played == 0;
        long fill = written - played;
        if (stalled) return fill + block <= max ? block : 0;
        return fill <= target ? block : 0;
    }

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
