package ves.android;

import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Process;
import android.os.ServiceManager;
import android.os.SystemClock;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

import ves.core.Profile;
import ves.core.Settings;
import ves.core.Sig;
import ves.core.SignalState;
import ves.core.VehicleText;
import ves.hmg.HmgClient;

/**
 * The car check ("점검") that the app walks the owner through. Everything goes to
 * <USB>/VES/probe-<time>/ on the stick: probe.log (steps, answers, results),
 * events.csv (MICOM audio callbacks and our path calls), and for the drive step
 * hscan.raw, signals.csv, vehicle.csv, cpu.csv; logcat and service list at the end.
 * Vehicle data is only read. Sound tests are short tones or the engine sound itself.
 */
final class Probe {
    private final Vesd d;
    private File dir;
    private OutputStream log, events;
    private volatile String running = "";
    private volatile boolean driveStop;
    private OutputStream raw;

    Probe(Vesd d) {
        this.d = d;
    }

    synchronized String state() {
        return (dir == null ? "closed" : "open " + dir) + (running.length() > 0 ? " running " + running : "");
    }

    String command(String[] a, String line) {
        String sub = a.length > 1 ? a[1].toUpperCase() : "";
        try {
            if (sub.equals("BEGIN")) return begin();
            if (dir == null) {
                // the app continues a check after the car (or vesd) restarted: open a new session folder
                String b = begin();
                if (b.startsWith("error")) return b;
                write("CONTINUED at " + line);
            }
            if (sub.equals("NOTE")) {
                int i = line.toUpperCase().indexOf("NOTE");
                write("NOTE " + line.substring(i + 4).trim());
                return "ok\n";
            }
            if (running.length() > 0 && !sub.equals("DRIVE")) return "error busy: " + running + "\n";
            if (sub.equals("INFO")) return info();
            if (sub.equals("TONE")) return tone(Integer.parseInt(a[2]), parseInt(a[3]), Integer.parseInt(a[4]), a.length > 5 ? a[5] : "");
            if (sub.equals("PACKS")) return packDemo(a.length > 2 ? a[2] : "");
            if (sub.equals("HOLD")) return hold(parseInt(a[2]), Integer.parseInt(a[3]), a.length > 4 ? Integer.parseInt(a[4]) : 30);
            if (sub.equals("STUCK")) return stuck(parseInt(a[2]));
            if (sub.equals("CLEAN")) return clean(parseInt(a[2]));
            if (sub.equals("DRIVE")) return a.length > 2 && a[2].equalsIgnoreCase("STOP") ? driveStop() : driveStart(a.length > 3 ? Integer.parseInt(a[3]) : 15);
            if (sub.equals("END")) return end();
            return "error unknown probe command\n";
        } catch (Throwable e) {
            write("ERROR " + line + ": " + e);
            return "error " + e + "\n";
        }
    }

    static int parseInt(String v) {
        v = v.trim().toLowerCase();
        return v.startsWith("0x") ? Integer.parseInt(v.substring(2), 16) : Integer.parseInt(v);
    }

    // ---- session

    private synchronized String begin() throws IOException {
        if (dir != null) return "ok\n" + dir + "\n";
        List<String> usb = d.hmg.usbFolders();
        // hmgapid lists a stick only when the mod's folder (VES) already exists on it
        if (usb.isEmpty()) return "error USB에 VES 폴더가 없음: USB 최상위에 VES 폴더를 만들고 다시 시도\n";
        File f = new File(usb.get(0), "probe-" + Out.stamp());
        if (!f.mkdirs()) return "error cannot create " + f + "\n";
        dir = f;
        log = new BufferedOutputStream(new FileOutputStream(new File(f, "probe.log"), true));
        events = new BufferedOutputStream(new FileOutputStream(new File(f, "events.csv"), true));
        events.write("t_ms,event\n".getBytes("UTF-8"));
        write("BEGIN " + f + " product=" + prop("ro.build.product") + " incremental=" + prop("ro.build.version.incremental"));
        return "ok\n" + f + "\n";
    }

    synchronized void write(String s) {
        if (log == null) return;
        try {
            log.write((SystemClock.uptimeMillis() + " " + s + "\n").getBytes("UTF-8"));
            log.flush();
        } catch (IOException ignored) {
        }
    }

