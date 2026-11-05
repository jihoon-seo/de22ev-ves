/*
 * ves.render.check: analysis helpers for tests/render/run.sh (host only).
 * Part of the Java port of DasEtwas/enginesound <https://github.com/DasEtwas/enginesound>,
 * upstream commit e5fcca587397c0c8ba9c9d24874b951fed74d260 (this file is new code, no upstream source).
 * Copyright (c) 2020 DasEtwas. MIT License; the full notice is in THIRD_PARTY.md.
 * The Java port and its throttle extension are MIT as well.
 */
package ves.render.check;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ves.render.Wav;

/**
 * {@code Check pack <dir>}: checks a rendered pack (pack.txt + loops), prints LOOP lines and
 * PASS/FAIL lines, exits 1 on any FAIL.
 * {@code Check spectrum <wav> <rpm> <cylinders>}: informational spectrum summary.
 */
public final class Check {
    private Check() {
    }

    static final double SEAM_FACTOR = 1.2;
    static final double HARMONIC_TOL = 0.03;
    static final int HARMONICS = 4;
    static final double HARMONIC_PASS = 0.8;
    static final double CENTROID_DROP = 0.15;
    static final double CENTROID_PASS = 0.7;
    static final double RMS_DB = 3.0;

    private static int fails;

    static String fmt(String f, Object... a) {
        return String.format(Locale.ROOT, f, a);
    }

