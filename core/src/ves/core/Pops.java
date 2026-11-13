package ves.core;

/**
 * Exhaust pops and crackles on lift-off (overrun), N-style: when the load drops by more than 0.35 within
 * 300 ms at more than 2500 rpm and 20 km/h, an episode of 3..8 bursts follows within 1.2 s; episodes are at
 * least 4 s apart. A burst is band-passed noise (about 400-1800 Hz, 8-30 ms decay) over a 180 Hz damped thump.
 * Levels: 0 off, 1 soft, 2 strong. next() returns a sample for the voice mix (before OutputStage); trigger()
 * only tells when a burst starts and how strong (PackVoice plays a pack's own pop sounds then). Seeded,
 * allocation-free, one thread.
 */
public final class Pops {
    static final double DROP = 0.35, MIN_RPM = 2500, MIN_KPH = 20;
    static final long WINDOW_MS = 300, EPISODE_MS = 1200, GAP_MS = 4000;
    static final int HIST = 32;

    private final int sr;
    private int rng;
    private int level;
    // load history (per block)
    private final long[] ht = new long[HIST];
    private final double[] hl = new double[HIST];
    private int hn, hi;
    // episode
    private boolean inEpisode;
    private long episodeStart = Long.MIN_VALUE / 2, lastEpisode = Long.MIN_VALUE / 2, nextBurst, lastBurst;
    private int burstsLeft, episodes, bursts;
    private long now;
    // burst voice
    private double amp, decay, thumpAmp, thumpDecay, thumpPh, lpA, lpB;
    private final double thumpStep, lpLoC, lpHiC;

    public Pops(int sampleRate, long seed) {
        sr = sampleRate > 0 ? sampleRate : 22050;
        rng = (int) (seed ^ (seed >>> 32)) ^ 0x2545F491;
        if (rng == 0) rng = 1;
        thumpStep = 2 * Math.PI * 180.0 / sr;
        lpLoC = 1 - Math.exp(-2 * Math.PI * 400.0 / sr);
        lpHiC = 1 - Math.exp(-2 * Math.PI * Math.min(1800.0, 0.4 * sr) / sr);
    }

    public void setLevel(int l) {
        level = l < 0 ? 0 : l > 2 ? 2 : l;
    }

    public static int level(String s) {
        return "strong".equals(s) ? 2 : "soft".equals(s) ? 1 : 0;
    }

    /** once per block, before next() is pulled for that block */
    public void update(double rpm, double load, double kph, long nowMs) {
        now = nowMs;
        if (!(load >= 0)) load = 0;
        hi = (hi + 1) % HIST;
        ht[hi] = nowMs;
        hl[hi] = load;
        if (hn < HIST) hn++;
        if (level == 0) {
            inEpisode = false;
            return;
        }
        if (!inEpisode && rpm > MIN_RPM && kph > MIN_KPH && nowMs - lastEpisode >= GAP_MS) {
            double max = 0;
            for (int k = 0; k < hn; k++) {
                int j = (hi - k + HIST) % HIST;
                if (nowMs - ht[j] > WINDOW_MS) break;
                max = Math.max(max, hl[j]);
            }
            if (max - load > DROP) {
                inEpisode = true;
                episodes++;
                episodeStart = lastEpisode = nowMs;
                burstsLeft = 3 + rand(6); // 3..8
                nextBurst = nowMs + 40 + rand(80);
            }
        }
        if (inEpisode && (burstsLeft == 0 || nowMs - episodeStart > EPISODE_MS)) inEpisode = false;
    }

    /** one sample of pops (0 when quiet) */
    public double next() {
        if (inEpisode && burstsLeft > 0 && now >= nextBurst) burst();
        if (amp < 1e-4 && thumpAmp < 1e-4) {
            amp = thumpAmp = 0;
            return 0;
        }
        double n = (rand(65536) - 32768) / 32768.0;
        lpA += (n - lpA) * lpHiC;
        lpB += (lpA - lpB) * lpLoC;
        double band = lpA - lpB; // ~400-1800 Hz
        double y = amp * band * 2.2 + thumpAmp * Math.sin(thumpPh);
        thumpPh += thumpStep;
        amp *= decay;
        thumpAmp *= thumpDecay;
        return y;
    }

    /**
     * instead of next(), for a voice that plays its own pop sounds: advances the burst schedule as next() does and
     * returns the strength of a burst starting on this sample (0.3..0.9), else 0
     */
    public double trigger() {
        if (!(inEpisode && burstsLeft > 0 && now >= nextBurst)) return 0;
        double s = burst();
        amp = thumpAmp = 0; // nothing synthesized
        return s;
    }

    /** starts a burst: its strength, decay and the time of the next one */
    private double burst() {
        burstsLeft--;
        bursts++;
        lastBurst = now;
        double strength = (level == 2 ? 0.9 : 0.5) * (0.6 + 0.4 * rand(1000) / 1000.0);
        amp = strength;
        decay = Math.exp(-1.0 / ((0.008 + 0.022 * rand(1000) / 1000.0) * sr));
        thumpAmp = strength * 0.6;
        thumpDecay = Math.exp(-1.0 / (0.04 * sr));
        thumpPh = 0;
        // the rest of the episode: closer together at first, then sparser
        int left = Math.max(1, burstsLeft);
        long span = Math.max(30, (episodeStart + EPISODE_MS - 60 - now) / (left + 1));
        nextBurst = now + 30 + rand((int) Math.min(400, span));
        return strength;
    }

    private int rand(int n) {
        rng ^= rng << 13;
        rng ^= rng >>> 17;
        rng ^= rng << 5;
        return n <= 0 ? 0 : (rng >>> 1) % n;
    }

    public int episodes() {
        return episodes;
    }

    public int bursts() {
        return bursts;
    }

    public long episodeStartMs() {
        return episodeStart;
    }

    public long lastBurstMs() {
        return lastBurst;
    }
}
