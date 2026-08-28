package com.jnetai.checkers.net

import android.content.Context
import android.net.wifi.WifiManager
import com.jnetai.checkers.utils.ErrorLogger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Enumeration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Direct peer-to-peer TCP transport for online multiplayer.
 *
 * Host listens on a local port and shares a token of the form
 * `ip:port:room`. The opponent joins by scanning the QR (which encodes the
 * token) or by typing the token. Messages are newline-delimited and simple:
 *
 *  HELLO <room>     (client)
 *  ROOM_OK          (host accepts handshake)
 *  MOV f<t1>:<t2>:...:c<s1>;<s2>   (a full move incl. path and captures)
 *  RESIGN
 *  PING / PONG
 *  BYE
 *
 * The protocol is intentionally tiny so it can be debugged with a plain TCP
 * client if ever needed.
 */
object P2PManager {

    interface Listener {
        fun onConnected(role: Role, remoteName: String)
        fun onMoveReceived(data: String)
        fun onPeerResigned()
        fun onPeerDisconnected(reason: String)
        fun onError(errorCode: String, message: String)
    }

    enum class Role { HOST, CLIENT }

    const val PROTOCOL_PREFIX = "CKRS:"
    const val DEFAULT_PORT = 45123

    private val running = AtomicBoolean(false)
    private var socket: Socket? = null
    private var serverSocket: ServerSocket? = null
    private var writer: PrintWriter? = null
    private val listeners = mutableListOf<Listener>()
    private val lock = Any()

    fun addListener(l: Listener) {
        synchronized(lock) { listeners.add(l) }
    }

    fun removeListener(l: Listener) {
        synchronized(lock) { listeners.remove(l) }
    }

    fun isRunning(): Boolean = running.get()

    // ------------------------------------------------------------------
    // Hosting
    // ------------------------------------------------------------------

    /**
     * Start hosting on [port]. Returns the share token to give to the
     * opponent, or null on failure.
     */
    fun hostGame(port: Int, room: String = generateRoomCode()): String? {
        if (running.get()) {
            ErrorLogger.log(ErrorLogger.Codes.NET_HOST_FAILED,
                "hostGame called while another session is running")
            return null
        }
        return try {
            running.set(true)
            val ss = ServerSocket(port)
            serverSocket = ss

            Thread {
                try {
                    val client = ss.accept()
                    client.soTimeout = 30000
                    handleClient(client, Role.HOST)
                } catch (e: Exception) {
                    ErrorLogger.logf(ErrorLogger.Codes.NET_HOST_FAILED,
                        "Host accept failed on port %d", e, port)
                    notifyError(
                        if (e is java.net.SocketTimeoutException) "E-NET-006-LISTEN-TIMEOUT"
                        else "E-NET-001-ACCEPT", "Failed to accept connection."
                    )
                }
            }.apply {
                name = "checkers-p2p-host"
                start()
            }

            buildToken(getLocalIpAddress(), port, room)
        } catch (e: Exception) {
            running.set(false)
            ErrorLogger.logf(ErrorLogger.Codes.NET_HOST_FAILED,
                "Unable to bind server on port %d", e, port)
            notifyError("E-NET-001-BIND", "Could not start the local server (port $port).")
            null
        }
    }

