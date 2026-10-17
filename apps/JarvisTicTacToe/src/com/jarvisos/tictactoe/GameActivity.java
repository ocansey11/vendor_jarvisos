package com.jarvisos.tictactoe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.RadioGroup;
import android.widget.TextView;

/**
 * The screen: the board, who plays which mark and the score, Jarvis's
 * speech bubble and "Jarvis is thinking…", New game and Pause/Resume (Rematch
 * after a game). New game opens a pop-up: pick X or O (X moves first), then
 * Play with Jarvis or Play with a friend. The info button shows the rules. Everything it shows comes
 * from {@link Game}; nothing here talks to Jarvis.
 */
public class GameActivity extends Activity implements Game.Screen {

    /** Set by the start tool: start a game with Jarvis once on screen. */
    static final String EXTRA_START_WITH_JARVIS = "com.jarvisos.tictactoe.START_WITH_JARVIS";
    /** For tests: set the evaluate_moves switch on launch (am start --ez evaluate_moves false). */
    static final String EXTRA_EVALUATE_MOVES = "evaluate_moves";

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Button[] mCells = new Button[9];
    private Game mGame;
    private TextView mPlayers;
    private TextView mBubble;
    private TextView mStatus;
    private View mThinkingRow;
    private TextView mThinking;
    private Button mPause;
    private AlertDialog mNewGame;

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
            mCells[i].setOnClickListener(v -> mGame.play(cell));
        }
        mPlayers = findViewById(R.id.players);
        mBubble = findViewById(R.id.bubble);
        mStatus = findViewById(R.id.status);
        mThinkingRow = findViewById(R.id.thinking_row);
        mThinking = findViewById(R.id.thinking);
        mPause = findViewById(R.id.pause);

        findViewById(R.id.new_game).setOnClickListener(v -> {
            mGame.newGame();
            showNewGame();
        });
        mPause.setOnClickListener(v -> {
            if (mGame.over()) mGame.rematch();
            else if (mGame.paused()) mGame.resume();
            else mGame.pause("Paused.");
        });
        findViewById(R.id.rules).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.rules)
                .setMessage(R.string.rules_text)
                .setPositiveButton(R.string.ok, null)
                .show());
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
        if (mGame.opponent() == Game.Opponent.NONE) showNewGame();
    }

    /** The new-game pop-up. The opponent buttons start the game. */
    private void showNewGame() {
        if (mNewGame != null && mNewGame.isShowing()) return;
        View view = getLayoutInflater().inflate(R.layout.dialog_new_game, null);
        RadioGroup marks = view.findViewById(R.id.marks);
        marks.check(mGame.mine() == Board.O ? R.id.mark_o : R.id.mark_x);
        marks.setOnCheckedChangeListener((group, id) ->
                mGame.chooseMark(id == R.id.mark_o ? Board.O : Board.X));
        CompoundButton evaluate = view.findViewById(R.id.evaluate_switch);
        evaluate.setChecked(mGame.evaluateMovesOn());
        evaluate.setOnCheckedChangeListener((b, on) -> mGame.setEvaluateMovesOn(on));
        mNewGame = new AlertDialog.Builder(this)
                .setTitle(R.string.new_game)
                .setView(view)
                .setPositiveButton(R.string.play_jarvis, (d, w) -> mGame.startWithJarvis())
                .setNegativeButton(R.string.play_friend, (d, w) -> mGame.startWithFriend())
                .show();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mNewGame != null) mNewGame.dismiss();
        // AX: a session lives only while the app is on screen.
        mGame.onScreenStopped();
        mGame.setScreen(null);
    }

    @Override
    public void onGameChanged() {
        boolean choosing = mGame.opponent() == Game.Opponent.NONE;
        Board board = mGame.board();
        int[] line = board.winningLine();
        for (int i = 0; i < 9; i++) {
            char mark = board.at(i);
            mCells[i].setText(mark == Board.EMPTY ? "" : String.valueOf(mark));
            mCells[i].setEnabled(mGame.canTap(i));
            mCells[i].setActivated(line != null && (line[0] == i || line[1] == i || line[2] == i));
        }

        mPause.setVisibility(choosing ? View.GONE : View.VISIBLE);
        mPause.setText(mGame.over() ? R.string.rematch
                : mGame.paused() ? R.string.resume : R.string.pause);

        if (choosing) {
            mPlayers.setText("");
            mStatus.setText(R.string.choose);
        } else {
            boolean jarvis = mGame.opponent() == Game.Opponent.JARVIS;
            mPlayers.setText((jarvis
                    ? getString(R.string.players_jarvis, mGame.mine(), Board.other(mGame.mine()))
                    : getString(R.string.players_friend))
                    + "\n" + getString(jarvis ? R.string.score_jarvis : R.string.score_friend,
                            mGame.wins(), mGame.losses(), mGame.draws()));
            mStatus.setText(mGame.status());
        }
        String bubble = mGame.bubble();
        mBubble.setVisibility(bubble != null ? View.VISIBLE : View.INVISIBLE);
        mBubble.setText(bubble != null ? bubble : "");
        mThinkingRow.setVisibility(mGame.thinking() ? View.VISIBLE : View.GONE);
        mMain.removeCallbacks(mTick);
        if (mGame.thinking()) mTick.run();
    }
}
