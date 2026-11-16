package com.example.data

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class GoogleDriveSyncStatus(
    val connectedEmail: String = "",
    val autoSyncEnabled: Boolean = true,
    val isSyncing: Boolean = false,
    val lastSyncedAt: Long = 0L,
    val statusMessage: String = "Not connected",
    val lastError: String? = null
)

sealed class DriveTokenResult {
    data class Success(val accessToken: String) : DriveTokenResult()
    data class ConsentRequired(val consentIntent: Intent) : DriveTokenResult()
    data class Error(val message: String) : DriveTokenResult()
}

data class DriveSyncCycleResult(
    val success: Boolean,
    val uploadedCount: Int = 0,
    val downloadedCount: Int = 0,
    val totalFolders: Int = 0,
    val totalScans: Int = 0,
    val message: String,
    val consentIntent: Intent? = null,
    val usedFullDriveBackup: Boolean = false
)

/**
 * Hybrid Google Sync Manager:
 * 1. Tries syncing folders and scan images directly inside a visible `Kagojpotro` folder
 *    in the user's own Google Drive (`drive.file` scope).
 * 2. If Google Drive API is not enabled on the Google Cloud project or OAuth Drive token
 *    is unavailable, automatically falls back to Firebase Cloud Sync (Firestore) using the
 *    authenticated Google Sign-In session (`Firebase.auth.currentUser`).
 */
