package com.example.data

import android.graphics.Bitmap
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class AppLanguage(val code: String, val badge: String) {
    EN("en", "EN"),
    BN("bn", "বাং")
}

enum class FolderViewMode {
    GRID,
    LIST
}

enum class FolderSortMode {
    NAME,
    LAST_MODIFIED,
    LAST_CREATED,
    CUSTOM
}

enum class ImageSortMode {
    NEWEST_FIRST,
    OLDEST_FIRST
}

enum class PdfPageFormat {
    FIT_TO_IMAGE,
    STANDARD_A4
}

enum class AppThemeMode(val labelEn: String, val labelBn: String) {
    SYSTEM("System", "সিস্টেম"),
    LIGHT("Light", "লাইট"),
    DARK("Dark", "ডার্ক");

    fun label(lang: AppLanguage): String = if (lang == AppLanguage.BN) labelBn else labelEn
}

enum class ScanResolutionPreset(val maxDim: Int, val labelEn: String, val labelBn: String) {
    MAXIMUM_4096(4096, "Maximum (4096px)", "সর্বোচ্চ (4096px)"),
    HIGH_2400(2400, "High (2400px)", "উচ্চ (2400px)"),
    COMPACT_1600(1600, "Compact (1600px)", "কম্প্যাক্ট (1600px)");

    fun label(lang: AppLanguage): String = if (lang == AppLanguage.BN) labelBn else labelEn
}

enum class AppStorageSyncMode(val labelEn: String, val labelBn: String) {
    PHONE_ONLY("Phone Only", "শুধু ফোনে"),
    PC_SERVER_SYNC("PC Server Sync", "পিসি সার্ভার সিঙ্ক"),
    GOOGLE_CLOUD_SYNC("Sign in with Google", "Google সাইন ইন");

    fun label(lang: AppLanguage): String = if (lang == AppLanguage.BN) labelBn else labelEn
}

data class PcSyncConfig(
    val mode: AppStorageSyncMode = AppStorageSyncMode.PHONE_ONLY,
    val serverUrl: String = "",
    val userDirectory: String = "",
    val accessKey: String = "",
    val autoSyncEnabled: Boolean = true
)

data class PcSyncRuntimeStatus(
    val isSyncing: Boolean = false,
    val isPcOnline: Boolean = false,
    val lastSyncedAt: Long = 0L,
    val pendingUploadCount: Int = 0,
    val pcFreeSpaceBytes: Long = 0L,
    val pcTotalSpaceBytes: Long = 0L,
    val pcUserUsedBytes: Long = 0L,
    val statusMessage: String = "Ready",
    val lastError: String? = null
)

enum class AccountSwitchTargetKind {
    PC_SERVER,
    GOOGLE_CLOUD
}

data class PendingAccountSwitchPrompt(
    val targetKind: AccountSwitchTargetKind,
    val previousAccountLabel: String,
    val newAccountLabel: String,
    val newAccountKey: String,
    val pcConfigToConnect: PcSyncConfig? = null,
    val googleEmailToConnect: String = ""
)

data class StorageUsageSummary(
    val savedScansBytes: Long = 0L,
    val originalsBytes: Long = 0L,
    val thumbnailsBytes: Long = 0L,
    val tempHighResSyncedBytes: Long = 0L,
    val unsyncedHighResBytes: Long = 0L,
    val cacheBytes: Long = 0L,
    val totalScansCount: Int = 0,
    val syncedScansCount: Int = 0,
    val pendingSyncScansCount: Int = 0,
    val totalFoldersCount: Int = 0
)

/**
 * Six high-definition document processing filters:
 * 1. Original, 2. Magic Color, 3. AI Enhance (on-device ONNX), 4. Whiteboard, 5. Clear B&W, 6. Grayscale.
 */
enum class ScanFilter(val labelEn: String, val labelBn: String) {
    ORIGINAL("Original", "আসল"),
    MAGIC_COLOR("Magic Color", "ম্যাজিক কালার"),
    AI_ENHANCE("AI Enhance", "AI এনহ্যান্স"),
    WHITEBOARD("Whiteboard", "হোয়াইটবোর্ড"),
    CLEAR_BW("Clear B&W", "স্পষ্ট সাদা-কালো"),
    GRAYSCALE("Grayscale", "সাদাকালো");

