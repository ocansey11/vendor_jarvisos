package com.jarvisos.tictactoe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;

/**
 * The screen: a 3x3 board, Jarvis's speech bubble, "Jarvis is thinking…",
 * the Play with Jarvis button and the evaluate_moves switch. Everything it
 * shows comes from {@link Game}; nothing here talks to Jarvis.
 */
public class GameActivity extends Activity implements Game.Screen {

    /** Set by the start tool: start a game with Jarvis once on screen. */
    static final String EXTRA_START_WITH_JARVIS = "com.jarvisos.tictactoe.START_WITH_JARVIS";
    /** For tests: set the evaluate_moves switch on launch (am start --ez evaluate_moves false). */
    static final String EXTRA_EVALUATE_MOVES = "evaluate_moves";

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Button[] mCells = new Button[9];
    private Game mGame;
    private TextView mBubble;
    private TextView mStatus;
    private View mThinkingRow;
    private TextView mThinking;
    private CompoundButton mEvaluateSwitch;

    /** Counts the seconds while Jarvis thinks; a turn takes about 20 s on the emulator. */
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            if (mGame.thinking()) {
                long seconds = (System.currentTimeMillis() - mGame.thinkingSince()) / 1000;
                mThinking.setText(getString(R.string.thinking, seconds));
                mMain.postDelayed(this, 1000);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game);
        mGame = Game.get(this);
        int[] ids = {R.id.cell0, R.id.cell1, R.id.cell2, R.id.cell3, R.id.cell4,
                R.id.cell5, R.id.cell6, R.id.cell7, R.id.cell8};
        for (int i = 0; i < 9; i++) {
            final int cell = i;
            mCells[i] = findViewById(ids[i]);
            mCells[i].setOnClickListener(v -> mGame.playerMoved(cell));
        }
        mBubble = findViewById(R.id.bubble);
        mStatus = findViewById(R.id.status);
        mThinkingRow = findViewById(R.id.thinking_row);
        mThinking = findViewById(R.id.thinking);
        mEvaluateSwitch = findViewById(R.id.evaluate_switch);
        mEvaluateSwitch.setOnCheckedChangeListener((b, on) -> mGame.setEvaluateMovesOn(on));
        findViewById(R.id.play).setOnClickListener(v -> mGame.startWithJarvis());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mGame.setScreen(this);
        Intent intent = getIntent();
        if (intent.hasExtra(EXTRA_EVALUATE_MOVES)) {
            mGame.setEvaluateMovesOn(intent.getBooleanExtra(EXTRA_EVALUATE_MOVES, true));
            intent.removeExtra(EXTRA_EVALUATE_MOVES);
        }
        // AX: opened by Jarvis's start tool. The session starts here, when the
        // game is on screen and ready, not in the tool: Jarvis waits for it.
        if (intent.getBooleanExtra(EXTRA_START_WITH_JARVIS, false)) {
            intent.removeExtra(EXTRA_START_WITH_JARVIS);
            mGame.startWithJarvis();
        }
        onGameChanged();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // AX: a session lives only while the app is on screen.
        mGame.onScreenStopped();
        mGame.setScreen(null);
    }

    @Override
    public void onGameChanged() {
        Board board = mGame.board();
        int[] line = board.winningLine();
        boolean playersTurn = mGame.turn() == Game.Turn.PLAYER;
        for (int i = 0; i < 9; i++) {
            char mark = board.at(i);
            mCells[i].setText(mark == Board.EMPTY ? "" : String.valueOf(mark));
            mCells[i].setEnabled(playersTurn && mark == Board.EMPTY);
            mCells[i].setActivated(line != null && (line[0] == i || line[1] == i || line[2] == i));
        }
        String bubble = mGame.bubble();
        mBubble.setVisibility(bubble != null ? View.VISIBLE : View.INVISIBLE);
        mBubble.setText(bubble != null ? bubble : "");
        mStatus.setText(mGame.status());
        mThinkingRow.setVisibility(mGame.thinking() ? View.VISIBLE : View.GONE);
        mMain.removeCallbacks(mTick);
        if (mGame.thinking()) mTick.run();
        if (mEvaluateSwitch.isChecked() != mGame.evaluateMovesOn()) {
            mEvaluateSwitch.setChecked(mGame.evaluateMovesOn());
        }
        // The switch applies to the next game: a session's tools are fixed when it starts.
        mEvaluateSwitch.setEnabled(mGame.turn() == Game.Turn.OVER);
    }
}