class GoogleDriveSyncManager(
    private val context: Context,
    private val database: KagojpotroDatabase,
    private val originalsDir: File,
    private val editedDir: File,
    private val thumbsDir: File,
    private val firebaseCloudRepo: FirebaseCloudSyncRepository = FirebaseCloudSyncRepository(context)
) {
    private val mutex = Mutex()
    private val folderDao = database.folderDao()
    private val scanDao = database.scanDao()

    companion object {
        private const val DRIVE_SCOPE = "oauth2:https://www.googleapis.com/auth/drive.file"
        private const val ROOT_FOLDER_NAME = "Kagojpotro"
        private const val MANIFEST_FILENAME = "kagojpotro_manifest.json"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    }

    @Suppress("DEPRECATION")
    suspend fun acquireAccessToken(email: String): DriveTokenResult = withContext(Dispatchers.IO) {
        if (email.isBlank()) {
            return@withContext DriveTokenResult.Error("Please sign in with Google first.")
        }
        runCatching {
            val am = AccountManager.get(context)
            val account = Account(email.trim(), "com.google")
            val future = am.getAuthToken(account, DRIVE_SCOPE, null, false, null, null)
            val bundle: Bundle = future.result
            val consentIntent = bundle.getParcelable<Intent>(AccountManager.KEY_INTENT)
            if (consentIntent != null) {
                return@withContext DriveTokenResult.ConsentRequired(consentIntent)
            }
            val token = bundle.getString(AccountManager.KEY_AUTHTOKEN)
            if (!token.isNullOrBlank()) {
                DriveTokenResult.Success(token)
            } else {
                DriveTokenResult.Error("Google Drive token unavailable")
            }
        }.getOrElse { err ->
            DriveTokenResult.Error(err.localizedMessage ?: "Google Drive token unavailable")
        }
    }

    /**
     * Performs a Two-Way Sync or Full Restore:
     * - Attempts Google Drive (`Kagojpotro` folder) first.
     * - Automatically falls back to Firebase Cloud Sync (`FirebaseCloudSyncRepository`)
     *   whenever Google Drive API is not enabled or unreachable.
     */
    suspend fun syncWithGoogleDrive(
        email: String,
        forceRestoreAll: Boolean = false,
        deletedFolderIds: Set<String> = emptySet(),
        deletedScanIds: Set<String> = emptySet(),
        trashedFolderIds: Set<String> = emptySet(),
        trashedScanIds: Set<String> = emptySet()
    ): DriveSyncCycleResult = withContext(Dispatchers.IO) {
        val effectiveEmail = email.ifBlank { Firebase.auth.currentUser?.email.orEmpty() }
        if (effectiveEmail.isBlank() && Firebase.auth.currentUser == null) {
            return@withContext DriveSyncCycleResult(
                success = false,
                message = "Please sign in with Google first."
            )
        }

        // Step 1: Try Google Drive first if an OS account token is available
        val tokenRes = acquireAccessToken(effectiveEmail)
        if (tokenRes is DriveTokenResult.Success) {
            val token = tokenRes.accessToken
            val driveResult = mutex.withLock {
                runCatching {
                    val rootFolderId = findOrCreateDriveFolder(
                        token = token,
                        folderName = ROOT_FOLDER_NAME,
                        parentDriveId = "root"
                    )
                    if (rootFolderId != null) {
                        performDirectDriveSync(
                            token = token,
                            rootFolderId = rootFolderId,
                            forceRestoreAll = forceRestoreAll,
                            deletedFolderIds = deletedFolderIds,
                            deletedScanIds = deletedScanIds,
                            trashedFolderIds = trashedFolderIds,
                            trashedScanIds = trashedScanIds
                        )
                    } else {
                        null
                    }
                }.getOrNull()
            }
            if (driveResult != null && driveResult.success) {
                return@withContext driveResult
            }
        }

        // Step 2: Automatic Firebase Cloud Sync fallback (when signed in via CredentialManager / Firebase Auth)
        if (Firebase.auth.currentUser != null) {
            val cloudResult = mutex.withLock {
                performFirebaseCloudFallbackSync(
                    forceRestoreAll = forceRestoreAll,
                    deletedFolderIds = deletedFolderIds,
                    deletedScanIds = deletedScanIds,
                    trashedFolderIds = trashedFolderIds,
                    trashedScanIds = trashedScanIds
                )
            }
            if (cloudResult.success) {
                return@withContext cloudResult
            }
            // If ConsentRequired was returned by Drive and Firebase also failed, surface consent or cloud error
            if (tokenRes is DriveTokenResult.ConsentRequired) {
                return@withContext DriveSyncCycleResult(
                    success = false,
                    message = "Grant Google Drive permission to continue",
                    consentIntent = tokenRes.consentIntent
                )
            }
            return@withContext cloudResult
        }

        if (tokenRes is DriveTokenResult.ConsentRequired) {
            return@withContext DriveSyncCycleResult(
                success = false,
                message = "Grant Google Drive permission to continue",
                consentIntent = tokenRes.consentIntent
            )
        }

        DriveSyncCycleResult(
            success = false,
            message = (tokenRes as? DriveTokenResult.Error)?.message ?: "Please sign in with Google."
        )
    }

    private suspend fun performFirebaseCloudFallbackSync(
        forceRestoreAll: Boolean,
        deletedFolderIds: Set<String> = emptySet(),
        deletedScanIds: Set<String> = emptySet(),
        trashedFolderIds: Set<String> = emptySet(),
        trashedScanIds: Set<String> = emptySet()
    ): DriveSyncCycleResult {
        return runCatching {
            val allTrashedFolderIds = trashedFolderIds + folderDao.getTrashedFoldersOnce().map { it.id }
            val allTrashedScanIds = trashedScanIds + scanDao.getTrashedScansOnce().map { it.id }

            val remoteFolders = firebaseCloudRepo.getUserFolders().getOrThrow().associateBy { it.id }
            val remoteScans = firebaseCloudRepo.getUserScans().getOrThrow().associateBy { it.id }

            val localFolders = folderDao.getAllFoldersOnce().associateBy { it.id }
            val localScans = scanDao.getAllScansOnce().associateBy { it.id }

            // Propagate local permanent deletions to Firestore when not doing an explicit full restore
            if (!forceRestoreAll) {
                for (delFolderId in deletedFolderIds) {
                    if (delFolderId !in localFolders && delFolderId !in allTrashedFolderIds && delFolderId in remoteFolders) {
                        firebaseCloudRepo.deleteFolder(delFolderId)
                    }
                }
                for (delScanId in deletedScanIds) {
                    if (delScanId !in localScans && delScanId !in allTrashedScanIds && delScanId in remoteScans) {
                        firebaseCloudRepo.deleteScan(delScanId)
                    }
                }
            }

            val rawMergedFolders = LinkedHashMap<String, FolderEntity>()
            val dirtyRemoteIds = mutableSetOf<String>()

            for ((id, localFolder) in localFolders) {
                val remote = remoteFolders[id]
                if (remote == null || (!forceRestoreAll && localFolder.modifiedAt >= remote.updatedAtMs)) {
                    firebaseCloudRepo.upsertFolder(
                        folderId = localFolder.id,
                        name = localFolder.name,
                        parentId = localFolder.parentId.orEmpty(),
                        sortOrder = localFolder.customOrder,
                        createdAtMs = localFolder.createdAt,
                        updatedAtMs = localFolder.modifiedAt
                    ).getOrThrow()
                    rawMergedFolders[id] = localFolder.copy(syncState = "SYNCED")
                    dirtyRemoteIds.add(id)
                } else {
                    rawMergedFolders[id] = FolderEntity(
                        id = remote.id,
                        name = remote.name,
                        parentId = remote.parentId.ifBlank { null },
                        createdAt = if (remote.createdAtMs > 0L) remote.createdAtMs else System.currentTimeMillis(),
                        modifiedAt = if (remote.updatedAtMs > 0L) remote.updatedAtMs else System.currentTimeMillis(),
                        customOrder = remote.sortOrder.toInt(),
                        syncState = "SYNCED"
                    )
                    dirtyRemoteIds.add(id)
                }
            }

            for ((id, remote) in remoteScansAndFoldersMissingLocally(remoteFolders, rawMergedFolders.keys)) {
                if (!forceRestoreAll && id in deletedFolderIds) continue
                if (id in allTrashedFolderIds) continue
                rawMergedFolders[id] = FolderEntity(
                    id = remote.id,
                    name = remote.name,
                    parentId = remote.parentId.ifBlank { null },
                    createdAt = if (remote.createdAtMs > 0L) remote.createdAtMs else System.currentTimeMillis(),
                    modifiedAt = if (remote.updatedAtMs > 0L) remote.updatedAtMs else System.currentTimeMillis(),
                    customOrder = remote.sortOrder.toInt(),
                    syncState = "SYNCED"
                )
                dirtyRemoteIds.add(id)
            }

            val mergedFolders = LinkedHashMap<String, FolderEntity>()
            val foldersToSaveLocally = mutableListOf<FolderEntity>()
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
                if (id in dirtyRemoteIds || validParentId != folder.parentId) {
                    foldersToSaveLocally.add(normalized)
                }
            }

            if (foldersToSaveLocally.isNotEmpty()) {
                folderDao.upsertFolders(foldersToSaveLocally)
            }

            var uploadedCount = 0
            var downloadedCount = 0
            val mergedScans = LinkedHashMap<String, ScanEntity>()
            val scansToSaveLocally = mutableListOf<ScanEntity>()

            for ((id, localScan) in localScans) {
                val remote = remoteScans[id]
                if (remote == null || localScan.syncState != "SYNCED") {
                    val thumbFile = File(localScan.thumbnailPath).takeIf { it.exists() && it.length() > 0L }
                    val editedFile = File(localScan.editedPath).takeIf { it.exists() && it.length() > 0L }
                    val b64 = when {
                        thumbFile != null && thumbFile.length() <= 500_000L ->
                            Base64.encodeToString(thumbFile.readBytes(), Base64.NO_WRAP)
                        editedFile != null && editedFile.length() <= 500_000L ->
                            Base64.encodeToString(editedFile.readBytes(), Base64.NO_WRAP)
                        editedFile != null -> encodeDownscaledJpegBase64(editedFile, 720)
                        else -> ""
                    }
                    val quadStr = "${localScan.cropTlX},${localScan.cropTlY},${localScan.cropTrX},${localScan.cropTrY},${localScan.cropBrX},${localScan.cropBrY},${localScan.cropBlX},${localScan.cropBlY}"
                    firebaseCloudRepo.upsertScan(
                        scanId = localScan.id,
                        folderId = localScan.folderId,
                        fileName = localScan.filename,
                        capturedAt = localScan.capturedAt,
                        filterName = localScan.filterMode,
                        rotationDegrees = localScan.rotationDegrees,
                        quadString = quadStr,
                        thumbBase64 = b64
                    ).getOrThrow()
                    uploadedCount++
                }
                val synced = localScan.copy(syncState = "SYNCED")
                mergedScans[id] = synced
                scansToSaveLocally.add(synced)
            }

            for ((id, remote) in remoteScans) {
                if (!forceRestoreAll && id in deletedScanIds) continue
                if (id in allTrashedScanIds || remote.folderId in allTrashedFolderIds) continue
                if (remote.folderId !in mergedFolders) continue
                val existingLocal = mergedScans[id]
                val localEditedExists = existingLocal != null &&
                    File(existingLocal.editedPath).let { it.exists() && it.length() > 0L }
                // Never overwrite an existing local high-res edited file with a compressed 720px cloud thumbnail
                if (!localEditedExists && remote.thumbBase64.isNotBlank()) {
                    val bytes = runCatching { Base64.decode(remote.thumbBase64, Base64.DEFAULT) }.getOrNull()
                    if (bytes != null && bytes.isNotEmpty()) {
                        val editedFile = File(editedDir, "edit_$id.jpg")
                        val thumbFile = File(thumbsDir, "thumb_$id.jpg")
                        val origFile = File(originalsDir, "orig_$id.jpg")
                        editedFile.parentFile?.mkdirs()
                        thumbFile.parentFile?.mkdirs()
                        editedFile.writeBytes(bytes)
                        thumbFile.writeBytes(bytes)
                        val coords = remote.quadString.split(",").mapNotNull { it.toFloatOrNull() }
                        val restoredScan = ScanEntity(
                            id = id,
                            folderId = remote.folderId,
                            filename = remote.fileName,
                            originalPath = existingLocal?.originalPath ?: origFile.absolutePath,
                            editedPath = editedFile.absolutePath,
                            thumbnailPath = thumbFile.absolutePath,
                            capturedAt = if (remote.capturedAt > 0L) remote.capturedAt else System.currentTimeMillis(),
                            modifiedAt = System.currentTimeMillis(),
                            rotationDegrees = remote.rotationDegrees.toInt(),
                            cropTlX = coords.getOrElse(0) { 0f },
                            cropTlY = coords.getOrElse(1) { 0f },
                            cropTrX = coords.getOrElse(2) { 1f },
                            cropTrY = coords.getOrElse(3) { 0f },
                            cropBrX = coords.getOrElse(4) { 1f },
                            cropBrY = coords.getOrElse(5) { 1f },
                            cropBlX = coords.getOrElse(6) { 0f },
                            cropBlY = coords.getOrElse(7) { 1f },
                            filterMode = remote.filterName.ifBlank { ScanFilter.MAGIC_COLOR.name },
                            width = existingLocal?.width ?: 0,
                            height = existingLocal?.height ?: 0,
                            syncState = "SYNCED"
                        )
                        mergedScans[id] = restoredScan
                        scansToSaveLocally.removeAll { it.id == id }
                        scansToSaveLocally.add(restoredScan)
                        downloadedCount++
                    }
                }
            }

            if (scansToSaveLocally.isNotEmpty()) {
                scanDao.insertScans(scansToSaveLocally)
            }

            DriveSyncCycleResult(
                success = true,
                uploadedCount = uploadedCount,
                downloadedCount = downloadedCount,
                totalFolders = mergedFolders.size,
                totalScans = mergedScans.size,
                message = "Synced (${mergedFolders.size} folders, ${mergedScans.size} scans)"
            )
        }.getOrElse { err ->
            DriveSyncCycleResult(
                success = false,
                message = "Sync error: ${err.localizedMessage ?: "Failed"}"
            )
        }
    }

    private fun remoteScansAndFoldersMissingLocally(
        remoteFolders: Map<String, CloudFolder>,
        existingKeys: Set<String>
    ): Map<String, CloudFolder> {
        return remoteFolders.filterKeys { it !in existingKeys }
    }

    private suspend fun performDirectDriveSync(
        token: String,
        rootFolderId: String,
        forceRestoreAll: Boolean,
        deletedFolderIds: Set<String> = emptySet(),
        deletedScanIds: Set<String> = emptySet(),
        trashedFolderIds: Set<String> = emptySet(),
        trashedScanIds: Set<String> = emptySet()
    ): DriveSyncCycleResult {
        val allTrashedFolderIds = trashedFolderIds + folderDao.getTrashedFoldersOnce().map { it.id }
        val allTrashedScanIds = trashedScanIds + scanDao.getTrashedScansOnce().map { it.id }

        val manifestFileId = findDriveFileId(
            token = token,
            name = MANIFEST_FILENAME,
            parentDriveId = rootFolderId
        )
        val remoteManifest = if (manifestFileId != null) {
            downloadDriveJson(token, manifestFileId)
        } else {
            JSONObject()
        }

        val remoteFoldersMap = parseFoldersMap(remoteManifest.optJSONArray("folders"))
        val remoteScansMap = parseScansMap(remoteManifest.optJSONArray("scans"))
        val remoteDriveIdsByScanId = parseDriveFileMap(remoteManifest.optJSONObject("driveFiles"))
        val remoteDeletedFolders = parseStringSet(remoteManifest.optJSONArray("deletedFolderIds"))
        val remoteDeletedScans = parseStringSet(remoteManifest.optJSONArray("deletedScanIds"))

        val localFolders = folderDao.getAllFoldersOnce().associateBy { it.id }
        val localScans = scanDao.getAllScansOnce().associateBy { it.id }
        val effectiveDeletedFolderIds = if (forceRestoreAll) {
            emptySet()
        } else {
            ((deletedFolderIds + remoteDeletedFolders) - localFolders.keys - allTrashedFolderIds)
        }
        val effectiveDeletedScanIds = if (forceRestoreAll) {
            emptySet()
        } else {
            ((deletedScanIds + remoteDeletedScans) - localScans.keys - allTrashedScanIds)
        }

        val rawMergedFolders = LinkedHashMap<String, FolderEntity>()
        val dirtyFolderIds = mutableSetOf<String>()

        for ((id, localFolder) in localFolders) {
            val remoteFolder = remoteFoldersMap[id]
            if (remoteFolder == null || (!forceRestoreAll && localFolder.modifiedAt >= remoteFolder.modifiedAt)) {
                rawMergedFolders[id] = localFolder.copy(syncState = "SYNCED")
            } else {
                rawMergedFolders[id] = remoteFolder.copy(syncState = "SYNCED")
                dirtyFolderIds.add(id)
            }
        }
        val preservedTrashedFolders = mutableListOf<FolderEntity>()
        for ((id, remoteFolder) in remoteFoldersMap) {
            if (!forceRestoreAll && id in effectiveDeletedFolderIds) continue
            if (id in allTrashedFolderIds) {
                preservedTrashedFolders.add(remoteFolder)
                continue
            }
            if (id !in rawMergedFolders) {
                rawMergedFolders[id] = remoteFolder.copy(syncState = "SYNCED")
                dirtyFolderIds.add(id)
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
            if (id in dirtyFolderIds || validParentId != folder.parentId) {
                foldersToInsertLocally.add(normalized)
            }
        }
        if (foldersToInsertLocally.isNotEmpty()) {
            folderDao.upsertFolders(foldersToInsertLocally)
        }

        val driveFolderIdByLocalFolderId = mutableMapOf<String, String>()
        fun ensureDriveFolderForLocal(localId: String): String {
            driveFolderIdByLocalFolderId[localId]?.let { return it }
            val folder = mergedFolders[localId] ?: return rootFolderId
            val parentDriveId = if (folder.parentId != null && mergedFolders.containsKey(folder.parentId)) {
                ensureDriveFolderForLocal(folder.parentId)
            } else {
                rootFolderId
            }
            val createdId = findOrCreateDriveFolder(
                token = token,
                folderName = folder.name,
                parentDriveId = parentDriveId
            ) ?: rootFolderId
            driveFolderIdByLocalFolderId[localId] = createdId
            return createdId
        }

        for (folderId in mergedFolders.keys) {
            ensureDriveFolderForLocal(folderId)
        }

        val mergedScans = LinkedHashMap<String, ScanEntity>()
        val updatedDriveFilesMap = HashMap(remoteDriveIdsByScanId)
        var uploadedCount = 0
        var downloadedCount = 0
        val scansToInsertLocally = mutableListOf<ScanEntity>()
        val newlySyncedScanIds = mutableListOf<String>()

        for ((id, localScan) in localScans) {
            val existingDriveFileId = updatedDriveFilesMap[id]
            val editedFile = File(localScan.editedPath)
            val needsUpload = (existingDriveFileId.isNullOrBlank() || localScan.syncState != "SYNCED") &&
                editedFile.exists() && editedFile.length() > 0L

            var finalState = localScan.syncState
            if (needsUpload) {
                val targetDriveFolderId = ensureDriveFolderForLocal(localScan.folderId)
                val displayFilename = if (localScan.filename.endsWith(".jpg", ignoreCase = true)) {
                    localScan.filename
                } else {
                    "${localScan.filename}.jpg"
                }
                val uploadedDriveId = uploadBinaryFileToDrive(
                    token = token,
                    fileName = displayFilename,
                    mimeType = "image/jpeg",
                    parentDriveId = targetDriveFolderId,
                    existingFileId = existingDriveFileId,
                    sourceFile = editedFile
                )
                if (!uploadedDriveId.isNullOrBlank()) {
                    updatedDriveFilesMap[id] = uploadedDriveId
                    uploadedCount++
                    finalState = "SYNCED"
                    newlySyncedScanIds.add(id)
                }
            } else if (!existingDriveFileId.isNullOrBlank()) {
                finalState = "SYNCED"
                if (localScan.syncState != "SYNCED") newlySyncedScanIds.add(id)
            }
            mergedScans[id] = localScan.copy(syncState = finalState)
        }

        if (!forceRestoreAll) {
            for ((scanId, driveFileId) in remoteDriveIdsByScanId) {
                val remoteScan = remoteScansMap[scanId]
                val isLocallyTrashed = scanId in allTrashedScanIds || (remoteScan != null && remoteScan.folderId in allTrashedFolderIds)
                val isTombstoned = scanId !in localScans && !isLocallyTrashed &&
                    (scanId in effectiveDeletedScanIds || (remoteScan != null && remoteScan.folderId in effectiveDeletedFolderIds))
                if (isTombstoned) {
                    trashDriveFile(token, driveFileId)
                    updatedDriveFilesMap.remove(scanId)
                }
            }
        }

        val preservedTrashedScans = mutableListOf<ScanEntity>()
        for ((id, remoteScan) in remoteScansMap) {
            if (!forceRestoreAll && id in effectiveDeletedScanIds) continue
            if (id in allTrashedScanIds || remoteScan.folderId in allTrashedFolderIds) {
                preservedTrashedScans.add(remoteScan)
                continue
            }
            if (remoteScan.folderId !in mergedFolders) continue
            val localScan = mergedScans[id]
            val localEditedExists = localScan != null && File(localScan.editedPath).let { it.exists() && it.length() > 0L }
            val remoteIsNewer = localScan != null && remoteScan.modifiedAt > localScan.modifiedAt && localScan.syncState == "SYNCED"
            if (localScan == null || !localEditedExists || remoteIsNewer || (forceRestoreAll && !localEditedExists)) {
                val driveFileId = updatedDriveFilesMap[id]
                if (!driveFileId.isNullOrBlank()) {
                    val editedFile = File(editedDir, "edit_$id.jpg")
                    val thumbFile = File(thumbsDir, "thumb_$id.jpg")
                    val origFile = File(originalsDir, "orig_$id.jpg")
                    val ok = downloadDriveBinaryFile(token, driveFileId, editedFile)
                    if (ok && editedFile.exists()) {
                        writeDownscaledThumbnail(editedFile, thumbFile, 720)
                        val restored = remoteScan.copy(
                            originalPath = localScan?.originalPath ?: origFile.absolutePath,
                            editedPath = editedFile.absolutePath,
                            thumbnailPath = thumbFile.absolutePath,
                            syncState = "SYNCED"
                        )
                        mergedScans[id] = restored
                        scansToInsertLocally.removeAll { it.id == id }
                        scansToInsertLocally.add(restored)
                        downloadedCount++
                    }
                }
            }
        }

        if (newlySyncedScanIds.isNotEmpty()) {
            scanDao.markScansSynced(newlySyncedScanIds)
        }
        if (scansToInsertLocally.isNotEmpty()) {
            scanDao.insertScans(scansToInsertLocally)
        }

        val newManifestJson = buildDriveManifestJson(
            folders = mergedFolders.values.toList() + preservedTrashedFolders,
            scans = mergedScans.values.toList() + preservedTrashedScans,
            driveFiles = updatedDriveFilesMap,
            deletedFolderIds = effectiveDeletedFolderIds,
            deletedScanIds = effectiveDeletedScanIds
        )
        val tempManifestFile = File(context.cacheDir, "drive_manifest_tmp.json")
        tempManifestFile.writeText(newManifestJson.toString(2), Charsets.UTF_8)
        uploadBinaryFileToDrive(
            token = token,
            fileName = MANIFEST_FILENAME,
            mimeType = "application/json",
            parentDriveId = rootFolderId,
            existingFileId = manifestFileId,
            sourceFile = tempManifestFile
        )
        runCatching { tempManifestFile.delete() }

        return DriveSyncCycleResult(
            success = true,
            uploadedCount = uploadedCount,
            downloadedCount = downloadedCount,
            totalFolders = mergedFolders.size,
            totalScans = mergedScans.size,
            message = "Google Drive synced (Kagojpotro) • ${mergedFolders.size} folders, ${mergedScans.size} scans",
            usedFullDriveBackup = true
        )
    }

    private fun findOrCreateDriveFolder(
        token: String,
        folderName: String,
        parentDriveId: String
    ): String? {
        val existing = findDriveFileId(
            token = token,
            name = folderName,
            parentDriveId = parentDriveId,
            mimeType = FOLDER_MIME
        )
        if (!existing.isNullOrBlank()) return existing

        val url = URL("https://www.googleapis.com/drive/v3/files")
        val payload = JSONObject().apply {
            put("name", folderName)
            put("mimeType", FOLDER_MIME)
            put("parents", JSONArray().put(parentDriveId))
        }.toString().toByteArray(Charsets.UTF_8)

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8000
            readTimeout = 15000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        return try {
            conn.outputStream.use { it.write(payload) }
            if (conn.responseCode !in 200..299) return null
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(resp).optString("id", "").ifBlank { null }
        } finally {
            conn.disconnect()
        }
    }

    private fun findDriveFileId(
        token: String,
        name: String,
        parentDriveId: String,
        mimeType: String? = null
    ): String? {
        val escapedName = name.replace("'", "\\'")
        val sb = StringBuilder("name = '$escapedName' and '$parentDriveId' in parents and trashed = false")
        if (mimeType != null) {
            sb.append(" and mimeType = '$mimeType'")
        }
        val q = URLEncoder.encode(sb.toString(), "UTF-8")
        val url = URL("https://www.googleapis.com/drive/v3/files?q=$q&fields=files(id,name)&pageSize=1")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 15000
            setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val files = JSONObject(body).optJSONArray("files") ?: return null
            if (files.length() > 0) {
                files.optJSONObject(0)?.optString("id", "")?.ifBlank { null }
            } else {
                null
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun uploadBinaryFileToDrive(
        token: String,
        fileName: String,
        mimeType: String,
        parentDriveId: String,
        existingFileId: String?,
        sourceFile: File
    ): String? {
        val boundary = "===KagojpotroBoundary${System.currentTimeMillis()}==="
        val urlStr = if (existingFileId.isNullOrBlank()) {
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id"
        } else {
            "https://www.googleapis.com/upload/drive/v3/files/$existingFileId?uploadType=multipart&fields=id"
        }
        val method = if (existingFileId.isNullOrBlank()) "POST" else "PATCH"

        val metadataJson = JSONObject().apply {
            put("name", fileName)
            if (existingFileId.isNullOrBlank()) {
                put("parents", JSONArray().put(parentDriveId))
            }
        }.toString()

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            doOutput = true
            connectTimeout = 10000
            readTimeout = 45000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        }

        return try {
            BufferedOutputStream(conn.outputStream).use { out ->
                val part1 = buildString {
                    append("--$boundary\r\n")
                    append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                    append(metadataJson)
                    append("\r\n--$boundary\r\n")
                    append("Content-Type: $mimeType\r\n\r\n")
                }
                out.write(part1.toByteArray(Charsets.UTF_8))
                BufferedInputStream(FileInputStream(sourceFile)).use { input ->
                    input.copyTo(out, bufferSize = 32 * 1024)
                }
                val closing = "\r\n--$boundary--\r\n"
                out.write(closing.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            if (conn.responseCode !in 200..299) return null
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(resp).optString("id", "").ifBlank { null }
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadDriveJson(token: String, fileId: String): JSONObject {
        val url = URL("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 20000
            setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            if (conn.responseCode !in 200..299) return JSONObject()
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadDriveBinaryFile(token: String, fileId: String, destFile: File): Boolean {
        val url = URL("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 35000
            setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            if (conn.responseCode !in 200..299) return false
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
    }

    private fun trashDriveFile(token: String, fileId: String) {
        runCatching {
            val url = URL("https://www.googleapis.com/drive/v3/files/$fileId")
            val payload = JSONObject().put("trashed", true).toString().toByteArray(Charsets.UTF_8)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "PATCH"
                doOutput = true
                connectTimeout = 8000
                readTimeout = 15000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            try {
                conn.outputStream.use { it.write(payload) }
                conn.responseCode
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun buildDriveManifestJson(
        folders: List<FolderEntity>,
        scans: List<ScanEntity>,
        driveFiles: Map<String, String>,
        deletedFolderIds: Set<String> = emptySet(),
        deletedScanIds: Set<String> = emptySet()
    ): JSONObject {
        val foldersArr = JSONArray()
        for (f in folders) {
            foldersArr.put(
                JSONObject().apply {
                    put("id", f.id)
                    put("name", f.name)
                    put("parentId", f.parentId ?: JSONObject.NULL)
                    put("createdAt", f.createdAt)
                    put("modifiedAt", f.modifiedAt)
                    put("customOrder", f.customOrder)
                }
            )
        }
        val scansArr = JSONArray()
        for (s in scans) {
            scansArr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("folderId", s.folderId)
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
        val driveFilesObj = JSONObject()
        for ((scanId, driveId) in driveFiles) {
            driveFilesObj.put(scanId, driveId)
        }
        val delFoldersArr = JSONArray()
        deletedFolderIds.toList().takeLast(500).forEach { delFoldersArr.put(it) }
        val delScansArr = JSONArray()
        deletedScanIds.toList().takeLast(1500).forEach { delScansArr.put(it) }
        return JSONObject().apply {
            put("updatedAt", System.currentTimeMillis())
            put("folders", foldersArr)
            put("scans", scansArr)
            put("driveFiles", driveFilesObj)
            put("deletedFolderIds", delFoldersArr)
            put("deletedScanIds", delScansArr)
        }
    }

    private fun parseStringSet(arr: JSONArray?): Set<String> {
        if (arr == null) return emptySet()
        val set = linkedSetOf<String>()
        for (i in 0 until arr.length()) {
            val v = arr.optString(i, "").trim()
            if (v.isNotEmpty()) set.add(v)
        }
        return set
    }

    private fun parseFoldersMap(arr: JSONArray?): Map<String, FolderEntity> {
        if (arr == null) return emptyMap()
        val result = LinkedHashMap<String, FolderEntity>()
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
        val result = LinkedHashMap<String, ScanEntity>()
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

    private fun parseDriveFileMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val map = mutableMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.optString(k, "")
            if (k.isNotBlank() && v.isNotBlank()) {
                map[k] = v
            }
        }
        return map
    }

    private fun encodeDownscaledJpegBase64(sourceFile: File, maxDim: Int): String {
        return runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(sourceFile.absolutePath, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return ""
            var sampleSize = 1
            while (w / sampleSize > maxDim * 2 || h / sampleSize > maxDim * 2) {
                sampleSize *= 2
            }
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decoded = android.graphics.BitmapFactory.decodeFile(sourceFile.absolutePath, opts) ?: return ""
            val scale = minOf(1f, maxDim.toFloat() / maxOf(decoded.width, decoded.height).toFloat())
            val tw = (decoded.width * scale).toInt().coerceAtLeast(1)
            val th = (decoded.height * scale).toInt().coerceAtLeast(1)
            val scaled = if (scale < 0.99f) android.graphics.Bitmap.createScaledBitmap(decoded, tw, th, true) else decoded
            val baos = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, baos)
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
            val bytes = baos.toByteArray()
            if (bytes.size <= 500_000) Base64.encodeToString(bytes, Base64.NO_WRAP) else ""
        }.getOrDefault("")
    }

    private fun writeDownscaledThumbnail(sourceFile: File, thumbFile: File, maxDim: Int) {
        runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(sourceFile.absolutePath, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) {
                sourceFile.copyTo(thumbFile, overwrite = true)
                return
            }
            var sampleSize = 1
            while (w / sampleSize > maxDim * 2 || h / sampleSize > maxDim * 2) {
                sampleSize *= 2
            }
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decoded = android.graphics.BitmapFactory.decodeFile(sourceFile.absolutePath, opts) ?: return
            val scale = minOf(1f, maxDim.toFloat() / maxOf(decoded.width, decoded.height).toFloat())
            val tw = (decoded.width * scale).toInt().coerceAtLeast(1)
            val th = (decoded.height * scale).toInt().coerceAtLeast(1)
            val thumb = if (scale < 0.99f) {
                android.graphics.Bitmap.createScaledBitmap(decoded, tw, th, true)
            } else {
                decoded
            }
            thumbFile.parentFile?.mkdirs()
            FileOutputStream(thumbFile).use { out ->
                thumb.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
            }
            if (thumb !== decoded) thumb.recycle()
            decoded.recycle()
        }
    }

    suspend fun deleteAllCloudAccountData(email: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val effectiveEmail = email.ifBlank { Firebase.auth.currentUser?.email.orEmpty() }
            if (effectiveEmail.isNotBlank()) {
                val tokenRes = acquireAccessToken(effectiveEmail)
                if (tokenRes is DriveTokenResult.Success) {
                    runCatching {
                        val rootId = findDriveFileId(
                            token = tokenRes.accessToken,
                            name = ROOT_FOLDER_NAME,
                            parentDriveId = "root",
                            mimeType = FOLDER_MIME
                        )
                        if (!rootId.isNullOrBlank()) {
                            val url = URL("https://www.googleapis.com/drive/v3/files/$rootId")
                            val conn = (url.openConnection() as HttpURLConnection).apply {
                                requestMethod = "DELETE"
                                connectTimeout = 8000
                                readTimeout = 15000
                                setRequestProperty("Authorization", "Bearer ${tokenRes.accessToken}")
                            }
                            try {
                                conn.responseCode
                            } finally {
                                conn.disconnect()
                            }
                        }
                    }
                }
            }
            firebaseCloudRepo.deleteAllUserData().isSuccess
        }
    }
}