    void event(String s) {
        synchronized (this) {
            if (events == null) return;
            try {
                events.write((SystemClock.uptimeMillis() + "," + s.replace(',', ' ') + "\n").getBytes("UTF-8"));
                events.flush();
            } catch (IOException ignored) {
            }
        }
    }

    private String end() throws IOException {
        StringBuilder b = new StringBuilder("ok\n");
        exec(new String[] { "/system/bin/logcat", "-d", "-v", "time" }, new File(dir, "logcat.txt"));
        filter(new File(dir, "logcat.txt"), new File(dir, "logcat-audio.txt"),
                "MicomAudio|AudioServiceHelper|AudioFlinger|AudioTrack|AudioPolicy|ModeManager|NaviTTS|exclusive");
        exec(new String[] { "/system/bin/service", "list" }, new File(dir, "services.txt"));
        write("STATUS\n" + d.status());
        write("END");
        synchronized (this) {
            log.close();
            events.close();
            log = null;
            events = null;
            b.append(dir).append('\n');
            dir = null;
        }
        return b.toString();
    }

    // ---- step a: what is there

    private String info() {
        StringBuilder b = new StringBuilder();
        b.append("service ").append(MicomAudio.SERVICE).append('=').append(has(MicomAudio.SERVICE)).append('\n');
        b.append("service tm_hscan_service=").append(has("tm_hscan_service")).append('\n');
        b.append("service com.hkmc.micom.hev=").append(has("com.hkmc.micom.hev")).append('\n');
        int[] types = { MicomAudio.NAVI, MicomAudio.NAVI_SOUNDEFFECT, MicomAudio.SYSTEM_ALARM, MicomAudio.TTS_EFFECT_SOUND, MicomAudio.TTS };
        for (int t : types) {
            String v;
            try {
                v = String.valueOf(d.micom.hasStreamTypeInAudioStack(t));
            } catch (Throwable e) {
                v = "err " + e;
            }
            b.append("in_stack 0x").append(Integer.toHexString(t)).append('=').append(v).append('\n');
        }
        long b0 = d.hscan.bytes;
        long[] c0 = counts();
        SystemClock.sleep(5000);
        long[] c1 = counts();
        b.append("hscan connected=").append(d.hscan.connected).append(" bytes_per_s=").append((d.hscan.bytes - b0) / 5).append('\n');
        StringBuilder fns = new StringBuilder();
        for (int fn = 0; fn < 256; fn++) {
            long n = c1[fn] - c0[fn];
            if (n > 0) fns.append(String.format("0x%02x:%.1f/s ", fn, n / 5.0));
        }
        b.append("records ").append(fns).append('\n');
        try {
            long t0 = SystemClock.uptimeMillis();
            HmgClient.Answer a = d.hmg.vehicleAnswer("speed", "pedal.accel", "hv.ready", "gear.park", "brake.pressure", "moving");
            Map<String, String> v = VehicleText.parse(a.code, a.text);
            b.append("hmgapi_vehicle ").append(v).append(" code=").append(a.code).append(" ms=")
                    .append(SystemClock.uptimeMillis() - t0).append(v.isEmpty() ? " text=" + a.text.trim() : "").append('\n');
        } catch (Throwable e) {
            b.append("hmgapi_vehicle err ").append(e).append('\n');
        }
        b.append(d.status());
        write("INFO\n" + b);
        return "ok\n" + b;
    }

    private long[] counts() {
        long[] c = new long[256];
        synchronized (d.core) {
            ves.core.EvDecoder dec = decoder();
            for (int fn = 0; fn < 256; fn++) c[fn] = dec == null ? 0 : dec.count(fn);
        }
        return c;
    }

    private ves.core.EvDecoder decoder() {
        Object l = d.core.recordListener();
        return l instanceof ves.core.EvDecoder ? (ves.core.EvDecoder) l : null;
    }

    static String has(String name) {
        try {
            return ServiceManager.checkService(name) != null ? "yes" : "no";
        } catch (Throwable e) {
            return "err";
        }
    }

    // ---- step b: a tone on one route

    private void beginExclusive() {
        Settings off = copy(d.ownerSettings());
        off.enabled = false;
        d.setOverride(off);
        SystemClock.sleep(2600); // the policy closes after 2 s of silence
        d.exclusive = true;
        SystemClock.sleep(100);
    }

