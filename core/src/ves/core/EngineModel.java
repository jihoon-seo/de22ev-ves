package ves.core;

/**
 * Turns vehicle signals into synth parameters. Call update() every block (~10 ms); inputs arriving every
 * 100..250 ms are smoothed (cascaded one-poles, slew-limited gain) so every output moves continuously.
 *
 * <h3>Gates (targetGain 0)</h3>
 * READY NaN (stale) or != 1 (not_ready); GEAR NaN (stale), P (park), N (neutral), R (reverse), any other
 * non-drive code (gear_none, e.g. 9); speed NaN (SPEED_KPH, falling back to MOTOR_SPEED_KPH) (stale).
 *
 * <h3>Behaviour ice (default since 0.2.0)</h3>
 * Sound whenever Ready, like an ICE car: P and N idle at idleLevel% and rev freely with the pedal (800 + 3700 x
 * pedal rpm, at most 4500); R plays at half the idle level, rpm 800 + 30 x km/h; stopped or creeping under
 * 5 km/h with the foot off: idle; foot off above 5 km/h: engine braking (overrun timbre: regen param &gt;= 0.75,
 * more with regenerative kW; gain max(idleLevel, regenLevel% x (0.6 + 0.4 min(1, km/h / 60)))), rpm falling
 * with speed as the profile computes it; otherwise drive. Only Ready off and stale data silence it.
 *
 * <h3>Modes (behaviour classic: the 0.1.x rules, P/N/R silent)</h3>
 * idle_regen: stopped (&lt;1 km/h) in D/B/ECO idles at idleLevel% (of full drive gain 1.0); moving, a regen voice
 * (darker, regenLevel%) while motor kW &lt; 0 or, without pedal, accel &lt; -0.05 g; otherwise drive.
 * stop_silent_regen: like idle_regen but silent when stopped. accel_only: sound only while pedal &gt; 3% or motor
 * kW &gt; 2, held ~1 s after release; no idle, no regen. drive_all: sound whenever moving, regen is plain low load.
 * fadeAboveKph (all modes): gain fades linearly to 0 over the 20 km/h above the threshold.
 *
 * <h3>Load</h3>
 * pedal through a response curve (soft: x^1.5 slow, normal: x^1.15, sport: x^0.85 fast), combined with
 * positive motor kW / 150: load = max(pc, 0.6 pc + 0.4 kWn); pedal only when kW is NaN, kW only when pedal is NaN.
 * Drive gain = 0.45 + 0.55 load.
 *
 * <h3>rpm</h3>
 * ICE_SMOOTH: CVT-like 800 + 2600 L + v (14 + 16 L), max 6500. ICE_SHIFT: 6 virtual gears (rpm per km/h
 * 150/98/68/50/37/27), upshift at 2200 + 3800 L^1.3 rpm, downshift below 1100 + 1700 L (the gap makes hunting
 * impossible: a downshift lands below the upshift point and vice versa), 700 ms minimum dwell, 250 ms shifts with an
 * rpm glide and a load dip, kickdown on a quick stab past 80% pedal, launch slip in 1st. EV_FUTURE: pitch driver
 * 1000 + 50 * km/h (MOTOR_SPEED_KPH, fallback SPEED_KPH), max 9000.
 */
public final class EngineModel {
    static final double IDLE_RPM = 800, MAX_RPM = 6500, FREE_REV_RPM = 4500;
    static final double[] RATIO = {0, 150, 98, 68, 50, 37, 27};
    static final double SHIFT_S = 0.25;
    static final long DWELL_MS = 700, KICK_DWELL_MS = 300, ACCEL_HANG_MS = 1000;
    static final double STOP_KPH = 0.8, MOVE_KPH = 1.2;

    private Profile profile;
    private boolean first = true;
    private long lastNow;

    private double spd1, spd2, ev1, ev2, ped1, ped2, kwn, load, regen, gain, rpm = IDLE_RPM, kickRef;
    private boolean stopped = true;
    private long lastAccelActive = Long.MIN_VALUE / 4;

    // gearbox
    private int gear = 1;
    private boolean shifting;
    private double shiftT, shiftFrom;
    private long lastShiftMs = Long.MIN_VALUE / 4;
    private long shiftCount;

    public EngineModel(Profile p) {
        profile = p != null ? p : Profile.ICE_SMOOTH;
        if (profile == Profile.EV_FUTURE) rpm = 1000;
    }

