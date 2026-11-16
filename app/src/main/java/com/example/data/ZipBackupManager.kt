package com.example.data

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ZipBackupResult(
    val success: Boolean,
    val foldersCount: Int = 0,
    val scansCount: Int = 0,
    val message: String,
    val restoredFolderIds: Set<String> = emptySet(),
    val restoredScanIds: Set<String> = emptySet()
)

/**
 * Handles 1-Click Offline Library Backup & Restore (.zip) so users can move their entire
 * folder hierarchy, timestamps, custom sort orders, and scanned images to a new phone without
 * requiring internet or a PC.
 */
class ZipBackupManager(
    private val context: Context,
    private val database: KagojpotroDatabase,
    private val originalsDir: File,
    private val editedDir: File,
    private val thumbsDir: File
) {
    private val folderDao = database.folderDao()
    private val scanDao = database.scanDao()

    suspend fun exportFullBackupToUri(destinationUri: Uri): ZipBackupResult = withContext(Dispatchers.IO) {
        runCatching {
            val folders = folderDao.getAllFoldersOnce()
            val scans = scanDao.getAllScansOnce()

            val manifestJson = buildBackupManifestJson(folders, scans)
            val manifestBytes = manifestJson.toString(2).toByteArray(Charsets.UTF_8)

            val outputStream = context.contentResolver.openOutputStream(destinationUri)
                ?: return@withContext ZipBackupResult(false, message = "Could not open backup destination file.")

            ZipOutputStream(BufferedOutputStream(outputStream)).use { zos ->
                // 1. Write manifest.json
                val manifestEntry = ZipEntry("manifest.json")
                zos.putNextEntry(manifestEntry)
                zos.write(manifestBytes)
                zos.closeEntry()

                // 2. Write all scan image files (edited, original, thumb)
                for (scan in scans) {
                    val editedFile = File(scan.editedPath)
                    if (editedFile.exists() && editedFile.length() > 0L) {
                        addFileToZip(zos, "edited/${scan.id}.jpg", editedFile)
                    }
                    val origFile = File(scan.originalPath)
                    if (origFile.exists() && origFile.length() > 0L) {
                        addFileToZip(zos, "original/${scan.id}.jpg", origFile)
                    }
                    val thumbFile = File(scan.thumbnailPath)
                    if (thumbFile.exists() && thumbFile.length() > 0L) {
                        addFileToZip(zos, "thumb/${scan.id}.jpg", thumbFile)
                    }
                }
            }

            ZipBackupResult(
                success = true,
                foldersCount = folders.size,
                scansCount = scans.size,
                message = "Backup exported: ${folders.size} folder(s), ${scans.size} scan(s)"
            )
        }.getOrElse { err ->
            ZipBackupResult(
                success = false,
                message = "Backup export failed: ${err.localizedMessage ?: "Unknown error"}"
            )
        }
    }

    private val safeIdRegex = Regex("^[a-zA-Z0-9_-]{1,120}$")

    companion object {
        private const val MAX_ZIP_ENTRIES = 5_000
        private const val MAX_MANIFEST_BYTES = 2L * 1024 * 1024
        private const val MAX_IMAGE_ENTRY_BYTES = 32L * 1024 * 1024
        private const val MAX_TOTAL_RESTORE_BYTES = 512L * 1024 * 1024
    }

    suspend fun restoreFullBackupFromUri(sourceUri: Uri): ZipBackupResult = withContext(Dispatchers.IO) {
        val stagingRoot = File(context.cacheDir, "zip_restore_stage_${java.util.UUID.randomUUID()}").apply {
            mkdirs()
        }
        val stagedEditedDir = File(stagingRoot, "edited").apply { mkdirs() }
        val stagedOrigDir = File(stagingRoot, "original").apply { mkdirs() }
        val stagedThumbDir = File(stagingRoot, "thumb").apply { mkdirs() }

        try {
            runCatching {
                val inputStream = context.contentResolver.openInputStream(sourceUri)
                    ?: return@withContext ZipBackupResult(false, message = "Could not read selected backup .zip file.")

                var manifestText: String? = null
                var entryCount = 0
                var totalBytesRead = 0L

                ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        entryCount++
                        if (entryCount > MAX_ZIP_ENTRIES) {
                            error("Backup archive exceeds maximum supported entry count ($MAX_ZIP_ENTRIES).")
                        }
                        val name = entry.name
                        if (!entry.isDirectory && !name.contains("..")) {
                            when {
                                name == "manifest.json" -> {
                                    val bytes = readBoundedZipBytes(zis, MAX_MANIFEST_BYTES)
                                    totalBytesRead += bytes.size
                                    if (totalBytesRead > MAX_TOTAL_RESTORE_BYTES) {
                                        error("Backup archive exceeds maximum restore size limit.")
                                    }
                                    manifestText = bytes.toString(Charsets.UTF_8)
                                }
                                name.startsWith("edited/") && name.endsWith(".jpg") -> {
                                    val scanId = name.removePrefix("edited/").removeSuffix(".jpg")
                                    if (safeIdRegex.matches(scanId)) {
                                        val dest = safeResolveChild(stagedEditedDir, "edit_$scanId.jpg")
                                        if (dest != null) {
                                            val written = writeZipEntryToFile(zis, dest, MAX_IMAGE_ENTRY_BYTES)
                                            totalBytesRead += written
                                        }
                                    }
                                }
                                name.startsWith("original/") && name.endsWith(".jpg") -> {
                                    val scanId = name.removePrefix("original/").removeSuffix(".jpg")
                                    if (safeIdRegex.matches(scanId)) {
                                        val dest = safeResolveChild(stagedOrigDir, "orig_$scanId.jpg")
                                        if (dest != null) {
                                            val written = writeZipEntryToFile(zis, dest, MAX_IMAGE_ENTRY_BYTES)
                                            totalBytesRead += written
                                        }
                                    }
                                }
                                name.startsWith("thumb/") && name.endsWith(".jpg") -> {
                                    val scanId = name.removePrefix("thumb/").removeSuffix(".jpg")
                                    if (safeIdRegex.matches(scanId)) {
                                        val dest = safeResolveChild(stagedThumbDir, "thumb_$scanId.jpg")
                                        if (dest != null) {
                                            val written = writeZipEntryToFile(zis, dest, MAX_IMAGE_ENTRY_BYTES)
                                            totalBytesRead += written
                                        }
                                    }
                                }
                            }
                            if (totalBytesRead > MAX_TOTAL_RESTORE_BYTES) {
                                error("Backup archive exceeds maximum restore size limit.")
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }

                val rawManifest = manifestText
                    ?: return@withContext ZipBackupResult(false, message = "Invalid backup file (missing manifest.json).")

                val json = JSONObject(rawManifest)
                val foldersArr = json.optJSONArray("folders") ?: JSONArray()
                val scansArr = json.optJSONArray("scans") ?: JSONArray()

                val rawFoldersById = LinkedHashMap<String, FolderEntity>()
                for (i in 0 until foldersArr.length()) {
                    val obj = foldersArr.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "").trim()
                    val name = sanitizeArchiveDisplayName(obj.optString("name", ""))
                    if (!safeIdRegex.matches(id) || name.isBlank()) continue
                    val rawParent = if (obj.isNull("parentId")) null else obj.optString("parentId", "").trim().ifBlank { null }
                    val parentId = if (rawParent != null && safeIdRegex.matches(rawParent)) rawParent else null
                    rawFoldersById[id] = FolderEntity(
                        id = id,
                        name = name,
                        parentId = parentId,
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                        modifiedAt = obj.optLong("modifiedAt", System.currentTimeMillis()),
                        customOrder = obj.optInt("customOrder", i),
                        syncState = "LOCAL_DIRTY"
                    )
                }

                // Enforce the 1-level subfolder limit and prevent cycles/dangling parent links
                val restoredFolders = rawFoldersById.values.map { folder ->
                    val pId = folder.parentId
                    val validParentId = if (
                        pId != null &&
                        pId != folder.id &&
                        rawFoldersById[pId]?.parentId == null &&
                        rawFoldersById.containsKey(pId)
                    ) {
                        pId
                    } else {
                        null
                    }
                    if (validParentId == folder.parentId) folder else folder.copy(parentId = validParentId)
                }
                val validFolderIds = restoredFolders.map { it.id }.toSet()

                val restoredScans = mutableListOf<ScanEntity>()
                var skippedMissingScans = 0
                val stagedFileCopies = mutableListOf<Pair<File, File>>()

                for (i in 0 until scansArr.length()) {
                    val obj = scansArr.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "").trim()
                    val folderId = obj.optString("folderId", "").trim()
                    if (!safeIdRegex.matches(id) || folderId !in validFolderIds) continue

                    val quad = NormalizedQuad(
                        tlX = finiteCoord(obj.optDouble("cropTlX", 0.0), 0f),
                        tlY = finiteCoord(obj.optDouble("cropTlY", 0.0), 0f),
                        trX = finiteCoord(obj.optDouble("cropTrX", 1.0), 1f),
                        trY = finiteCoord(obj.optDouble("cropTrY", 0.0), 0f),
                        brX = finiteCoord(obj.optDouble("cropBrX", 1.0), 1f),
                        brY = finiteCoord(obj.optDouble("cropBrY", 1.0), 1f),
                        blX = finiteCoord(obj.optDouble("cropBlX", 0.0), 0f),
                        blY = finiteCoord(obj.optDouble("cropBlY", 1.0), 1f)
                    ).clamped()

                    val cleanFilename = sanitizeArchiveDisplayName(
                        obj.optString("filename", "Kagojpotro_$id.jpg")
                    ).ifBlank { "Kagojpotro_$id.jpg" }

                    val filterEnum = ScanFilter.fromStoredName(
                        obj.optString("filterMode", ScanFilter.MAGIC_COLOR.name)
                    )
                    val cleanFilter = filterEnum.name
                    val rotDeg = ((obj.optInt("rotationDegrees", 0) % 360) + 360) % 360
                    val rawStrokesJson = obj.optString("eraserStrokesJson", "[]")
                    val decodedStrokes = decodeEraserStrokes(rawStrokesJson)
                    val normalizedStrokesJson = encodeEraserStrokes(decodedStrokes)

                    val stagedEdited = safeResolveChild(stagedEditedDir, "edit_$id.jpg") ?: continue
                    val stagedOrig = safeResolveChild(stagedOrigDir, "orig_$id.jpg") ?: continue
                    val stagedThumb = safeResolveChild(stagedThumbDir, "thumb_$id.jpg") ?: continue
                    val hasEdited = stagedEdited.exists() && stagedEdited.length() > 0L
                    val hasOrig = stagedOrig.exists() && stagedOrig.length() > 0L
                    val hasThumb = stagedThumb.exists() && stagedThumb.length() > 0L
                    if (!hasEdited && !hasOrig && !hasThumb) {
                        skippedMissingScans++
                        continue
                    }
                    var renderedWidth = obj.optInt("width", 0).coerceAtLeast(0)
                    var renderedHeight = obj.optInt("height", 0).coerceAtLeast(0)
                    if (!hasEdited) {
                        if (hasOrig) {
                            val renderedFromOrig = renderEditedFromOriginal(
                                origFile = stagedOrig,
                                destEditedFile = stagedEdited,
                                quad = quad,
                                rotationDegrees = rotDeg,
                                filter = filterEnum,
                                eraserStrokes = decodedStrokes
                            )
                            if (renderedFromOrig != null) {
                                renderedWidth = renderedFromOrig.first
                                renderedHeight = renderedFromOrig.second
                            } else {
                                stagedOrig.copyTo(stagedEdited, overwrite = true)
                            }
                        } else {
                            stagedThumb.copyTo(stagedEdited, overwrite = true)
                        }
                    }
                    if (!hasThumb && stagedEdited.exists()) {
                        writeDownscaledThumbnail(stagedEdited, stagedThumb, 720)
                    }
                    if (!hasOrig && stagedEdited.exists()) {
                        stagedEdited.copyTo(stagedOrig, overwrite = true)
                    }

                    val finalEdited = safeResolveChild(editedDir, "edit_$id.jpg") ?: continue
                    val finalOrig = safeResolveChild(originalsDir, "orig_$id.jpg") ?: continue
                    val finalThumb = safeResolveChild(thumbsDir, "thumb_$id.jpg") ?: continue
                    stagedFileCopies.add(stagedEdited to finalEdited)
                    stagedFileCopies.add(stagedOrig to finalOrig)
                    stagedFileCopies.add(stagedThumb to finalThumb)

                    restoredScans.add(
                        ScanEntity(
                            id = id,
                            folderId = folderId,
                            filename = cleanFilename,
                            originalPath = finalOrig.absolutePath,
                            editedPath = finalEdited.absolutePath,
                            thumbnailPath = finalThumb.absolutePath,
                            capturedAt = obj.optLong("capturedAt", System.currentTimeMillis()),
                            modifiedAt = obj.optLong("modifiedAt", System.currentTimeMillis()),
                            rotationDegrees = rotDeg,
                            cropTlX = quad.tlX,
                            cropTlY = quad.tlY,
                            cropTrX = quad.trX,
                            cropTrY = quad.trY,
                            cropBrX = quad.brX,
                            cropBrY = quad.brY,
                            cropBlX = quad.blX,
                            cropBlY = quad.blY,
                            filterMode = cleanFilter,
                            width = renderedWidth,
                            height = renderedHeight,
                            syncState = "LOCAL_DIRTY",
                            eraserStrokesJson = normalizedStrokesJson
                        )
                    )
                }

                // Commit files first from validated staging, then commit Room tables atomically
                for ((src, dst) in stagedFileCopies) {
                    if (src.exists()) {
                        dst.parentFile?.mkdirs()
                        src.copyTo(dst, overwrite = true)
                    }
                }
                if (restoredFolders.isNotEmpty()) {
                    folderDao.upsertFolders(restoredFolders)
                }
                if (restoredScans.isNotEmpty()) {
                    scanDao.insertScans(restoredScans)
                }

                val skippedSuffix = if (skippedMissingScans > 0) " ($skippedMissingScans missing image(s) skipped)" else ""
                ZipBackupResult(
                    success = true,
                    foldersCount = restoredFolders.size,
                    scansCount = restoredScans.size,
                    message = "Restored ${restoredFolders.size} folder(s) and ${restoredScans.size} scan(s)$skippedSuffix",
                    restoredFolderIds = validFolderIds,
                    restoredScanIds = restoredScans.map { it.id }.toSet()
                )
            }.getOrElse { err ->
                ZipBackupResult(
                    success = false,
                    message = "Restore failed: ${err.localizedMessage ?: "Invalid backup archive"}"
                )
            }
        } finally {
            runCatching { stagingRoot.deleteRecursively() }
        }
    }

    private fun finiteCoord(value: Double, fallback: Float): Float {
        return if (value.isFinite()) value.toFloat().coerceIn(0f, 1f) else fallback
    }

    private fun sanitizeArchiveDisplayName(raw: String): String {
        return raw.replace(Regex("[\\x00-\\x1F\\\\/:*?\"<>|]"), "_")
            .trim()
            .take(180)
    }

    private fun safeResolveChild(targetDir: File, fileName: String): File? {
        return runCatching {
            val baseCanonical = targetDir.canonicalPath
            val candidate = File(targetDir, fileName)
            val candidateCanonical = candidate.canonicalPath
            if (candidateCanonical.startsWith(baseCanonical + File.separator)) candidate else null
        }.getOrNull()
    }

    private fun renderEditedFromOriginal(
        origFile: File,
        destEditedFile: File,
        quad: NormalizedQuad,
        rotationDegrees: Int,
        filter: ScanFilter,
        eraserStrokes: List<EraserStrokeRecord>
    ): Pair<Int, Int>? {
        return runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(origFile.absolutePath, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return@runCatching null
            var sampleSize = 1
            while (w / sampleSize > 4096 * 1.5 || h / sampleSize > 4096 * 1.5) {
                sampleSize *= 2
            }
            val opts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            val decoded = android.graphics.BitmapFactory.decodeFile(origFile.absolutePath, opts) ?: return@runCatching null
            com.example.processing.DocumentImageProcessor.init(context.applicationContext)
            var processed = com.example.processing.DocumentImageProcessor.processDocument(
                original = decoded,
                quad = quad,
                rotationDegrees = rotationDegrees,
                filter = filter,
                filterIntensity = 0.5f,
                maxDimension = 4096
            )
            for (stroke in eraserStrokes) {
                val pts = stroke.normalizedPoints.map { (x, y) -> androidx.compose.ui.geometry.Offset(x, y) }
                val healed = com.example.processing.SmartDocumentEraser.eraseStroke(
                    source = processed,
                    normalizedPoints = pts,
                    normalizedRadius = stroke.normalizedRadius
                )
                if (healed !== processed && processed !== decoded && !processed.isRecycled) {
                    processed.recycle()
                }
                processed = healed
            }
            val finalW = processed.width
            val finalH = processed.height
            destEditedFile.parentFile?.mkdirs()
            FileOutputStream(destEditedFile).use { out ->
                processed.compress(android.graphics.Bitmap.CompressFormat.JPEG, 96, out)
            }
            if (processed !== decoded && !processed.isRecycled) processed.recycle()
            if (!decoded.isRecycled) decoded.recycle()
            finalW to finalH
        }.getOrNull()
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

    private fun addFileToZip(zos: ZipOutputStream, entryName: String, file: File) {
        val entry = ZipEntry(entryName)
        zos.putNextEntry(entry)
        BufferedInputStream(FileInputStream(file)).use { input ->
            input.copyTo(zos, bufferSize = 32 * 1024)
        }
        zos.closeEntry()
    }

    private fun readBoundedZipBytes(zis: ZipInputStream, maxBytes: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = zis.read(buf)
            if (read <= 0) break
            total += read
            if (total > maxBytes) {
                error("Backup entry exceeds safety limit (${maxBytes / (1024 * 1024)} MB).")
            }
            out.write(buf, 0, read)
        }
        return out.toByteArray()
    }

    private fun writeZipEntryToFile(zis: ZipInputStream, destFile: File, maxBytes: Long): Long {
        destFile.parentFile?.mkdirs()
        var written = 0L
        BufferedOutputStream(FileOutputStream(destFile)).use { out ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val read = zis.read(buf)
                if (read <= 0) break
                written += read
                if (written > maxBytes) {
                    error("Backup image entry exceeds ${maxBytes / (1024 * 1024)} MB limit.")
                }
                out.write(buf, 0, read)
            }
            out.flush()
        }
        return written
    }

    private fun buildBackupManifestJson(
        folders: List<FolderEntity>,
        scans: List<ScanEntity>
    ): JSONObject {
        val foldersArray = JSONArray()
        for (f in folders) {
            foldersArray.put(
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
        val scansArray = JSONArray()
        for (s in scans) {
            scansArray.put(
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
        return JSONObject().apply {
            put("app", "Kagojpotro")
            put("version", 1)
            put("exportedAt", System.currentTimeMillis())
            put("folders", foldersArray)
            put("scans", scansArray)
        }
    }
}