    fun label(lang: AppLanguage): String = if (lang == AppLanguage.BN) labelBn else labelEn

    companion object {
        fun fromStoredName(name: String): ScanFilter {
            return when (name) {
                "AI_ENHANCE", "AI_ENHANCED" -> AI_ENHANCE
                "AUTO", "AUTO_COLOR", "NO_SHADOW", "ENHANCE", "ENHANCED", "SHARP" -> MAGIC_COLOR
                "LIGHTEN" -> WHITEBOARD
                else -> runCatching { valueOf(name) }.getOrDefault(MAGIC_COLOR)
            }
        }
    }
}

/**
 * Normalized 4-corner document quadrilateral in [0f, 1f] coordinates relative to the
 * unrotated original image dimensions (top-left, top-right, bottom-right, bottom-left).
 */
data class NormalizedQuad(
    val tlX: Float = 0f,
    val tlY: Float = 0f,
    val trX: Float = 1f,
    val trY: Float = 0f,
    val brX: Float = 1f,
    val brY: Float = 1f,
    val blX: Float = 0f,
    val blY: Float = 1f
) {
    fun clamped(): NormalizedQuad {
        val qTlX = tlX.coerceIn(0f, 1f)
        val qTlY = tlY.coerceIn(0f, 1f)
        val qTrX = trX.coerceIn(0f, 1f)
        val qTrY = trY.coerceIn(0f, 1f)
        val qBrX = brX.coerceIn(0f, 1f)
        val qBrY = brY.coerceIn(0f, 1f)
        val qBlX = blX.coerceIn(0f, 1f)
        val qBlY = blY.coerceIn(0f, 1f)
        val coerced = NormalizedQuad(qTlX, qTlY, qTrX, qTrY, qBrX, qBrY, qBlX, qBlY)
        if (isClockwiseConvexInBounds(coerced)) {
            return coerced
        }

        val cTlX = qTlX.coerceIn(0f, 0.95f)
        val cTlY = qTlY.coerceIn(0f, 0.95f)
        val cTrX = qTrX.coerceIn(max(0.05f, cTlX + 0.04f), 1f)
        val cTrY = qTrY.coerceIn(0f, 0.95f)
        val cBrX = qBrX.coerceIn(max(0.05f, qBlX + 0.04f), 1f)
        val cBrY = qBrY.coerceIn(max(0.05f, cTrY + 0.04f), 1f)
        val cBlX = qBlX.coerceIn(0f, min(0.95f, cBrX - 0.04f))
        val cBlY = max(0.05f, cTlY + 0.04f).let { minBlY -> qBlY.coerceIn(minBlY, 1f) }
        return NormalizedQuad(cTlX, cTlY, cTrX, cTrY, cBrX, cBrY, cBlX, cBlY)
    }

    private fun isClockwiseConvexInBounds(q: NormalizedQuad): Boolean {
        val xs = floatArrayOf(q.tlX, q.trX, q.brX, q.blX)
        val ys = floatArrayOf(q.tlY, q.trY, q.brY, q.blY)
        var signedArea2 = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val k = (i + 2) % 4
            val dx1 = xs[j] - xs[i]
            val dy1 = ys[j] - ys[i]
            if (hypot(dx1.toDouble(), dy1.toDouble()).toFloat() < 0.04f) return false
            val dx2 = xs[k] - xs[j]
            val dy2 = ys[k] - ys[j]
            if (dx1 * dy2 - dy1 * dx2 <= 1e-4f) return false
            signedArea2 += xs[i] * ys[j] - xs[j] * ys[i]
        }
        return abs(signedArea2) * 0.5f >= 0.003f
    }

    fun isFullFrame(epsilon: Float = 0.015f): Boolean {
        return tlX <= epsilon && tlY <= epsilon &&
            trX >= 1f - epsilon && trY <= epsilon &&
            brX >= 1f - epsilon && brY >= 1f - epsilon &&
            blX <= epsilon && blY >= 1f - epsilon
    }

    companion object {
        val FULL_FRAME = NormalizedQuad(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
    }
}

