package ves.tools;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

import ves.gauge.GaugeRenderer;
import ves.gauge.LiveData;
import ves.gauge.Painter;
import ves.gauge.Theme;

/**
 * Host-only preview of the gauge (0.2.12): draws GaugeRenderer through AWT into PNGs, full 1920x720 and the main
 * screen's compact strip, for design review before the car. Fonts differ from the head unit's Roboto: layout only.
 * 0.2.13: also the split screen's sizes (the full gauge narrowed to 1281x720, the strip 1217x300) and the full gauge's
 * extras (clock, VES on/off button, camera panel).
 * <pre>java ves.tools.GaugePreview &lt;out dir&gt;</pre> writes one PNG per state and size (40), plus sheet.png.
 */
public final class GaugePreview {
    static final int BG = 0xFF07090C;
    /** the full gauge's clock in every preview (Clock.format of a fixed time: deterministic PNGs) */
    static final String CLOCK = "11월 23일 (월) 14:05";
    static final String CAM_SPEED = " nav=1 cam=speed lim=60 dist=350 zone=0 sec=0 slim=- savg=- ssrc=- sdist=-",
            CAM_SECTION = " nav=1 cam=section lim=80 dist=- zone=0 sec=1 slim=80 savg=87 ssrc=nav sdist=1500",
            CAM_MOBILE = " nav=1 cam=mobile lim=80 dist=1200 zone=0 sec=0 slim=- savg=- ssrc=- sdist=-";
    /** name, theme, LIVE line; on= picks the button's state (left out: unknown "--"), cam= the camera panel */
    static final String[][] STATES = {
            {"p-idle", "0", "rpm=820 gear=1 sh=0 sel=0 kph=0 kw=0 shift=6000 dm=- profile=ice_shift state=park red=6500 max=8000 on=0"},
            {"eco-d4-cruise", "1", "rpm=2100 gear=4 sh=0 sel=5 kph=64 kw=12 shift=2950 dm=- profile=ice_shift state=drive red=6500 max=8000 on=1"},
            {"sport-near-shift", "3", "rpm=5550 gear=2 sh=0 sel=5 kph=58 kw=118 shift=5800 dm=- profile=ice_shift state=drive red=6500 max=8000 on=1"},
            {"sport-shift-flash", "3", "rpm=5900 gear=2 sh=0 sel=5 kph=61 kw=125 shift=5800 dm=- profile=ice_shift state=drive red=6500 max=8000 on=1"},
            {"normal-regen", "2", "rpm=1650 gear=3 sh=0 sel=5 kph=42 kw=-31 shift=3900 dm=- profile=ice_shift state=overrun red=6500 max=8000 on=1"},
            {"reverse-smooth", "0", "rpm=900 gear=0 sh=0 sel=7 kph=3 kw=2 shift=6000 dm=- profile=ice_smooth state=reverse_quiet red=6500 max=8000"},
            {"ev-future", "2", "rpm=5200 gear=0 sh=0 sel=5 kph=84 kw=63 shift=8500 dm=- profile=ev_future state=drive red=- max=9000 on=1"},
            {"cam-speed-over", "0", "rpm=2600 gear=4 sh=0 sel=5 kph=72 kw=28 shift=3300 dm=- profile=ice_shift state=drive red=6500 max=8000 on=1"
                    + CAM_SPEED},
            {"cam-section-avg", "0", "rpm=2900 gear=5 sh=0 sel=5 kph=84 kw=35 shift=4700 dm=- profile=ice_shift state=drive red=6500 max=8000 on=1"
                    + CAM_SECTION},
            {"cam-mobile-off", "0", "rpm=4100 gear=0 sh=0 sel=5 kph=64 kw=20 shift=8500 dm=- profile=ev_future state=drive red=- max=9000 on=0"
                    + CAM_MOBILE}};
    /** full, the split screen's full gauge (1281), the main screen's strip, the strip when split (1281 - 64 padding) */
    static final int[][] SIZES = {{1920, 720}, {1281, 720}, {1856, 300}, {1217, 300}};

