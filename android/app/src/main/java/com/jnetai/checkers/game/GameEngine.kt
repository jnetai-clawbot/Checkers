package com.jnetai.checkers.game

import com.jnetai.checkers.utils.ErrorLogger

/**
 * GameEngine - full checkers board, move generation, and turn logic.
 *
 * Supports all rule presets (UK/Europe, US, International) with a single
 * implementation. Every illegal request is logged with a diagnostic error
 * code instead of failing silently.
 */
class GameEngine(val rules: RulePreset) {

    val size: Int = rules.boardSize
    private val board: IntArray = IntArray(size * size)

    var currentPlayer: Int = GameDefs.BLACK
        internal set

    /** Total number of completed ply (half-moves). */
    var moveCount: Int = 0
        private set

    /** Number of consecutive ply (both sides) without a capture since last capture. */
    var noCapturePly: Int = 0
        private set

    private var capturedBlack = 0
    private var capturedWhite = 0

    init {
        reset()
    }

    fun reset() {
        for (i in board.indices) board[i] = GameDefs.EMPTY
        currentPlayer = GameDefs.BLACK
        moveCount = 0
        noCapturePly = 0
        capturedBlack = 0
        capturedWhite = 0
        setupInitialPosition()
    }

    private fun setupInitialPosition() {
        val rowsPerSide = size / 2 - 1
        for (row in 0 until rowsPerSide) {
            for (col in 0 until size) {
                if ((row + col) % 2 == 1) {
                    board[idx(row, col)] = GameDefs.WHITE
                }
            }
        }
        for (row in (size - rowsPerSide) until size) {
            for (col in 0 until size) {
                if ((row + col) % 2 == 1) {
                    board[idx(row, col)] = GameDefs.BLACK
                }
            }
        }
    }

    fun idx(row: Int, col: Int): Int = row * size + col
    fun rowOf(sq: Int): Int = sq / size
    fun colOf(sq: Int): Int = sq % size

    fun pieceAt(sq: Int): Int {
        if (sq < 0 || sq >= board.size) {
            ErrorLogger.logf(ErrorLogger.Codes.GMB_INVALID_SQUARE,
                "pieceAt called with out-of-range square %d (board size %d)", sq, size)
            return GameDefs.EMPTY
        }
        return board[sq]
    }

    fun pieceAt(row: Int, col: Int): Int {
        if (row !in 0 until size || col !in 0 until size) return GameDefs.EMPTY
        return board[idx(row, col)]
    }

    fun placePiece(sq: Int, piece: Int) {
        if (sq in board.indices) board[sq] = piece
    }

    fun getCapturedBlack(): Int = capturedBlack
    fun getCapturedWhite(): Int = capturedWhite

    private fun inBounds(r: Int, c: Int): Boolean = r in 0 until size && c in 0 until size

    // ------------------------------------------------------------------
    // Move generation
    // ------------------------------------------------------------------

    /**
     * Generate all legal moves for [player]. If any capture chains exist they
     * are the only legal moves (mandatory capture). With the international
     * majority rule only the longest chains are returned.
     */
    fun generateMoves(player: Int): GeneratedMoves {
        val simple = mutableListOf<Move>()
        val jumps = mutableListOf<Move>()

        for (sq in board.indices) {
            val piece = board[sq]
            if (piece == GameDefs.EMPTY || GameDefs.owner(piece) != player) continue
            genSimple(sq, piece, simple)
            genJumpChains(sq, piece, jumps)
        }

        val jumpsResult = if (rules.majorityCapture && jumps.isNotEmpty()) {
            val maxLen = jumps.maxOfOrNull { it.jumpsCount } ?: 0
            jumps.filter { it.jumpsCount == maxLen }
        } else {
            jumps
        }
        return GeneratedMoves(simple, jumpsResult)
    }

