package com.example.data

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class PcPingResult(
    val ok: Boolean,
    val userDirectory: String = "",
    val freeSpaceBytes: Long = 0L,
    val totalSpaceBytes: Long = 0L,
    val userUsedBytes: Long = 0L,
    val folderCount: Int = 0,
    val scanCount: Int = 0,
    val errorMessage: String? = null
)

data class PcSyncCycleSummary(
    val success: Boolean,
    val uploadedScans: Int = 0,
    val downloadedScans: Int = 0,
    val totalFolders: Int = 0,
    val totalScans: Int = 0,
    val freeSpaceBytes: Long = 0L,
    val totalSpaceBytes: Long = 0L,
    val userUsedBytes: Long = 0L,
    val message: String,
    val error: String? = null
)

/**
 * Handles all communication with the self-hosted Office PC File Server (`kagojpotro_pc_server.py`):
 * - Multi-user directory isolation (`users/<userDirectory>/Folders/...` e.g. `baba` and `hadi`)
 * - All-Time Background Two-Way Sync of folders, subfolders, custom sort order, and images
 * - On-demand High-Res image restoration when Temporary High-Res Cache was cleared on the phone
 */
class PcSyncManager(
    private val database: KagojpotroDatabase,
    private val originalsDir: File,
    private val editedDir: File,
    private val thumbsDir: File
) {
    private val syncMutex = Mutex()
    private val folderDao = database.folderDao()
    private val scanDao = database.scanDao()

    fun sanitizeUserDirectory(raw: String): String {
        val trimmed = raw.trim().lowercase(Locale.ROOT)
        val beforeAt = trimmed.substringBefore("@")
        val cleaned = beforeAt.replace(Regex("[^a-z0-9_-]"), "_").trim('_')
        return cleaned.ifEmpty { "default" }
    }

    private val resolvedShortUrlCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val shortenerHosts = listOf(
        "tinyurl.com/",
        "da.gd/",
        "clck.ru/",
        "spoo.me/",
        "v.gd/",
        "is.gd/",
        "bit.ly/"
    )

    fun isLanHost(rawHost: String): Boolean {
        val host = rawHost.trim().trim('[', ']').lowercase(Locale.ROOT).trimEnd('.')
        if (host.isEmpty()) return false
        if (host == "localhost" || host == "::1" || host.endsWith(".local")) return true
        // IPv6 Unique Local (fc00::/7 -> fc.. / fd..) or Link-Local (fe80::/10)
        if (host.contains(':') && (host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80:"))) {
            return true
        }
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val a = octets[0]
        val b = octets[1]
        return a == 10 ||
            a == 127 ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254) ||
            (a == 172 && b in 16..31)
    }

    fun isAllowedTransportUrl(urlString: String): Boolean {
        val parsed = runCatching { URL(urlString) }.getOrNull() ?: return false
        val scheme = parsed.protocol.lowercase(Locale.ROOT)
        val host = parsed.host.orEmpty()
        if (host.isBlank()) return false
        return when (scheme) {
            "https" -> true
            "http" -> isLanHost(host)
            else -> false
        }
    }

    private fun readBoundedResponseText(
        stream: java.io.InputStream,
        maxBytes: Int = 10 * 1024 * 1024
    ): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        BufferedInputStream(stream).use { input ->
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                total += read
                if (total > maxBytes) {
                    throw IllegalStateException("Server response exceeds ${maxBytes / (1024 * 1024)} MB safety limit")
                }
                out.write(buf, 0, read)
            }
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    private fun setAccessKeyHeaderIfSafe(conn: HttpURLConnection, urlString: String, accessKey: String) {
        if (accessKey.isBlank()) return
        if (isAllowedTransportUrl(urlString)) {
            conn.setRequestProperty("X-Access-Key", accessKey.trim())
        }
    }

    fun invalidateResolvedUrl(rawUrl: String) {
        val cleaned = rawUrl.trim().replace(Regex("[\\s\\u200B-\\u200D\\uFEFF]+"), "").trimEnd('/')
        if (cleaned.isNotEmpty()) {
            resolvedShortUrlCache.remove(cleaned)
        }
        val normalized = normalizeServerBaseUrl(rawUrl)
        if (normalized.isNotEmpty()) {
            resolvedShortUrlCache.remove(normalized)
        }
    }

    fun normalizeServerBaseUrl(rawUrl: String): String {
        var trimmed = rawUrl.trim()
            .replace(Regex("[\\s\\u200B-\\u200D\\uFEFF]+"), "")
            .trimEnd('/')
        if (trimmed.isEmpty()) return ""
        if (trimmed.contains("://")) {
            if (!trimmed.startsWith("http://", ignoreCase = true) &&
                !trimmed.startsWith("https://", ignoreCase = true)
            ) {
                return ""
            }
            val parsed = runCatching { URL(trimmed) }.getOrNull() ?: return ""
            if (parsed.host.isNullOrBlank()) return ""
            return trimmed
        } else {
            val host = runCatching { URL("http://$trimmed").host }.getOrNull().orEmpty()
            if (host.isBlank()) return ""
            val lowerTrimmed = trimmed.lowercase(Locale.ROOT)
            val forceHttps = shortenerHosts.any { lowerTrimmed.contains(it) } ||
                lowerTrimmed.contains("trycloudflare.com") ||
                !isLanHost(host)
            trimmed = if (forceHttps) "https://$trimmed" else "http://$trimmed"
        }
        return trimmed
    }

    fun resolveServerBaseUrl(rawUrl: String): String {
        val initial = normalizeServerBaseUrl(rawUrl)
        if (initial.isEmpty() || !isAllowedTransportUrl(initial)) return ""
        resolvedShortUrlCache[initial]?.let { cached ->
            if (isAllowedTransportUrl(cached)) return cached
            resolvedShortUrlCache.remove(initial)
        }

        val lower = initial.lowercase(Locale.ROOT)
        val isLikelyShortUrl = shortenerHosts.any { lower.contains("://$it") || lower.contains("://www.$it") }
        if (!isLikelyShortUrl) {
            return initial
        }

        var current = initial
        var unsafeRedirect = false
        for (hop in 0 until 5) {
            val next = runCatching {
                val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Kagojpotro/1.0")
                }
                try {
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val loc = conn.getHeaderField("Location")?.trim()
                        if (!loc.isNullOrBlank()) {
                            URL(URL(current), loc).toString().trimEnd('/')
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
            if (next.isNullOrBlank()) break
            if (!isAllowedTransportUrl(next)) {
                unsafeRedirect = true
                break
            }
            current = next
        }
        if (unsafeRedirect || !isAllowedTransportUrl(current)) {
            resolvedShortUrlCache.remove(initial)
            return ""
        }
        if (current != initial) {
            resolvedShortUrlCache[initial] = current
        }
        return current
    }

    private fun formatHttpErrorMessage(code: Int, rawErrorBody: String?): String {
        val body = rawErrorBody.orEmpty().trim()
        if (code == 401 || code == 403) {
            return "Invalid Server Access Key (HTTP $code)"
        }
        if (code == 502 || code == 503 || code == 504 || code == 530 ||
            body.contains("origin_bad_gateway", ignoreCase = true) ||
            body.contains("trycloudflare.com", ignoreCase = true) ||
            body.contains("Argo Tunnel", ignoreCase = true) ||
            body.contains("cloudflare", ignoreCase = true)
        ) {
            return "PC Server is offline or tunnel URL expired (HTTP $code). Make sure PC Server is running and scan/copy the latest URL."
        }
        val cleanSnippet = runCatching {
            JSONObject(body).optString("error").ifBlank {
                JSONObject(body).optString("title")
            }
        }.getOrNull().orEmpty()
        return if (cleanSnippet.isNotBlank()) {
            "Server returned HTTP $code ($cleanSnippet)"
        } else {
            "Server returned HTTP $code"
        }
    }

    /**
     * Tests connection to the Office PC Server and returns live disk space (e.g. 4 TB free)
     * and user directory stats.
     */
    suspend fun pingServer(config: PcSyncConfig): PcPingResult = withContext(Dispatchers.IO) {
        val normalizedInput = normalizeServerBaseUrl(config.serverUrl)
        if (normalizedInput.isEmpty()) {
            return@withContext PcPingResult(
                ok = false,
                errorMessage = "Please enter a valid PC Server URL"
            )
        }
        if (!isAllowedTransportUrl(normalizedInput)) {
            return@withContext PcPingResult(
                ok = false,
                errorMessage = "HTTPS is required for non-LAN servers. Use https:// for public/tunnel URLs, or local Wi-Fi IP for HTTP."
            )
        }
        val userDir = sanitizeUserDirectory(config.userDirectory)

        var lastResult = PcPingResult(ok = false, errorMessage = "PC Server unreachable")
        for (attempt in 1..2) {
            val baseUrl = resolveServerBaseUrl(config.serverUrl)
            if (baseUrl.isEmpty() || !isAllowedTransportUrl(baseUrl)) {
                return@withContext PcPingResult(
                    ok = false,
                    errorMessage = "Blocked insecure non-LAN HTTP redirect. Use an HTTPS server URL."
                )
            }
            val urlStr = "$baseUrl/api/ping?user=${URLEncoder.encode(userDir, "UTF-8")}"
            var shouldRetry = false

            val attemptResult = runCatching {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 6500
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Kagojpotro/1.0")
                    setAccessKeyHeaderIfSafe(this, urlStr, config.accessKey)
                }
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        if (normalizedInput != baseUrl) {
                            resolvedShortUrlCache.remove(normalizedInput)
                        }
                        if (attempt == 1 && code in listOf(502, 503, 504)) {
                            shouldRetry = true
                        }
                        val errText = runCatching {
                            conn.errorStream?.let { readBoundedResponseText(it, 64 * 1024) }
                        }.getOrNull()
                        return@runCatching PcPingResult(
                            ok = false,
                            errorMessage = formatHttpErrorMessage(code, errText)
                        )
                    }
                    val body = readBoundedResponseText(conn.inputStream, 2 * 1024 * 1024)
                    val json = JSONObject(body)
                    PcPingResult(
                        ok = json.optBoolean("ok", true),
                        userDirectory = json.optString("userDirectory", userDir),
                        freeSpaceBytes = json.optLong("freeSpaceBytes", 0L),
                        totalSpaceBytes = json.optLong("totalSpaceBytes", 0L),
                        userUsedBytes = json.optLong("userUsedBytes", 0L),
                        folderCount = json.optInt("folderCount", 0),
                        scanCount = json.optInt("scanCount", 0)
                    )
                } finally {
                    conn.disconnect()
                }
            }.getOrElse { err ->
                if (normalizedInput != baseUrl) {
                    resolvedShortUrlCache.remove(normalizedInput)
                }
                PcPingResult(
                    ok = false,
                    errorMessage = "PC Server unreachable (${err.localizedMessage ?: "Offline"})"
                )
            }

            if (attemptResult.ok) {
                return@withContext attemptResult
            }
            lastResult = attemptResult
            if (!shouldRetry) break
            kotlinx.coroutines.delay(450L)
        }
        lastResult
    }

    /**
     * Performs a full Two-Way Sync cycle between this phone and the Office PC Server inside
     * the isolated user directory (`users/<userDirectory>/`):
     * 1. Checks server reachability. If Office PC is OFF, keeps changes queued in Pending Sync.
     * 2. Fetches remote manifest for this user (`GET /api/manifest`).
     * 3. Uploads any local scans (`thumb`, `edited`, `original`) that aren't synced yet.
     * 4. Downloads any remote folders & scans from PC that aren't on this phone yet.
     * 5. Pushes the merged manifest (`POST /api/manifest`) so the PC mirrors the exact
     *    `Folders/<FolderName>/<SubFolderName>/<filename>.jpg` hierarchy on its 4 TB drive.
     */
    suspend fun notifyDisconnect(config: PcSyncConfig) = withContext(Dispatchers.IO) {
        if (config.serverUrl.isBlank()) return@withContext
        val baseUrl = resolveServerBaseUrl(config.serverUrl)
        if (baseUrl.isEmpty() || !isAllowedTransportUrl(baseUrl)) return@withContext
        val userDir = sanitizeUserDirectory(config.userDirectory)
        runCatching {
            val urlStr = "$baseUrl/api/disconnect?user=${URLEncoder.encode(userDir, "UTF-8")}"
            if (!isAllowedTransportUrl(urlStr)) return@runCatching
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 2500
                readTimeout = 2500
                setFixedLengthStreamingMode(0)
                setAccessKeyHeaderIfSafe(this, urlStr, config.accessKey)
            }
            try {
                conn.outputStream.close()
                conn.responseCode
            } finally {
                conn.disconnect()
            }
        }
    }

    suspend fun performSyncCycle(
        config: PcSyncConfig,
        deletedFolderIds: Set<String>,
        deletedScanIds: Set<String>,
        trashedFolderIds: Set<String> = emptySet(),
        trashedScanIds: Set<String> = emptySet()
    ): PcSyncCycleSummary = withContext(Dispatchers.IO) {
        if (config.mode != AppStorageSyncMode.PC_SERVER_SYNC) {
            return@withContext PcSyncCycleSummary(
                success = false,
                message = "Phone Only Mode active"
            )
        }
        val baseUrl = resolveServerBaseUrl(config.serverUrl)
        if (baseUrl.isEmpty()) {
            return@withContext PcSyncCycleSummary(
                success = false,
                message = "Enter PC Server URL in Settings",
                error = "Server URL not configured"
            )
        }

        syncMutex.withLock {
            val userDir = sanitizeUserDirectory(config.userDirectory)
            val ping = pingServer(config)
            if (!ping.ok) {
                val localScans = scanDao.getAllScansOnce()
                val pending = localScans.count { it.syncState != "SYNCED" }
                return@withLock PcSyncCycleSummary(
                    success = false,
                    message = if (pending > 0) {
                        "PC Server offline • $pending scan(s) queued for auto-sync"
                    } else {
                        "PC Server offline • All current scans backed up"
                    },
                    error = ping.errorMessage
                )
            }

            runCatching {
                // 1. Fetch remote manifest from Office PC
                val remoteManifest = fetchRemoteManifest(baseUrl, userDir, config.accessKey)
                val remoteDeletedFolderIds = remoteManifest.optJSONArray("deletedFolderIds").toStringSet() + deletedFolderIds
                val remoteDeletedScanIds = remoteManifest.optJSONArray("deletedScanIds").toStringSet() + deletedScanIds

                val remoteFoldersMap = parseFoldersMap(remoteManifest.optJSONArray("folders"))
                val remoteScansMap = parseScansMap(remoteManifest.optJSONArray("scans"))

                // 2. Load local database records
                val localFolders = folderDao.getAllFoldersOnce()
                val localScans = scanDao.getAllScansOnce()

                // Remove any local records that were explicitly deleted on remote (cascade to descendants + scans)
                val childrenByParent = localFolders.groupBy { it.parentId }
                val cascadedDeleteFolderIds = mutableSetOf<String>()
                val deleteQueue = ArrayDeque<String>()
                localFolders.filter { it.id in remoteDeletedFolderIds }.forEach {
                    if (cascadedDeleteFolderIds.add(it.id)) deleteQueue.add(it.id)
                }
                while (deleteQueue.isNotEmpty()) {
                    val curr = deleteQueue.removeFirst()
                    childrenByParent[curr]?.forEach { child ->
                        if (cascadedDeleteFolderIds.add(child.id)) deleteQueue.add(child.id)
                    }
                }

                val effectiveDeletedFolderIds = remoteDeletedFolderIds + cascadedDeleteFolderIds
                val localScansToDelete = localScans.filter {
                    it.id in remoteDeletedScanIds || it.folderId in effectiveDeletedFolderIds
                }
                val effectiveDeletedScanIds = remoteDeletedScanIds + localScansToDelete.map { it.id }

                if (localScansToDelete.isNotEmpty()) {
                    scanDao.deleteScansByIds(localScansToDelete.map { it.id })
                    for (s in localScansToDelete) {
                        runCatching { File(s.originalPath).delete() }
                        runCatching { File(s.editedPath).delete() }
                        runCatching { File(s.thumbnailPath).delete() }
                    }
                }
                if (cascadedDeleteFolderIds.isNotEmpty()) {
                    folderDao.deleteFoldersByIds(cascadedDeleteFolderIds.toList())
                }

                val allTrashedFolderIds = trashedFolderIds + folderDao.getTrashedFoldersOnce().map { it.id }
                val allTrashedScanIds = trashedScanIds + scanDao.getTrashedScansOnce().map { it.id }

                val activeLocalFolders = folderDao.getAllFoldersOnce().associateBy { it.id }
                val activeLocalScans = scanDao.getAllScansOnce().associateBy { it.id }

                // 3. Merge Folders (latest modifiedAt wins) & enforce 1-level subfolder limit
                val rawMergedFolders = mutableMapOf<String, FolderEntity>()
                val dirtyRemoteFolderIds = mutableSetOf<String>()

                for ((id, localFolder) in activeLocalFolders) {
                    if (id in effectiveDeletedFolderIds) continue
                    val remoteFolder = remoteFoldersMap[id]
                    if (remoteFolder == null || localFolder.modifiedAt >= remoteFolder.modifiedAt) {
                        rawMergedFolders[id] = localFolder.copy(syncState = "SYNCED")
                    } else {
                        rawMergedFolders[id] = remoteFolder.copy(syncState = "SYNCED")
                        dirtyRemoteFolderIds.add(id)
                    }
                }
                val preservedTrashedFolders = mutableListOf<FolderEntity>()
                for ((id, remoteFolder) in remoteFoldersMap) {
                    if (id in effectiveDeletedFolderIds) continue
                    if (id in allTrashedFolderIds) {
                        // Keep remote backup metadata intact while in local 7-day Trash without re-downloading to active library
                        preservedTrashedFolders.add(remoteFolder)
                        continue
                    }
                    if (id !in rawMergedFolders) {
                        rawMergedFolders[id] = remoteFolder.copy(syncState = "SYNCED")
                        dirtyRemoteFolderIds.add(id)
                    }
                }

                val mergedFolders = LinkedHashMap<String, FolderEntity>()
                val foldersToInsertLocally = mutableListOf<FolderEntity>()
                for ((id, folder) in rawMergedFolders) {
                    val pId = folder.parentId
                    val validParentId = if (
                        pId != null &&
                        pId != id &&
                        rawMergedFolders[pId] != null &&
                        rawMergedFolders[pId]?.parentId == null
                    ) {
                        pId
                    } else {
                        null
                    }
                    val normalized = if (validParentId == folder.parentId) folder else folder.copy(parentId = validParentId)
                    mergedFolders[id] = normalized
                    if (id in dirtyRemoteFolderIds || validParentId != folder.parentId) {
                        foldersToInsertLocally.add(normalized)
                    }
                }

                if (foldersToInsertLocally.isNotEmpty()) {
                    folderDao.upsertFolders(foldersToInsertLocally)
                }
                if (activeLocalFolders.isNotEmpty()) {
                    folderDao.markFoldersSynced(activeLocalFolders.keys.toList())
                }

                // 4. Upload dirty/new local scans & Download remote scans missing on phone
                val mergedScans = mutableMapOf<String, ScanEntity>()
                var uploadedCount = 0
                var downloadedCount = 0
                val newlySyncedLocalScanIds = mutableListOf<String>()
                val remoteScansToInsertLocally = mutableListOf<ScanEntity>()

                for ((id, localScan) in activeLocalScans) {
                    if (id in effectiveDeletedScanIds) continue
                    val remoteScan = remoteScansMap[id]
                    val localNeedsUpload = localScan.syncState != "SYNCED" ||
                        remoteScan == null ||
                        localScan.modifiedAt > remoteScan.modifiedAt

                    if (localNeedsUpload) {
                        val uploadedOk = uploadScanFilesToPc(
                            baseUrl = baseUrl,
                            userDir = userDir,
                            accessKey = config.accessKey,
                            scan = localScan
                        )
                        val resultingState = if (uploadedOk) "SYNCED" else localScan.syncState
                        if (uploadedOk) {
                            uploadedCount++
                            newlySyncedLocalScanIds.add(id)
                        }
                        mergedScans[id] = localScan.copy(syncState = resultingState)
                    } else if (remoteScan.modifiedAt > localScan.modifiedAt) {
                        // Remote scan was updated more recently
                        val thumbFile = File(thumbsDir, "thumb_$id.jpg")
                        val thumbOk = downloadScanFileFromPc(baseUrl, userDir, config.accessKey, id, "thumb", thumbFile)
                        val editedFile = File(editedDir, "edit_$id.jpg")
                        val editedOk = downloadScanFileFromPc(baseUrl, userDir, config.accessKey, id, "edited", editedFile)
                        if (!thumbOk && editedOk && editedFile.exists()) {
                            runCatching { editedFile.copyTo(thumbFile, overwrite = true) }
                        }
                        if (editedOk || thumbOk) {
                            val updatedLocal = remoteScan.copy(
                                originalPath = File(originalsDir, "orig_$id.jpg").absolutePath,
                                editedPath = editedFile.absolutePath,
                                thumbnailPath = thumbFile.absolutePath,
                                syncState = if (editedOk) "SYNCED" else localScan.syncState
                            )
                            mergedScans[id] = updatedLocal
                            remoteScansToInsertLocally.add(updatedLocal)
                            downloadedCount++
                        } else {
                            mergedScans[id] = localScan
                        }
                    } else {
                        mergedScans[id] = localScan.copy(syncState = "SYNCED")
                        if (localScan.syncState != "SYNCED") {
                            newlySyncedLocalScanIds.add(id)
                        }
                    }
                }

                // Download scans that exist on PC but not yet on this phone
                val preservedTrashedScans = mutableListOf<ScanEntity>()
                for ((id, remoteScan) in remoteScansMap) {
                    if (id in effectiveDeletedScanIds) continue
                    if (id in allTrashedScanIds || remoteScan.folderId in allTrashedFolderIds) {
                        preservedTrashedScans.add(remoteScan)
                        continue
                    }
                    if (remoteScan.folderId !in mergedFolders) continue
                    if (id !in mergedScans) {
                        val thumbFile = File(thumbsDir, "thumb_$id.jpg")
                        val editedFile = File(editedDir, "edit_$id.jpg")
                        val origFile = File(originalsDir, "orig_$id.jpg")

                        val thumbOk = downloadScanFileFromPc(
                            baseUrl = baseUrl,
                            userDir = userDir,
                            accessKey = config.accessKey,
                            scanId = id,
                            kind = "thumb",
                            destFile = thumbFile
                        )
                        // Also download High-Res edited copy into Temporary High-Res storage so Baba can view offline
                        val editedOk = downloadScanFileFromPc(
                            baseUrl = baseUrl,
                            userDir = userDir,
                            accessKey = config.accessKey,
                            scanId = id,
                            kind = "edited",
                            destFile = editedFile
                        )
                        if (!thumbOk && editedOk) {
                            runCatching { editedFile.copyTo(thumbFile, overwrite = true) }
                        }

                        if (thumbFile.exists() || editedFile.exists()) {
                            val restoredEntity = remoteScan.copy(
                                originalPath = origFile.absolutePath,
                                editedPath = editedFile.absolutePath,
                                thumbnailPath = thumbFile.absolutePath,
                                syncState = if (editedOk || thumbOk) "SYNCED" else "LOCAL_DIRTY"
                            )
                            mergedScans[id] = restoredEntity
                            remoteScansToInsertLocally.add(restoredEntity)
                            downloadedCount++
                        }
                    }
                }

                if (newlySyncedLocalScanIds.isNotEmpty()) {
                    scanDao.markScansSynced(newlySyncedLocalScanIds)
                }
                if (remoteScansToInsertLocally.isNotEmpty()) {
                    scanDao.insertScans(remoteScansToInsertLocally)
                }

                // 5. Push merged manifest to PC so PC mirrors real Windows folders & files
                val manifestFolders = mergedFolders.values.toList() + preservedTrashedFolders
                val manifestScans = mergedScans.values.toList() + preservedTrashedScans
                val manifestPayload = buildManifestJson(
                    userDir = userDir,
                    folders = manifestFolders,
                    scans = manifestScans,
                    deletedFolderIds = effectiveDeletedFolderIds,
                    deletedScanIds = effectiveDeletedScanIds
                )
                val postResp = postRemoteManifest(baseUrl, userDir, config.accessKey, manifestPayload)
                val freeBytes = postResp.optLong("freeSpaceBytes", ping.freeSpaceBytes)
                val totalBytes = postResp.optLong("totalSpaceBytes", ping.totalSpaceBytes)
                val usedBytes = postResp.optLong("userUsedBytes", ping.userUsedBytes)

                PcSyncCycleSummary(
                    success = true,
                    uploadedScans = uploadedCount,
                    downloadedScans = downloadedCount,
                    totalFolders = mergedFolders.size,
                    totalScans = mergedScans.size,
                    freeSpaceBytes = freeBytes,
                    totalSpaceBytes = totalBytes,
                    userUsedBytes = usedBytes,
                    message = "Synced with PC ($userDir) • ${mergedScans.size} scans"
                )
            }.getOrElse { err ->
                PcSyncCycleSummary(
                    success = false,
                    message = "Sync paused: ${err.localizedMessage ?: "Connection interrupted"}",
                    error = err.localizedMessage
                )
            }
        }
    }

    /**
     * Ensures the full 4096px High-Res `editedPath` (and optionally `originalPath`) is present on
     * the phone. If the user previously tapped "Clear Temporary High-Res Files" in Settings to save
     * phone space, and the Office PC is currently reachable, this transparently downloads the High-Res
     * file back into Temporary High-Res storage on the phone.
     */
    suspend fun ensureHighResAvailableOnPhone(
        scan: ScanEntity,
        config: PcSyncConfig,
        needOriginalToo: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val editedFile = File(scan.editedPath)
        val origFile = File(scan.originalPath)
        val hasEdited = editedFile.exists() && editedFile.length() > 0L
        val hasOrig = !needOriginalToo || (origFile.exists() && origFile.length() > 0L)
        if (hasEdited && hasOrig) return@withContext true

        if (config.mode != AppStorageSyncMode.PC_SERVER_SYNC) return@withContext hasEdited
        val baseUrl = resolveServerBaseUrl(config.serverUrl)
        if (baseUrl.isEmpty()) return@withContext hasEdited
        val userDir = sanitizeUserDirectory(config.userDirectory)

        var okEdited = hasEdited
        if (!okEdited) {
            okEdited = downloadScanFileFromPc(
                baseUrl = baseUrl,
                userDir = userDir,
                accessKey = config.accessKey,
                scanId = scan.id,
                kind = "edited",
                destFile = editedFile
            )
        }
        if (needOriginalToo && (!origFile.exists() || origFile.length() == 0L)) {
            downloadScanFileFromPc(
                baseUrl = baseUrl,
                userDir = userDir,
                accessKey = config.accessKey,
                scanId = scan.id,
                kind = "original",
                destFile = origFile
            )
        }
        okEdited
    }

    private fun uploadScanFilesToPc(
        baseUrl: String,
        userDir: String,
        accessKey: String,
        scan: ScanEntity
    ): Boolean {
        val thumbFile = File(scan.thumbnailPath)
        val editedFile = File(scan.editedPath)
        val origFile = File(scan.originalPath)

        var anyUploaded = false
        if (thumbFile.exists() && thumbFile.length() > 0L) {
            val ok = putFileToPc(baseUrl, userDir, accessKey, scan.id, "thumb", scan.filename, thumbFile)
            if (!ok) return false
            anyUploaded = true
        }
        if (editedFile.exists() && editedFile.length() > 0L) {
            val ok = putFileToPc(baseUrl, userDir, accessKey, scan.id, "edited", scan.filename, editedFile)
            if (!ok) return false
            anyUploaded = true
        }
        if (origFile.exists() && origFile.length() > 0L) {
            val ok = putFileToPc(baseUrl, userDir, accessKey, scan.id, "original", scan.filename, origFile)
            if (!ok) return false
            anyUploaded = true
        }
        return anyUploaded
    }

    private fun putFileToPc(
        baseUrl: String,
        userDir: String,
        accessKey: String,
        scanId: String,
        kind: String,
        filename: String,
        sourceFile: File
    ): Boolean {
        return runCatching {
            val query = "user=${URLEncoder.encode(userDir, "UTF-8")}" +
                "&scanId=${URLEncoder.encode(scanId, "UTF-8")}" +
                "&kind=${URLEncoder.encode(kind, "UTF-8")}" +
                "&filename=${URLEncoder.encode(filename, "UTF-8")}"
            val fullUrl = "$baseUrl/api/file?$query"
            if (!isAllowedTransportUrl(fullUrl)) return@runCatching false
            val conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "PUT"
                doOutput = true
                connectTimeout = 6000
                readTimeout = 30000
                setFixedLengthStreamingMode(sourceFile.length())
                setRequestProperty("Content-Type", "image/jpeg")
                setAccessKeyHeaderIfSafe(this, fullUrl, accessKey)
            }
            try {
                BufferedOutputStream(conn.outputStream).use { out ->
                    BufferedInputStream(FileInputStream(sourceFile)).use { input ->
                        input.copyTo(out, bufferSize = 32 * 1024)
                    }
                }
                conn.responseCode in 200..299
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }

    private fun downloadScanFileFromPc(
        baseUrl: String,
        userDir: String,
        accessKey: String,
        scanId: String,
        kind: String,
        destFile: File
    ): Boolean {
        return runCatching {
            val query = "user=${URLEncoder.encode(userDir, "UTF-8")}" +
                "&scanId=${URLEncoder.encode(scanId, "UTF-8")}" +
                "&kind=${URLEncoder.encode(kind, "UTF-8")}"
            val fullUrl = "$baseUrl/api/file?$query"
            if (!isAllowedTransportUrl(fullUrl)) return@runCatching false
            val conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 25000
                setAccessKeyHeaderIfSafe(this, fullUrl, accessKey)
            }
            try {
                if (conn.responseCode !in 200..299) return@runCatching false
                destFile.parentFile?.mkdirs()
                val tempFile = File(destFile.parentFile, "${destFile.name}.part")
                BufferedInputStream(conn.inputStream).use { input ->
                    BufferedOutputStream(FileOutputStream(tempFile)).use { out ->
                        input.copyTo(out, bufferSize = 32 * 1024)
                    }
                }
                if (tempFile.exists() && tempFile.length() > 0L) {
                    tempFile.copyTo(destFile, overwrite = true)
                    tempFile.delete()
                    true
                } else {
                    tempFile.delete()
                    false
                }
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }

    private fun fetchRemoteManifest(baseUrl: String, userDir: String, accessKey: String): JSONObject {
        val urlStr = "$baseUrl/api/manifest?user=${URLEncoder.encode(userDir, "UTF-8")}"
        if (!isAllowedTransportUrl(urlStr)) return JSONObject()
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 12000
            setRequestProperty("Accept", "application/json")
            setAccessKeyHeaderIfSafe(this, urlStr, accessKey)
        }
        try {
            if (conn.responseCode !in 200..299) return JSONObject()
            val text = readBoundedResponseText(conn.inputStream, 10 * 1024 * 1024)
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun postRemoteManifest(
        baseUrl: String,
        userDir: String,
        accessKey: String,
        manifest: JSONObject
    ): JSONObject {
        val urlStr = "$baseUrl/api/manifest?user=${URLEncoder.encode(userDir, "UTF-8")}"
        if (!isAllowedTransportUrl(urlStr)) return JSONObject()
        val bytes = manifest.toString().toByteArray(Charsets.UTF_8)
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 6000
            readTimeout = 20000
            setFixedLengthStreamingMode(bytes.size)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setAccessKeyHeaderIfSafe(this, urlStr, accessKey)
        }
        try {
            conn.outputStream.use { it.write(bytes) }
            if (conn.responseCode !in 200..299) return JSONObject()
            val resp = readBoundedResponseText(conn.inputStream, 4 * 1024 * 1024)
            return runCatching { JSONObject(resp) }.getOrDefault(JSONObject())
        } finally {
            conn.disconnect()
        }
    }

    private fun buildManifestJson(
        userDir: String,
        folders: List<FolderEntity>,
        scans: List<ScanEntity>,
        deletedFolderIds: Set<String>,
        deletedScanIds: Set<String>
    ): JSONObject {
        val folderMap = folders.associateBy { it.id }

        fun resolveRelativeFolderPath(folderId: String?): String {
            if (folderId == null) return ""
            val parts = mutableListOf<String>()
            val visited = mutableSetOf<String>()
            var cur: String? = folderId
            while (cur != null && visited.add(cur)) {
                val f = folderMap[cur] ?: break
                parts.add(f.name)
                cur = f.parentId
            }
            parts.reverse()
            return parts.joinToString("/")
        }

        val foldersArray = JSONArray()
        for (f in folders) {
            foldersArray.put(
                JSONObject().apply {
                    put("id", f.id)
                    put("name", f.name)
                    put("parentId", f.parentId ?: JSONObject.NULL)
                    put("relativePath", resolveRelativeFolderPath(f.id))
                    put("createdAt", f.createdAt)
                    put("modifiedAt", f.modifiedAt)
                    put("customOrder", f.customOrder)
                }
            )
        }

        val scansArray = JSONArray()
        for (s in scans) {
            scansArray.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("folderId", s.folderId)
                    put("folderRelativePath", resolveRelativeFolderPath(s.folderId))
                    put("filename", s.filename)
                    put("capturedAt", s.capturedAt)
                    put("modifiedAt", s.modifiedAt)
                    put("rotationDegrees", s.rotationDegrees)
                    put("cropTlX", s.cropTlX.toDouble())
                    put("cropTlY", s.cropTlY.toDouble())
                    put("cropTrX", s.cropTrX.toDouble())
                    put("cropTrY", s.cropTrY.toDouble())
                    put("cropBrX", s.cropBrX.toDouble())
                    put("cropBrY", s.cropBrY.toDouble())
                    put("cropBlX", s.cropBlX.toDouble())
                    put("cropBlY", s.cropBlY.toDouble())
                    put("filterMode", s.filterMode)
                    put("width", s.width)
                    put("height", s.height)
                    put("eraserStrokesJson", s.eraserStrokesJson)
                }
            )
        }

        val delFoldersArr = JSONArray().apply { deletedFolderIds.forEach { put(it) } }
        val delScansArr = JSONArray().apply { deletedScanIds.forEach { put(it) } }

        return JSONObject().apply {
            put("userDirectory", userDir)
            put("updatedAt", System.currentTimeMillis())
            put("folders", foldersArray)
            put("scans", scansArray)
            put("deletedFolderIds", delFoldersArr)
            put("deletedScanIds", delScansArr)
        }
    }

    private fun parseFoldersMap(arr: JSONArray?): Map<String, FolderEntity> {
        if (arr == null) return emptyMap()
        val result = mutableMapOf<String, FolderEntity>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id", "")
            val name = obj.optString("name", "")
            if (id.isBlank() || name.isBlank()) continue
            val parentId = if (obj.isNull("parentId")) null else obj.optString("parentId", "").ifBlank { null }
            result[id] = FolderEntity(
                id = id,
                name = name,
                parentId = parentId,
                createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                modifiedAt = obj.optLong("modifiedAt", System.currentTimeMillis()),
                customOrder = obj.optInt("customOrder", i),
                syncState = "SYNCED"
            )
        }
        return result
    }

    private fun parseScansMap(arr: JSONArray?): Map<String, ScanEntity> {
        if (arr == null) return emptyMap()
        val result = mutableMapOf<String, ScanEntity>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id", "")
            val folderId = obj.optString("folderId", "")
            if (id.isBlank() || folderId.isBlank()) continue
            val filename = obj.optString("filename", "Kagojpotro_$id.jpg")
            result[id] = ScanEntity(
                id = id,
                folderId = folderId,
                filename = filename,
                originalPath = File(originalsDir, "orig_$id.jpg").absolutePath,
                editedPath = File(editedDir, "edit_$id.jpg").absolutePath,
                thumbnailPath = File(thumbsDir, "thumb_$id.jpg").absolutePath,
                capturedAt = obj.optLong("capturedAt", System.currentTimeMillis()),
                modifiedAt = obj.optLong("modifiedAt", System.currentTimeMillis()),
                rotationDegrees = obj.optInt("rotationDegrees", 0),
                cropTlX = obj.optDouble("cropTlX", 0.0).toFloat(),
                cropTlY = obj.optDouble("cropTlY", 0.0).toFloat(),
                cropTrX = obj.optDouble("cropTrX", 1.0).toFloat(),
                cropTrY = obj.optDouble("cropTrY", 0.0).toFloat(),
                cropBrX = obj.optDouble("cropBrX", 1.0).toFloat(),
                cropBrY = obj.optDouble("cropBrY", 1.0).toFloat(),
                cropBlX = obj.optDouble("cropBlX", 0.0).toFloat(),
                cropBlY = obj.optDouble("cropBlY", 1.0).toFloat(),
                filterMode = obj.optString("filterMode", ScanFilter.MAGIC_COLOR.name),
                width = obj.optInt("width", 0),
                height = obj.optInt("height", 0),
                syncState = "SYNCED",
                eraserStrokesJson = obj.optString("eraserStrokesJson", "[]")
            )
        }
        return result
    }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        val set = mutableSetOf<String>()
        for (i in 0 until length()) {
            val v = optString(i, "")
            if (v.isNotBlank()) set.add(v)
        }
        return set
    }

    companion object {
        /**
         * Complete, zero-dependency Python 3 Office PC Server script (`kagojpotro_pc_server.py`).
         * Uses only Python's standard library (`http.server`, `json`, `shutil`, `pathlib`) so no
         * `pip install` or Docker is required on the Office PC.
         */
        val PC_SERVER_PYTHON_SCRIPT = """
#!/usr/bin/env python3
\"\"\"
================================================================================
  KAGOJPOTRO (কাগজপত্র) — SELF-HOSTED PC FILE SERVER
================================================================================
- Zero external libraries required (runs on standard Python 3!).
- Creates isolated directories per user (e.g. `users/<username>/Folders/...`).
- Mirrors every folder & subfolder created in the Android app as real Windows/Linux
  folders containing the high-resolution `.jpg` images.
- Supports Local Wi-Fi, Tailscale, or Cloudflare Tunnel (`cloudflared tunnel --url http://localhost:8085`).

HOW TO RUN ON YOUR PC:
  python kagojpotro_pc_server.py --storage "D:/Kagojpotro_Storage" --port 8085
================================================================================
\"\"\"

import argparse
import json
import os
import re
import shutil
import socket
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

STORAGE_ROOT = Path("./Kagojpotro_Storage").resolve()
ACCESS_KEY = ""


def sanitize_user(raw: str) -> str:
    cleaned = re.sub(r"[^a-z0-9_-]", "_", (raw or "default").strip().lower().split("@")[0]).strip("_")
    return cleaned or "default"


def sanitize_folder_segment(name: str) -> str:
    cleaned = re.sub(r'[\\/:*?"<>|\x00-\x1f]', "_", (name or "").strip()).strip(". ")
    if not cleaned or cleaned in (".", ".."):
        return "Untitled_Folder"
    stem = cleaned.split(".")[0].upper()
    if stem in {"CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"}:
        cleaned = f"_{cleaned}"
    return cleaned or "Untitled_Folder"


def user_root(user: str) -> Path:
    p = STORAGE_ROOT / "users" / sanitize_user(user)
    (p / "Folders").mkdir(parents=True, exist_ok=True)
    (p / ".system" / "edited").mkdir(parents=True, exist_ok=True)
    (p / ".system" / "original").mkdir(parents=True, exist_ok=True)
    (p / ".system" / "thumb").mkdir(parents=True, exist_ok=True)
    return p


def load_manifest(user: str) -> dict:
    mfile = user_root(user) / ".system" / "manifest.json"
    if mfile.exists():
        try:
            return json.loads(mfile.read_text(encoding="utf-8"))
        except Exception:
            pass
    return {
        "userDirectory": sanitize_user(user),
        "updatedAt": 0,
        "folders": [],
        "scans": [],
        "deletedFolderIds": [],
        "deletedScanIds": [],
    }


def rebuild_human_readable_folders(user: str, manifest: dict):
    \"\"\"
    Mirrors the exact app folder & subfolder hierarchy inside:
      <STORAGE_ROOT>/users/<user>/Folders/<Folder>/<Subfolder>/<filename>.jpg
    so you can browse all folders and JPGs directly on your PC in File Explorer!
    \"\"\"
    u_root = user_root(user)
    folders_dir = u_root / "Folders"
    edited_store = u_root / ".system" / "edited"

    folders_by_id = {f["id"]: f for f in manifest.get("folders", []) if "id" in f}

    def resolve_rel_path(folder_id: str) -> Path:
        parts = []
        visited = set()
        curr = folder_id
        while curr and curr not in visited:
            visited.add(curr)
            f = folders_by_id.get(curr)
            if not f:
                break
            parts.append(sanitize_folder_segment(f.get("name", "Folder")))
            curr = f.get("parentId")
        parts.reverse()
        rel = Path(*parts) if parts else Path("Uncategorized")
        return folders_dir / rel

    # Ensure all folders & subfolders exist on disk
    expected_dirs = set()
    for fid in folders_by_id:
        dpath = resolve_rel_path(fid)
        dpath.mkdir(parents=True, exist_ok=True)
        expected_dirs.add(dpath.resolve())

    expected_files = set()
    for scan in manifest.get("scans", []):
        sid = scan.get("id", "")
        fid = scan.get("folderId", "")
        fname = sanitize_folder_segment(scan.get("filename", f"Scan_{sid}.jpg"))
        if not fname.lower().endswith(".jpg"):
            fname += ".jpg"
        src_edited = edited_store / f"{sid}.jpg"
        if src_edited.exists():
            target_dir = resolve_rel_path(fid)
            target_dir.mkdir(parents=True, exist_ok=True)
            dest_file = target_dir / fname
            if dest_file.resolve() in expected_files:
                stem = dest_file.stem
                dest_file = target_dir / f"{stem}_{sid[:4]}.jpg"
            src_stat = src_edited.stat()
            needs_copy = (
                not dest_file.exists()
                or dest_file.stat().st_size != src_stat.st_size
                or int(dest_file.stat().st_mtime) != int(src_stat.st_mtime)
            )
            if needs_copy:
                shutil.copy2(src_edited, dest_file)
            expected_files.add(dest_file.resolve())

    # Clean up moved or deleted files from the human-readable Folders tree
    for root, dirs, files in os.walk(folders_dir, topdown=False):
        root_path = Path(root)
        for f in files:
            fpath = (root_path / f).resolve()
            if fpath not in expected_files:
                try:
                    fpath.unlink()
                except Exception:
                    pass
        for d in dirs:
            dpath = (root_path / d).resolve()
            if dpath not in expected_dirs and not any(dpath.iterdir()):
                try:
                    dpath.rmdir()
                except Exception:
                    pass


def compute_user_bytes(user: str) -> int:
    u_root = user_root(user) / ".system"
    total = 0
    for r, _, files in os.walk(u_root):
        for f in files:
            try:
                total += (Path(r) / f).stat().st_size
            except Exception:
                pass
    return total


class KagojpotroHandler(BaseHTTPRequestHandler):
    def _check_auth(self) -> bool:
        if not ACCESS_KEY:
            return True
        client_key = self.headers.get("X-Access-Key", "").strip()
        if client_key != ACCESS_KEY:
            self._send_json(401, {"ok": False, "error": "Invalid Access Key"})
            return False
        return True

    def _send_json(self, status: int, data: dict):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if not self._check_auth():
            return
        parsed = urlparse(self.path)
        qs = parse_qs(parsed.query)
        user = sanitize_user(qs.get("user", ["default"])[0])

        if parsed.path == "/api/ping":
            usage = shutil.disk_usage(STORAGE_ROOT)
            manifest = load_manifest(user)
            self._send_json(
                200,
                {
                    "ok": True,
                    "serverName": "Kagojpotro PC Server",
                    "userDirectory": user,
                    "freeSpaceBytes": usage.free,
                    "totalSpaceBytes": usage.total,
                    "userUsedBytes": compute_user_bytes(user),
                    "folderCount": len(manifest.get("folders", [])),
                    "scanCount": len(manifest.get("scans", [])),
                },
            )
            return

        if parsed.path == "/api/manifest":
            manifest = load_manifest(user)
            self._send_json(200, manifest)
            return

        if parsed.path == "/api/file":
            scan_id = re.sub(r"[^a-zA-Z0-9_-]", "", qs.get("scanId", [""])[0])
            kind = qs.get("kind", ["edited"])[0]
            if kind not in ("edited", "original", "thumb"):
                kind = "edited"
            f_path = user_root(user) / ".system" / kind / f"{scan_id}.jpg"
            if not f_path.exists():
                self._send_json(404, {"ok": False, "error": "File not found"})
                return
            size = f_path.stat().st_size
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(size))
            self.end_headers()
            with open(f_path, "rb") as fp:
                shutil.copyfileobj(fp, self.wfile)
            return

        self._send_json(200, {"ok": True, "message": "Kagojpotro PC Server is running"})

    def do_PUT(self):
        if not self._check_auth():
            return
        parsed = urlparse(self.path)
        qs = parse_qs(parsed.query)
        user = sanitize_user(qs.get("user", ["default"])[0])

        if parsed.path == "/api/file":
            scan_id = re.sub(r"[^a-zA-Z0-9_-]", "", qs.get("scanId", [""])[0])
            kind = qs.get("kind", ["edited"])[0]
            if kind not in ("edited", "original", "thumb"):
                kind = "edited"
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except (TypeError, ValueError):
                length = 0
            if not scan_id or length <= 0:
                self._send_json(400, {"ok": False, "error": "Missing scanId or Content-Length"})
                return

            dest = user_root(user) / ".system" / kind / f"{scan_id}.jpg"
            tmp = dest.with_suffix(".part")
            remaining = length
            with open(tmp, "wb") as out:
                while remaining > 0:
                    chunk = self.rfile.read(min(65536, remaining))
                    if not chunk:
                        break
                    out.write(chunk)
                    remaining -= len(chunk)
            if remaining > 0:
                try:
                    tmp.unlink()
                except Exception:
                    pass
                self._send_json(400, {"ok": False, "error": "Incomplete upload"})
                return
            tmp.replace(dest)
            self._send_json(200, {"ok": True, "scanId": scan_id, "kind": kind})
            return

        self._send_json(404, {"ok": False})

    def do_POST(self):
        if not self._check_auth():
            return
        parsed = urlparse(self.path)
        qs = parse_qs(parsed.query)
        user = sanitize_user(qs.get("user", ["default"])[0])

        if parsed.path == "/api/manifest":
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except (TypeError, ValueError):
                length = 0
            raw = self.rfile.read(length).decode("utf-8", errors="replace") if length > 0 else "{}"
            try:
                manifest = json.loads(raw)
            except Exception:
                self._send_json(400, {"ok": False, "error": "Invalid JSON manifest"})
                return
            manifest["userDirectory"] = user
            mfile = user_root(user) / ".system" / "manifest.json"
            mfile.write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8")

            # Clean deleted scan files from .system
            u_root = user_root(user)
            for del_id in manifest.get("deletedScanIds", []):
                safe_id = re.sub(r"[^a-zA-Z0-9_-]", "", str(del_id))
                for k in ("edited", "original", "thumb"):
                    p = u_root / ".system" / k / f"{safe_id}.jpg"
                    if p.exists():
                        try:
                            p.unlink()
                        except Exception:
                            pass

            rebuild_human_readable_folders(user, manifest)
            usage = shutil.disk_usage(STORAGE_ROOT)
            self._send_json(
                200,
                {
                    "ok": True,
                    "userDirectory": user,
                    "freeSpaceBytes": usage.free,
                    "totalSpaceBytes": usage.total,
                    "userUsedBytes": compute_user_bytes(user),
                },
            )
            return

        self._send_json(404, {"ok": False})


def get_local_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Kagojpotro PC File Server")
    parser.add_argument("--storage", default="./Kagojpotro_Storage", help="Folder path on your PC drive (e.g. D:/Kagojpotro_Storage)")
    parser.add_argument("--port", type=int, default=8085, help="Port number (default: 8085)")
    parser.add_argument("--key", default="", help="Optional secret access key")
    args = parser.parse_args()

    STORAGE_ROOT = Path(args.storage).resolve()
    STORAGE_ROOT.mkdir(parents=True, exist_ok=True)
    ACCESS_KEY = args.key.strip()

    local_ip = get_local_ip()
    print("=" * 72)
    print("  KAGOJPOTRO (কাগজপত্র) PC SERVER IS RUNNING!")
    print("=" * 72)
    print(f"  Storage Location : {STORAGE_ROOT}")
    print(f"  User Directories : {STORAGE_ROOT / 'users' / '<username>' / 'Folders'}")
    print(f"  Local Wi-Fi URL  : http://{local_ip}:{args.port}")
    print("  Internet Sync    : Run `cloudflared tunnel --url http://localhost:" + str(args.port) + "`")
    print("=" * 72)

    server = ThreadingHTTPServer(("0.0.0.0", args.port), KagojpotroHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down server...")
        server.server_close()
""".trimIndent()
    }
}
