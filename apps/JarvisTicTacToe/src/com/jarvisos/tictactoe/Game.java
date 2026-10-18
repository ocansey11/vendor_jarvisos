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
 * It is an ordinary game first. The person picks a mark (X moves first) and
 * an opponent: a friend on the same phone, or Jarvis. Jarvis plays only when
 * invited, by "Play with Jarvis" or by asking Jarvis (the start tool), and
 * only on its own turns.
 *
 * This is the part to copy for another app. Agent Experience (AX) in three
 * steps, see vendor/jarvisos/AGENT_EXPERIENCE.md section 11:
 *
 * <ol>
 *   <li>Declare: res/xml/jarvis_session.xml (rules, persona, session tools,
 *       events) and the tools as receivers in AndroidManifest.xml.
 *   <li>Send events: {@link #play}, {@link #resume}. Events carry data (the
 *       board, the legal cells), never prompt text: Jarvis writes its own
 *       prompt.
 *   <li>Answer tools: MoveTool and the others call back into this class.
 * </ol>
 *
 * Main thread only.
 */
public final class Game implements JarvisSession.Listener {

    private static final String TAG = "TicTacToe";
    /** The name in jarvis_session.xml. */
    static final String SESSION = "tic_tac_toe";
    /**
     * A Qwen3-1.7B turn takes 15 to 50 s on the emulator, and over 2 minutes
     * while the server is busy building (17 Oct 2026). Past this the game
     * moves for Jarvis.
     */
    private static final long TURN_TIMEOUT_MS = 180_000;

    /** What the screen shows; it re-reads the game on every change. */
    public interface Screen {
        void onGameChanged();
    }

    /** Who plays the other mark. NONE: no game yet, the start screen shows. */
    public enum Opponent { NONE, FRIEND, JARVIS }

    private static Game sInstance;

    private final Context mContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Random mRandom = new Random();
    private Screen mScreen;

    private Board mBoard = new Board();
    private Opponent mOpponent = Opponent.NONE;
    /** The mark of the person holding the phone, chosen on the start screen. */
    private char mMine = Board.X;
    /** The mark that goes down next. */
    private char mNext = Board.X;
    private boolean mPaused;
    /** Results since the opponent was chosen: wins for the person's side, the other side, draws. */
    private int mWins, mLosses, mDraws;
    private int mPlayerLastMove = -1;
    private JarvisSession mSession;
    /** Listens to mSession only; callbacks from an older session are dropped. */
    private CurrentSession mListener;
    /** Jarvis's last line (say), shown in the speech bubble. */
    private String mBubble;
    /** One line about what happened, for the status bar. */
    private String mStatus = "";
    private boolean mThinking;
    private long mThinkingSince;
    /** The switch for the talk's experiment: may Jarvis use evaluate_moves? */
    private boolean mEvaluateMovesOn = true;

    private final Runnable mTurnTimeout = this::jarvisTimedOut;

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
    // Starting, pausing and ending: the session follows the game
    // -------------------------------------------------------------------------

    /** The start screen: X moves first, so this is also who starts. */
    void chooseMark(char mark) {
        if (mOpponent != Opponent.NONE) return;
        mMine = mark;
        changed();
    }

    /** "New game": back to the start screen. */
    void newGame() {
        endSession(null);
        mOpponent = Opponent.NONE;
        mBoard = new Board();
        mPaused = false;
        mBubble = null;
        setThinking(false);
        changed();
    }

    /** "Play with a friend": two people take turns on this phone. No Jarvis. */
    void startWithFriend() {
        endSession(null);
        clearScore(Opponent.FRIEND);
        reset(Opponent.FRIEND);
        mStatus = "X to move.";
        changed();
    }

    /**
     * "Play with Jarvis", or Jarvis opened the game through its start tool
     * (start_tic_tac_toe): both are the person inviting Jarvis. Jarvis plays
     * the mark the person did not choose.
     */
    void startWithJarvis() {
        endSession(null);
        clearScore(Opponent.JARVIS);
        reset(Opponent.JARVIS);
        if (openSession()) {
            mStatus = mNext == mMine ? "Your move. You are " + mMine + "."
                    : "Jarvis is " + jarvisMark() + " and moves first.";
            if (jarvisToMove()) askJarvis();
        }
        changed();
    }

