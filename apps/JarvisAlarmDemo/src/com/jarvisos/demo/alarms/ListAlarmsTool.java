package com.jarvisos.demo.alarms;

import android.content.Context;
import android.content.Intent;

import java.util.Map;

public class ListAlarmsTool extends JarvisTool {
    @Override
    String run(Context context, Intent intent) {
        Map<String, String> alarms = new AlarmStore(context).all();
        if (alarms.isEmpty()) return "No alarms are set.";
        StringBuilder sb = new StringBuilder(alarms.size() + " alarm(s) set:");
        for (Map.Entry<String, String> e : alarms.entrySet()) {
            sb.append(' ').append(e.getKey());
            if (!e.getValue().isEmpty()) sb.append(" (").append(e.getValue()).append(')');
            sb.append(';');
        }
        return sb.toString();
    }
}
