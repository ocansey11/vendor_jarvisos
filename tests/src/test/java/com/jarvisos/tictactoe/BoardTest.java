package com.jarvisos.tictactoe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/** The sample game's rules and evaluate_moves (apps/JarvisTicTacToe/.../Board.java). */
class BoardTest {

    @Test
    @DisplayName("Rows, columns and diagonals win; legal cells shrink; a full board is over")
    void rules() {
        assertEquals('X', Board.of("XXX......").winner());
        assertEquals('O', Board.of("O...O...O").winner());
        assertEquals('.', Board.of("XOXXOOOXX").winner());
        assertTrue(Board.of("XOXXOOOXX").isOver());
        assertEquals(List.of(1, 2, 3, 5, 6, 7, 8), Board.of("X...O....").legal());
        assertEquals(List.of(), Board.of("XXXOO....").legal(), "no moves after a win");
    }

    @Test
    @DisplayName("A taken cell is refused with the legal cells in the message")
    void takenCell() {
        Board board = Board.of("X...O....");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> board.play(4, Board.PLAYER));
        assertTrue(e.getMessage().contains("legal cells: [1, 2, 3, 5, 6, 7, 8]"), e.getMessage());
    }

    @Test
    @DisplayName("evaluate_moves: an immediate win, a block, and losing moves")
    void winAndBlock() {
        // O O . / X X . / X . .  — Jarvis (O) to move. 2 wins; 5 would only block.
        Map<Integer, String> e = Board.of("OO.XX.X..").evaluate(Board.JARVIS);
        // 2 also stops X on the 2-4-6 diagonal.
        assertEquals("wins now, blocks the player", e.get(2));
        assertTrue(e.get(5).contains("blocks the player"), e.get(5));
        assertTrue(e.get(7).startsWith("loses: player wins in 1 move"), e.get(7));
    }

    @Test
    @DisplayName("evaluate_moves: after X in a corner, only the centre draws")
    void cornerOpening() {
        Map<Integer, String> e = Board.of("X........").evaluate(Board.JARVIS);
        assertEquals("draw", e.get(4));
        for (int cell : List.of(1, 2, 3, 5, 6, 7, 8)) {
            assertTrue(e.get(cell).startsWith("loses"), cell + ": " + e.get(cell));
        }
    }

    @Test
    @DisplayName("evaluate_moves: a win on the board, and the edge that saves a draw")
    void diagonalPositions() {
        // O X . / . O . / X . .  : O to move; 8 wins now.
        Map<Integer, String> e = Board.of("OX..O.X..").evaluate(Board.JARVIS);
        assertEquals("wins now", e.get(8));
        // X . . / . O . / . . X : O to move; an edge forces a draw, a corner loses.
        Map<Integer, String> f = Board.of("X...O...X").evaluate(Board.JARVIS);
        assertEquals("draw", f.get(1));
        assertTrue(f.get(2).startsWith("loses"), f.get(2));
    }

    @Test
    @DisplayName("best: a win beats a block; after a corner opening only the centre")
    void best() {
        assertEquals(List.of(2), Board.of("OO.XX.X..").best(Board.JARVIS));
        assertEquals(List.of(4), Board.of("X........").best(Board.JARVIS));
        // Facing a fork threat, every drawing cell is listed.
        assertEquals(List.of(1, 3, 5, 7), Board.of("X...O...X").best(Board.JARVIS));
    }
}
