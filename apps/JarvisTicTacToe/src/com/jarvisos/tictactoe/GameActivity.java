package com.jarvisos.tictactoe;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;

/**
 * The screen: who O is, a 3x3 board, Jarvis's speech bubble, "Jarvis is
 * thinking…", New game, Play with (or Stop playing with) Jarvis, and the
 * evaluate_moves switch. Everything it
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
    private TextView mOpponent;
    private Button mJarvisButton;

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
        // Android 15 draws apps edge to edge: keep the controls clear of the
        // status and navigation bars.
        findViewById(R.id.root).setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
        mGame = Game.get(this);
        int[] ids = {R.id.cell0, R.id.cell1, R.id.cell2, R.id.cell3, R.id.cell4,
                R.id.cell5, R.id.cell6, R.id.cell7, R.id.cell8};
        for (int i = 0; i < 9; i++) {
            final int cell = i;
            mCells[i] = findViewById(ids[i]);
            mCells[i].setOnClickListener(v -> mGame.cellTapped(cell));
        }
        mBubble = findViewById(R.id.bubble);
        mStatus = findViewById(R.id.status);
        mThinkingRow = findViewById(R.id.thinking_row);
        mThinking = findViewById(R.id.thinking);
        mEvaluateSwitch = findViewById(R.id.evaluate_switch);
        mEvaluateSwitch.setOnCheckedChangeListener((b, on) -> mGame.setEvaluateMovesOn(on));
        mOpponent = findViewById(R.id.opponent);
        mJarvisButton = findViewById(R.id.play);
        mJarvisButton.setOnClickListener(v -> {
            if (jarvisPlaying()) mGame.stopPlayingWithJarvis(); else mGame.startWithJarvis();
        });
        findViewById(R.id.new_game).setOnClickListener(v -> mGame.newGame());
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
        for (int i = 0; i < 9; i++) {
            char mark = board.at(i);
            mCells[i].setText(mark == Board.EMPTY ? "" : String.valueOf(mark));
            mCells[i].setEnabled(mGame.canTap(i));
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
        // The switch applies to the next game with Jarvis: a session's tools
        // are fixed when it starts.
        mEvaluateSwitch.setEnabled(!jarvisPlaying());
        mOpponent.setText(mGame.opponent() == Game.Opponent.JARVIS
                ? R.string.opponent_jarvis : R.string.opponent_friend);
        mJarvisButton.setText(jarvisPlaying() ? R.string.stop : R.string.play);
    }

    /** A game with Jarvis is under way: the button offers to stop it. */
    private boolean jarvisPlaying() {
        return mGame.opponent() == Game.Opponent.JARVIS && mGame.turn() != Game.Turn.OVER;
    }
}
