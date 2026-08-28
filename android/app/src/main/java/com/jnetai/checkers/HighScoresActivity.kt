package com.jnetai.checkers

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.jnetai.checkers.utils.ErrorLogger
import com.jnetai.checkers.utils.HighScoreStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The high score board (reachable from the main menu, before or after a game).
 */
class HighScoresActivity : AppCompatActivity() {

    private lateinit var listScores: ListView
    private lateinit var tvEmpty: TextView

    private val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_highscores)

        listScores = findViewById(R.id.listScores)
        tvEmpty = findViewById(R.id.tvEmpty)
        val btnClear = findViewById<Button>(R.id.btnClearScores)

        refreshList()

        btnClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear high scores?")
                .setMessage("This cannot be undone.")
                .setPositiveButton("Clear") { _, _ ->
                    HighScoreStore.getInstance(this).clear()
                    refreshList()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun refreshList() {
        try {
            val entries = HighScoreStore.getInstance(this).getEntries()
            if (entries.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                listScores.visibility = View.GONE
            } else {
                tvEmpty.visibility = View.GONE
                listScores.visibility = View.VISIBLE
                listScores.adapter = ScoreAdapter(entries)
            }
        } catch (e: Exception) {
            ErrorLogger.log(ErrorLogger.Codes.HS_LOAD_FAILED, "Failed to load scores", e)
            Toast.makeText(this, "Could not load high scores", Toast.LENGTH_SHORT).show()
        }
    }

    private inner class ScoreAdapter(
        private val entries: List<HighScoreStore.ScoreEntry>
    ) : BaseAdapter() {

        override fun getCount(): Int = entries.size
        override fun getItem(pos: Int): Any = entries[pos]
        override fun getItemId(pos: Int): Long = pos.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(
                android.R.layout.simple_list_item_2, parent, false
            )
            val entry = entries[position]

            val title = v.findViewById<TextView>(android.R.id.text1)
            val sub = v.findViewById<TextView>(android.R.id.text2)
            title.text = "${position + 1}. ${entry.name}  —  ${entry.score} pts"
            val timePart = if (entry.seconds > 0) "  •  ${formatDuration(entry.seconds)}" else ""
            sub.text = "${entry.moves} moves$timePart  •  ${dateFmt.format(Date(entry.timestamp))}"
            return v
        }
    }

    private fun formatDuration(totalSec: Int): String {
        val m = totalSec / 60
        val s = totalSec % 60
        return "$m:${if (s < 10) "0" else ""}$s"
    }
}