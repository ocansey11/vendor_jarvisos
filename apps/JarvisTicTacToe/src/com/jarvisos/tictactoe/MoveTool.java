package com.jarvisos.tictactoe;

import android.content.Context;
import android.content.Intent;

/**
 * move(cell), the session's turn tool: a successful call ends Jarvis's turn.
 * A taken cell or a move out of turn is refused with the legal cells, so the
 * model can correct itself.
 */
public class MoveTool extends JarvisTool {
    @Override
    String run(Context context, Intent intent) {
        return Game.get(context).jarvisMoves(intArg(intent, "cell", 0, 8));
    }
}
