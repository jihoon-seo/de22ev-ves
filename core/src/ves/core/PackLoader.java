package ves.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads and validates a sound pack. Packs come built in (rendered by tools/render) or from the owner's USB
 * stick, so every byte is untrusted: only PackError leaves load(), never a RuntimeException (K4 fuzz test).
 *
 * <pre>
 * # comment
 * name   v8 cross-plane
 * cylinders 8
 * idle   800
 * redline 6500
 * aligned yes          (optional: loops start at the same crank angle, whole cycles; see Pack.aligned)
 * level -4.5           (optional: level trim in dB, -24..12, at most 2 decimals; see Pack.level)
 * loop v8_0800_on.wav rpm=800 load=on [start=0] [end=17640]
 * loop v8_0800_off.wav rpm=800 load=off
 * loop v8_0800_mid.wav rpm=800 load=0.45   (a load layer between off (0) and on (1), up to 3 decimals)
 * </pre>
 * Rules: WAV PCM 16-bit mono 22050 Hz; file names [a-z0-9_-]+.wav (no folders); at most 4 load layers (on = 1,
 * off = 0); the top one (above 0) holds at least 3 loops covering at most 1200 rpm at the low end and at least
 * 4000 at the high end; the others are optional, at least 2 loops each; without a load-0 layer the lowest one
 * stands in for it (the on-load loops in a pack without off-load ones); each loop 0.2..2 s; at most 96 loops and 2,000,000
 * samples (4 MB; a physics bake: 24 rpm x 3 loads of about 1 s); loop points inside the file. Duplicate rpm
 * per layer: the later line wins.
 */
public final class PackLoader {
    public interface Source {
        /** the named file of the pack (name already validated), at most maxBytes long */
        byte[] read(String name, int maxBytes) throws IOException;
    }

    public static final int RATE = 22050;
    public static final int MAX_LOOPS = 96, MAX_SAMPLES = 2_000_000;
    static final int MIN_LOOP = RATE / 5, MAX_LOOP = RATE * 2, MAX_LOADS = 4;
    static final int MAX_WAV_BYTES = MAX_LOOP * 2 + 64 * 1024, MAX_TXT_BYTES = 16 * 1024;

    private PackLoader() {}

    public static Pack load(Source src) throws PackError {
        try {
            return parse(src);
        } catch (PackError e) {
            throw e;
        } catch (IOException e) {
            throw new PackError("can't read: " + e.getMessage());
        } catch (RuntimeException e) {
            throw new PackError("broken pack: " + e);
        }
    }