    /** Join a host using a full share token (ip:port:room). */
    fun joinGame(token: String): Boolean {
        if (running.get()) {
            ErrorLogger.log(ErrorLogger.Codes.NET_JOIN_FAILED,
                "joinGame called while another session is running")
            return false
        }

        val parsed = parseToken(token)
        if (parsed == null) {
ErrorLogger.logf(ErrorLogger.Codes.NET_JOIN_FAILED,
                    "Invalid share token provided: %s", token)
            notifyError("E-NET-007-INVALID-TOKEN", "That doesn't look like a valid share code.")
            return false
        }

        return try {
            running.set(true)
            val sock = Socket()
            sock.connect(java.net.InetSocketAddress(parsed.ip, parsed.port), 10000)
            sock.soTimeout = 30000
            socket = sock
            Thread {
                try {
                    handleClient(sock, Role.CLIENT, parsed.room)
                } catch (e: Exception) {
                    ErrorLogger.logf(ErrorLogger.Codes.NET_JOIN_FAILED,
                        "Client session failed", e)
                    if (running.get()) notifyDisconnected("Connection to opponent lost.")
                }
            }.apply {
                name = "checkers-p2p-client"
                start()
            }
            true
        } catch (e: Exception) {
            running.set(false)
            closeQuietly()
            ErrorLogger.logf(ErrorLogger.Codes.NET_JOIN_FAILED,
                "Join failed for token '%s'", e, token)
            notifyError("E-NET-002-CONNECT",
                "Could not connect. Check the code and that the host is online.")
            false
        }
    }

    private fun handleClient(client: Socket, role: Role, expectedRoom: String? = null) {
        try {
            val bw = PrintWriter(client.getOutputStream(), true)
            val br = BufferedReader(InputStreamReader(client.getInputStream()))
            writer = bw

            if (role == Role.HOST) {
                // Read the HELLO with a 15s timeout window.
                client.soTimeout = 15000
                val hello = br.readLine() ?: throw IllegalStateException("Empty handshake")
                if (!hello.startsWith("HELLO")) {
                    throw IllegalStateException("Bad handshake: $hello")
                }
                client.soTimeout = 30000
                bw.println("ROOM_OK")
                bw.flush()
                notifyConnected(role, "Opponent")
            } else {
                if (expectedRoom.isNullOrEmpty()) {
                    throw IllegalStateException("Client handshake missing room code")
                }
                bw.println("HELLO $expectedRoom")
                bw.flush()
                client.soTimeout = 15000
                val ack = br.readLine()
                if (ack != "ROOM_OK") {
                    throw IllegalStateException("Unexpected host ack: $ack")
                }
                client.soTimeout = 30000
                notifyConnected(role, "Host")
            }
            readLoop(br)
        } catch (e: Exception) {
            ErrorLogger.logf(ErrorLogger.Codes.NET_RECEIVE_FAILED,
                "Peer connection dropped (role %s)", e, role)
            if (!running.get()) return
            notifyDisconnected("Connection to opponent lost.")
        } finally {
            closeQuietly()
            running.set(false)
        }
    }

    private fun readLoop(reader: BufferedReader) {
        while (running.get()) {
            try {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) continue
                when {
                    line == "PING" -> {
                        writer?.println("PONG")
                        writer?.flush()
                    }
                    line == "RESIGN" -> notifyPeerResigned()
                    line == "BYE" -> break
                    line.startsWith("MOV ") ->
                        notifyMove(line.removePrefix("MOV ").trim())
                    else -> {
                        ErrorLogger.logf(ErrorLogger.Codes.NET_PROTOCOL,
                            "Unknown protocol message ignored: %s", line.take(80))
                    }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    ErrorLogger.logf(ErrorLogger.Codes.NET_RECEIVE_FAILED,
                        "Read loop terminated unexpectedly", e)
                }
                break
            }
        }
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    fun sendMove(moveData: String): Boolean {
        return send("MOV $moveData")
    }

    fun sendResign(): Boolean = send("RESIGN")

    fun sendPing(): Boolean = send("PING")

