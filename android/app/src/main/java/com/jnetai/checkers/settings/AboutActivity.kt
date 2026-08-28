package com.jnetai.checkers.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.jnetai.checkers.BuildConfig
import com.jnetai.checkers.R
import com.jnetai.checkers.utils.ErrorLogger
import com.jnetai.checkers.utils.UpdateChecker
import java.util.concurrent.Executors

/**
 * About section: version (matching the GitHub release tag), developer credit,
 * check-for-update (with a graceful fallback to the repo page) and share.
 */
class AboutActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "checkers-update-checker")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        try {
            findViewById<TextView>(R.id.tvVersion).text =
                getString(R.string.about_version, BuildConfig.VERSION_NAME)

            findViewById<Button>(R.id.btnVisitSite).setOnClickListener {
                openUrl("https://jnetai.com")
            }

            findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener { checkForUpdate() }

            findViewById<Button>(R.id.btnShareApp).setOnClickListener { shareApp() }
        } catch (e: Exception) {
            ErrorLogger.log(ErrorLogger.Codes.SYS_UI_THREAD, "Failed to initialise About UI", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { executor.shutdownNow() } catch (_: Exception) { }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            ErrorLogger.logf(ErrorLogger.Codes.SYS_UI_THREAD,
                "Failed to open URL: %s", e, url)
            Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkForUpdate() {
        Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show()

        executor.execute {
            val info = UpdateChecker.checkForUpdate(this, BuildConfig.VERSION_NAME)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread

                // Fallback: any failure simply opens the release page.
                if (info.errorMessage != null || info.latestVersion.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.check_for_update))
                        .setMessage("Could not check automatically.\nOpening the release page instead.")
                        .setPositiveButton("Open") { _, _ ->
                            openUrl(UpdateChecker.getReleasesUrl())
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    return@runOnUiThread
                }

                if (info.isUpdateAvailable) {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.update_available, info.latestVersion))
                        .setMessage("An update v${BuildConfig.VERSION_NAME} → ${info.latestVersion} is available.")
                        .setPositiveButton(getString(R.string.download_update)) { _, _ ->
                            openUrl(UpdateChecker.getReleasesUrl())
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.no_update, info.latestVersion))
                        .setMessage("You are on the latest version.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun shareApp() {
        try {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Checkers for Android")
                putExtra(Intent.EXTRA_TEXT,
                    "I play Checkers on Android! Download yours here:\n${UpdateChecker.getReleasesUrl()}")
            }
            startActivity(Intent.createChooser(share, "Share Checkers"))
        } catch (e: Exception) {
            ErrorLogger.log(ErrorLogger.Codes.SYS_UI_THREAD, "Failed to share app", e)
            Toast.makeText(this, "Could not open the share menu", Toast.LENGTH_SHORT).show()
        }
    }
}