@Entity(
    tableName = "folders",
    indices = [Index(value = ["parentId"]), Index(value = ["name"]), Index(value = ["deletedAt"])]
)
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String? = null,
    val createdAt: Long,
    val modifiedAt: Long,
    val customOrder: Int = 0,
    val syncState: String = "LOCAL_DIRTY",
    val deletedAt: Long? = null
)

@Entity(
    tableName = "scans",
    indices = [Index(value = ["folderId"]), Index(value = ["capturedAt"]), Index(value = ["deletedAt"])]
)
data class ScanEntity(
    @PrimaryKey val id: String,
    val folderId: String,
    val filename: String,
    val originalPath: String,
    val editedPath: String,
    val thumbnailPath: String,
    val capturedAt: Long,
    val modifiedAt: Long,
    val rotationDegrees: Int = 0,
    val cropTlX: Float = 0f,
    val cropTlY: Float = 0f,
    val cropTrX: Float = 1f,
    val cropTrY: Float = 0f,
    val cropBrX: Float = 1f,
    val cropBrY: Float = 1f,
    val cropBlX: Float = 0f,
    val cropBlY: Float = 1f,
    val filterMode: String = ScanFilter.MAGIC_COLOR.name,
    val width: Int = 0,
    val height: Int = 0,
    val syncState: String = "LOCAL_DIRTY",
    val deletedAt: Long? = null,
    val eraserStrokesJson: String = "[]"
) {
    val cropQuad: NormalizedQuad
        get() = NormalizedQuad(
            cropTlX, cropTlY,
            cropTrX, cropTrY,
            cropBrX, cropBrY,
            cropBlX, cropBlY
        )

    val scanFilter: ScanFilter
        get() = ScanFilter.fromStoredName(filterMode)

    val eraserStrokes: List<EraserStrokeRecord>
        get() = decodeEraserStrokes(eraserStrokesJson)
}

fun stripImageExtension(filename: String): String {
    return filename.trim().replace(Regex("\\.(jpe?g|png|webp)$", RegexOption.IGNORE_CASE), "")
}

/**
 * Stores a normalized Smart Eraser stroke so strokes can be undone/redone and replayed at full
 * 4096px export resolution or across filter/rotation adjustments.
 */
data class EraserStrokeRecord(
    val normalizedPoints: List<Pair<Float, Float>>,
    val normalizedRadius: Float
) {
    fun rotateClockwise90(): EraserStrokeRecord {
        return EraserStrokeRecord(
            normalizedPoints = normalizedPoints.map { (x, y) -> (1f - y).coerceIn(0f, 1f) to x.coerceIn(0f, 1f) },
            normalizedRadius = normalizedRadius
        )
    }
}

fun encodeEraserStrokes(strokes: List<EraserStrokeRecord>): String {
    if (strokes.isEmpty()) return "[]"
    val arr = org.json.JSONArray()
    for (stroke in strokes) {
        if (stroke.normalizedPoints.isEmpty()) continue
        val obj = org.json.JSONObject()
        obj.put("r", stroke.normalizedRadius.coerceIn(0.004f, 0.12f).toDouble())
        val ptsArr = org.json.JSONArray()
        for ((x, y) in stroke.normalizedPoints) {
            val ptArr = org.json.JSONArray()
            ptArr.put(x.coerceIn(0f, 1f).toDouble())
            ptArr.put(y.coerceIn(0f, 1f).toDouble())
            ptsArr.put(ptArr)
        }
        obj.put("pts", ptsArr)
        arr.put(obj)
    }
    return arr.toString()
}

