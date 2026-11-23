package ves.app;

import android.os.Handler;
import android.os.Looper;

import java.io.IOException;

import ves.gauge.LiveData;
import ves.hmg.VesLink;

/**
 * Asks vesd for LIVE 10 times a second on one long-lived thread while a gauge is shown (start in onResume, stop in
 * onPause); vesd not answering: every second, and the gauge shows "--". The answers go to a Sink on the UI thread.
 */
final class LivePoller {
    /** gets each LIVE answer on the UI thread (null: vesd doesn't answer) */
    interface Sink {
        void setData(LiveData d);
    }

    static final long PERIOD_MS = 100, RETRY_MS = 1000;
    private final Sink view;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile Thread thread;

    LivePoller(Sink view) {
        this.view = view;
    }

    void start() {
        if (thread != null) return;
        Thread t = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "ves-gauge");
        thread = t;
        t.start();
    }

    void stop() {
        Thread t = thread;
        thread = null;
        if (t != null) t.interrupt();
    }

    private void loop() {
        Thread me = Thread.currentThread();
        while (thread == me) {
            LiveData d;
            try {
                d = LiveData.parse(VesLink.send("LIVE", 500));
            } catch (IOException e) {
                d = null;
            }
            final LiveData post = d;
            ui.post(new Runnable() {
                public void run() {
                    view.setData(post);
                }
            });
            try {
                Thread.sleep(d == null ? RETRY_MS : PERIOD_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
