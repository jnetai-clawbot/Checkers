package com.jnetai.checkers.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.jnetai.checkers.game.GameDefs
import com.jnetai.checkers.game.GameEngine
import com.jnetai.checkers.game.Move
import com.jnetai.checkers.utils.ErrorLogger

/**
 * CheckersBoardView - draws the board and pieces and handles touch input.
 *
 * Rebuilds legal move maps on every state change so the UI can never show an
 * illegal destination.
 */
class CheckersBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var engine: GameEngine? = null

    /** The player allowed to move with touch (GameDefs.BLACK / GameDefs.WHITE), or null when locked. */
    private var interactivePlayer: Int? = null

    /** Squares to draw with a highlight ring (used for hints / last move). */
    var highlightedSquares: List<Int> = emptyList()

    var onMoveChosen: ((Move) -> Unit)? = null

    private var selectedSquare: Int? = null
    private var selectableSquares: MutableSet<Int> = mutableSetOf()
    private val moveByTarget: MutableMap<Int, Move> = mutableMapOf()

    /** Pieces of the interactive side that have at least one legal move right now. */
    private var selectablePieces: MutableSet<Int> = mutableSetOf()

    /** True when the interactive side is under a compulsory capture. */
    private var captureMandatory = false

    private val lightPaint = Paint().apply { color = Color.rgb(240, 217, 181) }
    private val darkPaint = Paint().apply { color = Color.rgb(181, 136, 99) }
    private val darkAltPaint = Paint().apply { color = Color.rgb(165, 113, 78) }
    private val blackRingPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.rgb(20, 20, 22)
    }
    private val whiteFillPaint = Paint().apply { color = Color.rgb(245, 240, 230) }
    private val whiteEdgePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.rgb(191, 179, 160)
    }
    private val blackFillPaint = Paint().apply { color = Color.rgb(28, 28, 30) }
    private val blackEdgePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.rgb(10, 10, 10)
    }
    private val kingMarkPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.rgb(255, 193, 7)
    }
    private val selectedPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        color = Color.rgb(100, 255, 218)
    }
    private val targetPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.rgb(0, 230, 118)
    }
    private val highlightPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.rgb(255, 193, 7)
    }
    private val movableRingPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.rgb(0, 200, 235)
    }
    private val captureRingPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f
        color = Color.rgb(255, 96, 96)
    }
    private val boardBgPaint = Paint().apply { color = Color.rgb(27, 27, 27) }

    private var squareSize = 0f
    private var boardLeft = 0f
    private var boardTop = 0f

    /** When true the board is drawn upside-down (used when the local player is WHITE). */
    var reverseBoard: Boolean = false

    init {
        isFocusable = true
        isClickable = true
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun attachEngine(g: GameEngine, interactive: Int?) {
        engine = g
        interactivePlayer = interactive
        clearSelection()
        invalidate()
    }

    /** Freeze / allow input for a given side. */
    fun setInteractivePlayer(player: Int?) {
        interactivePlayer = player
        if (player == null) clearSelection()
        invalidate()
    }

    fun clearSelection() {
        selectedSquare = null
        selectableSquares = mutableSetOf()
        moveByTarget.clear()
        selectablePieces = mutableSetOf()
    }

    fun refresh() {
        rebuildSelectables()
        invalidate()
    }

    private fun rebuildSelectables() {
        val g = engine ?: return
        val player = interactivePlayer ?: return
        moveByTarget.clear()
        selectableSquares = mutableSetOf()
        selectablePieces = mutableSetOf()

        try {
            val gen = g.generateMoves(player)
            captureMandatory = gen.hasCapture
            for (m in gen.allLegal) {
                selectablePieces.add(m.from)
                selectableSquares.add(m.to)
                for (landing in m.path) {
                    moveByTarget[landing] = m
                }
            }
        } catch (e: Exception) {
            ErrorLogger.log(ErrorLogger.Codes.GMB_INVALID_STATE,
                "Failed to rebuild selectable squares", e)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = minOf(w, h)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val g = engine ?: return
        val n = g.size

        val vw = width.toFloat()
        val vh = height.toFloat()
        squareSize = minOf(vw, vh) / n
        boardLeft = (vw - squareSize * n) / 2f
        boardTop = (vh - squareSize * n) / 2f

        canvas.drawRect(boardLeft, boardTop, boardLeft + squareSize * n, boardTop + squareSize * n, boardBgPaint)

        // Draw the checkered squares (dark squares are playable).
        for (row in 0 until n) {
            for (col in 0 until n) {
                if ((row + col) % 2 == 0) continue
                val l = boardLeft + col * squareSize
                val t = boardTop + row * squareSize
                val p = if ((row == 0 || row == n - 1) && (col == 0 || col == n - 1)) darkAltPaint else darkPaint
                canvas.drawRect(l, t, l + squareSize, t + squareSize, p)
            }
        }

        // Selected square highlight.
        val sel = selectedSquare
        if (sel != null) {
            val r = scrRow(g, rowOfSquare(g, sel))
            val c = colOfSquare(g, sel)
            val l = boardLeft + c * squareSize
            val t = boardTop + r * squareSize
            canvas.drawRect(l, t, l + squareSize, t + squareSize, selectedPaint)
        }

        // Valid target dots.
        for (sq in selectableSquares) {
            val r = scrRow(g, rowOfSquare(g, sq))
            val c = colOfSquare(g, sq)
            val cx = boardLeft + c * squareSize + squareSize / 2
            val cy = boardTop + r * squareSize + squareSize / 2
            canvas.drawCircle(cx, cy, squareSize * 0.14f, targetPaint)
        }

        // Hint / last-move highlights.
        for (sq in highlightedSquares) {
            val r = scrRow(g, rowOfSquare(g, sq))
            val c = colOfSquare(g, sq)
            val l = boardLeft + c * squareSize
            val t = boardTop + r * squareSize
            canvas.drawRect(l, t, l + squareSize, t + squareSize, highlightPaint)
        }

        // Pieces.
        for (sq in 0 until n * n) {
            val piece = g.pieceAt(sq)
            if (piece == GameDefs.EMPTY) continue
            drawPiece(canvas, g, sq, piece)
        }
    }

    private fun drawPiece(canvas: Canvas, g: GameEngine, sq: Int, piece: Int) {
        val r = scrRow(g, rowOfSquare(g, sq))
        val c = colOfSquare(g, sq)
        val cx = boardLeft + c * squareSize + squareSize / 2
        val cy = boardTop + r * squareSize + squareSize / 2
        val radius = squareSize * 0.38f

        val fill: Paint
        val edge: Paint
        if (GameDefs.owner(piece) == GameDefs.BLACK) {
            fill = blackFillPaint
            edge = blackEdgePaint
        } else {
            fill = whiteFillPaint
            edge = whiteEdgePaint
        }

        canvas.drawCircle(cx, cy, radius, fill)
        canvas.drawCircle(cx, cy, radius, edge)

        if (sq in selectablePieces) {
            // Only pieces with a legal move are selectable. Show a red ring when
            // a capture is compulsory, otherwise a subtle blue ring.
            if (captureMandatory) {
                canvas.drawCircle(cx, cy, radius + 3f, captureRingPaint)
            } else {
                canvas.drawCircle(cx, cy, radius + 2f, movableRingPaint)
            }
        }

        if (GameDefs.isKing(piece)) {
            // Simple crown marker: inner ring plus a highlight dot.
            canvas.drawCircle(cx, cy, radius * 0.55f, kingMarkPaint)
            canvas.drawLine(cx, cy - radius * 0.4f, cx, cy + radius * 0.4f, kingMarkPaint)
            canvas.drawLine(cx - radius * 0.4f, cy, cx + radius * 0.4f, cy, kingMarkPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return false
        val g = engine ?: return false
        val player = interactivePlayer ?: return false
        if (gameLocked.get()) return true

        val col = ((event.x - boardLeft) / squareSize).toInt()
        var row = ((event.y - boardTop) / squareSize).toInt()
        if (reverseBoard && g.size > 0) row = g.size - 1 - row
        if (row !in 0 until g.size || col !in 0 until g.size) return true
        if ((row + col) % 2 == 0) {
            // light square, nothing playable
            tapNoop()
            return true
        }
        val sq = row * g.size + col
        handleTap(sq)
        return true
    }

    private val gameLocked = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Lock input (e.g. while the AI is moving). */
    fun setLocked(locked: Boolean) {
        gameLocked.set(locked)
        if (locked) clearSelection()
    }

    private fun handleTap(sq: Int) {
        val g = engine ?: return
        val player = interactivePlayer ?: return

        // Tap a valid target for the selected piece => perform the move.
        val move = moveByTarget[sq]
        if (move != null && selectedSquare != null) {
            val chosen = move
            clearSelection()
            onMoveChosen?.invoke(chosen)
            return
        }

        // Select / reselect own piece. A piece with no legal move in the
        // current position (e.g. one that cannot capture while a capture is
        // compulsory) cannot be selected.
        val piece = g.pieceAt(sq)
        if (piece != GameDefs.EMPTY && GameDefs.owner(piece) == player) {
            if (selectedSquare == sq) {
                clearSelection()
            } else if (sq in selectablePieces) {
                selectedSquare = sq
                rebuildTargetsFor(piece)
            } else {
                clearSelection()
            }
            invalidate()
            return
        }

        // Everything else deselects.
        clearSelection()
        invalidate()
    }

    private fun rebuildTargetsFor(piece: Int) {
        val g = engine ?: return
        val player = interactivePlayer ?: return
        val sel = selectedSquare ?: return
        moveByTarget.clear()
        selectableSquares = mutableSetOf()

        val gen = g.generateMoves(player)
        captureMandatory = gen.hasCapture
        for (m in gen.allLegal) {
            if (m.from != sel) continue
            for (landing in m.path) {
                moveByTarget[landing] = m
                selectableSquares.add(landing)
            }
        }
    }

    private fun tapNoop() {
        // intentionally silent
    }

    private fun rowOfSquare(g: GameEngine, sq: Int): Int = sq / g.size
    private fun colOfSquare(g: GameEngine, sq: Int): Int = sq % g.size
    private fun scrRow(g: GameEngine, row: Int): Int =
        if (reverseBoard) g.size - 1 - row else row
}