fun decodeEraserStrokes(rawJson: String): List<EraserStrokeRecord> {
    if (rawJson.isBlank() || rawJson.trim() == "[]") return emptyList()
    return runCatching {
        val arr = org.json.JSONArray(rawJson)
        val result = ArrayList<EraserStrokeRecord>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val radius = obj.optDouble("r", 0.022).toFloat().coerceIn(0.004f, 0.12f)
            val ptsArr = obj.optJSONArray("pts") ?: continue
            val pts = ArrayList<Pair<Float, Float>>(ptsArr.length())
            for (j in 0 until ptsArr.length()) {
                val ptArr = ptsArr.optJSONArray(j) ?: continue
                if (ptArr.length() >= 2) {
                    val x = ptArr.optDouble(0, Double.NaN).toFloat()
                    val y = ptArr.optDouble(1, Double.NaN).toFloat()
                    if (x.isFinite() && y.isFinite()) {
                        pts.add(x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f))
                    }
                }
            }
            if (pts.isNotEmpty()) {
                result.add(EraserStrokeRecord(normalizedPoints = pts, normalizedRadius = radius))
            }
        }
        result
    }.getOrDefault(emptyList())
}

/**
 * Represents a single captured or imported sheet inside the temporary capture batch.
 */
data class DraftScanItem(
    val id: String,
    val filename: String,
    val originalPath: String,
    val capturedAt: Long,
    val cropQuad: NormalizedQuad = NormalizedQuad.FULL_FRAME,
    val detectedQuad: NormalizedQuad = NormalizedQuad.FULL_FRAME,
    val rotationDegrees: Int = 0,
    val filter: ScanFilter = ScanFilter.MAGIC_COLOR,
    val filterIntensity: Float = 0.5f,
    val eraserStrokes: List<EraserStrokeRecord> = emptyList(),
    val redoEraserStrokes: List<EraserStrokeRecord> = emptyList(),
    val isProcessing: Boolean = false,
    val previewPath: String? = null,
    val previewBitmap: Bitmap? = null
)

data class FolderDeleteSummary(
    val folder: FolderEntity,
    val subfolderCount: Int,
    val totalImageCount: Int
)

data class MultiFolderDeleteSummary(
    val folders: List<FolderEntity>,
    val subfolderCount: Int,
    val totalImageCount: Int
)

data class VerificationCheckResult(
    val stepNumber: Int,
    val title: String,
    val passed: Boolean,
    val requiresPhysicalPhoneNote: String? = null,
    val details: String
)

/**
 * Bilingual UI strings for English and Bengali.
 * Brand title is always "কাগজপত্র" in Bangla only.
 */
class AppStrings(val lang: AppLanguage) {
    val isBn = lang == AppLanguage.BN

