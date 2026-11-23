package ves.gauge;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * The gauge's clock text (0.2.13, owner): "yyyy.MM.dd. (요일) HH:mm:ss", e.g. "2026.11.23. (월) 14:05:09". The
 * weekday comes from a fixed Korean table, never from the default Locale (the head unit may run in English).
 */
public final class Clock {
    /** TextClock's format for the panel page (its (E) follows the system locale; format() below does not) */
    public static final String PATTERN = "yyyy.MM.dd. (E) HH:mm:ss";
    /** Calendar.DAY_OF_WEEK - 1: SUNDAY .. SATURDAY */
    static final String[] WEEK = {"일", "월", "화", "수", "목", "금", "토"};

    private Clock() {}

    public static String format(Calendar c) {
        StringBuilder b = new StringBuilder(24);
        b.append(c.get(Calendar.YEAR)).append('.');
        two(b, c.get(Calendar.MONTH) + 1).append('.');
        two(b, c.get(Calendar.DAY_OF_MONTH)).append(". (");
        b.append(WEEK[(c.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY + 7) % 7]).append(") ");
        two(b, c.get(Calendar.HOUR_OF_DAY)).append(':');
        two(b, c.get(Calendar.MINUTE)).append(':');
        return two(b, c.get(Calendar.SECOND)).toString();
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
