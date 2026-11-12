package ves.resynth;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * RIFF/WAVE PCM 16-bit mono reader and writer. The reader walks the chunks (LIST, junk and other unknown
 * chunks are skipped, odd sizes padded to even as RIFF says) and returns the exact samples, s / 32768.
 * Anything else (not PCM, stereo, not 16-bit, a data chunk longer than the file) is an IOException:
 * inputs are untrusted.
 */
public final class WavIO {
    private WavIO() {
    }

    public static final class Audio {
        public final int rate;
        /** samples, s / 32768.0 */
        public final double[] x;

        public Audio(int rate, double[] x) {
            this.rate = rate;
            this.x = x;
        }
    }

    public static Audio read(File f) throws IOException {
        long len = f.length();
        if (len > 512L * 1024 * 1024) throw new IOException(f + ": larger than 512 MB");
        byte[] b = new byte[(int) len];
        InputStream in = new FileInputStream(f);
        try {
            int off = 0;
            while (off < b.length) {
                int r = in.read(b, off, b.length - off);
                if (r < 0) throw new IOException(f + ": short read");
                off += r;
            }
        } finally {
            in.close();
        }
        return read(b);
    }

    public static Audio read(byte[] b) throws IOException {
        if (b.length < 12 || !tag(b, 0, "RIFF") || !tag(b, 8, "WAVE")) throw new IOException("not a RIFF/WAVE file");
        int pos = 12, rate = -1;
        while (pos + 8 <= b.length) {
            long size = le32(b, pos + 4) & 0xffffffffL;
            int body = pos + 8;
            if (tag(b, pos, "fmt ")) {
                if (size < 16 || body + 16 > b.length) throw new IOException("bad fmt chunk");
                int format = le16(b, body), ch = le16(b, body + 2), bits = le16(b, body + 14);
                rate = le32(b, body + 4);
                if (format != 1) throw new IOException("not PCM (format " + format + ")");
                if (ch != 1) throw new IOException("not mono (" + ch + " channels)");
                if (bits != 16) throw new IOException("not 16-bit (" + bits + " bits)");
                if (rate <= 0) throw new IOException("bad sample rate " + rate);
            } else if (tag(b, pos, "data")) {
                if (rate < 0) throw new IOException("data before fmt");
                if (body + size > b.length) throw new IOException("truncated: data chunk " + size + " bytes, file has "
                        + (b.length - body));
                if ((size & 1) != 0) throw new IOException("odd data size for 16-bit samples");
                int n = (int) (size / 2);
                double[] x = new double[n];
                for (int i = 0; i < n; i++) x[i] = (short) le16(b, body + 2 * i) / 32768.0;
                return new Audio(rate, x);
            }
            long next = body + size + (size & 1);
            if (next > b.length) throw new IOException("truncated chunk");
            pos = (int) next;
        }
        throw new IOException("no data chunk");
    }

    /** x clipped to [-1, 1) and rounded to PCM16 */
    public static short[] toPcm(double[] x) {
        short[] s = new short[x.length];
        for (int i = 0; i < x.length; i++) {
            double v = StrictMath.rint(x[i] * 32768.0);
            s[i] = (short) (v > 32767 ? 32767 : v < -32768 ? -32768 : v);
        }
        return s;
    }

    public static void write(File f, double[] x, int rate) throws IOException {
        write16(f, toPcm(x), rate);
    }

    public static void write16(File f, short[] s, int rate) throws IOException {
        OutputStream o = new FileOutputStream(f);
        try {
            o.write(encode(s, rate));
        } finally {
            o.close();
        }
    }

    public static byte[] encode(short[] s, int rate) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(44 + 2 * s.length);
        o.write('R');
        o.write('I');
        o.write('F');
        o.write('F');
        w32(o, 36 + 2 * s.length);
        for (char c : "WAVEfmt ".toCharArray()) o.write(c);
        w32(o, 16);
        w16(o, 1);
        w16(o, 1);
        w32(o, rate);
        w32(o, rate * 2);
        w16(o, 2);
        w16(o, 16);
        for (char c : "data".toCharArray()) o.write(c);
        w32(o, 2 * s.length);
        for (short v : s) w16(o, v);
        return o.toByteArray();
    }

    private static void w16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write(v >> 8 & 0xff);
    }

    private static void w32(ByteArrayOutputStream o, int v) {
        w16(o, v & 0xffff);
        w16(o, v >>> 16);
    }

    private static boolean tag(byte[] b, int at, String t) {
        if (at + 4 > b.length) return false;
        for (int i = 0; i < 4; i++) if (b[at + i] != t.charAt(i)) return false;
        return true;
    }

    private static int le16(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8;
    }

    private static int le32(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8 | (b[at + 2] & 0xff) << 16 | (b[at + 3] & 0xff) << 24;
    }
}
