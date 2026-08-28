package com.jnetai.checkers.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules conformance tests for UK English Draughts.
 *
 * Board: 8x8, only dark squares are playable. BLACK occupies the bottom three
 * rows and moves "up" (decreasing row). WHITE occupies the top three rows and
 * moves "down". Index = row*8+col.
 */
class GameEngineTest {

    private fun idx(row: Int, col: Int): Int = row * 8 + col

    private fun isDark(sq: Int): Boolean = ((sq / 8) + (sq % 8)) % 2 == 1

    /** A fresh UK/EUROPE engine with an empty board (BLACK to move). */
    private fun fresh(): GameEngine {
        val g = GameEngine(RulePreset.UK_EUROPE)
        for (i in 0 until g.size * g.size) g.placePiece(i, GameDefs.EMPTY)
        return g
    }

    private fun legal(g: GameEngine, p: Int): List<Move> = g.generateMoves(p).allLegal

    private fun movesFrom(moves: List<Move>, from: Int): List<Move> =
        moves.filter { it.from == from }

    private fun hasJump(moves: List<Move>, from: Int, path: List<Int>, captured: List<Int>): Boolean =
        moves.any { it.from == from && it.path == path && it.captured == captured }

    // ------------------------------------------------------------------
    // 1. Board & setup
    // ------------------------------------------------------------------

    @Test
    fun initialSetup_isEnglishDraughts() {
        val g = fresh()
        g.reset() // back to the real opening position
        assertEquals(8, g.size)
        assertEquals(12, g.countPieces(GameDefs.BLACK))
        assertEquals(12, g.countPieces(GameDefs.WHITE))

        var allOnDark = true
        var blackInBottomThree = true
        var whiteInTopThree = true
        for (sq in 0 until 64) {
            val p = g.pieceAt(sq)
            if (p == GameDefs.EMPTY) continue
            if (!isDark(sq)) allOnDark = false
            val row = sq / 8
            if (GameDefs.owner(p) == GameDefs.BLACK && row !in 5..7) blackInBottomThree = false
            if (GameDefs.owner(p) == GameDefs.WHITE && row !in 0..2) whiteInTopThree = false
        }
        assertTrue("every piece must sit on a dark square", allOnDark)
        assertTrue("black must occupy the three nearest rows", blackInBottomThree)
        assertTrue("white must occupy the three nearest rows", whiteInTopThree)

        // The two central rows are empty and there is nothing to capture at move one.
        for (sq in 24..39) assertEquals(GameDefs.EMPTY, g.pieceAt(sq))
        assertFalse("no captures on the opening position", g.generateMoves(GameDefs.BLACK).hasCapture)
        assertEquals(GameDefs.EMPTY, g.getResult())
    }

