package ves.app;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import ves.gauge.GaugeRenderer;

/**
 * The full-width gauge screen (0.2.12); 0.2.13: with the clock, the VES on/off button (a tap on it toggles) and the
 * camera panel; a tap anywhere else goes back to the settings screen. Narrowed to the left while the split screen's
 * panel covers the right (Split).
 */
public final class GaugeActivity extends Activity {
    private GaugeView gauge;
    private LivePoller poller;
    private Split split;
    private volatile boolean toggling;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        gauge = new GaugeView(this);
        gauge.setExtras(true);
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        gauge.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;

            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getX();
                        downY = e.getY();
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (Math.abs(e.getX() - downX) <= slop && Math.abs(e.getY() - downY) <= slop) tap(v, e.getX(), e.getY());
                        return true;
                    default:
                        return true;
                }
            }
        });
        poller = new LivePoller(gauge);
        split = Split.wrap(this, gauge, GaugeView.BG);
        setContentView(split.root());
    }

    private void tap(View v, float x, float y) {
        float[] r = GaugeRenderer.buttonRect(v.getWidth(), v.getHeight());
        if (x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3]) toggle();
        else finish();
    }

    /** VES on/off off the UI thread; the gauge's button follows with the next LIVE answer (on=) */
    private void toggle() {
        if (toggling) return;
        toggling = true;
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                try {
                    boolean on = Actions.toggle();
                    Actions.toast(app, on ? "엔진 사운드 켜짐" : "엔진 사운드 꺼짐");
                } catch (Exception e) {
                    Actions.toast(app, "엔진 사운드: 설정 저장 실패");
                } finally {
                    toggling = false;
                }
                VesWidget.updateAll(app);
            }
        }, "ves-toggle").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        split.resume();
        poller.start();
    }

    @Override
    protected void onPause() {
        poller.stop();
        split.pause();
        super.onPause();
    }
}