    val appBrandTitle = "কাগজপত্র"
    val home = if (isBn) "হোম" else "Home"
    val settingsTitle = if (isBn) "সেটিংস" else "Settings"
    val newFolder = if (isBn) "নতুন ফোল্ডার" else "New folder"
    val searchFolders = if (isBn) "Search" else "Search"
    val searchSubfolders = if (isBn) "Search" else "Search"
    val clearSearch = if (isBn) "অনুসন্ধান মুছুন" else "Clear search"
    val clear = if (isBn) "বাদ দিন" else "Clear"
    val selectAllShort = if (isBn) "সব" else "All"
    val undo = if (isBn) "ফিরিয়ে আনুন" else "Undo"
    val scanOrImport = if (isBn) "স্ক্যান বা ইমপোর্ট" else "Scan or import"
    val scanImages = if (isBn) "ছবি স্ক্যান করুন" else "Scan image(s)"
    val importFromDevice = if (isBn) "গ্যালারি / ডিভাইস থেকে নিন" else "Import from gallery / device"
    val emptyLibraryTitle = if (isBn) "আপনার কাগজপত্র লাইব্রেরি খালি" else "Your document library is empty"
    val emptyLibrarySubtitle = if (isBn) "দ্রুত স্ক্যান শুরু করতে নিচের ক্যামেরা বোতামে ট্যাপ করুন অথবা উপরে নতুন ফোল্ডার তৈরি করুন।" else "Tap the camera button below for Quick Scan, or create a New folder above."
    val emptyFolderTitle = if (isBn) "এই ফোল্ডারটি খালি" else "The folder is empty"
    val emptyFolderSubtitle = if (isBn) "এখানে সরাসরি ডকুমেন্ট যোগ করতে নিচের স্ক্যান বা ইমপোর্ট বোতামে ট্যাপ করুন।" else "Tap Scan or import below to capture or add sheets directly into this folder."
    val noSearchMatches = if (isBn) "কোনো ফোল্ডার বা ছবি পাওয়া যায়নি" else "No matching folders or images found"
    val gridView = if (isBn) "গ্রিড ভিউ" else "Grid view"
    val listView = if (isBn) "তালিকা ভিউ" else "List view"
    val sortByName = if (isBn) "Name (নাম)" else "Name"
    val sortByModified = if (isBn) "Last modified (পরিবর্তনের তারিখ)" else "Last modified"
    val sortByCreated = if (isBn) "Last created (তৈরির তারিখ)" else "Last created"
    val sortByCustom = if (isBn) "Custom (কাস্টম ক্রম)" else "Custom"
    val ascendingOrder = if (isBn) "Ascending order (ঊর্ধ্বক্রম)" else "Ascending order"
    val descendingOrder = if (isBn) "Descending order (অধঃক্রম)" else "Descending order"
    val imagesSortSection = if (isBn) "ছবির ক্রম" else "Images"
    val newestFirst = if (isBn) "নতুন আগে" else "Newest first"
    val oldestFirst = if (isBn) "পুরোনো আগে" else "Oldest first"
    val foldersSection = if (isBn) "সাবফোল্ডার" else "Subfolders"
    val searchFoldersSection = if (isBn) "ফোল্ডার" else "Folders"
    val imagesSection = if (isBn) "ডকুমেন্টসমূহ" else "Documents"
    val rename = if (isBn) "নাম পরিবর্তন" else "Rename"
    val delete = if (isBn) "মুছে ফেলুন" else "Delete"
    val cancel = if (isBn) "বাতিল" else "Cancel"
    val create = if (isBn) "তৈরি করুন" else "Create"
    val save = if (isBn) "সংরক্ষণ" else "Save"
    val move = if (isBn) "সরান" else "Move"
    val saveShort = if (isBn) "সংরক্ষণ" else "Save"
    val folderNameLabel = if (isBn) "ফোল্ডারের নাম" else "Folder name"
    val folderNamePlaceholder = if (isBn) "যেমন: Projects বা রশিদ" else "e.g. Projects or রশিদ"
    val errorEmptyFolderName = if (isBn) "ফোল্ডারের নাম খালি রাখা যাবে না।" else "Folder name cannot be empty."
    val errorDuplicateFolderName = if (isBn) "এই স্থানে একই নামের একটি ফোল্ডার ইতিমধ্যে রয়েছে।" else "A folder with this name already exists in this location."
    val errorMaxDepth = if (isBn) "সাবফোল্ডারের ভেতর আরেকটি ফোল্ডার তৈরি করা যাবে না।" else "Subfolders cannot contain another folder level."
    val renameImage = if (isBn) "ছবির নাম পরিবর্তন" else "Rename image"
    val imageNameLabel = if (isBn) "ছবির নাম" else "Image name"
    val imageNamePlaceholder = if (isBn) "যেমন: Project_311026_0231" else "e.g. Project_311026_0231"
    val errorEmptyImageName = if (isBn) "ছবির নাম খালি রাখা যাবে না।" else "Image name cannot be empty."
    val errorDuplicateImageName = if (isBn) "এই ফোল্ডারে একই নামের একটি ছবি ইতিমধ্যে রয়েছে।" else "An image with this name already exists in this folder."
    val retake = if (isBn) "পুনরায় তুলুন" else "Retake"
    val rotate = if (isBn) "ঘোরান" else "Rotate"
    val cropOriginal = "Crop"
    val filters = if (isBn) "ফিল্টার" else "Filters"
    val smartEraser = if (isBn) "ইরেজার" else "Eraser"
    val applyToAllShort = if (isBn) "সবগুলোতে" else "Apply to all"
    val filterAppliedToAllBubble = "Filter applied to all pages"
    val filterNotAppliedToAllBubble = "Filter not applied to all pages"
    val saveAs = "Save image(s)"
    val addMore = if (isBn) "যোগ করুন" else "Add"
    val addMoreImagesTitle = if (isBn) "আরও ছবি যোগ করুন" else "Add more images"
    val saveToFolder = "Save to folder"
    val moveToFolder = "Move to folder"
    val saveHere = "Save here"
    val moveHere = "Move here"
    val makePdf = "Make PDF"
    val share = "Share"
    val keepScanning = "Keep scanning"
    val discardBatch = "Discard batch"
    val unsavedCapturesTitle = if (isBn) "অসংরক্ষিত স্ক্যান বাতিল করবেন?" else "Discard unsaved captures?"
    val unsavedCapturesMessage = if (isBn) "আপনার বর্তমান ব্যাচের স্ক্যানগুলো এখনো সংরক্ষণ করা হয়নি।" else "Your captured sheets in this batch have not been saved yet."
    val applyCrop = if (isBn) "Apply crop" else "Apply crop"
    val resetFullFrame = if (isBn) "সম্পূর্ণ ফ্রেম" else "Full frame"
    val autoDetectEdges = if (isBn) "অটো প্রান্ত" else "Auto edges"
    val viewAction = if (isBn) "দেখুন" else "View"
    val downloadFallback = if (isBn) "গ্যালারিতে সংরক্ষণ" else "Save to Gallery"
    val savedToGalleryTitle = if (isBn) "গ্যালারিতে সংরক্ষিত হয়েছে!" else "Saved to Gallery!"
    val savedToGalleryMessage = if (isBn) "এই ছবিটি আপনার ফোনের গ্যালারিতে (Pictures/Kagojpotro) সংরক্ষণ করা হয়েছে।" else "This photo has been saved to your Gallery (Pictures/Kagojpotro)."
    val cameraPermissionDeniedTitle = if (isBn) "ক্যামেরা অনুমতি প্রয়োজন বা অনুপলব্ধ" else "Camera access unavailable or denied"
    val cameraPermissionDeniedBody = if (isBn) "সরাসরি স্ক্যান করতে ক্যামেরা অনুমতি দিন, অথবা আপনার ডিভাইস থেকে ছবি ইমপোর্ট করুন।" else "Grant camera permission to scan sheets live, or import existing photos directly from your device."
    val processingBadge = if (isBn) "প্রসেসিং..." else "Processing..."
    val selectFolderPrompt = if (isBn) "নিচে একটি ফোল্ডার নির্বাচন করুন বা নতুন ফোল্ডার তৈরি করুন" else "Open a folder or subfolder below to enable confirmation"
    val sourceLocationPrefix = if (isBn) "বর্তমান স্থান: " else "Current location: "
    val pdfScreenTitle = if (isBn) "পিডিএফ পৃষ্ঠা ক্রম ও এক্সপোর্ট" else "PDF Page Order & Export"
    val pdfFilenameLabel = if (isBn) "পিডিএফ ফাইলের নাম" else "PDF Filename"
    val pdfFitToImage = if (isBn) "ছবির মাপ অনুযায়ী (Fit)" else "Fit to Image"
    val pdfStandardA4 = if (isBn) "স্ট্যান্ডার্ড A4" else "Standard A4"
    val exportAndSharePdf = if (isBn) "পিডিএফ তৈরি ও শেয়ার করুন" else "Export & Share PDF"
    val downloadPdf = if (isBn) "পিডিএফ ডাউনলোড করুন" else "Download PDF"
    val verificationTitle = if (isBn) "বিল্ড যাচাইকরণ রিপোর্ট (৮টি পরীক্ষা)" else "Build Verification Suite (8 Checks)"
    val cameraToggleGridDesc = if (isBn) "গ্রিড লাইন চালু/বন্ধ" else "Toggle alignment grid"
    val cameraToggleFlashDesc = if (isBn) "ফ্ল্যাশলাইট চালু/বন্ধ" else "Toggle flashlight"
    val importSampleSheetLabel = if (isBn) "নমুনা ডকুমেন্ট শীট ইমপোর্ট করুন" else "Import Sample Document Sheet"
    val requestCameraPermissionLabel = if (isBn) "ক্যামেরা অনুমতি দিন" else "Request Camera Permission"
    val cameraLevelZeroLabel = if (isBn) "সমতল · ০°" else "Level · 0°"
    val cameraLockedBadgeSuffix = if (isBn) " · লকড" else " · Locked"
    val cameraSearchingBadgeSuffix = if (isBn) " · খুঁজছে" else " · Searching"

