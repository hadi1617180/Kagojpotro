package com.example.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.AppLanguage
import com.example.data.AppStrings
import com.example.data.EraserStrokeRecord
import com.example.data.NormalizedQuad
import com.example.data.PdfPageFormat
import com.example.data.ScanEntity
import com.example.data.ScanFilter
import com.example.data.ScanOperationResult
import com.example.processing.DocumentImageProcessor
import com.example.processing.SmartDocumentEraser
import com.example.ui.components.CropOriginalOverlay
import com.example.ui.components.DeleteImagesConfirmDialog
import com.example.ui.components.EditorToolButton
import com.example.ui.components.FloatingOverlayIconButton
import com.example.ui.components.ImageNameDialog
import com.example.ui.components.UltraThinLineSlider
import com.example.ui.components.VerticalEraserHistoryColumn
import com.example.ui.components.ZoomableDocumentImage
import com.example.ui.components.compactFilterLabel
import com.example.ui.components.filterIconFor
import com.example.ui.theme.LocalGlassPalette
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.ObsidianDark
import com.example.ui.theme.ObsidianSurface
import com.example.util.HapticsAndShareHelper
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single Saved Image Preview & Editor:
 * - Slim top bar with Back button, file name (no page count subtitle), Download to Gallery button, and Share button.
 * - Full-Screen Swipe Pager with 2-Finger Pinch-to-Zoom & Pan, floating Rotate button at bottom-right
 *   (`Color.Black.copy(alpha = 0.35f)` translucent backdrop, no border/text), and vertical
 *   Undo / Redo / Reset icon buttons at bottom-left when Smart Eraser is active.
 * - Compact icon+text Filter selector with ultra-thin intensity slider (no "Filter Intensity" label).
 * - Unified bottom bar matching BatchReviewScreen, swapping Retake for Rename:
 *   1. Rename, 2. Crop, 3. Filters, 4. Smart Eraser, 5. Delete, 6. Save (icon-only accent box).
 */
