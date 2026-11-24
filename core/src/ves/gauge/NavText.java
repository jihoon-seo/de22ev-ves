package ves.gauge;

/**
 * The camera line on the main screen and the full gauge (0.2.13), Korean and short, from LIVE's camera fields:
 * "단속 60 · 350 m", "신호·과속 50 · 300 m", "어린이보호구역 30 · 120 m", "이동식 단속 50 · 1.2 km", "제한속도 80",
 * "과속방지턱", "주의 구간 · 350 m", "구간단속 80 · 평균 87"; "" when there is no camera or nothing is known (an older vesd).
 */
public final class NavText {
    static final String SEP = " · ";
    /** zone names by the trap line's warningZone, in the cluster's Navigation$WarningZone order (0.2.15; 2 and 8 seen on the car) */
    static final String[] ZONES = {"주의 구간", "사고 다발", "어린이보호구역", "낙석 주의", "철길 건널목", "안개 주의", "내리막길", "동물 출현", "과속방지턱"};

    private NavText() {}

    public static String line(LiveData d) {
        if (d == null) return "";
        if (d.section == 1 || "section".equals(d.cam)) {
            int lim = d.sectionLimit > 0 ? d.sectionLimit : d.camLimit;
            String s = lim > 0 ? "구간단속 " + lim : "구간단속";
            return d.sectionAvg > 0 ? s + SEP + "평균 " + d.sectionAvg : s;
        }
        String s;
        if ("speed".equals(d.cam)) s = withLimit("단속", d.camLimit);
        else if ("signal".equals(d.cam)) s = withLimit("신호·과속", d.camLimit);
        else if ("school".equals(d.cam)) s = withLimit("어린이보호구역", d.camLimit);
        else if ("mobile".equals(d.cam)) s = withLimit("이동식 단속", d.camLimit);
        else if ("limit".equals(d.cam)) s = withLimit("제한속도", d.camLimit);
        else if ("zone".equals(d.cam)) s = zoneName(d.zone);
        else return "";
        return d.camDist > 0 ? s + SEP + dist(d.camDist) : s;
    }

    /** the full gauge's text under its limit sign (0.2.13): what the sign doesn't show already */
    public static String signDetail(LiveData d) {
        String full = line(d);
        if (full.length() == 0) return "";
        if (section(d)) return "구간단속"; // the average gets its own line (sectionAvg)
        if (!(d.camLimit > 0)) return full; // a warning triangle instead of a sign: the whole line
        String far = d.camDist > 0 ? dist(d.camDist) : "";
        if ("mobile".equals(d.cam)) return far.length() > 0 ? "이동식" + SEP + far : "이동식";
        if ("signal".equals(d.cam)) return far.length() > 0 ? "신호·과속" + SEP + far : "신호·과속";
        if ("school".equals(d.cam)) return far.length() > 0 ? "어린이보호구역" + SEP + far : "어린이보호구역";
        if (far.length() > 0) return far;
        return "speed".equals(d.cam) ? "단속 카메라" : "제한속도";
    }

    /** the main screen's camera title (no sign there, so the limit stays): "구간단속 80", "단속 60 · 350 m" */
    public static String title(LiveData d) {
        if (!section(d)) return line(d);
        int lim = limit(d);
        return lim > 0 ? "구간단속 " + lim : "구간단속";
    }

    /** "현재 72" while a camera is ahead ("현재 --" with the speed unknown) */
    public static String now(LiveData d) {
        if (line(d).length() == 0) return "";
        return "현재 " + (Double.isNaN(d.kph) ? "--" : String.valueOf(Math.round(d.kph)));
    }

    /** the full gauge's section-average line under the camera line, "평균 87"; "" outside a section or unknown */
    public static String sectionAvg(LiveData d) {
        return section(d) && d.sectionAvg > 0 ? "평균 " + d.sectionAvg : "";
    }

    /** a section's average above its limit */
    public static boolean avgOver(LiveData d) {
        int lim = limit(d);
        return section(d) && lim > 0 && d.sectionAvg > lim;
    }

    /** the current speed above the camera's (or the section's) limit */
    public static boolean nowOver(LiveData d) {
        int lim = limit(d);
        return d != null && lim > 0 && !Double.isNaN(d.kph) && d.kph > lim;
    }

    static boolean section(LiveData d) {
        return d != null && (d.section == 1 || "section".equals(d.cam));
    }

    /** the limit that applies: the section's in a section (the camera's when it has none), else the camera's */
    static int limit(LiveData d) {
        if (d == null) return -1;
        return section(d) && d.sectionLimit > 0 ? d.sectionLimit : d.camLimit;
    }

    /**
     * Driving above the limit: the speed above the camera's limit; in a section also an average above its limit.
     * False when the speed or the limit is unknown.
     */
    public static boolean over(LiveData d) {
        if (d == null) return false;
        boolean section = d.section == 1 || "section".equals(d.cam);
        int lim = section && d.sectionLimit > 0 ? d.sectionLimit : d.camLimit;
        if (!(lim > 0)) return false;
        if (section && d.sectionAvg > lim) return true;
        return !Double.isNaN(d.kph) && d.kph > lim;
    }

    static String zoneName(int code) {
        return code > 0 && code < ZONES.length ? ZONES[code] : ZONES[0];
    }

    private static String withLimit(String what, int lim) {
        return lim > 0 ? what + " " + lim : what;
    }

    /** "350 m" below 1 km, else "1.2 km" (one decimal, no locale) */
    static String dist(int m) {
        if (m < 1000) return m + " m";
        long tenths = Math.round(m / 100.0);
        return tenths / 10 + "." + tenths % 10 + " km";
    }
}
