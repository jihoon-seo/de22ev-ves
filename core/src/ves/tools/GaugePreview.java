package ves.tools;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
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
 * <pre>java ves.tools.GaugePreview &lt;out dir&gt;</pre> writes one PNG per state and size, plus sheet.png.
 */
public final class GaugePreview {
    static final int BG = 0xFF07090C;
    static final String[][] STATES = {
            {"p-idle", "0", "rpm=820 gear=1 sh=0 sel=0 kph=0 kw=0 shift=6000 dm=- profile=ice_shift state=park red=6500 max=8000"},
            {"eco-d4-cruise", "1", "rpm=2100 gear=4 sh=0 sel=5 kph=64 kw=12 shift=2950 dm=- profile=ice_shift state=drive red=6500 max=8000"},
            {"sport-near-shift", "3", "rpm=5550 gear=2 sh=0 sel=5 kph=58 kw=118 shift=5800 dm=- profile=ice_shift state=drive red=6500 max=8000"},
            {"sport-shift-flash", "3", "rpm=5900 gear=2 sh=0 sel=5 kph=61 kw=125 shift=5800 dm=- profile=ice_shift state=drive red=6500 max=8000"},
            {"normal-regen", "2", "rpm=1650 gear=3 sh=0 sel=5 kph=42 kw=-31 shift=3900 dm=- profile=ice_shift state=overrun red=6500 max=8000"},
            {"reverse-smooth", "0", "rpm=900 gear=0 sh=0 sel=7 kph=3 kw=2 shift=6000 dm=- profile=ice_smooth state=reverse_quiet red=6500 max=8000"},
            {"ev-future", "2", "rpm=5200 gear=0 sh=0 sel=5 kph=84 kw=63 shift=8500 dm=- profile=ev_future state=drive red=- max=9000"}};
    static final int[][] SIZES = {{1920, 720}, {1856, 300}};

    public static void main(String[] a) throws Exception {
        File out = new File(a.length > 0 ? a[0] : "gauge-preview");
        if (!out.isDirectory() && !out.mkdirs()) throw new IllegalStateException("cannot make " + out);
        int n = 0;
        BufferedImage sheet = new BufferedImage(1920, STATES.length * (720 + 300 + 40), BufferedImage.TYPE_INT_RGB);
        Graphics2D sg = sheet.createGraphics();
        int sy = 0;
        for (String[] st : STATES) {
            LiveData d = LiveData.parse("ok\n" + st[2]);
            for (int[] wh : SIZES) {
                BufferedImage img = render(d, Integer.parseInt(st[1]), wh[0], wh[1]);
                ImageIO.write(img, "png", new File(out, st[0] + "-" + wh[0] + "x" + wh[1] + ".png"));
                sg.drawImage(img, 0, sy, null);
                sy += wh[1];
                n++;
            }
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
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(new Color(BG, true));
        g.fillRect(0, 0, w, h);
        GaugeRenderer.draw(new Awt(g), d, d.rpm, theme, true, w, h);
        g.dispose();
        return img;
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
            Font f = new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, Math.max(1, Math.round(size)));
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
    }
}