    /**
     * "Rematch" after a game: the same opponent, the marks swapped, so the
     * start alternates (X moving first is an advantage). The score carries on.
     */
    void rematch() {
        if (!mBoard.isOver()) return;
        mMine = Board.other(mMine);
        if (mOpponent == Opponent.JARVIS) {
            startWithJarvis();
        } else {
            startWithFriend();
        }
    }

    /** "Pause": the board stays. With Jarvis, the session ends until Resume. */
    void pause(String why) {
        if (mOpponent == Opponent.NONE || mPaused || mBoard.isOver()) return;
        endSession(null);
        mPaused = true;
        setThinking(false);
        mStatus = why;
        changed();
    }

    /** "Resume": with Jarvis, a new session on the same board. */
    void resume() {
        if (!mPaused) return;
        mPaused = false;
        if (mOpponent == Opponent.JARVIS && !openSession()) {
            changed();
            return;
        }
        mStatus = mOpponent == Opponent.FRIEND ? mNext + " to move."
                : jarvisToMove() ? "Jarvis's move." : "Your move.";
        // AX: the new session starts from the board as it is. If it is
        // Jarvis's turn, the turn event wakes it as usual.
        if (jarvisToMove()) askJarvis();
        changed();
    }

    /** The screen left the foreground. A session lives only on screen: pause. */
    void onScreenStopped() {
        pause("Paused: the app was left. Touch Resume to carry on.");
    }

    /** A new opponent starts a new score; a rematch keeps it. */
    private void clearScore(Opponent opponent) {
        if (opponent == mOpponent && mBoard.isOver()) return;
        mWins = mLosses = mDraws = 0;
    }

    private void reset(Opponent opponent) {
        mBoard = new Board();
        mNext = Board.X;
        mOpponent = opponent;
        mPaused = false;
        mPlayerLastMove = -1;
        mBubble = null;
        setThinking(false);
    }

    /** Starts the session; on failure the game waits, paused, with the reason. */
    private boolean openSession() {
        // AX: the app may close session tools it declared, never open others.
        // This is how the evaluate_moves switch reaches Jarvis.
        String[] closed = mEvaluateMovesOn ? null : new String[] {"evaluate_moves"};
        try {
            mListener = new CurrentSession();
            mSession = JarvisSession.start(mContext, SESSION, closed, mListener);
            return true;
        } catch (JarvisException e) {
            Log.w(TAG, "No session", e);
            mSession = null;
            mListener = null;
            mPaused = true;
            mStatus = "Jarvis is not available (" + e.getMessage() + ").";
            return false;
        }
    }

    private void endSession(String event) {
        if (mSession == null) return;
        // AX: an optional last event tells Jarvis how it ended.
        mSession.end(event, event != null ? state().toString() : null);
        mSession = null;
        mListener = null;
    }

    // -------------------------------------------------------------------------
    // The person's moves (and the friend's): sent to Jarvis as events
    // -------------------------------------------------------------------------

    /** May the person touch this cell now? Never on Jarvis's turn. */
    boolean canTap(int cell) {
        if (mOpponent == Opponent.NONE || mPaused || mBoard.isOver()) return false;
        if (mBoard.at(cell) != Board.EMPTY) return false;
        return mOpponent == Opponent.FRIEND || mNext == mMine;
    }

    /** A touch on the board. */
    void play(int cell) {
        if (!canTap(cell)) return;
        mBoard.play(cell, mNext);
        if (mOpponent == Opponent.FRIEND) {
            mNext = Board.other(mNext);
            if (!finishIfOver()) mStatus = mNext + " to move.";
            changed();
            return;
        }
        mPlayerLastMove = cell;
        // AX: events are data. Jarvis keeps the last few for its next prompt.
        if (mSession != null) mSession.event("player_moved", json("cell", cell, "board", mBoard));
        mNext = jarvisMark();
        if (finishIfOver()) {
            changed();
            return;
        }
        mStatus = "Jarvis's move.";
        askJarvis();
        changed();
    }

    /**
     * AX: the turn event (declared in &lt;turn&gt;) wakes Jarvis for one turn.
     * Its "legal" list is what Jarvis limits its move to. Jarvis is asked only
     * here, on its own turn, in a game it was invited to.
     */
    private void askJarvis() {
        setThinking(true);
        if (mSession == null || !mSession.event("your_turn", state().toString())) {
            jarvisGaveUp("Jarvis did not take the turn");
        }
    }