    private fun send(data: String): Boolean {
        val w = writer ?: return false
        return try {
            synchronized(writer ?: Any()) {
                w.println(data)
                w.flush()
            }
            true
        } catch (e: Exception) {
            ErrorLogger.logf(ErrorLogger.Codes.NET_SEND_FAILED,
                "Failed to send message: %s", e, data.take(60))
            false
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    fun stop() {
        running.set(false)
        closeQuietly()
    }

    private fun closeQuietly() {
        try { writer?.close() } catch (_: Exception) { }
        try { socket?.close() } catch (_: Exception) { }
        try { serverSocket?.close() } catch (_: Exception) { }
        socket = null
        serverSocket = null
        writer = null
    }

    // ------------------------------------------------------------------
    // Token helpers
    // ------------------------------------------------------------------

    data class ParsedToken(val ip: String, val port: Int, val room: String)

    fun buildToken(ip: String, port: Int, room: String): String {
        val safePort = if (port in 1..65535) port else DEFAULT_PORT
        return "$PROTOCOL_PREFIX$ip:$safePort:$room"
    }

    fun parseToken(token: String): ParsedToken? {
        return try {
            var t = token.trim()
            if (t.startsWith(PROTOCOL_PREFIX)) t = t.removePrefix(PROTOCOL_PREFIX)
            if (t.startsWith("checkers://")) {
                // Legacy-ish uri form: checkers//ip:port/room
                t = t.removePrefix("checkers://")
                val slash = t.indexOf('/')
                if (slash >= 0) {
                    val addr = t.substring(0, slash)
                    val room = t.substring(slash + 1)
                    val colon = addr.lastIndexOf(':')
                    val ip = addr.substring(0, colon)
                    val port = addr.substring(colon + 1).toIntOrNull() ?: DEFAULT_PORT
                    return ParsedToken(ip, port, room)
                }
                return null
            }

            val parts = t.split(":")
            if (parts.size < 3) return null
            val ip = parts[0]
            val port = parts[1].toIntOrNull() ?: return null
            val room = parts[2]
            ParsedToken(ip, port, room)
        } catch (e: Exception) {
            ErrorLogger.logf(ErrorLogger.Codes.NET_QR_INVALID,
                "Failed to parse token '%s'", e, token.take(80))
            null
        }
    }

    fun roomCode(): String = generateRoomCode()

    fun generateRoomCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        var sb = StringBuilder()
        var rng = java.util.Random()
        for (i in 0 until 6) sb.append(chars[rng.nextInt(chars.length)])
        return "CKR-" + sb.toString()
    }

    /** Best-effort local IPv4 address for display in the share token. */
    fun getLocalIpAddress(): String {
        return try {
            val interfaces: Enumeration<NetworkInterface> = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val addrs: Enumeration<InetAddress> = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
            "127.0.0.1"
        } catch (e: Exception) {
            ErrorLogger.logf(ErrorLogger.Codes.NET_HOST_FAILED,
                "Could not enumerate network interfaces", e)
            "127.0.0.1"
        }
    }

    // ------------------------------------------------------------------
    // Notification helpers (posted via Main handler by the caller)
    // ------------------------------------------------------------------

    fun notifyConnected(role: Role, remoteName: String) {
        val tmp: List<Listener>
        synchronized(lock) { tmp = listeners.toList() }
        for (l in tmp) {
            try { l.onConnected(role, remoteName) } catch (_: Exception) { }
        }
    }

    fun notifyMove(data: String) {
        val tmp: List<Listener>
        synchronized(lock) { tmp = listeners.toList() }
        for (l in tmp) {
            try { l.onMoveReceived(data) } catch (_: Exception) { }
        }
    }

    fun notifyPeerResigned() {
        val tmp: List<Listener>
        synchronized(lock) { tmp = listeners.toList() }
        for (l in tmp) {
            try { l.onPeerResigned() } catch (_: Exception) { }
        }
    }

    fun notifyDisconnected(reason: String) {
        val tmp: List<Listener>
        synchronized(lock) { tmp = listeners.toList() }
        for (l in tmp) {
            try { l.onPeerDisconnected(reason) } catch (_: Exception) { }
        }
    }

    fun notifyError(errorCode: String, message: String) {
        val tmp: List<Listener>
        synchronized(lock) { tmp = listeners.toList() }
        for (l in tmp) {
            try { l.onError(errorCode, message) } catch (_: Exception) { }
        }
    }
}