package ves.tools;

import java.awt.Color;
import java.awt.Graphics2D;
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
 * The split screen's "계기판" panel page art (0.2.13): GaugeRenderer's drawDial / drawNeedle / drawLamps / drawBar
 * through AWT into transparent PNGs for the AppWidget's drawables (build.sh puts them in drawable-nodpi):
 * <pre>
 * gp_dial_ice.png  gp_dial_ev.png   480x480  the dial (0-8000 + 6500 redline / 0-9000), transparent outside the disc
 * gp_needle.png                     480x480  the needle at 0 rpm: &lt;rotate fromDegrees=0 toDegrees=260 pivot 50% 50%&gt;
 * gp_lamps_0..9.png gp_lamps_flash  480x40   the shift-lamp level-list: 0..9 lit, flash = level 10 (all red)
 * gp_bar_regen.png gp_bar_drive.png 240x24   full bars for ClipDrawables: regen left half (gravity right), drive right
 *                                            half (gravity left), levels PanelFrame.regen / drive
 * </pre>
 * <pre>java ves.tools.PanelArt &lt;out dir&gt; [--preview]</pre> --preview also writes panel-preview.png (640x670): the
 * art composed like the planned widget (clock, on/off button, lamps, dial + needle at 3200 rpm, gear, speed, bars).
 */
