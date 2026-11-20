package ves.app;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import ves.gauge.Painter;

/** ves.gauge.Painter over an Android Canvas; one Paint, Path and RectF reused (no allocation per frame). */
final class AndroidPainter implements Painter {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private Canvas canvas;

    AndroidPainter() {
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    AndroidPainter on(Canvas c) {
        canvas = c;
        return this;
    }

    public void color(int argb) {
        paint.setColor(argb);
    }

    public void line(float x1, float y1, float x2, float y2, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(width);
        canvas.drawLine(x1, y1, x2, y2, paint);
    }

    public void arc(float cx, float cy, float r, float startDeg, float sweepDeg, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeWidth(width);
        oval.set(cx - r, cy - r, cx + r, cy + r);
        canvas.drawArc(oval, startDeg, sweepDeg, false, paint);
    }

    public void poly(float[] xy) {
        path.reset();
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i + 1 < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(path, paint);
    }

    public void circle(float cx, float cy, float r) {
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, r, paint);
    }

    public void text(String s, float x, float y, float size, boolean bold, boolean italic, int align) {
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(size);
        paint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        paint.setTextSkewX(italic ? -0.25f : 0);
        paint.setTextAlign(align < 0 ? Paint.Align.LEFT : align == 0 ? Paint.Align.CENTER : Paint.Align.RIGHT);
        canvas.drawText(s, x, y, paint);
        paint.setTextSkewX(0);
    }
}
