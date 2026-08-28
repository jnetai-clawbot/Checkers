package com.jnetai.checkers.game

import com.jnetai.checkers.utils.ErrorLogger
import kotlin.random.Random

/**
 * AI difficulty levels. Higher levels search deeper with alpha-beta pruning.
 */
enum class AiDifficulty(val displayName: String, val searchDepth: Int) {
    EASY("Easy", 1),
    MEDIUM("Medium", 4),
    HARD("Hard", 8)
}

/**
 * AiEngine - offline computer opponent. Never requires a network connection.
 *
 * EASY: random legal move (jumps preferred for pleasant play).
 * MEDIUM: 4-ply minimax with simple heuristic.
 * HARD: 8-ply alpha-beta with move ordering.
 */
object AiEngine {

    private const val VAL_WHITE_MAN = 100
    private const val VAL_WHITE_KING = 260
    private const val VAL_BLACK_MAN = 100
    private const val VAL_BLACK_KING = 260
    private const val VAL_ADVANCE = 3

    /**
     * Choose a move for [aiPlayer] on [engine].
     * Returns null if no legal move exists.
     */
    fun chooseMove(engine: GameEngine, aiPlayer: Int, difficulty: AiDifficulty): Move? {
        return try {
            val legal = engine.generateMoves(aiPlayer).allLegal
            if (legal.isEmpty()) return null

            when (difficulty) {
                AiDifficulty.EASY -> pickEasy(legal)
                AiDifficulty.MEDIUM -> pickSearch(engine, aiPlayer, difficulty.searchDepth, legal)
                AiDifficulty.HARD -> pickSearch(engine, aiPlayer, difficulty.searchDepth, legal)
            }
        } catch (e: Exception) {
            ErrorLogger.logf(ErrorLogger.Codes.AI_SEARCH_FAILED,
                "AI failed to choose a move (difficulty %s)", e, difficulty.displayName)
            // Fallback: random legal move so the game can continue.
            pickEasy(engine.generateMoves(aiPlayer).allLegal)
        }
    }

    private fun pickEasy(legal: List<Move>): Move {
        val jumps = legal.filter { it.isJump }
        val pool = if (jumps.isNotEmpty() && Random.nextFloat() < 0.85f) jumps else legal
        return pool[Random.nextInt(pool.size)]
    }

    private fun pickSearch(engine: GameEngine, aiPlayer: Int, depth: Int, legal: List<Move>): Move {
        val rootState = engine.saveState()
        var bestMove: Move? = null
        var bestScore = Int.MIN_VALUE
        var alpha = Int.MIN_VALUE
        val beta = Int.MAX_VALUE

        val ordered = legal.sortedWith(
            compareByDescending<Move> { it.isJump }
                .thenByDescending { it.captured.size }
        )

        for (move in ordered) {
            val applied = engine.applyMove(move)
            if (applied == null) {
                ErrorLogger.logf(ErrorLogger.Codes.AI_INVALID_MOVE,
                    "AI move rejected during search: %s", move)
                continue
            }
            val score = -negamax(engine, aiPlayer, depth - 1, -beta, -alpha)
            engine.restoreState(rootState)
            if (score > bestScore) {
                bestScore = score
                bestMove = move
            }
            if (score > alpha) alpha = score
        }

        if (bestMove == null) {
            ErrorLogger.log(ErrorLogger.Codes.AI_NO_MOVE,
                "Search returned no best move; falling back to random")
            return pickEasy(legal)
        }
        return bestMove
    }

    /**
     * NegaMax with alpha-beta pruning. Positions are scored from the point of
     * view of the side to move.
     */
    private fun negamax(engine: GameEngine, aiPlayer: Int, depth: Int, alphaIn: Int, betaIn: Int): Int {
        var alpha = alphaIn
        val beta = betaIn

        // Terminal check.
        val result = engine.getResult()
        if (result == GameDefs.BLACK || result == GameDefs.WHITE) {
            val winner = result
            return if (winner == aiPlayer) WIN_SCORE + depth else -WIN_SCORE - depth
        }
        if (result == GameEngine.DRAW_RESULT) return 0

        if (depth <= 0) {
            return evaluate(engine, aiPlayer)
        }

        val player = engine.currentPlayer
        val moves = engine.generateMoves(player).allLegal
        if (moves.isEmpty()) {
            // No moves => the side to move loses.
            return if (player == aiPlayer) -WIN_SCORE - depth else WIN_SCORE + depth
        }

        val ordered = moves.sortedWith(
            compareByDescending<Move> { it.isJump }
                .thenByDescending { it.captured.size }
        )

        var best = Int.MIN_VALUE
        val state = engine.saveState()
        for (move in ordered) {
            val applied = engine.applyMove(move)
            if (applied == null) continue
            val score = -negamax(engine, aiPlayer, depth - 1, -beta, -alpha)
            engine.restoreState(state)
            if (score > best) best = score
            if (score > alpha) alpha = score
            if (alpha >= beta) break
        }
        return best
    }

    /** Material + advancement + king-centre heuristic from [forPlayer] perspective. */
    fun evaluate(engine: GameEngine, forPlayer: Int): Int {
        val board = engine.boardFacade()
        val size = engine.size
        val opp = GameDefs.opponent(forPlayer)
        var score = 0

        for (sq in board.indices) {
            val p = board[sq]
            if (p == GameDefs.EMPTY) continue
            val owner = GameDefs.owner(p)
            val value: Int
            val advancement: Int

            when (p) {
                GameDefs.BLACK, GameDefs.WHITE -> {
                    value = if (owner == GameDefs.BLACK) VAL_BLACK_MAN else VAL_WHITE_MAN
                    val row = sq / size
                    val distanceToPromote = if (owner == GameDefs.BLACK) row else (size - 1 - row)
                    advancement = (size - 1 - distanceToPromote) * VAL_ADVANCE
                }
                else -> {
                    value = if (owner == GameDefs.BLACK) VAL_BLACK_KING else VAL_WHITE_KING
                    val row = sq / size
                    val col = sq % size
                    val central = if (row in size / 4 until size - size / 4 && col in size / 4 until size - size / 4) 6 else 0
                    advancement = central
                }
            }

            val pieceScore = value + advancement
            score += if (owner == forPlayer) pieceScore else -pieceScore
        }
        return score
    }

    private const val WIN_SCORE = 100_000
}