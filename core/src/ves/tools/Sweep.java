package ves.tools;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Random;

/**
 * Synthetic drive traces for Replay.
 * <pre>java ves.tools.Sweep &lt;out.csv&gt; [city|accel|cruise|navi|ready_off]   (default city)</pre>
 * Rows every 100 ms: speed, pedal, motor_kw, accel_g, brake on every row; gear and ready every 200 ms (empty cell
 * = no update, like the real CAN cadence). Events: "mark" (scenario phase markers used by tests),
 * "yield_start:navi" / "yield_end:navi". Simple longitudinal physics for a 1750 kg EV.
 */
public final class Sweep {
    public static final String[] SCENARIOS = {"city", "accel", "cruise", "navi", "ready_off"};
    public static final String HEADER = "t_ms,speed,pedal,motor_kw,accel_g,gear,ready,brake,event";

    static final double MASS = 1750;
    static final int P = 0, D = 5;

    private final StringBuilder sb = new StringBuilder(64 * 1024);
    private final Random rnd = new Random(42);
    private long t;
    private double v; // km/h
    private int row;
    private int gear = P, ready = 1;
    private String event = "";

    private Sweep() {
        sb.append(HEADER).append('\n');
    }

    public static String generate(String scenario) {
        Sweep s = new Sweep();
        String sc = scenario == null ? "city" : scenario.trim().toLowerCase(java.util.Locale.US);
        if (sc.equals("accel")) {
            s.stop(1000, P, 1);
            s.stop(1500, D, 1);
            s.event("mark");
            s.accel(100, 100, 15000);
            s.event("mark");
            s.cruise(2000, 22, 0);
            s.decel(0.15, 30000);
            s.stop(1000, D, 1);
            s.stop(500, P, 1);
        } else if (sc.equals("cruise")) {
            s.stop(500, P, 1);
            s.stop(1000, D, 1);
            s.accel(50, 80, 20000);
            s.cruise(3000, 20, 1.5);
            s.event("mark");
            s.cruise(25000, 20, 1.5);
            s.event("mark");
            s.decel(0.1, 30000);
            s.stop(1000, D, 1);
        } else if (sc.equals("navi")) {
            s.stop(500, P, 1);
            s.stop(1000, D, 1);
            s.accel(40, 60, 15000);
            s.cruise(5000, 18, 1);
            s.event("yield_start:navi");
            s.cruise(4000, 18, 1);
            s.event("yield_end:navi");
            s.cruise(6000, 18, 1);
            s.decel(0.1, 30000);
            s.stop(1000, D, 1);
        } else if (sc.equals("ready_off")) {
            s.stop(10000, P, 0);
        } else { // city
            s.stop(1000, P, 1);
            s.stop(2000, D, 1);
            for (int k = 0; k < 2; k++) {
                s.accel(40, 45, 10000);
                s.cruise(4000, 18, 1.5);
                s.decel(0.12, 20000);
                s.stop(3000, D, 1);
            }
            s.stop(1000, P, 1);
        }
        s.emit(0, 0, 0, 0); // final row closes the trace
        return s.sb.toString();
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: java ves.tools.Sweep <out.csv> [city|accel|cruise|navi|ready_off]");
            System.exit(2);
        }
        String sc = args.length > 1 ? args[1] : "city";
        String csv = generate(sc);
        Writer w = new OutputStreamWriter(new FileOutputStream(args[0]), "UTF-8");
        try {
            w.write(csv);
        } finally {
            w.close();
        }
        int rows = 0;
        for (int i = 0; i < csv.length(); i++) if (csv.charAt(i) == '\n') rows++;
        System.out.println("sweep " + sc + ": " + (rows - 1) + " rows to " + args[0]);
    }

    // ------------------------------------------------------------------ segments

    private void event(String e) {
        event = event.length() == 0 ? e : event + ";" + e;
    }

    private void stop(long ms, int g, int rdy) {
        gear = g;
        ready = rdy;
        v = 0;
        for (long e = t + ms; t < e; ) emit(0, 0, 0, gear == D ? 12 : 0);
    }

    private void accel(double pedal, double untilKph, long maxMs) {
        gear = D;
        for (long e = t + maxMs; t < e && v < untilKph; ) {
            double ad = pedal / 100.0 * 3.8 * (1 - v / 185.0);
            double a = ad - drag(v);
            double vms = v / 3.6;
            double kw = Math.min(150, MASS * ad * vms / 1000 + 1);
            v = Math.max(0, v + a * 0.1 * 3.6);
            emit(pedal, kw, a / 9.81, 0);
        }
    }

    private void cruise(long ms, double pedalBase, double noise) {
        for (long e = t + ms; t < e; ) {
            double vms = v / 3.6;
            double kw = MASS * drag(v) * vms / 1000 + 1;
            double p = Math.max(0, Math.min(100, pedalBase + noise * rnd.nextGaussian()));
            emit(p, kw + 0.5 * noise * rnd.nextGaussian(), 0.004 * rnd.nextGaussian(), 0);
        }
    }

    private void decel(double g, long maxMs) {
        for (long e = t + maxMs; t < e && v > 0; ) {
            double vms = v / 3.6;
            double kw = Math.max(-60, -MASS * g * 9.81 * vms / 1000 * 0.75);
            v = Math.max(0, v - g * 9.81 * 0.1 * 3.6);
            emit(0, kw, -g, v < 15 ? 8 : 0);
        }
        v = 0;
    }

    private static double drag(double kph) {
        return kph <= 0 ? 0 : 0.12 + 0.000035 * kph * kph;
    }

    private void emit(double pedal, double kw, double accG, double brake) {
        sb.append(t).append(',').append(f(v)).append(',').append(f(pedal)).append(',').append(f(Math.round(kw)))
                .append(',').append(f(accG)).append(',');
        if (row % 2 == 0) sb.append(gear);
        sb.append(',');
        if (row % 2 == 0) sb.append(ready);
        sb.append(',').append(f(brake)).append(',').append(event).append('\n');
        event = "";
        t += 100;
        row++;
    }

    static String f(double x) {
        if (Double.isNaN(x)) return "";
        long m = Math.round(x * 1000);
        String sign = m < 0 ? "-" : "";
        m = Math.abs(m);
        long ip = m / 1000, fp = m % 1000;
        if (fp == 0) return sign + ip;
        String fs = String.valueOf(1000 + fp).substring(1);
        while (fs.endsWith("0")) fs = fs.substring(0, fs.length() - 1);
        return sign + ip + "." + fs;
    }
}
