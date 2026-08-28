package com.jnetai.checkers.utils

import android.media.AudioManager
import android.media.ToneGenerator
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight sound effects using the system tone generator (no audio assets
 * needed). Respects the in-app sound setting.
 */
object SoundEffects {

    private val enabled = AtomicBoolean(true)
    private var toneGen: ToneGenerator? = null

    fun setEnabled(on: Boolean) {
        enabled.set(on)
    }

    fun isEnabled(): Boolean = enabled.get()

    private fun generator(): ToneGenerator? {
        if (toneGen == null) {
            toneGen = try {
                ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            } catch (e: Exception) {
                ErrorLogger.logf(ErrorLogger.Codes.SYS_UNEXPECTED,
                    "Could not create ToneGenerator", e)
                null
            }
        }
        return toneGen
    }

    /** Short click for a simple move. */
    fun playMove() {
        if (!enabled.get()) return
        try {
            generator()?.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
        } catch (_: Exception) { }
    }

    /** Slightly longer tone for a capture. */
    fun playCapture() {
        if (!enabled.get()) return
        try {
            generator()?.startTone(ToneGenerator.TONE_PROP_BEEP2, 120)
        } catch (_: Exception) { }
    }
}