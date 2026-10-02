package com.jarvisos.demo.alarms;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import java.util.Map;

/** Shows the alarm list, so what Jarvis did can be checked by eye. */
public class AlarmListActivity extends Activity {
    @Override
    protected void onResume() {
        super.onResume();
        StringBuilder sb = new StringBuilder("Alarms\n\n");
        for (Map.Entry<String, String> e : new AlarmStore(this).all().entrySet()) {
            sb.append(e.getKey()).append("  ").append(e.getValue()).append('\n');
        }
        TextView view = new TextView(this);
        view.setPadding(48, 96, 48, 48);
        view.setTextSize(20);
        view.setText(sb);
        setContentView(view);
    }
}