@Composable
fun SingleImageScreen(
    scan: ScanEntity,
    folderScans: List<ScanEntity> = listOf(scan),
    isSavingEdit: Boolean,
    appLanguage: AppLanguage,
    strings: AppStrings,
    isAppLocked: Boolean = false,
    onBack: () -> Unit,
    onSelectScanId: (String) -> Unit = {},
    onEnsureHighResOnDemand: suspend (ScanEntity, Boolean) -> Boolean = { _, _ -> false },
    onSaveEditsForScan: (String, NormalizedQuad, Int, ScanFilter) -> Unit = { _, _, _, _ -> },
    onSaveEditsWithIntensityForScan: (String, NormalizedQuad, Int, ScanFilter, Float, List<EraserStrokeRecord>?) -> Unit = { id, quad, rot, filter, _, _ ->
        onSaveEditsForScan(id, quad, rot, filter)
    },
    onSaveEditedBitmapForScan: (String, Bitmap, List<EraserStrokeRecord>?) -> Unit = { _, _, _ -> },
    onRenameScan: suspend (String, String) -> ScanOperationResult = { _, _ -> ScanOperationResult.EmptyName },
    onDeleteScan: (String) -> Unit,
    onStatusToast: (String) -> Unit,
    onSuppressResumeLock: () -> Unit = {}
) {
    val context = LocalContext.current
    val view = LocalView.current
    val glass = LocalGlassPalette.current
    val scope = rememberCoroutineScope()
    val isBn = appLanguage == AppLanguage.BN

    val pages = remember(folderScans, scan) {
        if (folderScans.isNotEmpty() && folderScans.any { it.id == scan.id }) {
            folderScans
        } else {
            listOf(scan)
        }
    }
    val initialPageIndex = remember(pages, scan.id) {
        pages.indexOfFirst { it.id == scan.id }.coerceAtLeast(0)
    }

    val pagerState = rememberPagerState(
        initialPage = initialPageIndex,
        pageCount = { pages.size }
    )

    var isCurrentPageZoomed by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState.currentPage, pages) {
        isCurrentPageZoomed = false
        val currentPageScan = pages.getOrNull(pagerState.currentPage)
        if (currentPageScan != null && currentPageScan.id != scan.id) {
            onSelectScanId(currentPageScan.id)
        }
    }

    val activeScan = pages.getOrNull(pagerState.currentPage) ?: scan

    var showCropModal by remember { mutableStateOf(false) }
    var showFilterStrip by remember { mutableStateOf(false) }
    var showEraserBar by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showGallerySavedPopup by remember { mutableStateOf(false) }

    var eraserBrushRadius by remember { mutableFloatStateOf(0.022f) }
    var isDraggingBrushSlider by remember { mutableStateOf(false) }
    var isErasingInProgress by remember { mutableStateOf(false) }

    LaunchedEffect(showGallerySavedPopup) {
        if (showGallerySavedPopup) {
            delay(3000L)
            showGallerySavedPopup = false
        }
    }

    // Synchronous in-memory bitmap & optimistic edit caches per scanId so tapping Rotate/Filter/Crop/Eraser
    // sequentially never clobbers pending edits while disk/Room updates run asynchronously.
    val immediateBitmaps = remember { mutableStateMapOf<String, Bitmap>() }
    val immediateRotations = remember { mutableStateMapOf<String, Int>() }
    val immediateFilters = remember { mutableStateMapOf<String, ScanFilter>() }
    val immediateIntensities = remember { mutableStateMapOf<String, Float>() }
    val immediateQuads = remember { mutableStateMapOf<String, NormalizedQuad>() }

    // Per-page Smart Eraser Undo / Redo / Reset stacks (pixels + replayable stroke records)
    val eraserUndoStacks = remember { mutableStateMapOf<String, List<Bitmap>>() }
    val eraserRedoStacks = remember { mutableStateMapOf<String, List<Bitmap>>() }
    val eraserCleanBaseBitmaps = remember { mutableStateMapOf<String, Bitmap>() }
    val immediateEraserStrokes = remember { mutableStateMapOf<String, List<EraserStrokeRecord>>() }
    val immediateRedoEraserStrokes = remember { mutableStateMapOf<String, List<EraserStrokeRecord>>() }
    val eraserSessionBaseStrokeCounts = remember { mutableStateMapOf<String, Int>() }

    LaunchedEffect(
        activeScan.id,
        activeScan.modifiedAt,
        activeScan.scanFilter,
        activeScan.cropQuad,
        activeScan.rotationDegrees,
        activeScan.eraserStrokesJson
    ) {
        if (immediateFilters[activeScan.id] == activeScan.scanFilter) {
            immediateFilters.remove(activeScan.id)
        }
        if (immediateQuads[activeScan.id] == activeScan.cropQuad) {
            immediateQuads.remove(activeScan.id)
        }
        val normSavedRot = ((activeScan.rotationDegrees % 360) + 360) % 360
        val normImmRot = immediateRotations[activeScan.id]?.let { ((it % 360) + 360) % 360 }
        if (normImmRot == normSavedRot) {
            immediateRotations.remove(activeScan.id)
        }
        if (immediateEraserStrokes[activeScan.id] == activeScan.eraserStrokes) {
            immediateEraserStrokes.remove(activeScan.id)
        }
    }

    LaunchedEffect(pagerState.currentPage, pages) {
        val startIdx = (pagerState.currentPage - 1).coerceAtLeast(0)
        val endIdx = (pagerState.currentPage + 1).coerceAtMost((pages.size - 1).coerceAtLeast(0))
        val keepIds = if (pages.isEmpty()) emptySet() else (startIdx..endIdx).map { pages[it].id }.toSet()
        val staleKeys = immediateBitmaps.keys.filter { it !in keepIds }
        staleKeys.forEach {
            immediateBitmaps.remove(it)
            eraserUndoStacks.remove(it)
            eraserRedoStacks.remove(it)
            eraserCleanBaseBitmaps.remove(it)
            immediateEraserStrokes.remove(it)
            immediateRedoEraserStrokes.remove(it)
            eraserSessionBaseStrokeCounts.remove(it)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            immediateBitmaps.clear()
            immediateRotations.clear()
            immediateFilters.clear()
            immediateIntensities.clear()
            immediateQuads.clear()
            eraserUndoStacks.clear()
            eraserRedoStacks.clear()
            eraserCleanBaseBitmaps.clear()
            immediateEraserStrokes.clear()
            immediateRedoEraserStrokes.clear()
            eraserSessionBaseStrokeCounts.clear()
        }
    }

    val activeEffectiveRot = immediateRotations[activeScan.id] ?: activeScan.rotationDegrees
    val activeEffectiveFilter = immediateFilters[activeScan.id] ?: activeScan.scanFilter
    val activeEffectiveIntensity = immediateIntensities[activeScan.id] ?: 0.5f
    val activeEffectiveQuad = immediateQuads[activeScan.id] ?: activeScan.cropQuad

    val originalBitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = showCropModal,
        key2 = activeScan.id,
        key3 = activeScan.originalPath
    ) {
        if (!showCropModal) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            if (!File(activeScan.originalPath).exists()) {
                onEnsureHighResOnDemand(activeScan, true)
            }
            DocumentImageProcessor.decodeSampledBitmapWithExif(activeScan.originalPath, 2800)
                ?: DocumentImageProcessor.decodeSampledBitmapWithExif(activeScan.editedPath, 2800)
                ?: DocumentImageProcessor.decodeSampledBitmapWithExif(activeScan.thumbnailPath, 2800)
        }
    }

    BackHandler {
        when {
            showGallerySavedPopup -> showGallerySavedPopup = false
            showCropModal -> showCropModal = false
            showEraserBar -> showEraserBar = false
            showFilterStrip -> showFilterStrip = false
            else -> onBack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianDark)
            .testTag("single_image_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            // 1. Slim Top Bar with Back button, file name (no (*/*) count), Save/Download to Gallery, and Share
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ObsidianSurface)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = {
                        HapticsAndShareHelper.tapTick(view)
                        onBack()
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("single_image_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }

                Text(
                    text = com.example.data.stripImageExtension(activeScan.filename),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp)
                )

                // Download / Save to Gallery Button (triggers loud haptic vibration + Gallery Saved popup)
                IconButton(
                    onClick = {
                        HapticsAndShareHelper.loudGallerySaveVibration(view, context)
                        val targetScan = activeScan
                        scope.launch {
                            val (saved, msg) = withContext(Dispatchers.IO) {
                                HapticsAndShareHelper.saveScanToGallery(context, targetScan)
                            }
                            if (saved) {
                                showGallerySavedPopup = true
                            } else {
                                onStatusToast(msg)
                            }
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("single_image_download_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = strings.downloadFallback,
                        tint = Color.White
                    )
                }

                IconButton(
                    onClick = {
                        HapticsAndShareHelper.tapTick(view)
                        onSuppressResumeLock()
                        val targetScan = activeScan
                        scope.launch {
                            val shared = withContext(Dispatchers.IO) {
                                HapticsAndShareHelper.shareEditedScans(context, listOf(targetScan))
                            }
                            if (!shared) {
                                HapticsAndShareHelper.loudGallerySaveVibration(view, context)
                                val (saved, msg) = withContext(Dispatchers.IO) {
                                    HapticsAndShareHelper.saveScanToGallery(context, targetScan)
                                }
                                if (saved) {
                                    showGallerySavedPopup = true
                                } else {
                                    onStatusToast(msg)
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("single_image_share_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = strings.share,
                        tint = Color.White
                    )
                }
            }

            // 2. Full-Screen Horizontal Swipe Pager with 2-Finger Pinch-to-Zoom & Pan + Floating Overlays
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = !isCurrentPageZoomed && !showEraserBar,
                    key = { pageIdx -> pages[pageIdx].id },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("single_image_pager")
                ) { pageIdx ->
                    val pageScan = pages[pageIdx]
                    val diskBitmap by produceState<Bitmap?>(
                        initialValue = immediateBitmaps[pageScan.id],
                        key1 = pageScan.editedPath,
                        key2 = pageScan.modifiedAt
                    ) {
                        val editedExists = withContext(Dispatchers.IO) {
                            File(pageScan.editedPath).exists() && File(pageScan.editedPath).length() > 0L
                        }
                        if (editedExists) {
                            val decoded = withContext(Dispatchers.IO) {
                                DocumentImageProcessor.decodeSampledBitmapWithExif(pageScan.editedPath, 2800)
                            }
                            if (decoded != null && (immediateBitmaps[pageScan.id] == null ||
                                    immediateRotations[pageScan.id] == null ||
                                    immediateRotations[pageScan.id] == pageScan.rotationDegrees)
                            ) {
                                immediateBitmaps[pageScan.id] = decoded
                            }
                            value = decoded
                        } else {
                            // Immediately display cached preview offline when PC/Cloud copy is evicted
                            val previewBmp = withContext(Dispatchers.IO) {
                                DocumentImageProcessor.decodeSampledBitmapWithExif(pageScan.thumbnailPath, 2800)
                                    ?: DocumentImageProcessor.decodeSampledBitmapWithExif(pageScan.originalPath, 2800)
                            }
                            if (previewBmp != null && immediateBitmaps[pageScan.id] == null) {
                                immediateBitmaps[pageScan.id] = previewBmp
                            }
                            value = previewBmp

                            // If Office PC is reachable, restore full 4096px High-Res into Temp High-Res Cache
                            val restoredHighRes = onEnsureHighResOnDemand(pageScan, false)
                            if (restoredHighRes) {
                                val fullBmp = withContext(Dispatchers.IO) {
                                    DocumentImageProcessor.decodeSampledBitmapWithExif(pageScan.editedPath, 2800)
                                }
                                if (fullBmp != null) {
                                    immediateBitmaps[pageScan.id] = fullBmp
                                    value = fullBmp
                                }
                            }
                        }
                    }

                    val bmpToShow = immediateBitmaps[pageScan.id] ?: diskBitmap
                    val rotToShow = immediateRotations[pageScan.id] ?: pageScan.rotationDegrees

                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (bmpToShow != null) {
                            ZoomableDocumentImage(
                                bitmap = bmpToShow,
                                contentDescription = pageScan.filename,
                                rotationDegrees = rotToShow,
                                resetKey = pageScan.id,
                                isEraserActive = showEraserBar && pageIdx == pagerState.currentPage && !isSavingEdit,
                                normalizedBrushRadius = eraserBrushRadius,
                                isAdjustingBrushSize = isDraggingBrushSlider && pageIdx == pagerState.currentPage,
                                accentColor = glass.accentHighlight,
                                onEraseStrokeFinished = { strokePoints, normRadius ->
                                    if (strokePoints.isNotEmpty() && !isErasingInProgress) {
                                        HapticsAndShareHelper.tapTick(view)
                                        val targetId = pageScan.id
                                        val currentBmp = immediateBitmaps[targetId] ?: bmpToShow
                                        val prevStrokeRecords = immediateEraserStrokes[targetId] ?: pageScan.eraserStrokes
                                        if (eraserCleanBaseBitmaps[targetId] == null) {
                                            eraserCleanBaseBitmaps[targetId] = currentBmp
                                            eraserSessionBaseStrokeCounts[targetId] = prevStrokeRecords.size
                                        }
                                        val prevUndo = eraserUndoStacks[targetId].orEmpty()
                                        eraserUndoStacks[targetId] = prevUndo + currentBmp
                                        eraserRedoStacks.remove(targetId)
                                        val newStrokeRecord = EraserStrokeRecord(
                                            normalizedPoints = strokePoints.map { pt ->
                                                pt.x.coerceIn(0f, 1f) to pt.y.coerceIn(0f, 1f)
                                            },
                                            normalizedRadius = normRadius.coerceIn(0.005f, 0.08f)
                                        )
                                        val updatedStrokeRecords = prevStrokeRecords + newStrokeRecord
                                        immediateEraserStrokes[targetId] = updatedStrokeRecords
                                        immediateRedoEraserStrokes.remove(targetId)
                                        isErasingInProgress = true
                                        scope.launch {
                                            try {
                                                val healed = withContext(Dispatchers.Default) {
                                                    SmartDocumentEraser.eraseStroke(
                                                        source = currentBmp,
                                                        normalizedPoints = strokePoints,
                                                        normalizedRadius = normRadius
                                                    )
                                                }
                                                immediateBitmaps[targetId] = healed
                                                onSaveEditedBitmapForScan(targetId, healed, updatedStrokeRecords)
                                            } finally {
                                                isErasingInProgress = false
                                            }
                                        }
                                    }
                                },
                                onZoomStateChanged = { zoomed ->
                                    if (pageIdx == pagerState.currentPage) {
                                        isCurrentPageZoomed = zoomed
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag(
                                        if (pageIdx == pagerState.currentPage) {
                                            "single_image_zoom_canvas"
                                        } else {
                                            "single_image_zoom_canvas_$pageIdx"
                                        }
                                    )
                            )
                        } else {
                            CircularProgressIndicator(color = glass.accentHighlight)
                        }
                        if (isSavingEdit && pageIdx == pagerState.currentPage) {
                            Surface(
                                color = ObsidianSurface.copy(alpha = 0.90f),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        color = glass.accentHighlight,
                                        strokeWidth = 2.5.dp,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = strings.processingBadge,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }

                // Top-Center: Floating Page Indicator & Quick Previous/Next Stepper Pill when folder has multiple images
                if (pages.size > 1 && !showEraserBar) {
                    val canPrev = pagerState.currentPage > 0
                    val canNext = pagerState.currentPage < pages.lastIndex
                    Surface(
                        color = Color.Black.copy(alpha = 0.48f),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 6.dp)
                            .testTag("single_image_page_indicator")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.padding(horizontal = 4.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    if (canPrev) {
                                        HapticsAndShareHelper.tapTick(view)
                                        scope.launch {
                                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                        }
                                    }
                                },
                                enabled = canPrev,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("single_image_prev_page_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                    contentDescription = "Previous image",
                                    tint = if (canPrev) Color.White else Color.White.copy(alpha = 0.30f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Text(
                                text = "${pagerState.currentPage + 1} / ${pages.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )

                            IconButton(
                                onClick = {
                                    if (canNext) {
                                        HapticsAndShareHelper.tapTick(view)
                                        scope.launch {
                                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                        }
                                    }
                                },
                                enabled = canNext,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("single_image_next_page_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = "Next image",
                                    tint = if (canNext) Color.White else Color.White.copy(alpha = 0.30f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                // Bottom-Left: Vertically arranged Undo / Redo / Reset icon buttons (only when Smart Eraser is active)
                if (showEraserBar) {
                    val undoStack = eraserUndoStacks[activeScan.id].orEmpty()
                    val redoStack = eraserRedoStacks[activeScan.id].orEmpty()
                    val canUndo = undoStack.isNotEmpty() && !isErasingInProgress && !isSavingEdit
                    val canRedo = redoStack.isNotEmpty() && !isErasingInProgress && !isSavingEdit
                    val canReset = (undoStack.isNotEmpty() || redoStack.isNotEmpty()) &&
                        eraserCleanBaseBitmaps[activeScan.id] != null &&
                        !isErasingInProgress && !isSavingEdit

                    VerticalEraserHistoryColumn(
                        canUndo = canUndo,
                        canRedo = canRedo,
                        canReset = canReset,
                        onUndo = {
                            val stack = eraserUndoStacks[activeScan.id].orEmpty()
                            if (stack.isNotEmpty()) {
                                HapticsAndShareHelper.tapTick(view)
                                val popped = stack.last()
                                eraserUndoStacks[activeScan.id] = stack.dropLast(1)
                                immediateBitmaps[activeScan.id]?.let { curBmp ->
                                    eraserRedoStacks[activeScan.id] = eraserRedoStacks[activeScan.id].orEmpty() + curBmp
                                }
                                val curStrokes = immediateEraserStrokes[activeScan.id] ?: activeScan.eraserStrokes
                                val updatedStrokes = if (curStrokes.isNotEmpty()) {
                                    val poppedStroke = curStrokes.last()
                                    immediateRedoEraserStrokes[activeScan.id] =
                                        immediateRedoEraserStrokes[activeScan.id].orEmpty() + poppedStroke
                                    curStrokes.dropLast(1)
                                } else {
                                    curStrokes
                                }
                                immediateEraserStrokes[activeScan.id] = updatedStrokes
                                immediateBitmaps[activeScan.id] = popped
                                onSaveEditedBitmapForScan(activeScan.id, popped, updatedStrokes)
                            }
                        },
                        onRedo = {
                            val stack = eraserRedoStacks[activeScan.id].orEmpty()
                            if (stack.isNotEmpty()) {
                                HapticsAndShareHelper.tapTick(view)
                                val popped = stack.last()
                                eraserRedoStacks[activeScan.id] = stack.dropLast(1)
                                immediateBitmaps[activeScan.id]?.let { curBmp ->
                                    eraserUndoStacks[activeScan.id] = eraserUndoStacks[activeScan.id].orEmpty() + curBmp
                                }
                                val redoStrokes = immediateRedoEraserStrokes[activeScan.id].orEmpty()
                                val curStrokes = immediateEraserStrokes[activeScan.id] ?: activeScan.eraserStrokes
                                val updatedStrokes = if (redoStrokes.isNotEmpty()) {
                                    val restoredStroke = redoStrokes.last()
                                    immediateRedoEraserStrokes[activeScan.id] = redoStrokes.dropLast(1)
                                    curStrokes + restoredStroke
                                } else {
                                    curStrokes
                                }
                                immediateEraserStrokes[activeScan.id] = updatedStrokes
                                immediateBitmaps[activeScan.id] = popped
                                onSaveEditedBitmapForScan(activeScan.id, popped, updatedStrokes)
                            }
                        },
                        onReset = {
                            val cleanBase = eraserCleanBaseBitmaps[activeScan.id]
                            if (cleanBase != null) {
                                HapticsAndShareHelper.tapTick(view)
                                eraserUndoStacks.remove(activeScan.id)
                                eraserRedoStacks.remove(activeScan.id)
                                val baseCount = eraserSessionBaseStrokeCounts[activeScan.id] ?: 0
                                val curStrokes = immediateEraserStrokes[activeScan.id] ?: activeScan.eraserStrokes
                                val resetStrokes = curStrokes.take(baseCount)
                                immediateEraserStrokes[activeScan.id] = resetStrokes
                                immediateRedoEraserStrokes.remove(activeScan.id)
                                immediateBitmaps[activeScan.id] = cleanBase
                                onSaveEditedBitmapForScan(activeScan.id, cleanBase, resetStrokes)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 8.dp, bottom = 8.dp)
                    )
                }

                // Bottom-Right: Small floating Rotate icon button (no border or text, subtle dark translucent backdrop)
                FloatingOverlayIconButton(
                    icon = Icons.AutoMirrored.Filled.RotateRight,
                    contentDescription = strings.rotate,
                    testTag = "single_rotate_button",
                    enabled = !isSavingEdit && !isErasingInProgress,
                    onClick = {
                        HapticsAndShareHelper.tapTick(view)
                        val curRot = immediateRotations[activeScan.id] ?: activeScan.rotationDegrees
                        val nextRot = (curRot + 90) % 360
                        val currentBmp = immediateBitmaps[activeScan.id]
                        if (currentBmp != null) {
                            immediateBitmaps[activeScan.id] = DocumentImageProcessor.rotateBitmap(currentBmp, 90)
                        }
                        eraserUndoStacks[activeScan.id]?.let { list ->
                            eraserUndoStacks[activeScan.id] = list.map { DocumentImageProcessor.rotateBitmap(it, 90) }
                        }
                        eraserRedoStacks[activeScan.id]?.let { list ->
                            eraserRedoStacks[activeScan.id] = list.map { DocumentImageProcessor.rotateBitmap(it, 90) }
                        }
                        eraserCleanBaseBitmaps[activeScan.id]?.let { baseBmp ->
                            eraserCleanBaseBitmaps[activeScan.id] = DocumentImageProcessor.rotateBitmap(baseBmp, 90)
                        }
                        val curStrokes = immediateEraserStrokes[activeScan.id] ?: activeScan.eraserStrokes
                        val rotatedStrokes = curStrokes.map { it.rotateClockwise90() }
                        immediateEraserStrokes[activeScan.id] = rotatedStrokes
                        immediateRedoEraserStrokes[activeScan.id]?.let { list ->
                            immediateRedoEraserStrokes[activeScan.id] = list.map { it.rotateClockwise90() }
                        }
                        immediateRotations[activeScan.id] = nextRot
                        val curQuad = immediateQuads[activeScan.id] ?: activeScan.cropQuad
                        val curFilter = immediateFilters[activeScan.id] ?: activeScan.scanFilter
                        val curIntensity = immediateIntensities[activeScan.id] ?: 0.5f
                        onSaveEditsWithIntensityForScan(activeScan.id, curQuad, nextRot, curFilter, curIntensity, rotatedStrokes)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 8.dp, bottom = 8.dp)
                )
            }

            // 3A. Compact Filter Strip (Icon + Text only, ultra-thin intensity slider, no "Filter Intensity" label)
            if (showFilterStrip) {
                Surface(
                    color = ObsidianCard,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp, bottom = 6.dp)
                    ) {
                        var sliderIntensity by remember(activeScan.id, activeEffectiveFilter) {
                            mutableFloatStateOf(activeEffectiveIntensity)
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            UltraThinLineSlider(
                                value = sliderIntensity,
                                onValueChange = { newVal ->
                                    sliderIntensity = newVal
                                },
                                onValueChangeFinished = {
                                    immediateIntensities[activeScan.id] = sliderIntensity
                                    eraserUndoStacks.remove(activeScan.id)
                                    eraserRedoStacks.remove(activeScan.id)
                                    eraserCleanBaseBitmaps.remove(activeScan.id)
                                    eraserSessionBaseStrokeCounts.remove(activeScan.id)
                                    val curRot = immediateRotations[activeScan.id] ?: activeScan.rotationDegrees
                                    val curQuad = immediateQuads[activeScan.id] ?: activeScan.cropQuad
                                    val curFilter = immediateFilters[activeScan.id] ?: activeScan.scanFilter
                                    val curStrokes = immediateEraserStrokes[activeScan.id] ?: activeScan.eraserStrokes
                                    onSaveEditsWithIntensityForScan(
                                        activeScan.id,
                                        curQuad,
                                        curRot,
                                        curFilter,
                                        sliderIntensity,
                                        curStrokes
                                    )
                                },
                                valueRange = 0f..1f,
                                activeColor = glass.accentHighlight,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("single_filter_intensity_slider")
                            )
                        }

                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            items(ScanFilter.entries) { filter ->
                                val isChosen = activeEffectiveFilter == filter
                                val filterIcon = filterIconFor(filter)
                                val labelText = compactFilterLabel(filter, isBn)
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isChosen) glass.accent.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.05f),
                                    border = BorderStroke(
                                        width = if (isChosen) 1.5.dp else 1.dp,
                                        color = if (isChosen) glass.accentHighlight else Color.White.copy(alpha = 0.14f)
                                    ),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            HapticsAndShareHelper.tapTick(view)
                                            eraserUndoStacks.remove(activeScan.id)
                                            eraserRedoStacks.remove(activeScan.id)
                                            eraserCleanBaseBitmaps.remove(activeScan.id)
                                            eraserSessionBaseStrokeCounts.remove(activeScan.id)
                                            immediateFilters[activeScan.id] = filter
                                            val curRot = immediateRotations[activeScan.id] ?: activeScan.rotationDegrees
                                            val curQuad = immediateQuads[activeScan.id] ?: activeScan.cropQuad
                                            val curIntensity = immediateIntensities[activeScan.id] ?: sliderIntensity
                                            val curStrokes = immediateEraserStrokes[activeScan.id] ?: activeScan.eraserStrokes
                                            onSaveEditsWithIntensityForScan(
                                                activeScan.id,
                                                curQuad,
                                                curRot,
                                                filter,
                                                curIntensity,
                                                curStrokes
                                            )
                                        }
                                        .testTag("single_filter_${filter.name}")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = filterIcon,
                                            contentDescription = labelText,
                                            tint = if (isChosen) glass.accentHighlight else Color.White.copy(alpha = 0.85f),
                                            modifier = Modifier.size(17.dp)
                                        )
                                        Text(
                                            text = labelText,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (isChosen) glass.accentHighlight else Color.White,
                                            fontWeight = if (isChosen) FontWeight.Bold else FontWeight.Medium,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3B. Smart Eraser Active Window (same ultra-thin slider for brush size)
            if (showEraserBar) {
                Surface(
                    color = ObsidianCard,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("single_smart_eraser_strip")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        UltraThinLineSlider(
                            value = eraserBrushRadius,
                            onValueChange = { newRadius ->
                                isDraggingBrushSlider = true
                                eraserBrushRadius = newRadius
                            },
                            onValueChangeFinished = {
                                isDraggingBrushSlider = false
                            },
                            onDragStateChanged = { dragging ->
                                isDraggingBrushSlider = dragging
                            },
                            valueRange = 0.008f..0.060f,
                            activeColor = glass.accentHighlight,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("single_eraser_brush_size_slider")
                        )
                    }
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.10f))

            // 4. Unified Bottom Non-Destructive Editing Controls
            // Order: 1. Rename (swapped for Retake), 2. Crop, 3. Filters, 4. Smart Eraser, 5. Delete, 6. Save (Icon-only accent box)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ObsidianSurface)
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                EditorToolButton(
                    icon = Icons.Default.DriveFileRenameOutline,
                    label = strings.rename,
                    isActive = showRenameDialog,
                    testTag = "single_rename_button",
                    onClick = {
                        HapticsAndShareHelper.tapTick(view)
                        showRenameDialog = true
                    }
                )
                EditorToolButton(
                    icon = Icons.Default.Crop,
                    label = strings.cropOriginal,
                    isActive = showCropModal,
                    testTag = "single_crop_original_button",
                    onClick = {
                        HapticsAndShareHelper.tapTick(view)
                        showFilterStrip = false
                        showEraserBar = false
                        showCropModal = true
                    }
                )
                EditorToolButton(
                    icon = Icons.Default.AutoFixHigh,
                    label = strings.filters,
                    isActive = showFilterStrip,
                    testTag = "single_filters_button",
                    onClick = {
                        HapticsAndShareHelper.tapTick(view)
                        val next = !showFilterStrip
                        showFilterStrip = next
                        if (next) showEraserBar = false
                    }
                )
                EditorToolButton(
                    icon = Icons.Default.AutoFixNormal,
                    label = strings.smartEraser,
                    isActive = showEraserBar,
                    testTag = "single_smart_eraser_button",
                    onClick = {
                        HapticsAndShareHelper.tapTick(view)
                        val next = !showEraserBar
                        showEraserBar = next
                        if (next) showFilterStrip = false
                    }
                )
                EditorToolButton(
                    icon = Icons.Default.Delete,
                    label = strings.delete,
                    isActive = false,
                    tint = glass.dangerOnDark,
                    testTag = "single_delete_button",
                    onClick = {
                        HapticsAndShareHelper.tapTick(view)
                        showDeleteConfirm = true
                    }
                )

                // Save Button: Icon only inside an accent-colored box (matches Batch Review bottom bar)
                val canSave = !isSavingEdit && !isErasingInProgress
                Box(
                    modifier = Modifier
                        .size(width = 52.dp, height = 48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (canSave) glass.accent else glass.accent.copy(alpha = 0.42f)
                        )
                        .clickable(enabled = canSave) {
                            HapticsAndShareHelper.tapTick(view)
                            onBack()
                        }
                        .testTag("single_save_button"),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSavingEdit || isErasingInProgress) {
                        CircularProgressIndicator(
                            color = glass.onAccent,
                            strokeWidth = 2.2.dp,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = strings.saveAs,
                            tint = glass.onAccent,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        // Prominent "Saved to Gallery" Popup Confirmation
        AnimatedVisibility(
            visible = showGallerySavedPopup,
            enter = fadeIn() + scaleIn(initialScale = 0.88f),
            exit = fadeOut() + scaleOut(targetScale = 0.92f),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 28.dp)
        ) {
            Surface(
                color = ObsidianSurface.copy(alpha = 0.96f),
                shape = RoundedCornerShape(20.dp),
                shadowElevation = 18.dp,
                modifier = Modifier
                    .border(1.5.dp, glass.accentHighlight, RoundedCornerShape(20.dp))
                    .clickable { showGallerySavedPopup = false }
                    .testTag("gallery_saved_popup")
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(glass.accent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = glass.onAccent,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = strings.savedToGalleryTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = strings.savedToGalleryMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.88f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Non-Destructive Crop Modal (loads uncropped originalPath so previously hidden edges can be recovered)
        if (showCropModal && !isAppLocked) {
            val loadedOriginal = originalBitmap
            val hasOriginalOnDisk = remember(activeScan.id, activeScan.originalPath, loadedOriginal) {
                File(activeScan.originalPath).let { it.exists() && it.length() > 0L }
            }
            if (loadedOriginal != null) {
                CropOriginalOverlay(
                    originalBitmap = loadedOriginal,
                    initialQuad = if (hasOriginalOnDisk) activeEffectiveQuad else NormalizedQuad.FULL_FRAME,
                    strings = strings,
                    onCancel = { showCropModal = false },
                    onApplyCrop = { newQuad ->
                        showCropModal = false
                        eraserUndoStacks.remove(activeScan.id)
                        eraserRedoStacks.remove(activeScan.id)
                        eraserCleanBaseBitmaps.remove(activeScan.id)
                        eraserSessionBaseStrokeCounts.remove(activeScan.id)
                        immediateEraserStrokes[activeScan.id] = emptyList()
                        immediateRedoEraserStrokes.remove(activeScan.id)
                        immediateQuads[activeScan.id] = newQuad
                        val curRot = immediateRotations[activeScan.id] ?: activeScan.rotationDegrees
                        val curFilter = immediateFilters[activeScan.id] ?: activeScan.scanFilter
                        val curIntensity = immediateIntensities[activeScan.id] ?: 0.5f
                        onSaveEditsWithIntensityForScan(activeScan.id, newQuad, curRot, curFilter, curIntensity, emptyList())
                    }
                )
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.78f))
                        .clickable(enabled = false, onClick = {})
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = glass.accent)
                        Spacer(modifier = Modifier.height(14.dp))
                        TextButton(onClick = { showCropModal = false }) {
                            Text(text = strings.cancel, color = Color.White)
                        }
                    }
                }
            }
        }
    }

    if (showRenameDialog && !isAppLocked) {
        ImageNameDialog(
            initialFilename = activeScan.filename,
            strings = strings,
            onDismiss = { showRenameDialog = false },
            onSubmit = { newName ->
                onRenameScan(activeScan.id, newName)
            }
        )
    }

    if (showDeleteConfirm && !isAppLocked) {
        DeleteImagesConfirmDialog(
            selectedCount = 1,
            strings = strings,
            onCancel = { showDeleteConfirm = false },
            onConfirmDelete = {
                showDeleteConfirm = false
                onDeleteScan(activeScan.id)
            }
        )
    }
}

/**
 * Make PDF Screen:
 * - Shows numbered thumbnails of the selected images starting in their current gallery order
 * - Supports drag-to-reorder plus accessible move-up, move-down, and remove-page buttons
 * - Lets the user name the PDF and choose between Fit to Image and Standard A4
 * - Exports one page per image using the edited full-resolution result, with correct orientation,
 *   preserved aspect ratio, and no clipped content
 * - Shares or downloads the finished PDF while keeping all source images in their folders
 */
@Composable
fun PdfBuilderScreen(
    orderedScans: List<ScanEntity>,
    pdfFilename: String,
    pageFormat: PdfPageFormat,
    isExporting: Boolean,
    strings: AppStrings,
    onUpdateFilename: (String) -> Unit,
    onUpdatePageFormat: (PdfPageFormat) -> Unit,
    onMovePage: (Int, Int) -> Unit,
    onRemovePage: (Int) -> Unit = {},
    onExportAndShare: () -> Unit,
    onExportAndDownload: () -> Unit,
    onBack: () -> Unit
) {
    val view = LocalView.current
    val glass = com.example.ui.theme.LocalGlassPalette.current

    BackHandler {
        onBack()
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("pdf_builder_screen"),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(glass.glassHeaderFill)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        HapticsAndShareHelper.tapTick(view)
                        onBack()
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("pdf_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = glass.textPrimary
                    )
                }
                Icon(
                    imageVector = Icons.Default.PictureAsPdf,
                    contentDescription = null,
                    tint = glass.accent
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = strings.pdfScreenTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = glass.textPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            HorizontalDivider(color = glass.dividerColor)

            // PDF Filename & Page Format Config
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(glass.glassCardFill),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 680.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    OutlinedTextField(
                        value = pdfFilename,
                        onValueChange = onUpdateFilename,
                        label = { Text(strings.pdfFilenameLabel) },
                        placeholder = { Text("Kagojpotro_Document") },
                        singleLine = true,
                        suffix = { Text(".pdf", color = glass.textSecondary) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pdf_filename_input")
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilterChip(
                            selected = pageFormat == PdfPageFormat.FIT_TO_IMAGE,
                            onClick = {
                                HapticsAndShareHelper.tapTick(view)
                                onUpdatePageFormat(PdfPageFormat.FIT_TO_IMAGE)
                            },
                            label = {
                                Text(
                                    text = strings.pdfFitToImage,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = glass.inactiveButtonFill,
                                labelColor = glass.textPrimary,
                                selectedContainerColor = glass.accent,
                                selectedLabelColor = glass.onAccent
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = pageFormat == PdfPageFormat.FIT_TO_IMAGE,
                                borderColor = glass.inactiveButtonBorder,
                                selectedBorderColor = glass.accent
                            ),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("pdf_format_fit_chip")
                        )

                        FilterChip(
                            selected = pageFormat == PdfPageFormat.STANDARD_A4,
                            onClick = {
                                HapticsAndShareHelper.tapTick(view)
                                onUpdatePageFormat(PdfPageFormat.STANDARD_A4)
                            },
                            label = {
                                Text(
                                    text = strings.pdfStandardA4,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = glass.inactiveButtonFill,
                                labelColor = glass.textPrimary,
                                selectedContainerColor = glass.accent,
                                selectedLabelColor = glass.onAccent
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = pageFormat == PdfPageFormat.STANDARD_A4,
                                borderColor = glass.inactiveButtonBorder,
                                selectedBorderColor = glass.accent
                            ),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("pdf_format_a4_chip")
                        )
                    }
                }
            }

            HorizontalDivider(color = glass.dividerColor)

            // Reorderable Page List (Drag-to-reorder + Accessible Move Up / Move Down buttons)
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("pdf_page_order_list"),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(orderedScans, key = { _, scan -> scan.id }) { index, scan ->
                    var dragAccY by remember { mutableFloatStateOf(0f) }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(orderedScans.size, index) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        dragAccY = 0f
                                        HapticsAndShareHelper.longPressPulse(view, view.context)
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragAccY += dragAmount.y
                                        if (dragAccY > 110f && index < orderedScans.lastIndex) {
                                            onMovePage(index, index + 1)
                                            dragAccY = 0f
                                            HapticsAndShareHelper.tapTick(view)
                                        } else if (dragAccY < -110f && index > 0) {
                                            onMovePage(index, index - 1)
                                            dragAccY = 0f
                                            HapticsAndShareHelper.tapTick(view)
                                        }
                                    }
                                )
                            }
                            .testTag("pdf_page_card_$index"),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = glass.glassCardFill),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.DragIndicator,
                                contentDescription = "Drag to reorder",
                                tint = glass.textSecondary
                            )
                            Spacer(modifier = Modifier.width(8.dp))

                            // Page Number Badge
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(glass.accent),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = glass.onAccent,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            // Edited Image Thumbnail
                            AsyncImage(
                                model = File(scan.thumbnailPath),
                                contentDescription = "Page ${index + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(width = 56.dp, height = 74.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, glass.dividerColor, RoundedCornerShape(8.dp))
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (strings.lang == AppLanguage.BN) {
                                        "পৃষ্ঠা ${index + 1}"
                                    } else {
                                        "Page ${index + 1}"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = glass.textPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${scan.width} × ${scan.height} px · ${scan.scanFilter.label(strings.lang)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = glass.textSecondary
                                )
                            }

                            // Accessible Move Up / Move Down Actions
                            IconButton(
                                onClick = {
                                    HapticsAndShareHelper.tapTick(view)
                                    onMovePage(index, index - 1)
                                },
                                enabled = index > 0,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("pdf_move_up_$index")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowUpward,
                                    contentDescription = "Move page ${index + 1} up",
                                    tint = if (index > 0) glass.accent else glass.textSecondary.copy(alpha = 0.35f)
                                )
                            }

                            IconButton(
                                onClick = {
                                    HapticsAndShareHelper.tapTick(view)
                                    onMovePage(index, index + 1)
                                },
                                enabled = index < orderedScans.lastIndex,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("pdf_move_down_$index")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowDownward,
                                    contentDescription = "Move page ${index + 1} down",
                                    tint = if (index < orderedScans.lastIndex) glass.accent else glass.textSecondary.copy(alpha = 0.35f)
                                )
                            }

                            if (orderedScans.size > 1) {
                                IconButton(
                                    onClick = {
                                        HapticsAndShareHelper.tapTick(view)
                                        onRemovePage(index)
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("pdf_remove_page_$index")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove page ${index + 1} from PDF",
                                        tint = glass.textSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = glass.dividerColor)

            // Bottom Export & Share / Download Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(glass.glassCardFill),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .widthIn(max = 680.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            HapticsAndShareHelper.tapTick(view)
                            onExportAndDownload()
                        },
                        enabled = orderedScans.isNotEmpty() && !isExporting,
                        border = BorderStroke(1.dp, glass.inactiveButtonBorder),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 50.dp)
                            .testTag("pdf_download_button")
                    ) {
                        Icon(imageVector = Icons.Default.Download, contentDescription = null, tint = glass.accent)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = strings.downloadPdf,
                            color = glass.accent,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Button(
                        onClick = {
                            HapticsAndShareHelper.tapTick(view)
                            onExportAndShare()
                        },
                        enabled = orderedScans.isNotEmpty() && !isExporting,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = glass.accent,
                            contentColor = glass.onAccent
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 50.dp)
                            .testTag("pdf_export_share_button")
                    ) {
                        if (isExporting) {
                            CircularProgressIndicator(
                                color = glass.onAccent,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        } else {
                            Icon(imageVector = Icons.Default.Share, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = strings.exportAndSharePdf,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

