package com.jnetai.checkers.game

/**
 * Core checkers definitions: piece values, a Move representation
 * and the rule presets (UK/Europe, US, International).
 */
object GameDefs {
    const val EMPTY = 0
    const val BLACK = 1
    const val BLACK_KING = 2
    const val WHITE = 3
    const val WHITE_KING = 4

    const val MAX_BOARD_SIZE = 10
    const val MAX_SQUARES = 100

    fun isKing(piece: Int): Boolean = piece == BLACK_KING || piece == WHITE_KING
    fun owner(piece: Int): Int = if (piece == BLACK || piece == BLACK_KING) BLACK else WHITE
    fun makeKing(piece: Int): Int = if (piece == BLACK) BLACK_KING else WHITE_KING
    fun opponent(player: Int): Int = if (player == BLACK) WHITE else BLACK

    fun isPromotionRow(row: Int, size: Int): Boolean = row == 0 || row == size - 1

    /** The row where BLACK men promote (top of the board). BLACK starts at the bottom. */
    fun promotionRowFor(player: Int, size: Int): Int = if (player == BLACK) 0 else size - 1

    /** @return a human readable piece name for debug output. */
    fun pieceName(piece: Int): String = when (piece) {
        EMPTY -> "EMPTY"
        BLACK -> "BLACK_MAN"
        BLACK_KING -> "BLACK_KING"
        WHITE -> "WHITE_MAN"
        WHITE_KING -> "WHITE_KING"
        else -> "UNKNOWN[$piece]"
    }
}

/**
 * A checkers move. `from` is the starting square, `path` is the list of
 * landing squares (one or more for a multi-jump) and `captured` is the list
 * of squares that had enemy pieces removed (one per landing for a jump).
 */
data class Move(
    val from: Int,
    val path: List<Int>,
    val captured: List<Int> = emptyList()
) {
    val to: Int get() = path.last()

    val isJump: Boolean get() = captured.isNotEmpty()

    val jumpsCount: Int get() = captured.size

    override fun toString(): String {
        val sb = StringBuilder("MOVE from=$from to=$to jump=${isJump}")
        if (captured.isNotEmpty()) sb.append(" captured=$captured")
        return sb.toString()
    }
}

/**
 * The result of move generation: simple (non-capture) moves and jump chains.
 * When jumps exist, only jump moves are legal (mandatory capture).
 */
data class GeneratedMoves(
    val simple: List<Move>,
    val jumps: List<Move>
) {
    val allLegal: List<Move>
        get() = if (jumps.isNotEmpty()) jumps else simple

    val hasCapture: Boolean get() = jumps.isNotEmpty()
}

/**
 * Game rule presets from around the world.
 *
 * - UK / EUROPE (default): 8x8 board, men capture forward only, flying kings,
 *   mandatory capture.
 * - US / AMERICAN: 8x8 board, men capture forward only, kings move one square,
 *   mandatory capture.
 * - INTERNATIONAL: 10x10 board, men capture forward AND backward, flying kings,
 *   mandatory capture of the maximum number of pieces.
 */
enum class RulePreset(
    val displayName: String,
    val boardSize: Int,
    val flyingKings: Boolean,
    val menCaptureBackward: Boolean,
    val majorityCapture: Boolean
) {
    UK_EUROPE("UK / Europe", 8, true, false, false) {
        override fun describe(): String =
            "English draughts: 8x8, men jump forward only, flying kings, capture is mandatory."
    },
    US_AMERICAN("US / American", 8, false, false, false) {
        override fun describe(): String =
            "American checkers: 8x8, men jump forward only, kings move one square, capture is mandatory."
    },
    INTERNATIONAL("International", 10, true, true, true) {
        override fun describe(): String =
            "International draughts: 10x10, men jump forward and backward, flying kings, capture the maximum."
    };

    abstract fun describe(): String
}