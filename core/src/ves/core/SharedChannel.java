package ves.core;

/**
 * Tells our own MICOM AudioStart/AudioEnd events from another user's of the same stream type.
 * The navigation plays its effect sounds (speed camera chimes ...) on type 0x45 too, MICOM reports
 * AudioStart/AudioEnd(type) for every startAudio/endAudio call, and endAudio(type) ends whatever
 * sound holds the channel (car check 2026-11-05). An event within ACK_MS of our own call (and the
 * first one only) is ours; anything else is someone else's.
 * <ul>
 * <li>foreign start: the navigation holds the channel: step aside, and don't call endAudio (it would
 * cut its chime) until its end arrives;</li>
 * <li>foreign end while our path is open: the channel was closed under us: reopen it;</li>
 * <li>a start within RESTORE_MS after another source's yield ended (VuiEnd ...) or after our channel was
 * closed under us: MICOM restoring our channel to its audio stack (car check 2, 14 ms after VuiEnd),
 * not a navigation chime.</li>
 * </ul>
 * Call ownStartCalled/ownEndCalled before the binder call (the event may arrive before it returns).
 * Not thread-safe: callers synchronize.
 */
public final class SharedChannel {
    public static final int OURS = 0, FOREIGN_START = 1, FOREIGN_END = 2, RESTORE = 3;
    public static final long ACK_MS = 1000, RESTORE_MS = 1000;
    /** a foreign start whose end never arrives stops holding the channel after this */
    public static final long FOREIGN_MAX_MS = 30000;

    private long startCallAt, endCallAt;
    private boolean startPending, endPending;
    private boolean foreignHeld; // another user holds the channel now
    private long foreignSince;
    private boolean lost; // the channel was closed by someone else while our path was open
    private long lastYieldEnd = Long.MIN_VALUE / 2, lostAt = Long.MIN_VALUE / 2;

    /** another source (navigation prompt, VUI, call ...) stopped speaking */
    public void yieldEnded(long nowMs) {
        lastYieldEnd = nowMs;
    }

    public void ownStartCalled(long nowMs) {
        startCallAt = nowMs;
        startPending = true;
        lost = false;
    }

    public void ownEndCalled(long nowMs) {
        endCallAt = nowMs;
        endPending = true;
    }

    public int onStart(long nowMs) {
        if (startPending && nowMs - startCallAt <= ACK_MS && nowMs >= startCallAt) {
            startPending = false;
            return OURS;
        }
        startPending = false;
        if (nowMs - lastYieldEnd <= RESTORE_MS && nowMs >= lastYieldEnd || nowMs - lostAt <= RESTORE_MS && nowMs >= lostAt) {
            lost = false;
            foreignHeld = false;
            return RESTORE;
        }
        foreignHeld = true;
        foreignSince = nowMs;
        return FOREIGN_START;
    }

    /** pathOpen: whether we believed the channel was ours when the end arrived */
    public int onEnd(long nowMs, boolean pathOpen) {
        if (endPending && nowMs - endCallAt <= ACK_MS && nowMs >= endCallAt) {
            endPending = false;
            return OURS;
        }
        endPending = false;
        foreignHeld = false;
        if (pathOpen) {
            lost = true;
            lostAt = nowMs;
        }
        return FOREIGN_END;
    }

    /** true when calling endAudio now would cut someone else's sound, or the channel is already closed */
    public boolean skipEnd(long nowMs) {
        if (foreignHeld && nowMs - foreignSince >= FOREIGN_MAX_MS) foreignHeld = false;
        return foreignHeld || lost;
    }
}
