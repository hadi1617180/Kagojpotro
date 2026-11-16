package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppStorageSyncMode
import com.example.data.BulkNumberPadding
import com.example.data.BulkRenameConfig
import com.example.data.BulkRenameMode
import com.example.data.BulkRenameOrder
import com.example.data.BulkRenamePlanner
import com.example.data.BulkRenameResult
import com.example.data.EraserStrokeRecord
import com.example.data.FolderOperationResult
import com.example.data.KagojpotroDatabase
import com.example.data.KagojpotroRepository
import com.example.data.NormalizedQuad
import com.example.data.PcSyncConfig
import com.example.data.PdfPageFormat
import com.example.data.PinVerifyResult
import com.example.data.ScanFilter
import com.example.processing.DocumentImageProcessor
import com.example.ui.KagojpotroViewModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private lateinit var context: Context
    private lateinit var database: KagojpotroDatabase
    private lateinit var repository: KagojpotroRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KagojpotroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = KagojpotroRepository(context, database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun verifyAllEightEndToEndWorkflows() = runBlocking {
        // 0. Start with an empty library
        assertEquals(0, database.folderDao().getAllFoldersOnce().size)
        assertEquals(0, database.scanDao().getAllScansOnce().size)

        // 1. First run from Home: scan 2 sheets, review once, create Projects -> create Site Visit -> Save here
        val raw1 = DocumentImageProcessor.generateSampleCapturedSheet(1, 600, 800)
        val raw2 = DocumentImageProcessor.generateSampleCapturedSheet(2, 600, 800)
        val draft1 = repository.processDraftItem(
            repository.createDraftFromBitmap(raw1, capturedAt = 1700000001000L),
            detectEdges = true
        )
        val draft2 = repository.processDraftItem(
            repository.createDraftFromBitmap(raw2, capturedAt = 1700000002000L),
            detectEdges = true
        )

        val projectsRes = repository.createFolder("Projects", parentId = null, now = 1700000003000L)
        assertTrue(projectsRes is FolderOperationResult.Success)
        val projectsFolder = (projectsRes as FolderOperationResult.Success).folder

        val siteVisitRes = repository.createFolder("Site Visit", parentId = projectsFolder.id, now = 1700000004000L)
        assertTrue(siteVisitRes is FolderOperationResult.Success)
        val siteVisitFolder = (siteVisitRes as FolderOperationResult.Success).folder

        val savedHomeBatch = repository.saveDraftBatchToFolder(
            listOf(draft1, draft2),
            targetFolderId = siteVisitFolder.id,
            now = 1700000005000L
        ).getOrThrow()
        assertEquals(2, savedHomeBatch.size)
        val siteVisitScansAfterStep1 = database.scanDao().getScansInFolderOnce(siteVisitFolder.id)
        assertEquals(2, siteVisitScansAfterStep1.size)

        // 2. Save in a parent folder: Start on Home, import an image, and save directly into Projects even though it contains a subfolder
        val rawParentImport = DocumentImageProcessor.generateSampleCapturedSheet(3, 600, 800)
        val draftParentImport = repository.processDraftItem(
            repository.createDraftFromBitmap(rawParentImport, capturedAt = 1700000010000L),
            detectEdges = true
        )
        val savedInProjects = repository.saveDraftBatchToFolder(
            listOf(draftParentImport),
            targetFolderId = projectsFolder.id,
            now = 1700000011000L
        ).getOrThrow()
        assertEquals(1, savedInProjects.size)
        assertEquals(projectsFolder.id, savedInProjects[0].folderId)
        assertEquals(1, database.scanDao().getScansInFolderOnce(projectsFolder.id).size)

        // 3. Scan inside a folder: Open Site Visit, capture 3 sheets, crop the second, and save directly into Site Visit
        val fSheet1 = repository.processDraftItem(
            repository.createDraftFromBitmap(DocumentImageProcessor.generateSampleCapturedSheet(4, 600, 800), 1700000020000L),
            detectEdges = true
        )
        val tightCrop = NormalizedQuad(0.20f, 0.20f, 0.80f, 0.20f, 0.80f, 0.80f, 0.20f, 0.80f)
        val fSheet2 = repository.processDraftItem(
            repository.createDraftFromBitmap(DocumentImageProcessor.generateSampleCapturedSheet(5, 600, 800), 1700000021000L)
                .copy(cropQuad = tightCrop),
            detectEdges = false
        )
        val fSheet3 = repository.processDraftItem(
            repository.createDraftFromBitmap(DocumentImageProcessor.generateSampleCapturedSheet(6, 760, 560), 1700000022000L),
            detectEdges = true
        )
        val savedInSiteVisit = repository.saveDraftBatchToFolder(
            listOf(fSheet1, fSheet2, fSheet3),
            targetFolderId = siteVisitFolder.id,
            now = 1700000025000L
        ).getOrThrow()
        assertEquals(3, savedInSiteVisit.size)
        assertEquals(5, database.scanDao().getScansInFolderOnce(siteVisitFolder.id).size)

        val secondCroppedScan = savedInSiteVisit[1]
        assertEquals(0.20f, secondCroppedScan.cropTlX, 0.01f)
        val croppedWidthBeforeRecovery = secondCroppedScan.width

        // 4. Recover the original: Reopen that second image and expand its crop to include a previously hidden edge
        val expandedCrop = NormalizedQuad(0.02f, 0.02f, 0.98f, 0.02f, 0.98f, 0.98f, 0.02f, 0.98f)
        val recoveredScan = repository.updateSavedScanEdits(
            scanId = secondCroppedScan.id,
            newQuad = expandedCrop,
            newRotationDegrees = secondCroppedScan.rotationDegrees,
            newFilter = secondCroppedScan.scanFilter,
            now = 1700000030000L
        ).getOrThrow()

        assertTrue(File(recoveredScan.originalPath).exists())
        assertEquals(0.02f, recoveredScan.cropTlX, 0.01f)
        assertTrue(
            "Expanded crop width (${recoveredScan.width}) should exceed tight crop width ($croppedWidthBeforeRecovery)",
            recoveredScan.width > croppedWidthBeforeRecovery
        )

        // 5. Move selected images: Select two edited images in Site Visit and move them into Projects
        val moveIds = listOf(savedInSiteVisit[0].id, recoveredScan.id)
        val movedCount = repository.moveScansToFolder(
            scanIds = moveIds,
            targetFolderId = projectsFolder.id,
            now = 1700000040000L
        ).getOrThrow()
        assertEquals(2, movedCount)

        val remainingInSiteVisit = database.scanDao().getScansInFolderOnce(siteVisitFolder.id)
        val nowInProjects = database.scanDao().getScansInFolderOnce(projectsFolder.id)
        assertEquals(3, remainingInSiteVisit.size)
        assertEquals(3, nowInProjects.size)

        val movedSecondInProjects = nowInProjects.find { it.id == recoveredScan.id }
        assertNotNull(movedSecondInProjects)
        // Verify original capture date and edits are preserved after move
        assertEquals(1700000021000L, movedSecondInProjects!!.capturedAt)
        assertEquals(0.02f, movedSecondInProjects.cropTlX, 0.01f)
        assertEquals(recoveredScan.originalPath, movedSecondInProjects.originalPath)

        // 6. Duplicate name validation & Bengali folder name support
        val duplicateAttempt = repository.createFolder("  site visit ", parentId = projectsFolder.id)
        assertTrue(duplicateAttempt is FolderOperationResult.DuplicateName)

        val bengaliFolderRes = repository.createFolder("রশিদ ও দলিল", parentId = null)
        assertTrue(bengaliFolderRes is FolderOperationResult.Success)

        // 7. PDF order: Select 3 images, arrange them as 3 -> 1 -> 2, export PDF, verify page order & orientation
        val img1 = savedHomeBatch[0] // Portrait
        val img2 = savedHomeBatch[1] // Portrait
        val img3 = savedInSiteVisit[2] // Landscape (760 x 560)
        val ordered312 = listOf(img3, img1, img2)

        val pdfFile = repository.exportSelectedScansToPdf(
            orderedScans = ordered312,
            rawPdfName = "Site_Visit_3_1_2",
            pageFormat = PdfPageFormat.FIT_TO_IMAGE
        ).getOrThrow()
        assertTrue(pdfFile.exists())
        assertTrue(pdfFile.length() > 1000L)

        // Inspect actual exported PDF page count, order (3 -> 1 -> 2), and orientation (/MediaBox [0 0 W H])
        val pdfHeaderAndMeta = String(pdfFile.readBytes(), Charsets.ISO_8859_1)
        assertTrue(pdfHeaderAndMeta.startsWith("%PDF-1.4"))
        assertTrue(pdfHeaderAndMeta.contains("/Count 3"))

        val mediaBoxes = Regex("/MediaBox \\[0 0 (\\d+) (\\d+)\\]")
            .findAll(pdfHeaderAndMeta)
            .map { match ->
                val w = match.groupValues[1].toInt()
                val h = match.groupValues[2].toInt()
                w to h
            }
            .toList()
        assertEquals(3, mediaBoxes.size)
        // Page 1 corresponds to img3 (Landscape: width > height)
        assertTrue("Page 1 (img 3) should be landscape: ${mediaBoxes[0]}", mediaBoxes[0].first > mediaBoxes[0].second)
        // Page 2 corresponds to img1 (Portrait: height > width)
        assertTrue("Page 2 (img 1) should be portrait: ${mediaBoxes[1]}", mediaBoxes[1].second > mediaBoxes[1].first)
        // Page 3 corresponds to img2 (Portrait: height > width)
        assertTrue("Page 3 (img 2) should be portrait: ${mediaBoxes[2]}", mediaBoxes[2].second > mediaBoxes[2].first)

        // 8. Single-level subfolder limit: Verify sub-subfolder creation inside a subfolder is blocked
        val nestedAttempt = repository.createFolder("Deep Level 3", parentId = siteVisitFolder.id)
        assertTrue(nestedAttempt is FolderOperationResult.MaxDepthExceeded)

        // Verify custom folder sorting persistence
        val customA = (repository.createFolder("Alpha", parentId = null) as FolderOperationResult.Success).folder
        val customB = (repository.createFolder("Beta", parentId = null) as FolderOperationResult.Success).folder
        repository.updateCustomFolderOrder(listOf(customB.id, customA.id, projectsFolder.id))
        val updatedFolders = database.folderDao().getAllFoldersOnce().associateBy { it.id }
        assertEquals(0, updatedFolders[customB.id]?.customOrder)
        assertEquals(1, updatedFolders[customA.id]?.customOrder)
        assertEquals(2, updatedFolders[projectsFolder.id]?.customOrder)

        // Also run the built-in 8-check verification runner and confirm all 8 checks pass
        val suiteResults = com.example.ui.KagojpotroViewModel.executeVerificationSuite(repository)
        assertEquals(8, suiteResults.size)
        suiteResults.forEach { check ->
            assertTrue("Check ${check.stepNumber} (${check.title}) failed: ${check.details}", check.passed)
        }

        // Verify Bangla-only brand title in both EN and BN modes
        assertEquals("কাগজপত্র", com.example.data.AppStrings(com.example.data.AppLanguage.EN).appBrandTitle)
        assertEquals("কাগজপত্র", com.example.data.AppStrings(com.example.data.AppLanguage.BN).appBrandTitle)

        // Verify unified "Search" placeholder across Home, folder, subfolder, and deep subfolder
        val enStrings = com.example.data.AppStrings(com.example.data.AppLanguage.EN)
        assertEquals("Search", enStrings.searchInFolderPlaceholder(null))
        assertEquals("Search", enStrings.searchInFolderPlaceholder("Projects"))
        assertEquals("Search", enStrings.searchInFolderPlaceholder("Projects/Site Visit"))
        assertEquals("Search", enStrings.searchInFolderPlaceholder("Projects/Site Visit/Floor 2"))

        // Verify 3D perspective aspect-ratio recovery on a tilted portrait sheet (like Screenshot 1 -> 2)
        val tiltedPortraitQuad = NormalizedQuad(
            tlX = 0.24f, tlY = 0.27f,
            trX = 0.79f, trY = 0.26f,
            brX = 0.84f, brY = 0.64f,
            blX = 0.14f, blY = 0.64f
        )
        val sourceFrame = android.graphics.Bitmap.createBitmap(738, 1600, android.graphics.Bitmap.Config.ARGB_8888)
        val rectified = DocumentImageProcessor.warpPerspective(sourceFrame, tiltedPortraitQuad, maxDimension = 1000)
        val rectifiedAspect = rectified.height.toFloat() / rectified.width.toFloat()
        assertTrue(
            "Tilted portrait sheet should recover true portrait aspect ratio (> 1.30), got $rectifiedAspect (${rectified.width}x${rectified.height})",
            rectifiedAspect > 1.30f
        )

        // Verify updated UI labels ("Crop" and "Save image(s)") and all 6 filters with Enhanced directly after Magic Color
        assertEquals("Crop", enStrings.cropOriginal)
        assertEquals("Save image(s)", enStrings.saveAs)

        val filterNames = com.example.data.ScanFilter.entries.map { it.labelEn }
        assertEquals(
            listOf("Original", "Magic Color", "AI Enhance", "Whiteboard", "Clear B&W", "Grayscale"),
            filterNames
        )
        assertEquals(
            com.example.data.ScanFilter.AI_ENHANCE,
            com.example.data.ScanFilter.fromStoredName("AI_ENHANCE")
        )
        // Optional ai_enhance.onnx asset (>30 MB) is not required; query should never throw and offline enhancer runs below
        val hasOptionalOnnx = DocumentImageProcessor.isAiEnhanceReady()
        assertTrue(hasOptionalOnnx || !hasOptionalOnnx)

        val sampleFilterInput = DocumentImageProcessor.generateSampleCapturedSheet(1, 320, 420)
        for (filter in com.example.data.ScanFilter.entries) {
            val out = DocumentImageProcessor.applyFilter(sampleFilterInput, filter)
            assertEquals(320, out.width)
            assertEquals(420, out.height)
            if (filter == com.example.data.ScanFilter.WHITEBOARD) {
                // Verify Whiteboard filter produces grayscale/monochrome black-on-pure-white output
                val px = IntArray(out.width * out.height)
                out.getPixels(px, 0, out.width, 0, 0, out.width, out.height)
                val pureWhiteCount = px.count { (it and 0x00FFFFFF) == 0x00FFFFFF }
                val darkInkCount = px.count { (it and 0xFF) < 64 }
                assertTrue("Whiteboard filter should produce pure white background", pureWhiteCount > px.size / 2)
                assertTrue("Whiteboard filter should preserve black lines/text", darkInkCount > 100)
            }
        }
    }

    @Test
    fun verifyMultiFolderDeleteSummaryAndBatchCascade() = runBlocking {
        val f1 = (repository.createFolder("Tax 2025", parentId = null, now = 1700001000000L) as FolderOperationResult.Success).folder
        val f1Sub = (repository.createFolder("Receipts", parentId = f1.id, now = 1700001001000L) as FolderOperationResult.Success).folder
        val f2 = (repository.createFolder("Bills", parentId = null, now = 1700001002000L) as FolderOperationResult.Success).folder
        val fKeep = (repository.createFolder("Keep Me", parentId = null, now = 1700001003000L) as FolderOperationResult.Success).folder

        val d1 = repository.processDraftItem(
            repository.createDraftFromBitmap(DocumentImageProcessor.generateSampleCapturedSheet(1, 400, 600), 1700001010000L),
            detectEdges = false
        )
        val d2 = repository.processDraftItem(
            repository.createDraftFromBitmap(DocumentImageProcessor.generateSampleCapturedSheet(2, 400, 600), 1700001011000L),
            detectEdges = false
        )
        val d3 = repository.processDraftItem(
            repository.createDraftFromBitmap(DocumentImageProcessor.generateSampleCapturedSheet(3, 400, 600), 1700001012000L),
            detectEdges = false
        )
        repository.saveDraftBatchToFolder(listOf(d1), targetFolderId = f1Sub.id).getOrThrow()
        repository.saveDraftBatchToFolder(listOf(d2), targetFolderId = f2.id).getOrThrow()
        repository.saveDraftBatchToFolder(listOf(d3), targetFolderId = fKeep.id).getOrThrow()

        val summary = repository.getFoldersDeleteSummary(listOf(f1.id, f2.id))
        assertNotNull(summary)
        assertEquals(2, summary!!.folders.size)
        assertEquals(1, summary.subfolderCount)
        assertEquals(2, summary.totalImageCount)

        val deleted = repository.deleteFoldersAndContents(listOf(f1.id, f2.id))
        assertTrue(deleted)

        val remainingFolders = database.folderDao().getAllFoldersOnce()
        val remainingScans = database.scanDao().getAllScansOnce()
        assertEquals(listOf(fKeep.id), remainingFolders.map { it.id })
        assertEquals(1, remainingScans.size)
        assertEquals(fKeep.id, remainingScans.first().folderId)
    }

    @Test
    fun verifyPcServerExportAndShortUrlNormalization() = runBlocking {
        val manager = com.example.data.PcSyncManager(
            database = database,
            originalsDir = File(context.filesDir, "test_orig"),
            editedDir = File(context.filesDir, "test_edit"),
            thumbsDir = File(context.filesDir, "test_thumb")
        )
        assertEquals("https://tinyurl.com/abc123", manager.normalizeServerBaseUrl("tinyurl.com/abc123/"))
        assertEquals("https://da.gd/xyz", manager.normalizeServerBaseUrl("da.gd/xyz"))
        assertEquals("https://clck.ru/9ab", manager.normalizeServerBaseUrl("clck.ru/9ab/"))
        assertEquals("https://spoo.me/test", manager.normalizeServerBaseUrl("spoo.me/test"))
        assertEquals("https://v.gd/short1", manager.normalizeServerBaseUrl("v.gd/short1"))
        assertEquals("https://is.gd/short2", manager.normalizeServerBaseUrl("is.gd/short2"))
        assertEquals("http://192.168.1.50:8085", manager.normalizeServerBaseUrl("192.168.1.50:8085/"))
        assertEquals(
            "https://foo-bar.trycloudflare.com",
            manager.normalizeServerBaseUrl("  https://foo-bar.\ntrycloudflare.com / \u200B ")
        )

        val batFile = repository.exportPcServerScriptFile()
        assertTrue(batFile.exists())
        val text = batFile.readText(Charsets.UTF_8)
        assertTrue(text.contains("# ===KAGOJPOTRO_PS_BODY_START==="))
        assertTrue(text.contains("WScript.Shell"))
        assertTrue(text.contains("\$BtnFolderMenu"))
        assertTrue(text.contains("\$BtnToggleServer"))
        assertTrue(text.contains("\$PicStatusDot"))
        assertTrue(!text.contains("\$TxtUrl"))
        assertTrue(!text.contains("\$BtnCopyUrl"))
        assertTrue(!text.contains("tinyurl.com/api-create.php"))
        assertTrue(text.contains("Stop Server"))
        assertTrue(text.contains("Start Server"))
        assertTrue(text.contains("LastClientActivityUtc"))
        assertTrue(text.contains("Red: Server Off"))
        assertTrue(text.contains("Yellow: Server Running (Waiting for Phone Sync)"))
        assertTrue(text.contains("Green: Server Running & Phone Synced"))
        assertTrue(text.contains("SimpleQrGenerator"))
        assertTrue(text.contains("Scan from phone to connect"))
        assertTrue(text.contains("Kagojpotro_PC_Server_SingleInstance"))
        assertTrue(text.contains("/api/disconnect"))
        assertTrue(text.contains("BuildAllFolderRelativePaths"))
    }

    @Test
    fun verifyCacheCleanupSafetyAndStorageAccounting() = runBlocking {
        val folder = (repository.createFolder("Vault", parentId = null, now = 1700002000000L) as FolderOperationResult.Success).folder
        val draft1 = repository.processDraftItem(
            repository.createDraftFromBitmap(DocumentImageProcessor.generateSampleCapturedSheet(1, 500, 700), 1700002001000L),
            detectEdges = false
        )
        val draft2 = repository.processDraftItem(
            repository.createDraftFromBitmap(DocumentImageProcessor.generateSampleCapturedSheet(2, 500, 700), 1700002002000L),
            detectEdges = false
        )
        val saved = repository.saveDraftBatchToFolder(listOf(draft1, draft2), targetFolderId = folder.id).getOrThrow()
        assertEquals(2, saved.size)

        // Mark only scan[0] as SYNCED; leave scan[1] as LOCAL_DIRTY (unsynced local scan)
        database.scanDao().markScansSynced(listOf(saved[0].id))

        val sharedDir = File(context.cacheDir, "shared_images").apply { mkdirs() }
        val dummyShare = File(sharedDir, "share_tmp.jpg").apply { writeBytes(ByteArray(2048) { 1 }) }

        // In PHONE_ONLY mode (no remote high-res backup), even SYNCED edited high-res must be preserved!
        val phoneOnlySummary = repository.getStorageUsageSummary()
        assertTrue(phoneOnlySummary.savedScansBytes > phoneOnlySummary.thumbnailsBytes)
        assertEquals(0L, phoneOnlySummary.tempHighResSyncedBytes)

        val freedPhoneOnly = repository.clearTemporaryCache(includeAllLocalHighResIfRequested = false)
        assertTrue(freedPhoneOnly >= dummyShare.length())
        assertTrue("Phone-only scan[0] edited file must be protected", File(saved[0].editedPath).exists())
        assertTrue("Unsynced scan[1] edited file must be preserved", File(saved[1].editedPath).exists())
        assertTrue("Unsynced scan original file must be preserved", File(saved[1].originalPath).exists())
        assertTrue("Synced scan thumbnail must remain viewable", File(saved[0].thumbnailPath).exists())
        assertTrue("Shared cache file should be cleaned", !dummyShare.exists())

        // In PC_SERVER_SYNC mode with configured URL, SYNCED edited duplicate IS counted & evicted
        repository.updatePcSyncConfig(
            PcSyncConfig(mode = AppStorageSyncMode.PC_SERVER_SYNC, serverUrl = "http://127.0.0.1:8085", userDirectory = "test")
        )
        val pcSummary = repository.getStorageUsageSummary()
        assertTrue(pcSummary.tempHighResSyncedBytes > 0L)
        assertTrue(pcSummary.cacheBytes >= pcSummary.tempHighResSyncedBytes)
        val freedPc = repository.clearTemporaryCache(includeAllLocalHighResIfRequested = false)
        assertTrue(freedPc > 0L)
        assertTrue("PC-backed SYNCED scan[0] edited cache should be evicted", !File(saved[0].editedPath).exists())
        assertTrue("Unsynced scan[1] edited file must still be preserved", File(saved[1].editedPath).exists())
    }

    @Test
    fun verifyFolderPrefixedNamingCollisionSuffixAndScanRename() = runBlocking {
        val folder = (repository.createFolder("Invoices", parentId = null, now = 1700003000000L) as FolderOperationResult.Success).folder
        val sameMinuteTimestamp = 1700003005123L
        val fmt = java.text.SimpleDateFormat("ddMMyy_HHmm", java.util.Locale.US)
        val expectedBase = "Invoices_${fmt.format(java.util.Date(sameMinuteTimestamp))}"

        val d1 = repository.processDraftItem(
            repository.createDraftFromBitmap(DocumentImageProcessor.generateSampleCapturedSheet(1, 400, 600), sameMinuteTimestamp, originFolderName = folder.name),
            detectEdges = false
        )
        val d2 = repository.processDraftItem(
            repository.createDraftFromBitmap(DocumentImageProcessor.generateSampleCapturedSheet(2, 400, 600), sameMinuteTimestamp + 50, originFolderName = folder.name),
            detectEdges = false
        )
        val d3 = repository.processDraftItem(
            repository.createDraftFromBitmap(DocumentImageProcessor.generateSampleCapturedSheet(3, 400, 600), sameMinuteTimestamp + 90, originFolderName = folder.name),
            detectEdges = false
        )

        val saved = repository.saveDraftBatchToFolder(listOf(d1, d2, d3), targetFolderId = folder.id).getOrThrow()
        assertEquals(3, saved.size)
        assertEquals("$expectedBase.jpg", saved[0].filename)
        assertEquals("${expectedBase}_1.jpg", saved[1].filename)
        assertEquals("${expectedBase}_2.jpg", saved[2].filename)

        assertEquals(expectedBase, com.example.data.stripImageExtension(saved[0].filename))
        assertEquals("${expectedBase}_1", com.example.data.stripImageExtension(saved[1].filename))

        // Verify nested subfolder saves with subfolder name only (not parent_subfolder)
        val parentFolder = (repository.createFolder("Projects", parentId = null, now = 1700003100000L) as FolderOperationResult.Success).folder
        val subFolder = (repository.createFolder("Site Visit", parentId = parentFolder.id, now = 1700003101000L) as FolderOperationResult.Success).folder
        val dSub = repository.processDraftItem(
            repository.createDraftFromBitmap(DocumentImageProcessor.generateSampleCapturedSheet(4, 400, 600), sameMinuteTimestamp, originFolderName = subFolder.name),
            detectEdges = false
        )
        val savedSub = repository.saveDraftBatchToFolder(listOf(dSub), targetFolderId = subFolder.id).getOrThrow()
        val expectedSubName = "Site_Visit_${fmt.format(java.util.Date(sameMinuteTimestamp))}.jpg"
        assertEquals(expectedSubName, savedSub[0].filename)

        // Reject empty and duplicate renames inside same folder
        val emptyRes = repository.renameScan(saved[0].id, "   ")
        assertTrue(emptyRes is com.example.data.ScanOperationResult.EmptyName)

        val dupRes = repository.renameScan(saved[0].id, "${expectedBase}_1")
        assertTrue(dupRes is com.example.data.ScanOperationResult.DuplicateName)

        // Successful rename updates stored filename with .jpg while UI display strips extension
        val okRes = repository.renameScan(saved[0].id, "Electricity Bill Oct")
        assertTrue(okRes is com.example.data.ScanOperationResult.Success)
        val renamedScan = (okRes as com.example.data.ScanOperationResult.Success).scan
        assertEquals("Electricity Bill Oct.jpg", renamedScan.filename)
        assertEquals("Electricity Bill Oct", com.example.data.stripImageExtension(renamedScan.filename))
        assertEquals("LOCAL_DIRTY", renamedScan.syncState)
    }

    @Test
    fun verifySevenDayTrashUndoRestoreParentAncestorsAndPurge() = runBlocking {
        val t0 = 1700004000000L
        val root = (repository.createFolder("Finance", parentId = null, now = t0) as FolderOperationResult.Success).folder
        val sub = (repository.createFolder("Bank", parentId = root.id, now = t0) as FolderOperationResult.Success).folder
        val d1 = repository.processDraftItem(
            repository.createDraftFromBitmap(DocumentImageProcessor.generateSampleCapturedSheet(1, 400, 600), t0),
            detectEdges = false
        )
        val saved = repository.saveDraftBatchToFolder(listOf(d1), targetFolderId = sub.id, now = t0).getOrThrow()
        val scan = saved.first()

        // Move folder tree to trash
        val trashBatch = repository.moveFoldersToTrash(listOf(root.id), now = t0)
        assertNotNull(trashBatch)
        assertEquals(0, database.folderDao().getAllFoldersOnce().size)
        assertEquals(0, database.scanDao().getAllScansOnce().size)
        assertEquals(2, database.folderDao().getTrashedFoldersOnce().size)
        assertEquals(1, database.scanDao().getTrashedScansOnce().size)
        assertTrue("Original file must still exist while in Trash", File(scan.originalPath).exists())

        // Restoring just the child scan automatically restores trashed parent ancestors so it's not orphaned
        val restoredItems = repository.restoreTrashedScan(scan.id, now = t0 + 1000L)
        assertTrue(restoredItems >= 3)
        assertEquals(2, database.folderDao().getAllFoldersOnce().size)
        assertEquals(1, database.scanDao().getAllScansOnce().size)

        // Undo via TrashBatchRecord
        val secondBatch = repository.moveScansToTrash(listOf(scan.id), now = t0 + 2000L)!!
        assertEquals(0, database.scanDao().getAllScansOnce().size)
        repository.restoreTrashBatch(secondBatch, now = t0 + 3000L)
        assertEquals(1, database.scanDao().getAllScansOnce().size)

        // Purge removes items strictly older than 7 days while preserving recent ones
        val expiredTime = t0
        val recentTime = t0 + (6L * 24L * 3600L * 1000L)
        val purgeCheckNow = expiredTime + com.example.data.TRASH_RETENTION_MILLIS + 60_000L

        val oldScanDraft = repository.processDraftItem(
            repository.createDraftFromBitmap(DocumentImageProcessor.generateSampleCapturedSheet(2, 400, 600), expiredTime),
            detectEdges = false
        )
        val recentScanDraft = repository.processDraftItem(
            repository.createDraftFromBitmap(DocumentImageProcessor.generateSampleCapturedSheet(3, 400, 600), recentTime),
            detectEdges = false
        )
        val extraSaved = repository.saveDraftBatchToFolder(listOf(oldScanDraft, recentScanDraft), targetFolderId = root.id, now = expiredTime).getOrThrow()
        repository.moveScansToTrash(listOf(extraSaved[0].id), now = expiredTime)
        repository.moveScansToTrash(listOf(extraSaved[1].id), now = recentTime)

        val purged = repository.purgeExpiredTrash(now = purgeCheckNow)
        assertEquals(1, purged)
        val remainingTrashed = database.scanDao().getTrashedScansOnce()
        assertEquals(listOf(extraSaved[1].id), remainingTrashed.map { it.id })
        assertTrue(!File(extraSaved[0].editedPath).exists())
        assertTrue(File(extraSaved[1].editedPath).exists())
    }

    @Test
    fun verifyAppPinHashAndVerification() = runBlocking {
        assertTrue(!repository.setAppLockPin("123")) // Must be 4 digits
        assertTrue(!repository.setAppLockPin("abcd"))
        assertTrue(repository.setAppLockPin("2580"))
        assertTrue(repository.verifyAppLockPin("2580"))
        assertTrue(!repository.verifyAppLockPin("0000"))
        assertTrue(!repository.verifyAppLockPin("2581"))
    }

    @Test
    fun verifyCascadingSubfolderTrashExpiryAndRestoreCollisionDisambiguation() = runBlocking {
        val t0 = 1700005000000L
        val parent = (repository.createFolder("Tax", parentId = null, now = t0) as FolderOperationResult.Success).folder
        val sub = (repository.createFolder("Receipts", parentId = parent.id, now = t0) as FolderOperationResult.Success).folder

        // Trash parent at t0, simulate sub having a slightly newer deletedAt timestamp, then purge at t0 + 7 days + 1 min
        repository.moveFoldersToTrash(listOf(parent.id), now = t0)
        val newerDeletedSub = database.folderDao().getAnyFolderById(sub.id)!!.copy(deletedAt = t0 + 500_000L)
        database.folderDao().updateFolder(newerDeletedSub)

        val purgedCount = repository.purgeExpiredTrash(now = t0 + com.example.data.TRASH_RETENTION_MILLIS + 60_000L)
        assertEquals(2, purgedCount)
        assertTrue(database.folderDao().getAllFoldersIncludingTrashedOnce().isEmpty())

        // Restore collision disambiguation: trash "Work" + scan "Bill", create active "Work" + "Bill", then restore from Trash
        val workOld = (repository.createFolder("Work", parentId = null, now = t0 + 1000L) as FolderOperationResult.Success).folder
        val dOld = repository.processDraftItem(
            repository.createDraftFromBitmap(DocumentImageProcessor.generateSampleCapturedSheet(1, 300, 400), t0 + 1000L, originFolderName = workOld.name),
            detectEdges = false
        )
        val savedOld = repository.saveDraftBatchToFolder(listOf(dOld), targetFolderId = workOld.id, now = t0 + 1000L).getOrThrow().first()
        repository.renameScan(savedOld.id, "Bill", now = t0 + 1100L)
        val trashBatch = repository.moveFoldersToTrash(listOf(workOld.id), now = t0 + 1200L)!!

        val workNew = (repository.createFolder("Work", parentId = null, now = t0 + 2000L) as FolderOperationResult.Success).folder
        assertEquals("Work", workNew.name)

        repository.restoreTrashBatch(trashBatch, now = t0 + 3000L)
        val activeFolders = database.folderDao().getAllFoldersOnce()
        val names = activeFolders.map { it.name }.toSet()
        assertEquals(2, activeFolders.size)
        assertTrue("Expected collision-disambiguated restored folder name", names.contains("Work (Restored)"))
    }

    @Test
    fun verifyMultiCueCornerDetectionOnTiltedShadowLightDeskAndInnerTables() {
        fun drawQuadScene(
            w: Int,
            h: Int,
            bgColor: Int,
            paperColor: Int,
            tl: Pair<Float, Float>,
            tr: Pair<Float, Float>,
            br: Pair<Float, Float>,
            bl: Pair<Float, Float>,
            addShadowRightHalf: Boolean = false,
            addInnerDarkTable: Boolean = false
        ): android.graphics.Bitmap {
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val pixels = IntArray(w * h)
            val poly = arrayOf(tl, tr, br, bl)

            fun isInsideQuad(u: Float, v: Float): Boolean {
                for (i in 0 until 4) {
                    val a = poly[i]
                    val b = poly[(i + 1) % 4]
                    val cross = (b.first - a.first) * (v - a.second) - (b.second - a.second) * (u - a.first)
                    if (cross < 0f) return false
                }
                return true
            }

            val tableInk = android.graphics.Color.rgb(25, 28, 30)
            for (y in 0 until h) {
                val v = y.toFloat() / h
                val rowOff = y * w
                for (x in 0 until w) {
                    val u = x.toFloat() / w
                    var c = if (isInsideQuad(u, v)) paperColor else bgColor
                    if (addInnerDarkTable) {
                        val onOuterRect = (u in 0.30f..0.70f && (kotlin.math.abs(v - 0.30f) < 0.006f || kotlin.math.abs(v - 0.65f) < 0.006f)) ||
                            (v in 0.30f..0.65f && (kotlin.math.abs(u - 0.30f) < 0.008f || kotlin.math.abs(u - 0.70f) < 0.008f))
                        val onInnerRule = u in 0.30f..0.70f && kotlin.math.abs(v - 0.42f) < 0.005f
                        if (onOuterRect || onInnerRule) {
                            c = tableInk
                        }
                    }
                    if (addShadowRightHalf && u >= 0.48f) {
                        val r = (((c shr 16) and 0xFF) * 0.56f).toInt()
                        val g = (((c shr 8) and 0xFF) * 0.56f).toInt()
                        val b = ((c and 0xFF) * 0.58f).toInt()
                        c = android.graphics.Color.rgb(r, g, b)
                    }
                    pixels[rowOff + x] = c
                }
            }
            bmp.setPixels(pixels, 0, w, 0, 0, w, h)
            return bmp
        }

        // 1. Strongly tilted perspective trapezoid on warm wooden desk + inner dark table (must grab outer paper, not table!)
        val tiltedBmp = drawQuadScene(
            w = 600,
            h = 800,
            bgColor = android.graphics.Color.rgb(122, 88, 56),
            paperColor = android.graphics.Color.rgb(246, 244, 239),
            tl = 0.22f to 0.14f,
            tr = 0.84f to 0.20f,
            br = 0.78f to 0.86f,
            bl = 0.12f to 0.78f,
            addInnerDarkTable = true
        )
        val qTilted = DocumentImageProcessor.detectDocumentCorners(tiltedBmp)
        assertTrue("Tilted paper should be detected (not FULL_FRAME): $qTilted", !qTilted.isFullFrame())
        assertEquals(0.22f, qTilted.tlX, 0.05f)
        assertEquals(0.14f, qTilted.tlY, 0.05f)
        assertEquals(0.84f, qTilted.trX, 0.05f)
        assertEquals(0.20f, qTilted.trY, 0.05f)
        assertEquals(0.78f, qTilted.brX, 0.05f)
        assertEquals(0.86f, qTilted.brY, 0.05f)
        assertEquals(0.12f, qTilted.blX, 0.05f)
        assertEquals(0.78f, qTilted.blY, 0.05f)

        // 2. Shadow crossing right half of the paper
        val shadowBmp = drawQuadScene(
            w = 600,
            h = 800,
            bgColor = android.graphics.Color.rgb(44, 48, 52),
            paperColor = android.graphics.Color.rgb(242, 240, 236),
            tl = 0.15f to 0.12f,
            tr = 0.85f to 0.14f,
            br = 0.88f to 0.86f,
            bl = 0.12f to 0.84f,
            addShadowRightHalf = true
        )
        val qShadow = DocumentImageProcessor.detectDocumentCorners(shadowBmp)
        assertTrue("Shadowed paper should still grab full outer corners: $qShadow", !qShadow.isFullFrame())
        assertEquals(0.85f, qShadow.trX, 0.06f)
        assertEquals(0.88f, qShadow.brX, 0.06f)
        assertEquals(0.15f, qShadow.tlX, 0.06f)

        // 3. White paper on light pastel blue desk (low luminance gap, relies on color Sobel + chroma distance)
        val lightDeskBmp = drawQuadScene(
            w = 600,
            h = 800,
            bgColor = android.graphics.Color.rgb(198, 224, 245),
            paperColor = android.graphics.Color.rgb(248, 246, 240),
            tl = 0.16f to 0.15f,
            tr = 0.84f to 0.15f,
            br = 0.86f to 0.85f,
            bl = 0.14f to 0.85f
        )
        val qLightDesk = DocumentImageProcessor.detectDocumentCorners(lightDeskBmp)
        assertTrue("Paper on light pastel desk should be detected: $qLightDesk", !qLightDesk.isFullFrame())
        assertEquals(0.16f, qLightDesk.tlX, 0.06f)
        assertEquals(0.84f, qLightDesk.trX, 0.06f)

        // 4. Blank uniform frame must gracefully return FULL_FRAME without hallucinating corners
        val blankBmp = android.graphics.Bitmap.createBitmap(400, 500, android.graphics.Bitmap.Config.ARGB_8888)
        blankBmp.eraseColor(android.graphics.Color.rgb(200, 200, 200))
        val qBlank = DocumentImageProcessor.detectDocumentCorners(blankBmp)
        assertTrue("Blank uniform scene must return FULL_FRAME, got $qBlank", qBlank.isFullFrame())
    }

    @Test
    fun verifyAccountSwitchingMergeAndCleanSwitchWorkflows() = runBlocking {
        // 1. Start in Phone Only with local folders and scans
        assertEquals("", repository.getLastSyncedAccountKey())
        assertTrue(!repository.hasAnyLocalFoldersOrScans())

        val folder = (repository.createFolder("Personal Docs", parentId = null, now = 1700006000000L) as FolderOperationResult.Success).folder
        val draft = repository.processDraftItem(
            repository.createDraftFromBitmap(DocumentImageProcessor.generateSampleCapturedSheet(1, 400, 600), 1700006001000L),
            detectEdges = false
        )
        val savedScan = repository.saveDraftBatchToFolder(listOf(draft), targetFolderId = folder.id).getOrThrow().first()
        assertTrue(repository.hasAnyLocalFoldersOrScans())
        assertTrue(File(savedScan.editedPath).exists())

        // 2. First sync to PC:usera records lastSyncedAccountKey while keeping Phone Only files
        repository.setLastSyncedAccountKey("PC:usera")
        assertEquals("PC:usera", repository.getLastSyncedAccountKey())
        database.scanDao().markScansSynced(listOf(savedScan.id))
        assertEquals("SYNCED", database.scanDao().getScanById(savedScan.id)?.syncState)

        // 3. Switch to GOOGLE:userb@gmail.com with Merge Files -> keeps local files & marks them LOCAL_DIRTY for the new account
        repository.prepareLocalWorkspaceForAccountMerge("GOOGLE:userb@gmail.com")
        assertEquals("GOOGLE:userb@gmail.com", repository.getLastSyncedAccountKey())
        assertEquals(1, database.folderDao().getAllFoldersOnce().size)
        val mergedScan = database.scanDao().getScanById(savedScan.id)
        assertNotNull(mergedScan)
        assertEquals("LOCAL_DIRTY", mergedScan!!.syncState)
        assertTrue(File(mergedScan.editedPath).exists())

        // 4. Switch to PC:userc with Switch Cleanly -> clears previous account's local copy from phone so only new account loads
        repository.resetLocalWorkspaceForAccountSwitch("PC:userc")
        assertEquals("PC:userc", repository.getLastSyncedAccountKey())
        assertTrue(!repository.hasAnyLocalFoldersOrScans())
        assertEquals(0, database.folderDao().getAllFoldersIncludingTrashedOnce().size)
        assertEquals(0, database.scanDao().getAllScansIncludingTrashedOnce().size)
        assertTrue(!File(savedScan.editedPath).exists())
    }

    @Test
    fun verifyCropConvexityGuard() {
        // 2D Convexity Guard: must accept rotated diamond while rejecting crossed/concave quads
        val rotatedDiamond = NormalizedQuad(
            tlX = 0.50f, tlY = 0.12f,
            trX = 0.88f, trY = 0.50f,
            brX = 0.50f, brY = 0.88f,
            blX = 0.12f, blY = 0.50f
        )
        assertTrue("Rotated diamond must pass 2D convexity check", DocumentImageProcessor.isValidConvexQuad(rotatedDiamond))

        val selfIntersectingQuad = NormalizedQuad(
            tlX = 0.20f, tlY = 0.20f,
            trX = 0.80f, trY = 0.80f,
            brX = 0.80f, brY = 0.20f,
            blX = 0.20f, blY = 0.80f
        )
        assertTrue("Self-intersecting bowtie must be rejected", !DocumentImageProcessor.isValidConvexQuad(selfIntersectingQuad))

        // NormalizedQuad.clamped() must preserve valid rotated/tilted convex quads without axis-aligned distortion
        val clampedDiamond = rotatedDiamond.clamped()
        assertEquals(0.50f, clampedDiamond.tlX, 0.001f)
        assertEquals(0.12f, clampedDiamond.tlY, 0.001f)
        assertEquals(0.88f, clampedDiamond.trX, 0.001f)
        assertEquals(0.50f, clampedDiamond.trY, 0.001f)
        assertEquals(0.50f, clampedDiamond.brX, 0.001f)
        assertEquals(0.88f, clampedDiamond.brY, 0.001f)
        assertEquals(0.12f, clampedDiamond.blX, 0.001f)
        assertEquals(0.50f, clampedDiamond.blY, 0.001f)
    }

    @Test
    fun verifyClearTempCachePreservesOriginalAndTrashDefersTombstones(): Unit = runBlocking {
        // Default startup theme should be SYSTEM until explicitly toggled by user
        val initialTheme = repository.themeModeFlow.first()
        assertEquals(com.example.data.AppThemeMode.SYSTEM, initialTheme)

        val folderRes = repository.createFolder("AuditDocs", parentId = null, now = 1700001000000L)
        assertTrue(folderRes is FolderOperationResult.Success)
        val folder = (folderRes as FolderOperationResult.Success).folder

        val draft = repository.processDraftItem(
            repository.createDraftFromBitmap(
                DocumentImageProcessor.generateSampleCapturedSheet(9, 600, 800),
                capturedAt = 1700001001000L
            ),
            detectEdges = true
        )
        val saved = repository.saveDraftBatchToFolder(
            listOf(draft),
            targetFolderId = folder.id,
            now = 1700001002000L
        ).getOrThrow().first()

        // Mark scan SYNCED: first check FIRESTORE_THUMB fallback tier protects editedPath,
        // then switch to DRIVE_FULL and verify clearTemporaryCache evicts only the duplicate editedPath
        database.scanDao().markScansSynced(listOf(saved.id))
        repository.updatePcSyncConfig(PcSyncConfig(mode = AppStorageSyncMode.GOOGLE_CLOUD_SYNC))
        repository.setGoogleSyncBackupTier("FIRESTORE_THUMB")
        assertTrue(repository.getStorageUsageSummary().tempHighResSyncedBytes == 0L)
        repository.clearTemporaryCache()
        assertTrue("Firestore thumbnail-only tier must protect full-res editedPath", File(saved.editedPath).exists())

        repository.setGoogleSyncBackupTier("DRIVE_FULL")
        val tempDraftFile = File(context.cacheDir, "draft_originals/stale_temp.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(2048) { 7 })
        }
        val usageBefore = repository.getStorageUsageSummary()
        assertTrue(usageBefore.cacheBytes > 0L)

        val freed = repository.clearTemporaryCache()
        assertTrue("Expected cache bytes to be freed", freed >= 2048L)
        assertTrue("Stale draft cache must be removed", !tempDraftFile.exists())
        assertTrue("Immutable original must be preserved for re-crop", File(saved.originalPath).exists())
        assertTrue("Offline preview thumbnail must be preserved", File(saved.thumbnailPath).exists())
        assertTrue("SYNCED edited duplicate should be evicted under DRIVE_FULL", !File(saved.editedPath).exists())

        // Re-crop must still succeed from preserved originalPath!
        val reCropped = repository.updateSavedScanEdits(
            scanId = saved.id,
            newQuad = NormalizedQuad(0.05f, 0.05f, 0.95f, 0.05f, 0.95f, 0.95f, 0.05f, 0.95f),
            newRotationDegrees = 0,
            newFilter = saved.scanFilter,
            now = 1700001005000L
        ).getOrThrow()
        assertTrue(File(reCropped.editedPath).exists())

        // Moving to 7-Day Trash must NOT emit remote deletion tombstones yet
        repository.moveScansToTrash(listOf(reCropped.id), now = 1700001006000L)
        val deletedDuringTrash = repository.deletedScanIdsFlow.first()
        assertTrue("Soft-trash must NOT mark scan for remote deletion", reCropped.id !in deletedDuringTrash)

        // Permanent deletion MUST record remote deletion tombstone
        assertTrue(repository.permanentlyDeleteTrashedScan(reCropped.id))
        val deletedAfterPurge = repository.deletedScanIdsFlow.first()
        assertTrue("Permanent purge must record deletion tombstone", reCropped.id in deletedAfterPurge)
    }

    @Test
    fun verifyZipBackupSkipsMissingScanFiles(): Unit = runBlocking {
        val backupFile = File(context.cacheDir, "test_corrupt_backup.zip")
        val manifestJson = org.json.JSONObject().apply {
            put("version", 1)
            put("exportedAt", System.currentTimeMillis())
            put(
                "folders",
                org.json.JSONArray().put(
                    org.json.JSONObject().apply {
                        put("id", "f_valid_1")
                        put("name", "ValidFolder")
                        put("parentId", org.json.JSONObject.NULL)
                        put("createdAt", 1700000000000L)
                        put("modifiedAt", 1700000000000L)
                        put("customOrder", 0)
                    }
                )
            )
            put(
                "scans",
                org.json.JSONArray().put(
                    org.json.JSONObject().apply {
                        put("id", "s_missing_img")
                        put("folderId", "f_valid_1")
                        put("filename", "Missing.jpg")
                    }
                )
            )
        }
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(backupFile)).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            zos.write(manifestJson.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        val res = repository.zipBackupManager.restoreFullBackupFromUri(android.net.Uri.fromFile(backupFile))
        assertTrue(res.success)
        assertEquals(1, res.foldersCount)
        assertEquals(0, res.scansCount)
        assertEquals(0, database.scanDao().getAllScansOnce().size)
        assertTrue(res.message.contains("skipped"))
        backupFile.delete()
        Unit
    }

    @Test
    fun verifyBulkRenamePlannerRepositoryAndUndo(): Unit = runBlocking {
        val folder = (repository.createFolder("Invoices", null, now = 1700002000000L) as FolderOperationResult.Success).folder

        // Create 4 scans: 3 selected + 1 unselected sibling ("Invoices_01.jpg") to verify collision avoidance
        val raw1 = DocumentImageProcessor.generateSampleCapturedSheet(1, 300, 400)
        val raw2 = DocumentImageProcessor.generateSampleCapturedSheet(2, 300, 400)
        val raw3 = DocumentImageProcessor.generateSampleCapturedSheet(3, 300, 400)
        val rawSibling = DocumentImageProcessor.generateSampleCapturedSheet(4, 300, 400)

        val d1 = repository.processDraftItem(repository.createDraftFromBitmap(raw1, capturedAt = 1700002001000L), detectEdges = false)
        val d2 = repository.processDraftItem(repository.createDraftFromBitmap(raw2, capturedAt = 1700002002000L), detectEdges = false)
        val d3 = repository.processDraftItem(repository.createDraftFromBitmap(raw3, capturedAt = 1700002003000L), detectEdges = false)
        val dSib = repository.processDraftItem(repository.createDraftFromBitmap(rawSibling, capturedAt = 1700002004000L), detectEdges = false)

        val saved = repository.saveDraftBatchToFolder(listOf(d1, d2, d3, dSib), folder.id, now = 1700002005000L).getOrThrow()
        assertEquals(4, saved.size)

        // Rename the 4th scan so it occupies "Invoices_01.jpg"
        val siblingScan = saved[3]
        repository.renameScan(siblingScan.id, "Invoices_01", now = 1700002006000L)
        val allScansNow = database.scanDao().getAllScansOnce()
        val selectedScans = allScansNow.filter { it.id != siblingScan.id }
        val foldersMap = database.folderDao().getAllFoldersOnce().associateBy { it.id }

        // 1. Pattern mode: OLDEST_FIRST with {folder}_{num}, padding 01, start 1 -> first item collides with sibling so auto-suffixes _2
        val patternPreview = BulkRenamePlanner.buildPreview(
            selectedScansInDisplayOrder = selectedScans,
            allActiveScans = allScansNow,
            foldersById = foldersMap,
            config = BulkRenameConfig(
                mode = BulkRenameMode.PATTERN,
                pattern = "{folder}_{num}",
                startNumber = 1,
                padding = BulkNumberPadding.TWO,
                order = BulkRenameOrder.OLDEST_FIRST
            )
        )
        assertEquals(3, patternPreview.size)
        assertEquals("Invoices_01_2", patternPreview[0].finalNewBaseName)
        assertTrue(patternPreview[0].hasAutoDisambiguation)
        assertEquals("Invoices_02", patternPreview[1].finalNewBaseName)
        assertEquals("Invoices_03", patternPreview[2].finalNewBaseName)

        // 2. Pattern mode: NEWEST_FIRST + 001 padding + {date} + {original} + invalid character sanitization
        val newestPreview = BulkRenamePlanner.buildPreview(
            selectedScansInDisplayOrder = selectedScans,
            allActiveScans = allScansNow,
            foldersById = foldersMap,
            config = BulkRenameConfig(
                mode = BulkRenameMode.PATTERN,
                pattern = "Doc:{num}_{date}_{folder}",
                startNumber = 5,
                padding = BulkNumberPadding.THREE,
                order = BulkRenameOrder.NEWEST_FIRST
            )
        )
        assertEquals(selectedScans.maxBy { it.capturedAt }.id, newestPreview[0].scan.id)
        assertTrue(newestPreview[0].finalNewBaseName.startsWith("Doc_005_"))
        assertTrue(newestPreview[0].finalNewBaseName.endsWith("_Invoices"))
        assertTrue(!newestPreview[0].finalNewBaseName.contains(":"))

        // 3. Apply atomic bulk rename to repository
        val originalFilenames = selectedScans.associate { it.id to it.filename }
        val proposedMap = patternPreview.associate { it.scan.id to it.finalNewBaseName }
        val applyRes = repository.bulkRenameScans(proposedMap, now = 1700002007000L)
        assertTrue(applyRes is BulkRenameResult.Success)
        val successRes = applyRes as BulkRenameResult.Success
        assertEquals(3, successRes.renamedCount)
        assertEquals(originalFilenames, successRes.previousNamesByScanId)

        val renamedInDb = database.scanDao().getAllScansOnce().associateBy { it.id }
        assertEquals("Invoices_01_2.jpg", renamedInDb[patternPreview[0].scan.id]?.filename)
        assertEquals("Invoices_02.jpg", renamedInDb[patternPreview[1].scan.id]?.filename)
        assertEquals("Invoices_03.jpg", renamedInDb[patternPreview[2].scan.id]?.filename)
        assertEquals("LOCAL_DIRTY", renamedInDb[patternPreview[0].scan.id]?.syncState)
        assertTrue(File(renamedInDb[patternPreview[0].scan.id]!!.editedPath).exists())
        assertTrue(File(renamedInDb[patternPreview[0].scan.id]!!.originalPath).exists())

        // 4. Find & Replace mode (case-insensitive vs case-sensitive)
        val renamedSelected = database.scanDao().getAllScansOnce().filter { it.id != siblingScan.id }
        val findCaseSensitive = BulkRenamePlanner.buildPreview(
            selectedScansInDisplayOrder = renamedSelected,
            allActiveScans = database.scanDao().getAllScansOnce(),
            foldersById = foldersMap,
            config = BulkRenameConfig(
                mode = BulkRenameMode.FIND_REPLACE,
                findText = "invoices",
                replaceText = "Receipt",
                matchCase = true
            )
        )
        assertEquals(0, findCaseSensitive.count { it.willChange })

        val findCaseInsensitive = BulkRenamePlanner.buildPreview(
            selectedScansInDisplayOrder = renamedSelected,
            allActiveScans = database.scanDao().getAllScansOnce(),
            foldersById = foldersMap,
            config = BulkRenameConfig(
                mode = BulkRenameMode.FIND_REPLACE,
                findText = "invoices",
                replaceText = "Receipt",
                matchCase = false
            )
        )
        assertEquals(3, findCaseInsensitive.count { it.willChange })
        assertTrue(findCaseInsensitive.all { it.finalNewBaseName.startsWith("Receipt_") })

        // 5. Undo restores original filenames cleanly
        val restoredCount = repository.restoreScanFilenames(successRes.previousNamesByScanId, now = 1700002008000L)
        assertEquals(3, restoredCount)
        val afterUndo = database.scanDao().getAllScansOnce().associateBy { it.id }
        for ((scanId, origName) in originalFilenames) {
            assertEquals(origName, afterUndo[scanId]?.filename)
        }
        Unit
    }

    @Test
    fun verifyDeveloperProfileResourcesAndIntents() {
        // Verify developer profile image resource is present in APK drawables
        val profileDrawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.developer_profile)
        assertNotNull("Developer profile photo must exist in drawable resources", profileDrawable)

        val whatsappDrawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_social_whatsapp)
        assertNotNull("WhatsApp icon must exist in drawable resources", whatsappDrawable)

        val facebookDrawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_social_facebook)
        assertNotNull("Facebook icon must exist in drawable resources", facebookDrawable)

        // Verify intent launcher helpers run cleanly without throwing exceptions
        com.example.util.HapticsAndShareHelper.openDialer(context, "+8801991617180")
        com.example.util.HapticsAndShareHelper.openWhatsApp(context, "+8801991617180")
        com.example.util.HapticsAndShareHelper.openEmail(context, "hadi280994@gmail.com")
        com.example.util.HapticsAndShareHelper.openFacebook(context, "https://www.facebook.com/hadi280994")
    }

    @Test
    fun verifyAiEnhanceRemovesLeftShadowAndSharpensInk() {
        val candidates = listOf(
            File("public/assets/aistudio/image_14f8d878.png"),
            File("../public/assets/aistudio/image_14f8d878.png")
        )
        val inputFile = candidates.firstOrNull { it.exists() }
        val src = if (inputFile != null) {
            android.graphics.BitmapFactory.decodeFile(inputFile.absolutePath)
        } else {
            DocumentImageProcessor.generateSampleCapturedSheet(1, 900, 1200)
        }
        assertNotNull(src)

        val enhanced = DocumentImageProcessor.applyFilter(src!!, com.example.data.ScanFilter.AI_ENHANCE, 0.5f)
        assertEquals(src.width, enhanced.width)
        assertEquals(src.height, enhanced.height)

        val verifyFile = File(context.cacheDir, "ai_enhance_verify.png")
        verifyFile.outputStream().use {
            enhanced.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        assertTrue(verifyFile.exists() && verifyFile.length() > 10_000L)

        val w = enhanced.width
        val h = enhanced.height
        val px = IntArray(w * h)
        enhanced.getPixels(px, 0, w, 0, 0, w, h)

        var leftPureWhite = 0
        var leftTotal = 0
        for (y in (h * 0.25f).toInt() until (h * 0.75f).toInt()) {
            for (x in 0 until (w * 0.20f).toInt()) {
                val c = px[y * w + x]
                leftTotal++
                if ((c and 0x00FFFFFF) == 0x00FFFFFF) leftPureWhite++
            }
        }
        val leftWhiteRatio = leftPureWhite.toFloat() / leftTotal.coerceAtLeast(1)
        assertTrue(
            "Left shadow region must become clean white (>95%), got $leftWhiteRatio",
            leftWhiteRatio > 0.95f
        )

        val darkInkCount = px.count { c ->
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            ((r * 77 + g * 150 + b * 29) ushr 8) < 110
        }
        val pureWhiteTotalRatio = px.count { (it and 0x00FFFFFF) == 0x00FFFFFF }.toFloat() / px.size.coerceAtLeast(1)
        assertTrue("Crisp dark ink strokes must be preserved (>1500 px), got $darkInkCount", darkInkCount > 1500)
        assertTrue(
            "Overall page must stay crisp without over-bolding (<22% ink coverage), pureWhite=$pureWhiteTotalRatio",
            pureWhiteTotalRatio > 0.78f
        )
        verifyFile.delete()
        Unit
    }

    @Test
    fun verifyCroppedOriginalPreviewRendersCroppedWithoutFilter() {
        val w = 400
        val h = 500
        val src = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        val borderBlue = (0xFF shl 24) or (20 shl 16) or (40 shl 8) or 220
        val creamPaper = (0xFF shl 24) or (225 shl 16) or (215 shl 8) or 195
        for (y in 0 until h) {
            for (x in 0 until w) {
                val insidePaper = x in 80 until 320 && y in 100 until 400
                pixels[y * w + x] = if (insidePaper) creamPaper else borderBlue
            }
        }
        src.setPixels(pixels, 0, w, 0, 0, w, h)

        val quad = NormalizedQuad(0.22f, 0.22f, 0.78f, 0.22f, 0.78f, 0.78f, 0.22f, 0.78f)
        val cropped = DocumentImageProcessor.renderCroppedOriginal(src, quad, rotationDegrees = 0, maxDimension = 300)

        val centerColor = cropped.getPixel(cropped.width / 2, cropped.height / 2)
        val cornerColor = cropped.getPixel(2, 2)
        val centerR = (centerColor ushr 16) and 0xFF
        val centerG = (centerColor ushr 8) and 0xFF
        val centerB = centerColor and 0xFF
        val cornerR = (cornerColor ushr 16) and 0xFF
        val cornerB = cornerColor and 0xFF
        assertTrue(
            "Outer blue border must be cropped out (corner RGB=$cornerR,$cornerB)",
            cornerB < 210
        )
        assertTrue(
            "Cropped Original preview must not apply B&W or auto-bleach filter (center RGB=$centerR,$centerG,$centerB)",
            centerR in 210..240 && centerR > centerB
        )
    }

    @Test
    fun verifyAiEnhanceCleansHeavyDimShadowWithoutBoldingThinLines() {
        // Simulates a dim dark-gray shadowed document sheet with 1-2px thin table lines and text strokes
        val w = 480
        val h = 640
        val src = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                // Heavy dim gradient from 78 (top-left shadow) to 145 (bottom-right dim light)
                val paperL = (78f + (x.toFloat() / w) * 35f + (y.toFloat() / h) * 32f).toInt().coerceIn(60, 160)
                val isThinBorder = (x in 60..61 && y in 120..520) || (y in 200..201 && x in 60..420)
                val lum = if (isThinBorder) (paperL * 0.30f).toInt() else paperL
                px[y * w + x] = (0xFF shl 24) or (lum shl 16) or (lum shl 8) or lum
            }
        }
        src.setPixels(px, 0, w, 0, 0, w, h)

        val out = DocumentImageProcessor.applyFilter(src, com.example.data.ScanFilter.AI_ENHANCE, 0.5f)
        val outPx = IntArray(w * h)
        out.getPixels(outPx, 0, w, 0, 0, w, h)

        // Margin shadow area outside the thin line should become pure white (#FFFFFF)
        var whitePaperCount = 0
        var checkedPaper = 0
        for (y in 20 until 100) {
            for (x in 20 until 200) {
                checkedPaper++
                if ((outPx[y * w + x] and 0x00FFFFFF) == 0x00FFFFFF) whitePaperCount++
            }
        }
        assertTrue("Dim dark-gray background must turn pure white (>98%)", whitePaperCount > checkedPaper * 0.98f)

        // Check cross-section across horizontal 2px line (y = 200..201) at x = 240: should NOT bloat beyond <= 4px wide
        var darkRowPixels = 0
        for (y in 196..205) {
            val lum = outPx[y * w + 240] and 0xFF
            if (lum < 180) darkRowPixels++
        }
        assertTrue("2px line must remain crisp (1..4px), got $darkRowPixels", darkRowPixels in 1..4)
    }

    @Test
    fun verifyPcSyncLanOnlyHttpAndPublicHttpsEnforcement() {
        val mgr = repository.pcSyncManager

        assertTrue(mgr.isLanHost("192.168.1.10"))
        assertTrue(mgr.isLanHost("10.0.0.5"))
        assertTrue(mgr.isLanHost("172.16.4.2"))
        assertTrue(mgr.isLanHost("127.0.0.1"))
        assertTrue(mgr.isLanHost("localhost"))
        assertTrue(mgr.isLanHost("office-pc.local"))
        assertFalse(mgr.isLanHost("example.com"))
        assertFalse(mgr.isLanHost("8.8.8.8"))
        assertFalse(mgr.isLanHost("172.32.0.1"))

        assertTrue(mgr.isAllowedTransportUrl("http://192.168.1.10:8085"))
        assertTrue(mgr.isAllowedTransportUrl("http://localhost:8085"))
        assertTrue(mgr.isAllowedTransportUrl("http://10.0.0.5:8085"))
        assertTrue(mgr.isAllowedTransportUrl("http://my-pc.local:8085"))
        assertTrue(mgr.isAllowedTransportUrl("https://sync.example.com"))

        // Non-LAN plaintext HTTP must be blocked
        assertFalse(mgr.isAllowedTransportUrl("http://sync.example.com"))
        assertFalse(mgr.isAllowedTransportUrl("http://8.8.8.8:8085"))
        assertFalse(mgr.isAllowedTransportUrl("ftp://192.168.1.10"))

        // Scheme-less host normalization picks http for LAN and https for public hosts
        assertEquals("http://192.168.1.50:8085", mgr.normalizeServerBaseUrl("192.168.1.50:8085/"))
        assertEquals("https://docs.example.com", mgr.normalizeServerBaseUrl("docs.example.com/"))
        assertEquals("", mgr.resolveServerBaseUrl("http://public-server.example.com"))
    }

    @Test
    fun verifyPinPbkdf2AndProgressiveLockout(): Unit = runBlocking {
        repository.clearPinLockoutState()
        assertFalse(repository.setAppLockPin("123")) // too short
        assertTrue(repository.setAppLockPin("4829"))

        val t0 = 1_700_000_000_000L
        for (attempt in 1..4) {
            val res = repository.verifyAppLockPinWithStatus("0000", nowMs = t0 + attempt)
            assertTrue("Expected Invalid on attempt $attempt, got $res", res is PinVerifyResult.Invalid)
            val invalid = res as PinVerifyResult.Invalid
            assertEquals(attempt, invalid.failedAttempts)
            assertEquals(5 - attempt, invalid.remainingBeforeLockout)
        }

        // 5th wrong attempt triggers 30s lockout
        val fifth = repository.verifyAppLockPinWithStatus("0000", nowMs = t0 + 5)
        assertTrue("Expected LockedOut on 5th failed attempt, got $fifth", fifth is PinVerifyResult.LockedOut)
        assertEquals(30, (fifth as PinVerifyResult.LockedOut).remainingSeconds)

        // Even correct PIN is rejected while cooldown is active
        val duringCooldown = repository.verifyAppLockPinWithStatus("4829", nowMs = t0 + 10_000L)
        assertTrue(duringCooldown is PinVerifyResult.LockedOut)

        // After cooldown expires, correct PIN unlocks and clears failed counter
        val afterCooldown = repository.verifyAppLockPinWithStatus("4829", nowMs = t0 + 35_000L)
        assertTrue(afterCooldown is PinVerifyResult.Success)
        val lockoutState = repository.pinLockoutStateFlow.first()
        assertEquals(0, lockoutState.failedAttempts)
        assertEquals(0L, lockoutState.lockoutUntilMs)
    }

    @Test
    fun verifyZipRestoreRejectsPathTraversalAndClampsInvalidQuads(): Unit = runBlocking {
        val zipFile = File(context.cacheDir, "test_hardened_backup.zip")
        val jpegBytes = ByteArrayOutputStream().use { bos ->
            val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos)
            bmp.recycle()
            bos.toByteArray()
        }

        val manifest = JSONObject().apply {
            put("app", "Kagojpotro")
            put("version", 1)
            put("folders", JSONArray().apply {
                put(JSONObject().apply {
                    put("id", "folder_ok_1")
                    put("name", "Restored Folder")
                    put("parentId", JSONObject.NULL)
                    put("createdAt", 1700000000000L)
                    put("modifiedAt", 1700000000000L)
                    put("customOrder", 0)
                })
            })
            put("scans", JSONArray().apply {
                put(JSONObject().apply {
                    put("id", "scan_ok_1")
                    put("folderId", "folder_ok_1")
                    put("filename", "bad:name/with?chars.jpg")
                    put("capturedAt", 1700000000000L)
                    put("modifiedAt", 1700000000000L)
                    put("rotationDegrees", -90)
                    put("cropTlX", "NaN")
                    put("cropTlY", -0.5)
                    put("cropTrX", 1.8)
                    put("cropTrY", 0.0)
                    put("cropBrX", 1.0)
                    put("cropBrY", 2.5)
                    put("cropBlX", 0.0)
                    put("cropBlY", 1.0)
                    put("filterMode", "MAGIC_COLOR")
                    put("width", 64)
                    put("height", 64)
                })
            })
        }

        val escapedTarget = File(context.cacheDir, "evil_escape.txt")
        if (escapedTarget.exists()) escapedTarget.delete()

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // Malicious traversal entries must be ignored
            zos.putNextEntry(ZipEntry("../../evil_escape.txt"))
            zos.write("pwned".toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("edited/../evil_escape.jpg"))
            zos.write(jpegBytes)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("edited/scan_ok_1.jpg"))
            zos.write(jpegBytes)
            zos.closeEntry()
        }

        val result = repository.zipBackupManager.restoreFullBackupFromUri(Uri.fromFile(zipFile))
        assertTrue("Restore failed unexpectedly: ${result.message}", result.success)
        assertFalse("Path traversal file must never be created", escapedTarget.exists())
        assertEquals(1, result.foldersCount)
        assertEquals(1, result.scansCount)

        val scans = database.scanDao().getScansInFolderOnce("folder_ok_1")
        assertEquals(1, scans.size)
        val restored = scans.first()
        assertFalse(restored.filename.contains("/"))
        assertFalse(restored.filename.contains(":"))
        assertEquals(270, restored.rotationDegrees)
        assertTrue(restored.cropTlX in 0f..1f && restored.cropTlX.isFinite())
        assertEquals(0f, restored.cropTlY, 0.001f)
        assertEquals(1f, restored.cropTrX, 0.001f)
        assertEquals(1f, restored.cropBrY, 0.001f)
        zipFile.delete()
    }

    @Test
    fun verifyViewModelRejectsUnreadableImportsAndLocksOnBackground(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = KagojpotroViewModel(app)
        val mainLooper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())

        // 1. Corrupt/nonexistent URI import must NOT create synthetic draft sheets
        val nonexistentUri = Uri.fromFile(File(context.cacheDir, "does_not_exist_12345.jpg"))
        vm.importFromDeviceUris(listOf(nonexistentUri))
        repeat(40) {
            mainLooper.idle()
            if (vm.statusToast.value != null) return@repeat
            Thread.sleep(25L)
        }
        assertTrue("Draft items must remain empty when all imported URIs fail", vm.draftItems.value.isEmpty())
        assertNotNull("Expected an error toast for unreadable imported URI", vm.statusToast.value)

        // 2. App lock should lock after backgrounding when lock is enabled
        assertTrue(vm.setupOrChangeAppPin("1357"))
        vm.setAppLockEnabled(true)
        repeat(40) {
            mainLooper.idle()
            if (vm.appLockEnabled.value && vm.hasAppPin.value) return@repeat
            Thread.sleep(25L)
        }
        vm.onAppBackgrounded()
        vm.onAppForegrounded()
        assertTrue("App must lock when backgrounded without active grace window", vm.isAppLocked.value)

        // Unlock with correct PIN
        val unlockResult = vm.unlockAppWithPinResult("1357")
        assertTrue(unlockResult is PinVerifyResult.Success)
        assertFalse(vm.isAppLocked.value)
    }

    @Test
    fun verifySmartEraserHealsInkLineAndAiEnhancePreservesThinLines() {
        val w = 360
        val h = 300
        val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val paperLum = 242
                val isInkLine = (y in 148..151 && x in 60..300)
                val lum = if (isInkLine) 25 else paperLum
                px[y * w + x] = (0xFF shl 24) or (lum shl 16) or (lum shl 8) or lum
            }
        }
        src.setPixels(px, 0, w, 0, 0, w, h)

        val strokePoints = listOf(
            androidx.compose.ui.geometry.Offset(0.30f, 0.50f),
            androidx.compose.ui.geometry.Offset(0.50f, 0.50f),
            androidx.compose.ui.geometry.Offset(0.70f, 0.50f)
        )
        val erased = com.example.processing.SmartDocumentEraser.eraseStroke(
            source = src,
            normalizedPoints = strokePoints,
            normalizedRadius = 0.035f
        )
        val centerErasedPixel = erased.getPixel(w / 2, 150)
        val erasedLum = centerErasedPixel and 0xFF
        assertTrue(
            "Smart Eraser must heal the dark ink line (lum=25) into bright paper (>215), got $erasedLum",
            erasedLum > 215
        )

        // Verify thin 1px line with anti-aliased shoulders does not get thickened by AI Enhance
        val thinSrc = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val thinPx = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val lum = when {
                    x in 50..310 && y == 120 -> 45   // 1px core line
                    x in 50..310 && (y == 119 || y == 121) -> 185 // light anti-aliased shoulder
                    else -> 228 // paper background
                }
                thinPx[y * w + x] = (0xFF shl 24) or (lum shl 16) or (lum shl 8) or lum
            }
        }
        thinSrc.setPixels(thinPx, 0, w, 0, 0, w, h)
        val enhancedThin = DocumentImageProcessor.applyFilter(thinSrc, com.example.data.ScanFilter.AI_ENHANCE, 0.5f)
        var darkCount = 0
        for (y in 116..124) {
            val lum = enhancedThin.getPixel(w / 2, y) and 0xFF
            if (lum < 140) darkCount++
        }
        assertEquals("1px thin line must remain 1px wide without shoulder thickening", 1, darkCount)

        // Verify bold headers ("BD INTERIOR" style 12px-16px wide stems with slight center toner sheen lum=72)
        // stay 100% solid dark ink inside with ZERO hollowing/outlining, while shadowed paper becomes #FFFFFF
        val boldSrc = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val boldPx = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val paperLum = if (x < 140) 145 else 226 // steep shadow on left half
                val isBoldStem = (y in 70..115 && x in 180..193)
                val isBoldCenter = (y in 75..110 && x in 184..189)
                val lum = when {
                    isBoldCenter -> 74 // interior of bold letter with slight toner sheen (not < 38)
                    isBoldStem -> 58   // outer edge of bold letter
                    else -> paperLum
                }
                boldPx[y * w + x] = (0xFF shl 24) or (lum shl 16) or (lum shl 8) or lum
            }
        }
        boldSrc.setPixels(boldPx, 0, w, 0, 0, w, h)
        val enhancedBold = DocumentImageProcessor.applyFilter(boldSrc, com.example.data.ScanFilter.AI_ENHANCE, 0.5f)
        for (x in 182..191) {
            val interiorLum = enhancedBold.getPixel(x, 92) and 0xFF
            assertTrue(
                "Bold character interior at x=$x must stay solid dark ink (<45), never hollow/outlined (got $interiorLum)",
                interiorLum < 45
            )
        }
        val shadowedPaperLum = enhancedBold.getPixel(60, 92) and 0xFF
        assertEquals("Shadowed paper must bleach cleanly to pure white (255)", 255, shadowedPaperLum)

        // Verify Content-Aware Paper Shade Fill on warm/shaded paper (R=218, G=210, B=196)
        val shadedSrc = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val shadedPx = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val isDarkLine = (y in 147..152 && x in 80..280)
                val r = if (isDarkLine) 28 else 218
                val g = if (isDarkLine) 28 else 210
                val b = if (isDarkLine) 28 else 196
                shadedPx[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        shadedSrc.setPixels(shadedPx, 0, w, 0, 0, w, h)
        val shadedErased = com.example.processing.SmartDocumentEraser.eraseStroke(
            source = shadedSrc,
            normalizedPoints = strokePoints,
            normalizedRadius = 0.035f
        )
        val healedColor = shadedErased.getPixel(w / 2, 150)
        val healedR = (healedColor ushr 16) and 0xFF
        val healedG = (healedColor ushr 8) and 0xFF
        val healedB = healedColor and 0xFF
        assertTrue("Content-aware erase R ($healedR) must match warm paper shade ~218", kotlin.math.abs(healedR - 218) <= 5)
        assertTrue("Content-aware erase G ($healedG) must match warm paper shade ~210", kotlin.math.abs(healedG - 210) <= 5)
        assertTrue("Content-aware erase B ($healedB) must match warm paper shade ~196", kotlin.math.abs(healedB - 196) <= 5)
    }

    @Test
    fun verifySavedScanEraserStrokesPersistReplayAcrossFilterAndRotationAndResetOnCrop(): Unit = runBlocking {
        val folder = (repository.createFolder("EraserDocs", null, now = 1700009000000L) as FolderOperationResult.Success).folder

        // Create a square document with a bright paper surface (lum=240) and a horizontal ink bar across row y ~ 0.25 (x in 0.2..0.8)
        val w = 320
        val h = 320
        val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val isInk = (y in 76..84 && x in 64..256)
                val lum = if (isInk) 24 else 240
                px[y * w + x] = (0xFF shl 24) or (lum shl 16) or (lum shl 8) or lum
            }
        }
        src.setPixels(px, 0, w, 0, 0, w, h)

        val draft = repository.processDraftItem(
            repository.createDraftFromBitmap(src, capturedAt = 1700009001000L).copy(filter = ScanFilter.ORIGINAL),
            detectEdges = false
        )
        val saved = repository.saveDraftBatchToFolder(listOf(draft), targetFolderId = folder.id, now = 1700009002000L).getOrThrow().first()

        // Apply Smart Eraser stroke over the dark ink bar (y=0.25, x=0.3..0.7)
        val stroke = EraserStrokeRecord(
            normalizedPoints = listOf(0.30f to 0.25f, 0.50f to 0.25f, 0.70f to 0.25f),
            normalizedRadius = 0.04f
        )
        val beforeBmp = android.graphics.BitmapFactory.decodeFile(saved.editedPath)!!
        val erasedBmp = com.example.processing.SmartDocumentEraser.eraseStroke(
            source = beforeBmp,
            normalizedPoints = stroke.normalizedPoints.map { (x, y) -> androidx.compose.ui.geometry.Offset(x, y) },
            normalizedRadius = stroke.normalizedRadius
        )
        val withEraser = repository.saveEditedBitmapToScan(
            scanId = saved.id,
            editedBitmap = erasedBmp,
            eraserStrokes = listOf(stroke),
            now = 1700009003000L
        ).getOrThrow()
        assertEquals(1, withEraser.eraserStrokes.size)

        // 1. Switch filter to AI_ENHANCE (without passing explicit strokes): saved eraser stroke must replay!
        val afterFilter = repository.updateSavedScanEdits(
            scanId = saved.id,
            newQuad = withEraser.cropQuad,
            newRotationDegrees = 0,
            newFilter = ScanFilter.AI_ENHANCE,
            filterIntensity = 0.5f,
            now = 1700009004000L
        ).getOrThrow()
        assertEquals(1, afterFilter.eraserStrokes.size)
        val filteredBmp = android.graphics.BitmapFactory.decodeFile(afterFilter.editedPath)!!
        val healedCenterFilterLum = filteredBmp.getPixel(filteredBmp.width / 2, (filteredBmp.height * 0.25f).toInt()) and 0xFF
        assertTrue(
            "Healed stroke at (0.50, 0.25) must stay bright paper after filter change (got $healedCenterFilterLum)",
            healedCenterFilterLum > 200
        )

        // 2. Rotate 90 degrees clockwise: stroke coordinates rotate (x,y)->(1-y,x) -> (0.75, 0.50) and replay on rotated image!
        val afterRotate = repository.updateSavedScanEdits(
            scanId = saved.id,
            newQuad = afterFilter.cropQuad,
            newRotationDegrees = 90,
            newFilter = ScanFilter.AI_ENHANCE,
            filterIntensity = 0.5f,
            now = 1700009005000L
        ).getOrThrow()
        assertEquals(1, afterRotate.eraserStrokes.size)
        val rotatedStrokeMid = afterRotate.eraserStrokes.first().normalizedPoints[1]
        assertEquals(0.75f, rotatedStrokeMid.first, 0.01f)
        assertEquals(0.50f, rotatedStrokeMid.second, 0.01f)
        val rotatedBmp = android.graphics.BitmapFactory.decodeFile(afterRotate.editedPath)!!
        val healedRotatedLum = rotatedBmp.getPixel((rotatedBmp.width * 0.75f).toInt(), rotatedBmp.height / 2) and 0xFF
        assertTrue(
            "Healed stroke rotated to (0.75, 0.50) must stay bright paper (got $healedRotatedLum)",
            healedRotatedLum > 200
        )

        // 3. Changing crop geometry clears eraserStrokes cleanly
        val newQuad = NormalizedQuad(0.05f, 0.05f, 0.95f, 0.05f, 0.95f, 0.95f, 0.05f, 0.95f)
        val afterCrop = repository.updateSavedScanEdits(
            scanId = saved.id,
            newQuad = newQuad,
            newRotationDegrees = 90,
            newFilter = ScanFilter.AI_ENHANCE,
            now = 1700009006000L
        ).getOrThrow()
        assertTrue("Crop change should reset eraserStrokes", afterCrop.eraserStrokes.isEmpty())
    }

    @Test
    fun verifySevenDayTrashBlocksPcSyncResurrectionAndKeepsRemoteBackupUntilPermanentDelete(): Unit = runBlocking {
        val postedManifests = java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
        val remoteManifest = JSONObject().apply {
            put("version", 1)
            put("updatedAt", 1700010000000L)
            put("deletedFolderIds", JSONArray())
            put("deletedScanIds", JSONArray())
            put("folders", JSONArray().put(JSONObject().apply {
                put("id", "f_trashed")
                put("name", "Trashed Folder")
                put("parentId", JSONObject.NULL)
                put("createdAt", 1700010000000L)
                put("modifiedAt", 1700010000000L)
                put("customOrder", 0)
            }))
            put("scans", JSONArray().put(JSONObject().apply {
                put("id", "s_trashed")
                put("folderId", "f_trashed")
                put("filename", "TrashedDoc.jpg")
                put("capturedAt", 1700010000000L)
                put("modifiedAt", 1700010000000L)
                put("rotationDegrees", 0)
                put("filterMode", "ORIGINAL")
                put("width", 100)
                put("height", 100)
            }))
        }

        val serverSocket = java.net.ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        val serverThread = Thread {
            while (!serverSocket.isClosed) {
                val client = try {
                    serverSocket.accept()
                } catch (_: Exception) {
                    break
                }
                try {
                    client.soTimeout = 3000
                    val input = client.getInputStream()
                    val headerLines = mutableListOf<String>()
                    val lineBuf = ByteArrayOutputStream()
                    while (true) {
                        val b = input.read()
                        if (b == -1) break
                        if (b == '\n'.code) {
                            val line = lineBuf.toString("UTF-8").trimEnd('\r')
                            lineBuf.reset()
                            if (line.isEmpty()) break
                            headerLines.add(line)
                        } else {
                            lineBuf.write(b)
                        }
                    }
                    val reqLine = headerLines.firstOrNull().orEmpty()
                    val contentLength = headerLines
                        .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                        ?.substringAfter(":")?.trim()?.toIntOrNull() ?: 0
                    val bodyBytes = ByteArray(contentLength)
                    var readTotal = 0
                    while (readTotal < contentLength) {
                        val r = input.read(bodyBytes, readTotal, contentLength - readTotal)
                        if (r <= 0) break
                        readTotal += r
                    }

                    val respBody = when {
                        reqLine.contains("/api/ping") -> """{"status":"ok","app":"Kagojpotro"}"""
                        reqLine.startsWith("GET ") && reqLine.contains("/api/manifest") -> remoteManifest.toString()
                        reqLine.startsWith("POST ") && reqLine.contains("/api/manifest") -> {
                            postedManifests.add(JSONObject(String(bodyBytes, Charsets.UTF_8)))
                            """{"status":"ok"}"""
                        }
                        else -> """{"status":"ok"}"""
                    }
                    val respBytes = respBody.toByteArray(Charsets.UTF_8)
                    val resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${respBytes.size}\r\nConnection: close\r\n\r\n"
                    client.getOutputStream().write(resp.toByteArray(Charsets.US_ASCII))
                    client.getOutputStream().write(respBytes)
                    client.getOutputStream().flush()
                } catch (_: Exception) {
                } finally {
                    runCatching { client.close() }
                }
            }
        }.apply { isDaemon = true; start() }

        try {
            val cfg = PcSyncConfig(
                mode = AppStorageSyncMode.PC_SERVER_SYNC,
                serverUrl = "http://127.0.0.1:${serverSocket.localPort}",
                userDirectory = "tester"
            )
            val summary = repository.pcSyncManager.performSyncCycle(
                config = cfg,
                deletedFolderIds = emptySet(),
                deletedScanIds = emptySet(),
                trashedFolderIds = setOf("f_trashed"),
                trashedScanIds = setOf("s_trashed")
            )
            assertTrue("Sync cycle failed: ${summary.error}", summary.success)
            assertTrue("Trashed folder must NOT be resurrected into active library", database.folderDao().getAllFoldersOnce().isEmpty())
            assertTrue("Trashed scan must NOT be resurrected into active library", database.scanDao().getAllScansOnce().isEmpty())
            assertEquals(1, postedManifests.size)
            val posted = postedManifests.first()
            val postedFolderIds = (0 until posted.getJSONArray("folders").length()).map {
                posted.getJSONArray("folders").getJSONObject(it).getString("id")
            }
            val postedScanIds = (0 until posted.getJSONArray("scans").length()).map {
                posted.getJSONArray("scans").getJSONObject(it).getString("id")
            }
            assertTrue("Remote folder backup must be preserved while item is in 7-day Trash", "f_trashed" in postedFolderIds)
            assertTrue("Remote scan backup must be preserved while item is in 7-day Trash", "s_trashed" in postedScanIds)
        } finally {
            serverSocket.close()
            serverThread.join(1000L)
        }
    }

    @Test
    fun verifyPcSyncKeepsLocalDirtyWhenOriginalUploadFails(): Unit = runBlocking {
        val folder = (repository.createFolder("UploadTest", null, now = 1700011000000L) as FolderOperationResult.Success).folder
        val draft = repository.processDraftItem(
            repository.createDraftFromBitmap(DocumentImageProcessor.generateSampleCapturedSheet(1, 300, 400), 1700011001000L),
            detectEdges = false
        )
        val saved = repository.saveDraftBatchToFolder(listOf(draft), folder.id, now = 1700011002000L).getOrThrow().first()

        val emptyManifest = JSONObject().apply {
            put("version", 1)
            put("updatedAt", 0L)
            put("deletedFolderIds", JSONArray())
            put("deletedScanIds", JSONArray())
            put("folders", JSONArray())
            put("scans", JSONArray())
        }

        val serverSocket = java.net.ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        val serverThread = Thread {
            while (!serverSocket.isClosed) {
                val client = try {
                    serverSocket.accept()
                } catch (_: Exception) {
                    break
                }
                try {
                    client.soTimeout = 3000
                    val input = client.getInputStream()
                    val headerLines = mutableListOf<String>()
                    val lineBuf = ByteArrayOutputStream()
                    while (true) {
                        val b = input.read()
                        if (b == -1) break
                        if (b == '\n'.code) {
                            val line = lineBuf.toString("UTF-8").trimEnd('\r')
                            lineBuf.reset()
                            if (line.isEmpty()) break
                            headerLines.add(line)
                        } else {
                            lineBuf.write(b)
                        }
                    }
                    val reqLine = headerLines.firstOrNull().orEmpty()
                    val contentLength = headerLines
                        .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                        ?.substringAfter(":")?.trim()?.toIntOrNull() ?: 0
                    val bodyBytes = ByteArray(contentLength)
                    var readTotal = 0
                    while (readTotal < contentLength) {
                        val r = input.read(bodyBytes, readTotal, contentLength - readTotal)
                        if (r <= 0) break
                        readTotal += r
                    }

                    val (statusLine, respBody) = when {
                        reqLine.contains("/api/ping") -> "HTTP/1.1 200 OK" to """{"status":"ok","app":"Kagojpotro"}"""
                        reqLine.startsWith("GET ") && reqLine.contains("/api/manifest") -> "HTTP/1.1 200 OK" to emptyManifest.toString()
                        reqLine.contains("/api/file") && reqLine.contains("kind=original") ->
                            "HTTP/1.1 500 Internal Server Error" to """{"error":"disk write failed"}"""
                        reqLine.contains("/api/file") -> "HTTP/1.1 200 OK" to """{"status":"ok"}"""
                        reqLine.startsWith("POST ") && reqLine.contains("/api/manifest") -> "HTTP/1.1 200 OK" to """{"status":"ok"}"""
                        else -> "HTTP/1.1 200 OK" to """{"status":"ok"}"""
                    }
                    val respBytes = respBody.toByteArray(Charsets.UTF_8)
                    val resp = "$statusLine\r\nContent-Type: application/json\r\nContent-Length: ${respBytes.size}\r\nConnection: close\r\n\r\n"
                    client.getOutputStream().write(resp.toByteArray(Charsets.US_ASCII))
                    client.getOutputStream().write(respBytes)
                    client.getOutputStream().flush()
                } catch (_: Exception) {
                } finally {
                    runCatching { client.close() }
                }
            }
        }.apply { isDaemon = true; start() }

        try {
            val cfg = PcSyncConfig(
                mode = AppStorageSyncMode.PC_SERVER_SYNC,
                serverUrl = "http://127.0.0.1:${serverSocket.localPort}",
                userDirectory = "tester"
            )
            val summary = repository.pcSyncManager.performSyncCycle(
                config = cfg,
                deletedFolderIds = emptySet(),
                deletedScanIds = emptySet()
            )
            assertTrue("Sync cycle should complete with partial upload handling", summary.success)
            val dbScan = database.scanDao().getScanById(saved.id)!!
            assertEquals("Scan must remain LOCAL_DIRTY when original upload fails", "LOCAL_DIRTY", dbScan.syncState)
        } finally {
            serverSocket.close()
            serverThread.join(1000L)
        }
    }

    @Test
    fun verifyZipRestoreReRendersMissingEditedFromOriginalAndEraserStrokes(): Unit = runBlocking {
        val zipFile = File(context.cacheDir, "test_rerender_backup.zip")
        val origBytes = ByteArrayOutputStream().use { bos ->
            val sheet = DocumentImageProcessor.generateSampleCapturedSheet(1, 320, 400)
            sheet.compress(Bitmap.CompressFormat.JPEG, 92, bos)
            sheet.recycle()
            bos.toByteArray()
        }
        val strokeJson = com.example.data.encodeEraserStrokes(
            listOf(EraserStrokeRecord(normalizedPoints = listOf(0.25f to 0.25f, 0.75f to 0.25f), normalizedRadius = 0.03f))
        )

        val manifest = JSONObject().apply {
            put("app", "Kagojpotro")
            put("version", 1)
            put("folders", JSONArray().apply {
                put(JSONObject().apply {
                    put("id", "f_rerender")
                    put("name", "Restored Renders")
                    put("parentId", JSONObject.NULL)
                    put("createdAt", 1700012000000L)
                    put("modifiedAt", 1700012000000L)
                    put("customOrder", 0)
                })
            })
            put("scans", JSONArray().apply {
                put(JSONObject().apply {
                    put("id", "s_rerender")
                    put("folderId", "f_rerender")
                    put("filename", "ReRendered.jpg")
                    put("capturedAt", 1700012000000L)
                    put("modifiedAt", 1700012001000L)
                    put("rotationDegrees", 90)
                    put("filterMode", "WHITEBOARD")
                    put("eraserStrokesJson", strokeJson)
                    put("width", 400)
                    put("height", 320)
                })
            })
        }

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // Provide ONLY original/<id>.jpg, intentionally omitting edited/<id>.jpg
            zos.putNextEntry(ZipEntry("original/s_rerender.jpg"))
            zos.write(origBytes)
            zos.closeEntry()
        }

        val res = repository.zipBackupManager.restoreFullBackupFromUri(Uri.fromFile(zipFile))
        assertTrue("Expected restore to succeed cleanly: ${res.message}", res.success)
        assertEquals(1, res.scansCount)
        val restored = database.scanDao().getScanById("s_rerender")!!
        assertTrue("Edited image must be freshly rendered from original", File(restored.editedPath).exists() && File(restored.editedPath).length() > 0L)
        assertTrue("Thumbnail must be generated", File(restored.thumbnailPath).exists() && File(restored.thumbnailPath).length() > 0L)
        assertEquals(90, restored.rotationDegrees)
        assertEquals(ScanFilter.WHITEBOARD, restored.scanFilter)
        assertEquals(1, restored.eraserStrokes.size)
        zipFile.delete()
    }

    @Test
    fun verifyGithubReleaseUpdaterVersionComparisonAndApkDownload(): Unit = runBlocking {
        // 1. Repo slug normalization
        assertEquals(
            "hadi280994/kagojpotro",
            com.example.data.GithubReleaseUpdater.normalizeRepoSlug("https://github.com/hadi280994/kagojpotro/releases")
        )
        assertEquals(
            "hadi280994/kagojpotro",
            com.example.data.GithubReleaseUpdater.normalizeRepoSlug("@hadi280994/kagojpotro.git")
        )
        assertEquals("", com.example.data.GithubReleaseUpdater.normalizeRepoSlug("invalid-single-segment"))

        // 2. Semantic version comparison
        assertTrue(com.example.data.GithubReleaseUpdater.isRemoteVersionNewer("v1.1", "1.0", currentVersionCode = 1))
        assertTrue(com.example.data.GithubReleaseUpdater.isRemoteVersionNewer("v1.0.1", "1.0", currentVersionCode = 1))
        assertTrue(com.example.data.GithubReleaseUpdater.isRemoteVersionNewer("v2.0", "1.9.5", currentVersionCode = 1))
        assertFalse(com.example.data.GithubReleaseUpdater.isRemoteVersionNewer("v1.0", "1.0", currentVersionCode = 1))
        assertFalse(com.example.data.GithubReleaseUpdater.isRemoteVersionNewer("v0.9", "1.0", currentVersionCode = 1))

        // 3. End-to-end checkForLatestRelease + downloadReleaseApk against local HTTP server
        val fakeApkPayload = ByteArray(4096) { idx -> (idx % 251).toByte() }
        val serverSocket = java.net.ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        val port = serverSocket.localPort
        val releaseJson = JSONObject().apply {
            put("tag_name", "v1.2.0")
            put("name", "Kagojpotro v1.2.0 Update")
            put("body", "- Fixed PC QR code progress\n- Added 1-tap GitHub Updater")
            put("html_url", "https://github.com/hadi280994/kagojpotro/releases/tag/v1.2.0")
            put("published_at", "2026-11-15T12:00:00Z")
            put("assets", JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "Kagojpotro-v1.2.0.apk")
                    put("browser_download_url", "http://127.0.0.1:$port/download/Kagojpotro-v1.2.0.apk")
                    put("size", fakeApkPayload.size)
                })
            })
        }

        val serverThread = Thread {
            while (!serverSocket.isClosed) {
                val client = try {
                    serverSocket.accept()
                } catch (_: Exception) {
                    break
                }
                try {
                    client.soTimeout = 3000
                    val input = client.getInputStream()
                    val headerLines = mutableListOf<String>()
                    val lineBuf = ByteArrayOutputStream()
                    while (true) {
                        val b = input.read()
                        if (b == -1) break
                        if (b == '\n'.code) {
                            val line = lineBuf.toString("UTF-8").trimEnd('\r')
                            lineBuf.reset()
                            if (line.isEmpty()) break
                            headerLines.add(line)
                        } else {
                            lineBuf.write(b)
                        }
                    }
                    val reqLine = headerLines.firstOrNull().orEmpty()
                    val out = client.getOutputStream()
                    if (reqLine.contains("/releases/latest")) {
                        val bodyBytes = releaseJson.toString().toByteArray(Charsets.UTF_8)
                        val hdr = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
                        out.write(hdr.toByteArray(Charsets.US_ASCII))
                        out.write(bodyBytes)
                    } else if (reqLine.contains(".apk")) {
                        val hdr = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.android.package-archive\r\nContent-Length: ${fakeApkPayload.size}\r\nConnection: close\r\n\r\n"
                        out.write(hdr.toByteArray(Charsets.US_ASCII))
                        out.write(fakeApkPayload)
                    }
                    out.flush()
                } catch (_: Exception) {
                } finally {
                    runCatching { client.close() }
                }
            }
        }.apply { isDaemon = true; start() }

        try {
            val checkRes = repository.githubReleaseUpdater.checkForLatestRelease(
                rawRepoSlug = "hadi280994/kagojpotro",
                currentVersionName = "1.0",
                currentVersionCode = 1,
                apiBaseUrlOverride = "http://127.0.0.1:$port"
            )
            assertTrue("Expected UpdateAvailable, got $checkRes", checkRes is com.example.data.GithubUpdateCheckResult.UpdateAvailable)
            val info = (checkRes as com.example.data.GithubUpdateCheckResult.UpdateAvailable).info
            assertEquals("1.2.0", info.remoteVersionName)
            assertNotNull(info.apkAsset)
            assertEquals("Kagojpotro-v1.2.0.apk", info.apkAsset?.name)

            var lastPct = 0
            val downloadedFile = repository.githubReleaseUpdater.downloadReleaseApk(
                asset = info.apkAsset!!,
                versionTag = info.tagName
            ) { pct, _, _ ->
                lastPct = pct
            }.getOrThrow()

            assertEquals(100, lastPct)
            assertTrue(downloadedFile.exists())
            assertEquals(fakeApkPayload.size.toLong(), downloadedFile.length())
            downloadedFile.delete()
        } finally {
            serverSocket.close()
            serverThread.join(1000L)
        }
    }
}