    fun cameraTiltLabel(deg: Int): String {
        return if (isBn) "কাত · $deg°" else "Tilt · $deg°"
    }

    fun importUnreadableImagesError(failedCount: Int, totalCount: Int): String {
        return if (failedCount >= totalCount) {
            if (isBn) "নির্বাচিত ছবিটি পড়া যায়নি বা নষ্ট হয়েছে" else "Could not read the selected image file(s)"
        } else {
            if (isBn) "$failedCount টি নষ্ট ছবি বাদ দেওয়া হয়েছে" else "Skipped $failedCount unreadable image file(s)"
        }
    }

    fun pinWrongAttemptsRemaining(remaining: Int): String {
        return if (isBn) {
            "ভুল পিন — আর $remaining বার চেষ্টা বাকি"
        } else {
            "Incorrect PIN ($remaining attempt${if (remaining == 1) "" else "s"} left)"
        }
    }

    fun pinLockedOutSeconds(seconds: Int): String {
        return if (isBn) {
            "অনেকবার ভুল পিন — ${seconds} সেকেন্ড পরে চেষ্টা করুন"
        } else {
            "Too many attempts — try again in ${seconds}s"
        }
    }

    fun searchInFolderPlaceholder(folderPath: String?): String {
        return "Search"
    }

    fun imagesCountSummary(count: Int): String {
        return if (isBn) "$count টি ছবি" else "$count image${if (count == 1) "" else "s"}"
    }