    private void endExclusive() {
        d.exclusive = false;
        d.setOverride(null);
    }

    private String tone(int stream, int type, int ms, String label) {
        running = "tone";
        StringBuilder b = new StringBuilder();
        beginExclusive();
        try {
            ms = Math.max(500, Math.min(ms, 10000));
            int rate = Vesd.RATE, frames = rate * ms / 1000;
            short[] pcm = chime(rate, frames);
            write("TONE start label=" + label + " stream=" + stream + " type=" + (type < 0 ? "none" : "0x" + Integer.toHexString(type)));
            if (type >= 0) b.append("startAudio=").append(d.micom.startAudio(type)).append(' ');
            if (type >= 0) b.append("in_stack=").append(d.micom.hasStreamTypeInAudioStack(type)).append(' ');
            AudioTrack t = new AudioTrack(stream, rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    frames * 2, AudioTrack.MODE_STATIC);
            int w = t.write(pcm, 0, frames);
            t.play();
            SystemClock.sleep(ms + 150);
            t.stop();
            t.release();
            if (type >= 0) b.append("endAudio=").append(d.micom.endAudio(type)).append(' ');
            b.append("written=").append(w);
            write("TONE end label=" + label + " " + b);
            return "ok\n" + b + "\n";
        } catch (Throwable e) {
            if (type >= 0) {
                try {
                    d.micom.endAudio(type);
                } catch (Throwable ignored) {
                }
            }
            write("TONE fail label=" + label + " " + e);
            return "error " + e + "\n";
        } finally {
            endExclusive();
            running = "";
        }
    }

    /** two notes alternating every 250 ms, soft edges: easy to hear over music */
    static short[] chime(int rate, int frames) {
        short[] o = new short[frames];
        int seg = rate / 4;
        for (int i = 0; i < frames; i++) {
            double f = (i / seg) % 2 == 0 ? 660 : 880;
            int k = i % seg;
            double env = Math.min(1.0, Math.min(k, seg - k) / (rate * 0.01));
            o[i] = (short) (Math.sin(2 * Math.PI * f * i / rate) * 9000 * env);
        }
        return o;
    }

    // ---- step c: engine sound held on while the owner triggers voices

    /** a 6 s free rev (idle, ~3700 rpm, idle) with one sound (synth or a pack slot); parked only */
    private String packDemo(final String slot) {
        long now = SystemClock.uptimeMillis();
        String no;
        synchronized (d.core) {
            SignalState g = d.core.signals();
            no = ves.core.ProbeGuard.demoRefusal(g.get(Sig.SPEED_KPH, now), g.get(Sig.GEAR, now), g.get(Sig.READY, now));
        }
        if (no != null) return "error " + no + "\n";
        final ves.core.Pack p;
        if (slot.equals("synth")) p = null;
        else {
            try {
                p = d.packs.load(slot);
            } catch (ves.core.PackError e) {
                return "error " + e.getMessage() + "\n";
            }
        }
        running = "hold";
        write("PACKS start " + slot + (p != null ? " (" + p.name + ")" : ""));
        Vesd.thread("probe-packs", new Runnable() {
            public void run() {
                try {
                    Settings s = copy(d.ownerSettings());
                    s.enabled = true;
                    s.volume = Math.max(50, s.volume);
                    s.behavior = "ice";
                    s.pops = "off";
                    s.sound = slot.equals("synth") ? "synth" : slot;
                    d.hscan.paused = true;
                    d.core.setPack(p);
                    d.setOverride(s);
                    double[] ped = ves.core.ProbeGuard.demoPedal();
                    for (int i = 0; i < ped.length; i++) {
                        long t = SystemClock.uptimeMillis();
                        synchronized (d.core) {
                            SignalState g = d.core.signals();
                            g.set(Sig.READY, 1, t);
                            g.set(Sig.GEAR, Sig.GEAR_P, t);
                            g.set(Sig.SPEED_KPH, 0, t);
                            g.set(Sig.MOTOR_SPEED_KPH, 0, t);
                            g.set(Sig.PEDAL_PCT, ped[i], t);
                            g.set(Sig.MOTOR_KW, 0, t);
                        }
                        SystemClock.sleep(100);
                    }
                    SystemClock.sleep(1500); // back to idle
                    write("PACKS end " + slot + " status " + d.core.status());
                } catch (Throwable e) {
                    write("PACKS fail " + e);
                } finally {
                    d.setOverride(null);
                    d.hscan.paused = false;
                    d.packSlot = ""; // load the owner's own pack again
                    d.loadPack(d.ownerSettings().sound);
                    running = "";
                }
            }
        });
        return "ok\npacks demo " + slot + "\n";
    }

