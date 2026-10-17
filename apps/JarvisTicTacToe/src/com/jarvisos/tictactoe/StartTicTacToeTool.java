package com.jarvisos.tictactoe;

import android.content.Context;
import android.content.Intent;

/**
 * start_tic_tac_toe, the start tool (named in jarvis_session.xml): how Jarvis
 * finds this app when the person says "let's play tic-tac-toe". It only opens
 * the game; the game starts the session itself once it is on screen, and
 * Jarvis waits for that (AGENT_EXPERIENCE.md 11.6). Jarvis lets this receiver
 * start an activity even though the app is in the background.
 */
public class StartTicTacToeTool extends JarvisTool {
    @Override
    String run(Context context, Intent intent) {
        context.startActivity(new Intent(context, GameActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(GameActivity.EXTRA_START_WITH_JARVIS, true));
        return "Opened Tic-tac-toe with a new game against you, with the mark the player last chose.";
    }
}
