package ves.tools;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

import ves.gauge.GaugeMath;
import ves.gauge.GaugeRenderer;
import ves.gauge.LiveData;
import ves.gauge.PanelFrame;
import ves.gauge.Theme;

/**
 * The split screen's "계기판" panel page art (0.2.13; 0.2.14 the shift lamps on a ring round the dial, no power bar,
 * the dial as big as the page allows): GaugeRenderer's drawDial / drawNeedle / drawLamps through AWT into transparent
 * PNGs for the AppWidget's drawables (build.sh puts them in drawable-nodpi):
 * <pre>
 * gp_dial_ice.png  gp_dial_ev.png   600x600  the dial (0-8000 + 6500 redline / 0-9000) with the dark shift lamps on
 *                                            its ring, transparent outside the disc
 * gp_needle.png                     600x600  the needle at 0 rpm: &lt;rotate fromDegrees=0 toDegrees=260 pivot 50% 50%&gt;
 * gp_lamps_lit.png gp_lamps_flash   600x600  the ring's lamps lit (green, amber, red) / all red, over the dial in
 *                                            ClipDrawables: levels PanelFrame.lampsLevel / flashLevel
 * </pre>
 * 600 px: about the dial's size on the page (the panel is about 640 x 670 px), so the car scales it little.
 * <pre>java ves.tools.PanelArt &lt;out dir&gt; [--preview]</pre> --preview also writes panel-preview.png (640x670): the
 * page as res/layout/gauge_widget.xml lays it out at the car's 240 dpi (1 dp = 1.5 px): the clock and the on/off
 * button over the top corners, the dial square filling the width below a 26 dp band (its centre at DIAL_CY), needle at 3200 rpm, gear, speed.
 */
public final class PanelArt {
    public static final int DIAL = 600, PANEL_W = 640, PANEL_H = 670;
    /** the layout's dial box: 26 dp from the top, 4 dp at the sides, 2 dp at the bottom (px at 1.5 px per dp) */
    static final int BOX_TOP = 39, BOX_SIDE = 6, BOX_BOTTOM = 3;
    /** the preview's state: ICE_SHIFT in D4 at 3200 rpm, 72 km/h, VES on */
    static final String PREVIEW = "rpm=3200 gear=4 sh=0 sel=5 kph=72 kw=45 shift=3500 dm=- profile=ice_shift state=drive red=6500 max=8000 on=1";

    private PanelArt() {}

    public static void main(String[] a) throws Exception {
        String dir = null;
        boolean preview = false;
        for (String s : a) {
            if (s.equals("--preview")) preview = true;
            else dir = s;
        }
        if (dir == null) throw new IllegalArgumentException("usage: PanelArt <out dir> [--preview]");
        File out = new File(dir);
        if (!out.isDirectory() && !out.mkdirs()) throw new IllegalStateException("cannot make " + out);
        write(dial("ice_smooth", DIAL), out, "gp_dial_ice.png");
        write(dial("ev_future", DIAL), out, "gp_dial_ev.png");
        write(needle(DIAL), out, "gp_needle.png");
        write(lamps(false, DIAL), out, "gp_lamps_lit.png");
        write(lamps(true, DIAL), out, "gp_lamps_flash.png");
        int n = 5;
        if (preview) write(preview(), out, "panel-preview.png");
        System.out.println("panel art: " + n + " PNGs" + (preview ? " + panel-preview.png" : "") + " in " + out);
    }

    static void write(BufferedImage img, File dir, String name) throws Exception {
        if (!ImageIO.write(img, "png", new File(dir, name))) throw new IllegalStateException("no PNG writer for " + name);
    }

    static BufferedImage dial(String profile, int size) {
        BufferedImage img = clear(size, size);
        Graphics2D g = GaugePreview.smooth(img.createGraphics());
        GaugeRenderer.drawDial(new GaugePreview.Awt(g), profile, size);
        g.dispose();
        return img;
    }

