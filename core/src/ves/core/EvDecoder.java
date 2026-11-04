package ves.core;

/**
 * Decodes Niro EV (Can2A20EVMessage, infobank V039) hscan records into a SignalState using a text table, so the
 * map can be corrected after a car capture without code changes (see {@link #EvDecoder(SignalState, String)}).
 *
 * <h3>Index base</h3>
 * Record data[0] is the first byte after [fn][len], i.e. CAN data byte 0. The stock parsers (CanCsm.parseXXX(short[]))
 * receive an array whose element 0 is the function code, so parser index i == data[i-1]. Evidence:
 * parseVCU1 reads CF_Vcu_GarSelDisp from p1[2]>>3&0xF and CR_Vcu_AccPedDep from p1[5] (*0.3921568627), Rdy bits
 * from p1[6]; parseYRS12 builds LongAc from p1[1] | p1[2]<<8 (DBC LONG_ACCEL 0|16 = CAN bytes 0..1); parseESP12
 * builds CYL_PRES from (p1[5]&0x3F)<<6 | p1[4]>>2 (DBC CYL_PRES 26|12 = CAN bytes 3..4); CN7 CanSignalState:
 * "input includes a leading fncode; here p[pos] is data[0]" (road-validated ESP12 brake and EMS11 rpm).
 *
 * <h3>Table format</h3>
 * One field per line: {@code fn signal startBit bitLen u|s scale offset [invalidRaw]}; '#' starts a comment.
 * Bits are Intel/little-endian over data[] (bit b = data[b/8] bit b%8), so data[k]>>s&mask is start k*8+s.
 * value = raw*scale+offset, raw==invalidRaw (unsigned raw, before sign extension) stores NaN.
 * A record shorter than a field needs is skipped for that field (shortRecords).
 */
public final class EvDecoder implements RecordListener {

    /** READY follows CF_Vcu_Ev_Rdy (data[5] bit5), which the stock app reports via onVcuEvRdyStatus;
     *  CF_Vcu_Rdy (bit4) is not used. */
    public static final String DEFAULT_TABLE = ""
            + "# fn  signal           start len sign scale          offset    [invalid]\n"
            + "0x21  PEDAL_PCT        32    8   u    0.3921568627   0          # VCU1 CR_Vcu_AccPedDep_Pc data[4]\n"
            + "0x21  GEAR             11    4   u    1              0          # VCU1 CF_Vcu_GarSelDisp data[1]>>3&15\n"
            + "0x21  READY            45    1   u    1              0          # VCU1 CF_Vcu_Ev_Rdy data[5] bit5\n"
            + "0x18  MOTOR_SPEED_KPH  16    8   u    1              0   255    # MCU3 VehSpd data[2]\n"
            + "0x18  TORQUE_PCT       32    12  u    0.048828       0          # MCU3 Mg1EstTqBefAJ data[4]|(data[5]&15)<<8\n"
            + "0x18  MOTOR_KW         56    8   s    1              0          # MCU3 MotPwrAvn (int8)data[7]\n"
            + "0x11  SPEED_KPH        0     9   u    0.5            0   511    # EV_CP2 data[0]|(data[1]&1)<<8\n"
            + "0x28  ACCEL_G          0     16  u    1.27465e-4     -4.17677   # YRS12 CR_Yrs_LongAc data[0]|data[1]<<8\n"
            + "0x3E  BRAKE_BAR        26    12  u    0.1            0   4095   # ESP12 CYL_PRES ((data[4]&63)<<6)|(data[3]>>2)\n"
            + "0x26  DRIVE_MODE       58    4   u    1              0          # VCU5 EvMod data[7]>>2&15\n";

    private static final int MAX_FIELDS = 64;

    private final SignalState state;
    // field arrays
    private final int[] fSig = new int[MAX_FIELDS], fStart = new int[MAX_FIELDS], fLen = new int[MAX_FIELDS];
    private final boolean[] fSigned = new boolean[MAX_FIELDS];
    private final double[] fScale = new double[MAX_FIELDS], fOffset = new double[MAX_FIELDS];
    private final long[] fInvalid = new long[MAX_FIELDS];
    private int nFields;
    private final int[][] byFn = new int[256][];
    private final long[] counts = new long[256];
    private int[] known = new int[0];
    private long shortRecords;
    private int tableErrors;

