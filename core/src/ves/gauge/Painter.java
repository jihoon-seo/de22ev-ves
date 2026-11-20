package ves.gauge;

/**
 * The six drawing calls the gauge needs, so one renderer draws on the head unit (Android Canvas) and on a PC (AWT,
 * ves.tools.GaugePreview). Screen coordinates in pixels, y down. Angles in degrees clockwise from 3 o'clock (Android's
 * convention). Text: ASCII only, y is the baseline, align -1 left, 0 centre, 1 right; italic is a fixed slant.
 */
public interface Painter {
    /** colour (ARGB) for the calls that follow */
    void color(int argb);

    void line(float x1, float y1, float x2, float y2, float width);

    /** an arc stroke of the circle (cx, cy, r) */
    void arc(float cx, float cy, float r, float startDeg, float sweepDeg, float width);

    /** a filled polygon x0, y0, x1, y1 ... */
    void poly(float[] xy);

    void circle(float cx, float cy, float r);

    void text(String s, float x, float y, float size, boolean bold, boolean italic, int align);
}
