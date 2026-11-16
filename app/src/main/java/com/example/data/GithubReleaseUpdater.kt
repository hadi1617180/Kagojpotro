package com.example.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.BuildConfig
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class GithubReleaseAssetInfo(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long
)

data class GithubReleaseUpdateInfo(
    val repoSlug: String,
    val tagName: String,
    val remoteVersionName: String,
    val currentVersionName: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val htmlUrl: String,
    val publishedAt: String,
    val apkAsset: GithubReleaseAssetInfo?,
    val pcScriptAsset: GithubReleaseAssetInfo?,
    val isUpdateAvailable: Boolean
)

sealed class GithubUpdateCheckResult {
    data class UpdateAvailable(val info: GithubReleaseUpdateInfo) : GithubUpdateCheckResult()
    data class UpToDate(val info: GithubReleaseUpdateInfo) : GithubUpdateCheckResult()
    data class Error(val message: String) : GithubUpdateCheckResult()
}

data class AppUpdateUiState(
    val repoSlug: String = GithubReleaseUpdater.DEFAULT_GITHUB_REPO,
    val currentVersionName: String = BuildConfig.VERSION_NAME,
    val currentVersionCode: Int = BuildConfig.VERSION_CODE,
    val isChecking: Boolean = false,
    val isDownloadingApk: Boolean = false,
    val downloadProgressPercent: Int = 0,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val availableUpdate: GithubReleaseUpdateInfo? = null,
    val latestCheckedInfo: GithubReleaseUpdateInfo? = null,
    val downloadedApkFilePath: String? = null,
    val showUpdateDialog: Boolean = false,
    val lastStatusMessage: String? = null,
    val lastError: String? = null
)

/**
 * GitHub Releases In-App Self-Updater ("Option 1").
 *
 * Queries `https://api.github.com/repos/{owner}/{repo}/releases/latest`, compares semantic version
 * numbers against `BuildConfig.VERSION_NAME` / `BuildConfig.VERSION_CODE`, downloads the attached
 * `.apk` asset with live byte progress into `cacheDir/app_updates/`, and launches the Android
 * Package Installer via `FileProvider` so all user folders, scans, and settings remain intact.
 */
class GithubReleaseUpdater(private val context: Context) {