    public static void main(String[] a) throws Exception {
        File out = new File(a.length > 0 ? a[0] : "gauge-preview");
        if (!out.isDirectory() && !out.mkdirs()) throw new IllegalStateException("cannot make " + out);
        int n = 0;
        // the sheet at half scale: per state the two full sizes side by side, the two strips below them
        int row = 360 + 150 + 40;
        BufferedImage sheet = new BufferedImage(1920, STATES.length * row, BufferedImage.TYPE_INT_RGB);
        Graphics2D sg = sheet.createGraphics();
        sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        int sy = 0;
        for (String[] st : STATES) {
            LiveData d = LiveData.parse("ok\n" + st[2]);
            int xFull = 0, xStrip = 0;
            for (int[] wh : SIZES) {
                BufferedImage img = render(d, Integer.parseInt(st[1]), wh[0], wh[1]);
                ImageIO.write(img, "png", new File(out, st[0] + "-" + wh[0] + "x" + wh[1] + ".png"));
                if (wh[1] < 720) {
                    sg.drawImage(img, xStrip, sy + 360, wh[0] / 2, wh[1] / 2, null);
                    xStrip += wh[0] / 2 + 16;
                } else {
                    sg.drawImage(img, xFull, sy, wh[0] / 2, wh[1] / 2, null);
                    xFull += wh[0] / 2 + 16;
                }
                n++;
            }
            sy += 360 + 150;
            sg.setColor(new Color(0x30, 0x34, 0x3A));
            sg.fillRect(0, sy, 1920, 40);
            sg.setColor(Color.LIGHT_GRAY);
            sg.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 22));
            sg.drawString(st[0] + "  (theme " + Theme.name(Integer.parseInt(st[1])) + ")", 16, sy + 28);
            sy += 40;
        }
        sg.dispose();
        ImageIO.write(sheet, "png", new File(out, "sheet.png"));
        System.out.println("gauge preview: " + n + " images + sheet.png in " + out);
    }

    static BufferedImage render(LiveData d, int theme, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = smooth(img.createGraphics());
        g.setColor(new Color(BG, true));
        g.fillRect(0, 0, w, h);
        Awt p = new Awt(g);
        GaugeRenderer.draw(p, d, d.rpm, theme, true, w, h);
        GaugeRenderer.drawExtras(p, d, CLOCK, w, h); // the full gauge only (nothing on the strip)
        g.dispose();
        return img;
    }

    static Graphics2D smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR); // PanelArt's rotated needle
        return g;
    }

    /** a font with Hangul for the Korean texts (clock weekday, button, camera): Malgun Gothic on Windows, else Dialog */
    static final String HANGUL = hangulFamily();

    static String hangulFamily() {
        for (String f : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames())
            if (f.equals("Malgun Gothic")) return f;
        return Font.DIALOG;
    }

    /** Painter over Graphics2D (Android angles: clockwise from 3 o'clock; AWT's are counter-clockwise) */
    static final class Awt implements Painter {
        final Graphics2D g;

        Awt(Graphics2D g) {
            this.g = g;
        }

        public void color(int argb) {
            g.setColor(new Color(argb, true));
        }

        public void line(float x1, float y1, float x2, float y2, float width) {
            g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Float(x1, y1, x2, y2));
        }

        public void arc(float cx, float cy, float r, float startDeg, float sweepDeg, float width) {
            g.setStroke(new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
            g.draw(new Arc2D.Float(cx - r, cy - r, 2 * r, 2 * r, -startDeg, -sweepDeg, Arc2D.OPEN));
        }

        public void poly(float[] xy) {
            Path2D.Float p = new Path2D.Float();
            p.moveTo(xy[0], xy[1]);
            for (int i = 2; i + 1 < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
            p.closePath();
            g.fill(p);
        }

        public void circle(float cx, float cy, float r) {
            g.fill(new Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r));
        }

        public void text(String s, float x, float y, float size, boolean bold, boolean italic, int align) {
            // ASCII keeps the 0.2.12 font (identical PNGs); Korean needs a font with Hangul
            Font f = new Font(ascii(s) ? Font.SANS_SERIF : HANGUL, bold ? Font.BOLD : Font.PLAIN, Math.max(1, Math.round(size)));
            float w = g.getFontMetrics(f).stringWidth(s);
            float x0 = align < 0 ? x : align == 0 ? x - w / 2 : x - w;
            AffineTransform keep = g.getTransform();
            g.setFont(f);
            if (italic) { // the same slant as Android's setTextSkewX(-0.25f)
                g.translate(x0, y);
                g.shear(-0.25, 0);
                g.drawString(s, 0, 0);
            } else {
                g.drawString(s, x0, y);
            }
            g.setTransform(keep);
        }

        static boolean ascii(String s) {
            for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 0x7E) return false;
            return true;
        }
    }
}