    /**
     * Non-capuring moves. Men always move one square forward diagonally;
     * kings move one diagonal step, or slide any distance with flying rules.
     */
    private fun genSimple(sq: Int, piece: Int, out: MutableList<Move>) {
        val r = rowOf(sq)
        val c = colOf(sq)
        if (GameDefs.isKing(piece)) {
            for ((dr, dc) in DIRS) {
                if (rules.flyingKings) {
                    var nr = r + dr
                    var nc = c + dc
                    while (inBounds(nr, nc)) {
                        if (board[idx(nr, nc)] != GameDefs.EMPTY) break
                        out.add(Move(sq, listOf(idx(nr, nc))))
                        nr += dr
                        nc += dc
                    }
                } else {
                    val nr = r + dr
                    val nc = c + dc
                    if (inBounds(nr, nc) && board[idx(nr, nc)] == GameDefs.EMPTY) {
                        out.add(Move(sq, listOf(idx(nr, nc))))
                    }
                }
            }
        } else {
            // Men only move forward.
            val dr = if (GameDefs.owner(piece) == GameDefs.BLACK) -1 else 1
            for (dc in intArrayOf(-1, 1)) {
                val nr = r + dr
                val nc = c + dc
                if (inBounds(nr, nc) && board[idx(nr, nc)] == GameDefs.EMPTY) {
                    out.add(Move(sq, listOf(idx(nr, nc))))
                }
            }
        }
    }

    /** Generate maximal jump chains for the piece on [sq]. */
    private fun genJumpChains(sq: Int, piece: Int, out: MutableList<Move>) {
        recJump(sq, sq, piece, mutableListOf(), mutableListOf(), out, board.copyOf())
    }

    /**
     * Recursive jump-chain builder.
     *
     * @param origin the square the piece started the sequence from
     * @param cur current square of the capturing piece
     * @param piece the piece value (may be promoted king during chain)
     * @param captured squares removed so far in this chain
     * @param path landing squares so far
     * @param out result accumulator
     * @param working mutable snapshot of the board that evolves through the chain
     */
    private fun recJump(
        origin: Int,
        cur: Int,
        piece: Int,
        captured: MutableList<Int>,
        path: MutableList<Int>,
        out: MutableList<Move>,
        working: IntArray
    ) {
        val r = rowOf(cur)
        val c = colOf(cur)
        val isKingPiece = GameDefs.isKing(piece)
        val owner = GameDefs.owner(piece)
        val manForwardDr = if (owner == GameDefs.BLACK) -1 else 1

        // Gather (landing, captured) pairs for the current square.
        val targets = mutableListOf<Pair<Int, Int>>()
        for ((dr, dc) in DIRS) {
            // For a man the capture must move forward unless backward captures enabled.
            if (!isKingPiece && !rules.menCaptureBackward && dr != manForwardDr) continue

            if (rules.flyingKings) {
                // Flying kings: the first occupied piece along the ray must be an
                // enemy; every empty square beyond it is a legal landing square.
                var nr = r + dr
                var nc = c + dc
                while (inBounds(nr, nc)) {
                    val enemySq = idx(nr, nc)
                    val cell = working[enemySq]
                    if (cell != GameDefs.EMPTY) {
                        if (GameDefs.owner(cell) != owner) {
                            var lr = nr + dr
                            var lc = nc + dc
                            while (inBounds(lr, lc) && working[idx(lr, lc)] == GameDefs.EMPTY) {
                                targets.add(Pair(idx(lr, lc), enemySq))
                                lr += dr
                                lc += dc
                            }
                        }
                        break
                    }
                    nr += dr
                    nc += dc
                }
            } else {
                // Non-flying (men and kings): the enemy must sit on the immediately
                // adjacent diagonal square and the landing square must be empty.
                val enemyR = r + dr
                val enemyC = c + dc
                if (!inBounds(enemyR, enemyC)) continue
                val enemySq = idx(enemyR, enemyC)
                val cell = working[enemySq]
                if (cell == GameDefs.EMPTY || GameDefs.owner(cell) == owner) continue
                val landingR = enemyR + dr
                val landingC = enemyC + dc
                if (!inBounds(landingR, landingC)) continue
                val landingSq = idx(landingR, landingC)
                if (working[landingSq] == GameDefs.EMPTY) {
                    targets.add(Pair(landingSq, enemySq))
                }
            }
        }

        if (targets.isEmpty()) {
            if (path.isNotEmpty()) {
                out.add(Move(origin, path.toList(), captured.toList()))
            }
            return
        }

        for ((landing, capturedSq) in targets) {
            if (working[landing] != GameDefs.EMPTY) continue

            val nextBoard = working.copyOf()
            nextBoard[cur] = GameDefs.EMPTY
            nextBoard[capturedSq] = GameDefs.EMPTY
            nextBoard[landing] = piece

            val nextPath = path.toMutableList()
            nextPath.add(landing)
            val nextCaptured = captured.toMutableList()
            nextCaptured.add(capturedSq)

            val promotesNow = !isKingPiece &&
                    rowOf(landing) == GameDefs.promotionRowFor(owner, size)

            if (promotesNow) {
                // English rule: a man reaching the back row during a jump stops and promotes.
                out.add(Move(origin, nextPath, nextCaptured))
            } else {
                recJump(origin, landing, piece, nextCaptured, nextPath, out, nextBoard)
            }
        }
    }

