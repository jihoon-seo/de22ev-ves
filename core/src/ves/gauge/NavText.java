package ves.gauge;

/**
 * The camera line on the main screen and the full gauge (0.2.13), Korean and short, from LIVE's camera fields:
 * "단속 60 · 350 m", "이동식 단속 50 · 1.2 km", "제한속도 80", "주의 구간 · 350 m", "구간단속 80 · 평균 87"; "" when there is no
 * camera or nothing is known (an older vesd).
 */
public final class NavText {
    static final String SEP = " · ";

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
        else if ("mobile".equals(d.cam)) s = withLimit("이동식 단속", d.camLimit);
        else if ("limit".equals(d.cam)) s = withLimit("제한속도", d.camLimit);
        else if ("zone".equals(d.cam)) s = "주의 구간";
        else return "";
        return d.camDist > 0 ? s + SEP + dist(d.camDist) : s;
    }

    /** the full gauge's text under its limit sign (0.2.13): what the sign doesn't show already */
    public static String signDetail(LiveData d) {
        String full = line(d);
        if (full.length() == 0) return "";
        if (d.section == 1 || "section".equals(d.cam)) return d.sectionAvg > 0 ? "구간단속" + SEP + "평균 " + d.sectionAvg : "구간단속";
        if (!(d.camLimit > 0)) return full; // a warning triangle instead of a sign: the whole line
        String far = d.camDist > 0 ? dist(d.camDist) : "";
        if ("mobile".equals(d.cam)) return far.length() > 0 ? "이동식" + SEP + far : "이동식";
        if (far.length() > 0) return far;
        return "speed".equals(d.cam) ? "단속 카메라" : "제한속도";
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
