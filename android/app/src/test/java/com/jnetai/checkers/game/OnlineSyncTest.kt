package com.jnetai.checkers.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Reproduces the online move-exchange flow between two peer engines to verify
 * both boards always stay synchronised. Mirrors GameActivity.onLocalMove /
 * onMoveReceived / serialize & deserialize behaviour so a regression (missing
 * opponent pieces on one side) gets caught here.
 */
class OnlineSyncTest {

    private fun boardString(e: GameEngine): String = e.snapshotBoard().joinToString(",")

    private fun serializeMove(move: Move): String {
        val sb = StringBuilder()
        sb.append(move.from).append('|')
        sb.append(move.path.joinToString(",")).append('|')
        sb.append(move.captured.joinToString(","))
        return sb.toString()
    }

    private fun deserializeMove(data: String): Move? {
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
        return Move(from, path, captured)
    }

    /**
     * Faithful simulation of the current app behaviour (peer relays a move,
     * the receiving side re-broadcasts it too - the "echo").
     */
    private fun runEchoGame(maxMoves: Int) {
        val host = GameEngine(RulePreset.UK_EUROPE)   // local player BLACK
        val client = GameEngine(RulePreset.UK_EUROPE) // local player WHITE
        val queued: Map<GameEngine, ArrayDeque<String>> = mapOf(
            host to ArrayDeque(), client to ArrayDeque()
        )

        fun deliver(to: GameEngine, colour: Int): Boolean {
            var applied = false
            val q = queued.getValue(to)
            val isHostSide = to === host
            while (q.isNotEmpty()) {
                val raw = q.removeFirst()
                val infinitelyLooping = false
                // Guard: ignore if it's our turn.
                if (to.currentPlayer == colour) continue
                val move = deserializeMove(raw) ?: continue
                val captured = to.applyMove(move)
                if (captured != null) {
                    // onMovePlayed re-broadcasts the move back to the sender.
                    queued.getValue(if (isHostSide) client else host).addLast(serializeMove(move))
                    applied = true
                }
                if (infinitelyLooping) break
            }
            return applied
        }

        var moves = 0
        while (moves < maxMoves) {
            // Whoever's turn it is: first absorb any inbound messages.
            if (host.currentPlayer == GameDefs.BLACK) {
                deliver(host, GameDefs.BLACK)
                val legal = host.generateMoves(GameDefs.BLACK).allLegal
                if (legal.isEmpty()) break
                host.applyMove(legal.first())
                queued.getValue(client).addLast(serializeMove(legal.first()))
            } else {
                deliver(client, GameDefs.WHITE)
                val legal = client.generateMoves(GameDefs.WHITE).allLegal
                if (legal.isEmpty()) break
                client.applyMove(legal.first())
                queued.getValue(host).addLast(serializeMove(legal.first()))
            }

            moves++
            // Let the opponent process the freshly relayed move (+ its echo).
            val r1 = if (host.currentPlayer == GameDefs.WHITE) deliver(client, GameDefs.WHITE) else false
            val r2 = if (client.currentPlayer == GameDefs.BLACK) deliver(host, GameDefs.BLACK) else false

            if (boardString(host) != boardString(client) || host.currentPlayer != client.currentPlayer) {
                dump(host, client)
                throw AssertionError("BOARDS DIVERGED after $moves moves")
            }
        }

        assertEquals(boardString(host), boardString(client))
        assertEquals(host.currentPlayer, client.currentPlayer)
    }

    @Test
    fun `echo protocol keeps both boards synchronised`() {
        runEchoGame(400)
    }

    /** Clean protocol (no echo) - the intended behaviour after the fix. */
    private fun runCleanGame(maxMoves: Int) {
        val host = GameEngine(RulePreset.UK_EUROPE)
        val client = GameEngine(RulePreset.UK_EUROPE)
        val queued: Map<GameEngine, ArrayDeque<String>> = mapOf(
            host to ArrayDeque(), client to ArrayDeque()
        )

        fun deliver(to: GameEngine, colour: Int) {
            val q = queued.getValue(to)
            while (q.isNotEmpty()) {
                val raw = q.removeFirst()
                if (to.currentPlayer == colour) continue // our turn - ignore (should not happen)
                val move = deserializeMove(raw) ?: continue
                assertNotNull(to.applyMove(move)) // must always succeed when clean
            }
        }

        var moves = 0
        while (moves < maxMoves) {
            if (host.currentPlayer == GameDefs.BLACK) {
                deliver(host, GameDefs.BLACK)
                val legal = host.generateMoves(GameDefs.BLACK).allLegal
                if (legal.isEmpty()) break
                host.applyMove(legal.first())
                queued.getValue(client).addLast(serializeMove(legal.first()))
            } else {
                deliver(client, GameDefs.WHITE)
                val legal = client.generateMoves(GameDefs.WHITE).allLegal
                if (legal.isEmpty()) break
                client.applyMove(legal.first())
                queued.getValue(host).addLast(serializeMove(legal.first()))
            }
            moves++
            if (host.currentPlayer == GameDefs.WHITE) deliver(client, GameDefs.WHITE)
            if (client.currentPlayer == GameDefs.BLACK) deliver(host, GameDefs.BLACK)

            if (boardString(host) != boardString(client) || host.currentPlayer != client.currentPlayer) {
                dump(host, client)
                throw AssertionError("BOARDS DIVERGED after $moves moves")
            }
        }

        assertEquals(boardString(host), boardString(client))
        assertEquals(host.currentPlayer, client.currentPlayer)
    }