    private String hold(final int type, int sec, int volume) {
        final int vol = Math.max(0, Math.min(volume, 100));
        running = "hold";
        final int secs = Math.max(5, Math.min(sec, 180));
        final int prevType = d.ownType;
        write("HOLD start type=0x" + Integer.toHexString(type) + " sec=" + secs + " volume=" + vol);
        Vesd.thread("probe-hold", new Runnable() {
            public void run() {
                try {
                    d.cleanupPath("probe hold");
                    d.ownType = type;
                    d.micom.setOwnType(type);
                    Settings s = copy(d.ownerSettings());
                    s.enabled = true;
                    s.volume = vol;
                    s.mode = "drive_all";
                    s.profile = Profile.ICE_SMOOTH;
                    d.hscan.paused = true;
                    d.setOverride(s);
                    long t0 = SystemClock.uptimeMillis();
                    while (SystemClock.uptimeMillis() - t0 < secs * 1000L) {
                        long t = SystemClock.uptimeMillis();
                        double ph = (t - t0) / 4000.0 * 2 * Math.PI;
                        synchronized (d.core) {
                            SignalState g = d.core.signals();
                            g.set(Sig.READY, 1, t);
                            g.set(Sig.GEAR, Sig.GEAR_D, t);
                            g.set(Sig.SPEED_KPH, 35 + 20 * Math.sin(ph), t);
                            g.set(Sig.MOTOR_SPEED_KPH, 35 + 20 * Math.sin(ph), t);
                            g.set(Sig.PEDAL_PCT, 30 + 25 * Math.sin(ph * 1.7), t);
                            g.set(Sig.MOTOR_KW, 20 + 15 * Math.sin(ph * 1.7), t);
                            g.set(Sig.ACCEL_G, 0.05, t);
                        }
                        SystemClock.sleep(50);
                    }
                    write("HOLD end opens=" + d.opens + " closes=" + d.closes + " status " + d.status());
                } catch (Throwable e) {
                    write("HOLD fail " + e);
                } finally {
                    d.setOverride(null);
                    d.hscan.paused = false;
                    SystemClock.sleep(2600);
                    d.cleanupPath("probe hold end");
                    d.ownType = prevType;
                    d.micom.setOwnType(prevType);
                    running = "";
                }
            }
        });
        return "ok\nholding " + secs + " s\n";
    }

    // ---- step c2: does a crash leave the channel stuck?

    private String stuck(int type) throws Exception {
        boolean ok = d.micom.startAudio(type);
        write("STUCK startAudio(0x" + Integer.toHexString(type) + ")=" + ok + ", flag written, killing vesd without endAudio");
        FileOutputStream f = new FileOutputStream(new File(dir, "STUCK_PENDING"));
        f.write(Integer.toHexString(type).getBytes("UTF-8"));
        f.close();
        synchronized (this) {
            log.flush();
            events.flush();
        }
        Vesd.thread("probe-kill", new Runnable() {
            public void run() {
                SystemClock.sleep(500);
                Process.killProcess(Process.myPid());
            }
        });
        return "ok\nkilled in 0.5 s; HMG-API restarts vesd\n";
    }

    /** At start: a pending stuck test means "don't clean up this once". Keeps the session folder. */
    boolean consumeStuckFlag() {
        try {
            for (String u : d.hmg.usbFolders()) {
                File[] fs = new File(u).listFiles();
                if (fs == null) continue;
                for (File p : fs) {
                    File flag = new File(p, "STUCK_PENDING");
                    if (p.getName().startsWith("probe-") && flag.isFile()) {
                        boolean del = flag.delete();
                        synchronized (this) {
                            dir = p;
                            log = new BufferedOutputStream(new FileOutputStream(new File(p, "probe.log"), true));
                            events = new BufferedOutputStream(new FileOutputStream(new File(p, "events.csv"), true));
                        }
                        write("RESTARTED after stuck test (flag removed " + del + "); startup cleanup skipped");
                        return true;
                    }
                }
            }
        } catch (Throwable e) {
            Out.log("probe: stuck flag check: " + e);
        }
        return false;
    }

