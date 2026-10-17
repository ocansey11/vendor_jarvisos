package com.jarvisos.tictactoe;

import android.content.Context;
import android.jarvis.JarvisException;
import android.jarvis.JarvisSession;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The game, and its side of the Jarvis session. One per process: the screen
 * (GameActivity) and the tool receivers (Jarvis's moves) both use it.
 *
 * This is the part to copy for another app. Agent Experience (AX) in three
 * steps, see vendor/jarvisos/AGENT_EXPERIENCE.md section 11:
 *
 * <ol>
 *   <li>Declare: res/xml/jarvis_session.xml (rules, persona, session tools,
 *       events) and the tools as receivers in AndroidManifest.xml.
 *   <li>Send events: {@link #startWithJarvis}, {@link #playerMoved}. Events
 *       carry data (the board, the legal cells), never prompt text: Jarvis
 *       writes its own prompt.
 *   <li>Answer tools: MoveTool and the others call back into this class.
 * </ol>
 *
 * Main thread only.
 */
public final class Game implements JarvisSession.Listener {

    private static final String TAG = "TicTacToe";
    /** The name in jarvis_session.xml. */
    static final String SESSION = "tic_tac_toe";
    /** A Qwen3-1.7B turn takes about 20 s on the emulator; past this the game moves for Jarvis. */
    private static final long TURN_TIMEOUT_MS = 120_000;

    /** What the screen shows; it re-reads the game on every change. */
    public interface Screen {
        void onGameChanged();
    }

    public enum Turn { PLAYER, JARVIS, OVER }

    private static Game sInstance;

    private final Context mContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Random mRandom = new Random();
    private Screen mScreen;

    private Board mBoard = new Board();
    private Turn mTurn = Turn.OVER;
    private int mPlayerLastMove = -1;
    private JarvisSession mSession;
    /** Listens to mSession only; callbacks from an older session are dropped. */
    private CurrentSession mListener;
    /** Why Jarvis left a game still running, or null. The game then plays O itself. */
    private String mJarvisLeft;
    /** Jarvis's last line (say), shown in the speech bubble. */
    private String mBubble;
    /** One line about what happened, for the status bar. */
    private String mStatus = "Touch Play with Jarvis to start.";
    private boolean mThinking;
    private long mThinkingSince;
    /** The switch for the talk's experiment: may Jarvis use evaluate_moves? */
    private boolean mEvaluateMovesOn = true;

    private final Runnable mTurnTimeout = () -> {
        if (mTurn == Turn.JARVIS) jarvisGaveUp("no move after " + TURN_TIMEOUT_MS / 1000 + " s");
    };

    public static Game get(Context context) {
        if (sInstance == null) sInstance = new Game(context.getApplicationContext());
        return sInstance;
    }

    private Game(Context context) {
        mContext = context;
    }

    void setScreen(Screen screen) {
        mScreen = screen;
    }

    // -------------------------------------------------------------------------
    // Starting and ending: the session follows the game
    // -------------------------------------------------------------------------

    /**
     * A new game against Jarvis. Called by the "Play with Jarvis" button, and
     * when Jarvis opened the game through its start tool (start_tic_tac_toe).
     * The player (X) moves first.
     */
    void startWithJarvis() {
        endSession(null);
        mBoard = new Board();
        mTurn = Turn.PLAYER;
        mPlayerLastMove = -1;
        mJarvisLeft = null;
        mBubble = null;
        setThinking(false);
        // AX: the app may close session tools it declared, never open others.
        // This is how the evaluate_moves switch reaches Jarvis.
        String[] closed = mEvaluateMovesOn ? null : new String[] {"evaluate_moves"};
        try {
            mListener = new CurrentSession();
            mSession = JarvisSession.start(mContext, SESSION, closed, mListener);
            mStatus = "Your move. You are X, Jarvis is O.";
        } catch (JarvisException e) {
            Log.w(TAG, "No session", e);
            mSession = null;
            mTurn = Turn.OVER;
            mStatus = "Jarvis is not available: " + e.getMessage();
        }
        changed();
    }

    /** The screen left the foreground. Jarvis would end the session anyway; say so first. */
    void onScreenStopped() {
        endSession(null);
        if (mTurn != Turn.OVER) jarvisLeft("the app was left");
        setThinking(false);
    }

    private void endSession(String event) {
        if (mSession == null) return;
        // AX: an optional last event tells Jarvis how it ended.
        mSession.end(event, event != null ? state().toString() : null);
        mSession = null;
        mListener = null;
    }

    // -------------------------------------------------------------------------
    // The player's moves: sent to Jarvis as events
    // -------------------------------------------------------------------------

    void playerMoved(int cell) {
        if (mTurn != Turn.PLAYER || mBoard.at(cell) != Board.EMPTY) return;
        mBoard.play(cell, Board.PLAYER);
        mPlayerLastMove = cell;
        // AX: events are data. Jarvis keeps the last few for its next prompt.
        if (mSession != null) mSession.event("player_moved", json("cell", cell, "board", mBoard));
        if (finishIfOver()) return;
        if (mJarvisLeft != null) {
            gamePlaysForJarvis();
            return;
        }

        mTurn = Turn.JARVIS;
        mStatus = "Jarvis's move.";
        setThinking(true);
        // AX: the turn event (declared in <turn>) wakes Jarvis for one turn.
        // Its "legal" list is what Jarvis limits its move to.
        if (mSession == null || !mSession.event("your_turn", state().toString())) {
            jarvisGaveUp("Jarvis did not take the turn");
        }
        changed();
    }

    // -------------------------------------------------------------------------
    // Jarvis's side: tools (receivers) and the session listener
    // -------------------------------------------------------------------------

    /** The move tool. Refuses anything illegal; the reply goes back to the model. */
    String jarvisMoves(int cell) {
        if (mTurn != Turn.JARVIS) throw new IllegalStateException("it is not your turn");
        mBoard.play(cell, Board.JARVIS);   // throws with the legal cells if taken
        setThinking(false);
        String reply = "You played cell " + cell + ". Board: " + mBoard;
        if (!finishIfOver()) {
            mTurn = Turn.PLAYER;
            mStatus = "Your move.";
            reply += ". The player moves next.";
        } else {
            reply += ". " + mStatus;
        }
        changed();
        return reply;
    }

    /** get_state: what the game looks like now. */
    JSONObject state() {
        try {
            JSONObject state = new JSONObject();
            state.put("board", mBoard.toString());
            state.put("you", String.valueOf(Board.JARVIS));
            state.put("player", String.valueOf(Board.PLAYER));
            state.put("turn", mTurn == Turn.JARVIS ? "you" : mTurn == Turn.PLAYER ? "player" : "none");
            state.put("legal", new JSONArray(mBoard.legal()));
            if (mPlayerLastMove >= 0) state.put("player_last_move", mPlayerLastMove);
            if (mBoard.isOver()) state.put("winner", winnerName());
            return state;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** evaluate_moves: the tool does the search; the model only chooses. */
    JSONObject evaluateMoves() {
        if (!mEvaluateMovesOn) throw new IllegalStateException("evaluate_moves is switched off");
        try {
            JSONObject cells = new JSONObject();
            for (Map.Entry<Integer, String> e : mBoard.evaluate(Board.JARVIS).entrySet()) {
                cells.put(String.valueOf(e.getKey()), e.getValue());
            }
            return new JSONObject()
                    .put("best", new JSONArray(mBoard.best(Board.JARVIS)))
                    .put("cells", cells);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void onTurnStarted(String event) {
        setThinking(true);
        changed();
    }

    @Override
    public void onSay(String text) {
        mBubble = text;
        changed();
    }

    @Override
    public void onTurnEnded(boolean done, String detail) {
        Log.i(TAG, "Jarvis's turn ended: " + (done ? "moved" : "gave up") + " (" + detail + ")");
        // AX: when Jarvis gives up (no legal call after its retries), the app
        // decides what happens. Here: a random legal move, so play goes on.
        if (!done && mTurn == Turn.JARVIS) jarvisGaveUp(detail);
        setThinking(false);
        changed();
    }

    /**
     * Passes on one session's callbacks while it is the current session.
     * Jarvis tells the app that a session ended after the app has already
     * started the next one (a new game, or the start tool opening the game),
     * and that late onEnded must not end the new game.
     */
    private final class CurrentSession implements JarvisSession.Listener {
        @Override public void onTurnStarted(String event) {
            if (mListener == this) Game.this.onTurnStarted(event);
        }
        @Override public void onSay(String text) {
            if (mListener == this) Game.this.onSay(text);
        }
        @Override public void onTurnEnded(boolean done, String detail) {
            if (mListener == this) Game.this.onTurnEnded(done, detail);
        }
        @Override public void onEnded(String reason) {
            if (mListener == this) Game.this.onEnded(reason);
            else Log.i(TAG, "Ignored the end of an older session: " + reason);
        }
    }

    @Override
    public void onEnded(String reason) {
        Log.i(TAG, "Session ended: " + reason);
        mSession = null;
        mListener = null;
        if (mTurn != Turn.OVER) jarvisLeft(reason);
        setThinking(false);
        changed();
    }

    /**
     * Jarvis left a game that is still running. The game stays playable: the
     * app plays O itself until the next "Play with Jarvis". An app must work
     * without its agent.
     */
    private void jarvisLeft(String reason) {
        mJarvisLeft = reason;
        setThinking(false);
        if (mTurn == Turn.JARVIS) {
            gamePlaysForJarvis();
        } else {
            mStatus = leftStatus();
            changed();
        }
    }

    /** The game's own move for O, from the game tree, once Jarvis has left. */
    private void gamePlaysForJarvis() {
        List<Integer> best = mBoard.best(Board.JARVIS);
        List<Integer> cells = best.isEmpty() ? mBoard.legal() : best;
        if (cells.isEmpty()) return;
        mTurn = Turn.JARVIS;
        jarvisMoves(cells.get(0));
        if (mTurn != Turn.OVER) mStatus = leftStatus();
        changed();
    }

    private String leftStatus() {
        return "Your move. Jarvis left (" + mJarvisLeft + "), so the game plays O."
                + " Touch Play with Jarvis for a new game.";
    }

    private void jarvisGaveUp(String why) {
        List<Integer> legal = mBoard.legal();
        if (legal.isEmpty()) return;
        int cell = legal.get(mRandom.nextInt(legal.size()));
        Log.i(TAG, "Random move " + cell + " for Jarvis: " + why);
        mTurn = Turn.JARVIS;
        jarvisMoves(cell);
        mBubble = "(Jarvis got stuck, so the game played cell " + cell + " for it.)";
        changed();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean finishIfOver() {
        if (!mBoard.isOver()) return false;
        mTurn = Turn.OVER;
        char winner = mBoard.winner();
        mStatus = winner == Board.PLAYER ? "You win!" : winner == Board.JARVIS ? "Jarvis wins." : "Draw.";
        setThinking(false);
        endSession("game_over");
        return true;
    }

    private String winnerName() {
        char winner = mBoard.winner();
        return winner == Board.PLAYER ? "player" : winner == Board.JARVIS ? "you" : "draw";
    }

    private void setThinking(boolean thinking) {
        if (thinking && !mThinking) {
            mThinkingSince = System.currentTimeMillis();
            mMain.removeCallbacks(mTurnTimeout);
            mMain.postDelayed(mTurnTimeout, TURN_TIMEOUT_MS);
        } else if (!thinking) {
            mMain.removeCallbacks(mTurnTimeout);
        }
        mThinking = thinking;
    }

    private void changed() {
        if (mScreen != null) mScreen.onGameChanged();
    }

    private static String json(Object... pairs) {
        try {
            JSONObject o = new JSONObject();
            for (int i = 0; i < pairs.length; i += 2) {
                Object value = pairs[i + 1];
                o.put((String) pairs[i], value instanceof Board ? value.toString() : value);
            }
            return o.toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    // Read by the screen.
    Board board() { return mBoard; }
    Turn turn() { return mTurn; }
    String bubble() { return mBubble; }
    String status() { return mStatus; }
    boolean thinking() { return mThinking; }
    long thinkingSince() { return mThinkingSince; }
    boolean sessionOpen() { return mSession != null && mSession.isOpen(); }
    boolean evaluateMovesOn() { return mEvaluateMovesOn; }
    void setEvaluateMovesOn(boolean on) { mEvaluateMovesOn = on; changed(); }
}
