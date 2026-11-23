package ves.app;

import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import java.io.IOException;
import java.util.Arrays;
import java.util.Calendar;

import ves.gauge.Clock;
import ves.gauge.GaugeMath;
import ves.gauge.LiveData;
import ves.gauge.PanelFrame;
import ves.hmg.VesLink;

/**
 * Feeds the split screen's "계기판" page (GaugeWidget, 0.2.13) on one worker thread. While Settings.System
 * "split_screen_status" is 1 and a page exists: vesd's LIVE every 200 ms (250 ms while the shift lamps flash, the
 * flash's phase), a PanelFrame, and partiallyUpdateAppWidget with only the fields that changed (plus the clock, set
 * from ves.gauge.Clock at each new second: TextClock's (E) would follow the system locale); a full update on the
 * first frame, when the pages change or a provider broadcast asked (onStartCommand). vesd silent: "--" and a retry
 * each second. Split off: idle, looking every 2 s. No page: stopSelf. VES turned on / off: the 엔진 사운드 page
 * (VesWidget) is refreshed too.
 */
public final class PanelService extends Service {
    static final long PERIOD_MS = 200, FLASH_MS = 250, IDLE_MS = 2000;
    private volatile Thread worker;
    private volatile boolean forceFull;
    private int lastStartId; // guarded by this

    static void start(Context c) {
        try {
            c.startService(new Intent(c, PanelService.class));
        } catch (RuntimeException e) {
            // a receiver context may refuse it; the next provider broadcast or app start tries again
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, PanelService.class));
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public synchronized int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        forceFull = true;
        if (worker == null) {
            Thread t = new Thread(new Runnable() {
                public void run() {
                    loop();
                }
            }, "ves-panel");
            worker = t;
            t.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Thread t = worker;
        worker = null;
        if (t != null) t.interrupt();
        super.onDestroy();
    }

    private void loop() {
        Thread me = Thread.currentThread();
        Context ctx = getApplicationContext();
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        int[] shown = null; // the pages the last full update went to (null: none since idle / start)
        PanelFrame prev = null;
        String prevClock = null;
        boolean flashOn = false;
        while (worker == me) {
            long t0 = System.currentTimeMillis();
            int startId;
            synchronized (this) {
                startId = lastStartId;
            }
            int[] ids = GaugeWidget.ids(ctx);
            if (ids.length == 0) {
                synchronized (this) {
                    if (startId == lastStartId) { // no start since the look: a page added now starts us again
                        worker = null;
                        stopSelf(startId);
                        return;
                    }
                }
                continue; // a start came in meanwhile (a new page?): look again
            }
            Arrays.sort(ids);
            long wait;
            if (Split.status(ctx.getContentResolver()) != 1) {
                shown = null; // full frame when the split screen comes back
                wait = IDLE_MS;
            } else {
                LiveData d;
                try {
                    d = LiveData.parse(VesLink.send("LIVE", 500));
                } catch (IOException e) {
                    d = null;
                }
                boolean flashing = d != null && GaugeMath.shiftBlink(d.rpm, d.shiftRpm);
                flashOn = flashing && !flashOn;
                PanelFrame f = PanelFrame.of(d == null ? GaugeView.unknown() : d, flashOn);
                String clock = Clock.format(Calendar.getInstance());
                boolean full = forceFull || shown == null || !Arrays.equals(ids, shown);
                try {
                    if (full) {
                        forceFull = false;
                        m.updateAppWidget(ids, GaugeWidget.full(ctx, f, clock));
                        shown = ids;
                    } else {
                        int mask = f.diff(prev);
                        boolean tick = !clock.equals(prevClock);
                        if (mask != 0 || tick) m.partiallyUpdateAppWidget(ids, GaugeWidget.partial(ctx, f, mask, tick ? clock : null));
                    }
                } catch (RuntimeException e) {
                    shown = null; // the widget service hiccuped: a full frame next time
                }
                if (prev != null && prev.on != f.on && f.on >= 0) refreshVesWidget(ctx);
                prev = f;
                prevClock = clock;
                // vesd silent: retry just after the next second, so the clock still ticks
                wait = d == null ? 1000 - System.currentTimeMillis() % 1000 + 10
                        : Math.max(0, (flashing ? FLASH_MS : PERIOD_MS) - (System.currentTimeMillis() - t0));
            }
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** the 엔진 사운드 page reads hmgapid (slow): off this thread */
    private static void refreshVesWidget(final Context ctx) {
        new Thread(new Runnable() {
            public void run() {
                VesWidget.updateAll(ctx);
            }
        }, "ves-widget").start();
    }
}
