package ves.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import ves.hmg.Prefs;

/**
 * One screen, big buttons: on/off, volume -/+, sound, behaviour, and the finer
 * settings; status line from vesd. Every change is saved to the mod's settings
 * (the same ones HMG-API's Mods tab shows).
 */
public final class MainActivity extends Activity {
    private final Handler h = new Handler();
    private TextView status, volume;
    private Button power, profile, mode, idle, regen, response, fade, type;
    private boolean visible;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 20, 32, 20);
        sv.addView(root);
        sv.setBackgroundColor(Color.rgb(16, 18, 22));

        TextView title = text(root, "가상 엔진 사운드", 30, Color.WHITE);
        title.setPadding(0, 0, 0, 8);
        status = text(root, "…", 20, Color.rgb(150, 200, 255));

        LinearLayout row = row(root);
        power = big(row, "켜기", 2);
        power.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                act(new Op() {
                    public void run() throws Exception {
                        Actions.toggle();
                    }
                });
            }
        });
        Button minus = big(row, "볼륨 −", 1);
        volume = text(row, "--", 34, Color.WHITE);
        volume.setGravity(Gravity.CENTER);
        volume.setLayoutParams(new LinearLayout.LayoutParams(0, 140, 1));
        Button plus = big(row, "볼륨 +", 1);
        minus.setOnClickListener(volumeBy(-5));
        plus.setOnClickListener(volumeBy(5));

        profile = setting(root, Prefs.PROFILE, Actions.PROFILES);
        mode = setting(root, Prefs.MODE, Actions.MODES);
        LinearLayout r2 = row(root);
        idle = setting(r2, Prefs.IDLE_LEVEL, Actions.IDLE);
        regen = setting(r2, Prefs.REGEN_LEVEL, Actions.REGEN);
        LinearLayout r3 = row(root);
        response = setting(r3, Prefs.RESPONSE, Actions.RESPONSE);
        fade = setting(r3, Prefs.FADE_ABOVE, Actions.FADE);
        type = setting(root, Prefs.AUDIO_TYPE, Actions.TYPES);

        Button probe = big(root, "차량 점검 (주차 상태에서)", 0);
        probe.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ProbeActivity.class));
            }
        });
        text(root, "주행 중에는 조작하지 마세요. 차 밖으로 나가는 보행자 경고음(VESS)은 바꾸지 않습니다.", 15, Color.GRAY);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        refresh();
    }

    @Override
    protected void onPause() {
        visible = false;
        super.onPause();
    }

    interface Op {
        void run() throws Exception;
    }

    private View.OnClickListener volumeBy(final int d) {
        return new View.OnClickListener() {
            public void onClick(View v) {
                act(new Op() {
                    public void run() throws Exception {
                        Actions.changeVolume(d);
                    }
                });
            }
        };
    }

    private Button setting(LinearLayout parent, final String key, final String[] values) {
        Button b = big(parent, "…", parent.getOrientation() == LinearLayout.HORIZONTAL ? 1 : 0);
        b.setTextSize(20);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                act(new Op() {
                    public void run() throws Exception {
                        Actions.cycle(key, values);
                    }
                });
            }
        });
        return b;
    }

    private void act(final Op op) {
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    op.run();
                } catch (Exception e) {
                    err = e.getMessage();
                }
                VesWidget.updateAll(getApplicationContext());
                final String e2 = err;
                h.post(new Runnable() {
                    public void run() {
                        if (e2 != null) status.setText("저장 실패: " + e2);
                        refresh();
                    }
                });
            }
        }).start();
    }

    private void refresh() {
        new Thread(new Runnable() {
            public void run() {
                JSONObject o = null;
                try {
                    o = Actions.load();
                } catch (Exception ignored) {
                }
                final JSONObject s = o;
                final String st = Actions.shortStatus(Actions.status());
                h.post(new Runnable() {
                    public void run() {
                        show(s, st);
                    }
                });
            }
        }).start();
        h.removeCallbacks(tick);
        if (visible) h.postDelayed(tick, 1000);
    }

    private final Runnable tick = new Runnable() {
        public void run() {
            if (visible) refresh();
        }
    };

    private void show(JSONObject o, String st) {
        status.setText(st);
        if (o == null) {
            status.setText("HMG-API 설정을 읽을 수 없음 (모드가 켜져 있나요?)");
            return;
        }
        boolean on = Actions.enabled(o);
        power.setText(on ? "켜짐 (누르면 끄기)" : "꺼짐 (누르면 켜기)");
        power.setBackgroundColor(on ? Color.rgb(30, 110, 60) : Color.rgb(70, 70, 76));
        volume.setText(String.valueOf(Actions.volume(o)));
        profile.setText("소리: " + Actions.name(o.optString(Prefs.PROFILE), Actions.PROFILES, Actions.PROFILE_NAMES));
        mode.setText("동작: " + Actions.name(o.optString(Prefs.MODE), Actions.MODES, Actions.MODE_NAMES));
        idle.setText("공회전 크기 " + o.optString(Prefs.IDLE_LEVEL) + "%");
        regen.setText("회생음 크기 " + o.optString(Prefs.REGEN_LEVEL) + "%");
        response.setText("반응: " + Actions.name(o.optString(Prefs.RESPONSE), Actions.RESPONSE, Actions.RESPONSE_NAMES));
        String f = o.optString(Prefs.FADE_ABOVE);
        fade.setText("0".equals(f) ? "고속 줄임: 끔" : "고속 줄임: " + f + " km/h부터");
        type.setText("오디오 채널: " + Actions.name(o.optString(Prefs.AUDIO_TYPE), Actions.TYPES, Actions.TYPE_NAMES));
    }

    // ---- small view helpers

    static TextView text(LinearLayout p, String s, int sp, int color) {
        TextView t = new TextView(p.getContext());
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setPadding(8, 6, 8, 6);
        p.addView(t);
        return t;
    }

    static LinearLayout row(LinearLayout p) {
        LinearLayout r = new LinearLayout(p.getContext());
        r.setOrientation(LinearLayout.HORIZONTAL);
        p.addView(r, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return r;
    }

    static Button big(LinearLayout p, String s, int weight) {
        Button b = new Button(p.getContext());
        b.setText(s);
        b.setTextSize(24);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.rgb(50, 54, 62));
        LinearLayout.LayoutParams lp = weight > 0
                ? new LinearLayout.LayoutParams(0, 140, weight)
                : new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 120);
        lp.setMargins(8, 8, 8, 8);
        p.addView(b, lp);
        return b;
    }
}