    companion object {
        const val DEFAULT_GITHUB_REPO = "hadi1617180/kagojpotro"
        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val READ_TIMEOUT_MS = 20_000

        /**
         * Normalizes a user-entered GitHub repository string (e.g. `https://github.com/owner/repo/releases`,
         * `@owner/repo`, or `owner/repo.git`) into clean `owner/repo` format.
         */
        fun normalizeRepoSlug(raw: String): String {
            var cleaned = raw.trim()
                .removePrefix("https://github.com/")
                .removePrefix("http://github.com/")
                .removePrefix("github.com/")
                .removePrefix("@")
                .trim('/')
            if (cleaned.endsWith(".git", ignoreCase = true)) {
                cleaned = cleaned.substring(0, cleaned.length - 4)
            }
            val segments = cleaned.split('/')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (segments.size < 2) return ""
            val owner = segments[0].replace(Regex("[^a-zA-Z0-9._-]"), "")
            val repo = segments[1].replace(Regex("[^a-zA-Z0-9._-]"), "")
            return if (owner.isNotEmpty() && repo.isNotEmpty()) "$owner/$repo" else ""
        }

        /**
         * Parses a version string such as `"v1.2.3"`, `"release-2.0"`, or `"1.0.4-beta1"` into
         * numeric components `[1, 2, 3]`.
         */
        fun parseVersionParts(rawVersion: String): List<Int> {
            val cleaned = rawVersion.trim()
            val match = Regex("(\\d+(?:\\.\\d+)*)").find(cleaned) ?: return emptyList()
            return match.value
                .split('.')
                .mapNotNull { it.toIntOrNull() }
        }

        /**
         * Returns `true` when [remoteTagOrVersion] is strictly newer than [currentVersionName]
         * (or when [remoteVersionCodeHint] > [currentVersionCode]).
         */
        fun isRemoteVersionNewer(
            remoteTagOrVersion: String,
            currentVersionName: String,
            currentVersionCode: Int = BuildConfig.VERSION_CODE,
            remoteVersionCodeHint: Int? = null
        ): Boolean {
            if (remoteVersionCodeHint != null && remoteVersionCodeHint > currentVersionCode) {
                return true
            }
            val remoteParts = parseVersionParts(remoteTagOrVersion)
            val localParts = parseVersionParts(currentVersionName)
            if (remoteParts.isEmpty()) return false
            if (localParts.isEmpty()) return true

            val maxLen = maxOf(remoteParts.size, localParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val l = localParts.getOrElse(i) { 0 }
                if (r > l) return true
                if (r < l) return false
            }
            // If numeric tuple has a single integer greater than versionCode (e.g. tag "v2" while versionName="1.0" and versionCode=1)
            if (remoteParts.size == 1 && remoteParts[0] > currentVersionCode) {
                return true
            }
            return false
        }

        /**
         * Parses a GitHub Releases API JSON payload into [GithubReleaseUpdateInfo].
         */
        fun parseLatestReleaseJson(
            jsonText: String,
            repoSlug: String,
            currentVersionName: String = BuildConfig.VERSION_NAME,
            currentVersionCode: Int = BuildConfig.VERSION_CODE
        ): GithubReleaseUpdateInfo {
            val obj = JSONObject(jsonText)
            val tagName = obj.optString("tag_name", "").trim()
            val releaseName = obj.optString("name", "").trim().ifBlank { tagName }
            val body = obj.optString("body", "").trim()
            val htmlUrl = obj.optString("html_url", "https://github.com/$repoSlug/releases/latest").trim()
            val publishedAt = obj.optString("published_at", "").trim()

            val assetsArr = obj.optJSONArray("assets") ?: JSONArray()
            var apkAsset: GithubReleaseAssetInfo? = null
            var pcScriptAsset: GithubReleaseAssetInfo? = null

            for (i in 0 until assetsArr.length()) {
                val assetObj = assetsArr.optJSONObject(i) ?: continue
                val assetName = assetObj.optString("name", "").trim()
                val downloadUrl = assetObj.optString("browser_download_url", "").trim()
                val sizeBytes = assetObj.optLong("size", 0L).coerceAtLeast(0L)
                if (assetName.isEmpty() || downloadUrl.isEmpty()) continue

                if (apkAsset == null && assetName.endsWith(".apk", ignoreCase = true)) {
                    apkAsset = GithubReleaseAssetInfo(
                        name = assetName,
                        downloadUrl = downloadUrl,
                        sizeBytes = sizeBytes
                    )
                } else if (pcScriptAsset == null && assetName.endsWith(".bat", ignoreCase = true)) {
                    pcScriptAsset = GithubReleaseAssetInfo(
                        name = assetName,
                        downloadUrl = downloadUrl,
                        sizeBytes = sizeBytes
                    )
                }
            }

            // Optional versionCode hint in release body: e.g. "versionCode: 2" or "[versionCode=2]"
            val versionCodeHint = Regex("versionCode\\s*[:=]\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(body)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            val effectiveVersionStr = tagName.ifBlank { releaseName }
            val cleanRemoteVersion = effectiveVersionStr.removePrefix("v").removePrefix("V").trim()
                .ifBlank { effectiveVersionStr }

            val newer = isRemoteVersionNewer(
                remoteTagOrVersion = effectiveVersionStr,
                currentVersionName = currentVersionName,
                currentVersionCode = currentVersionCode,
                remoteVersionCodeHint = versionCodeHint
            )

            return GithubReleaseUpdateInfo(
                repoSlug = repoSlug,
                tagName = tagName.ifBlank { "v$cleanRemoteVersion" },
                remoteVersionName = cleanRemoteVersion,
                currentVersionName = currentVersionName,
                releaseTitle = releaseName,
                releaseNotes = body,
                htmlUrl = htmlUrl,
                publishedAt = publishedAt,
                apkAsset = apkAsset,
                pcScriptAsset = pcScriptAsset,
                isUpdateAvailable = newer
            )
        }
    }

    val updatesCacheDir: File
        get() = File(context.cacheDir, "app_updates").apply { mkdirs() }

    /**
     * Queries GitHub Releases REST API (`GET /repos/{owner}/{repo}/releases/latest`)
     * or custom test endpoint [apiBaseUrlOverride] for unit tests.
     */
    suspend fun checkForLatestRelease(
        rawRepoSlug: String,
        currentVersionName: String = BuildConfig.VERSION_NAME,
        currentVersionCode: Int = BuildConfig.VERSION_CODE,
        apiBaseUrlOverride: String? = null
    ): GithubUpdateCheckResult = withContext(Dispatchers.IO) {
        val slug = normalizeRepoSlug(rawRepoSlug)
        if (slug.isBlank()) {
            return@withContext GithubUpdateCheckResult.Error(
                "Invalid GitHub repository format. Use owner/repo (e.g. $DEFAULT_GITHUB_REPO)."
            )
        }

        val base = (apiBaseUrlOverride ?: "https://api.github.com").trimEnd('/')
        val endpoint = "$base/repos/$slug/releases/latest"

        runCatching {
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Kagojpotro-Android-Updater/${BuildConfig.VERSION_NAME}")
            }
            try {
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                    return@withContext GithubUpdateCheckResult.Error(
                        "No update has been published yet."
                    )
                }
                if (code !in 200..299) {
                    return@withContext GithubUpdateCheckResult.Error(
                        "Unable to check for updates (HTTP $code)."
                    )
                }
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val info = parseLatestReleaseJson(
                    jsonText = body,
                    repoSlug = slug,
                    currentVersionName = currentVersionName,
                    currentVersionCode = currentVersionCode
                )
                if (info.isUpdateAvailable) {
                    GithubUpdateCheckResult.UpdateAvailable(info)
                } else {
                    GithubUpdateCheckResult.UpToDate(info)
                }
            } finally {
                conn.disconnect()
            }
        }.getOrElse { err ->
            GithubUpdateCheckResult.Error(
                err.localizedMessage ?: "Failed to check for updates. Check your internet connection."
            )
        }
    }

    /**
     * Downloads the release `.apk` binary into `cacheDir/app_updates/` with streaming progress.
     */
    suspend fun downloadReleaseApk(
        asset: GithubReleaseAssetInfo,
        versionTag: String,
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            // Clean old cached APKs first so cache stays lean
            updatesCacheDir.listFiles()?.forEach { old ->
                runCatching { old.delete() }
            }

            val safeTag = versionTag.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "latest" }
            val partFile = File(updatesCacheDir, "Kagojpotro_update_${safeTag}.apk.part")
            val finalFile = File(updatesCacheDir, "Kagojpotro_update_${safeTag}.apk")
            if (partFile.exists()) partFile.delete()
            if (finalFile.exists()) finalFile.delete()

            var currentUrl = asset.downloadUrl
            var redirects = 0
            var conn: HttpURLConnection? = null

            try {
                while (redirects < 6) {
                    val c = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = CONNECT_TIMEOUT_MS
                        readTimeout = READ_TIMEOUT_MS
                        instanceFollowRedirects = true
                        setRequestProperty("Accept", "application/octet-stream, */*")
                        setRequestProperty("User-Agent", "Kagojpotro-Android-Updater/${BuildConfig.VERSION_NAME}")
                    }
                    val status = c.responseCode
                    if (status in listOf(
                            HttpURLConnection.HTTP_MOVED_PERM,
                            HttpURLConnection.HTTP_MOVED_TEMP,
                            HttpURLConnection.HTTP_SEE_OTHER,
                            307,
                            308
                        )
                    ) {
                        val location = c.getHeaderField("Location")
                        c.disconnect()
                        if (location.isNullOrBlank()) error("Redirect missing Location header")
                        currentUrl = location
                        redirects++
                        continue
                    }
                    conn = c
                    break
                }

                val activeConn = conn ?: error("Too many redirects while downloading APK")
                val code = activeConn.responseCode
                if (code !in 200..299) {
                    error("APK download failed with HTTP $code")
                }

                val headerLen = activeConn.contentLengthLong
                val totalBytes = when {
                    headerLen > 0L -> headerLen
                    asset.sizeBytes > 0L -> asset.sizeBytes
                    else -> -1L
                }

                BufferedInputStream(activeConn.inputStream, 64 * 1024).use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var downloaded = 0L
                        var lastReportedPercent = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val pct = if (totalBytes > 0L) {
                                ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            } else {
                                -1
                            }
                            if (pct != lastReportedPercent) {
                                lastReportedPercent = pct
                                onProgress(pct.coerceAtLeast(0), downloaded, totalBytes.coerceAtLeast(downloaded))
                            }
                        }
                        output.flush()
                    }
                }

                if (!partFile.exists() || partFile.length() < 1024L) {
                    partFile.delete()
                    error("Downloaded APK file is empty or incomplete.")
                }

                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }
                onProgress(100, finalFile.length(), finalFile.length())
                finalFile
            } finally {
                conn?.disconnect()
            }
        }
    }

    /**
     * Returns `true` if this app is allowed to request package installation, or if the OS version
     * does not require runtime unknown-sources toggle (< Android 8.0).
     */
    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Launches the Android system Package Installer for [apkFile].
     * If `canRequestPackageInstalls()` is false on Android 8+, opens the exact system settings
     * screen for "Install unknown apps" for Kagojpotro so the user can toggle it on in 1 tap.
     */
    fun launchApkInstaller(apkFile: File): Boolean {
        if (!apkFile.exists() || apkFile.length() <= 0L) return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                val permIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(permIntent)
                return true
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            true
        }.getOrDefault(false)
    }
}
