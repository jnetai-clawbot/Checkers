package com.jnetai.checkers.utils

import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ErrorLogger - persistent error tracking and debugging system.
 *
 * Generates a unique error code, logs stack traces and stores diagnostic
 * information for every failure/exception in the app. Codes stay permanently
 * integrated so future bugs can be traced quickly.
 *
 * Code format: E-<MODULE>-NNN
 */
object ErrorLogger {

    private const val TAG = "Checkers-DEBUG"
    private val errorLog = mutableListOf<ErrorRecord>()
    private val lock = Any()

    data class ErrorRecord(
        val errorCode: String,
        val message: String,
        val exception: Throwable?,
        val timestamp: Long,
        val threadName: String,
        val stackTrace: String
    )

    /** Module error code constants. */
    object Codes {
        const val SYS_START = "E-SYS-001"
        const val SYS_UI_THREAD = "E-SYS-002"
        const val SYS_UNEXPECTED = "E-SYS-999"

        const val GMB_INVALID_SQUARE = "E-GMB-001"
        const val GMB_ILLEGAL_MOVE = "E-GMB-002"
        const val GMB_APPLY_INVALID = "E-GMB-003"
        const val GMB_INVALID_STATE = "E-GMB-004"
        const val GMB_RESTORE_MISMATCH = "E-GMB-005"
        const val GMB_UNDO_FAILED = "E-GMB-006"
        const val GMB_SAVE_FAILED = "E-GMB-007"
        const val GMB_LOAD_FAILED = "E-GMB-008"

        const val AI_SEARCH_FAILED = "E-AI-001"
        const val AI_INVALID_MOVE = "E-AI-002"
        const val AI_NO_MOVE = "E-AI-003"

        const val SET_LOAD_FAILED = "E-SET-001"
        const val SET_SAVE_FAILED = "E-SET-002"
        const val SET_INVALID_VALUE = "E-SET-003"

        const val HS_LOAD_FAILED = "E-HS-001"
        const val HS_SAVE_FAILED = "E-HS-002"
        const val HS_PARSE_FAILED = "E-HS-003"

        const val UPD_CHECK_FAILED = "E-UPD-001"
        const val UPD_PARSE_FAILED = "E-UPD-002"
        const val UPD_NETWORK = "E-UPD-003"

        const val NET_HOST_FAILED = "E-NET-001"
        const val NET_JOIN_FAILED = "E-NET-002"
        const val NET_SEND_FAILED = "E-NET-003"
        const val NET_RECEIVE_FAILED = "E-NET-004"
        const val NET_PROTOCOL = "E-NET-005"
        const val NET_CLOSED = "E-NET-006"
        const val NET_QR_INVALID = "E-NET-007"

        const val QR_GENERATE_FAILED = "E-QR-001"
        const val QR_SCAN_FAILED = "E-QR-002"

        const val UI_BOARD_DRAW = "E-UI-001"
        const val UI_VIEW_BINDING = "E-UI-002"
    }

    /**
     * Record and log an error. Always safe to call from any thread.
     */
    fun log(errorCode: String, message: String, exception: Throwable? = null) {
        val stackInfo = if (exception != null) {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            exception.printStackTrace(pw)
            sw.toString()
        } else {
            Throwable().stackTraceToString()
        }

        val record = ErrorRecord(
            errorCode = errorCode,
            message = message,
            exception = exception,
            timestamp = System.currentTimeMillis(),
            threadName = Thread.currentThread().name,
            stackTrace = stackInfo
        )

        synchronized(lock) {
            errorLog.add(record)
            if (errorLog.size > 1000) {
                errorLog.removeAt(0)
            }
        }

        Log.e(TAG, "[$errorCode] $message")
        if (exception != null) {
            Log.e(TAG, "[$errorCode] Exception:", exception)
        }
        Log.e(TAG, "[$errorCode] Stack:\n$stackInfo")
    }

    /** Formatted-message overload. */
    fun logf(errorCode: String, format: String, vararg args: Any?) {
        log(errorCode, String.format(Locale.US, format, *args))
    }

    /** Formatted-message overload with an exception attached. */
    fun logf(errorCode: String, format: String, exception: Throwable?, vararg args: Any?) {
        log(errorCode, String.format(Locale.US, format, *args), exception)
    }

    fun getErrorLog(): List<ErrorRecord> = synchronized(lock) { errorLog.toList() }

    fun getRecentErrors(count: Int = 10): List<ErrorRecord> =
        synchronized(lock) { errorLog.takeLast(count).reversed() }

    fun clearLog() {
        synchronized(lock) { errorLog.clear() }
    }

    /** Format a human readable diagnostic report. */
    fun formatErrorReport(errorCode: String, message: String, exception: Throwable?): String {
        val sb = StringBuilder()
        sb.appendLine("════════════════════════════════════════")
        sb.appendLine("ERROR REPORT")
        sb.appendLine("════════════════════════════════════════")
        sb.appendLine("Error Code: $errorCode")
        sb.appendLine("Message:    $message")
        sb.appendLine("Timestamp:  ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
        sb.appendLine("Thread:     ${Thread.currentThread().name}")
        if (exception != null) {
            sb.appendLine("Exception:  ${exception.javaClass.name}: ${exception.message}")
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            exception.printStackTrace(pw)
            sb.appendLine("Stack Trace:\n${sw.toString().take(2000)}")
        }
        sb.appendLine("════════════════════════════════════════")
        return sb.toString()
    }

    /** Try/catch wrapper that logs and returns null when the block throws. */
    inline fun <T> tryOrNull(errorCode: String, message: String, block: () -> T): T? {
        return try {
            block()
        } catch (e: Exception) {
            log(errorCode, message, e)
            null
        }
    }
}