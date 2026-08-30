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
        const val AI_THINK_SECONDS = "ai_think_seconds"
        const val RULE_PRESET = "rule_preset"
        const val TIMER_MINUTES = "timer_minutes"
        const val SOUND_ENABLED = "sound_enabled"
        const val PIECE_COLOR_P1 = "piece_color_p1"
        const val PIECE_COLOR_P2 = "piece_color_p2"
        const val BOARD_DARK_SQUARE = "board_dark_square"
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

    // ----- AI reply delay (seconds before the AI "moves"; 0 = instant) -----
    fun setAiThinkSeconds(seconds: Int) {
        prefs.edit().putInt(Keys.AI_THINK_SECONDS, seconds.coerceIn(0, 3)).apply()
    }

    fun getAiThinkSeconds(): Int = prefs.getInt(Keys.AI_THINK_SECONDS, 2)

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

    // ----- Colours -----
    fun setPieceColorP1(color: BoardColors.PieceColor) {
        prefs.edit().putString(Keys.PIECE_COLOR_P1, color.name).apply()
    }

    fun getPieceColorP1(): BoardColors.PieceColor {
        return enumOrDefault(
            Keys.PIECE_COLOR_P1, BoardColors.PieceColor.RED.name,
            BoardColors.PieceColor.RED
        )
    }

    fun setPieceColorP2(color: BoardColors.PieceColor) {
        prefs.edit().putString(Keys.PIECE_COLOR_P2, color.name).apply()
    }

    fun getPieceColorP2(): BoardColors.PieceColor {
        return enumOrDefault(
            Keys.PIECE_COLOR_P2, BoardColors.PieceColor.BLUE.name,
            BoardColors.PieceColor.BLUE
        )
    }

    fun setBoardDarkSquare(color: BoardColors.SquareColor) {
        prefs.edit().putString(Keys.BOARD_DARK_SQUARE, color.name).apply()
    }

    fun getBoardDarkSquare(): BoardColors.SquareColor {
        return enumOrDefault(
            Keys.BOARD_DARK_SQUARE, BoardColors.SquareColor.BROWN.name,
            BoardColors.SquareColor.BROWN
        )
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(key: String, defaultName: String, default: T): T {
        val name = prefs.getString(key, defaultName) ?: defaultName
        return try {
            enumValueOf<T>(name)
        } catch (e: IllegalArgumentException) {
            ErrorLogger.logf(ErrorLogger.Codes.SET_INVALID_VALUE,
                "Unknown stored colour '%s' for %s, resetting to %s", name, key, defaultName)
            default
        }
    }

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