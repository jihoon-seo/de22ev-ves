package ves.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

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
 * loop v8_0800_on.wav rpm=800 load=on [start=0] [end=17640]
 * loop v8_0800_off.wav rpm=800 load=off
 * </pre>
 * Rules: WAV PCM 16-bit mono 22050 Hz; file names [a-z0-9_-]+.wav (no folders); at least 3 on-load loops
 * covering at most 1200 rpm at the low end and at least 4000 at the high end; off-load loops optional (the
 * on-load ones stand in), at least 2 when present; each loop 0.2..2 s; at most 64 loops and 2.5 MB of
 * samples; loop points inside the file. Duplicate rpm per layer: the later line wins.
 */
public final class PackLoader {
    public interface Source {
        /** the named file of the pack (name already validated), at most maxBytes long */
        byte[] read(String name, int maxBytes) throws IOException;
    }

    public static final int RATE = 22050;
    public static final int MAX_LOOPS = 64, MAX_SAMPLES = 2_500_000 / 2;
    static final int MIN_LOOP = RATE / 5, MAX_LOOP = RATE * 2;
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
        List<Pack.Loop> on = new ArrayList<Pack.Loop>(), off = new ArrayList<Pack.Loop>();
        int lineNo = 0, total = 0;
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
            else if (k.equals("loop")) {
                if (on.size() + off.size() >= MAX_LOOPS) throw new PackError("more than " + MAX_LOOPS + " loops");
                if (f.length < 2 || !f[1].matches("[a-z0-9_-]{1,60}\\.wav")) throw new PackError("line " + lineNo + ": bad file name");
                int rpm = -1, start = 0, end = -1;
                String load = null;
                for (int i = 2; i < f.length; i++) {
                    int eq = f[i].indexOf('=');
                    if (eq <= 0) throw new PackError("line " + lineNo + ": " + f[i]);
                    String key = f[i].substring(0, eq), v = f[i].substring(eq + 1);
                    if (key.equals("rpm")) rpm = parseInt(v, 300, 12000, lineNo);
                    else if (key.equals("load") && (v.equals("on") || v.equals("off"))) load = v;
                    else if (key.equals("start")) start = parseInt(v, 0, Integer.MAX_VALUE, lineNo);
                    else if (key.equals("end")) end = parseInt(v, 1, Integer.MAX_VALUE, lineNo);
                    else throw new PackError("line " + lineNo + ": " + f[i]);
                }
                if (rpm < 0 || load == null) throw new PackError("line " + lineNo + ": needs rpm= and load=on|off");
                short[] pcm = Wav.mono16(src.read(f[1], MAX_WAV_BYTES), RATE, f[1]);
                if (end < 0) end = pcm.length;
                if (start >= end || end > pcm.length) throw new PackError(f[1] + ": loop points outside the file");
                int n = end - start;
                if (n < MIN_LOOP || n > MAX_LOOP) throw new PackError(f[1] + ": loop must be 0.2 to 2 s");
                total += n;
                if (total > MAX_SAMPLES) throw new PackError("pack larger than 2.5 MB of samples");
                (load.equals("on") ? on : off).add(new Pack.Loop(rpm, Arrays.copyOfRange(pcm, start, end)));
            } else throw new PackError("line " + lineNo + ": unknown key " + k);
        }
        if (name == null || name.length() == 0 || name.length() > 40) throw new PackError("needs a name (1-40 characters)");
        if (idle >= redline) throw new PackError("idle must be below redline");
        Pack.Loop[] a = sorted(on), b = sorted(off);
        if (a.length < 3) throw new PackError("needs at least 3 on-load loops");
        if (a[0].rpm > 1200 || a[a.length - 1].rpm < 4000) throw new PackError("on-load loops must reach from <=1200 to >=4000 rpm");
        if (b.length == 1) throw new PackError("needs 0 or at least 2 off-load loops");
        return new Pack(name, cyl, idle, redline, a, b.length == 0 ? a : b);
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

    private static int parseInt(String v, int lo, int hi, int line) throws PackError {
        if (!v.matches("[0-9]{1,9}")) throw new PackError("line " + line + ": not a number: " + v);
        int x = Integer.parseInt(v);
        if (x < lo || x > hi) throw new PackError("line " + line + ": " + v + " out of range");
        return x;
    }
}