    /** Switches the profile but keeps all smoothing state so the output glides to the new behaviour. */
    public void setProfile(Profile p) {
        if (p == null || p == profile) return;
        profile = p;
        shifting = false;
    }

    public Profile profile() {
        return profile;
    }

    /** ICE_SHIFT gear changes so far */
    public long shiftCount() {
        return shiftCount;
    }

    public void update(SignalState s, Settings st, long nowMs, EngineParams out) {
        if (st == null) st = DEFAULT_SETTINGS;
        double dt;
        if (first) {
            dt = 0.01;
            first = false;
        } else {
            dt = (nowMs - lastNow) / 1000.0;
            if (dt < 0) dt = 0;
            if (dt > 0.1) dt = 0.1;
        }
        lastNow = nowMs;

        double ready = s.get(Sig.READY, nowMs);
        double gearSig = s.get(Sig.GEAR, nowMs);
        double speed = s.get(Sig.SPEED_KPH, nowMs);
        double mspeed = s.get(Sig.MOTOR_SPEED_KPH, nowMs);
        if (Double.isNaN(speed)) speed = mspeed;
        if (Double.isNaN(mspeed)) mspeed = speed;
        double pedal = s.get(Sig.PEDAL_PCT, nowMs);
        double kw = s.get(Sig.MOTOR_KW, nowMs);
        double acc = s.get(Sig.ACCEL_G, nowMs);
        if (Math.abs(acc) > 1.5) acc = Double.NaN; // implausible (the car check read a constant -4.18 g: no YRS12 data)

        String gate = null;
        if (Double.isNaN(ready)) gate = "stale";
        else if (ready < 0.5) gate = "not_ready";
        else if (Double.isNaN(gearSig)) gate = "stale";
        else {
            int g = (int) Math.round(gearSig);
            if (g == Sig.GEAR_P) gate = "park";
            else if (g == Sig.GEAR_N) gate = "neutral";
            else if (g == Sig.GEAR_R) gate = "reverse";
            else if (!Sig.isDrive(g)) gate = "gear_none";
            else if (Double.isNaN(speed)) gate = "stale";
        }
        // ICE-like (default): P and N idle (and rev freely), R plays quietly; only Ready off / stale data silence
        boolean ice = !"classic".equals(st.behavior);
        boolean parked = false, reversing = false;
        if (ice && gate != null) {
            if (gate.equals("park") || gate.equals("neutral")) {
                parked = true;
                gate = null;
            } else if (gate.equals("reverse")) {
                reversing = true;
                gate = null;
            }
            if (gate == null && Double.isNaN(speed)) speed = mspeed = 0; // standing in P: speed may be unknown
        }

        // ---- response
        // car check 2 found normal "a little late": normal and sport follow the pedal faster since 0.2.0
        double expo, tauPed, gainUp, tauRpmUp, tauLoad;
        if ("soft".equals(st.response)) {
            expo = 1.5;
            tauPed = 0.32;
            gainUp = 1.2;
            tauRpmUp = 0.25;
            tauLoad = 0.05;
        } else if ("sport".equals(st.response)) {
            expo = 0.85;
            tauPed = 0.05;
            gainUp = 3.0;
            tauRpmUp = 0.06;
            tauLoad = 0.02;
        } else {
            expo = 1.15;
            tauPed = 0.08;
            gainUp = 2.0;
            tauRpmUp = 0.09;
            tauLoad = 0.03;
        }

        // ---- smoothed inputs
        double v = Double.isNaN(speed) ? spd2 : clamp(speed, 0, 300);
        double a = alpha(dt, 0.07);
        spd1 += (v - spd1) * a;
        spd2 += (spd1 - spd2) * a;
        double ve = Double.isNaN(mspeed) ? ev2 : clamp(mspeed, 0, 300);
        ev1 += (ve - ev1) * a;
        ev2 += (ev1 - ev2) * a;
        double sp = spd2;

        double pRaw = Double.isNaN(pedal) ? 0 : clamp(pedal / 100.0, 0, 1);
        double pc = Math.pow(pRaw, expo);
        double ap = alpha(dt, tauPed * 0.5);
        ped1 += (pc - ped1) * ap;
        ped2 += (ped1 - ped2) * ap;
        pc = ped2;
        double kwTarget = Double.isNaN(kw) ? 0 : clamp(kw / 150.0, 0, 1);
        kwn += (kwTarget - kwn) * alpha(dt, 0.15);
        double lt;
        if (Double.isNaN(kw)) lt = pc;
        else if (Double.isNaN(pedal)) lt = kwn;
        else lt = Math.max(pc, 0.6 * pc + 0.4 * kwn);
        load += (lt - load) * alpha(dt, tauLoad);
        double L = clamp(load, 0, 1);

        if (stopped && sp > MOVE_KPH) stopped = false;
        else if (!stopped && sp < STOP_KPH) stopped = true;

        double regenRaw = 0;
        if (!stopped && pRaw < 0.02) {
            if (!Double.isNaN(kw) && kw < 0) regenRaw = Math.min(1, -kw / 40.0);
            if (!Double.isNaN(acc) && acc < -0.05) regenRaw = Math.max(regenRaw, Math.min(1, (-acc - 0.05) / 0.15));
        }
        // ICE-like: foot off while rolling is engine braking (overrun timbre), stronger with regen
        boolean coasting = ice && !parked && !reversing && !stopped && pRaw < 0.02 && sp > 5;
        if (coasting) regenRaw = Math.max(0.75, regenRaw);
        else if (ice) regenRaw = 0;
        regen += (regenRaw - regen) * alpha(dt, 0.2);

        // ---- mode
        String mode = st.mode == null ? "idle_regen" : st.mode;
        boolean regenVoice = mode.equals("idle_regen") || mode.equals("stop_silent_regen");
        double driveG = 0.45 + 0.55 * L;
        double idleG = clamp(st.idleLevel, 0, 60) / 100.0;
        double tg;
        String reason;
        double outRegen = regenVoice ? regen : 0;
        if (ice) {
            outRegen = regen;
            double overrunG = Math.max(idleG, clamp(st.regenLevel, 0, 100) / 100.0 * (0.6 + 0.4 * Math.min(1, sp / 60.0)));
            if (parked) {
                tg = pRaw > 0.02 ? Math.max(idleG, driveG) : idleG;
                reason = pRaw > 0.02 ? "rev" : "idle";
            } else if (reversing) {
                tg = 0.5 * idleG;
                reason = "reverse_quiet"; // not "reverse": that is classic's silent gate
            } else if (stopped || (pRaw < 0.02 && sp <= 5)) {
                // stopped, or creeping with the foot off: idle
                tg = pRaw > 0.02 ? Math.max(idleG, driveG) : idleG;
                reason = pRaw > 0.02 ? "drive" : "idle";
            } else if (coasting) {
                tg = overrunG;
                reason = "overrun";
            } else {
                // pedal down: the drive level from standstill on (0.2.6 blended from idle over 0-8 km/h, so
                // pulling away dipped from the standing drive level to near idle)
                tg = Math.max(idleG, driveG);
                reason = "drive";
            }
        } else if (mode.equals("accel_only")) {
            outRegen = 0;
            if (pRaw > 0.03 || (!Double.isNaN(kw) && kw > 2)) lastAccelActive = nowMs;
            if (nowMs - lastAccelActive <= ACCEL_HANG_MS) {
                tg = driveG;
                reason = "drive";
            } else {
                tg = 0;
                reason = "accel_only_wait";
            }
        } else if (stopped) {
            if (mode.equals("idle_regen")) {
                if (pRaw > 0.02) {
                    tg = Math.max(idleG, driveG);
                    reason = "drive";
                } else {
                    tg = idleG;
                    reason = "idle";
                }
            } else {
                tg = 0;
                reason = "stopped_silent";
            }
        } else if (regenVoice && regenRaw > 0) {
            tg = clamp(st.regenLevel, 0, 100) / 100.0 * (0.5 + 0.5 * regen);
            reason = "regen";
        } else {
            tg = driveG;
            if (mode.equals("idle_regen")) tg = idleG + (driveG - idleG) * Math.min(1, sp / 8.0);
            reason = "drive";
        }
        if (st.fadeAboveKph > 0 && tg > 0) {
            double f = clamp(1 - (sp - st.fadeAboveKph) / 20.0, 0, 1);
            tg *= f;
            if (f <= 0) reason = "fade_speed";
        }
        if (gate != null) {
            tg = 0;
            reason = gate;
        }
        // slew-limited gain: continuous, and reaches exactly 0 (a gate silences within ~200 ms)
        double up = gainUp * dt, down = 5.0 * dt;
        if (tg > gain) gain = Math.min(tg, gain + up);
        else gain = Math.max(tg, gain - down);

        // ---- rpm
        double loadMul = 1;
        int vg = 0;
        boolean sh = false;
        if (ice && (parked || reversing) && profile != Profile.EV_FUTURE) {
            // standing: free revs up to 4500 in P/N, a slow crawl in R; the gearbox waits in 1st
            double target = parked ? IDLE_RPM + (FREE_REV_RPM - IDLE_RPM) * pRaw : IDLE_RPM + 30 * sp;
            target = clamp(target, IDLE_RPM, FREE_REV_RPM);
            rpm += (target - rpm) * alpha(dt, target > rpm ? 0.15 : 0.5);
            gear = 1;
            shifting = false;
        } else if (profile == Profile.EV_FUTURE) {
            double target = Math.min(9000, 1000 + 50 * ev2);
            rpm += (target - rpm) * alpha(dt, 0.15);
        } else if (profile == Profile.ICE_SHIFT) {
            loadMul = gearbox(nowMs, dt, sp, L, pRaw);
            vg = gear;
            sh = shifting;
        } else {
            double target = IDLE_RPM + 2600 * L + sp * (14 + 16 * L);
            target = clamp(target, IDLE_RPM, MAX_RPM);
            rpm += (target - rpm) * alpha(dt, target > rpm ? tauRpmUp : 0.45);
        }
        if (profile != Profile.ICE_SHIFT && !(ice && (parked || reversing))) {
            // keep the gearbox consistent for a later switch to ICE_SHIFT
            gear = bestGear(sp, L);
            shifting = false;
        }

        out.rpm = rpm;
        out.load = clamp(L * loadMul, 0, 1);
        out.regen = clamp(outRegen, 0, 1);
        out.targetGain = clamp(gain, 0, 1);
        out.speedKph = sp;
        out.virtualGear = vg;
        out.shifting = sh;
        out.reason = reason;
    }

