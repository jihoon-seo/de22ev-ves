package ves.core;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Speed cameras from the stock navigation (0.2.13): vesd reads {@code logcat -v time -b system ClusterService:D *:S}
 * and feeds every line here. The navigation hands its camera info to com.hkmc.micom.ClusterService, which logs every
 * argument (Slog.d "ClusterService", text pieces from fw260902 ClusterService$NaviCluster.smali):
 * <pre>
 * 11-20 17:46:33.460 D/ClusterService(  601): NaviCluster.updateTBTTrapInfo() fixedSpeed: 0 mobileSpeed: 0 ... speedLimit: 0
 *     ... displayCountry: 0cameraSafetyAlert0          (17- and 22-field variants; the car logs the 22-field one)
 * NaviCluster.updateNaviWarnDist() overDec: 350 underDec: 0 scale: 0     (10-arg call: warnDistOverDec ... + 3 more lines)
 * NaviCluster.updateSCC_HU_Navi_P() ... rangeCamInfo: 1 rangeCamDist: 1500 rangeCamSpdLimit: 80 rangeCamAvgMovingSpd: 87 ...
 * NaviCluster.updateSLIFNaviInformation()::speedLimit:60 / linkClass:2 / ...   (logged raw only)
 * </pre>
 * Camera priority: section (SCC rangeCamSpdLimit &gt; 0) &gt; fixedSpeed &gt; mobileSpeed &gt; speedLimit &gt; warningZone; a speed of
 * -1 or 0 is none, so a trap line without any clears the camera (and its distance).
 * <p>The car's navigation (0.2.15, from the 2026-11-23/24 drive logs) never fills fixedSpeed or mobileSpeed: a camera
 * comes as speedLimit (its limit) + speedInfoType + cameraSafetyAlert, with the warning distance counting down to it
 * and the line cleared after it. speedInfoType 1 is a speed camera (cameraSafetyAlert 1; 80 km/h roads too), 5 a
 * signal + speed camera (cameraSafetyAlert 3), and cameraSafetyAlert 5 (type 1) / 6 (type 5) one at a school zone (30
 * km/h, the school-zone warning right after). A zone comes as warningZone in the order of the cluster's
 * Navigation$WarningZone names (1 black spot .. 8 speed bump, not its bit values) with cameraSafetyAlert 51 + it (2 school
 * zone / 53, 8 speed bump / 59). A speedLimit with speedInfoType 0 stays a plain limit. The 0.2.14 vesd showed all of the
 * car's cameras as "제한속도". Event times come from the logcat
 * stamp (this year; last year when that is over a day ahead); a line older than HOLD_MS when read (vesd's startup
 * backlog) never becomes active, it only counts for the nav age. A camera no trap or distance line refreshed for
 * STALE_MS, or a section no SCC line refreshed, goes unknown. The camera enums are not known yet (0.2.14 from drive
 * captures): takeLog() hands vesd the raw lines of the camera methods, changes only, at most one per LOG_GAP_MS per
 * line kind (method + first key), a clear always. Thread-safe (reader, watch and LIVE threads).
 */
public final class NavCam {
    /** a line older than this when read is backlog: never active */
    public static final long HOLD_MS = 15000;
    /** a camera / section nobody refreshed for this long is unknown again */
    public static final long STALE_MS = 60000;
    /** raw log rate per line kind */
    public static final long LOG_GAP_MS = 1000;
    /** our own section average is shown after this much driving in the section; speed tick gaps count this much at most */
    public static final long OWN_MIN_MS = 10000, TICK_CAP_MS = 2000;
    static final long DAY_MS = 86400000L;
    static final int LOG_MAX = 16384;

