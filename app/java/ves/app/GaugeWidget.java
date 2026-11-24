package ves.app;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.widget.RemoteViews;

import java.util.Calendar;

import io.github.jihoonseo.ves.R;
import ves.gauge.Clock;
import ves.gauge.PanelFrame;

/**
 * The split screen's "계기판" panel page (0.2.13, mod.json provides.panel, the first page): an app widget HMG-API shows in
 * the right-hand panel. PanelService keeps it current while the split screen is on (partial updates of the fields a
 * PanelFrame changed); this provider sends the first full frame and starts the service.
 */
public final class GaugeWidget extends AppWidgetProvider {
    static final int WHITE = 0xFFF2F6FA, INK = 0xFF0B0E12, GREY = 0xFF8A9096;

    @Override
    public void onUpdate(Context ctx, AppWidgetManager m, int[] ids) {
        m.updateAppWidget(ids, full(ctx, PanelFrame.of(GaugeView.unknown(), false), Clock.format(Calendar.getInstance())));
        PanelService.start(ctx); // it sends a full frame of live data next
    }

    @Override
    public void onDeleted(Context ctx, int[] ids) {
        PanelService.start(ctx); // the remaining pages get a full frame; none left: it stops itself
    }

    @Override
    public void onDisabled(Context ctx) {
        PanelService.stop(ctx);
    }

    static int[] ids(Context ctx) {
        int[] ids = AppWidgetManager.getInstance(ctx).getAppWidgetIds(new ComponentName(ctx, GaugeWidget.class));
        return ids == null ? new int[0] : ids;
    }

    /** every field and the button's action: for updateAppWidget */
    static RemoteViews full(Context ctx, PanelFrame f, String clock) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.gauge_widget);
        apply(v, f, PanelFrame.ALL, clock);
        v.setOnClickPendingIntent(R.id.g_toggle, VesWidget.pi(ctx, Actions.ACTION_TOGGLE, 1));
        return v;
    }

    /** the fields in mask (PanelFrame.F_*) and the clock unless null: for partiallyUpdateAppWidget */
    static RemoteViews partial(Context ctx, PanelFrame f, int mask, String clock) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.gauge_widget);
        apply(v, f, mask, clock);
        return v;
    }

    static void apply(RemoteViews v, PanelFrame f, int mask, String clock) {
        if (clock != null) v.setTextViewText(R.id.g_clock, clock);
        if ((mask & PanelFrame.F_DIAL) != 0) v.setImageViewResource(R.id.g_dial, f.ev ? R.drawable.gp_dial_ev : R.drawable.gp_dial_ice);
        if ((mask & PanelFrame.F_NEEDLE) != 0) v.setInt(R.id.g_needle, "setImageLevel", f.needle);
        if ((mask & PanelFrame.F_LAMPS) != 0) {
            v.setInt(R.id.g_lamps, "setImageLevel", f.lampsLevel());
            v.setInt(R.id.g_flash, "setImageLevel", f.flashLevel());
        }
        if ((mask & PanelFrame.F_GEAR) != 0) v.setTextViewText(R.id.g_gear, f.gear);
        if ((mask & PanelFrame.F_SPEED) != 0) v.setTextViewText(R.id.g_speed, "-".equals(f.speed) ? "--" : f.speed);
        if ((mask & PanelFrame.F_ON) != 0) {
            v.setTextViewText(R.id.g_toggle, f.on == 1 ? "VES 켜짐" : f.on == 0 ? "VES 꺼짐" : "VES --");
            v.setTextColor(R.id.g_toggle, f.on == 1 ? INK : f.on == 0 ? WHITE : GREY);
            v.setInt(R.id.g_toggle, "setBackgroundResource",
                    f.on == 1 ? R.drawable.gauge_btn_on : f.on == 0 ? R.drawable.gauge_btn_off : R.drawable.gauge_btn_unknown);
        }
    }
}
