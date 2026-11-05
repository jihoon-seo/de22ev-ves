package ves.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import ves.core.ProbeResume;
import ves.hmg.VesLink;

/**
 * The guided car check: each step says what to do, may run a vesd probe command
 * (a short tone, the engine sound held on, the drive recording ...), and records
 * the owner's answer. Results land on the USB stick (VES/probe-<time>/).
 * Every step except the drive recording is done parked. The current step is kept, so leaving the
 * screen (to start the radio, Bluetooth music ...) and coming back continues where it was.
 */
public final class ProbeActivity extends Activity {
    static final class Step {
        final String id, text, command, button;
        final String[] answers;

        Step(String id, String text, String button, String command, String... answers) {
            this.id = id;
            this.text = text;
            this.button = button;
            this.command = command;
            this.answers = answers;
        }
    }

    static final String[] HEAR = { "들림, 음악 그대로", "들림, 음악이 작아짐", "들림, 음악이 끊기거나 바뀜", "안 들림" };
    static final String[] PROMPT = { "안내 잘 들림, 엔진 소리 멈춤", "안내 들림, 엔진 소리와 겹침", "안내 안 들림/끊김", "해당 없음" };

    private final List<Step> steps = new ArrayList<Step>();
    private final Handler h = new Handler();
    private int at;
    private TextView title, body, result;
    private LinearLayout buttons;
    private boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        build();
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 20, 32, 20);
        sv.addView(root);
        sv.setBackgroundColor(Color.rgb(16, 18, 22));
        title = MainActivity.text(root, "", 24, Color.WHITE);
        body = MainActivity.text(root, "", 21, Color.rgb(220, 220, 220));
        result = MainActivity.text(root, "", 16, Color.rgb(150, 200, 255));
        buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.VERTICAL);
        root.addView(buttons);
        setContentView(sv);
        at = savedStep();
        show();
    }

    private boolean resumed;

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (busy) return;
        at = savedStep();
        result.setText("");
        show();
        // back from another screen (voice recognition, the radio ...): a hold may still be running in vesd,
        // or may have finished while this screen was away (car check 2)
        final String id = steps.get(at).id;
        final String saved = prefs().getString("result_" + id, null);
        new Thread(new Runnable() {
            public void run() {
                String st;
                try {
                    st = VesLink.send("STATUS", 3000);
                } catch (IOException e) {
                    st = null;
                }
                final int what = ProbeResume.decide(id, st, saved);
                h.post(new Runnable() {
                    public void run() {
                        if (!resumed || busy || !steps.get(at).id.equals(id)) return;
                        if (what == ProbeResume.REATTACH) reattach();
                        else if (what == ProbeResume.ADVANCE) finishStep(steps.get(at), saved);
                    }
                });
            }
        }).start();
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    /** wait for a hold that vesd is still running (started before the screen was covered) */
    private void reattach() {
        busy = true;
        final Step s = steps.get(at);
        result.setText("실행 중… (이어서 기다림)");
        new Thread(new Runnable() {
            public void run() {
                final String r = waitIdle("ok", s.id.equals("hold") ? 70 : 40);
                saveResult(s.id, r);
                h.post(new Runnable() {
                    public void run() {
                        busy = false;
                        if (resumed) finishStep(s, r);
                    }
                });
            }
        }).start();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("probe", MODE_PRIVATE);
    }

    /** the step to continue from: the saved one, or the first */
    private int savedStep() {
        String id = prefs().getString("step", null);
        if (id == null) return 0;
        for (int i = 0; i < steps.size(); i++) if (steps.get(i).id.equals(id)) return i;
        return 0;
    }

    private void saveStep() {
        String id = steps.get(at).id;
        SharedPreferences.Editor e = prefs().edit();
        if (id.equals("start") || id.equals("done")) e.remove("step");
        else e.putString("step", id);
        e.commit();
    }

    private void build() {
        add("start", "주차(P) 상태, Ready 켜짐, USB를 꽂은 상태에서 시작합니다. 결과는 USB 최상위의 VES 폴더에 저장됩니다(폴더가 미리 있어야 함).",
                "점검 시작", "PROBE BEGIN");
        add("info", "헤드유닛의 오디오 서비스와 차량 신호를 확인합니다 (5초, 소리 없음).", "확인", "PROBE INFO");
        String[][] sources = { { "fm", "라디오(FM)" }, { "bt", "블루투스 음악" }, { "aa", "Android Auto 음악" } };
        String[][] routes = {
                // 0.1.1's check settled the rest: 0x39 took the music source over, 0x3 reported a
                // navigation prompt, no channel / the music stream weren't heard
                { "r1", "12 0x45", "1번 (내비 효과음 채널)" },
                { "r2", "12 0x46", "2번 (시스템 알림 채널)" } };
        for (String[] s : sources) {
            add("src_" + s[0], s[1] + "을(를) 보통 볼륨으로 재생하세요. 재생되면 다음으로.", "재생 중, 다음", null);
            for (String[] r : routes) {
                add("tone_" + s[0] + "_" + r[0], s[1] + " 재생 중 시험음 " + r[2] + ": 버튼을 누르고 3초 동안 들어 보세요.",
                        "시험음 재생", "PROBE TONE " + r[1] + " 3000 " + s[0] + "_" + r[0], HEAR);
            }
        }
        add("knob", "시험음 1번을 8초 동안 냅니다. 그동안 볼륨 노브를 조금 돌려 보세요.", "시험음 재생 (8초)",
                "PROBE TONE 12 0x45 8000 knob", "시험음 크기가 바뀜", "음악 크기만 바뀜", "아무것도 안 바뀜", "모르겠음");
        add("navi_route", "순정 내비로 가까운 목적지 안내를 시작하세요 (안내 음성이 나오도록). Android Auto도 연결해 두면 좋습니다.",
                "준비됨, 다음", null);
        add("hold", "엔진 소리를 60초 동안 냅니다 (볼륨 30). 그동안 ① 순정 내비 안내 음성 다시 듣기 ② Android Auto 내비 안내 ③ 음성인식 버튼을 차례로 해 보세요.",
                "엔진 소리 60초", "PROBE HOLD 0x45 60 30");
        add("hold_navi", "① 순정 내비 안내 음성은 어땠나요?", null, null, PROMPT);
        add("hold_aa", "② Android Auto 내비 안내는 어땠나요?", null, null, PROMPT);
        add("hold_vr", "③ 음성인식은 어땠나요?", null, null, PROMPT);
        add("hold_resume", "안내가 끝나고 엔진 소리가 다시 나왔나요?", null, null, "다시 나옴", "안 나옴", "모르겠음");
        add("stuck", "고장 시험: 엔진 소리 채널을 연 채로 서비스를 강제 종료합니다. 몇 초 뒤 서비스가 다시 뜹니다.",
                "강제 종료 시험", "PROBE STUCK 0x45");
        add("stuck_prompt", "지금 순정 내비 안내 음성을 한 번 나오게 하세요. 잘 들렸나요?", null, null, "잘 들림", "작게/이상하게 들림", "안 들림");
        add("stuck_clean", "채널을 정리합니다.", "정리", "PROBE CLEAN 0x45");
        add("stuck_prompt2", "다시 안내 음성을 한 번 나오게 하세요. 잘 들렸나요?", null, null, "잘 들림", "작게/이상하게 들림", "안 들림");
        add("chime", "엔진 소리를 최대(볼륨 100)로 30초 냅니다. 그동안 운전석 문을 열거나 안전벨트를 풀어 경고음을 내 보세요.",
                "엔진 소리 최대 30초", "PROBE HOLD 0x45 30 100");
        add("chime_q", "경고음이 잘 들렸나요?", null, null, "잘 들림", "엔진 소리에 묻힘", "경고음 안 남");
        add("ui_launcher", "홈 화면 전체 메뉴에 '엔진 사운드' 타일이 있나요?", null, null, "있음", "없음");
        add("ui_star", "설정 > 버튼 설정의 ★ 버튼 목록에 '엔진 사운드 켜기/끄기'가 있나요?", null, null, "있음", "없음");
        add("ui_panel", "분할 화면 설정에서 '엔진 사운드' 위젯을 고를 수 있나요?", null, null, "있음", "없음", "분할 화면 없음");
        add("drive", "주행 기록: 버튼을 누르고 출발하세요 (최대 15분, 실제 설정으로 엔진 소리 남). 주행 중에는 화면을 만지지 마세요. 정차했을 때 드라이브 모드와 회생제동 단계를 한 번씩 바꿔 보세요. 주차 후 다음 화면에서 기록을 멈춥니다.",
                "기록 시작", "PROBE DRIVE START 15");
        add("drive_stop", "주차했으면 기록을 멈추세요.", "기록 중지", "PROBE DRIVE STOP");
        add("drive_feel", "주행 중 엔진 소리는?", null, null, "적당함", "너무 큼", "너무 작음", "거의 안 들림");
        add("drive_lag", "가속 페달에 대한 반응은?", null, null, "바로 따라옴", "조금 늦음", "많이 늦음");
        add("drive_glitch", "소리가 끊기거나 튀었나요? 화면이 버벅였나요?", null, null, "없음", "소리 끊김/튐", "화면 버벅임", "둘 다");
        add("end", "점검을 끝내고 로그를 USB에 저장합니다.", "끝내기", "PROBE END");
        add("done", "끝났습니다. USB의 VES/probe-… 폴더를 보내 주세요.", null, null);
    }

    private void add(String id, String text, String button, String command, String... answers) {
        steps.add(new Step(id, text, button, command, answers));
    }

    private void show() {
        Step s = steps.get(at);
        title.setText("차량 점검 " + (at + 1) + "/" + steps.size());
        body.setText(s.text);
        buttons.removeAllViews();
        if (s.command != null) {
            Button run = MainActivity.big(buttons, s.button, 0);
            run.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    runCommand();
                }
            });
        } else if (s.answers.length == 0 && s.button != null) {
            Button next = MainActivity.big(buttons, s.button, 0);
            next.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    note("done");
                    next();
                }
            });
        }
        for (final String a : s.answers) {
            Button ans = MainActivity.big(buttons, a, 0);
            ans.setTextSize(20);
            ans.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    note(a);
                    next();
                }
            });
        }
        if (s.command != null && s.answers.length > 0) setAnswersEnabled(false);
        if (at > 1) {
            Button again = MainActivity.big(buttons, "처음부터 다시", 0);
            again.setTextSize(16);
            again.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (busy) return;
                    at = 0;
                    saveStep();
                    result.setText("");
                    show();
                }
            });
        }
        if (at < steps.size() - 1 && !s.id.equals("start")) {
            Button skip = MainActivity.big(buttons, "건너뛰기", 0);
            skip.setTextSize(16);
            skip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    note("skipped");
                    next();
                }
            });
        }
    }

    private void setAnswersEnabled(boolean on) {
        Step s = steps.get(at);
        for (int i = 0; i < buttons.getChildCount(); i++) {
            View v = buttons.getChildAt(i);
            if (v instanceof Button) {
                String t = ((Button) v).getText().toString();
                for (String a : s.answers) if (a.equals(t)) v.setEnabled(on);
            }
        }
    }

    private void runCommand() {
        if (busy) return;
        busy = true;
        final Step s = steps.get(at);
        result.setText("실행 중…");
        new Thread(new Runnable() {
            public void run() {
                String r;
                try {
                    r = VesLink.send(s.command, 30000);
                    if (s.id.equals("hold") || s.id.equals("chime")) r = waitIdle(r, s.id.equals("hold") ? 70 : 40);
                    if (s.id.equals("stuck")) r = waitRestart(r);
                } catch (IOException e) {
                    r = "error " + e.getMessage();
                }
                final String res = r;
                saveResult(s.id, res); // for the screen that is in front when this ends (may be a new one)
                h.post(new Runnable() {
                    public void run() {
                        busy = false;
                        if (resumed) finishStep(s, res);
                    }
                });
            }
        }).start();
    }

    private void saveResult(String id, String r) {
        prefs().edit().putString("result_" + id, r).commit();
    }

    /** a step's command finished: show its result, then its answers or the next step */
    private void finishStep(Step s, String res) {
        prefs().edit().remove("result_" + s.id).commit();
        if (steps.get(at) != s) return;
        result.setText(res.length() > 1500 ? res.substring(0, 1500) + "…" : res);
        if (res.startsWith("error")) return;
        if (s.answers.length > 0) setAnswersEnabled(true);
        else next();
    }

    /** a hold runs in vesd: wait until it says it's idle again */
    static String waitIdle(String first, int maxSec) {
        long t0 = SystemClock.uptimeMillis();
        while (SystemClock.uptimeMillis() - t0 < maxSec * 1000L) {
            SystemClock.sleep(1000);
            try {
                String st = VesLink.send("STATUS", 3000);
                if (st.contains("probe open") && !st.contains("running hold")) return first + "\n(끝)";
            } catch (IOException ignored) {
            }
        }
        return first;
    }

    /** after the kill, wait for HMG-API to bring vesd back */
    static String waitRestart(String first) {
        SystemClock.sleep(1500);
        for (int i = 0; i < 60; i++) {
            try {
                VesLink.send("STATUS", 2000);
                return first + "\n서비스가 다시 시작됨";
            } catch (IOException e) {
                SystemClock.sleep(1000);
            }
        }
        return "error 서비스가 60초 안에 다시 시작되지 않음";
    }

    private void note(final String answer) {
        final String id = steps.get(at).id;
        new Thread(new Runnable() {
            public void run() {
                try {
                    VesLink.send("PROBE NOTE step=" + id + " answer=" + answer, 3000);
                } catch (IOException ignored) {
                }
            }
        }).start();
    }

    private void next() {
        if (at < steps.size() - 1) at++;
        saveStep();
        result.setText("");
        show();
    }
}
