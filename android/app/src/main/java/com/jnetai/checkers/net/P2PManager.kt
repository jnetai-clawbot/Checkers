package com.jnetai.checkers.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.jnetai.checkers.utils.ErrorLogger
import org.json.JSONObject

/**
 * P2PManager - online multiplayer transport facade.
 *
 * The heavy lifting is done by a WebView running PeerJS (WebRTC) with public
 * Google STUN servers and an openrelay TURN relay, so opponents connect over
 * the internet even behind NAT. This object keeps the same high-level API used
 * before (host/join/send) but now delegates to that bridge.
 *
 * Modes:
 *  - HOST: publish a short share-code id and wait for a challenger.
 *  - CLIENT: dial an opponent by share-code id.
 *  - RANDOM: rolling "lobby slot" matchmaking — first look for an existing
 *    host to connect to, otherwise auto-host a room, looping until a player
 *    is found.
 */
object P2PManager {

    interface Listener {
        fun onConnected(role: Role, remoteName: String)
        fun onMoveReceived(data: String)
        fun onPeerResigned()
        fun onPeerDisconnected(reason: String)
        fun onError(errorCode: String, message: String)

        /** Optional transport status updates (searching / connecting…). */
        fun onStatus(text: String, isError: Boolean) {}

        /** Optional: the local published share-code id became available. */
        fun onLocalId(id: String) {}
    }

    enum class Role { HOST, CLIENT }

    const val PROTOCOL_PREFIX = "CKRS:"

    /** Retained for compatibility with older callers; unused with WebRTC. */
    const val DEFAULT_PORT = 45123

    private val listeners = mutableListOf<Listener>()
    private val lock = Any()
    private val uiHandler = Handler(Looper.getMainLooper())

    private var running = false
    private var connected = false
    private var mode = "idle" // idle | host | join | random
    private var myId: String? = null

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Must be called from the UI thread (OnlineActivity) before any action. */
    fun initialize(context: Context) {
        PeerTransport.initialize(context)
    }

    fun addListener(l: Listener) {
        synchronized(lock) { listeners.add(l) }
    }

    fun removeListener(l: Listener) {
        synchronized(lock) { listeners.remove(l) }
    }

    fun isRunning(): Boolean = running

    fun isConnected(): Boolean = connected

    fun isRandomMode(): Boolean = mode == "random"

    fun getMyId(): String? = myId

    /**
     * Start hosting. Returns the full share token (shown / shared / QR) so the
     * challenger can join, or null when another session is already running.
     */
    fun hostGame(): String? {
        if (running) {
            ErrorLogger.log(ErrorLogger.Codes.NET_HOST_FAILED,
                "hostGame called while another session is running")
            return null
        }
        running = true
        connected = false
        mode = "host"
        myId = generateId()
        PeerTransport.runJs("HostBridge.host(${jsString(myId!!)});")
        return "$PROTOCOL_PREFIX$myId"
    }

    /** Join a host using a full share token (CKRS:<id> or just the id). */
    fun joinGame(token: String): Boolean {
        if (running) {
            ErrorLogger.log(ErrorLogger.Codes.NET_JOIN_FAILED,
                "joinGame called while another session is running")
            return false
        }
        if (parseId(token) == null) {
            ErrorLogger.logf(ErrorLogger.Codes.NET_JOIN_FAILED,
                "Invalid share token provided: %s", token)
            onError("E-NET-007-INVALID-TOKEN", "That doesn't look like a valid share code.")
            return false
        }
        running = true
        connected = false
        mode = "join"
        PeerTransport.runJs("HostBridge.join(${jsString(token)});")
        return true
    }

    /**
     * Quick Match — random matchmaking. Looks for an existing host (lobby)
     * first and only auto-hosts when none can be found, looping until a
     * player connects.
     */
    fun startRandom(): Boolean {
        if (running) {
            ErrorLogger.log(ErrorLogger.Codes.NET_JOIN_FAILED,
                "startRandom called while another session is running")
            return false
        }
        running = true
        connected = false
        mode = "random"
        myId = generateId()
        PeerTransport.runJs("HostBridge.quickMatch(${jsString(myId!!)});")
        return true
    }

    fun stopRandom() {
        if (mode != "random") return
        PeerTransport.runJs("HostBridge.cancelQuickMatch();")
        mode = "idle"
        running = false
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    fun sendMove(moveData: String): Boolean {
        if (!running || !connected) return false
        PeerTransport.runJs("HostBridge.sendMove(${jsString(moveData)});")
        return true
    }

    fun sendResign(): Boolean {
        if (!running || !connected) return false
        PeerTransport.runJs("HostBridge.sendResign();")
        return true
    }

    fun stop() {
        running = false
        connected = false
        mode = "idle"
        PeerTransport.runJs("HostBridge.stop();")
    }

    fun stopAndDestroy() {
        stop()
        PeerTransport.destroy()
    }

    // ------------------------------------------------------------------
    // JS -> Kotlin callbacks (dispatched on the main thread)
    // ------------------------------------------------------------------

    internal fun onLocalId(id: String) {
        myId = id
        uiHandler.post {
            dispatch { it.onLocalId(id) }
        }
    }

    internal fun onStatus(text: String, isError: Boolean) {
        uiHandler.post {
            dispatch { it.onStatus(text, isError) }
        }
    }

    internal fun onConnected(role: String, name: String) {
        uiHandler.post {
            connected = true
            dispatch { it.onConnected(if (role == "HOST") Role.HOST else Role.CLIENT, name) }
        }
    }

    internal fun onMove(data: String) {
        uiHandler.post {
            dispatch { it.onMoveReceived(data) }
        }
    }

    internal fun onPeerResigned() {
        uiHandler.post {
            dispatch { it.onPeerResigned() }
        }
    }

    internal fun onPeerDisconnected(reason: String) {
        uiHandler.post {
            connected = false
            running = false
            mode = "idle"
            dispatch { it.onPeerDisconnected(reason) }
        }
    }

    internal fun onError(errorCode: String, message: String) {
        uiHandler.post {
            // Pairing-time transport failures should reset the session so the
            // user can retry. In-game errors must not tear down a live match.
            val pairingFailure = !connected && (errorCode.startsWith("E-NET-") || errorCode.startsWith("E-NET-001"))
            if (pairingFailure) {
                running = false
                mode = "idle"
            }
            dispatch { it.onError(errorCode, message) }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun parseId(token: String): String? {
        var t = token.trim()
        if (t.startsWith(PROTOCOL_PREFIX)) t = t.removePrefix(PROTOCOL_PREFIX)
        if (t.startsWith("checkers://")) return null
        // Take the host id only (tolerate any legacy :port:room suffix).
        val clean = t.split(":")[0].trim()
        return clean.takeIf {
            it.isNotEmpty() && it.length <= 64 &&
                    it.all { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '_' }
        }
    }

    private fun generateId(): String {
        val chars = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
        val rnd = java.security.SecureRandom()
        val sb = StringBuilder(8)
        for (i in 0 until 8) sb.append(chars[rnd.nextInt(chars.length)])
        return sb.toString()
    }

    private fun jsString(value: String): String =
        JSONObject.quote(value).toString()

    private inline fun dispatch(block: (Listener) -> Unit) {
        val tmp: List<Listener>
        synchronized(lock) { tmp = listeners.toList() }
        for (l in tmp) {
            try {
                block(l)
            } catch (e: Exception) {
                ErrorLogger.logf(ErrorLogger.Codes.NET_CLOSED,
                    "Listener dispatch failed: %s", e, l.javaClass.simpleName)
            }
        }
    }
}