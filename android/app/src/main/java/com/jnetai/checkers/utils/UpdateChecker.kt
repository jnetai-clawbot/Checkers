package com.jnetai.checkers.utils

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the latest published release version against the installed one.
 *
 * If the network/API request ever fails the UI falls back to simply opening
 * the repo releases page (see [getReleasesUrl]).
 */
object UpdateChecker {

    const val GITHUB_REPO = "jnetai-clawbot/Checkers"
    private const val GITHUB_API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
    private const val GITHUB_RELEASES_URL = "https://github.com/$GITHUB_REPO/releases"
    private const val CONNECT_TIMEOUT = 8000
    private const val READ_TIMEOUT = 8000

    data class UpdateInfo(
        val latestVersion: String,
        val releaseUrl: String,
        val isUpdateAvailable: Boolean,
        val errorMessage: String? = null
    )

    fun checkForUpdate(context: Context, currentVersion: String): UpdateInfo {
        if (!isNetworkAvailable(context)) {
            return UpdateInfo("", "", false,
                "No network connection available. Please check your internet.")
        }

        return try {
            val url = URL(GITHUB_API_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Checkers-Android")

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()
                connection.disconnect()

                val tagName = extractTagName(response)
                if (tagName != null) {
                    val isUpdate = compareVersions(currentVersion, tagName) < 0
                    UpdateInfo(
                        latestVersion = tagName,
                        releaseUrl = GITHUB_RELEASES_URL,
                        isUpdateAvailable = isUpdate
                    )
                } else {
                    UpdateInfo("", "", false, "Could not parse update information.")
                }
            } else {
                UpdateInfo("", "", false, "GitHub API returned status $responseCode")
            }
        } catch (e: Exception) {
            ErrorLogger.log(ErrorLogger.Codes.UPD_CHECK_FAILED, "Network request failed", e)
            UpdateInfo("", "", false, "Network error: ${e.localizedMessage ?: "Unknown error"}")
        }
    }

    /** The fallback target that is always shown if the API check fails. */
    fun getReleasesUrl(): String = GITHUB_RELEASES_URL

    /** Build a browser intent for the releases page (fallback path). */
    fun openReleasesIntent(context: Context): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_RELEASES_URL))
    }

    private fun extractTagName(json: String): String? {
        return try {
            val key = "\"tag_name\":"
            val startIndex = json.indexOf(key)
            if (startIndex >= 0) {
                val valueStart = startIndex + key.length
                val quoteStart = json.indexOf('"', valueStart)
                if (quoteStart >= 0) {
                    val quoteEnd = json.indexOf('"', quoteStart + 1)
                    if (quoteEnd >= 0) json.substring(quoteStart + 1, quoteEnd) else null
                } else null
            } else null
        } catch (e: Exception) {
            ErrorLogger.log(ErrorLogger.Codes.UPD_PARSE_FAILED, "Failed to parse tag_name", e)
            null
        }
    }

    /** Compare two dotted semantic versions; returns negative if v1 < v2. */
    fun compareVersions(v1: String, v2: String): Int {
        val cleanV1 = v1.trimStart('v', 'V')
        val cleanV2 = v2.trimStart('v', 'V')
        val parts1 = cleanV1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = cleanV2.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1 - p2
        }
        return 0
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }
}