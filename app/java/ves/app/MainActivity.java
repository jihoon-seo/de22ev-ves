package ves.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ves.hmg.Prefs;
import ves.hmg.VesLink;

/**
 * One screen, big buttons: on/off, volume -/+, sound, behaviour, and the finer
 * settings; status line from vesd. Every change is saved to the mod's settings
 * (the same ones HMG-API's Mods tab shows).
 */
public final class MainActivity extends Activity {
    private final Handler h = new Handler();
    private TextView status, volume;
    private Button power, sound, profile, behavior, pops, prompts, mode, idle, regen, response, fade, speedBoost;
    private final Map<String, String> packNames = new HashMap<String, String>();
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

        sound = big(root, "…", 0);
        sound.setTextSize(20);
        sound.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                chooseSound();
            }
        });
        Button imp = big(root, "USB에서 소리 팩 가져오기 (VES/packs)", 0);
        imp.setTextSize(18);
        imp.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                importPack();
            }
        });
        profile = setting(root, Prefs.PROFILE, Actions.PROFILES);
        LinearLayout r1 = row(root);
        behavior = setting(r1, Prefs.BEHAVIOR, Actions.BEHAVIORS);
        pops = setting(r1, Prefs.POPS, Actions.POPS);
        prompts = setting(root, Prefs.PROMPTS, Actions.PROMPTS);
        mode = setting(root, Prefs.MODE, Actions.MODES);
        LinearLayout r2 = row(root);
        idle = setting(r2, Prefs.IDLE_LEVEL, Actions.IDLE);
        regen = setting(r2, Prefs.REGEN_LEVEL, Actions.REGEN);
        LinearLayout r3 = row(root);
        response = setting(r3, Prefs.RESPONSE, Actions.RESPONSE);
        fade = setting(r3, Prefs.FADE_ABOVE, Actions.FADE);
        speedBoost = setting(root, Prefs.SPEED_BOOST, Actions.SPEED_BOOST);

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
                final Map<String, String> names = packList();
                h.post(new Runnable() {
                    public void run() {
                        if (names != null) {
                            packNames.clear();
                            packNames.putAll(names);
                        }
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

    /** slot -> pack name ("-": empty) from vesd, null when vesd doesn't answer */
    static Map<String, String> packList() {
        try {
            String r = VesLink.send("PACK LIST", 3000);
            if (!r.startsWith("ok")) return null;
            Map<String, String> m = new HashMap<String, String>();
            String[] ls = r.split("\n");
            for (int i = 1; i < ls.length; i++) {
                int sp = ls[i].indexOf(' ');
                if (sp > 0) m.put(ls[i].substring(0, sp), ls[i].substring(sp + 1));
            }
            return m;
        } catch (IOException e) {
            return null;
        }
    }

    /** pick a pack folder on the stick, then a user slot; vesd validates and copies it */
    private void importPack() {
        status.setText("USB의 소리 팩 찾는 중…");
        new Thread(new Runnable() {
            public void run() {
                final List<String> found = new ArrayList<String>();
                String err = null;
                try {
                    String r = VesLink.send("PACK USB", 8000);
                    if (r.startsWith("ok")) {
                        String[] ls = r.split("\n");
                        for (int i = 1; i < ls.length; i++) if (ls[i].trim().length() > 0) found.add(ls[i].trim());
                    } else err = r;
                } catch (IOException e) {
                    err = "서비스 연결 안 됨";
                }
                final String e2 = err;
                h.post(new Runnable() {
                    public void run() {
                        if (e2 != null) status.setText("가져오기 실패: " + e2);
                        else if (found.isEmpty()) status.setText("USB의 VES/packs 안에 소리 팩 폴더(pack.txt 포함)가 없음");
                        else chooseSlot(found.toArray(new String[0]));
                    }
                });
            }
        }).start();
    }

    /** the engine sound from a list (built-in packs, then the user slots: cycling through them took many taps) */
    private void chooseSound() {
        final String[] values = Actions.SOUNDS;
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            String n = packNames.get(values[i]);
            labels[i] = Actions.SOUND_NAMES[i] + (!Actions.isUserSlot(values[i]) ? "" : n == null || n.equals("-") ? " (비어 있음)" : " (" + n + ")");
        }
        new AlertDialog.Builder(this).setTitle("엔진 소리").setItems(labels, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, final int which) {
                act(new Op() {
                    public void run() throws Exception {
                        Actions.set(Prefs.SOUND, values[which]);
                    }
                });
            }
        }).show();
    }

    private void chooseSlot(final String[] folders) {
        new AlertDialog.Builder(this).setTitle("가져올 소리 팩").setItems(folders, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, final int which) {
                final String[] slots = Actions.USER_SLOTS;
                String[] labels = new String[slots.length];
                for (int i = 0; i < slots.length; i++) {
                    String n = packNames.get(slots[i]);
                    labels[i] = "사용자 " + (i + 1) + (n == null || n.equals("-") ? " (비어 있음)" : " (" + n + " 덮어씀)");
                }
                new AlertDialog.Builder(MainActivity.this).setTitle("넣을 칸").setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d2, final int slot) {
                        status.setText("가져오는 중…");
                        new Thread(new Runnable() {
                            public void run() {
                                String r;
                                try {
                                    r = VesLink.send("PACK IMPORT " + slots[slot] + " " + folders[which], 30000);
                                } catch (IOException e) {
                                    r = "error 서비스 연결 안 됨";
                                }
                                final String res = r;
                                h.post(new Runnable() {
                                    public void run() {
                                        status.setText(res.startsWith("ok") ? "가져옴: " + res.substring(3).trim()
                                                : "가져오기 실패: " + res.substring(Math.min(6, res.length())).trim());
                                        refresh();
                                    }
                                });
                            }
                        }).start();
                    }
                }).show();
            }
        }).show();
    }

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
        String snd = o.optString(Prefs.SOUND);
        String pn = packNames.get(snd);
        sound.setText("엔진 소리: " + Actions.name(snd, Actions.SOUNDS, Actions.SOUND_NAMES)
                + (!Actions.isUserSlot(snd) ? "" : pn == null || pn.equals("-") ? " (비어 있음: VEX 4기통이 나옴)" : " (" + pn + ")"));
        profile.setText("회전수: " + Actions.name(o.optString(Prefs.PROFILE), Actions.PROFILES, Actions.PROFILE_NAMES));
        behavior.setText("동작: " + Actions.name(o.optString(Prefs.BEHAVIOR), Actions.BEHAVIORS, Actions.BEHAVIOR_NAMES));
        pops.setText("배기 팝: " + Actions.name(o.optString(Prefs.POPS), Actions.POPS, Actions.POPS_NAMES));
        prompts.setText("안내 음성 때: " + Actions.name(o.optString(Prefs.PROMPTS), Actions.PROMPTS, Actions.PROMPTS_NAMES));
        mode.setText("예전 방식 동작: " + Actions.name(o.optString(Prefs.MODE), Actions.MODES, Actions.MODE_NAMES));
        mode.setEnabled("classic".equals(o.optString(Prefs.BEHAVIOR)));
        idle.setText("공회전 크기 " + o.optString(Prefs.IDLE_LEVEL) + "%");
        regen.setText("회생음 크기 " + o.optString(Prefs.REGEN_LEVEL) + "%");
        response.setText("반응: " + Actions.name(o.optString(Prefs.RESPONSE), Actions.RESPONSE, Actions.RESPONSE_NAMES));
        String f = o.optString(Prefs.FADE_ABOVE);
        fade.setText("0".equals(f) ? "고속 줄임: 끔" : "고속 줄임: " + f + " km/h부터");
        speedBoost.setText("고속 보강: " + Actions.name(o.optString(Prefs.SPEED_BOOST), Actions.SPEED_BOOST, Actions.SPEED_BOOST_NAMES));
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
