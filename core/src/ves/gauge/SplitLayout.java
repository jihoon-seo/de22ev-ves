package ves.gauge;

/**
 * Split screen (0.2.13): the car's split is a floating panel over the right 640 px and the window manager resizes
 * nothing, so apps narrow their own content like the stock ones (ev3 MainActivity): width 1281 px, gravity LEFT|TOP,
 * while Settings.System "split_screen_status" is 1; full width otherwise.
 */
public final class SplitLayout {
    public static final int SPLIT_WIDTH = 1281;

    private SplitLayout() {}

    /** the content width in px for a split_screen_status value (only 1 means split) and the screen width */
    public static int contentWidth(int status, int screenW) {
        return status == 1 ? Math.min(SPLIT_WIDTH, screenW) : screenW;
    }
}