    public static final Pattern LINE = Pattern.compile("^(\\d\\d)-(\\d\\d) (\\d\\d):(\\d\\d):(\\d\\d)\\.(\\d{3})\\s+[VDIWEF]/ClusterService\\(\\s*\\d+\\):"
            + "\\s+NaviCluster\\.(\\w+)\\(\\)\\s*(.*)$");
    public static final Pattern KV = Pattern.compile("([A-Za-z][A-Za-z0-9_]*)\\s*:\\s*(-?\\d+)");
    /** the firmware appends this one without a separator: "displayCountry: 0cameraSafetyAlert0" */
    public static final Pattern SAFETY = Pattern.compile("cameraSafetyAlert\\s*:?\\s*(-?\\d+)");

    public static final String TRAP = "updateTBTTrapInfo", WARN_DIST = "updateNaviWarnDist", SCC = "updateSCC_HU_Navi_P",
            SLIF = "updateSLIFNaviInformation";
    /** the methods whose raw lines go to vesd.log (updateRemainDis is the route's remaining distance: every second, no camera) */
    static final String[] LOGGED = {TRAP, WARN_DIST, SCC, SLIF};
    /** a trap line with all of these at 0 (or -1) has no camera: the "all-zero" clear */
    static final String[] TRAP_CAMERA = {"fixedSpeed", "mobileSpeed", "redlineSpeed", "nonSpeed", "warningZone", "curveWarning",
            "mergeWarning", "speedLimit", "busSpeed", "warningZone2", "warningZone3", "warningZone4", "cameraSafetyAlert"};

    // all times on vesd's uptime clock (the wall clock jumps when the car sets it)
    private boolean navSeen;
    private long navAt;
    private boolean trapKnown;
    private long trapAt;
    private int fixed, mobile, limit, zone, infoType, alert;
    private int distM = -1;
    private long distAt = Long.MIN_VALUE / 2;
    private boolean sccKnown;
    private long sccAt;
    private int sLim, sAvg, sDist;
    private boolean secOn;
    private long secMs, lastTick;
    private double secMeters;
    private final StringBuilder log = new StringBuilder();
    private final Map<String, String> logArgs = new HashMap<String, String>();
    private final Map<String, Long> logAt = new HashMap<String, Long>();
    private final Map<String, Boolean> logNonZero = new HashMap<String, Boolean>();

    /** one logcat line, read at wallMs (System.currentTimeMillis) / uptimeMs (SystemClock.uptimeMillis or similar) */
    public synchronized void onLine(String line, long wallMs, long uptimeMs) {
        if (line == null) return;
        int end = line.length();
        while (end > 0 && line.charAt(end - 1) <= ' ') end--;
        Matcher m = LINE.matcher(line.substring(0, end));
        if (!m.matches()) return;
        int mon = Integer.parseInt(m.group(1)), day = Integer.parseInt(m.group(2)), h = Integer.parseInt(m.group(3));
        int mi = Integer.parseInt(m.group(4)), s = Integer.parseInt(m.group(5)), ms = Integer.parseInt(m.group(6));
        if (mon < 1 || mon > 12 || day < 1 || day > 31 || h > 23 || mi > 59 || s > 59) return;
        String method = m.group(7), args = m.group(8);
        Map<String, Integer> kv = new HashMap<String, Integer>();
        String first = null;
        Matcher k = KV.matcher(args);
        while (k.find()) {
            Integer v = parse(k.group(2));
            if (v == null) return; // a broken number: the whole line is suspect
            if (first == null) first = k.group(1);
            if (!kv.containsKey(k.group(1))) kv.put(k.group(1), v);
        }
        Matcher sa = SAFETY.matcher(args);
        if (sa.find()) {
            Integer v = parse(sa.group(1));
            if (v == null) return;
            kv.put("cameraSafetyAlert", v);
        }
        if (first == null) return;
        long age = Math.max(0, wallMs - eventWall(mon, day, h, mi, s, ms, wallMs));
        long at = uptimeMs - age;
        if (!navSeen || at > navAt) navAt = at;
        navSeen = true;
        if (age > HOLD_MS) return;
        log(method, first, args, kv, at);
        if (TRAP.equals(method)) trap(kv, at);
        else if (WARN_DIST.equals(method)) warnDist(kv, at);
        else if (SCC.equals(method)) scc(kv, at);
    }

