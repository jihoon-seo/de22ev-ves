package ves.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import org.json.JSONObject;

import io.github.jihoonseo.ves.R;

/**
 * The split-screen panel page (an app widget HMG-API shows in the right-hand
 * panel, mod.json provides.panel) and the receiver for the star key action:
 * on/off and volume -/+, with the current state.
 */
public final class VesWidget extends AppWidgetProvider {
    @Override
    public void onReceive(final Context ctx, Intent intent) {
        final String a = intent.getAction();
        if (Actions.ACTION_TOGGLE.equals(a) || Actions.ACTION_VOL_UP.equals(a) || Actions.ACTION_VOL_DOWN.equals(a)) {
            final PendingResult pr = goAsync();
            final Context app = ctx.getApplicationContext();
            new Thread(new Runnable() {
                public void run() {
                    try {
                        if (Actions.ACTION_TOGGLE.equals(a)) {
                            boolean on = Actions.toggle();
                            Actions.toast(app, on ? "엔진 사운드 켜짐" : "엔진 사운드 꺼짐");
                        } else {
                            int v = Actions.changeVolume(Actions.ACTION_VOL_UP.equals(a) ? 5 : -5);
                            Actions.toast(app, "엔진 사운드 볼륨 " + v);
                        }
                    } catch (Exception e) {
                        Actions.toast(app, "엔진 사운드: 설정 저장 실패");
                    }
                    updateAll(app);
                    pr.finish();
                }
            }).start();
            return;
        }
        super.onReceive(ctx, intent);
    }

    @Override
    public void onUpdate(final Context ctx, AppWidgetManager m, int[] ids) {
        final PendingResult pr = goAsync();
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                updateAll(app);
                pr.finish();
            }
        }).start();
    }

    static void updateAll(Context ctx) {
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        int[] ids = m.getAppWidgetIds(new ComponentName(ctx, VesWidget.class));
        if (ids == null || ids.length == 0) return;
        String line;
        boolean on = false;
        int vol = -1;
        try {
            JSONObject o = Actions.load();
            on = Actions.enabled(o);
            vol = Actions.volume(o);
            line = Actions.shortStatus(Actions.status());
        } catch (Exception e) {
            line = "설정을 읽을 수 없음";
        }
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.ves_widget);
        v.setTextViewText(R.id.w_state, (on ? "켜짐" : "꺼짐") + (vol >= 0 ? " · 볼륨 " + vol : ""));
        v.setTextViewText(R.id.w_status, line);
        v.setTextViewText(R.id.w_toggle, on ? "끄기" : "켜기");
        v.setOnClickPendingIntent(R.id.w_toggle, pi(ctx, Actions.ACTION_TOGGLE, 1));
        v.setOnClickPendingIntent(R.id.w_down, pi(ctx, Actions.ACTION_VOL_DOWN, 2));
        v.setOnClickPendingIntent(R.id.w_up, pi(ctx, Actions.ACTION_VOL_UP, 3));
        v.setOnClickPendingIntent(R.id.w_title,
                PendingIntent.getActivity(ctx, 4, new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 0));
        m.updateAppWidget(ids, v);
    }

    static PendingIntent pi(Context ctx, String action, int code) {
        Intent i = new Intent(action).setClass(ctx, VesWidget.class);
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
