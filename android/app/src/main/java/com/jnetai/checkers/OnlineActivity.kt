package com.jnetai.checkers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import com.jnetai.checkers.net.P2PManager
import com.jnetai.checkers.utils.ErrorLogger
import com.jnetai.checkers.utils.QRCodeUtils

/**
 * Host or join an online P2P checkers match using a share code / QR code.
 */
class OnlineActivity : AppCompatActivity(), P2PManager.Listener {

    private val uiHandler = Handler(Looper.getMainLooper())

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        val contents = result.contents
        if (contents.isNullOrEmpty()) {
            Toast.makeText(this, "Scan cancelled", Toast.LENGTH_SHORT).show()
        } else {
            etShareCode.setText(contents.trim())
            onJoinClick()
        }
    }

    private lateinit var tvShareCode: TextView
    private lateinit var imgQr: ImageView
    private lateinit var etShareCode: EditText
    private lateinit var tvStatus: TextView
    private lateinit var btnHost: Button
    private lateinit var btnJoin: Button
    private lateinit var btnShowQr: Button
    private lateinit var btnCopyCode: Button
    private lateinit var btnShare: Button
    private lateinit var btnScanQr: Button

    private var pendingRole = P2PManager.Role.HOST
    private var startedGame = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_online)

        try {
            tvShareCode = findViewById(R.id.tvShareCode)
            imgQr = findViewById(R.id.imgQr)
            etShareCode = findViewById(R.id.etShareCode)
            tvStatus = findViewById(R.id.tvOnlineStatus)
            btnHost = findViewById(R.id.btnHost)
            btnJoin = findViewById(R.id.btnJoin)
            btnShowQr = findViewById(R.id.btnShowQr)
            btnCopyCode = findViewById(R.id.btnCopyCode)
            btnShare = findViewById(R.id.btnShare)
            btnScanQr = findViewById(R.id.btnScanQr)
        } catch (e: Exception) {
            ErrorLogger.log(ErrorLogger.Codes.UI_VIEW_BINDING, "Failed to bind online views", e)
            finish()
            return
        }

        btnHost.setOnClickListener { onHostClick() }
        btnJoin.setOnClickListener { onJoinClick() }
        btnShowQr.setOnClickListener { toggleQr() }
        btnCopyCode.setOnClickListener { copyCode() }
        btnShare.setOnClickListener { shareCode() }
        btnScanQr.setOnClickListener { startQrScan() }
    }

    override fun onStart() {
        super.onStart()
        P2PManager.addListener(this)
    }

    override fun onStop() {
        super.onStop()
        P2PManager.removeListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only stop the transport if we didn't hand off to the game.
        if (!startedGame) {
            P2PManager.stop()
        }
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private fun onHostClick() {
        if (P2PManager.isRunning()) {
            Toast.makeText(this, "A session is already active", Toast.LENGTH_SHORT).show()
            return
        }
        val token = P2PManager.hostGame(P2PManager.DEFAULT_PORT)
        if (token == null) {
            setStatus("Could not start hosting. Try again.", true)
            return
        }
        pendingRole = P2PManager.Role.HOST
        tvShareCode.text = token
        tvShareCode.visibility = TextView.VISIBLE
        hideQr()
        setStatus(getString(R.string.online_waiting), false)
        Toast.makeText(this, "Share the code with your opponent", Toast.LENGTH_LONG).show()
    }

    private fun onJoinClick() {
        val token = etShareCode.text.toString().trim()
        if (token.isEmpty()) {
            Toast.makeText(this, "Enter the share code first", Toast.LENGTH_SHORT).show()
            return
        }
        pendingRole = P2PManager.Role.CLIENT
        setStatus("Connecting…", false)
        if (!P2PManager.joinGame(token)) {
            setStatus("Join failed. Check the code.", true)
        }
    }

    private fun toggleQr() {
        val token = tvShareCode.text.toString()
        if (!token.startsWith(P2PManager.PROTOCOL_PREFIX)) {
            Toast.makeText(this, "Start hosting first", Toast.LENGTH_SHORT).show()
            return
        }
        if (imgQr.visibility == ImageView.VISIBLE) {
            hideQr()
            return
        }
        val bmp = QRCodeUtils.generateQrBitmap(token)
        if (bmp != null) {
            imgQr.setImageBitmap(bmp)
            imgQr.visibility = ImageView.VISIBLE
        } else {
            Toast.makeText(this, "Could not generate QR code", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideQr() {
        imgQr.visibility = ImageView.GONE
    }

    private fun copyCode() {
        val token = tvShareCode.text.toString()
        if (!token.startsWith(P2PManager.PROTOCOL_PREFIX)) {
            Toast.makeText(this, "Start hosting first", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("checkers-share-code", token))
        Toast.makeText(this, "Code copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareCode() {
        val token = tvShareCode.text.toString()
        if (!token.startsWith(P2PManager.PROTOCOL_PREFIX)) {
            Toast.makeText(this, "Start hosting first", Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT,
                "Play Checkers with me!\nShare code: $token\nOpen the online mode and scan, or enter the code.")
        }
        startActivity(Intent.createChooser(send, "Share checkers code"))
    }

    private fun startQrScan() {
        try {
            val options = ScanOptions()
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            options.setPrompt("Scan the host's Checkers QR code")
            options.setCameraId(0)
            options.setOrientationLocked(false)
            options.setBeepEnabled(true)
            qrScanLauncher.launch(options)
        } catch (e: Exception) {
            ErrorLogger.log(ErrorLogger.Codes.QR_SCAN_FAILED, "Failed to start QR scanner", e)
            Toast.makeText(this, "Could not open camera scanner", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
    }

    // ------------------------------------------------------------------
    // P2P listener
    // ------------------------------------------------------------------

    override fun onConnected(role: P2PManager.Role, remoteName: String) {
        uiHandler.post {
            if (startedGame) return@post
            startedGame = true
            setStatus(getString(R.string.online_connected), false)
            val game = Intent(this, GameActivity::class.java)
            game.putExtra(GameActivity.EXTRA_MODE, GameActivity.MODE_ONLINE)
            game.putExtra(GameActivity.EXTRA_ONLINE_ROLE,
                if (role == P2PManager.Role.HOST) GameActivity.ROLE_HOST else GameActivity.ROLE_CLIENT)
            startActivity(game)
        }
    }

    override fun onMoveReceived(data: String) {
        // Not relevant to the pairing screen; the game handles moves.
    }

    override fun onPeerResigned() {
        // Not relevant at pairing time.
    }

    override fun onPeerDisconnected(reason: String) {
        uiHandler.post {
            if (!startedGame) {
                setStatus("Connection lost: $reason", true)
            }
        }
    }

    override fun onError(errorCode: String, message: String) {
        uiHandler.post {
            setStatus("$errorCode — $message", true)
        }
    }

    private fun setStatus(text: String, isError: Boolean) {
        tvStatus.text = text
        tvStatus.setTextColor(
            if (isError) 0xFFFF6E6E.toInt() else 0xFF9E9E9E.toInt()
        )
    }
}