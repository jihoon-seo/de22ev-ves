package ves.core;

/** Receives one hscan_mux record: MICOM function code and its data bytes buf[off..off+len). */
public interface RecordListener {
    void onRecord(int fn, byte[] buf, int off, int len, long tMs);
}
