package ves.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.View;

import ves.gauge.GaugeRenderer;
import ves.gauge.LiveData;
import ves.gauge.Needle;
import ves.gauge.Theme;

/**
 * The RPM + gear gauge (0.2.12), compact or full by its own aspect (GaugeRenderer). The static layer is drawn once
 * per size / profile / theme into a bitmap; each frame draws that and the dynamic layer. It animates only while the
 * needle is still moving or the shift lamps flash, so a settled gauge costs nothing between data points.
 */
final class GaugeView extends View {
    static final int BG = 0xFF07090C;
    private final AndroidPainter painter = new AndroidPainter();
    private final Needle needle = new Needle();
    private final Theme.Hold hold = new Theme.Hold();
    private LiveData data = unknown();
    private Bitmap layer;
    private String layerKey = "";
    private long lastFrame;

    GaugeView(Context c) {
        super(c);
        setBackgroundColor(BG);
    }

    /** new data from LivePoller (null: vesd doesn't answer) */
    void setData(LiveData d) {
        data = d == null ? unknown() : d;
        invalidate();
    }

    static LiveData unknown() {
        LiveData d = new LiveData();
        d.rpm = Double.NaN;
        d.kph = Double.NaN;
        return d;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        layerKey = "";
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        long now = SystemClock.uptimeMillis();
        double dt = lastFrame == 0 ? 0 : Math.min(0.2, (now - lastFrame) / 1000.0);
        lastFrame = now;
        LiveData d = data;
        double rpm = needle.step(d.rpm, dt);
        int theme = hold.update(d.driveMode, now);
        String key = w + "x" + h + " " + d.profile + " " + theme;
        if (!key.equals(layerKey) || layer == null) {
            if (layer == null || layer.getWidth() != w || layer.getHeight() != h) {
                if (layer != null) layer.recycle();
                layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }
            layer.eraseColor(0);
            GaugeRenderer.drawStatic(painter.on(new Canvas(layer)), d.profile, theme, w, h);
            layerKey = key;
        }
        c.drawBitmap(layer, 0, 0, null);
        boolean blinkOn = (now / 250) % 2 == 0;
        GaugeRenderer.drawDynamic(painter.on(c), d, rpm, theme, blinkOn, w, h);
        boolean moving = !Double.isNaN(d.rpm) && Math.abs(rpm - d.rpm) > 2;
        boolean flashing = ves.gauge.GaugeMath.shiftBlink(d.rpm, d.shiftRpm);
        if (moving || flashing) postInvalidateOnAnimation();
        else lastFrame = 0; // the next data point starts a fresh dt
    }
}