    private double gearbox(long now, double dt, double v, double L, double pRaw) {
        double upRpm = 2200 + 3800 * Math.pow(L, 1.3);
        double downRpm = 1100 + 1700 * L;
        kickRef += (pRaw - kickRef) * alpha(dt, 0.6);
        long since = now - lastShiftMs;
        if (!shifting) {
            double rg = v * RATIO[gear];
            if (v < 3 && gear > 1) {
                gear = 1; // standstill: no audible shift
            } else if (pRaw > 0.8 && pRaw - kickRef > 0.35 && gear > 1 && since >= KICK_DWELL_MS) {
                int g = 1;
                while (g < gear && v * RATIO[g] > 5400) g++;
                kickRef = pRaw;
                if (g < gear) startShift(g, now);
            } else if (gear < 6 && rg >= upRpm && v * RATIO[gear + 1] > downRpm + 200 && since >= DWELL_MS) {
                startShift(gear + 1, now);
            } else if (gear > 1 && rg < downRpm && since >= DWELL_MS) {
                startShift(gear - 1, now);
            }
        }
        double target = gearRpm(gear, v, L);
        double loadMul = 1;
        if (shifting) {
            shiftT += dt / SHIFT_S;
            double t = Math.min(1, shiftT);
            double sm = t * t * (3 - 2 * t);
            rpm = shiftFrom + (target - shiftFrom) * sm;
            loadMul = 1 - 0.6 * Math.sin(Math.PI * t);
            if (shiftT >= 1) shifting = false;
        } else {
            rpm += (target - rpm) * alpha(dt, 0.06);
        }
        return loadMul;
    }

