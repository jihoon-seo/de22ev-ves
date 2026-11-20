package ves.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;

/** The full-width gauge screen (0.2.12); a tap goes back to the settings screen. */
public final class GaugeActivity extends Activity {
    private GaugeView gauge;
    private LivePoller poller;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        gauge = new GaugeView(this);
        gauge.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        poller = new LivePoller(gauge);
        setContentView(gauge);
    }

    @Override
    protected void onResume() {
        super.onResume();
        poller.start();
    }

    @Override
    protected void onPause() {
        poller.stop();
        super.onPause();
    }
}
