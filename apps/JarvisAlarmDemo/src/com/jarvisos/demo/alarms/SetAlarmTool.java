package com.jarvisos.demo.alarms;

import android.content.Context;
import android.content.Intent;

public class SetAlarmTool extends JarvisTool {
    @Override
    String run(Context context, Intent intent) {
        int hour = intArg(intent, "hour", 0, 23);
        int minute = intArg(intent, "minute", 0, 59);
        String time = AlarmStore.key(hour, minute);
        // Safe to repeat: a second request for the same time changes nothing.
        return new AlarmStore(context).add(hour, minute, arg(intent, "label"))
                ? "Alarm set for " + time + "."
                : "An alarm for " + time + " already exists. Nothing was changed.";
    }
}
