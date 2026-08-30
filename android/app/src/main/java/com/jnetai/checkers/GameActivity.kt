package com.jnetai.checkers

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jnetai.checkers.game.AiDifficulty
import com.jnetai.checkers.game.AiEngine
import com.jnetai.checkers.game.GameDefs
import com.jnetai.checkers.game.GameEngine
import com.jnetai.checkers.game.Move
import com.jnetai.checkers.net.P2PManager
import com.jnetai.checkers.ui.CheckersBoardView
import com.jnetai.checkers.utils.ErrorLogger
import com.jnetai.checkers.utils.HighScoreStore
import com.jnetai.checkers.utils.SettingsManager
import com.jnetai.checkers.utils.SoundEffects
import java.util.Random
import java.util.concurrent.Executors

/**
 * GameActivity - the checkers match itself. Handles AI, two-player (same
 * device) and online (P2P) modes, plus the optional per-player clock.
 */
class GameActivity : AppCompatActivity(), P2PManager.Listener {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_AI = "ai"
        const val MODE_2P = "2p"
        const val MODE_ONLINE = "online"
        const val EXTRA_ONLINE_ROLE = "online_role"
        const val ROLE_HOST = "HOST"
        const val ROLE_CLIENT = "CLIENT"

        const val MSG_NEWGAME = "NEWGAME"

        // AI realism: minimum "thinking" time before the AI replies and the
        // time it takes the animated piece to glide across the board.
        private const val AI_MIN_THINK_MS = 1600L
        private const val AI_THINK_JITTER_MS = 1400L
        private const val AI_MOVE_ANIM_MS_PER_HOP = 650L

