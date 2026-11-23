package ves.app;

import android.app.Activity;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.os.Handler;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import ves.gauge.SplitLayout;

/**
 * Split screen (0.2.13): the car's split is a floating panel over the right-hand part of the screen and the window
 * manager resizes nothing, so a screen narrows its own content like the stock apps do: while Settings.System
 * "split_screen_status" is 1 the content is SplitLayout.contentWidth px wide at the left, else full width. Wrap the
 * content in a container (wrap(), read once there so there is no full-width flash), call resume() / pause() from
 * onResume / onPause: an observer on the setting re-applies the width while the screen is shown.
 */
final class Split {
    static final String KEY = "split_screen_status";
    private final Activity activity;
    private final View content;
    private final ContentObserver observer;
    private boolean registered;

    private Split(Activity a, View content) {
        this.activity = a;
        this.content = content;
        observer = new ContentObserver(new Handler()) {
            @Override
            public void onChange(boolean selfChange) {
                apply();
            }
        };
    }

    /** puts content in a FrameLayout of the given background colour at the current width; setContentView(s.root()) */
    static Split wrap(Activity a, View content, int background) {
        FrameLayout box = new FrameLayout(a);
        box.setBackgroundColor(background);
        box.addView(content);
        Split s = new Split(a, content);
        s.apply();
        return s;
    }

    View root() {
        return (View) content.getParent();
    }

    static int status(ContentResolver cr) {
        return Settings.System.getInt(cr, KEY, 0);
    }

    void apply() {
        int screenW = activity.getResources().getDisplayMetrics().widthPixels;
        int w = SplitLayout.contentWidth(status(activity.getContentResolver()), screenW);
        content.setLayoutParams(new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT | Gravity.TOP));
        content.requestLayout();
    }

    void resume() {
        if (!registered) {
            activity.getContentResolver().registerContentObserver(Settings.System.getUriFor(KEY), false, observer);
            registered = true;
        }
        apply(); // it may have changed while the screen was away
    }

    void pause() {
        if (registered) {
            activity.getContentResolver().unregisterContentObserver(observer);
            registered = false;
        }
    }
}