    @Test
    fun `clean protocol keeps both boards synchronised`() {
        runCleanGame(400)
    }

    // ------------------------------------------------------------------
    // Authoritative STATE protocol (current app behaviour)
    // ------------------------------------------------------------------

    private fun statePayload(e: GameEngine, hl: List<Int>): String {
        val s = e.saveState()
        return "STATE|${e.rules.name}|${s.moveCount}|${s.currentPlayer}|${s.noCapturePly}|" +
                "${s.capturedBlack}|${s.capturedWhite}|${s.board.joinToString(",")}|" +
                "${if (hl.size > 0) hl[0] else -1}|${if (hl.size > 1) hl[1] else -1}"
    }

    /** Mirrors GameActivity.onStateReceived. Returns the (possibly rebuilt) engine. */
    private fun applyState(engine0: GameEngine?, data: String): GameEngine? {
        val parts = data.split("|")
        require(parts.size >= 10) { "bad state" }
        var target = engine0
        var adopted = false
        if (target == null || target.rules.name != parts[1]) {
            target = GameEngine(RulePreset.valueOf(parts[1]))
            adopted = true
        }
        val incomingCount = parts[2].toInt()
        if (!adopted && incomingCount <= target!!.moveCount) return target
        val board = parts[7].split(",").filter { it.isNotBlank() }.map { it.trim().toInt() }.toIntArray()
        require(board.size == target!!.size * target!!.size)
        target.restoreState(
            GameEngine.EngineState(
                board, parts[3].toInt(), incomingCount, parts[4].toInt(),
                parts[5].toInt(), parts[6].toInt()
            )
        )
        return target
    }

    @Test
    fun `state protocol keeps boards synchronised even across different presets`() {
        val host = GameEngine(RulePreset.UK_EUROPE)
        var client: GameEngine = GameEngine(RulePreset.INTERNATIONAL)

        val toClient = ArrayDeque<String>()
        val toHost = ArrayDeque<String>()
        var hl = emptyList<Int>()

        // Host's board is authoritative from the start.
        toClient.addLast(statePayload(host, emptyList()))

        var moves = 0
        while (moves < 300) {
            // Deliver inbound states.
            while (toClient.isNotEmpty()) { applyState(client, toClient.removeFirst())?.let { client = it } }
            while (toHost.isNotEmpty()) { applyState(host, toHost.removeFirst()) }

            assert(client.rules.name == host.rules.name)
            val acting: GameEngine
            val colour: Int
            if (host.currentPlayer == GameDefs.BLACK) {
                acting = host
                colour = GameDefs.BLACK
            } else {
                acting = client
                colour = GameDefs.WHITE
            }
            val legal = acting.generateMoves(colour).allLegal
            if (legal.isEmpty()) break
            val m = legal.first()
            assertNotNull(acting.applyMove(m))
            hl = listOf(m.from, m.to)
            if (acting === host) toClient.addLast(statePayload(host, hl))
            else toHost.addLast(statePayload(client, hl))
            moves++

            while (toClient.isNotEmpty()) { applyState(client, toClient.removeFirst())?.let { client = it } }
            while (toHost.isNotEmpty()) { applyState(host, toHost.removeFirst()) }

            assertEquals(boardString(host), boardString(client))
            assertEquals(host.currentPlayer, client.currentPlayer)
            assertEquals(host.rules.name, client.rules.name)
        }
    }

    @Test
    fun `state protocol adopts host preset when client differs`() {
        val host = GameEngine(RulePreset.UK_EUROPE)
        var client: GameEngine = GameEngine(RulePreset.INTERNATIONAL)
        applyState(client, statePayload(host, emptyList()))?.let { client = it }
        assertEquals(RulePreset.UK_EUROPE.name, client.rules.name)
        assert(client.size == 8)
        // Both colours present for every player.
        assertEquals(12, client.countPieces(GameDefs.BLACK))
        assertEquals(12, client.countPieces(GameDefs.WHITE))
    }

    private fun dump(a: GameEngine, b: GameEngine) {
        println("HOST   turn=${a.currentPlayer} moves=${a.moveCount}  ${boardString(a)}")
        println("CLIENT turn=${b.currentPlayer} moves=${b.moveCount}  ${boardString(b)}")
    }
}