    /** vesd's speed (km/h) every ~100 ms: our own section average when the navigation gives none */
    public synchronized void tickSpeed(double kph, long uptimeMs) {
        if (!sectionActive(uptimeMs)) {
            secOn = false;
            return;
        }
        if (!secOn) {
            secOn = true;
            secMs = 0;
            secMeters = 0;
            lastTick = uptimeMs;
            return;
        }
        long dt = uptimeMs - lastTick;
        lastTick = uptimeMs;
        if (dt <= 0 || Double.isNaN(kph) || kph < 0) return;
        if (dt > TICK_CAP_MS) dt = TICK_CAP_MS;
        secMs += dt;
        secMeters += kph / 3.6 * dt / 1000.0;
    }

    /**
     * The LIVE suffix (starts with a space): nav cam lim dist zone sec slim savg ssrc sdist, "-" unknown, e.g.
     * {@code " nav=3 cam=speed lim=60 dist=350 zone=0 sec=0 slim=- savg=- ssrc=- sdist=-"}.
     */
    public synchronized String liveFields(long uptimeMs) {
        long now = uptimeMs;
        boolean sec = sectionActive(now);
        boolean trap = trapKnown && now - Math.max(trapAt, distAt) <= STALE_MS;
        String cam;
        int lim = -1;
        if (sec) {
            cam = "section";
            lim = sLim;
        } else if (!trap) {
            cam = "-";
        } else if (fixed > 0) {
            cam = "speed";
            lim = fixed;
        } else if (mobile > 0) {
            cam = "mobile";
            lim = mobile;
        } else if (limit > 0) {
            cam = limitKind(infoType, alert);
            lim = limit;
        } else {
            cam = zone > 0 ? "zone" : "none";
        }
        boolean camera = !"-".equals(cam) && !"none".equals(cam);
        int dist = camera && now - distAt <= STALE_MS ? distM : -1;
        int avg = -1;
        String src = "-";
        if (sec && sAvg > 0) {
            avg = sAvg;
            src = "nav";
        } else if (sec && secOn && secMs >= OWN_MIN_MS) {
            avg = (int) Math.round(secMeters * 3600 / secMs);
            src = "own";
        }
        StringBuilder b = new StringBuilder(96);
        b.append(" nav=").append(navSeen ? String.valueOf(Math.max(0, now - navAt) / 1000) : "-");
        b.append(" cam=").append(cam);
        b.append(" lim=").append(pos(lim));
        b.append(" dist=").append(pos(dist));
        b.append(" zone=").append(trap ? String.valueOf(zone) : "-");
        b.append(" sec=").append(sec ? 1 : 0);
        b.append(" slim=").append(sec ? pos(sLim) : "-");
        b.append(" savg=").append(pos(avg));
        b.append(" ssrc=").append(src);
        b.append(" sdist=").append(sec ? pos(sDist) : "-");
        return b.toString();
    }

    /** the raw lines for vesd.log since the last call ("nav: <method> <args>", one per line; "" when none) */
    public synchronized String takeLog() {
        if (log.length() == 0) return "";
        String t = log.toString();
        log.setLength(0);
        return t;
    }

    /** speedLimit's kind from speedInfoType and cameraSafetyAlert (the car's codes, see the class comment) */
    static String limitKind(int infoType, int alert) {
        if (infoType <= 0) return "limit";
        if (alert == 5 || alert == 6) return "school";
        return infoType == 5 ? "signal" : "speed";
    }

    /** warning distance in m: over + under/10 in the unit of scale (0 m, 1 km, 2 mile, 3 feet); -1 unknown (over 65535 = none) */
    public static int distMeters(int over, int under, int scale) {
        if (over < 0 || under < 0 || over >= 65535) return -1;
        double f;
        switch (scale) {
            case 0:
                f = 1;
                break;
            case 1:
                f = 1000;
                break;
            case 2:
                f = 1609.344;
                break;
            case 3:
                f = 0.3048;
                break;
            default:
                return -1;
        }
        return (int) Math.round((over + under / 10.0) * f);
    }