    private String clean(int type) throws Exception {
        boolean in0 = d.micom.hasStreamTypeInAudioStack(type);
        boolean ok = d.micom.endAudio(type);
        boolean in1 = d.micom.hasStreamTypeInAudioStack(type);
        String r = "in_stack_before=" + in0 + " endAudio=" + ok + " in_stack_after=" + in1;
        write("CLEAN 0x" + Integer.toHexString(type) + " " + r);
        return "ok\n" + r + "\n";
    }

    // ---- step d: the drive recording

    private String driveStart(int minutes) throws IOException {
        if (running.equals("drive")) return "ok\nalready recording\n";
        if (running.length() > 0) return "error busy: " + running + "\n";
        running = "drive";
        driveStop = false;
        final int mins = Math.max(1, Math.min(minutes, 60));
        raw = new BufferedOutputStream(new FileOutputStream(new File(dir, "hscan.raw")), 1 << 16);
        d.hscan.tap = new HscanReader.Tap() {
            public void onChunk(byte[] b, int off, int len, long tMs) {
                try {
                    OutputStream o = raw;
                    if (o == null) return;
                    byte[] h = new byte[12];
                    for (int i = 0; i < 8; i++) h[i] = (byte) (tMs >>> (8 * i));
                    for (int i = 0; i < 4; i++) h[8 + i] = (byte) (len >>> (8 * i));
                    o.write(h);
                    o.write(b, off, len);
                } catch (IOException ignored) {
                }
            }
        };
        Settings s = copy(d.ownerSettings());
        s.enabled = true;
        if (s.volume <= 0) s.volume = 30;
        d.setOverride(s);
        write("DRIVE start minutes=" + mins + " settings=" + s);
        Vesd.thread("probe-drive", new Runnable() {
            public void run() {
                record(mins);
            }
        });
        return "ok\nrecording up to " + mins + " min\n";
    }

    private String driveStop() {
        driveStop = true;
        return "ok\nstopping\n";
    }

    private void record(int mins) {
        OutputStream sig = null, veh = null, cpu = null;
        try {
            sig = new BufferedOutputStream(new FileOutputStream(new File(dir, "signals.csv")));
            veh = new BufferedOutputStream(new FileOutputStream(new File(dir, "vehicle.csv")));
            cpu = new BufferedOutputStream(new FileOutputStream(new File(dir, "cpu.csv")));
            StringBuilder h = new StringBuilder("t_ms");
            for (int i = 0; i < Sig.COUNT; i++) h.append(',').append(Sig.name(i));
            h.append(",rpm,load,regen,target,gain,gear_v,open,underruns,state\n");
            sig.write(h.toString().getBytes("UTF-8"));
            veh.write("t_ms,speed,pedal.accel,hv.ready,gear.park,brake.pressure\n".getBytes("UTF-8"));
            cpu.write("t_ms,proc_jiffies,total_jiffies,idle_jiffies\n".getBytes("UTF-8"));
            long t0 = SystemClock.uptimeMillis(), lastCpu = 0;
            // HMG-API's readings on their own thread: one call takes ~3.2 s on the car, which held the
            // 100 ms signal rows back to one per 3 s in car check 2
            final OutputStream vehOut = veh;
            Vesd.thread("probe-vehicle", new Runnable() {
                public void run() {
                    while (!driveStop && running.equals("drive")) {
                        try {
                            long t1 = SystemClock.uptimeMillis();
                            HmgClient.Answer a = d.hmg.vehicleAnswer("speed", "pedal.accel", "hv.ready", "gear.park", "brake.pressure");
                            Map<String, String> v = VehicleText.parse(a.code, a.text);
                            synchronized (vehOut) {
                                vehOut.write((t1 + "," + v.get("speed") + "," + v.get("pedal.accel") + "," + v.get("hv.ready") + ","
                                        + v.get("gear.park") + "," + v.get("brake.pressure") + "\n").getBytes("UTF-8"));
                            }
                        } catch (Throwable ignored) {
                            SystemClock.sleep(1000);
                        }
                        SystemClock.sleep(200);
                    }
                }
            });
            while (!driveStop && SystemClock.uptimeMillis() - t0 < mins * 60000L) {
                long t = SystemClock.uptimeMillis();
                StringBuilder b = new StringBuilder().append(t);
                synchronized (d.core) {
                    SignalState g = d.core.signals();
                    for (int i = 0; i < Sig.COUNT; i++) b.append(',').append(Vesd.fmt(g.get(i, t)));
                    ves.core.EngineParams p = d.core.lastParams();
                    b.append(',').append(Math.round(p.rpm)).append(',').append(Vesd.fmt(p.load)).append(',').append(Vesd.fmt(p.regen))
                            .append(',').append(Vesd.fmt(p.targetGain)).append(',').append(Vesd.fmt(d.core.lastGain()))
                            .append(',').append(p.virtualGear).append(',').append(d.pathOpen ? 1 : 0).append(',').append(d.feeder == null ? 0 : d.feeder.underruns())
                            .append(',').append(p.reason);
                }
                sig.write(b.append('\n').toString().getBytes("UTF-8"));
                if (t - lastCpu >= 1000) {
                    lastCpu = t;
                    cpu.write((t + "," + procJiffies() + "," + totalJiffies() + "\n").getBytes("UTF-8"));
                }
                SystemClock.sleep(100);
            }
            write("DRIVE end after " + (SystemClock.uptimeMillis() - t0) / 1000 + " s; " + d.status());
        } catch (Throwable e) {
            write("DRIVE fail " + e);
        } finally {
            d.hscan.tap = null;
            close(raw);
            raw = null;
            close(sig);
            close(veh);
            close(cpu);
            d.setOverride(null);
            running = "";
        }
    }

