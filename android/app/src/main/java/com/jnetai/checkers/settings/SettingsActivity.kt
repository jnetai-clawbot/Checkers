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
import com.jnetai.checkers.utils.BoardColors
import com.jnetai.checkers.utils.ErrorLogger
import com.jnetai.checkers.utils.SettingsManager
import com.jnetai.checkers.utils.SoundEffects

/**
 * Settings: AI difficulty, worldwide rule presets, per-player timer, board
 * colours and sound.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var rgDifficulty: RadioGroup
    private lateinit var rgRules: RadioGroup
    private lateinit var spinnerTimer: Spinner
    private lateinit var spinnerAiDelay: Spinner
    private lateinit var spinnerP1Colour: Spinner
    private lateinit var spinnerP2Colour: Spinner
    private lateinit var spinnerSquareColour: Spinner
    private lateinit var swSound: SwitchMaterial

    private val settingsManager by lazy { SettingsManager.getInstance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        try {
            rgDifficulty = findViewById(R.id.rgDifficulty)
            rgRules = findViewById(R.id.rgRules)
            spinnerTimer = findViewById(R.id.spinnerTimer)
            spinnerAiDelay = findViewById(R.id.spinnerAiDelay)
            spinnerP1Colour = findViewById(R.id.spinnerP1Colour)
            spinnerP2Colour = findViewById(R.id.spinnerP2Colour)
            spinnerSquareColour = findViewById(R.id.spinnerSquareColour)
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

        spinnerAiDelay.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: android.view.View?, pos: Int, id: Long) {
                settingsManager.setAiThinkSeconds(pos)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>) { }
        }

        setupColourSpinner(spinnerP1Colour, 0) { pos ->
            settingsManager.setPieceColorP1(BoardColors.PieceColor.values()[pos])
        }
        setupColourSpinner(spinnerP2Colour, 1) { pos ->
            settingsManager.setPieceColorP2(BoardColors.PieceColor.values()[pos])
        }
        setupSquareSpinner(spinnerSquareColour) { pos ->
            settingsManager.setBoardDarkSquare(BoardColors.SquareColor.values()[pos])
        }

        swSound.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.setSoundEnabled(isChecked)
            SoundEffects.setEnabled(isChecked)
        }
    }

    /**
     * Wire a piece-colour spinner, keeping player 1 and player 2 distinct so
     * both sides can always be told apart.
     */
    private fun setupColourSpinner(spinner: Spinner, player: Int, onSelected: (Int) -> Unit) {
        val titles = resources.getStringArray(R.array.piece_colour_options)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, titles)
        spinner.adapter = adapter
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            private var programmatic = true

            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                pos: Int,
                id: Long
            ) {
                if (programmatic) { programmatic = false; return }
                val colours = BoardColors.PieceColor.values()
                val chosen = colours[pos]
                val other = settingsManager.run {
                    if (player == 0) getPieceColorP2() else getPieceColorP1()
                }
                if (chosen == other) {
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.settings_colours_pieces_different),
                        Toast.LENGTH_SHORT
                    ).show()
                    // Revert to the previous value.
                    val current = settingsManager.run {
                        if (player == 0) getPieceColorP1() else getPieceColorP2()
                    }
                    programmatic = true
                    spinner.setSelection(colours.indexOfFirst { it == current })
                } else {
                    onSelected(pos)
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>) { }
        }
        val initial = settingsManager.run {
            if (player == 0) getPieceColorP1() else getPieceColorP2()
        }
        spinner.setSelection(BoardColors.PieceColor.values().indexOfFirst { it == initial })
    }

    private fun setupSquareSpinner(spinner: Spinner, onSelected: (Int) -> Unit) {
        val titles = resources.getStringArray(R.array.square_colour_options)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, titles)
        spinner.adapter = adapter
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                onSelected(pos)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>) { }
        }
        spinner.setSelection(
            BoardColors.SquareColor.values().indexOfFirst { it == settingsManager.getBoardDarkSquare() }
        )
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

        // AI reply delay dropdown
        val aiDelayTitles = resources.getStringArray(R.array.ai_delay_options)
        val aiDelayAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, aiDelayTitles)
        spinnerAiDelay.adapter = aiDelayAdapter
        spinnerAiDelay.setSelection(settingsManager.getAiThinkSeconds())

        // Sound
        swSound.isChecked = settingsManager.isSoundEnabled()
    }
}