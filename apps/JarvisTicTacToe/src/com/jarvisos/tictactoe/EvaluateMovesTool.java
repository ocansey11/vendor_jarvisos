package com.jarvisos.tictactoe;

import android.content.Context;
import android.content.Intent;

/**
 * evaluate_moves, a session tool: for each legal cell, what it leads to with
 * best play after it (a full minimax search, see Board.evaluate). The tool
 * does the calculation; the model only has to pick. The game's switch turns
 * it off for the with/without comparison.
 */
public class EvaluateMovesTool extends JarvisTool {
    @Override
    String run(Context context, Intent intent) {
        return Game.get(context).evaluateMoves().toString();
    }
}
