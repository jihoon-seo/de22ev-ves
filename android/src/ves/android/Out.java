package ves.android;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** vesd's own log: stdout, which HMG-API keeps as the service log (size-capped). */
final class Out {
    private static final SimpleDateFormat F = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static String last;
    private static int repeats;

    private Out() {}

    static synchronized void log(String msg) {
        if (msg.equals(last)) {
            if (++repeats % 100 != 0) return;
            msg = msg + " (x" + repeats + ")";
        } else {
            last = msg;
            repeats = 0;
        }
        System.out.println(F.format(new Date()) + " " + msg);
        System.out.flush();
    }

    static String stamp() {
        synchronized (F) {
            return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        }
    }
}
