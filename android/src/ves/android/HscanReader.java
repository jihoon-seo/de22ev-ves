package ves.android;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;

import java.io.IOException;
import java.io.InputStream;

import ves.core.HscanFramer;

/**
 * Reads the vehicle data stream that the head unit's hscand pushes to clients of
 * the abstract socket "hscan_mux". Read only: nothing is ever written to the
 * socket (tests/android/allowlist.sh checks that no getOutputStream() is used here).
 */
final class HscanReader implements Runnable {
    interface Tap {
        void onChunk(byte[] b, int off, int len, long tMs);
    }

    static final String SOCKET = "hscan_mux";
    private final HscanFramer framer;
    private final Object lock;
    volatile Tap tap;
    volatile boolean paused;
    volatile boolean connected;
    volatile long bytes, lastDataMs, connects;
    volatile String lastError = "";
    private volatile boolean stop;

    HscanReader(HscanFramer framer, Object lock) {
        this.framer = framer;
        this.lock = lock;
    }

    void stop() {
        stop = true;
    }

    public void run() {
        byte[] buf = new byte[4096];
        long backoff = 500;
        while (!stop) {
            LocalSocket s = new LocalSocket();
            try {
                s.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
                s.setSoTimeout(3000);
                connected = true;
                connects++;
                backoff = 500;
                Out.log("hscan: connected");
                InputStream in = s.getInputStream();
                while (!stop) {
                    int n;
                    try {
                        n = in.read(buf);
                    } catch (IOException timeout) {
                        if (SystemClock.uptimeMillis() - lastDataMs > 10000) throw timeout;
                        continue;
                    }
                    if (n < 0) throw new IOException("closed by hscand");
                    long t = SystemClock.uptimeMillis();
                    bytes += n;
                    lastDataMs = t;
                    Tap tp = tap;
                    if (tp != null) tp.onChunk(buf, 0, n, t);
                    if (!paused) {
                        synchronized (lock) {
                            framer.feed(buf, 0, n, t);
                        }
                    }
                }
            } catch (Throwable e) {
                lastError = String.valueOf(e.getMessage());
                if (connected) Out.log("hscan: lost (" + lastError + ")");
            } finally {
                connected = false;
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
            if (stop) break;
            SystemClock.sleep(backoff);
            backoff = Math.min(backoff * 2, 10000);
        }
    }
}