    static void result(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) {
            fails++;
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 2 && args[0].equals("pack")) {
            pack(new File(args[1]));
        } else if (args.length == 4 && args[0].equals("spectrum")) {
            spectrum(args[1], Double.parseDouble(args[2]), Integer.parseInt(args[3]));
        } else {
            System.err.println("usage: Check pack <dir> | Check spectrum <wav> <rpm> <cylinders>");
            System.exit(2);
        }
        System.exit(fails == 0 ? 0 : 1);
    }

    /** dominant DFT bin (exact bins of the whole signal) between fLo and fHi, refined parabolically */
    public static double dominant(float[] x, int sr, double fLo, double fHi) {
        int n = x.length;
        int kLo = Math.max(1, (int) Math.ceil(fLo * n / sr));
        int kHi = (int) Math.floor(fHi * n / sr);
        int best = kLo;
        double bestMag = -1;
        for (int k = kLo; k <= kHi; k++) {
            double m = Spectrum.goertzel(x, (double) k * sr / n, sr);
            if (m > bestMag) {
                bestMag = m;
                best = k;
            }
        }
        double a = Math.log(Spectrum.goertzel(x, (double) (best - 1) * sr / n, sr) + 1e-12);
        double b = Math.log(bestMag + 1e-12);
        double c = Math.log(Spectrum.goertzel(x, (double) (best + 1) * sr / n, sr) + 1e-12);
        double den = a - 2 * b + c;
        double d = den < 0 ? 0.5 * (a - c) / den : 0;
        return (best + Math.max(-0.5, Math.min(0.5, d))) * sr / n;
    }

    /** harmonic m of the firing frequency the peak matches within tolerance, or 0 */
    public static int firingHarmonic(double fPeak, double fFire) {
        for (int m = 1; m <= HARMONICS; m++) {
            if (Math.abs(fPeak / (m * fFire) - 1.0) <= HARMONIC_TOL) {
                return m;
            }
        }
        return 0;
    }

    public static double lowBandTop(double fFire, int sr) {
        return Math.min((HARMONICS + 0.5) * fFire, 0.45 * sr);
    }

    static final class Loop {
        String file;
        int rpm;
        boolean on;
        int end;
        double rmsDb;
        double peakDb;
        double centroid;
    }

    static void pack(File dir) throws IOException {
        String text = new String(java.nio.file.Files.readAllBytes(new File(dir, "pack.txt").toPath()), "UTF-8");
        String name = null;
        int cylinders = 0;
        boolean aligned = false;
        List<Loop> loops = new ArrayList<Loop>();
        for (String line : text.split("\n")) {
            String[] t = line.trim().split("\\s+");
            if (t[0].equals("name")) {
                name = t[1];
            } else if (t[0].equals("cylinders")) {
                cylinders = Integer.parseInt(t[1]);
            } else if (t[0].equals("aligned")) {
                aligned = t.length > 1 && t[1].equals("yes");
            } else if (t[0].equals("loop")) {
                Loop l = new Loop();
                l.file = t[1];
                for (int i = 2; i < t.length; i++) {
                    String[] p = t[i].split("=");
                    if (p[0].equals("rpm")) {
                        l.rpm = Integer.parseInt(p[1]);
                    } else if (p[0].equals("load")) {
                        l.on = p[1].equals("on");
                    } else if (p[0].equals("end")) {
                        l.end = Integer.parseInt(p[1]);
                    }
                }
                loops.add(l);
            }
        }
        result(name != null && cylinders > 0 && loops.size() == 20, name + ": pack.txt has name, cylinders, 20 loops");
        int seamOk = 0;
        int lenOk = 0;
        int cyclesOk = 0;
        int formatOk = 0;
        int onLoops = 0;
        int harmOk = 0;
        double maxPeak = 0;
        for (Loop l : loops) {
            int[] sr = new int[1];
            float[] x = Wav.read(new File(dir, l.file).getPath(), sr);
            if (x.length == l.end) {
                lenOk++;
            }
            // whole 4-stroke cycles: length = round(N * 120 * sr / rpm) for an integer N
            double cycle = 120.0 * sr[0] / l.rpm;
            long cycles = Math.round(x.length / cycle);
            cyclesOk += cycles >= 1 && Math.abs(x.length - cycles * cycle) <= 0.5 ? 1 : 0;
            // what the runtime loader takes: name, 22050 Hz, 0.2..2 s
            formatOk += l.file.matches("[a-z0-9_-]{1,60}[.]wav") && sr[0] == 22050
                    && x.length >= 22050 / 5 && x.length <= 22050 * 2 ? 1 : 0;
            double maxJump = 0;
            double peak = 0;
            for (int i = 0; i < x.length; i++) {
                peak = Math.max(peak, Math.abs(x[i]));
                if (i > 0) {
                    maxJump = Math.max(maxJump, Math.abs(x[i] - x[i - 1]));
                }
            }
            double seam = Math.abs(x[0] - x[x.length - 1]);
            boolean sOk = seam <= SEAM_FACTOR * maxJump;
            seamOk += sOk ? 1 : 0;
            maxPeak = Math.max(maxPeak, peak);
            double fFire = cylinders * l.rpm / 120.0;
            double fPeak = dominant(x, sr[0], 0.75 * l.rpm / 120.0, lowBandTop(fFire, sr[0]));
            int m = firingHarmonic(fPeak, fFire);
            if (l.on) {
                onLoops++;
                harmOk += m > 0 ? 1 : 0;
            }
            l.rmsDb = Spectrum.db(Spectrum.rms(x));
            l.peakDb = Spectrum.db(peak);
            l.centroid = Spectrum.centroid(Spectrum.magnitudes(x, 0), sr[0], 20.0);
            System.out.println(fmt("LOOP %-18s rpm=%4d load=%-3s len=%5d (%.3f s) rms=%6.1f dB peak=%5.1f dB"
                    + " seam=%.4f/%.4f%s fire=%6.1f Hz low_peak=%6.1f Hz%s centroid=%5.0f Hz",
                    l.file, l.rpm, l.on ? "on" : "off", x.length, x.length / (double) sr[0], l.rmsDb, l.peakDb,
                    seam, maxJump, sOk ? "" : "(!)", fFire, fPeak, m > 0 ? " (x" + m + ")" : " (not firing)",
                    l.centroid));
        }
        result(aligned, name + ": pack.txt says aligned yes");
        result(cyclesOk == loops.size(), name + ": every loop is a whole number of engine cycles +-0.5 sample ("
                + cyclesOk + "/" + loops.size() + ")");
        result(formatOk == loops.size(), name + ": file names, 22050 Hz, 0.2-2 s per loop (" + formatOk + "/"
                + loops.size() + ")");
        result(lenOk == loops.size(), name + ": loop lengths match pack.txt end= (" + lenOk + "/" + loops.size() + ")");
        result(seamOk == loops.size(), name + ": loop seam jump <= 1.2x max inner jump (" + seamOk + "/" + loops.size() + ")");
        result(Math.abs(Spectrum.db(maxPeak) + 1.0) < 0.1,
                name + ": loudest loop peaks at -1 dBFS (" + fmt("%.2f", Spectrum.db(maxPeak)) + ")");
        result(harmOk >= HARMONIC_PASS * onLoops, name + ": dominant low peak at firing frequency or harmonic x1..x"
                + HARMONICS + " +-3% (" + harmOk + "/" + onLoops + " on-load loops, need 80%)");
        int cOk = 0;
        int pairs = 0;
        double sum = 0;
        for (Loop on : loops) {
            if (!on.on) {
                continue;
            }
            sum += on.rmsDb;
            for (Loop off : loops) {
                if (!off.on && off.rpm == on.rpm) {
                    pairs++;
                    cOk += off.centroid <= (1.0 - CENTROID_DROP) * on.centroid ? 1 : 0;
                }
            }
        }
        result(pairs > 0 && cOk >= CENTROID_PASS * pairs, name + ": off-load centroid >= 15% below on-load ("
                + cOk + "/" + pairs + " rpm, need 70%)");
        double mean = sum / Math.max(1, onLoops);
        double lo = 1e9;
        double hi = -1e9;
        for (Loop l : loops) {
            if (l.on) {
                lo = Math.min(lo, l.rmsDb);
                hi = Math.max(hi, l.rmsDb);
            }
        }
        result(hi - mean <= RMS_DB && mean - lo <= RMS_DB, name + ": on-load RMS within +-3 dB across rpm ("
                + fmt("%.1f..%.1f dB, mean %.1f", lo, hi, mean) + ")");
        double offMean = 0;
        int offN = 0;
        for (Loop l : loops) {
            if (!l.on) {
                offMean += l.rmsDb;
                offN++;
            }
        }
        System.out.println(fmt("INFO %s: off-load RMS mean %.1f dB (%.1f dB vs on-load)", name, offMean / offN,
                offMean / offN - mean));
    }

    static void spectrum(String wav, double rpm, int cylinders) throws IOException {
        int[] sr = new int[1];
        float[] x = Wav.read(wav, sr);
        double fFire = cylinders * rpm / 120.0;
        double[] mag = Spectrum.magnitudes(x, 0);
        int n = (mag.length - 1) * 2;
        double top = 0;
        for (double v : mag) {
            top = Math.max(top, v);
        }
        System.out.println(fmt("SPECTRUM %s: %d samples at %d Hz, rpm %.0f, %d cyl, firing %.1f Hz",
                new File(wav).getName(), x.length, sr[0], rpm, cylinders, fFire));
        System.out.println(fmt("SPECTRUM rms %.1f dBFS, centroid %.0f Hz", Spectrum.db(Spectrum.rms(x)),
                Spectrum.centroid(mag, sr[0], 20.0)));
        double fPeak = dominant(x, sr[0], 0.75 * rpm / 120.0, lowBandTop(fFire, sr[0]));
        int m = firingHarmonic(fPeak, fFire);
        System.out.println(fmt("SPECTRUM dominant low peak %.1f Hz = %.2f x firing%s", fPeak, fPeak / fFire,
                m > 0 ? " (harmonic x" + m + ")" : " (not a firing harmonic)"));
        // strongest local maxima, at least 3 bins apart
        List<int[]> peaks = new ArrayList<int[]>();
        for (int k = 2; k < mag.length - 2; k++) {
            if (mag[k] > mag[k - 1] && mag[k] >= mag[k + 1] && mag[k] > mag[k - 2] && mag[k] >= mag[k + 2]) {
                peaks.add(new int[] {k});
            }
        }
        final double[] fm = mag;
        java.util.Collections.sort(peaks, new java.util.Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                return Double.compare(fm[b[0]], fm[a[0]]);
            }
        });
        StringBuilder sb = new StringBuilder("SPECTRUM top peaks (Hz / dB re max / x cycle order):");
        for (int i = 0; i < Math.min(10, peaks.size()); i++) {
            int k = peaks.get(i)[0];
            double f = (double) k * sr[0] / n;
            sb.append(fmt(" %.0f/%.1f/%.1f", f, Spectrum.db(mag[k] / top), f / (rpm / 120.0)));
        }
        System.out.println(sb);
    }
}