    /**
     * Apply a fully generated move. Returns captured squares, or null when the
     * move does not match a generated legal move.
     */
    fun applyMove(move: Move): List<Int>? {
        val player = currentPlayer
        val gen = generateMoves(player)
        val valid = gen.allLegal.any {
            it.from == move.from && it.path == move.path &&
                    it.captured == move.captured
        }
        if (!valid) {
            ErrorLogger.logf(ErrorLogger.Codes.GMB_APPLY_INVALID,
                "Attempted to apply an ungenerated move: %s", move)
            return null
        }

        val fromPiece = board[move.from]
        if (fromPiece == GameDefs.EMPTY) {
            ErrorLogger.logf(ErrorLogger.Codes.GMB_APPLY_INVALID,
                "Move origin %d is empty", move.from)
            return null
        }

        for (c in move.captured) {
            val p = board[c]
            if (p != GameDefs.EMPTY) {
                if (GameDefs.owner(p) == GameDefs.BLACK) capturedBlack++
                else capturedWhite++
                board[c] = GameDefs.EMPTY
            }
        }

        board[move.from] = GameDefs.EMPTY
        board[move.to] = fromPiece

        if (rowOf(move.to) == GameDefs.promotionRowFor(player, size) && board[move.to] == player) {
            board[move.to] = GameDefs.makeKing(player)
        }

        moveCount++
        if (move.captured.isNotEmpty()) {
            noCapturePly = 0
        } else {
            noCapturePly++
        }

        currentPlayer = GameDefs.opponent(player)

        return move.captured
    }

    /**
     * Result of the game: GameDefs.WHITE / GameDefs.BLACK for a win, EMPTY for
     * ongoing, DRAW_RESULT for a draw.
     */
    fun getResult(): Int {
        val blackPieces = countPieces(GameDefs.BLACK)
        val whitePieces = countPieces(GameDefs.WHITE)
        if (blackPieces == 0) return GameDefs.WHITE
        if (whitePieces == 0) return GameDefs.BLACK

        if (generateMoves(GameDefs.BLACK).allLegal.isEmpty()) return GameDefs.WHITE
        if (generateMoves(GameDefs.WHITE).allLegal.isEmpty()) return GameDefs.BLACK

        if (noCapturePly >= MAX_NO_CAPTURE_PLY) return DRAW_RESULT
        if (moveCount >= MAX_MOVE_COUNT) return DRAW_RESULT

        return GameDefs.EMPTY
    }

    fun countPieces(player: Int): Int {
        var n = 0
        for (p in board) {
            if (p != GameDefs.EMPTY && GameDefs.owner(p) == player) n++
        }
        return n
    }

    fun snapshotBoard(): IntArray = board.copyOf()

    /** Read access to the live board array (AI evaluation read-only usage). */
    fun boardFacade(): IntArray = board

    fun restoreBoard(snapshot: IntArray) {
        if (snapshot.size != board.size) {
            ErrorLogger.logf(ErrorLogger.Codes.GMB_RESTORE_MISMATCH,
                "Board snapshot size %d does not match %d", snapshot.size, board.size)
            return
        }
        System.arraycopy(snapshot, 0, board, 0, board.size)
    }

    /** Full engine state needed to undo a move during AI search. */
    data class EngineState(
        val board: IntArray,
        val currentPlayer: Int,
        val moveCount: Int,
        val noCapturePly: Int,
        val capturedBlack: Int,
        val capturedWhite: Int
    )

    fun saveState(): EngineState = EngineState(
        board.copyOf(), currentPlayer, moveCount, noCapturePly, capturedBlack, capturedWhite
    )

    fun restoreState(state: EngineState) {
        restoreBoard(state.board)
        currentPlayer = state.currentPlayer
        moveCount = state.moveCount
        noCapturePly = state.noCapturePly
        capturedBlack = state.capturedBlack
        capturedWhite = state.capturedWhite
    }

    companion object {
        const val DRAW_RESULT = 99
        const val MAX_NO_CAPTURE_PLY = 80
        const val MAX_MOVE_COUNT = 400

        private val DIRS = arrayOf(
            -1 to -1, -1 to 1, 1 to -1, 1 to 1
        )
    }
}