package ves.gauge;

/**
 * vesd's LIVE answer (VesCore.liveLine), one line of key=value:
 * {@code rpm= gear= sh= sel= kph= kw= shift= dm= profile= state= red= max=}; "-" is unknown (NaN, or -1 for codes).
 */
public final class LiveData {
    public double rpm, kph, kw = Double.NaN, shiftRpm = Double.NaN, redline = Double.NaN, max = 8000;
    /** virtual gear (ICE_SHIFT 1..6, else 0), car selector code (Sig.GEAR_*, -1 unknown), drive-mode raw (-1 unknown) */
    public int gear, selector = -1, driveMode = -1;
    public boolean shifting;
    public String profile = "", state = "";

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