    // -------------------------------------------------------------------------
    // Jarvis's side: tools (receivers) and the session listener
    // -------------------------------------------------------------------------

    /** The move tool. Refuses anything illegal; the reply goes back to the model. */
    String jarvisMoves(int cell) {
        if (!jarvisToMove()) throw new IllegalStateException("it is not your turn");
        mBoard.play(cell, jarvisMark());   // throws with the legal cells if taken
        setThinking(false);
        mNext = mMine;
        String reply = "You played cell " + cell + ". Board: " + mBoard;
        if (!finishIfOver()) {
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
            state.put("you", String.valueOf(jarvisMark()));
            state.put("player", String.valueOf(mMine));
            state.put("turn", mBoard.isOver() ? "none" : mNext == mMine ? "player" : "you");
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
            for (Map.Entry<Integer, String> e : mBoard.evaluate(jarvisMark()).entrySet()) {
                cells.put(String.valueOf(e.getKey()), e.getValue());
            }
            return new JSONObject()
                    .put("best", new JSONArray(mBoard.best(jarvisMark())))
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
        if (!done && jarvisToMove()) jarvisGaveUp(detail);
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

    /** Jarvis ended the session itself (it was stopped, or failed): pause, never lose the board. */
    @Override
    public void onEnded(String reason) {
        Log.i(TAG, "Session ended: " + reason);
        mSession = null;
        mListener = null;
        pause("Jarvis left (" + reason + "). Touch Resume to bring it back.");
    }

    private void jarvisGaveUp(String why) {
        int cell = randomMoveForJarvis(why);
        if (cell >= 0) mBubble = "(Jarvis got stuck, so the game played cell " + cell + " for it.)";
        changed();
    }

    /**
     * Jarvis took too long. The app ends the turn the one way it can: it
     * closes the session, which stops the turn inside Jarvis, so a late move
     * cannot arrive. Then it plays a random cell for Jarvis and opens a new
     * session on the same board for the next turn.
     */
    private void jarvisTimedOut() {
        if (!jarvisToMove()) return;
        endSession(null);
        int cell = randomMoveForJarvis("no move after " + TURN_TIMEOUT_MS / 1000 + " s");
        if (cell >= 0) {
            mBubble = "(Jarvis took too long, so the game played cell " + cell + " for it.)";
        }
        if (!mBoard.isOver()) openSession();   // on failure the game pauses and says why
        changed();
    }

    /** @return the cell played, or -1 if there was none */
    private int randomMoveForJarvis(String why) {
        List<Integer> legal = mBoard.legal();
        if (legal.isEmpty()) return -1;
        int cell = legal.get(mRandom.nextInt(legal.size()));
        Log.i(TAG, "Random move " + cell + " for Jarvis: " + why);
        jarvisMoves(cell);
        return cell;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private char jarvisMark() {
        return Board.other(mMine);
    }

    private boolean jarvisToMove() {
        return mOpponent == Opponent.JARVIS && !mPaused && !mBoard.isOver()
                && mNext == jarvisMark();
    }

    private boolean finishIfOver() {
        if (!mBoard.isOver()) return false;
        char winner = mBoard.winner();
        if (winner == Board.EMPTY) mDraws++; else if (winner == mMine) mWins++; else mLosses++;
        if (mOpponent == Opponent.JARVIS) {
            mStatus = winner == mMine ? "You win!" : winner == Board.EMPTY ? "Draw." : "Jarvis wins.";
        } else {
            mStatus = winner == Board.EMPTY ? "Draw." : winner + " wins!";
        }
        setThinking(false);
        endSession("game_over");
        return true;
    }

    private String winnerName() {
        char winner = mBoard.winner();
        return winner == Board.EMPTY ? "draw" : winner == mMine ? "player" : "you";
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
    Opponent opponent() { return mOpponent; }
    char mine() { return mMine; }
    int wins() { return mWins; }
    int losses() { return mLosses; }
    int draws() { return mDraws; }
    boolean paused() { return mPaused; }
    boolean over() { return mBoard.isOver(); }
    String bubble() { return mBubble; }
    String status() { return mStatus; }
    boolean thinking() { return mThinking; }
    long thinkingSince() { return mThinkingSince; }
    boolean sessionOpen() { return mSession != null && mSession.isOpen(); }
    boolean evaluateMovesOn() { return mEvaluateMovesOn; }
    void setEvaluateMovesOn(boolean on) { mEvaluateMovesOn = on; changed(); }
}