    @Test
    fun movesNeverLandOnLightSquares() {
        val g = fresh()
        for (p in intArrayOf(GameDefs.BLACK, GameDefs.WHITE)) {
            for (m in legal(g, p)) {
                val to = m.to
                assertTrue("move $m lands on a light square", isDark(to))
            }
        }
        g.reset()
        // Exercise a few tactical positions as well.
        val positions = listOf(
            { e: GameEngine -> e.placePiece(idx(4, 3), GameDefs.BLACK); e.placePiece(idx(3, 2), GameDefs.WHITE) },
            { e: GameEngine -> e.placePiece(idx(3, 2), GameDefs.BLACK_KING); e.placePiece(idx(4, 3), GameDefs.WHITE) },
            { e: GameEngine -> e.placePiece(idx(5, 2), GameDefs.BLACK); e.placePiece(idx(3, 4), GameDefs.WHITE) }
        )
        for (setup in positions) {
            g.reset()
            for (sq in 0 until 64) g.placePiece(sq, GameDefs.EMPTY)
            setup(g)
            for (p in intArrayOf(GameDefs.BLACK, GameDefs.WHITE)) {
                for (m in legal(g, p)) {
                    assertTrue("move $m lands on a light square", isDark(m.to))
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 2. Men
    // ------------------------------------------------------------------

    @Test
    fun normalPiece_movesOneSquareForwardDiagonally() {
        val g = fresh()
        g.placePiece(idx(4, 3), GameDefs.BLACK) // sq 35
        val moves = legal(g, GameDefs.BLACK)
        val from35 = movesFrom(moves, 35)
        assertEquals("expected exactly two forward moves", 2, from35.size)
        assertTrue(from35.any { it.to == idx(3, 2) }) // 26
        assertTrue(from35.any { it.to == idx(3, 4) }) // 28
        assertFalse("no backward move allowed", from35.any { it.to == idx(5, 2) || it.to == idx(5, 4) })
    }

    @Test
    fun normalPiece_cannotMoveBackward() {
        val g = fresh()
        g.placePiece(idx(3, 2), GameDefs.BLACK) // sq 26
        val from26 = movesFrom(legal(g, GameDefs.BLACK), 26)
        assertTrue(from26.none { it.to == idx(4, 1) || it.to == idx(4, 3) })
    }

    @Test
    fun normalPiece_capturesForward() {
        val g = fresh()
        g.placePiece(idx(4, 3), GameDefs.BLACK) // 35
        g.placePiece(idx(3, 2), GameDefs.WHITE) // 26
        val jumps = legal(g, GameDefs.BLACK)
        assertTrue(hasJump(jumps, 35, listOf(idx(2, 1)), listOf(idx(3, 2))))
    }

    @Test
    fun normalPiece_cannotCaptureBackward() {
        val g = fresh()
        g.placePiece(idx(3, 2), GameDefs.BLACK) // 26
        g.placePiece(idx(4, 3), GameDefs.WHITE) // 35, diagonally BEHIND black
        val moves = legal(g, GameDefs.BLACK)
        assertTrue("backward capture by a man is illegal", moves.none { it.isJump })
    }

    @Test
    fun man_cannotCaptureAcrossAGap() {
        // White is two diagonal squares away with an empty square between.
        val g = fresh()
        g.placePiece(idx(5, 2), GameDefs.BLACK) // 42
        g.placePiece(idx(3, 4), GameDefs.WHITE) // 28 - at 35 there is a gap
        val moves = legal(g, GameDefs.BLACK)
        assertTrue("a gap capture is illegal", moves.none { it.isJump })
        // It may still take a normal forward move toward the gap square.
        assertTrue(movesFrom(moves, 42).any { it.to == idx(4, 3) })
    }

    // ------------------------------------------------------------------
    // 3. Compulsory capture
    // ------------------------------------------------------------------

    @Test
    fun captureIsCompulsory() {
        val g = fresh()
        g.placePiece(idx(4, 3), GameDefs.BLACK) // 35 can capture 26 -> 17
        g.placePiece(idx(3, 2), GameDefs.WHITE)
        g.placePiece(idx(5, 2), GameDefs.BLACK) // 42 has a plain forward move
        val moves = legal(g, GameDefs.BLACK)
        assertTrue(moves.all { it.isJump })
        assertTrue("only the capturing piece may move when capture is mandatory",
            moves.all { it.from == 35 })
    }

    @Test
    fun nonCaptureMove_isRejectedWhenACaptureExists() {
        val g = fresh()
        g.placePiece(idx(4, 3), GameDefs.BLACK)
        g.placePiece(idx(3, 2), GameDefs.WHITE)
        g.placePiece(idx(5, 2), GameDefs.BLACK)
        assertNull("plain move must be rejected while a capture exists",
            g.applyMove(Move(42, listOf(idx(4, 1)))))
        assertNotNull("the forced capture must be accepted",
            g.applyMove(Move(35, listOf(idx(2, 1)), listOf(idx(3, 2)))))
    }

    @Test
    fun multipleCaptureOptions_allowPlayerChoice() {
        val g = fresh()
        g.placePiece(idx(4, 3), GameDefs.BLACK) // 35
        g.placePiece(idx(3, 2), GameDefs.WHITE) // 26 -> land 17
        g.placePiece(idx(3, 4), GameDefs.WHITE) // 28 -> land 21
        val moves = legal(g, GameDefs.BLACK)
        assertEquals(2, moves.size)
        assertTrue(hasJump(moves, 35, listOf(idx(2, 1)), listOf(idx(3, 2))))
        assertTrue(hasJump(moves, 35, listOf(idx(2, 5)), listOf(idx(3, 4))))
    }

    // ------------------------------------------------------------------
    // 4. Multi-capture
    // ------------------------------------------------------------------

    private fun chainBoard(): GameEngine {
        val g = fresh()
        g.placePiece(idx(5, 4), GameDefs.BLACK) // 44 (man)
        g.placePiece(idx(4, 3), GameDefs.WHITE) // 35 -> land 26
        g.placePiece(idx(2, 3), GameDefs.WHITE) // 19 -> land 12
        return g
    }

    @Test
    fun multiCapture_continuesWithTheSamePiece() {
        val g = chainBoard()
        val moves = legal(g, GameDefs.BLACK)
        assertTrue(movesFrom(moves, 44).any { it.path == listOf(26, 12) && it.captured == listOf(35, 19) })
    }

    @Test
    fun multiCapture_cannotStopOrSwitchPiecesEarly() {
        val g = chainBoard()
        // A truncated chain (stop after one capture) must be rejected.
        assertNull("partial chain must not be accepted",
            g.applyMove(Move(44, listOf(26), listOf(35))))
        // The complete chain is the only legal move; applying it switches the
        // turn exactly once and removes exactly two enemy pieces.
        val s = g.saveState()
        val captured = g.applyMove(Move(44, listOf(26, 12), listOf(35, 19)))
        assertNotNull(captured)
        assertTrue("player must not change mid-chain", g.currentPlayer == GameDefs.WHITE)
        assertEquals(2, g.getCapturedWhite())
        g.restoreState(s)
        // And the move list contains no 1-jump option that the UI could offer early.
        assertTrue(legal(g, GameDefs.BLACK).none { it.from == 44 && it.captured.size == 1 })
    }

    // ------------------------------------------------------------------
    // 5. Kings
    // ------------------------------------------------------------------

    @Test
    fun king_movesForwardAndBackward() {
        val g = fresh()
        g.placePiece(idx(4, 3), GameDefs.BLACK_KING) // 35
        val from35 = movesFrom(legal(g, GameDefs.BLACK), 35)
        assertEquals(4, from35.size)
        assertTrue(from35.any { it.to == idx(3, 2) })
        assertTrue(from35.any { it.to == idx(3, 4) })
        assertTrue(from35.any { it.to == idx(5, 2) })
        assertTrue(from35.any { it.to == idx(5, 4) })
    }

    @Test
    fun king_capturesForward() {
        val g = fresh()
        g.placePiece(idx(4, 3), GameDefs.BLACK_KING) // 35
        g.placePiece(idx(3, 2), GameDefs.WHITE) // 26
        assertTrue(hasJump(legal(g, GameDefs.BLACK), 35, listOf(idx(2, 1)), listOf(idx(3, 2))))
    }

    @Test
    fun king_capturesBackward() {
        val g = fresh()
        g.placePiece(idx(3, 2), GameDefs.BLACK_KING) // 26
        g.placePiece(idx(4, 3), GameDefs.WHITE) // 35, behind the king
        assertTrue(hasJump(legal(g, GameDefs.BLACK), 26, listOf(idx(5, 4)), listOf(idx(4, 3))))
    }

    @Test
    fun king_cannotFlyMultipleSquares() {
        val g = fresh()
        g.placePiece(idx(4, 3), GameDefs.BLACK_KING) // 35
        g.placePiece(idx(2, 1), GameDefs.WHITE) // 17 - two squares up-left, not adjacent
        val moves = legal(g, GameDefs.BLACK)
        assertTrue("kings must not fly", moves.none { it.isJump })
        // And simple king moves are exactly one diagonal step.
        val from35 = movesFrom(moves, 35)
        assertEquals("a non-flying king has exactly four one-step moves", 4, from35.size)
        assertTrue(from35.all { Math.abs(it.to - 35) == 7 || Math.abs(it.to - 35) == 9 })
    }

    @Test
    fun king_cannotCaptureAcrossAGap() {
        val g = fresh()
        g.placePiece(idx(5, 2), GameDefs.BLACK_KING) // 42
        g.placePiece(idx(3, 4), GameDefs.WHITE) // 28, with an empty square at 35
        val moves = legal(g, GameDefs.BLACK)
        assertTrue("kings must jump an adjacent piece only", moves.none { it.isJump })
        assertTrue(movesFrom(moves, 42).any { it.to == idx(4, 3) })
    }

    @Test
    fun king_performsMultiCapture() {
        val g = fresh()
        g.placePiece(idx(5, 4), GameDefs.BLACK_KING) // 44
        g.placePiece(idx(4, 3), GameDefs.WHITE) // 35 -> 26
        g.placePiece(idx(2, 3), GameDefs.WHITE) // 19 -> 12
        val chain = legal(g, GameDefs.BLACK)
        assertTrue(chain.any { it.from == 44 && it.path == listOf(26, 12) && it.captured == listOf(35, 19) })
    }

    // ------------------------------------------------------------------
    // 6. Promotion
    // ------------------------------------------------------------------

    @Test
    fun man_promotesOnOpponentsBackRow() {
        val g = fresh()
        g.placePiece(idx(1, 0), GameDefs.BLACK) // 8
        assertNotNull(g.applyMove(Move(8, listOf(idx(0, 1)))))
        assertEquals(GameDefs.BLACK_KING, g.pieceAt(idx(0, 1)))
    }

    @Test
    fun promotionDuringCapture_endsTheTurnImmediately() {
        val g = fresh()
        g.placePiece(idx(2, 1), GameDefs.BLACK) // 17
        g.placePiece(idx(1, 2), GameDefs.WHITE) // 10 -> landing 3 (back row)
        val moves = legal(g, GameDefs.BLACK)
        assertTrue("exactly one move expected (promoting capture)",
            moves.size == 1)
        assertTrue(hasJump(moves, 17, listOf(idx(0, 3)), listOf(idx(1, 2))))

        val captured = g.applyMove(Move(17, listOf(idx(0, 3)), listOf(idx(1, 2))))
        assertNotNull(captured)
        assertEquals("the promoted man must become a king", GameDefs.BLACK_KING, g.pieceAt(idx(0, 3)))
        assertEquals("turn must end after the promoting capture", GameDefs.WHITE, g.currentPlayer)
    }

    // ------------------------------------------------------------------
    // 7. Capture priority - no maximum
    // ------------------------------------------------------------------

    @Test
    fun noMaximumCaptureRule_isEnforced() {
        val g = fresh()
        // King at 35 has a 3-piece chain (26 -> 10 -> 12).
        g.placePiece(idx(4, 3), GameDefs.BLACK_KING)
        g.placePiece(idx(3, 2), GameDefs.WHITE)   // 26
        g.placePiece(idx(1, 2), GameDefs.WHITE)   // 10
        g.placePiece(idx(1, 4), GameDefs.WHITE)   // 12
        // Man at 44 has a single capture over 37.
        g.placePiece(idx(5, 4), GameDefs.BLACK)
        g.placePiece(idx(4, 5), GameDefs.WHITE)   // 37
        val moves = legal(g, GameDefs.BLACK)
        // The 3-capture chain must be offered.
        assertTrue("3-piece chain must be legal",
            moves.any { it.from == 35 && it.captured == listOf(26, 10, 12) })
        // AND the 1-capture option must also remain legal (no majority rule).
        assertTrue("single capture must still be legal",
            moves.any { it.from == 44 && it.captured == listOf(37) })
    }

    // ------------------------------------------------------------------
    // 8. Win / draw
    // ------------------------------------------------------------------

    @Test
    fun playerWithNoPieces_loses() {
        val g = fresh()
        g.placePiece(idx(5, 0), GameDefs.BLACK) // 40
        assertEquals(0, g.countPieces(GameDefs.WHITE))
        assertEquals(GameDefs.BLACK, g.getResult())
    }

    @Test
    fun playerWhosePiecesAreBlocked_loses() {
        val g = fresh()
        g.placePiece(idx(5, 0), GameDefs.BLACK) // 40 has a forward move
        g.placePiece(idx(7, 2), GameDefs.WHITE) // 58 - on the back row, no forward move
        assertEquals("white has no legal move therefore black wins", GameDefs.BLACK, g.getResult())
    }

    @Test
    fun gameRejectsMovesAfterGameOver() {
        val g = fresh()
        g.placePiece(idx(5, 0), GameDefs.BLACK) // 40
        g.placePiece(idx(7, 2), GameDefs.WHITE) // 58 - blocked on back row
        g.currentPlayer = GameDefs.WHITE
        assertEquals(GameDefs.BLACK, g.getResult())
        // White is to move but has nothing - every submission is rejected.
        assertNull(g.applyMove(Move(58, listOf(50))))
        assertNull(g.applyMove(Move(58, listOf(49))))
    }

    @Test
    fun noDrawWhenCaptureMissingButMovesExist() {
        val g = fresh()
        g.placePiece(idx(5, 0), GameDefs.BLACK) // 40
        g.placePiece(idx(1, 0), GameDefs.WHITE) // 8
        assertFalse(g.generateMoves(GameDefs.BLACK).hasCapture)
        assertTrue(g.generateMoves(GameDefs.BLACK).allLegal.isNotEmpty())
        assertTrue(g.generateMoves(GameDefs.WHITE).allLegal.isNotEmpty())
        assertEquals("a simple-move position is not a draw", GameDefs.EMPTY, g.getResult())
    }

    // ------------------------------------------------------------------
    // 9. AI
    // ------------------------------------------------------------------

    @Test
    fun ai_obeysCompulsoryCapture() {
        val g = fresh()
        g.placePiece(idx(4, 3), GameDefs.BLACK) // 35 can capture
        g.placePiece(idx(3, 2), GameDefs.WHITE)
        g.placePiece(idx(5, 2), GameDefs.BLACK) // 42 must not be used
        val choice = AiEngine.chooseMove(g, GameDefs.BLACK, AiDifficulty.MEDIUM)
        assertNotNull(choice)
        assertTrue("AI must capture when a capture exists", choice!!.isJump)
        assertEquals(35, choice.from)
    }

    @Test
    fun ai_obeysKingBackwardCapture() {
        val g = fresh()
        g.placePiece(idx(3, 2), GameDefs.BLACK_KING) // 26
        g.placePiece(idx(4, 3), GameDefs.WHITE)
        val choice = AiEngine.chooseMove(g, GameDefs.BLACK, AiDifficulty.EASY)
        assertNotNull(choice)
        assertTrue(choice!!.isJump)
        assertEquals(26, choice.from)
    }

    @Test
    fun ai_needsLegalMovesFromSameEngine() {
        val g = fresh()
        // Mandatory multi-capture chain.
        g.placePiece(idx(5, 4), GameDefs.BLACK)
        g.placePiece(idx(4, 3), GameDefs.WHITE)
        g.placePiece(idx(2, 3), GameDefs.WHITE)
        val choice = AiEngine.chooseMove(g, GameDefs.BLACK, AiDifficulty.HARD)
        assertNotNull(choice)
        assertTrue(choice!!.isJump)
        assertEquals(listOf(26, 12), choice.path)
    }

    // ------------------------------------------------------------------
    // 10. Multiplayer / server-side validation
    // ------------------------------------------------------------------

    @Test
    fun onlineMoves_areValidatedByTheAuthorityEngine() {
        val g = chainBoard()
        // A peer sends the honest full chain -> accepted.
        val honest = Move(44, listOf(26, 12), listOf(35, 19))
        assertNotNull("honest full chain must pass server validation", g.applyMove(honest))
        g.reset()
        for (i in 0 until 64) g.placePiece(i, GameDefs.EMPTY)
        // Rebuild the same position for tampered cases.
        g.placePiece(idx(5, 4), GameDefs.BLACK)
        g.placePiece(idx(4, 3), GameDefs.WHITE)
        g.placePiece(idx(2, 3), GameDefs.WHITE)

        // Tampered 1: truncated chain (stop the capture sequence early).
        assertNull("truncated chain must be rejected", g.applyMove(Move(44, listOf(26), listOf(35))))
        // Tampered 2: re-ordered path that does not correspond to a real chain.
        assertNull("misordered path must be rejected",
            g.applyMove(Move(44, listOf(12, 26), listOf(35, 19))))
        // Tampered 3: moving for the wrong player/capturing own pieces.
        assertNull("move for the wrong side must be rejected",
            g.applyMove(Move(35, listOf(44), listOf(44))))
    }

    @Test
    fun clientCannotBypassMandatoryCapture() {
        val g = fresh()
        g.placePiece(idx(4, 3), GameDefs.BLACK)
        g.placePiece(idx(3, 2), GameDefs.WHITE)
        g.placePiece(idx(5, 2), GameDefs.BLACK)
        // Client submits a non-capturing move while a capture exists.
        assertNull(g.applyMove(Move(42, listOf(33))))
        // The only accepted move is the compulsory capture.
        assertNotNull(g.applyMove(Move(35, listOf(17), listOf(26))))
    }

    @Test
    fun internationalPreset_stillSupportsItsVariant() {
        val g = GameEngine(RulePreset.INTERNATIONAL)
        assertEquals(10, g.size)
        // Men capture backward in international rules.
        g.reset()
        for (i in 0 until 100) g.placePiece(i, GameDefs.EMPTY)
        g.placePiece(4 * 10 + 3, GameDefs.BLACK) // man middle
        g.placePiece(5 * 10 + 4, GameDefs.WHITE) // diagonally behind
        val moves = g.generateMoves(GameDefs.BLACK).allLegal
        assertTrue("international men may capture backward",
            moves.any { it.isJump })
    }
}