    public EvDecoder(SignalState s) {
        this(s, DEFAULT_TABLE);
    }

    /** Uses the given table text (see class doc); malformed lines are skipped and counted in tableErrors(). */
    public EvDecoder(SignalState s, String table) {
        this.state = s;
        parse(table == null ? DEFAULT_TABLE : table);
    }

    private void parse(String table) {
        int[] perFn = new int[256];
        int[] fnOf = new int[MAX_FIELDS];
        String[] lines = table.split("\n");
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.trim();
            if (line.length() == 0) continue;
            String[] t = line.split("[\\s,]+");
            try {
                if (t.length < 7 || t.length > 8 || nFields >= MAX_FIELDS) throw new IllegalArgumentException();
                int fn = Integer.decode(t[0]);
                int sig = Sig.byName(t[1]);
                int start = Integer.parseInt(t[2]);
                int len = Integer.parseInt(t[3]);
                boolean signed;
                if (t[4].equalsIgnoreCase("u")) signed = false;
                else if (t[4].equalsIgnoreCase("s")) signed = true;
                else throw new IllegalArgumentException();
                double scale = Double.parseDouble(t[5]);
                double offset = Double.parseDouble(t[6]);
                long inv = t.length == 8 ? Long.decode(t[7]) : -1;
                if (fn < 0 || fn > 255 || sig < 0 || start < 0 || len < 1 || len > 32 || start + len > 64)
                    throw new IllegalArgumentException();
                int i = nFields++;
                fnOf[i] = fn;
                fSig[i] = sig;
                fStart[i] = start;
                fLen[i] = len;
                fSigned[i] = signed;
                fScale[i] = scale;
                fOffset[i] = offset;
                fInvalid[i] = inv;
                perFn[fn]++;
            } catch (RuntimeException e) {
                tableErrors++;
            }
        }
        int nk = 0;
        for (int fn = 0; fn < 256; fn++) {
            if (perFn[fn] == 0) continue;
            nk++;
            int[] a = new int[perFn[fn]];
            int k = 0;
            for (int i = 0; i < nFields; i++) if (fnOf[i] == fn) a[k++] = i;
            byFn[fn] = a;
        }
        known = new int[nk];
        nk = 0;
        for (int fn = 0; fn < 256; fn++) if (byFn[fn] != null) known[nk++] = fn;
    }

    public void onRecord(int fn, byte[] buf, int off, int len, long tMs) {
        fn &= 0xFF;
        counts[fn]++;
        int[] fields = byFn[fn];
        if (fields == null || buf == null || off < 0 || len <= 0 || off + len > buf.length) return;
        int n = Math.min(len, 8);
        long v = 0;
        for (int i = 0; i < n; i++) v |= (buf[off + i] & 0xFFL) << (8 * i);
        for (int k = 0; k < fields.length; k++) {
            int f = fields[k];
            int need = (fStart[f] + fLen[f] + 7) >> 3;
            if (need > n) {
                shortRecords++;
                continue;
            }
            long raw = (v >>> fStart[f]) & ((1L << fLen[f]) - 1);
            double val;
            if (fInvalid[f] >= 0 && raw == fInvalid[f]) {
                val = Double.NaN;
            } else {
                long r = raw;
                if (fSigned[f] && ((r >>> (fLen[f] - 1)) & 1) != 0) r -= 1L << fLen[f];
                val = r * fScale[f] + fOffset[f];
            }
            if (state != null) state.set(fSig[f], val, tMs);
        }
    }

    /** function codes that have at least one field, ascending */
    public int[] knownFns() {
        return known.clone();
    }

    /** records seen for fn (0..255), including functions without fields */
    public long count(int fn) {
        return fn < 0 || fn > 255 ? 0 : counts[fn];
    }

    public long shortRecords() {
        return shortRecords;
    }

    public int tableErrors() {
        return tableErrors;
    }
}
