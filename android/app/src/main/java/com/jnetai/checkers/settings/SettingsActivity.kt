package com.jnetai.checkers.settings

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.jnetai.checkers.R
import com.jnetai.checkers.game.AiDifficulty
import com.jnetai.checkers.game.RulePreset
import com.jnetai.checkers.utils.ErrorLogger
import com.jnetai.checkers.utils.SettingsManager
import com.jnetai.checkers.utils.SoundEffects

/**
 * Settings: AI difficulty, worldwide rule presets, per-player timer and sound.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var rgDifficulty: RadioGroup
    private lateinit var rgRules: RadioGroup
    private lateinit var spinnerTimer: Spinner
    private lateinit var swSound: SwitchMaterial

    private val settingsManager by lazy { SettingsManager.getInstance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        try {
            rgDifficulty = findViewById(R.id.rgDifficulty)
            rgRules = findViewById(R.id.rgRules)
            spinnerTimer = findViewById(R.id.spinnerTimer)
            swSound = findViewById(R.id.swSound)
        } catch (e: Exception) {
            ErrorLogger.log(ErrorLogger.Codes.UI_VIEW_BINDING, "Failed to bind settings views", e)
            Toast.makeText(this, "Settings error - E-UI-002", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        loadCurrentValues()

        // Save handlers
        rgDifficulty.setOnCheckedChangeListener { _, checkedId ->
            val diff = when (checkedId) {
                R.id.rbEasy -> AiDifficulty.EASY
                R.id.rbMedium -> AiDifficulty.MEDIUM
                R.id.rbHard -> AiDifficulty.HARD
                else -> return@setOnCheckedChangeListener
            }
            settingsManager.setAiDifficulty(diff)
        }

        rgRules.setOnCheckedChangeListener { _, checkedId ->
            val preset = when (checkedId) {
                R.id.rbRulesUk -> RulePreset.UK_EUROPE
                R.id.rbRulesUs -> RulePreset.US_AMERICAN
                R.id.rbRulesIntl -> RulePreset.INTERNATIONAL
                else -> return@setOnCheckedChangeListener
            }
            settingsManager.setRulePreset(preset)
        }

        spinnerTimer.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: android.view.View?, pos: Int, id: Long) {
                val minutes = when (pos) {
                    0 -> 0
                    1 -> 3
                    2 -> 5
                    else -> 10
                }
                settingsManager.setTimerMinutes(minutes)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>) { }
        }

        swSound.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.setSoundEnabled(isChecked)
            SoundEffects.setEnabled(isChecked)
        }
    }

    private fun loadCurrentValues() {
        // Difficulty
        when (settingsManager.getAiDifficulty()) {
            AiDifficulty.EASY -> rgDifficulty.check(R.id.rbEasy)
            AiDifficulty.MEDIUM -> rgDifficulty.check(R.id.rbMedium)
            AiDifficulty.HARD -> rgDifficulty.check(R.id.rbHard)
        }

        // Rules
        when (settingsManager.getRulePreset()) {
            RulePreset.UK_EUROPE -> rgRules.check(R.id.rbRulesUk)
            RulePreset.US_AMERICAN -> rgRules.check(R.id.rbRulesUs)
            RulePreset.INTERNATIONAL -> rgRules.check(R.id.rbRulesIntl)
        }

        // Timer dropdown
        val titles = resources.getStringArray(R.array.timer_options)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, titles)
        spinnerTimer.adapter = adapter
        val minutes = settingsManager.getTimerMinutes()
        spinnerTimer.setSelection(
            when (minutes) {
                0 -> 0
                3 -> 1
                5 -> 2
                else -> 3
            }
        )

        // Sound
        swSound.isChecked = settingsManager.isSoundEnabled()
    }
}