        private var highScorePlayerName = ""
    }

    private lateinit var engine: GameEngine
    private lateinit var boardView: CheckersBoardView
    private lateinit var settingsManager: SettingsManager

    private var mode = MODE_AI
    private var onlineRole = ROLE_HOST

    /** Who the local player controls (interactive). */
    private var localPlayer = GameDefs.BLACK

    private lateinit var tvStatus: TextView
    private lateinit var tvCaptured: TextView
    private lateinit var tvTimerBlack: TextView
    private lateinit var tvTimerWhite: TextView
    private lateinit var btnUndo: Button
    private lateinit var btnHint: Button
    private lateinit var btnResign: Button
    private lateinit var btnNewGame: Button

    private val uiHandler = Handler(Looper.getMainLooper())
    private val aiExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "checkers-ai").apply { priority = Thread.NORM_PRIORITY }
    }

    private var aiThinkStart = 0L

    // Chess clock state (ms). 0 = disabled.
    private var timerMinutes = 0
    private var blackMs = 0L
    private var whiteMs = 0L
    private var elapsedAccumMs = 0L
    private var clockRunning = false
    private val clockTicker = object : Runnable {
        override fun run() {
            if (!clockRunning) return
            tickClock(100)
            uiHandler.postDelayed(this, 100)
        }
    }

    // Undo history (AI / 2P only).
    private val history = ArrayDeque<GameEngine.EngineState>()
    private var gameOver = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        settingsManager = SettingsManager.getInstance(this)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_AI
        onlineRole = intent.getStringExtra(EXTRA_ONLINE_ROLE) ?: ROLE_HOST

        try {
            bindViews()
        } catch (e: Exception) {
            ErrorLogger.log(ErrorLogger.Codes.UI_VIEW_BINDING, "Failed to bind game views", e)
            Toast.makeText(this, "UI initialization error - E-UI-002", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupGame()

        boardView.onMoveChosen = { move ->
            onLocalMove(move)
        }
    }

    override fun onResume() {
        super.onResume()
        if (mode == MODE_ONLINE) {
            P2PManager.addListener(this)
        }
        startClock()
    }

    override fun onPause() {
        super.onPause()
        if (mode == MODE_ONLINE) {
            P2PManager.removeListener(this)
        }
        stopClock()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(clockTicker)
        try { aiExecutor.shutdownNow() } catch (_: Exception) { }
        // Tear down the PeerJS transport so a stale session can't linger.
        if (mode == MODE_ONLINE) {
            P2PManager.stop()
        }
    }

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    private fun setupGame() {
        engine = GameEngine(settingsManager.getRulePreset())
        timerMinutes = settingsManager.getTimerMinutes()

        when (mode) {
            MODE_AI -> localPlayer = GameDefs.BLACK
            MODE_2P -> localPlayer = GameDefs.BLACK
            MODE_ONLINE -> localPlayer = if (onlineRole == ROLE_HOST) GameDefs.BLACK else GameDefs.WHITE
            else -> {
                ErrorLogger.logf(ErrorLogger.Codes.GMB_INVALID_STATE, "Unknown game mode '%s'", mode)
                mode = MODE_AI
                localPlayer = GameDefs.BLACK
            }
        }

        boardView.attachEngine(engine, null)
        boardView.reverseBoard = localPlayer == GameDefs.WHITE
        boardView.setLocked(false)

        btnUndo.isEnabled = mode != MODE_ONLINE
        btnHint.isEnabled = mode == MODE_AI

        updateCapturedLabel()
        updateTimerLabels()
        updateInteractive()
        refreshStatus()
        startClock()
    }

    private fun bindViews() {
        boardView = findViewById(R.id.boardView)
        tvStatus = findViewById(R.id.tvStatus)
        tvCaptured = findViewById(R.id.tvCaptured)
        tvTimerBlack = findViewById(R.id.tvTimerBlack)
        tvTimerWhite = findViewById(R.id.tvTimerWhite)
        btnUndo = findViewById(R.id.btnUndo)
        btnHint = findViewById(R.id.btnHint)
        btnResign = findViewById(R.id.btnResign)
        btnNewGame = findViewById(R.id.btnNewGame)

        btnUndo.setOnClickListener { doUndo() }
        btnHint.setOnClickListener { doHint() }
        btnResign.setOnClickListener { doResign() }
        btnNewGame.setOnClickListener { doNewGame() }
    }

    // ------------------------------------------------------------------
    // Turns & moves
    // ------------------------------------------------------------------

    /** Who is allowed to interact with the board right now. */
    private fun updateInteractive() {
        val interactive: Int? = when (mode) {
            MODE_2P -> engine.currentPlayer
            else -> if (localPlayer != GameDefs.EMPTY && engine.currentPlayer == localPlayer) localPlayer else null
        }
        boardView.setInteractivePlayer(interactive)
        boardView.refresh()
    }

    private fun onLocalMove(move: Move) {
        if (gameOver) return
        // In 2P either side can move; in AI/online only our own side.
        if (mode != MODE_2P && engine.currentPlayer != localPlayer) return

        val state = engine.saveState()
        val captured = engine.applyMove(move)
        if (captured == null) {
            ErrorLogger.logf(ErrorLogger.Codes.GMB_ILLEGAL_MOVE,
                "Local move rejected by engine: %s", move)
            Toast.makeText(this, "Illegal move rejected", Toast.LENGTH_SHORT).show()
            boardView.refresh()
            return
        }
        history.addLast(state)
        if (history.size > 400) history.removeFirst()

        onMovePlayed(move, move.captured.isNotEmpty())
    }

    private fun onMovePlayed(move: Move, didCapture: Boolean) {
        if (didCapture) {
            SoundEffects.playCapture()
            boardView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        } else {
            SoundEffects.playMove()
        }

        updateCapturedLabel()
        boardView.highlightedSquares = listOf(move.from, move.to)
        refreshStatus()

        // Online: relay the move to the peer.
        if (mode == MODE_ONLINE) {
            P2PManager.sendMove(serializeMove(move))
        }

        val result = engine.getResult()
        if (result != GameDefs.EMPTY) {
            handleGameOver(result)
            return
        }

        if (mode == MODE_AI && engine.currentPlayer == GameDefs.WHITE) {
            runAiIfNeeded()
        } else {
            updateInteractive()
        }
    }

    private fun runAiIfNeeded() {
        if (gameOver) return
        if (mode != MODE_AI || engine.currentPlayer != GameDefs.WHITE) return

        boardView.setLocked(true)
        aiThinkStart = System.currentTimeMillis()
        tvStatus.text = getString(R.string.game_status_ai_turn)

        val diff: AiDifficulty = settingsManager.getAiDifficulty()
        aiExecutor.execute {
            val chosen: Move? = AiEngine.chooseMove(engine, GameDefs.WHITE, diff)
            // Make the AI "think" for a short, human-feeling while so the move
            // isn't instant and the player notices what happened.
            val elapsed = System.currentTimeMillis() - aiThinkStart
            val desiredThink = AI_MIN_THINK_MS + Random().nextInt(AI_THINK_JITTER_MS.toInt() + 1)
            val wait = maxOf(0L, desiredThink - elapsed)

            uiHandler.postDelayed({
                if (isDestroyed || isFinishing) return@postDelayed
                if (chosen == null) {
                    boardView.setLocked(false)
                    val r = engine.getResult()
                    if (r != GameDefs.EMPTY) handleGameOver(r)
                    return@postDelayed
                }
                val state = engine.saveState()
                val captured = engine.applyMove(chosen)
                if (captured == null) {
                    boardView.setLocked(false)
                    return@postDelayed
                }
                history.addLast(state)
                if (history.size > 400) history.removeFirst()

                // Glide the piece smoothly to its destination, then finalise.
                boardView.highlightedSquares = listOf(chosen.from, chosen.to)
                boardView.animateAiMove(chosen, AI_MOVE_ANIM_MS_PER_HOP) {
                    if (isDestroyed || isFinishing) return@animateAiMove
                    boardView.setLocked(false)
                    onMovePlayed(chosen, chosen.isJump)
                }
            }, wait)
        }
    }

    // ------------------------------------------------------------------
    // Online P2P callbacks
    // ------------------------------------------------------------------

    override fun onConnected(role: P2PManager.Role, remoteName: String) {
        uiHandler.post {
            Toast.makeText(this, "$remoteName connected", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onMoveReceived(data: String) {
        uiHandler.post {
            if (gameOver) return@post
            if (data == MSG_NEWGAME) {
                resetGame()
                Toast.makeText(this, "Opponent started a new game", Toast.LENGTH_SHORT).show()
                return@post
            }
            if (engine.currentPlayer == localPlayer) {
                ErrorLogger.logf(ErrorLogger.Codes.NET_PROTOCOL,
                    "Peer moved during our turn; ignoring (%s)", data.take(60))
                return@post
            }
            val move = deserializeMove(data)
            if (move == null) {
                ErrorLogger.logf(ErrorLogger.Codes.NET_PROTOCOL,
                    "Could not parse peer move: %s", data.take(120))
                return@post
            }
            val state = engine.saveState()
            val captured = engine.applyMove(move)
            if (captured == null) {
                ErrorLogger.logf(ErrorLogger.Codes.NET_PROTOCOL,
                    "Peer move rejected by engine: %s", data.take(120))
                Toast.makeText(this, "Invalid move from opponent", Toast.LENGTH_SHORT).show()
                return@post
            }
            history.addLast(state)
            if (history.size > 400) history.removeFirst()
            onMovePlayed(move, move.captured.isNotEmpty())
        }
    }

    override fun onPeerResigned() {
        uiHandler.post {
            Toast.makeText(this, "Opponent resigned", Toast.LENGTH_SHORT).show()
            handleGameOver(localPlayer)
        }
    }

    override fun onPeerDisconnected(reason: String) {
        uiHandler.post {
            gameOver = true
            stopClock()
            Toast.makeText(this, "Opponent disconnected", Toast.LENGTH_LONG).show()
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.back_to_menu))
                .setMessage(reason)
                .setCancelable(false)
                .setPositiveButton("OK") { _, _ -> finish() }
                .show()
        }
    }

    override fun onError(errorCode: String, message: String) {
        uiHandler.post {
            Toast.makeText(this, "Network error: $message", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------
    // Move serialization for online play
    // ------------------------------------------------------------------

    private fun serializeMove(move: Move): String {
        return buildString {
            append(move.from)
            append('|')
            append(move.path.joinToString(","))
            append('|')
            append(move.captured.joinToString(","))
        }
    }

    private fun deserializeMove(data: String): Move? {
        return try {
            val parts = data.trim().split("|")
            if (parts.size < 2) return null
            val from = parts[0].toIntOrNull() ?: return null
            val path = parts[1].split(",").filter { it.isNotBlank() }.mapNotNull { it.toIntOrNull() }
            if (path.isEmpty()) return null
            val captured = if (parts.size >= 3 && parts[2].isNotBlank()) {
                parts[2].split(",").filter { it.isNotBlank() }.mapNotNull { it.toIntOrNull() }
            } else {
                emptyList()
            }
            Move(from, path, captured)
        } catch (e: Exception) {
            ErrorLogger.logf(ErrorLogger.Codes.NET_PROTOCOL,
                "Failed to deserialize move '%s'", e, data.take(100))
            null
        }
    }

    // ------------------------------------------------------------------
    // Timer
    // ------------------------------------------------------------------

    private fun startClock() {
        if (timerMinutes <= 0) {
            updateTimerLabels()
            return
        }
        if (clockRunning) return
        clockRunning = true
        uiHandler.removeCallbacks(clockTicker)
        uiHandler.post(clockTicker)
    }

    private fun stopClock() {
        clockRunning = false
        uiHandler.removeCallbacks(clockTicker)
    }

    private fun tickClock(deltaMs: Long) {
        if (gameOver || timerMinutes <= 0) {
            stopClock()
            return
        }
        elapsedAccumMs += deltaMs
        val active = engine.currentPlayer
        if (active == GameDefs.BLACK) {
            blackMs = (blackMs - deltaMs).coerceAtLeast(0)
            if (blackMs <= 0) handleTimeUp(GameDefs.BLACK)
        } else {
            whiteMs = (whiteMs - deltaMs).coerceAtLeast(0)
            if (whiteMs <= 0) handleTimeUp(GameDefs.WHITE)
        }
        updateTimerLabels()
    }

    private fun handleTimeUp(player: Int) {
        gameOver = true
        stopClock()
        val winner = GameDefs.opponent(player)
        val loser = if (player == GameDefs.BLACK) "Black" else "White"
        val subtitle: String
        val title: String
        when {
            mode == MODE_AI && winner == GameDefs.BLACK -> {
                title = getString(R.string.game_you_won)
                subtitle = "AI ran out of time."
            }
            mode == MODE_AI && winner == GameDefs.WHITE -> {
                title = getString(R.string.game_you_lost)
                subtitle = "You ran out of time."
            }
            else -> {
                title = if (winner == GameDefs.BLACK) getString(R.string.game_black_wins)
                else getString(R.string.game_white_wins)
                subtitle = "$loser ran out of time."
            }
        }
        if (mode == MODE_ONLINE) P2PManager.sendResign()
        handleWinFlow(winner, "$title\n$subtitle")
    }

    private fun updateTimerLabels() {
        if (timerMinutes <= 0) {
            tvTimerBlack.text = "--:--"
            tvTimerWhite.text = "--:--"
            tvTimerBlack.alpha = 0.4f
            tvTimerWhite.alpha = 0.4f
            return
        }
        val budget = timerMinutes * 60_000L
        if (blackMs <= 0) blackMs = budget
        if (whiteMs <= 0) whiteMs = budget
        tvTimerBlack.text = formatClock(blackMs)
        tvTimerWhite.text = formatClock(whiteMs)
        tvTimerBlack.alpha = if (engine.currentPlayer == GameDefs.BLACK) 1f else 0.55f
        tvTimerWhite.alpha = if (engine.currentPlayer == GameDefs.WHITE) 1f else 0.55f
    }

    private fun formatClock(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val m = totalSec / 60
        val s = totalSec % 60
        return "$m:${if (s < 10) "0" else ""}$s"
    }

    // ------------------------------------------------------------------
    // End of game & high scores
    // ------------------------------------------------------------------

    private fun handleGameOver(winner: Int) {
        if (gameOver) return
        gameOver = true
        stopClock()

        val title: String
        val note: String
        when (winner) {
            GameDefs.BLACK -> {
                title = if (mode == MODE_AI) getString(R.string.game_you_won)
                else getString(R.string.game_black_wins)
                note = if (mode == MODE_ONLINE) "Black wins!" else ""
            }
            GameDefs.WHITE -> {
                title = if (mode == MODE_AI) getString(R.string.game_you_lost)
                else getString(R.string.game_white_wins)
                note = if (mode == MODE_ONLINE) "White wins!" else ""
            }
            else -> {
                title = getString(R.string.game_draw)
                note = getString(R.string.no_winner_draw)
            }
        }

        handleWinFlow(winner, "$title\n${note.trim()}".trim())
    }

    private fun handleWinFlow(winner: Int, message: String) {
        // High score only when the human beats the AI offline.
        if (mode == MODE_AI && winner == GameDefs.BLACK) {
            promptHighScoreSave(message)
        } else {
            showGameEndDialog(message, null)
        }
    }

    private fun promptHighScoreSave(title: String) {
        val moves = engine.moveCount
        val seconds = elapsedSeconds()
        val diff = settingsManager.getAiDifficulty()
        val score = HighScoreStore.computeScore(moves, seconds, diff)

        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.hint = getString(R.string.enter_name_hint)
        input.maxLines = 1
        input.setText(highScorePlayerName)

        val pad = (24 * resources.displayMetrics.density).toInt()
        input.setPadding(pad, pad, pad, pad)

        val timeText = if (timerMinutes > 0) {
            "\nTime: " + formatClock(elapsedAccumMs)
        } else ""

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(getString(R.string.your_score_is, score) + timeText)
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.save_score)) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Player" }
                highScorePlayerName = name
                val entry = HighScoreStore.ScoreEntry(
                    name = name,
                    score = score,
                    moves = moves,
                    seconds = seconds,
                    difficulty = diff.name,
                    timestamp = System.currentTimeMillis()
                )
                HighScoreStore.getInstance(this).addEntry(entry)
                Toast.makeText(this, "Score saved! See the High Score board.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(getString(R.string.back_to_menu)) { _, _ -> finish() }
            .show()
    }

    private fun showGameEndDialog(message: String, note: String?) {
        val b = AlertDialog.Builder(this)
            .setTitle(message)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.new_game)) { _, _ -> doNewGame() }
            .setNegativeButton(getString(R.string.back_to_menu)) { _, _ -> finish() }
        if (note != null) b.setMessage(note)
        b.show()
    }

    private fun elapsedSeconds(): Int {
        return if (timerMinutes > 0) (elapsedAccumMs / 1000).toInt() else 0
    }

    // ------------------------------------------------------------------
    // Controls
    // ------------------------------------------------------------------

    private fun doUndo() {
        if (mode == MODE_ONLINE) {
            Toast.makeText(this, "Undo is disabled in online matches", Toast.LENGTH_SHORT).show()
            return
        }
        if (gameOver) return
        // AI mode: roll back both the human move and the AI reply.
        val count = if (mode == MODE_AI) 2 else 1
        var undone = 0
        while (undone < count && history.isNotEmpty()) {
            engine.restoreState(history.removeLast())
            undone++
        }
        if (undone > 0) {
            boardView.highlightedSquares = emptyList()
            refreshStatus()
            updateCapturedLabel()
            updateInteractive()
            if (mode == MODE_AI && engine.currentPlayer == GameDefs.WHITE) {
                runAiIfNeeded()
            } else {
                updateInteractive()
            }
        }
    }

    private fun doHint() {
        if (mode != MODE_AI || gameOver) return
        if (engine.currentPlayer != GameDefs.BLACK) return

        aiExecutor.execute {
            val hintMove = AiEngine.chooseMove(engine, GameDefs.BLACK, settingsManager.getAiDifficulty())
            uiHandler.post {
                if (isDestroyed || isFinishing || hintMove == null) return@post
                boardView.highlightedSquares = listOf(hintMove.from, hintMove.to)
                boardView.refresh()
                uiHandler.postDelayed({
                    boardView.highlightedSquares = emptyList()
                    boardView.refresh()
                }, 1800)
            }
        }
    }

    private fun doResign() {
        if (gameOver) return
        gameOver = true
        stopClock()

        val winner: Int
        when (mode) {
            MODE_AI -> winner = GameDefs.WHITE
            MODE_ONLINE -> {
                P2PManager.sendResign()
                winner = GameDefs.opponent(localPlayer)
            }
            else -> winner = GameDefs.opponent(engine.currentPlayer)
        }
        handleGameOver(winner)
    }

    private fun doNewGame() {
        if (mode == MODE_ONLINE) {
            P2PManager.sendMove(MSG_NEWGAME)
        }
        resetGame()
    }

    private fun resetGame() {
        gameOver = false
        history.clear()
        engine.reset()
        blackMs = 0L
        whiteMs = 0L
        elapsedAccumMs = 0L
        boardView.highlightedSquares = emptyList()
        updateCapturedLabel()
        updateTimerLabels()
        updateInteractive()
        refreshStatus()
        startClock()
        if (mode == MODE_AI && engine.currentPlayer == GameDefs.WHITE) {
            runAiIfNeeded()
        }
    }

    private fun updateCapturedLabel() {
        val b = engine.getCapturedBlack()
        val w = engine.getCapturedWhite()
        tvCaptured.text = "Move ${engine.moveCount}\t•\t⚫ $b   ⚪ $w"
    }

    private fun refreshStatus() {
        when {
            gameOver -> return
            mode == MODE_AI -> {
                tvStatus.text = if (engine.currentPlayer == GameDefs.BLACK) {
                    getString(R.string.game_status_your_turn, settingsManager.getAiDifficulty().displayName)
                } else {
                    getString(R.string.game_status_ai_turn)
                }
            }
            mode == MODE_ONLINE -> {
                tvStatus.text = if (engine.currentPlayer == localPlayer) "Your turn" else "Opponent's turn"
            }
            else -> {
                tvStatus.text = if (engine.currentPlayer == GameDefs.BLACK) {
                    getString(R.string.game_status_black_turn)
                } else {
                    getString(R.string.game_status_white_turn)
                }
            }
        }
    }
}