    private void trap(Map<String, Integer> kv, long at) {
        if (!kv.containsKey("fixedSpeed")) return;
        trapKnown = true;
        trapAt = at;
        fixed = get(kv, "fixedSpeed");
        mobile = get(kv, "mobileSpeed");
        limit = get(kv, "speedLimit");
        zone = get(kv, "warningZone");
        infoType = get(kv, "speedInfoType");
        alert = get(kv, "cameraSafetyAlert");
        if (fixed <= 0 && mobile <= 0 && limit <= 0 && zone <= 0) distM = -1; // cleared: the distance went with it
    }

    private void warnDist(Map<String, Integer> kv, long at) {
        int d;
        if (kv.containsKey("overDec")) d = distMeters(get(kv, "overDec"), get(kv, "underDec"), get(kv, "scale"));
        else if (kv.containsKey("warnDistOverDec"))
            d = distMeters(get(kv, "warnDistOverDec"), get(kv, "warnDistUnderDec"), get(kv, "warnDistScale"));
        else return; // unit1.. / popup.. lines of the 10-arg call
        distM = d;
        distAt = at;
    }

    private void scc(Map<String, Integer> kv, long at) {
        if (!kv.containsKey("rangeCamSpdLimit")) return;
        sccKnown = true;
        sccAt = at;
        sLim = get(kv, "rangeCamSpdLimit");
        sAvg = get(kv, "rangeCamAvgMovingSpd");
        sDist = get(kv, "rangeCamDist");
        // the car sends this line with every field at its all-ones "invalid" (rangeCamInfo 7, limit 15, distance 65535,
        // average 255) when the navigation starts: no section (0.2.15)
        if (get(kv, "rangeCamInfo") == 7 || sLim == 15) sLim = 0;
        if (sAvg >= 255) sAvg = 0;
        if (sDist >= 65535) sDist = 0;
        if (sLim <= 0) secOn = false;
    }

    private boolean sectionActive(long now) {
        return sccKnown && sLim > 0 && now - sccAt <= STALE_MS;
    }

    private void log(String method, String first, String args, Map<String, Integer> kv, long at) {
        boolean logged = false;
        for (String x : LOGGED) logged |= x.equals(method);
        if (!logged) return;
        String key = method + "/" + first;
        if (args.equals(logArgs.get(key))) return;
        boolean zero = true;
        if (TRAP.equals(method)) {
            for (String c : TRAP_CAMERA) zero &= get(kv, c) <= 0;
        } else {
            for (Integer v : kv.values()) zero &= v == 0;
        }
        boolean clear = zero && Boolean.TRUE.equals(logNonZero.get(key));
        Long last = logAt.get(key);
        if (!clear && last != null && at - last < LOG_GAP_MS) return;
        if (log.length() < LOG_MAX) log.append("nav: ").append(method).append(' ').append(args).append('\n');
        logArgs.put(key, args);
        logAt.put(key, at);
        logNonZero.put(key, !zero);
    }

    private static long eventWall(int mon, int day, int h, int mi, int s, int ms, long wallMs) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(wallMs);
        int year = c.get(Calendar.YEAR);
        c.clear();
        c.set(year, mon - 1, day, h, mi, s);
        c.set(Calendar.MILLISECOND, ms);
        long t = c.getTimeInMillis();
        if (t - wallMs > DAY_MS) {
            c.set(Calendar.YEAR, year - 1);
            t = c.getTimeInMillis();
        }
        return t;
    }

    private static Integer parse(String v) {
        if (v.length() > 10) return null;
        try {
            return Integer.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int get(Map<String, Integer> kv, String k) {
        Integer v = kv.get(k);
        return v == null ? 0 : v;
    }

    private static String pos(int v) {
        return v > 0 ? String.valueOf(v) : "-";
    }
}
