package com.jarvisos.tictactoe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tic-tac-toe board: cells 0 to 8, row by row, marks X and O. X moves first.
 *
 * Plain Java with no Android in it, so the rules and the move evaluation are
 * tested on the host (vendor/jarvisos/tests, BoardTest).
 */
public final class Board {

    public static final char X = 'X';
    public static final char O = 'O';
    public static final char EMPTY = '.';

    /** The other side's mark. */
    public static char other(char mark) {
        return mark == X ? O : X;
    }

    private static final int[][] LINES = {
        {0, 1, 2}, {3, 4, 5}, {6, 7, 8},   // rows
        {0, 3, 6}, {1, 4, 7}, {2, 5, 8},   // columns
        {0, 4, 8}, {2, 4, 6},              // diagonals
    };

    private final char[] mCells = new char[9];

    public Board() {
        java.util.Arrays.fill(mCells, EMPTY);
    }

    /** A board from its 9-character form, e.g. "X...O....". */
    public static Board of(String cells) {
        if (cells.length() != 9) throw new IllegalArgumentException("a board has 9 cells");
        Board board = new Board();
        for (int i = 0; i < 9; i++) board.mCells[i] = cells.charAt(i);
        return board;
    }

    public char at(int cell) {
        return mCells[cell];
    }

    /** Puts {@code mark} on {@code cell}; refuses a taken cell or a finished game. */
    public void play(int cell, char mark) {
        if (cell < 0 || cell > 8) throw new IllegalArgumentException("cell must be 0 to 8, got " + cell);
        if (isOver()) throw new IllegalStateException("the game is over");
        if (mCells[cell] != EMPTY) {
            throw new IllegalArgumentException("cell " + cell + " is taken; legal cells: " + legal());
        }
        mCells[cell] = mark;
    }

    public List<Integer> legal() {
        List<Integer> cells = new ArrayList<>();
        if (winner() != EMPTY) return cells;
        for (int i = 0; i < 9; i++) if (mCells[i] == EMPTY) cells.add(i);
        return cells;
    }

    /** X or O if that mark has three in a row, otherwise '.'. */
    public char winner() {
        for (int[] line : LINES) {
            char a = mCells[line[0]];
            if (a != EMPTY && a == mCells[line[1]] && a == mCells[line[2]]) return a;
        }
        return EMPTY;
    }

    /** The three winning cells, or null. */
    public int[] winningLine() {
        for (int[] line : LINES) {
            char a = mCells[line[0]];
            if (a != EMPTY && a == mCells[line[1]] && a == mCells[line[2]]) return line.clone();
        }
        return null;
    }

    public boolean isOver() {
        if (winner() != EMPTY) return true;
        for (char c : mCells) if (c == EMPTY) return false;
        return true;
    }

    public Board copy() {
        return of(toString());
    }

    /** The 9-character form: the shape of the board in events and tool replies. */
    @Override
    public String toString() {
        return new String(mCells);
    }

    // -------------------------------------------------------------------------
    // evaluate_moves: the tool does the calculation, the model decides
    // -------------------------------------------------------------------------

    /**
     * What each legal move for {@code mark} leads to with best play by both
     * sides after it, in words a small model can use without searching:
     * "wins now", "wins in 2 moves", "draw", "loses: player wins in 1 move",
     * plus ", blocks the player" when the cell stops the other side's three
     * in a row. Cells in order. "Player" is always the other side: the
     * caller evaluates for Jarvis.
     */
    public Map<Integer, String> evaluate(char mark) {
        final char other = other(mark);
        Map<Integer, String> result = new LinkedHashMap<>();
        for (int cell : legal()) {
            Board next = copy();
            next.mCells[cell] = mark;
            String outcome;
            if (next.winner() == mark) {
                outcome = "wins now";
            } else {
                // Score from the other side's view after this move, then flip.
                int[] best = next.solve(other);
                int value = -best[0];
                int plies = best[1] + 1;   // this move plus the rest of the game
                if (value > 0) {
                    outcome = "wins in " + (plies + 1) / 2 + " moves";
                } else if (value < 0) {
                    int theirs = plies / 2;
                    outcome = "loses: player wins in "
                            + theirs + (theirs == 1 ? " move" : " moves");
                } else {
                    outcome = "draw";
                }
            }
            Board threat = copy();
            threat.mCells[cell] = other;
            if (threat.winner() == other) outcome += ", blocks the player";
            result.put(cell, outcome);
        }
        return result;
    }

    /**
     * The cells with the best outcome for {@code mark}: a win now, else the
     * fastest forced win, else a draw, else the slowest loss. First seen on
     * the emulator: given only the per-cell table, Qwen3-1.7B often picked a
     * cell the table said loses, so the tool also says which cells are best.
     */
    public List<Integer> best(char mark) {
        final char other = other(mark);
        List<Integer> best = new ArrayList<>();
        int bestScore = Integer.MIN_VALUE;
        for (int cell : legal()) {
            Board next = copy();
            next.mCells[cell] = mark;
            int score;
            if (next.winner() == mark) {
                score = 100;
            } else {
                int[] reply = next.solve(other);
                int plies = reply[1] + 1;
                // Win: sooner is better. Loss: later is better. Draw: 0.
                score = -reply[0] > 0 ? 50 - plies : -reply[0] < 0 ? -50 + plies : 0;
            }
            if (score > bestScore) {
                bestScore = score;
                best.clear();
            }
            if (score == bestScore) best.add(cell);
        }
        return best;
    }

    /**
     * Minimax for the side to move: {value, plies}, where value is 1 (it
     * wins), 0 (draw) or -1 (it loses), and plies is how many moves the game
     * still lasts. A win is taken as fast as possible, a loss put off as long
     * as possible. Small enough to search whole: at most 9! positions.
     */
    int[] solve(char toMove) {
        final char other = other(toMove);
        if (winner() == other) return new int[] {-1, 0};
        List<Integer> moves = legal();
        if (moves.isEmpty()) return new int[] {0, 0};
        int[] best = null;
        for (int cell : moves) {
            mCells[cell] = toMove;
            int[] reply = solve(other);
            mCells[cell] = EMPTY;
            int value = -reply[0];
            int plies = reply[1] + 1;
            if (best == null || value > best[0]
                    || (value == best[0] && (value > 0 ? plies < best[1] : plies > best[1]))) {
                best = new int[] {value, plies};
            }
        }
        return best;
    }
}
