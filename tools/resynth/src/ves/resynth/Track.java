package ves.resynth;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A cycle-frequency track: fc (engine cycles per second, rpm / 120 for a 4-stroke) and the crank phase theta
 * (cycles) at frame times t0 + k * dt. Between frames theta is a cubic Hermite curve with slope fc, so
 * the phase stays consistent over a whole file (order phases can be compared across frames).
 */
public final class Track {
    public final double t0, dt;
    public final double[] fc, theta;

    public Track(double t0, double dt, double[] fc, double[] theta) {
        if (fc.length != theta.length || fc.length < 2 || !(dt > 0)) throw new IllegalArgumentException("track shape");
        this.t0 = t0;
        this.dt = dt;
        this.fc = fc;
        this.theta = theta;
    }

    /** theta from fc by the trapezoid rule, theta(t0) = 0 */
    public static Track fromFc(double t0, double dt, double[] fc) {
        double[] th = new double[fc.length];
        for (int k = 1; k < fc.length; k++) th[k] = th[k - 1] + 0.5 * (fc[k - 1] + fc[k]) * dt;
        return new Track(t0, dt, fc, th);
    }

    public int frames() {
        return fc.length;
    }

    public double time(int k) {
        return t0 + k * dt;
    }

    public double tEnd() {
        return time(fc.length - 1);
    }

    public double fcAt(double t) {
        double p = (t - t0) / dt;
        if (p <= 0) return fc[0];
        int k = (int) p;
        if (k >= fc.length - 1) return fc[fc.length - 1];
        double u = p - k;
        // derivative of the Hermite theta curve
        double h00 = 6 * u * u - 6 * u, h10 = 3 * u * u - 4 * u + 1, h01 = -6 * u * u + 6 * u, h11 = 3 * u * u - 2 * u;
        return (h00 * theta[k] + h01 * theta[k + 1]) / dt + h10 * fc[k] + h11 * fc[k + 1];
    }

    public double rpmAt(double t) {
        return 120 * fcAt(t);
    }

    /** crank phase in cycles at time t (linear beyond the ends) */
    public double thetaAt(double t) {
        double p = (t - t0) / dt;
        if (p <= 0) return theta[0] + (t - t0) * fc[0];
        int k = (int) p;
        if (k >= fc.length - 1) return theta[fc.length - 1] + (t - tEnd()) * fc[fc.length - 1];
        double u = p - k, u2 = u * u, u3 = u2 * u;
        double h00 = 2 * u3 - 3 * u2 + 1, h10 = u3 - 2 * u2 + u, h01 = -2 * u3 + 3 * u2, h11 = u3 - u2;
        return h00 * theta[k] + h10 * dt * fc[k] + h01 * theta[k + 1] + h11 * dt * fc[k + 1];
    }

    /** theta at every sample time i / sr, i = 0..n-1 */
    public double[] thetaSamples(int n, int sr) {
        double[] th = new double[n];
        for (int i = 0; i < n; i++) th[i] = thetaAt(i / (double) sr);
        return th;
    }

    // ------------------------------------------------------------------ text file (private data: *.track.txt)

    public void write(File f, String comment) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("# ves.resynth track v1").append('\n');
        if (comment != null) b.append("# ").append(comment.replace('\n', ' ')).append('\n');
        b.append("# frame: t_s fc_hz theta_cycles (rpm = 120 * fc)\n");
        b.append(String.format(Locale.ROOT, "dt %.9f%n", dt));
        for (int k = 0; k < fc.length; k++)
            b.append(String.format(Locale.ROOT, "f %.6f %.7f %.7f%n", time(k), fc[k], theta[k]));
        Files.writeText(f, b.toString());
    }

    public static Track read(File f) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        try {
            double dt = -1, t0 = Double.NaN;
            List<double[]> rows = new ArrayList<double[]>();
            String l;
            while ((l = r.readLine()) != null) {
                l = l.trim();
                if (l.length() == 0 || l.startsWith("#")) continue;
                String[] p = l.split("\\s+");
                if (p[0].equals("dt")) dt = Double.parseDouble(p[1]);
                else if (p[0].equals("f") && p.length >= 4) {
                    if (rows.isEmpty()) t0 = Double.parseDouble(p[1]);
                    rows.add(new double[] {Double.parseDouble(p[2]), Double.parseDouble(p[3])});
                } else throw new IOException(f + ": bad line: " + l);
            }
            double[] fc = new double[rows.size()], th = new double[rows.size()];
            for (int k = 0; k < fc.length; k++) {
                fc[k] = rows.get(k)[0];
                th[k] = rows.get(k)[1];
            }
            return new Track(t0, dt, fc, th);
        } finally {
            r.close();
        }
    }
}