    fun selectedCountSummary(count: Int): String {
        return if (isBn) "${count}টি নির্বাচিত" else "$count selected"
    }

    fun movedToast(count: Int, destinationPath: String): String {
        return if (isBn) {
            "$count টি ছবি $destinationPath-এ সরানো হয়েছে"
        } else {
            "$count image${if (count == 1) "" else "s"} moved to $destinationPath"
        }
    }

    fun dragInstructionSingle(isCustomSort: Boolean): String {
        return if (isCustomSort) {
            if (isBn) {
                "নাম বদলাতে বা মুছতে নিচে টানুন, সাজাতে ফোল্ডারের ওপর রাখুন, অথবা ছেড়ে ট্যাপ করুন"
            } else {
                "Drag to Rename/Delete, over folders to reorder, or release to tap"
            }
        } else {
            if (isBn) {
                "নিচে টানুন বা ছেড়ে ট্যাপ করুন (সাজাতে Sort থেকে Custom নিন)"
            } else {
                "Drag to Rename/Delete or release to tap (Custom sort reorders)"
            }
        }
    }

    fun dragInstructionMulti(selectedCount: Int): String {
        return if (isBn) {
            "$selectedCount টি ফোল্ডার নির্বাচিত — মুছতে নিচে Delete-এ টানুন বা ছেড়ে ট্যাপ করুন"
        } else {
            "$selectedCount folders selected — drag to Delete or release to tap"
        }
    }

    fun folderStickyInstruction(selectedCount: Int): String {
        return if (isBn) {
            "${selectedCount}টি নির্বাচিত — আরও ফোল্ডারে ট্যাপ করুন বা নিচের অপশন চাপুন"
        } else {
            "$selectedCount selected — tap folders or press an action below"
        }
    }

    fun imageDragInstruction(selectedCount: Int, isSticky: Boolean): String {
        return if (isSticky) {
            if (isBn) {
                "${selectedCount}টি নির্বাচিত — আরও ছবিতে ট্যাপ করুন বা নিচের অপশন চাপুন"
            } else {
                "$selectedCount selected — tap images or press an action below"
            }
        } else if (selectedCount >= 2) {
            if (isBn) {
                "${selectedCount}টি ছবি স্ট্যাক হয়েছে — নিচের অপশনে রাখুন বা ছেড়ে ট্যাপ করুন"
            } else {
                "$selectedCount stacked — drag onto an action below or release to tap"
            }
        } else {
            if (isBn) {
                "নিচের অপশনে টানুন, অথবা ছেড়ে ট্যাপ করুন"
            } else {
                "Drag onto an action below, or release to tap options"
            }
        }
    }
}
