package ves.gauge;

/**
 * vesd's LIVE answer (VesCore.liveLine), one line of key=value:
 * {@code rpm= gear= sh= sel= kph= kw= shift= dm= profile= state= red= max=}; "-" is unknown (NaN, or -1 for codes).
 * 0.2.13 appends {@code on=} (settings.enabled 1|0) and vesd's camera fields (ves.core.NavCam.liveFields):
 * {@code nav= cam= lim= dist= zone= sec= slim= savg= ssrc= sdist=}. A key an older vesd leaves out stays unknown.
 */
public final class LiveData {
    public double rpm, kph, kw = Double.NaN, shiftRpm = Double.NaN, redline = Double.NaN, max = 8000;
    /** virtual gear (ICE_SHIFT 1..6, else 0), car selector code (Sig.GEAR_*, -1 unknown), drive-mode raw (-1 unknown) */
    public int gear, selector = -1, driveMode = -1;
    public boolean shifting;
    public String profile = "", state = "";
    /** 0.2.13, -1 unknown: VES on (1) / off (0); seconds since the navigation's last ClusterService line */
    public int on = -1, navAge = -1;
    /** the camera ahead: its limit km/h, distance m, the trap line's raw warningZone */
    public int camLimit = -1, camDist = -1, zone = -1;
    /** section enforcement: 1 active / 0 not, its limit km/h, average km/h, remaining distance (raw rangeCamDist) */
    public int section = -1, sectionLimit = -1, sectionAvg = -1, sectionDist = -1;
    /** cam: none|speed|mobile|limit|zone|section, "-" unknown; sectionSrc: nav|own, "-" none */
    public String cam = "-", sectionSrc = "-";

    /** null for an error answer, no answer or a line without rpm */
    public static LiveData parse(String text) {
        if (text == null) return null;
        for (String line : text.split("\n")) {
            if (line.startsWith("error")) return null;
            if (line.indexOf("rpm=") < 0) continue;
            LiveData d = new LiveData();
            boolean rpm = false;
            for (String kv : line.trim().split("\\s+")) {
                int eq = kv.indexOf('=');
                if (eq <= 0) continue;
                String k = kv.substring(0, eq), v = kv.substring(eq + 1);
                if (k.equals("rpm")) {
                    d.rpm = num(v);
                    rpm = !Double.isNaN(d.rpm);
                } else if (k.equals("gear")) d.gear = code(v, 0);
                else if (k.equals("sh")) d.shifting = v.equals("1");
                else if (k.equals("sel")) d.selector = code(v, -1);
                else if (k.equals("kph")) d.kph = num(v);
                else if (k.equals("kw")) d.kw = num(v);
                else if (k.equals("shift")) d.shiftRpm = num(v);
                else if (k.equals("dm")) d.driveMode = code(v, -1);
                else if (k.equals("profile")) d.profile = v;
                else if (k.equals("state")) d.state = v;
                else if (k.equals("red")) d.redline = num(v);
                else if (k.equals("max")) d.max = num(v);
                else if (k.equals("on")) d.on = code(v, -1);
                else if (k.equals("nav")) d.navAge = code(v, -1);
                else if (k.equals("cam")) d.cam = v;
                else if (k.equals("lim")) d.camLimit = code(v, -1);
                else if (k.equals("dist")) d.camDist = code(v, -1);
                else if (k.equals("zone")) d.zone = code(v, -1);
                else if (k.equals("sec")) d.section = code(v, -1);
                else if (k.equals("slim")) d.sectionLimit = code(v, -1);
                else if (k.equals("savg")) d.sectionAvg = code(v, -1);
                else if (k.equals("ssrc")) d.sectionSrc = v;
                else if (k.equals("sdist")) d.sectionDist = code(v, -1);
            }
            return rpm ? d : null;
        }
        return null;
    }

    static double num(String v) {
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    static int code(String v, int unknown) {
        double x = num(v);
        return Double.isNaN(x) ? unknown : (int) x;
    }
}
