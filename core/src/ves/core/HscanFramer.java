package ves.core;

/**
 * Re-frames the hscan_mux byte stream into MICOM CAN-record packets and hands each record to a RecordListener.
 *
 * <h3>Wire format relied on (offsets are 0-based)</h3>
 * <pre>
 *  0      FF        SOF
 *  1      0B        sender (hscand forwards sender 0x0B packets to hscan_mux; 0x03 goes to micom_mux_albumart)
 *  2      8E        receiver
 *  3      01        type
 *  4..5   8B C7     MICOM function (CAN record batch)
 *  6..7   01 00     L, big-endian; total packet length = 8 + L = 264 (fixed)
 *  8      C7        constant in every record packet (echo of the function low byte)
 *  9..10  count     record count, little-endian u16, 0..84
 *  11..   records   [fn u8][len u8][data len bytes], len 1..8 (CAN DLC), data[0] = CAN byte 0
 *  ..262  padding   zero bytes after the last record
 *  263    XOR       p[263] = p[0]^..^p[262]  (XOR of all 264 bytes == 0)
 * </pre>
 * Evidence:
 * <ul>
 * <li>analysis/micom_native/RESULT.ko.txt sec. 2: common header "0 SOF / 1 sender / 2 receiver / 3 type /
 *     4 function high / 5 function low / 6 length high / 7 length low / 8.. data", N = 8 + L, last byte = XOR of
 *     p[0..N-2]; sec. 4 (hscand 0x116D0/0x110F0): reads at most 0x108 (264) bytes, checks "SOF=FF, sender=03 or 0B,
 *     type=0/1/80, L=0x100, total length 264, XOR".</li>
 * <li>TM_HSCANPacketRunner.getLength/findSingleMultiFH (stock com.infobank): length = BE16(p[6..7]) + 8, sync on 0xFF;
 *     compareFrameCRC: XOR check.</li>
 * <li>CN7 v1.2 CanSignalState.acceptPacket (road-validated against raw captures): header {255,11,142,1,139,199,1,0},
 *     xor==0 over 264 bytes, p[8]==199, count=(p[9])|(p[10]&lt;&lt;8), records from 11, all records before 263,
 *     n in 1..8, count &lt;= 84, padding zero; "input includes a leading fncode; here p[pos] is data[0]".</li>
 * </ul>
 * NOTE: the record count is at offset 9 (not 8): byte 8 is the constant 0xC7.
 *
 * <h3>Robustness</h3>
 * Sync search for FF 0B 8E 01 (garbage before it is dropped and counted as one resync). A candidate with L != 0x100
 * or a bad XOR is a bad packet; the search then restarts one byte after its SOF, so a real header inside a fake
 * window is still found. A packet with a good XOR but another function (bytes 4,5,8) is ignored (ignoredPackets).
 * Record structure is validated before any record is delivered (atomic, like the CN7 bridge): a bad count,
 * truncated/zero/over-8 length or non-zero padding rejects the whole packet (badPackets, badRecords += declared
 * count). Partial packets are kept across feed() calls. feed() never throws, also not on listener exceptions
 * (counted in listenerErrors). Single-threaded: call feed() from one thread (or under a lock).
 */
public final class HscanFramer {
    public static final int PACKET = 264;
    public static final int MAX_RECORDS = 84;
    static final int REC_START = 11, REC_END = 263;

    private final RecordListener listener;
    private final byte[] buf = new byte[PACKET * 8];
    private int start, end; // valid bytes buf[start..end)

    private long packets, badPackets, records, badRecords, resyncs, ignored, listenerErrors, garbageBytes;
    private boolean inGarbage;

    public HscanFramer(RecordListener l) {
        this.listener = l;
    }

    public long packets() {
        return packets;
    }

    public long badPackets() {
        return badPackets;
    }

    public long records() {
        return records;
    }

    public long resyncs() {
        return resyncs;
    }

    /** records declared by rejected packets */
    public long badRecords() {
        return badRecords;
    }

    /** well-formed frames (good XOR) with another MICOM function */
    public long ignoredPackets() {
        return ignored;
    }

    public long listenerErrors() {
        return listenerErrors;
    }

    public long garbageBytes() {
        return garbageBytes;
    }

    /** Drops any partial packet (e.g. after a socket reconnect). */
    public void reset() {
        start = end = 0;
        inGarbage = false;
    }