    static long procJiffies() {
        String s = first("/proc/self/stat");
        if (s == null) return -1;
        String[] f = s.substring(s.lastIndexOf(')') + 2).split(" ");
        return Long.parseLong(f[11]) + Long.parseLong(f[12]); // utime + stime
    }

    /** "total,idle" from the aggregate cpu line */
    static String totalJiffies() {
        String s = first("/proc/stat");
        if (s == null) return "-1,-1";
        String[] f = s.trim().split("\\s+");
        long tot = 0;
        for (int i = 1; i < f.length; i++) tot += Long.parseLong(f[i]);
        return tot + "," + f[4];
    }

    // ---- helpers

    static Settings copy(Settings o) {
        Settings s = Settings.fromMap(new java.util.HashMap<String, String>());
        s.enabled = o.enabled;
        s.volume = o.volume;
        s.profile = o.profile;
        s.mode = o.mode;
        s.idleLevel = o.idleLevel;
        s.regenLevel = o.regenLevel;
        s.response = o.response;
        s.fadeAboveKph = o.fadeAboveKph;
        return s;
    }

    static String first(String path) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(path));
            return r.readLine();
        } catch (IOException e) {
            return null;
        } finally {
            close(r);
        }
    }

    static String prop(String k) {
        try {
            java.lang.Process p = Runtime.getRuntime().exec(new String[] { "/system/bin/getprop", k });
            BufferedReader r = new BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String v = r.readLine();
            p.waitFor();
            return v == null ? "" : v.trim();
        } catch (Throwable e) {
            return "?";
        }
    }

    static void exec(String[] cmd, File out) {
        OutputStream o = null;
        try {
            java.lang.Process p = Runtime.getRuntime().exec(cmd);
            InputStream in = p.getInputStream();
            o = new BufferedOutputStream(new FileOutputStream(out));
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            p.waitFor();
        } catch (Throwable e) {
            Out.log("probe: " + cmd[0] + ": " + e);
        } finally {
            close(o);
        }
    }

    static void filter(File in, File out, String regex) {
        BufferedReader r = null;
        OutputStream o = null;
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(regex);
            r = new BufferedReader(new FileReader(in));
            o = new BufferedOutputStream(new FileOutputStream(out));
            String l;
            while ((l = r.readLine()) != null) if (p.matcher(l).find()) o.write((l + "\n").getBytes("UTF-8"));
        } catch (Throwable ignored) {
        } finally {
            close(r);
            close(o);
        }
    }

    static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }
}