public final class PanelArt {
    public static final int DIAL = 480, LAMPS_W = 480, LAMPS_H = 40, BAR_W = 240, BAR_H = 24, PANEL_W = 640, PANEL_H = 670;
    /** the preview's state: ICE_SMOOTH in D at 3200 rpm, 72 km/h, 45 kW, VES on */
    static final String PREVIEW = "rpm=3200 gear=0 sh=0 sel=5 kph=72 kw=45 shift=3500 dm=- profile=ice_smooth state=drive red=6500 max=8000 on=1";

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
        int n = 0;
        write(dial("ice_smooth"), out, "gp_dial_ice.png");
        write(dial("ev_future"), out, "gp_dial_ev.png");
        write(needle(), out, "gp_needle.png");
        n += 3;
        for (int k = 0; k <= PanelFrame.LAMPS; k++, n++) write(lamps(k, false), out, "gp_lamps_" + k + ".png");
        write(lamps(PanelFrame.LAMPS, true), out, "gp_lamps_flash.png");
        write(bar(true), out, "gp_bar_regen.png");
        write(bar(false), out, "gp_bar_drive.png");
        n += 3;
        if (preview) write(preview(), out, "panel-preview.png");
        System.out.println("panel art: " + n + " PNGs" + (preview ? " + panel-preview.png" : "") + " in " + out);
    }

    static void write(BufferedImage img, File dir, String name) throws Exception {
        if (!ImageIO.write(img, "png", new File(dir, name))) throw new IllegalStateException("no PNG writer for " + name);
    }

    static BufferedImage dial(String profile) {
        BufferedImage img = clear(DIAL, DIAL);
        Graphics2D g = GaugePreview.smooth(img.createGraphics());
        GaugeRenderer.drawDial(new GaugePreview.Awt(g), profile, DIAL);
        g.dispose();
        return img;
    }

    static BufferedImage needle() {
        BufferedImage img = clear(DIAL, DIAL);
        Graphics2D g = GaugePreview.smooth(img.createGraphics());
        GaugeRenderer.drawNeedle(new GaugePreview.Awt(g), DIAL);
        g.dispose();
        return img;
    }

    static BufferedImage lamps(int lit, boolean flash) {
        BufferedImage img = clear(LAMPS_W, LAMPS_H);
        Graphics2D g = GaugePreview.smooth(img.createGraphics());
        GaugeRenderer.drawLamps(new GaugePreview.Awt(g), lit, flash, LAMPS_W, LAMPS_H);
        g.dispose();
        return img;
    }

    static BufferedImage bar(boolean regen) {
        BufferedImage img = clear(BAR_W, BAR_H);
        Graphics2D g = GaugePreview.smooth(img.createGraphics());
        GaugeRenderer.drawBar(new GaugePreview.Awt(g), regen, BAR_W, BAR_H);
        g.dispose();
        return img;
    }

    static BufferedImage clear(int w, int h) {
        return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB); // transparent
    }

    /** the planned widget at 640x670, the PNGs placed as its ImageViews would show them */
    static BufferedImage preview() {
        LiveData d = LiveData.parse("ok\n" + PREVIEW);
        PanelFrame f = PanelFrame.of(d, true);
        BufferedImage img = new BufferedImage(PANEL_W, PANEL_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = GaugePreview.smooth(img.createGraphics());
        GaugePreview.Awt p = new GaugePreview.Awt(g);
        g.setColor(new Color(GaugePreview.BG, true));
        g.fillRect(0, 0, PANEL_W, PANEL_H);
        int white = 0xFFF2F6FA, dim = 0x59FFFFFF, accent = Theme.accent(Theme.NEUTRAL);
        // top row: the clock (TextClock) and the on/off button
        p.color(white);
        p.text(GaugePreview.CLOCK, 20, 48, 28, false, false, -1);
        g.setColor(new Color(f.on == 1 ? accent : 0xFF3A3E44, true));
        g.fill(new RoundRectangle2D.Float(PANEL_W - 20 - 150, 16, 150, 48, 48, 48));
        p.color(f.on == 1 ? 0xFF0B0E12 : white);
        p.text(f.on == 1 ? "VES 켜짐" : "VES 꺼짐", PANEL_W - 20 - 75, 40 + 7, 20, true, false, 0);
        // lamps, dial, needle (RotateDrawable: fromDegrees 0, toDegrees 260, pivot 50% 50%)
        int x0 = (PANEL_W - DIAL) / 2, lampY = 78, dialY = 116;
        g.drawImage(lamps(f.lamps == PanelFrame.LAMPS_FLASH ? PanelFrame.LAMPS : f.lamps, f.lamps == PanelFrame.LAMPS_FLASH), x0, lampY, null);
        g.drawImage(dial(f.ev ? "ev_future" : "ice_smooth"), x0, dialY, null);
        AffineTransform keep = g.getTransform();
        g.rotate(Math.toRadians(GaugeMath.SWEEP_DEG * f.needle / 10000.0), x0 + DIAL / 2.0, dialY + DIAL / 2.0);
        g.drawImage(needle(), x0, dialY, null);
        g.setTransform(keep);
        // the centre: gear and speed (TextViews)
        float cx = PANEL_W / 2f, cy = dialY + DIAL / 2f, r = DIAL * 0.44f;
        p.color(white);
        p.text(f.gear, cx, cy + r * 0.12f, r * 0.44f, true, true, 0);
        p.text(f.speed, cx, cy + r * 0.45f, r * 0.21f, true, false, 0);
        p.color(dim);
        p.text("km/h", cx, cy + r * 0.57f, r * 0.065f, false, false, 0);
        // the bars: a faint track, regen clipped from the right in the left half, drive from the left in the right half
        int barY = dialY + DIAL + 8;
        g.setColor(new Color(0x24FFFFFF, true));
        g.fillRect(x0, barY + BAR_H / 4, DIAL, BAR_H / 2);
        int rw = BAR_W * f.regen / 10000, dw = BAR_W * f.drive / 10000;
        if (rw > 0) g.drawImage(bar(true).getSubimage(BAR_W - rw, 0, rw, BAR_H), x0 + BAR_W - rw, barY, null);
        if (dw > 0) g.drawImage(bar(false).getSubimage(0, 0, dw, BAR_H), x0 + BAR_W, barY, null);
        g.setColor(new Color(dim, true));
        g.fillRect(x0 + BAR_W - 1, barY, 2, BAR_H);
        p.text("회생", x0, barY + BAR_H + 24, 17, false, false, -1);
        p.text("출력", x0 + DIAL, barY + BAR_H + 24, 17, false, false, 1);
        g.dispose();
        return img;
    }
}
