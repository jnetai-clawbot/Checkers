/*
 * Checkers online multiplayer transport bridge.
 *
 * Runs inside a hidden native WebView and provides WebRTC peer-to-peer
 * connections through public Google STUN servers and the openrelay TURN
 * relay, so players connect to each other over the internet (through NATs)
 * instead of raw private IP TCP sockets.
 *
 * It implements four things the native code asks for (via window.HostBridge):
 *   - host a game  (publish a short share-code id and wait for a peer)
 *   - join a game  (dial an opponent by share-code id)
 *   - quick match  (random matchmaking via a rolling "lobby slot" id honour)
 *   - game messages (MOV / RESIGN / PING) over the WebRTC data channel
 *
 * Results / inbound events are reported back through window.AndroidJsb.
 */
(function () {
    'use strict';

    var ICE_SERVERS = [
        { urls: 'stun:stun.l.google.com:19302' },
        { urls: 'stun:stun1.l.google.com:19302' },
        { urls: 'stun:stun2.l.google.com:19302' },
        { urls: 'stun:stun3.l.google.com:19302' },
        { urls: 'stun:stun4.l.google.com:19302' },
        { urls: 'turn:openrelay.metered.ca:80', username: 'openrelayproject', credential: 'openrelayproject' },
        { urls: 'turn:openrelay.metered.ca:443', username: 'openrelayproject', credential: 'openrelayproject' },
        { urls: 'turn:openrelay.metered.ca:443?transport=tcp', username: 'openrelayproject', credential: 'openrelayproject' }
    ];

    var LOBBY_PREFIX = 'checkers-mp-lobby-';
    var LOBBY_SLOT_SECS = 45;      // length of each lobby window
    var LOBBY_MAX_AGE_MS = 30000;  // how long the lobby host waits for a joiner
    var RANDOM_LOOP_DELAY = 2000;
    var RANDOM_TIMEOUT_MS = 5 * 60 * 1000;
    var HELLO_TIMEOUT_MS = 20000;

    var net = {
        peer: null,          // main Peer used for the actual game connection
        myId: null,
        conn: null,          // active game DataConnection
        mode: 'idle',        // idle | host | join | random
        connected: false,    // handshake completed (playable)
        randomActive: false,
        randomStartedAt: 0,
        lobbyPeer: null,
        lobbyConn: null,
        lobbyQueue: [],
        lobbyTimer: null,
        retryTimer: null
    };

    function log(msg) {
        try { if (window.AndroidJsb) window.AndroidJsb.onLog(String(msg)); } catch (e) { /* no-op */ }
    }

    function status(text, isError) {
        try { if (window.AndroidJsb) window.AndroidJsb.onStatus(String(text), !!isError); } catch (e) { /* no-op */ }
    }

    function notifyConnected(role, name) {
        net.connected = true;
        try { window.AndroidJsb.onConnected(String(role), String(name)); } catch (e) { /* no-op */ }
    }

    function notifyError(code, message) {
        try { window.AndroidJsb.onError(String(code), String(message)); } catch (e) { /* no-op */ }
    }

    function notifyDisconnected(reason) {
        try { window.AndroidJsb.onPeerDisconnected(String(reason)); } catch (e) { /* no-op */ }
    }

    function genId() {
        var chars = 'ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789';
        var s = '';
        for (var i = 0; i < 8; i++) s += chars[Math.floor(Math.random() * chars.length)];
        return s;
    }

    function makePeer(id, debug) {
        return new Peer(id, {
            config: { iceServers: ICE_SERVERS.slice(), iceTransportPolicy: 'all' },
            debug: debug ? 1 : 0
        });
    }

    function parseMsg(raw) {
        if (typeof raw !== 'string') return null;
        try {
            var m = JSON.parse(raw);
            return (m && typeof m === 'object' && m.type) ? m : null;
        } catch (e) {
            return { type: raw };
        }
    }

    function clearTimers() {
        if (net.lobbyTimer) { clearTimeout(net.lobbyTimer); net.lobbyTimer = null; }
        if (net.retryTimer) { clearTimeout(net.retryTimer); net.retryTimer = null; }
    }

    function destroyPeer(p) {
        try { if (p) p.destroy(); } catch (e) { /* no-op */ }
    }

    function leaveLobby() {
        if (net.lobbyConn) { try { net.lobbyConn.close(); } catch (e) { /* no-op */ } }
        net.lobbyConn = null;
        destroyPeer(net.lobbyPeer);
        net.lobbyPeer = null;
        net.lobbyQueue = [];
        if (net.lobbyTimer) { clearTimeout(net.lobbyTimer); net.lobbyTimer = null; }
    }

    function stopAll() {
        net.randomActive = false;
        clearTimers();
        if (net.conn) { try { net.conn.close(); } catch (e) { /* no-op */ } }
        net.conn = null;
        leaveLobby();
        destroyPeer(net.peer);
        net.peer = null;
        net.mode = 'idle';
        net.connected = false;
        log('Transport stopped');
    }

    // ------------------------------------------------------------------
    // Main peer lifecycle
    // ------------------------------------------------------------------

    // Make sure the main peer exists (any identity). Invokes cb once open.
    function ensureMain(cb, preferredId) {
        if (net.peer) {
            if (net.peer.open) { cb(); return; }
            net.peer.on('open', function () { cb(); });
            return;
        }
        if (preferredId) net.myId = String(preferredId);
        else net.myId = genId();
        var p = makePeer(net.myId, true);
        net.peer = p;
        p.on('open', function (id) {
            net.myId = id;
            cb();
        });
        p.on('disconnected', function () {
            try { p.reconnect(); } catch (e) { /* no-op */ }
        });
        p.on('error', function (err) {
            log('Main peer error: ' + err.type, true);
            // If the random matchmaking flow lost its identity, retry the loop.
            if (net.randomActive) scheduleRandomRetry();
        });
    }

    // Recreate the main peer with a fresh identity (needed when the local
    // own id must be the published share code).
    function publishMain(cb, preferredId) {
        destroyPeer(net.peer);
        net.peer = null;
        ensureMain(cb, preferredId);
    }

    // ------------------------------------------------------------------
    // Game connection + handshake
    // ------------------------------------------------------------------

    function setupGameConn(conn, role) {
        net.conn = conn;
        var isHost = role === 'HOST';
        var hello = null;

        conn.on('open', function () {
            log('Game data channel open as ' + role);
            if (!isHost) {
                try { conn.send(JSON.stringify({ type: 'HELLO', id: net.myId })); } catch (e) { /* no-op */ }
            }
        });

        conn.on('data', function (raw) {
            var msg = parseMsg(raw);
            if (!msg) {
                try { conn.send('PING'); } catch (e) { /* no-op */ }
                return;
            }
            switch (msg.type) {
                case 'HELLO':
                    if (isHost && !net.connected) {
                        if (hello) { clearTimeout(hello); hello = null; }
                        try { conn.send(JSON.stringify({ type: 'ROOM_OK', id: net.myId })); } catch (e) { /* no-op */ }
                        notifyConnected('HOST', 'Opponent');
                    }
                    break;
                case 'ROOM_OK':
                    if (!isHost && !net.connected) {
                        if (hello) { clearTimeout(hello); hello = null; }
                        notifyConnected('CLIENT', 'Host');
                    }
                    break;
                case 'PING':
                    try { conn.send('PONG'); } catch (e) { /* no-op */ }
                    break;
                case 'RESIGN':
                    try { window.AndroidJsb.onPeerResigned(); } catch (e) { /* no-op */ }
                    break;
                case 'MOV':
                    try { window.AndroidJsb.onMove(String(msg.data)); } catch (e) { /* no-op */ }
                    break;
                case 'BYE':
                    try { conn.close(); } catch (e) { /* no-op */ }
                    break;
                default:
                    break;
            }
        });

        conn.on('close', function () {
            if (hello) { clearTimeout(hello); hello = null; }
            net.conn = null;
            if (net.connected) {
                net.connected = false;
                notifyDisconnected('Connection to opponent lost.');
            } else {
                notifyError('E-NET-002-CONNECT',
                    isHost ? 'Waiting for a challenger timed out.' : 'Could not connect to the host. Check the code and that the host is online.');
            }
        });

        conn.on('error', function (e) {
            log('Data channel error: ' + (e && e.type ? e.type : String(e)), true);
        });

        hello = setTimeout(function () {
            if (!net.connected) {
                try { conn.close(); } catch (e) { /* no-op */ }
            }
        }, HELLO_TIMEOUT_MS);
    }

    function sendGameMessage(obj) {
        if (net.conn && net.conn.open) {
            try { net.conn.send(JSON.stringify(obj)); return true; } catch (e) { log('Send failed: ' + e, true); }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Host by share code
    // ------------------------------------------------------------------

    function hostProcess(id) {
        net.mode = 'host';
        net.connected = false;
        var hostAttempts = 0;
        var hostErrorWired = false;

        function tryHost(suggestedId) {
            if (net.mode !== 'host') return;
            publishMain(function () {
                if (net.mode !== 'host') return;
                try { window.AndroidJsb.onLocalId(String(net.myId)); } catch (e) { /* no-op */ }
                status('Waiting for opponent…', false);
                net.peer.on('connection', function (conn) {
                    log('Incoming challenger: ' + conn.peer);
                    if (net.conn) { try { conn.close(); } catch (e) { /* no-op */ } return; }
                    status('Challenger joined — connecting…', false);
                    setupGameConn(conn, 'HOST');
                });
                if (!hostErrorWired) {
                    hostErrorWired = true;
                    net.peer.on('error', function (err) {
                        log('Host peer error: ' + err.type, true);
                        if (err.type === 'unavailable-id' || err.type === 'network') {
                            hostAttempts++;
                            if (hostAttempts > 5 || net.connected) {
                                if (!net.connected) {
                                    notifyError('E-NET-002-CONNECT', 'Could not start hosting. Check your connection and try again.');
                                    net.mode = 'idle';
                                }
                                return;
                            }
                            status('Starting host (attempt ' + hostAttempts + ')…', false);
                            tryHost();
                        }
                    });
                }
            }, suggestedId);
        }

        tryHost(id);
    }

    // ------------------------------------------------------------------
    // Join by share code
    // ------------------------------------------------------------------

    function joinProcess(code) {
        net.mode = 'join';
        net.connected = false;
        var target = sanitizeTarget(code);
        if (!target) {
            notifyError('E-NET-007-INVALID-TOKEN', "That doesn't look like a valid share code.");
            return;
        }
        ensureMain(function () {
            status('Connecting…', false);
            var c = net.peer.connect(target, { reliable: true });
            setupGameConn(c, 'CLIENT');
        });
    }

    function sanitizeTarget(code) {
        if (typeof code !== 'string') return null;
        var t = code.trim();
        if (t.indexOf(':') >= 0) {
            var parts = t.split(':');
            t = parts[0].length > 0 && parts[0].indexOf('/') >= 0 ? parts[parts.length - 1] : t;
        }
        t = t.replace(/^CKRS:/i, '').replace(/^checkers:\/\//i, '');
        t = t.split(':')[0];
        t = t.trim();
        if (!t || t.length > 64) return null;
        if (!/^[A-Za-z0-9_-]+$/.test(t)) return null;
        return t;
    }

    // ------------------------------------------------------------------
    // Quick match (random) — rolling lobby slots
    // ------------------------------------------------------------------

    function getSlot() {
        return Math.floor(Date.now() / (LOBBY_SLOT_SECS * 1000));
    }

    function startRandom(preferredId) {
        if (net.randomActive) return;
        net.mode = 'random';
        net.randomActive = true;
        net.randomStartedAt = Date.now();
        status('Searching for an opponent…', false);
        // Publish our own identity so we can also auto-host.
        publishMain(function () {
            if (!net.randomActive) return;
            try { window.AndroidJsb.onLocalId(String(net.myId)); } catch (e) { /* no-op */ }
            // When we win the lobby slot we become the game HOST: accept the
            // challenger's direct connection on our main peer.
            net.peer.on('connection', function (conn) {
                if (!net.randomActive && net.mode !== 'host') return;
                log('Incoming random challenger: ' + conn.peer);
                if (net.conn) { try { conn.close(); } catch (e) { /* no-op */ } return; }
                status('Opponent found! Connecting…', false);
                setupGameConn(conn, 'HOST');
            });
            joinSlot(getSlot());
        }, preferredId);
    }

    function stopRandom() {
        if (!net.randomActive && net.mode !== 'random') return;
        net.randomActive = false;
        clearTimers();
        leaveLobby();
        // Tear down a game connection that was being dialled but not yet paired.
        if (!net.connected && net.conn) {
            try { net.conn.close(); } catch (e) { /* no-op */ }
            net.conn = null;
        }
        if (net.mode === 'random') net.mode = 'idle';
        status('Search stopped', false);
    }

    function joinSlot(slot) {
        if (!net.randomActive) return;
        if (Date.now() - net.randomStartedAt > RANDOM_TIMEOUT_MS) {
            net.randomActive = false;
            net.mode = 'idle';
            status('No opponent found yet. Tap Quick Match again to keep searching.', false);
            return;
        }
        var lobbyId = LOBBY_PREFIX + slot;
        log('Trying lobby slot ' + slot + ' (' + lobbyId + ')');

        leaveLobby();
        var lp = makePeer(lobbyId, false);
        net.lobbyPeer = lp;

        lp.on('open', function () {
            if (!net.randomActive) { destroyPeer(lp); return; }
            log('AUTO-HOST: won lobby slot ' + slot);
            status('No host found — hosting a room, waiting for players…', false);
            net.lobbyQueue = [{ conn: null, id: net.myId, isHost: true }];
            net.lobbyTimer = setTimeout(function () {
                if (net.randomActive && net.lobbyQueue.length <= 1) {
                    log('Lobby window expired without a challenger');
                    joinSlot(Math.max(slot, getSlot()));
                }
            }, LOBBY_MAX_AGE_MS);
        });

        lp.on('connection', function (conn2) {
            handleLobbyConn(conn2);
        });

        lp.on('error', function (err) {
            if (err.type === 'unavailable-id') {
                log('Slot ' + slot + ' already hosted by another player');
                destroyPeer(lp);
                net.lobbyPeer = null;
                connectToLobbyHost(lobbyId);
            } else {
                log('Lobby error: ' + err.type, true);
                scheduleRandomRetry();
            }
        });
    }

    function handleLobbyConn(conn2) {
        conn2.on('open', function () {
            conn2.on('data', function (raw) {
                var msg = parseMsg(raw);
                if (!msg || msg.type !== 'lobby_join') return;
                if (net.lobbyQueue.length >= 2) return;
                log('A player joined the room: ' + msg.id);
                net.lobbyQueue.push({ conn: conn2, id: msg.id, isHost: false });
                tryPair();
            });
            conn2.on('close', function () {
                net.lobbyQueue = net.lobbyQueue.filter(function (q) { return q.conn !== conn2; });
            });
        });
    }

    function tryPair() {
        while (net.lobbyQueue.length >= 2) {
            var a = net.lobbyQueue.shift();
            var b = net.lobbyQueue.shift();
            var hostEntry = a.isHost ? a : b;
            var clientEntry = a.isHost ? b : a;
            log('Random match: host ' + hostEntry.id + ' <-> ' + clientEntry.id);
            // The game host (lobby winner) waits for the direct connection;
            // tell the client to dial the host's published id.
            if (clientEntry.conn) {
                try {
                    clientEntry.conn.send(JSON.stringify({ type: 'lobby_paired', hostId: hostEntry.id }));
                } catch (e) { /* no-op */ }
            }
            net.randomActive = false;
            status('Opponent found! Waiting for connection…', false);
            net.mode = 'host';
            leaveLobby();
        }
    }

    function connectToLobbyHost(lobbyId) {
        if (!net.randomActive) return;
        ensureMain(function () {
            var c;
            try {
                c = net.peer.connect(lobbyId, { reliable: true });
            } catch (e) {
                scheduleRandomRetry();
                return;
            }
            net.lobbyConn = c;
            c.on('open', function () {
                try { c.send(JSON.stringify({ type: 'lobby_join', id: net.myId })); } catch (e) { /* no-op */ }
                status('A room was found — waiting for a match…', false);
            });
            c.on('data', function (raw) {
                var msg = parseMsg(raw);
                if (!msg) return;
                if (msg.type === 'lobby_paired' && msg.hostId) {
                    log('Matched with ' + msg.hostId);
                    net.randomActive = false;
                    net.mode = 'join';
                    leaveLobby();
                    status('Opponent found! Connecting…', false);
                    var g = net.peer.connect(String(msg.hostId), { reliable: true });
                    setupGameConn(g, 'CLIENT');
                } else if (msg.type === 'lobby_reject') {
                    scheduleRandomRetry();
                }
            });
            c.on('close', function () {
                net.lobbyConn = null;
                if (net.randomActive) scheduleRandomRetry();
            });
            c.on('error', function () {
                net.lobbyConn = null;
                if (net.randomActive) scheduleRandomRetry();
            });
        });
    }

    function scheduleRandomRetry() {
        if (!net.randomActive) return;
        if (Date.now() - net.randomStartedAt > RANDOM_TIMEOUT_MS) {
            net.randomActive = false;
            net.mode = 'idle';
            status('No opponent found yet. Tap Quick Match again to keep searching.', false);
            return;
        }
        clearTimers();
        net.retryTimer = setTimeout(function () {
            if (net.randomActive) joinSlot(Math.max(0, getSlot()));
        }, RANDOM_LOOP_DELAY);
    }

    // ------------------------------------------------------------------
    // Bridge surface for native code
    // ------------------------------------------------------------------

    window.HostBridge = {
        ready: function () {
            if (window.AndroidJsb) {
                try { window.AndroidJsb.onReady(); } catch (e) { /* no-op */ }
            }
        },
        host: function (id) { hostProcess(id); },
        join: function (code) { joinProcess(code); },
        quickMatch: function (id) { startRandom(id); },
        cancelQuickMatch: function () { stopRandom(); },
        sendMove: function (data) {
            var sent = sendGameMessage({ type: 'MOV', data: String(data) });
            return sent ? 'true' : 'false';
        },
        sendResign: function () {
            sendGameMessage({ type: 'RESIGN' });
        },
        sendPing: function () {
            sendGameMessage({ type: 'PING' });
        },
        stop: function () { stopAll(); },
        getMyId: function () { return net.myId ? String(net.myId) : ''; },
        isConnected: function () { return net.connected ? 'true' : 'false'; }
    };

    try {
        window.HostBridge.ready();
        log('Checkers P2P bridge loaded');
    } catch (e) {
        try { if (window.AndroidJsb) window.AndroidJsb.onLog('Bridge init failed: ' + e); } catch (e2) { /* no-op */ }
    }
})();