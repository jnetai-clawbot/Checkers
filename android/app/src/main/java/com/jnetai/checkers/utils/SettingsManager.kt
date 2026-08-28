package com.jnetai.checkers.utils

import android.content.Context
import android.content.SharedPreferences
import com.jnetai.checkers.game.AiDifficulty
import com.jnetai.checkers.game.GameDefs
import com.jnetai.checkers.game.RulePreset

/**
 * Centralised settings storage. All values survive app restarts.
 */
class SettingsManager private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    object Keys {
        const val AI_DIFFICULTY = "ai_difficulty"
        const val RULE_PRESET = "rule_preset"
        const val TIMER_MINUTES = "timer_minutes"
        const val SOUND_ENABLED = "sound_enabled"
    }

    // ----- AI difficulty -----
    fun setAiDifficulty(diff: AiDifficulty) {
        prefs.edit().putString(Keys.AI_DIFFICULTY, diff.name).apply()
    }

    fun getAiDifficulty(): AiDifficulty {
        val name = prefs.getString(Keys.AI_DIFFICULTY, AiDifficulty.EASY.name) ?: AiDifficulty.EASY.name
        return try {
            AiDifficulty.valueOf(name)
        } catch (e: IllegalArgumentException) {
            ErrorLogger.logf(ErrorLogger.Codes.SET_INVALID_VALUE,
                "Unknown stored AI difficulty '%s', resetting to EASY", name)
            AiDifficulty.EASY
        }
    }

    // ----- Rules preset -----
    fun setRulePreset(preset: RulePreset) {
        prefs.edit().putString(Keys.RULE_PRESET, preset.name).apply()
    }

    fun getRulePreset(): RulePreset {
        val name = prefs.getString(Keys.RULE_PRESET, RulePreset.UK_EUROPE.name)
            ?: RulePreset.UK_EUROPE.name
        return try {
            RulePreset.valueOf(name)
        } catch (e: IllegalArgumentException) {
            ErrorLogger.logf(ErrorLogger.Codes.SET_INVALID_VALUE,
                "Unknown stored rules preset '%s', resetting to UK_EUROPE", name)
            RulePreset.UK_EUROPE
        }
    }

    // ----- Timer (minutes per player, 0 = off) -----
    fun setTimerMinutes(minutes: Int) {
        prefs.edit().putInt(Keys.TIMER_MINUTES, minutes.coerceIn(0, 60)).apply()
    }

    fun getTimerMinutes(): Int = prefs.getInt(Keys.TIMER_MINUTES, 0)

    fun isTimerEnabled(): Boolean = getTimerMinutes() > 0

    // ----- Sound effects (safe default on) -----
    fun setSoundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(Keys.SOUND_ENABLED, enabled).apply()
    }

    fun isSoundEnabled(): Boolean = prefs.getBoolean(Keys.SOUND_ENABLED, true)

    companion object {
        private const val PREFS_NAME = "checkers_settings"

        @Volatile
        private var instance: SettingsManager? = null

        fun getInstance(context: Context): SettingsManager {
            return instance ?: synchronized(this) {
                instance ?: SettingsManager(context.applicationContext).also { instance = it }
            }
        }
    }
}