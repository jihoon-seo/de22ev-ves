/*
 * ves.render: offline engine-sound renderer (host tool, not shipped to the car).
 * Part of the Java port of DasEtwas/enginesound <https://github.com/DasEtwas/enginesound>,
 * upstream commit e5fcca587397c0c8ba9c9d24874b951fed74d260 (upstream recorder.rs writes WAV too).
 * Copyright (c) 2020 DasEtwas. MIT License; the full notice is in THIRD_PARTY.md.
 * The Java port and its throttle extension are MIT as well.
 */
package ves.render;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Mono PCM16 WAV writer / reader (only what ves.render writes). */
public final class Wav {
    private Wav() {
    }

    /** Peak of |x|; NaN if any sample is NaN or infinite. */
    public static float peak(float[] x) {
        float p = 0.0f;
        for (float v : x) {
            if (Float.isNaN(v) || Float.isInfinite(v)) {
                return Float.NaN;
            }
            p = Math.max(p, Math.abs(v));
        }
        return p;
    }

    /** -1 dBFS as a linear factor */
    public static final double MINUS_1_DBFS = StrictMath.pow(10.0, -1.0 / 20.0);

    /** Writes x * gain as PCM16 (rounded, clamped). */
    public static void write(String path, float[] x, double gain, int sampleRate) throws IOException {
        int n = x.length;
        byte[] b = new byte[44 + 2 * n];
        ascii(b, 0, "RIFF");
        le32(b, 4, 36 + 2 * n);
        ascii(b, 8, "WAVE");
        ascii(b, 12, "fmt ");
        le32(b, 16, 16);
        le16(b, 20, 1); // PCM
        le16(b, 22, 1); // mono
        le32(b, 24, sampleRate);
        le32(b, 28, sampleRate * 2);
        le16(b, 32, 2);
        le16(b, 34, 16);
        ascii(b, 36, "data");
        le32(b, 40, 2 * n);
        for (int i = 0; i < n; i++) {
            long v = Math.round(x[i] * gain * 32767.0);
            v = Math.max(-32768, Math.min(32767, v));
            le16(b, 44 + 2 * i, (int) v);
        }
        File f = new File(path);
        if (f.getParentFile() != null) {
            f.getParentFile().mkdirs();
        }
        FileOutputStream fo = new FileOutputStream(f);
        try {
            fo.write(b);
        } finally {
            fo.close();
        }
    }

    /** Reads a mono PCM16 WAV as floats in [-1, 1); sampleRateOut[0] receives the rate. */
    public static float[] read(String path, int[] sampleRateOut) throws IOException {
        InputStream in = new FileInputStream(path);
        byte[] b;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) {
                bo.write(buf, 0, r);
            }
            b = bo.toByteArray();
        } finally {
            in.close();
        }
        if (b.length < 12 || !new String(b, 0, 4, "US-ASCII").equals("RIFF")
                || !new String(b, 8, 4, "US-ASCII").equals("WAVE")) {
            throw new IOException(path + ": not a WAV file");
        }
        int p = 12;
        int channels = 0;
        int bits = 0;
        while (p + 8 <= b.length) {
            String id = new String(b, p, 4, "US-ASCII");
            int len = rd32(b, p + 4);
            if (id.equals("fmt ")) {
                channels = rd16(b, p + 10);
                if (sampleRateOut != null) {
                    sampleRateOut[0] = rd32(b, p + 12);
                }
                bits = rd16(b, p + 22);
            } else if (id.equals("data")) {
                if (channels != 1 || bits != 16) {
                    throw new IOException(path + ": expected mono PCM16");
                }
                int n = len / 2;
                float[] x = new float[n];
                for (int i = 0; i < n; i++) {
                    x[i] = (short) rd16(b, p + 8 + 2 * i) / 32768.0f;
                }
                return x;
            }
            p += 8 + len + (len & 1);
        }
        throw new IOException(path + ": no data chunk");
    }

    private static void ascii(byte[] b, int o, String s) {
        for (int i = 0; i < 4; i++) {
            b[o + i] = (byte) s.charAt(i);
        }
    }

    private static void le16(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
    }

    private static void le32(byte[] b, int o, int v) {
        le16(b, o, v);
        le16(b, o + 2, v >> 16);
    }

    private static int rd16(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8;
    }

    private static int rd32(byte[] b, int o) {
        return rd16(b, o) | rd16(b, o + 2) << 16;
    }
}
