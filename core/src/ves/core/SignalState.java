package ves.core;

/**
 * Latest value + timestamp per {@link Sig}. Written by the hscan reader thread (via EvDecoder) or directly
 * (fallback/tests), read by the audio thread; all methods are synchronized (uncontended, ~10 reads per block).
 * get() returns NaN when a signal was never set, was set to NaN (decoder sentinel), or is older than its stale
 * window. A sample stamped slightly in the future (clock race between threads) counts as fresh.
 */
public final class SignalState {
    public static final long DEFAULT_STALE_MS = 500, SLOW_STALE_MS = 2000;

    private final double[] value = new double[Sig.COUNT];
    private final long[] time = new long[Sig.COUNT];
    private final boolean[] has = new boolean[Sig.COUNT];
    private final long[] updates = new long[Sig.COUNT];
    private final long[] staleMs = new long[Sig.COUNT];

    public SignalState() {
        for (int i = 0; i < Sig.COUNT; i++) staleMs[i] = DEFAULT_STALE_MS;
        staleMs[Sig.GEAR] = SLOW_STALE_MS;
        staleMs[Sig.READY] = SLOW_STALE_MS;
        clear();
    }

    public synchronized void set(int sig, double v, long tMs) {
        if (sig < 0 || sig >= Sig.COUNT) return;
        value[sig] = v;
        time[sig] = tMs;
        has[sig] = true;
        updates[sig]++;
    }

    public synchronized double get(int sig, long nowMs) {
        if (sig < 0 || sig >= Sig.COUNT || !has[sig]) return Double.NaN;
        long age = nowMs - time[sig];
        long st = staleMs[sig];
        if (age > st || age < -st) return Double.NaN;
        return value[sig];
    }

    public synchronized void setStaleMs(int sig, long ms) {
        if (sig < 0 || sig >= Sig.COUNT) return;
        staleMs[sig] = Math.max(0, ms);
    }

    /** Timestamp of the last set(), or Long.MIN_VALUE if never set. */
    public synchronized long lastUpdate(int sig) {
        if (sig < 0 || sig >= Sig.COUNT || !has[sig]) return Long.MIN_VALUE;
        return time[sig];
    }

    /** Number of set() calls for sig. */
    public synchronized long updates(int sig) {
        return sig < 0 || sig >= Sig.COUNT ? 0 : updates[sig];
    }

    /** Forgets all values (counters are kept). */
    public synchronized void clear() {
        for (int i = 0; i < Sig.COUNT; i++) {
            has[i] = false;
            value[i] = Double.NaN;
            time[i] = 0;
        }
    }
}