    public void feed(byte[] b, int off, int len, long tMs) {
        if (b == null || off < 0 || len <= 0 || off > b.length || len > b.length - off) return;
        try {
            while (len > 0) {
                if (start > 0 && end + len > buf.length) {
                    System.arraycopy(buf, start, buf, 0, end - start);
                    end -= start;
                    start = 0;
                }
                int n = Math.min(len, buf.length - end);
                System.arraycopy(b, off, buf, end, n);
                end += n;
                off += n;
                len -= n;
                process(tMs);
            }
        } catch (RuntimeException e) {
            // defensive: never propagate; drop the buffer
            listenerErrors++;
            reset();
        }
    }

    private void process(long tMs) {
        while (true) {
            int avail = end - start;
            if (avail < 4) return;
            int s = findSync(start, end);
            if (s < 0) {
                // keep up to 3 bytes that may be the start of a split sync
                int keep = 3;
                drop(avail - keep);
                return;
            }
            if (s > start) drop(s - start);
            if (end - start < PACKET) {
                // need the length bytes to reject early, else wait for the full packet
                if (end - start >= 8 && (buf[start + 6] != 0x01 || buf[start + 7] != 0x00)) {
                    bad(0);
                    continue;
                }
                compactIfNeeded();
                return;
            }
            int p = start;
            if (buf[p + 6] != 0x01 || buf[p + 7] != 0x00) {
                bad(0);
                continue;
            }
            int x = 0;
            for (int i = 0; i < PACKET; i++) x ^= buf[p + i];
            if ((x & 0xFF) != 0) {
                bad(0);
                continue;
            }
            inGarbage = false;
            // well-formed frame: consume it whatever its content
            if (buf[p + 4] != (byte) 0x8B || buf[p + 5] != (byte) 0xC7 || buf[p + 8] != (byte) 0xC7) {
                ignored++;
                start += PACKET;
                continue;
            }
            parse(p, tMs);
            start += PACKET;
        }
    }

    private void parse(int p, long tMs) {
        int count = (buf[p + 9] & 0xFF) | ((buf[p + 10] & 0xFF) << 8);
        if (count > MAX_RECORDS) {
            badPackets++;
            badRecords += Math.min(count, MAX_RECORDS);
            return;
        }
        int pos = p + REC_START, lim = p + REC_END;
        for (int i = 0; i < count; i++) {
            if (pos + 2 > lim) {
                rejectRecords(count);
                return;
            }
            int n = buf[pos + 1] & 0xFF;
            if (n < 1 || n > 8 || pos + 2 + n > lim) {
                rejectRecords(count);
                return;
            }
            pos += 2 + n;
        }
        for (int i = pos; i < lim; i++) {
            if (buf[i] != 0) {
                rejectRecords(count);
                return;
            }
        }
        packets++;
        pos = p + REC_START;
        for (int i = 0; i < count; i++) {
            int fn = buf[pos] & 0xFF, n = buf[pos + 1] & 0xFF;
            records++;
            if (listener != null) {
                try {
                    listener.onRecord(fn, buf, pos + 2, n, tMs);
                } catch (RuntimeException e) {
                    listenerErrors++;
                }
            }
            pos += 2 + n;
        }
    }

    private void rejectRecords(int count) {
        badPackets++;
        badRecords += Math.max(1, count);
    }

    /** bad candidate at start: count it and resume the sync search one byte later */
    private void bad(int unused) {
        badPackets++;
        start++;
        garbageBytes++;
        if (!inGarbage) {
            resyncs++;
            inGarbage = true;
        }
    }

    private void drop(int n) {
        if (n <= 0) return;
        start += n;
        garbageBytes += n;
        if (!inGarbage) {
            resyncs++;
            inGarbage = true;
        }
        if (start == end) start = end = 0;
    }

    private void compactIfNeeded() {
        if (start > buf.length - PACKET) {
            System.arraycopy(buf, start, buf, 0, end - start);
            end -= start;
            start = 0;
        }
    }

    private int findSync(int from, int to) {
        for (int i = from; i + 3 < to; i++) {
            if (buf[i] == (byte) 0xFF && buf[i + 1] == 0x0B && buf[i + 2] == (byte) 0x8E && buf[i + 3] == 0x01) return i;
        }
        // a trailing partial sync (1..3 bytes) is kept by the caller
        return -1;
    }
}
