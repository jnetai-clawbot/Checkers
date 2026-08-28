package com.jnetai.checkers.utils

import android.content.Context
import android.content.SharedPreferences
import com.jnetai.checkers.game.AiDifficulty
import org.json.JSONArray
import org.json.JSONObject

/**
 * High score board. Entries persist locally (offline) in SharedPreferences as
 * JSON. Highest score (fewest moves, fastest when timed) ranks first.
 */
class HighScoreStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class ScoreEntry(
        val name: String,
        val score: Int,
        val moves: Int,
        val seconds: Int,
        val difficulty: String,
        val timestamp: Long
    )

    companion object {
        private const val PREFS_NAME = "checkers_highscores"
        private const val KEY_ENTRIES = "entries"
        private const val MAX_ENTRIES = 10
        private val LOCK = Any()

        @Volatile
        private var instance: HighScoreStore? = null

        fun getInstance(context: Context): HighScoreStore {
            return instance ?: synchronized(this) {
                instance ?: HighScoreStore(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Score formula: difficulty base + efficiency bonus for few moves and
         * a small reward for speed when the game is timed.
         */
        fun computeScore(moves: Int, seconds: Int, difficulty: AiDifficulty): Int {
            val base = when (difficulty) {
                AiDifficulty.EASY -> 800
                AiDifficulty.MEDIUM -> 1600
                AiDifficulty.HARD -> 3000
            }
            val moveBonus = (5000 - moves * 20).coerceAtLeast(0)
            val timePenalty = seconds / 10
            return (base + moveBonus - timePenalty).coerceAtLeast(1)
        }
    }

    fun getEntries(): List<ScoreEntry> {
        return synchronized(LOCK) {
            try {
                val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
                val arr = JSONArray(raw)
                val list = mutableListOf<ScoreEntry>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(ScoreEntry(
                        name = o.optString("name", "Player"),
                        score = o.optInt("score", 0),
                        moves = o.optInt("moves", 0),
                        seconds = o.optInt("seconds", 0),
                        difficulty = o.optString("difficulty", ""),
                        timestamp = o.optLong("timestamp", 0L)
                    ))
                }
                list.sortedByDescending { it.score }
            } catch (e: Exception) {
                ErrorLogger.log(ErrorLogger.Codes.HS_PARSE_FAILED, "Failed to parse score list", e)
                emptyList()
            }
        }
    }

    fun getBestScore(): Int = getEntries().firstOrNull()?.score ?: 0

    /**
     * Add an entry; returns true when it made the top 10 (a notable score).
     */
    fun addEntry(entry: ScoreEntry): Boolean {
        return synchronized(LOCK) {
            try {
                val current = getEntries().toMutableList()
                current.add(entry)
                val sorted = current.sortedByDescending { it.score }.take(MAX_ENTRIES)

                val arr = JSONArray()
                for (e in sorted) {
                    val o = JSONObject()
                    o.put("name", e.name.take(20))
                    o.put("score", e.score)
                    o.put("moves", e.moves)
                    o.put("seconds", e.seconds)
                    o.put("difficulty", e.difficulty)
                    o.put("timestamp", e.timestamp)
                    arr.put(o)
                }
                prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply()

                sorted.any { it.name == entry.name && it.score == entry.score && it.timestamp == entry.timestamp }
            } catch (e: Exception) {
                ErrorLogger.logf(ErrorLogger.Codes.HS_SAVE_FAILED,
                    "Failed to persist high score entry for %s", e, entry.name)
                false
            }
        }
    }

    fun clear() {
        synchronized(LOCK) {
            prefs.edit().remove(KEY_ENTRIES).apply()
        }
    }
}