    private static Pack parse(Source src) throws PackError, IOException {
        String text = new String(src.read("pack.txt", MAX_TXT_BYTES), "UTF-8");
        String name = null;
        int cyl = 4, idle = 800, redline = 6500;
        boolean aligned = false;
        double level = 1;
        TreeMap<Integer, List<Pack.Loop>> byLoad = new TreeMap<Integer, List<Pack.Loop>>(); // load in thousandths
        int lineNo = 0, total = 0, loops = 0;
        for (String raw : text.split("\n")) {
            lineNo++;
            String l = raw.trim();
            if (l.length() == 0 || l.startsWith("#")) continue;
            String[] f = l.split("\\s+");
            String k = f[0];
            if (k.equals("name")) name = l.substring(4).trim();
            else if (k.equals("cylinders")) cyl = num(f, 1, 1, 16, lineNo);
            else if (k.equals("idle")) idle = num(f, 1, 300, 2000, lineNo);
            else if (k.equals("redline")) redline = num(f, 1, 2000, 12000, lineNo);
            else if (k.equals("aligned")) aligned = f.length > 1 && f[1].equals("yes");
            else if (k.equals("level")) level = levelDb(f, lineNo);
            else if (k.equals("loop")) {
                if (loops++ >= MAX_LOOPS) throw new PackError("more than " + MAX_LOOPS + " loops");
                if (f.length < 2 || !f[1].matches("[a-z0-9_-]{1,60}\\.wav")) throw new PackError("line " + lineNo + ": bad file name");
                int rpm = -1, start = 0, end = -1, load = -1;
                for (int i = 2; i < f.length; i++) {
                    int eq = f[i].indexOf('=');
                    if (eq <= 0) throw new PackError("line " + lineNo + ": " + f[i]);
                    String key = f[i].substring(0, eq), v = f[i].substring(eq + 1);
                    if (key.equals("rpm")) rpm = parseInt(v, 300, 12000, lineNo);
                    else if (key.equals("load") && v.matches("on|off|0(\\.[0-9]{1,3})?|1(\\.0{1,3})?"))
                        load = v.equals("on") ? 1000 : v.equals("off") ? 0 : (int) Math.round(Double.parseDouble(v) * 1000);
                    else if (key.equals("start")) start = parseInt(v, 0, Integer.MAX_VALUE, lineNo);
                    else if (key.equals("end")) end = parseInt(v, 1, Integer.MAX_VALUE, lineNo);
                    else throw new PackError("line " + lineNo + ": " + f[i]);
                }
                if (rpm < 0 || load < 0) throw new PackError("line " + lineNo + ": needs rpm= and load=on|off|0..1");
                short[] pcm = Wav.mono16(src.read(f[1], MAX_WAV_BYTES), RATE, f[1]);
                if (end < 0) end = pcm.length;
                if (start >= end || end > pcm.length) throw new PackError(f[1] + ": loop points outside the file");
                int n = end - start;
                if (n < MIN_LOOP || n > MAX_LOOP) throw new PackError(f[1] + ": loop must be 0.2 to 2 s");
                total += n;
                if (total > MAX_SAMPLES) throw new PackError("pack larger than " + MAX_SAMPLES + " samples");
                List<Pack.Loop> layer = byLoad.get(load);
                if (layer == null) {
                    if (byLoad.size() >= MAX_LOADS) throw new PackError("line " + lineNo + ": more than " + MAX_LOADS + " loads");
                    byLoad.put(load, layer = new ArrayList<Pack.Loop>());
                }
                layer.add(new Pack.Loop(rpm, Arrays.copyOfRange(pcm, start, end)));
            } else throw new PackError("line " + lineNo + ": unknown key " + k);
        }
        if (name == null || name.length() == 0 || name.length() > 40) throw new PackError("needs a name (1-40 characters)");
        if (idle >= redline) throw new PackError("idle must be below redline");
        if (byLoad.isEmpty() || byLoad.lastKey() == 0) throw new PackError("needs at least 3 on-load loops");
        // the layers by rising load; without a load-0 layer the lowest stands in for it
        int n = byLoad.size(), base = byLoad.firstKey() == 0 ? 0 : 1, i = base;
        double[] loads = new double[n + base];
        Pack.Loop[][] layers = new Pack.Loop[n + base][];
        for (Map.Entry<Integer, List<Pack.Loop>> e : byLoad.entrySet()) {
            loads[i] = e.getKey() / 1000.0;
            layers[i++] = sorted(e.getValue());
        }
        if (base == 1) layers[0] = layers[1];
        Pack.Loop[] a = layers[layers.length - 1];
        if (a.length < 3) throw new PackError("needs at least 3 on-load loops");
        if (a[0].rpm > 1200 || a[a.length - 1].rpm < 4000) throw new PackError("on-load loops must reach from <=1200 to >=4000 rpm");
        for (int k = base; k < layers.length - 1; k++)
            if (layers[k].length == 1) throw new PackError("needs 0 or at least 2 loops at load " + loads[k]);
        return new Pack(name, cyl, idle, redline, aligned, level, loads, layers);
    }

    private static Pack.Loop[] sorted(List<Pack.Loop> l) {
        List<Pack.Loop> u = new ArrayList<Pack.Loop>();
        for (Pack.Loop x : l) {
            for (int i = u.size() - 1; i >= 0; i--) if (u.get(i).rpm == x.rpm) u.remove(i);
            u.add(x);
        }
        Pack.Loop[] a = u.toArray(new Pack.Loop[0]);
        Arrays.sort(a, new Comparator<Pack.Loop>() {
            public int compare(Pack.Loop x, Pack.Loop y) {
                return x.rpm - y.rpm;
            }
        });
        return a;
    }

    private static int num(String[] f, int i, int lo, int hi, int line) throws PackError {
        if (f.length <= i) throw new PackError("line " + line + ": missing value");
        return parseInt(f[i], lo, hi, line);
    }

    /** "level <dB>": -24..12 dB, plain decimal (no exponent, NaN or infinity), as a gain */
    private static double levelDb(String[] f, int line) throws PackError {
        if (f.length != 2) throw new PackError("line " + line + ": level takes one value in dB");
        if (!f[1].matches("[-+]?[0-9]{1,2}(\\.[0-9]{1,2})?")) throw new PackError("line " + line + ": not a level in dB: " + f[1]);
        double db = Double.parseDouble(f[1]);
        if (db < -24 || db > 12) throw new PackError("line " + line + ": level " + f[1] + " out of range (-24..12 dB)");
        return Math.pow(10, db / 20);
    }

    private static int parseInt(String v, int lo, int hi, int line) throws PackError {
        if (!v.matches("[0-9]{1,9}")) throw new PackError("line " + line + ": not a number: " + v);
        int x = Integer.parseInt(v);
        if (x < lo || x > hi) throw new PackError("line " + line + ": " + v + " out of range");
        return x;
    }
}
