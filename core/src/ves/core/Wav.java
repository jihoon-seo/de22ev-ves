package ves.core;

/** RIFF/WAVE reader for PCM 16-bit mono at a given rate; anything else is a PackError. */
final class Wav {
    private Wav() {}

    static short[] mono16(byte[] b, int rate, String what) throws PackError {
        if (b.length < 12 || !tag(b, 0, "RIFF") || !tag(b, 8, "WAVE")) throw new PackError(what + ": not a WAV file");
        int pos = 12;
        boolean fmt = false;
        while (pos + 8 <= b.length) {
            int size = le32(b, pos + 4);
            if (size < 0) throw new PackError(what + ": bad chunk");
            int body = pos + 8;
            if (tag(b, pos, "fmt ")) {
                if (size < 16 || body + 16 > b.length) throw new PackError(what + ": bad fmt chunk");
                int format = le16(b, body), ch = le16(b, body + 2), sr = le32(b, body + 4), bits = le16(b, body + 14);
                if (format != 1) throw new PackError(what + ": not PCM");
                if (ch != 1) throw new PackError(what + ": must be mono");
                if (sr != rate) throw new PackError(what + ": must be " + rate + " Hz, is " + sr);
                if (bits != 16) throw new PackError(what + ": must be 16-bit");
                fmt = true;
            } else if (tag(b, pos, "data")) {
                if (!fmt) throw new PackError(what + ": data before fmt");
                long end = (long) body + size;
                if (end > b.length) end = b.length; // tolerate a truncated last chunk
                int n = (int) ((end - body) / 2);
                short[] s = new short[n];
                for (int i = 0; i < n; i++) s[i] = (short) le16(b, body + 2 * i);
                return s;
            }
            long next = (long) body + size + (size & 1);
            if (next > Integer.MAX_VALUE) break;
            pos = (int) next;
        }
        throw new PackError(what + ": no audio data");
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