    static BufferedImage needle(int size) {
        BufferedImage img = clear(size, size);
        Graphics2D g = GaugePreview.smooth(img.createGraphics());
        GaugeRenderer.drawNeedle(new GaugePreview.Awt(g), size);
        g.dispose();
        return img;
    }

    static BufferedImage lamps(boolean flash, int size) {
        BufferedImage img = clear(size, size);
        Graphics2D g = GaugePreview.smooth(img.createGraphics());
        GaugeRenderer.drawLamps(new GaugePreview.Awt(g), flash, size);
        g.dispose();
        return img;
    }

    static BufferedImage clear(int w, int h) {
        return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB); // transparent
    }

    /** the planned widget at 640x670 (gauge_widget.xml at 1.5 px per dp), the art drawn where its ImageViews put it */
    static BufferedImage preview() {
        LiveData d = LiveData.parse("ok\n" + PREVIEW);
        PanelFrame f = PanelFrame.of(d, true);
        BufferedImage img = new BufferedImage(PANEL_W, PANEL_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = GaugePreview.smooth(img.createGraphics());
        GaugePreview.Awt p = new GaugePreview.Awt(g);
        g.setColor(new Color(GaugePreview.BG, true));
        g.fillRect(0, 0, PANEL_W, PANEL_H);
        int white = 0xFFF2F6FA, dim = 0x59FFFFFF, accent = Theme.accent(Theme.NEUTRAL);
        // the dial box: fitCenter, a square as big as the box allows, centred in it
        int bw = PANEL_W - 2 * BOX_SIDE, bh = PANEL_H - BOX_TOP - BOX_BOTTOM, size = Math.min(bw, bh);
        int x0 = BOX_SIDE + (bw - size) / 2, y0 = BOX_TOP + (bh - size) / 2;
        AffineTransform keep = g.getTransform();
        g.translate(x0, y0);
        Shape all = g.getClip();
        GaugeRenderer.drawDial(p, f.ev ? "ev_future" : "ice_smooth", size);
        // lit lamps and flash clipped from the left (ClipDrawables), the needle turned about the centre (RotateDrawable)
        int lw = size * f.lampsLevel() / 10000, fw = size * f.flashLevel() / 10000;
        if (lw > 0) {
            g.clipRect(0, 0, lw, size);
            GaugeRenderer.drawLamps(p, false, size);
            g.setClip(all);
        }
        if (fw > 0) {
            g.clipRect(0, 0, fw, size);
            GaugeRenderer.drawLamps(p, true, size);
            g.setClip(all);
        }
        g.rotate(Math.toRadians(GaugeMath.SWEEP_DEG * f.needle / 10000.0), size / 2.0, size * GaugeRenderer.DIAL_CY);
        GaugeRenderer.drawNeedle(p, size);
        g.setTransform(keep);
        // the centre: gear 84 sp, speed 40 sp, km/h 13 sp (TextViews)
        float cx = x0 + size / 2f, cy = y0 + size * GaugeRenderer.DIAL_CY, r = size * GaugeRenderer.DIAL_R;
        p.color(white);
        p.text(f.gear, cx, cy + r * 0.12f, 126, true, true, 0);
        p.text(f.speed, cx, cy + r * 0.45f, 60, true, false, 0);
        p.color(dim);
        p.text("km/h", cx, cy + r * 0.57f, 19.5f, false, false, 0);
        // over the top corners: the clock (18 sp) and the on/off button (96 x 32 dp, 14 sp)
        p.color(white);
        p.text(GaugePreview.CLOCK, 18, 9 + 27, 27, false, false, -1);
        g.setColor(new Color(f.on == 1 ? accent : 0xFF3A3E44, true));
        g.fill(new RoundRectangle2D.Float(PANEL_W - 12 - 144, 9, 144, 48, 48, 48));
        p.color(f.on == 1 ? 0xFF0B0E12 : white);
        p.text(f.on == 1 ? "VES 켜짐" : "VES 꺼짐", PANEL_W - 12 - 72, 9 + 24 + 7, 21, true, false, 0);
        g.dispose();
        return img;
    }
}
