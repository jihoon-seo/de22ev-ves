package ves.core;

/**
 * The app's one short status line (widget, main screen) from vesd's STATUS answer. A frozen audio loop
 * (loop_age_ms) or an open path whose track doesn't play (play_age_ms) comes first: 0.2.3 showed the state it
 * froze in ("주행 중 · 재생", "안내 음성 중 (작게)") for hours.
 */
public final class StatusLabel {
    /** a loop or track quiet this long is not working */
    static final long STALE_MS = 3000;

    private StatusLabel() {
    }

    public static String of(String status) {
        if (status.startsWith("error")) return "서비스 연결 안 됨";
        String s = "", audio = "";
        for (String l : status.split("\n")) {
            if (l.startsWith("state=")) s = l;
            else if (l.startsWith("audio ")) audio = l;
        }
        String open = field(s, "open");
        if (num(field(audio, "loop_age_ms")) > STALE_MS) return "소리 처리 멈춤";
        if ("1".equals(open) && num(field(audio, "play_age_ms")) > STALE_MS) return "소리 출력 안 됨";
        String state = field(s, "state");
        String yield = field(s, "yield");
        if (yield != null && yield.startsWith("1")) return s.contains(" duck=1") ? "안내 음성 중 (작게)" : "안내 음성 중 (잠시 멈춤)";
        if (state == null) return "";
        if (state.equals("not_ready")) return "Ready 아님";
        if (state.equals("park") || state.equals("neutral") || state.equals("reverse") || state.equals("gear_none")) return "P/N/R: 무음";
        if (state.equals("stale")) return "차량 신호 없음";
        if (state.equals("stopped_silent")) return "정차: 무음";
        if (state.equals("accel_only_wait")) return "가속 대기";
        if (state.equals("fade_speed")) return "고속: 줄임";
        if (state.equals("drive")) return "주행 중" + ("1".equals(open) ? " · 재생" : "");
        if (state.equals("idle")) return "공회전";
        if (state.equals("regen")) return "회생제동";
        return state;
    }

    public static String field(String line, String key) {
        for (String f : line.split(" ")) if (f.startsWith(key + "=")) return f.substring(key.length() + 1);
        return null;
    }

    static long num(String v) {
        if (v == null) return -1;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
