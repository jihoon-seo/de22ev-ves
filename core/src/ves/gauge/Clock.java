package ves.gauge;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * The gauge's clock text (0.2.14, owner): "M월 d일 (요일) HH:mm", e.g. "11월 23일 (월) 14:05" (month and day unpadded,
 * 24-hour time padded). The weekday comes from a fixed Korean table, never from the default Locale (the head unit may
 * run in English).
 */
public final class Clock {
    /** the same as a SimpleDateFormat / TextClock pattern (its (E) follows the system locale; format() below does not) */
    public static final String PATTERN = "M월 d일 (E) HH:mm";
    /** Calendar.DAY_OF_WEEK - 1: SUNDAY .. SATURDAY */
    static final String[] WEEK = {"일", "월", "화", "수", "목", "금", "토"};

    private Clock() {}

    public static String format(Calendar c) {
        StringBuilder b = new StringBuilder(16);
        b.append(c.get(Calendar.MONTH) + 1).append("월 ");
        b.append(c.get(Calendar.DAY_OF_MONTH)).append("일 (");
        b.append(WEEK[(c.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY + 7) % 7]).append(") ");
        two(b, c.get(Calendar.HOUR_OF_DAY)).append(':');
        return two(b, c.get(Calendar.MINUTE)).toString();
    }

    /** epoch ms in the given zone (TimeZone.getDefault() on the car) */
    public static String format(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(ms);
        return format(c);
    }

    private static StringBuilder two(StringBuilder b, int v) {
        if (v < 10) b.append('0');
        return b.append(v);
    }
}
