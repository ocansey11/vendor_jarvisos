package com.jarvisos.demo.alarms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.ResultReceiver;

/**
 * Base class for a Jarvis tool. Jarvis sends a com.jarvisos.TOOL broadcast to
 * the tool's receiver with each argument as a string extra
 * ("com.jarvisos.tool.arg.NAME") and a ResultReceiver to answer on.
 */
abstract class JarvisTool extends BroadcastReceiver {
    private static final String EXTRA_RESULT_RECEIVER = "com.jarvisos.tool.result_receiver";
    private static final String EXTRA_RESULT = "com.jarvisos.tool.result";
    private static final String ARG_PREFIX = "com.jarvisos.tool.arg.";
    private static final int RESULT_OK = 0;
    private static final int RESULT_ERROR = 1;

    /** Does the work and returns the text Jarvis shows the model. Throw to report an error. */
    abstract String run(Context context, Intent intent) throws Exception;

    @Override
    public final void onReceive(Context context, Intent intent) {
        ResultReceiver reply = intent.getParcelableExtra(EXTRA_RESULT_RECEIVER, ResultReceiver.class);
        if (reply == null) return;
        Bundle data = new Bundle();
        try {
            data.putString(EXTRA_RESULT, run(context, intent));
            reply.send(RESULT_OK, data);
        } catch (Exception e) {
            data.putString(EXTRA_RESULT, "Error: " + e.getMessage());
            reply.send(RESULT_ERROR, data);
        }
    }

    static String arg(Intent intent, String name) {
        return intent.getStringExtra(ARG_PREFIX + name);
    }

    static int intArg(Intent intent, String name, int min, int max) {
        String raw = arg(intent, name);
        if (raw == null) throw new IllegalArgumentException("missing argument '" + name + "'");
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + name + "' must be a whole number, got " + raw);
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    "'" + name + "' must be between " + min + " and " + max + ", got " + value);
        }
        return value;
    }
}
