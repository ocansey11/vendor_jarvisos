package com.jarvisos.tictactoe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.ResultReceiver;

/**
 * Base class for a Jarvis tool, as in the alarm demo (apps/JarvisAlarmDemo).
 * Jarvis sends a com.jarvisos.TOOL broadcast to the tool's receiver with each
 * argument as a string extra ("com.jarvisos.tool.arg.NAME") and a
 * ResultReceiver to answer on. Only the system can send that broadcast.
 *
 * Replies are text for the model. A failure starts with "Error:", which is
 * how Jarvis tells a refused move from a played one during a turn.
 */
abstract class JarvisTool extends BroadcastReceiver {
    /** Protected broadcast: only the system may send it. */
    private static final String ACTION_TOOL = "com.jarvisos.TOOL";
    private static final String EXTRA_RESULT_RECEIVER = "com.jarvisos.tool.result_receiver";
    private static final String EXTRA_RESULT = "com.jarvisos.tool.result";
    private static final String ARG_PREFIX = "com.jarvisos.tool.arg.";
    private static final int RESULT_OK = 0;
    private static final int RESULT_ERROR = 1;

    /** Does the work (on the main thread) and returns the reply. Throw to report an error. */
    abstract String run(Context context, Intent intent) throws Exception;

    @Override
    public final void onReceive(Context context, Intent intent) {
        // The receiver is exported, so any app can aim an explicit broadcast
        // at it; only with this action is the sender known to be the system.
        if (!ACTION_TOOL.equals(intent.getAction())) return;
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

    static int intArg(Intent intent, String name, int min, int max) {
        String raw = intent.getStringExtra(ARG_PREFIX + name);
        if (raw == null) throw new IllegalArgumentException("missing argument '" + name + "'");
        int value;
        try {
            value = (int) Double.parseDouble(raw.trim());   // models sometimes write 4.0
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
