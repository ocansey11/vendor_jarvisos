package com.jarvisos.tictactoe;

import android.content.Context;
import android.content.Intent;

/** get_state, a session tool: the board, whose turn it is, the legal cells. */
public class GetStateTool extends JarvisTool {
    @Override
    String run(Context context, Intent intent) {
        return Game.get(context).state().toString();
    }
}
