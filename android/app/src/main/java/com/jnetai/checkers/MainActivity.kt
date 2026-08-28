package com.jnetai.checkers

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.jnetai.checkers.settings.AboutActivity
import com.jnetai.checkers.settings.SettingsActivity
import com.jnetai.checkers.utils.ErrorLogger
import com.jnetai.checkers.utils.SettingsManager
import com.jnetai.checkers.utils.SoundEffects

/**
 * Main menu: choose mode, view high scores, settings or about.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        SoundEffects.setEnabled(SettingsManager.getInstance(this).isSoundEnabled())

        try {
            findViewById<Button>(R.id.btnPlayAi).setOnClickListener { startGame(GameActivity.MODE_AI) }
            findViewById<Button>(R.id.btnPlay2p).setOnClickListener { startGame(GameActivity.MODE_2P) }
            findViewById<Button>(R.id.btnPlayOnline).setOnClickListener {
                startActivity(Intent(this, OnlineActivity::class.java))
            }
            findViewById<Button>(R.id.btnHighScores).setOnClickListener {
                startActivity(Intent(this, HighScoresActivity::class.java))
            }
            findViewById<Button>(R.id.btnSettings).setOnClickListener {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            findViewById<Button>(R.id.btnAbout).setOnClickListener {
                startActivity(Intent(this, AboutActivity::class.java))
            }
        } catch (e: Exception) {
            ErrorLogger.log(ErrorLogger.Codes.UI_VIEW_BINDING,
                "Failed to bind main menu buttons", e)
        }
    }

    private fun startGame(mode: String) {
        val i = Intent(this, GameActivity::class.java)
        i.putExtra(GameActivity.EXTRA_MODE, mode)
        startActivity(i)
    }
}