    private void startShift(int to, long now) {
        if (to == gear) return;
        gear = to;
        shifting = true;
        shiftT = 0;
        shiftFrom = rpm;
        lastShiftMs = now;
        shiftCount++;
    }

    private static double gearRpm(int g, double v, double L) {
        double r = v * RATIO[g];
        if (g == 1) r = Math.max(r, IDLE_RPM + 2200 * L * Math.max(0, 1 - v / 35.0));
        r = Math.max(r, g == 1 ? IDLE_RPM : 900);
        return Math.min(r, MAX_RPM);
    }

    /** the gear the shift logic would settle in at steady speed v, load L */
    private static int bestGear(double v, double L) {
        double upRpm = 2200 + 3800 * Math.pow(L, 1.3);
        int g = 1;
        while (g < 6 && v * RATIO[g] >= upRpm && v * RATIO[g + 1] > 1300 + 1700 * L) g++;
        return v < 3 ? 1 : g;
    }

    private static double alpha(double dt, double tau) {
        if (tau <= 0) return 1;
        return 1 - Math.exp(-dt / tau);
    }

    static double clamp(double x, double lo, double hi) {
        return x < lo ? lo : x > hi ? hi : x;
    }

    private static final Settings DEFAULT_SETTINGS = new Settings();
}
