package com.jnetai.checkers

import android.app.Application
import android.util.Log
import com.jnetai.checkers.utils.ErrorLogger

/**
 * Application entry point. Installs a global uncaught-exception logger so no
 * crash ever happens silently.
 */
class CheckersApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Global crash handler: log before the default handler terminates the app.
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            ErrorLogger.log(ErrorLogger.Codes.SYS_UNEXPECTED,
                "Uncaught exception on thread ${thread.name}", throwable)
            try {
                Log.e("Checkers", "Uncaught exception", throwable)
            } catch (_: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
                ?: run { throw throwable }
        }

        ErrorLogger.logf(ErrorLogger.Codes.SYS_START,
            "Checkers application starting (v%s code %d)",
            BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
    }
}