package com.jarvisos.demo.alarms;

import android.content.Context;
import android.content.Intent;

public class DeleteAlarmTool extends JarvisTool {
    @Override
    String run(Context context, Intent intent) {
        int hour = intArg(intent, "hour", 0, 23);
        int minute = intArg(intent, "minute", 0, 59);
        String time = AlarmStore.key(hour, minute);
        return new AlarmStore(context).remove(hour, minute)
                ? "Alarm for " + time + " deleted."
                : "There is no alarm for " + time + ". Nothing was changed.";
    }
}
