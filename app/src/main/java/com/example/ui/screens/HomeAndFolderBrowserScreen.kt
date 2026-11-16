package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.AppLanguage
import com.example.data.AppStorageSyncMode
import com.example.data.AppStrings
import com.example.data.BulkRenameResult
import com.example.data.FolderDeleteSummary
import com.example.data.FolderEntity
import com.example.data.FolderOperationResult
import com.example.data.FolderSortMode
import com.example.data.FolderViewMode
import com.example.data.ImageSortMode
import com.example.data.MultiFolderDeleteSummary
import com.example.data.PcSyncConfig
import com.example.data.PcSyncRuntimeStatus
import com.example.data.ScanEntity
import com.example.data.ScanOperationResult
import com.example.data.stripImageExtension
import com.example.ui.MoveSuccessBanner
import com.example.ui.UndoRenameBanner
import com.example.ui.UndoTrashBanner
import com.example.ui.components.AccentShadedFolderGridContent
import com.example.ui.components.AccentShadedFolderSvgGraphic
import com.example.ui.components.BulkRenameDialog
import com.example.ui.components.FOLDER_SVG_ASPECT_RATIO
import com.example.ui.components.DeleteImagesConfirmDialog
import com.example.ui.components.DeleteMultipleFoldersConfirmDialog
import com.example.ui.components.FolderNameDialog
import com.example.ui.components.ImageNameDialog
import com.example.ui.theme.LocalGlassPalette
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusRed
import com.example.util.HapticsAndShareHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class DateScanGroup(
    val dateKey: String,
    val dateHeaderTitle: String,
    val scans: List<ScanEntity>
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeAndFolderBrowserScreen(
    currentFolderId: String?,
    searchFolderPath: String?,
    isAppLocked: Boolean = false,
    allFolders: List<FolderEntity>,
    allScans: List<ScanEntity>,
    searchQuery: String,
    viewMode: FolderViewMode,
    folderSortMode: FolderSortMode,
    folderSortAscending: Boolean,
    imageSortMode: ImageSortMode,
    isSelectionMode: Boolean,
    selectedScanIds: Set<String>,
    appLanguage: AppLanguage,
    strings: AppStrings,
    pcSyncConfig: PcSyncConfig = PcSyncConfig(),
    pcSyncStatus: PcSyncRuntimeStatus = PcSyncRuntimeStatus(),
    moveSuccessBanner: MoveSuccessBanner?,
    undoTrashBanner: UndoTrashBanner? = null,
    undoRenameBanner: UndoRenameBanner? = null,
    statusToast: String?,
    matchesSearch: (String, String) -> Boolean,
    onUpdateSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onOpenFolder: (String?) -> Unit,
    onNavigateBack: () -> Boolean,
    onToggleViewMode: () -> Unit,
    onSetFolderSort: (FolderSortMode) -> Unit,
    onSetFolderSortAscending: (Boolean) -> Unit,
    onUpdateCustomFolderOrder: (List<String>) -> Unit,
    onSetImageSort: (ImageSortMode) -> Unit,
    onOpenSettings: () -> Unit,
    onCreateFolder: suspend (String, String?) -> FolderOperationResult,
    onRenameFolder: suspend (String, String) -> FolderOperationResult,
    onRenameScan: suspend (String, String) -> ScanOperationResult = { _, _ -> ScanOperationResult.EmptyName },
    onBulkRenameScans: suspend (Map<String, String>) -> BulkRenameResult = { BulkRenameResult.Error("") },
    onGetFolderDeleteSummary: suspend (String) -> FolderDeleteSummary?,
    onGetFoldersDeleteSummary: suspend (List<String>) -> MultiFolderDeleteSummary? = { null },
    onDeleteFolder: (String) -> Unit,
    onDeleteFolders: (List<String>) -> Unit = {},
    onStartCameraScan: () -> Unit,
    onImportDeviceUris: (List<android.net.Uri>) -> Unit,
    onThumbnailTapped: (ScanEntity) -> Unit,
    onThumbnailLongPressed: (ScanEntity) -> Unit,
    onSetScanSelection: (Set<String>) -> Unit = {},
    onClearSelection: () -> Unit,
    onOpenMovePicker: () -> Unit,
    onOpenPdfBuilder: (List<ScanEntity>) -> Unit,
    onDeleteSelectedImages: () -> Unit,
    onDismissMoveBanner: () -> Unit,
    onUndoTrash: () -> Unit = {},
    onDismissUndoTrash: () -> Unit = {},
    onUndoRename: () -> Unit = {},
    onDismissUndoRename: () -> Unit = {},
    onDismissStatusToast: () -> Unit,
    onShowStatusToast: (String) -> Unit,
    onSuppressResumeLock: () -> Unit = {}
) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val glass = LocalGlassPalette.current

    val folderMap = remember(allFolders) {
        allFolders.associateBy { it.id }
    }
    val folderItemCounts = remember(allFolders, allScans) {
        val counts = mutableMapOf<String, Int>()
        for (f in allFolders) {
            f.parentId?.let { pid -> counts[pid] = (counts[pid] ?: 0) + 1 }
        }
        for (s in allScans) {
            counts[s.folderId] = (counts[s.folderId] ?: 0) + 1
        }
        counts
    }
    val currentFolder = remember(currentFolderId, folderMap) {
        currentFolderId?.let { id -> folderMap[id] }
    }
    val isHome = currentFolder == null

    // Build folder chain for the interactive Breadcrumb Path Pill when inside a folder/subfolder
    val folderChain = remember(currentFolderId, allFolders) {
        if (currentFolderId == null) {
            emptyList()
        } else {
            val map = allFolders.associateBy { it.id }
            val list = mutableListOf<FolderEntity>()
            val visited = mutableSetOf<String>()
            var curr: String? = currentFolderId
            while (curr != null && visited.add(curr)) {
                val f = map[curr] ?: break
                list.add(f)
                curr = f.parentId
            }
            list.reversed()
        }
    }

    var isFabMenuExpanded by remember { mutableStateOf(false) }
    var showSortDropdown by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var renamingFolder by remember { mutableStateOf<FolderEntity?>(null) }
    var renamingScan by remember { mutableStateOf<ScanEntity?>(null) }
    var bulkRenamingScans by remember { mutableStateOf<List<ScanEntity>?>(null) }
    var deletingMultiFolderSummary by remember { mutableStateOf<MultiFolderDeleteSummary?>(null) }
    var showDeleteSelectedImagesDialog by remember { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris ->
        isFabMenuExpanded = false
        if (uris.isNotEmpty()) {
            onImportDeviceUris(uris)
        }
    }

    val isSearchActive = searchQuery.trim().isNotEmpty()

    // Filter & sort folders:
    // - On Homepage with active search: search all folders across the library.
    // - Otherwise: show direct children of currentFolderId matching search.
    val sortedFolders = remember(
        currentFolderId,
        isHome,
        isSearchActive,
        allFolders,
        searchQuery,
        folderSortMode,
        folderSortAscending
    ) {
        val raw = if (isHome && isSearchActive) {
            allFolders.filter { matchesSearch(it.name, searchQuery) }
        } else {
            allFolders
                .filter { it.parentId == currentFolderId }
                .filter { matchesSearch(it.name, searchQuery) }
        }

        val baseSorted = when (folderSortMode) {
            FolderSortMode.NAME -> raw.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            )
            FolderSortMode.LAST_MODIFIED -> raw.sortedBy { it.modifiedAt }
            FolderSortMode.LAST_CREATED -> raw.sortedBy { it.createdAt }
            FolderSortMode.CUSTOM -> raw.sortedWith(
                compareBy<FolderEntity> { it.customOrder }.thenBy { it.createdAt }
            )
        }
        if (folderSortAscending) baseSorted else baseSorted.reversed()
    }

    // Filter & sort images:
    // - Inside folder/subfolder: scans in currentFolderId filtered by image filename.
    // - On Homepage with active search: matching scans across the entire library.
    val displayedScans = remember(
        currentFolderId,
        isHome,
        isSearchActive,
        allScans,
        imageSortMode,
        searchQuery
    ) {
        val raw = when {
            isHome && isSearchActive -> {
                allScans.filter { matchesSearch(stripImageExtension(it.filename), searchQuery) }
            }
            isHome -> emptyList()
            else -> {
                allScans
                    .filter { it.folderId == currentFolderId }
                    .filter { matchesSearch(stripImageExtension(it.filename), searchQuery) }
            }
        }
        when (imageSortMode) {
            ImageSortMode.NEWEST_FIRST -> raw.sortedByDescending { it.capturedAt }
            ImageSortMode.OLDEST_FIRST -> raw.sortedBy { it.capturedAt }
        }
    }

    // Drag-and-drop + sticky selection state for folders
    var draggingFolder by remember { mutableStateOf<FolderEntity?>(null) }
    var collectedDragFolders by remember { mutableStateOf<List<FolderEntity>>(emptyList()) }
    var isStickyFolderSelection by remember { mutableStateOf(false) }
    var dragPointerInRoot by remember { mutableStateOf(Offset.Zero) }
    var dragTouchOffsetInCard by remember { mutableStateOf(Offset.Zero) }
    var draggedCardWidthPx by remember { mutableStateOf(0f) }
    var draggedCardHeightPx by remember { mutableStateOf(0f) }
    var rootBoxPositionInRoot by remember { mutableStateOf(Offset.Zero) }
    var renameDropBounds by remember { mutableStateOf<Rect?>(null) }
    var deleteDropBounds by remember { mutableStateOf<Rect?>(null) }
    val folderBoundsMap = remember { mutableStateMapOf<String, Rect>() }

    // Drag-and-drop + sticky selection state for images
    var draggingAnchorScan by remember { mutableStateOf<ScanEntity?>(null) }
    var imageDragPointerInRoot by remember { mutableStateOf(Offset.Zero) }
    var imageDragTouchOffsetInCard by remember { mutableStateOf(Offset.Zero) }
    var imageDraggedCardWidthPx by remember { mutableStateOf(0f) }
    val scanBoundsMap = remember { mutableStateMapOf<String, Rect>() }
    val imageActionBounds = remember { mutableStateMapOf<ImageBottomAction, Rect>() }

    LaunchedEffect(currentFolderId, viewMode) {
        folderBoundsMap.clear()
        scanBoundsMap.clear()
    }

    LaunchedEffect(currentFolderId) {
        draggingFolder = null
        collectedDragFolders = emptyList()
        isStickyFolderSelection = false
        draggingAnchorScan = null
    }

    val isFolderDragActive = draggingFolder != null && collectedDragFolders.isNotEmpty()
    val isFolderBarVisible = collectedDragFolders.isNotEmpty()
    val isMultiFolderDrag = collectedDragFolders.size >= 2
    val collectedDragFolderIds = remember(collectedDragFolders) {
        collectedDragFolders.map { it.id }.toSet()
    }

    val isImageDragActive = draggingAnchorScan != null
    val isImageBarVisible = isSelectionMode || isImageDragActive
    val isAnyDragActive = isFolderDragActive || isImageDragActive

    val currentSelectedScanIds by rememberUpdatedState(selectedScanIds)
    val currentIsSelectionMode by rememberUpdatedState(isSelectionMode)
    val currentDisplayedScans by rememberUpdatedState(displayedScans)

    // Local list used for instantaneous visual reordering during a single-folder Custom-mode drag
    var customDragFolders by remember { mutableStateOf<List<FolderEntity>?>(null) }
    var initialDragOrderIds by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(sortedFolders, draggingFolder) {
        if (draggingFolder == null) {
            customDragFolders = null
            val validIds = sortedFolders.map { it.id }.toSet()
            folderBoundsMap.keys.retainAll(validIds)
        }
    }
    LaunchedEffect(allFolders, isStickyFolderSelection) {
        if (isStickyFolderSelection) {
            val validById = allFolders.associateBy { it.id }
            val updated = collectedDragFolders.mapNotNull { validById[it.id] }
            collectedDragFolders = updated
            if (updated.isEmpty()) {
                isStickyFolderSelection = false
            }
        }
    }
    val displayedFolders = customDragFolders ?: sortedFolders

    // Group images by Date Created (latest date groups and latest images on top)
    val dateGroupedScans = remember(displayedScans, appLanguage) {
        if (displayedScans.isEmpty()) {
            emptyList()
        } else {
            val keyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val displayFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            val todayCal = Calendar.getInstance()
            val todayKey = keyFmt.format(todayCal.time)
            val yesterdayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            val yesterdayKey = keyFmt.format(yesterdayCal.time)

            val map = LinkedHashMap<String, MutableList<ScanEntity>>()
            for (scan in displayedScans) {
                val key = keyFmt.format(Date(scan.capturedAt))
                map.getOrPut(key) { mutableListOf() }.add(scan)
            }
            map.entries.map { (key, groupScans) ->
                val sampleDate = Date(groupScans.first().capturedAt)
                val formattedDate = displayFmt.format(sampleDate)
                val label = when (key) {
                    todayKey -> if (appLanguage == AppLanguage.BN) "আজ · $formattedDate" else "Today · $formattedDate"
                    yesterdayKey -> if (appLanguage == AppLanguage.BN) "গতকাল · $formattedDate" else "Yesterday · $formattedDate"
                    else -> formattedDate
                }
                DateScanGroup(
                    dateKey = key,
                    dateHeaderTitle = label,
                    scans = groupScans
                )
            }
        }
    }

    fun cancelFolderDragSession() {
        draggingFolder = null
        collectedDragFolders = emptyList()
        isStickyFolderSelection = false
        customDragFolders = null
        initialDragOrderIds = emptyList()
    }

    LaunchedEffect(isSelectionMode) {
        if (isSelectionMode) {
            cancelFolderDragSession()
        }
    }

    BackHandler(
        enabled = !isHome || isSearchActive || isSelectionMode || isFabMenuExpanded || isFolderBarVisible || isImageDragActive
    ) {
        when {
            isImageDragActive -> {
                draggingAnchorScan = null
            }
            isFolderBarVisible -> {
                cancelFolderDragSession()
            }
            isSelectionMode -> {
                onClearSelection()
            }
            isFabMenuExpanded -> {
                isFabMenuExpanded = false
            }
            isHome && isSearchActive -> {
                onClearSearch()
            }
            else -> {
                onNavigateBack()
            }
        }
    }

    val isHoveringRename = remember(draggingFolder, isMultiFolderDrag, dragPointerInRoot, renameDropBounds) {
        draggingFolder != null && !isMultiFolderDrag && renameDropBounds?.contains(dragPointerInRoot) == true
    }
    val isHoveringDelete = remember(draggingFolder, dragPointerInRoot, deleteDropBounds) {
        draggingFolder != null && deleteDropBounds?.contains(dragPointerInRoot) == true
    }

    val hoveredImageAction = remember(draggingAnchorScan, imageDragPointerInRoot, selectedScanIds) {
        if (draggingAnchorScan == null) {
            null
        } else {
            ImageBottomAction.entries.firstOrNull { action ->
                imageActionBounds[action]?.contains(imageDragPointerInRoot) == true
            }
        }
    }

    fun startFolderDragSession(
        folder: FolderEntity,
        startOffsetInCard: Offset,
        fallbackWidthDp: Float,
        fallbackHeightDp: Float,
        currentFolders: List<FolderEntity>
    ) {
        if (isSelectionMode || isImageDragActive) {
            onClearSelection()
            draggingAnchorScan = null
        }
        HapticsAndShareHelper.longPressPulse(view, context)
        val cardBounds = folderBoundsMap[folder.id]
        val existingSelection = if (isStickyFolderSelection && collectedDragFolders.any { it.id == folder.id }) {
            collectedDragFolders
        } else if (isStickyFolderSelection) {
            collectedDragFolders + folder
        } else {
            listOf(folder)
        }
        draggingFolder = folder
        collectedDragFolders = existingSelection
        isStickyFolderSelection = false
        initialDragOrderIds = currentFolders.map { it.id }
        customDragFolders = if (existingSelection.size == 1) currentFolders else null
        if (cardBounds != null) {
            draggedCardWidthPx = cardBounds.width
            draggedCardHeightPx = cardBounds.height
            dragTouchOffsetInCard = startOffsetInCard
            dragPointerInRoot = cardBounds.topLeft + startOffsetInCard
        } else {
            draggedCardWidthPx = with(density) { fallbackWidthDp.dp.toPx() }
            draggedCardHeightPx = with(density) { fallbackHeightDp.dp.toPx() }
            dragTouchOffsetInCard = Offset(draggedCardWidthPx * 0.5f, draggedCardHeightPx * 0.5f)
            dragPointerInRoot = startOffsetInCard
        }
    }

    fun joinFolderToDragSession(folder: FolderEntity, currentFolders: List<FolderEntity>) {
        if (draggingFolder == null) return
        if (collectedDragFolders.any { it.id == folder.id }) return
        HapticsAndShareHelper.tapTick(view)
        collectedDragFolders = collectedDragFolders + folder
        customDragFolders = currentFolders
    }

    fun toggleStickyFolderSelection(folder: FolderEntity) {
        HapticsAndShareHelper.tapTick(view)
        val exists = collectedDragFolders.any { it.id == folder.id }
        val next = if (exists) {
            collectedDragFolders.filterNot { it.id == folder.id }
        } else {
            collectedDragFolders + folder
        }
        collectedDragFolders = next
        if (next.isEmpty()) {
            isStickyFolderSelection = false
        }
    }

    fun handleFolderDragDelta(
        folder: FolderEntity,
        dragDelta: Offset,
        currentFolders: List<FolderEntity>,
        currentSort: FolderSortMode
    ) {
        dragPointerInRoot += dragDelta
        if (currentSort == FolderSortMode.CUSTOM && collectedDragFolders.size == 1) {
            val overBottomTargets =
                renameDropBounds?.contains(dragPointerInRoot) == true ||
                    deleteDropBounds?.contains(dragPointerInRoot) == true
            if (!overBottomTargets) {
                val activeList = customDragFolders ?: currentFolders
                val fromIndex = activeList.indexOfFirst { it.id == folder.id }
                val targetEntry = folderBoundsMap.entries.firstOrNull { (otherId, rect) ->
                    otherId != folder.id && rect.contains(dragPointerInRoot)
                }
                if (fromIndex != -1 && targetEntry != null) {
                    val toIndex = activeList.indexOfFirst { it.id == targetEntry.key }
                    if (toIndex != -1 && toIndex != fromIndex) {
                        val mutable = activeList.toMutableList()
                        val moved = mutable.removeAt(fromIndex)
                        mutable.add(toIndex, moved)
                        customDragFolders = mutable
                        HapticsAndShareHelper.tapTick(view)
                    }
                }
            }
        }
    }

    fun triggerDeleteForFolders(targetFolders: List<FolderEntity>) {
        if (targetFolders.isEmpty()) return
        val targetIds = targetFolders.map { it.id }
        scope.launch {
            deletingMultiFolderSummary = onGetFoldersDeleteSummary(targetIds)
                ?: if (targetIds.size == 1) {
                    onGetFolderDeleteSummary(targetIds.first())?.let { single ->
                        MultiFolderDeleteSummary(
                            folders = listOf(single.folder),
                            subfolderCount = single.subfolderCount,
                            totalImageCount = single.totalImageCount
                        )
                    }
                } else {
                    null
                }
        }
    }

    fun finishFolderDragSession(currentFolders: List<FolderEntity>, currentSort: FolderSortMode) {
        val finalOrderIds = (customDragFolders ?: currentFolders).map { it.id }
        val droppedFolders = collectedDragFolders
        val dropPoint = dragPointerInRoot
        draggingFolder = null
        customDragFolders = null

        if (droppedFolders.isEmpty()) {
            cancelFolderDragSession()
            return
        }

        val didCustomReorder = droppedFolders.size == 1 &&
            currentSort == FolderSortMode.CUSTOM &&
            finalOrderIds.isNotEmpty() &&
            finalOrderIds != initialDragOrderIds

        when {
            droppedFolders.size == 1 && renameDropBounds?.contains(dropPoint) == true -> {
                HapticsAndShareHelper.tapTick(view)
                renamingFolder = droppedFolders.first()
                cancelFolderDragSession()
            }
            deleteDropBounds?.contains(dropPoint) == true -> {
                HapticsAndShareHelper.tapTick(view)
                triggerDeleteForFolders(droppedFolders)
                cancelFolderDragSession()
            }
            didCustomReorder -> {
                onUpdateCustomFolderOrder(finalOrderIds)
                cancelFolderDragSession()
            }
            else -> {
                // Keep selected & keep bottom bar open for one-handed taps
                isStickyFolderSelection = true
                initialDragOrderIds = emptyList()
            }
        }
    }

    fun executeImageBottomAction(action: ImageBottomAction, targetScans: List<ScanEntity>) {
        if (targetScans.isEmpty()) return
        when (action) {
            ImageBottomAction.MOVE -> {
                HapticsAndShareHelper.tapTick(view)
                onOpenMovePicker()
            }
            ImageBottomAction.MAKE_PDF -> {
                HapticsAndShareHelper.tapTick(view)
                onOpenPdfBuilder(currentDisplayedScans)
            }
            ImageBottomAction.RENAME -> {
                HapticsAndShareHelper.tapTick(view)
                if (targetScans.size == 1) {
                    renamingScan = targetScans.first()
                    onClearSelection()
                } else if (targetScans.size > 1) {
                    bulkRenamingScans = targetScans
                }
            }
            ImageBottomAction.SHARE -> {
                HapticsAndShareHelper.tapTick(view)
                onSuppressResumeLock()
                val scansSnapshot = targetScans.toList()
                onClearSelection()
                scope.launch {
                    val launched = withContext(Dispatchers.IO) {
                        HapticsAndShareHelper.shareEditedScans(context, scansSnapshot)
                    }
                    if (!launched) {
                        HapticsAndShareHelper.loudGallerySaveVibration(view, context)
                        val (count, path) = withContext(Dispatchers.IO) {
                            HapticsAndShareHelper.downloadEditedScansToDevice(context, scansSnapshot)
                        }
                        val msg = if (count > 0) {
                            if (appLanguage == AppLanguage.BN) {
                                "${count}টি সম্পাদিত ছবি $path-এ সংরক্ষণ করা হয়েছে"
                            } else {
                                "Saved $count edited image(s) to $path"
                            }
                        } else {
                            if (appLanguage == AppLanguage.BN) "শেয়ার বা সংরক্ষণ করা যায়নি" else "Unable to share or save images"
                        }
                        onShowStatusToast(msg)
                    }
                }
            }
            ImageBottomAction.SAVE -> {
                HapticsAndShareHelper.loudGallerySaveVibration(view, context)
                val scansSnapshot = targetScans.toList()
                onClearSelection()
                scope.launch {
                    val (count, path) = withContext(Dispatchers.IO) {
                        HapticsAndShareHelper.downloadEditedScansToDevice(context, scansSnapshot)
                    }
                    val msg = if (count > 0) {
                        if (appLanguage == AppLanguage.BN) {
                            "${count}টি ছবি $path-এ সংরক্ষণ করা হয়েছে"
                        } else {
                            "Saved $count photo(s) to $path"
                        }
                    } else {
                        if (appLanguage == AppLanguage.BN) "গ্যালারিতে সংরক্ষণ করা যায়নি" else "Unable to save photo(s) to Gallery"
                    }
                    onShowStatusToast(msg)
                }
            }
            ImageBottomAction.DELETE -> {
                HapticsAndShareHelper.tapTick(view)
                showDeleteSelectedImagesDialog = true
            }
        }
    }

    fun startImageDragSession(
        scan: ScanEntity,
        startOffsetInCard: Offset,
        fallbackWidthDp: Float
    ) {
        if (isFolderBarVisible) {
            cancelFolderDragSession()
        }
        HapticsAndShareHelper.longPressPulse(view, context)
        val existing = currentSelectedScanIds
        val nextIds = when {
            scan.id in existing -> existing
            currentIsSelectionMode -> existing + scan.id
            else -> setOf(scan.id)
        }
        onSetScanSelection(nextIds)
        draggingAnchorScan = scan
        val cardBounds = scanBoundsMap[scan.id]
        if (cardBounds != null) {
            imageDraggedCardWidthPx = cardBounds.width
            imageDragTouchOffsetInCard = startOffsetInCard
            imageDragPointerInRoot = cardBounds.topLeft + startOffsetInCard
        } else {
            val fallbackW = with(density) { fallbackWidthDp.dp.toPx() }
            imageDraggedCardWidthPx = fallbackW
            imageDragTouchOffsetInCard = Offset(fallbackW * 0.5f, fallbackW * 0.6f)
            imageDragPointerInRoot = startOffsetInCard
        }
    }

    fun joinImageToDragSession(scan: ScanEntity) {
        if (draggingAnchorScan == null) return
        if (scan.id in currentSelectedScanIds) return
        HapticsAndShareHelper.tapTick(view)
        onSetScanSelection(currentSelectedScanIds + scan.id)
    }

    fun handleImageDragDelta(dragDelta: Offset) {
        imageDragPointerInRoot += dragDelta
    }

    fun finishImageDragSession() {
        val dropPoint = imageDragPointerInRoot
        val selectedScans = currentDisplayedScans.filter { it.id in currentSelectedScanIds }
        draggingAnchorScan = null

        val droppedAction = ImageBottomAction.entries.firstOrNull { action ->
            imageActionBounds[action]?.contains(dropPoint) == true
        }
        if (droppedAction != null && selectedScans.isNotEmpty()) {
            executeImageBottomAction(droppedAction, selectedScans)
        }
        // If released outside bottom action buttons, keep selected & keep 2x3 bottom bar open!
    }

    fun cancelImageDragSession() {
        draggingAnchorScan = null
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("home_and_folder_browser_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(glass.glassHeaderFill)
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                // 1. Main Header Row:
                // - On Homepage: Tapping Logo or "কাগজপত্র" title opens Settings. On the right: '+' button to create new folder.
                // - Inside Folder/Subfolder: Back button + folder title. If root folder (parentId == null), right side has '+' to create subfolder. If subfolder (parentId != null), no plus button. No settings button inside folders.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (isHome) {
                        // Homepage: Tapping Logo or "কাগজপত্র" text navigates to Settings!
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    HapticsAndShareHelper.tapTick(view)
                                    onOpenSettings()
                                }
                                .padding(vertical = 4.dp, horizontal = 4.dp)
                                .testTag("header_brand_settings_click")
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_paper_logo),
                                contentDescription = "Kagojpotro Logo",
                                tint = glass.accent,
                                modifier = Modifier
                                    .size(38.dp)
                                    .testTag("header_paper_logo")
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Text(
                                text = strings.appBrandTitle,
                                style = MaterialTheme.typography.headlineSmall,
                                color = glass.textPrimary,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("header_brand_title")
                            )

                            if (pcSyncConfig.mode == AppStorageSyncMode.PC_SERVER_SYNC) {
                                val isOnline = pcSyncStatus.isPcOnline
                                val lightColor by animateColorAsState(
                                    targetValue = if (isOnline) StatusGreen else StatusRed,
                                    label = "home_pc_dot_color"
                                )
                                val statusDesc = if (isOnline) "PC Server online" else "PC Server offline"
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(lightColor)
                                        .semantics { contentDescription = statusDesc }
                                        .testTag("home_pc_status_light")
                                )
                            }
                        }
                    } else {
                        // Inside Folder / Subfolder: Plain Borderless Back Arrow + Folder Title Only
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            IconButton(
                                onClick = {
                                    HapticsAndShareHelper.tapTick(view)
                                    onNavigateBack()
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("folder_header_back_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = glass.textPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Text(
                                text = currentFolder?.name ?: strings.appBrandTitle,
                                style = MaterialTheme.typography.headlineSmall,
                                color = glass.textPrimary,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("header_brand_title")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Top-Right Action Button:
                    // Only show '+' new folder button on Homepage or inside a root folder (parentId == null).
                    // Sub-subfolders are strictly disallowed, so hide the '+' button when inside a subfolder!
                    val canCreateFolderHere = isHome || currentFolder?.parentId == null
                    if (canCreateFolderHere) {
                        IconButton(
                            onClick = {
                                HapticsAndShareHelper.tapTick(view)
                                showNewFolderDialog = true
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("header_new_folder_top_button")
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(glass.glassCardFill)
                                    .border(1.2.dp, glass.accentGlassBorderBrush, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = strings.newFolder,
                                    tint = glass.accent,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }

                // 2. Search Bar + Sort & View Controls on the right (Single Row, uniform 44dp height & 8dp gap)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 1. Sort Icon (with popup menu) on the left
                    Box {
                        IconButton(
                            onClick = {
                                HapticsAndShareHelper.tapTick(view)
                                showSortDropdown = true
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(glass.glassCardFill)
                                .border(1.dp, glass.accentGlassBorderBrush, RoundedCornerShape(14.dp))
                                .testTag("folder_sort_menu_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Sort,
                                contentDescription = "Sort",
                                tint = glass.textPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showSortDropdown && !isAppLocked,
                            onDismissRequest = { showSortDropdown = false },
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = strings.sortByName,
                                        fontWeight = if (folderSortMode == FolderSortMode.NAME) FontWeight.Bold else FontWeight.Normal,
                                        color = if (folderSortMode == FolderSortMode.NAME) glass.accent else glass.textPrimary
                                    )
                                },
                                trailingIcon = {
                                    if (folderSortMode == FolderSortMode.NAME) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = glass.accent,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                onClick = {
                                    HapticsAndShareHelper.tapTick(view)
                                    onSetFolderSort(FolderSortMode.NAME)
                                    showSortDropdown = false
                                },
                                modifier = Modifier.testTag("sort_option_name")
                            )

                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = strings.sortByModified,
                                        fontWeight = if (folderSortMode == FolderSortMode.LAST_MODIFIED) FontWeight.Bold else FontWeight.Normal,
                                        color = if (folderSortMode == FolderSortMode.LAST_MODIFIED) glass.accent else glass.textPrimary
                                    )
                                },
                                trailingIcon = {
                                    if (folderSortMode == FolderSortMode.LAST_MODIFIED) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = glass.accent,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                onClick = {
                                    HapticsAndShareHelper.tapTick(view)
                                    onSetFolderSort(FolderSortMode.LAST_MODIFIED)
                                    showSortDropdown = false
                                },
                                modifier = Modifier.testTag("sort_option_modified")
                            )

                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = strings.sortByCreated,
                                        fontWeight = if (folderSortMode == FolderSortMode.LAST_CREATED) FontWeight.Bold else FontWeight.Normal,
                                        color = if (folderSortMode == FolderSortMode.LAST_CREATED) glass.accent else glass.textPrimary
                                    )
                                },
                                trailingIcon = {
                                    if (folderSortMode == FolderSortMode.LAST_CREATED) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = glass.accent,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                onClick = {
                                    HapticsAndShareHelper.tapTick(view)
                                    onSetFolderSort(FolderSortMode.LAST_CREATED)
                                    showSortDropdown = false
                                },
                                modifier = Modifier.testTag("sort_option_created")
                            )

                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = strings.sortByCustom,
                                        fontWeight = if (folderSortMode == FolderSortMode.CUSTOM) FontWeight.Bold else FontWeight.Normal,
                                        color = if (folderSortMode == FolderSortMode.CUSTOM) glass.accent else glass.textPrimary
                                    )
                                },
                                trailingIcon = {
                                    if (folderSortMode == FolderSortMode.CUSTOM) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = glass.accent,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                onClick = {
                                    HapticsAndShareHelper.tapTick(view)
                                    onSetFolderSort(FolderSortMode.CUSTOM)
                                    onSetFolderSortAscending(true)
                                    showSortDropdown = false
                                },
                                modifier = Modifier.testTag("sort_option_custom")
                            )

                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = glass.dividerColor
                            )

                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = strings.ascendingOrder,
                                        fontWeight = if (folderSortAscending) FontWeight.Bold else FontWeight.Normal,
                                        color = if (folderSortAscending) glass.accent else glass.textPrimary
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.ArrowUpward,
                                        contentDescription = null,
                                        tint = if (folderSortAscending) glass.accent else glass.textSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                trailingIcon = {
                                    if (folderSortAscending) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = glass.accent,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                onClick = {
                                    HapticsAndShareHelper.tapTick(view)
                                    onSetFolderSortAscending(true)
                                    showSortDropdown = false
                                },
                                modifier = Modifier.testTag("sort_option_ascending")
                            )

                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = strings.descendingOrder,
                                        fontWeight = if (!folderSortAscending) FontWeight.Bold else FontWeight.Normal,
                                        color = if (!folderSortAscending) glass.accent else glass.textPrimary
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.ArrowDownward,
                                        contentDescription = null,
                                        tint = if (!folderSortAscending) glass.accent else glass.textSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                trailingIcon = {
                                    if (!folderSortAscending) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = glass.accent,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                onClick = {
                                    HapticsAndShareHelper.tapTick(view)
                                    onSetFolderSortAscending(false)
                                    showSortDropdown = false
                                },
                                modifier = Modifier.testTag("sort_option_descending")
                            )

                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = glass.dividerColor
                            )

                            Text(
                                text = strings.imagesSortSection,
                                style = MaterialTheme.typography.labelSmall,
                                color = glass.textSecondary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )

                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = strings.newestFirst,
                                        fontWeight = if (imageSortMode == ImageSortMode.NEWEST_FIRST) FontWeight.Bold else FontWeight.Normal,
                                        color = if (imageSortMode == ImageSortMode.NEWEST_FIRST) glass.accent else glass.textPrimary
                                    )
                                },
                                trailingIcon = {
                                    if (imageSortMode == ImageSortMode.NEWEST_FIRST) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = glass.accent,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                onClick = {
                                    HapticsAndShareHelper.tapTick(view)
                                    onSetImageSort(ImageSortMode.NEWEST_FIRST)
                                    showSortDropdown = false
                                },
                                modifier = Modifier.testTag("sort_option_images_newest")
                            )

                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = strings.oldestFirst,
                                        fontWeight = if (imageSortMode == ImageSortMode.OLDEST_FIRST) FontWeight.Bold else FontWeight.Normal,
                                        color = if (imageSortMode == ImageSortMode.OLDEST_FIRST) glass.accent else glass.textPrimary
                                    )
                                },
                                trailingIcon = {
                                    if (imageSortMode == ImageSortMode.OLDEST_FIRST) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = glass.accent,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                onClick = {
                                    HapticsAndShareHelper.tapTick(view)
                                    onSetImageSort(ImageSortMode.OLDEST_FIRST)
                                    showSortDropdown = false
                                },
                                modifier = Modifier.testTag("sort_option_images_oldest")
                            )
                        }
                    }

                    // 2. Search Bar Box in the middle (weight(1f))
                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(glass.glassCardFill)
                            .border(1.dp, glass.accentGlassBorderBrush, RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = glass.accent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(modifier = Modifier.weight(1f)) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = strings.searchInFolderPlaceholder(searchFolderPath),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = glass.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                BasicTextField(
                                    value = searchQuery,
                                    onValueChange = onUpdateSearch,
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                                        color = glass.textPrimary
                                    ),
                                    cursorBrush = SolidColor(glass.accent),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("search_folders_input")
                                )
                            }
                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        HapticsAndShareHelper.tapTick(view)
                                        onClearSearch()
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("clear_search_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = strings.clearSearch,
                                        tint = glass.textSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 3. Grid / List View Toggle on the right
                    IconButton(
                        onClick = {
                            HapticsAndShareHelper.tapTick(view)
                            onToggleViewMode()
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(glass.glassCardFill)
                            .border(1.dp, glass.accentGlassBorderBrush, RoundedCornerShape(14.dp))
                            .testTag("view_mode_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (viewMode == FolderViewMode.GRID) {
                                Icons.AutoMirrored.Filled.ViewList
                            } else {
                                Icons.Default.GridView
                            },
                            contentDescription = if (viewMode == FolderViewMode.GRID) {
                                strings.listView
                            } else {
                                strings.gridView
                            },
                            tint = glass.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                HorizontalDivider(color = glass.dividerColor)
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .onGloballyPositioned { coords ->
                    rootBoxPositionInRoot = coords.positionInRoot()
                }
        ) {
            val isEmptyView = displayedFolders.isEmpty() && displayedScans.isEmpty()

            if (isEmptyView) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (searchQuery.trim().isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = glass.textSecondary,
                            modifier = Modifier.size(52.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = strings.noSearchMatches,
                            style = MaterialTheme.typography.titleMedium,
                            color = glass.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                HapticsAndShareHelper.tapTick(view)
                                onClearSearch()
                            }
                        ) {
                            Text(text = strings.clearSearch, color = glass.accent)
                        }
                    } else {
                        Image(
                            painter = painterResource(id = R.drawable.img_empty_archive_1794383404665),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 210.dp, height = 150.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .border(1.dp, glass.accentGlassBorderBrush, RoundedCornerShape(18.dp))
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (isHome) strings.emptyLibraryTitle else strings.emptyFolderTitle,
                            style = MaterialTheme.typography.titleLarge,
                            color = glass.textPrimary,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        if (isHome) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = strings.emptyLibrarySubtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = glass.textSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else if (isHome && !isSearchActive) {
                // HOMEPAGE VIEW (when not searching): 3-Column Glass Folder Grid or List
                val currentDisplayedFolders by rememberUpdatedState(displayedFolders)
                val currentSortMode by rememberUpdatedState(folderSortMode)

                val topListPadding by animateDpAsState(
                    targetValue = if (isFolderBarVisible || isImageBarVisible) 68.dp else 10.dp,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "homeTopPadding"
                )
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        userScrollEnabled = !isAnyDragActive,
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            top = topListPadding,
                            bottom = if (isFolderBarVisible) 128.dp else 112.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .widthIn(max = 840.dp)
                            .fillMaxSize()
                            .testTag("browser_grid")
                    ) {
                        // 3-Column Glass Folder Cards on Homepage
                        items(
                            items = displayedFolders,
                            key = { "folder_${it.id}" },
                            span = {
                                if (viewMode == FolderViewMode.LIST) {
                                    GridItemSpan(3)
                                } else {
                                    GridItemSpan(1)
                                }
                            }
                        ) { folder ->
                            val isSelectedHighlight = folder.id in collectedDragFolderIds
                            val isActivelyDragged = isFolderDragActive && isSelectedHighlight
                            FolderCardItem(
                                folder = folder,
                                itemCount = folderItemCounts[folder.id] ?: 0,
                                viewMode = viewMode,
                                isBeingDragged = isActivelyDragged,
                                isSelectedHighlight = isSelectedHighlight,
                                isMultiDragSourceActive = isFolderDragActive,
                                isInDragSelection = isSelectedHighlight,
                                modifier = Modifier.animateItem(
                                    fadeInSpec = null,
                                    fadeOutSpec = null,
                                    placementSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                ),
                                onBoundsChanged = { rect ->
                                    folderBoundsMap[folder.id] = rect
                                },
                                onOpen = {
                                    if (isStickyFolderSelection) {
                                        toggleStickyFolderSelection(folder)
                                    } else if (!isFolderDragActive) {
                                        HapticsAndShareHelper.tapTick(view)
                                        onOpenFolder(folder.id)
                                    }
                                },
                                onJoinDragSelection = {
                                    joinFolderToDragSession(folder, currentDisplayedFolders)
                                },
                                onDragStart = { startOffsetInCard ->
                                    startFolderDragSession(
                                        folder = folder,
                                        startOffsetInCard = startOffsetInCard,
                                        fallbackWidthDp = 112f,
                                        fallbackHeightDp = 58f,
                                        currentFolders = currentDisplayedFolders
                                    )
                                },
                                onDragBy = { dragDelta ->
                                    handleFolderDragDelta(
                                        folder = folder,
                                        dragDelta = dragDelta,
                                        currentFolders = currentDisplayedFolders,
                                        currentSort = currentSortMode
                                    )
                                },
                                onDragFinish = {
                                    finishFolderDragSession(currentDisplayedFolders, currentSortMode)
                                },
                                onDragCancel = {
                                    cancelFolderDragSession()
                                }
                            )
                        }
                    }
                }
            } else {
                // INSIDE FOLDER / SUBFOLDER VIEW OR HOMEPAGE SEARCH RESULTS:
                // 1. Folders / Subfolders in Grid (3-col on Home Search, 4-col in Folder) or List
                // 2. Sticky Date Headers + Images in 4-col Grid or Full-Width List
                val currentDisplayedFolders by rememberUpdatedState(displayedFolders)
                val currentSortMode by rememberUpdatedState(folderSortMode)

                val topListPadding by animateDpAsState(
                    targetValue = if (isFolderBarVisible || isImageBarVisible) 68.dp else 10.dp,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "folderTopPadding"
                )
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    LazyColumn(
                        userScrollEnabled = !isAnyDragActive,
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            top = topListPadding,
                            bottom = when {
                                isImageBarVisible -> 180.dp
                                isFolderBarVisible -> 128.dp
                                else -> 112.dp
                            }
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .widthIn(max = 720.dp)
                            .fillMaxSize()
                            .testTag("browser_grid")
                    ) {
                        // 1. Folders / Subfolders Section
                        if (displayedFolders.isNotEmpty()) {
                            item(key = "subfolders_glass_frame") {
                                if (isHome) {
                                    // Homepage Search Matching Folders (3-per-row Grid or List)
                                    val folderCols = if (viewMode == FolderViewMode.LIST) 1 else 3
                                    val folderRows = displayedFolders.chunked(folderCols)
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        for (rowFolders in folderRows) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                for (folder in rowFolders) {
                                                    val isSelectedHighlight = folder.id in collectedDragFolderIds
                                                    val isActivelyDragged = isFolderDragActive && isSelectedHighlight
                                                    FolderCardItem(
                                                        folder = folder,
                                                        itemCount = folderItemCounts[folder.id] ?: 0,
                                                        viewMode = viewMode,
                                                        isBeingDragged = isActivelyDragged,
                                                        isSelectedHighlight = isSelectedHighlight,
                                                        isMultiDragSourceActive = isFolderDragActive,
                                                        isInDragSelection = isSelectedHighlight,
                                                        modifier = Modifier.weight(1f),
                                                        onBoundsChanged = { rect -> folderBoundsMap[folder.id] = rect },
                                                        onOpen = {
                                                            if (isStickyFolderSelection) {
                                                                toggleStickyFolderSelection(folder)
                                                            } else if (!isFolderDragActive) {
                                                                HapticsAndShareHelper.tapTick(view)
                                                                onOpenFolder(folder.id)
                                                            }
                                                        },
                                                        onJoinDragSelection = {
                                                            joinFolderToDragSession(folder, currentDisplayedFolders)
                                                        },
                                                        onDragStart = { startOffsetInCard ->
                                                            startFolderDragSession(
                                                                folder = folder,
                                                                startOffsetInCard = startOffsetInCard,
                                                                fallbackWidthDp = 112f,
                                                                fallbackHeightDp = 58f,
                                                                currentFolders = currentDisplayedFolders
                                                            )
                                                        },
                                                        onDragBy = { dragDelta ->
                                                            handleFolderDragDelta(
                                                                folder = folder,
                                                                dragDelta = dragDelta,
                                                                currentFolders = currentDisplayedFolders,
                                                                currentSort = currentSortMode
                                                            )
                                                        },
                                                        onDragFinish = {
                                                            finishFolderDragSession(currentDisplayedFolders, currentSortMode)
                                                        },
                                                        onDragCancel = {
                                                            cancelFolderDragSession()
                                                        }
                                                    )
                                                }
                                                if (viewMode != FolderViewMode.LIST) {
                                                    for (i in 0 until (folderCols - rowFolders.size)) {
                                                        Spacer(modifier = Modifier.weight(1f))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    // Inside Folder: Compact Subfolders Framed at Top (4 per row Grid or List)
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(glass.glassSubfolderFrameFill)
                                            .border(1.dp, glass.accentGlassBorderBrush, RoundedCornerShape(16.dp))
                                            .padding(8.dp)
                                            .testTag("subfolders_top_frame")
                                    ) {
                                        val rows = if (viewMode == FolderViewMode.LIST) {
                                            displayedFolders.map { listOf(it) }
                                        } else {
                                            displayedFolders.chunked(4)
                                        }
                                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            for (rowFolders in rows) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    for (folder in rowFolders) {
                                                        val isSelectedHighlight = folder.id in collectedDragFolderIds
                                                        val isActivelyDragged = isFolderDragActive && isSelectedHighlight
                                                        if (viewMode == FolderViewMode.LIST) {
                                                            SubfolderListItem(
                                                                folder = folder,
                                                                itemCount = folderItemCounts[folder.id] ?: 0,
                                                                isBeingDragged = isActivelyDragged,
                                                                isSelectedHighlight = isSelectedHighlight,
                                                                isMultiDragSourceActive = isFolderDragActive,
                                                                isInDragSelection = isSelectedHighlight,
                                                                modifier = Modifier.fillMaxWidth(),
                                                                onBoundsChanged = { rect ->
                                                                    folderBoundsMap[folder.id] = rect
                                                                },
                                                                onOpen = {
                                                                    if (isStickyFolderSelection) {
                                                                        toggleStickyFolderSelection(folder)
                                                                    } else if (!isFolderDragActive) {
                                                                        HapticsAndShareHelper.tapTick(view)
                                                                        onOpenFolder(folder.id)
                                                                    }
                                                                },
                                                                onJoinDragSelection = {
                                                                    joinFolderToDragSession(folder, currentDisplayedFolders)
                                                                },
                                                                onDragStart = { startOffsetInCard ->
                                                                    startFolderDragSession(
                                                                        folder = folder,
                                                                        startOffsetInCard = startOffsetInCard,
                                                                        fallbackWidthDp = 200f,
                                                                        fallbackHeightDp = 44f,
                                                                        currentFolders = currentDisplayedFolders
                                                                    )
                                                                },
                                                                onDragBy = { dragDelta ->
                                                                    handleFolderDragDelta(
                                                                        folder = folder,
                                                                        dragDelta = dragDelta,
                                                                        currentFolders = currentDisplayedFolders,
                                                                        currentSort = currentSortMode
                                                                    )
                                                                },
                                                                onDragFinish = {
                                                                    finishFolderDragSession(currentDisplayedFolders, currentSortMode)
                                                                },
                                                                onDragCancel = {
                                                                    cancelFolderDragSession()
                                                                }
                                                            )
                                                        } else {
                                                            SubfolderMiniCardItem(
                                                                folder = folder,
                                                                isBeingDragged = isActivelyDragged,
                                                                isSelectedHighlight = isSelectedHighlight,
                                                                isMultiDragSourceActive = isFolderDragActive,
                                                                isInDragSelection = isSelectedHighlight,
                                                                modifier = Modifier.weight(1f),
                                                                onBoundsChanged = { rect ->
                                                                    folderBoundsMap[folder.id] = rect
                                                                },
                                                                onOpen = {
                                                                    if (isStickyFolderSelection) {
                                                                        toggleStickyFolderSelection(folder)
                                                                    } else if (!isFolderDragActive) {
                                                                        HapticsAndShareHelper.tapTick(view)
                                                                        onOpenFolder(folder.id)
                                                                    }
                                                                },
                                                                onJoinDragSelection = {
                                                                    joinFolderToDragSession(folder, currentDisplayedFolders)
                                                                },
                                                                onDragStart = { startOffsetInCard ->
                                                                    startFolderDragSession(
                                                                        folder = folder,
                                                                        startOffsetInCard = startOffsetInCard,
                                                                        fallbackWidthDp = 56f,
                                                                        fallbackHeightDp = 60f,
                                                                        currentFolders = currentDisplayedFolders
                                                                    )
                                                                },
                                                                onDragBy = { dragDelta ->
                                                                    handleFolderDragDelta(
                                                                        folder = folder,
                                                                        dragDelta = dragDelta,
                                                                        currentFolders = currentDisplayedFolders,
                                                                        currentSort = currentSortMode
                                                                    )
                                                                },
                                                                onDragFinish = {
                                                                    finishFolderDragSession(currentDisplayedFolders, currentSortMode)
                                                                },
                                                                onDragCancel = {
                                                                    cancelFolderDragSession()
                                                                }
                                                            )
                                                        }
                                                    }
                                                    if (viewMode != FolderViewMode.LIST) {
                                                        for (i in 0 until (4 - rowFolders.size)) {
                                                            Spacer(modifier = Modifier.weight(1f))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 2. Sticky Date Headers + Images (4-per-row Grid OR Full-Width List)
                        for (group in dateGroupedScans) {
                            stickyHeader(key = "date_header_${group.dateKey}") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(glass.glassStickyDateFill)
                                        .border(1.dp, glass.accentGlassBorderBrush, RoundedCornerShape(12.dp))
                                        .padding(horizontal = 12.dp, vertical = 7.dp)
                                        .testTag("date_group_header_${group.dateKey}")
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.CalendarToday,
                                                contentDescription = null,
                                                tint = glass.accent,
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = group.dateHeaderTitle,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = glass.textPrimary,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Text(
                                            text = strings.imagesCountSummary(group.scans.size),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = glass.accent,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }

                            if (viewMode == FolderViewMode.GRID) {
                                val scanRows = group.scans.chunked(4)
                                items(
                                    items = scanRows,
                                    key = { row -> "scan_grid_row_${group.dateKey}_${row.first().id}" }
                                ) { rowScans ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        for (scan in rowScans) {
                                            val isSelected = scan.id in selectedScanIds
                                            val isActivelyDragged = isImageDragActive && isSelected
                                            GridScanCardItem(
                                                scan = scan,
                                                isSelected = isSelected,
                                                isBeingDragged = isActivelyDragged,
                                                isImageDragActive = isImageDragActive,
                                                parentFolderName = if (isHome && isSearchActive) folderMap[scan.folderId]?.name else null,
                                                modifier = Modifier.weight(1f),
                                                onBoundsChanged = { rect -> scanBoundsMap[scan.id] = rect },
                                                onTap = {
                                                    if (!isImageDragActive) {
                                                        cancelFolderDragSession()
                                                        HapticsAndShareHelper.tapTick(view)
                                                        onThumbnailTapped(scan)
                                                    }
                                                },
                                                onJoinDragSelection = { joinImageToDragSession(scan) },
                                                onDragStart = { startOffset ->
                                                    startImageDragSession(
                                                        scan = scan,
                                                        startOffsetInCard = startOffset,
                                                        fallbackWidthDp = 82f
                                                    )
                                                },
                                                onDragBy = { delta -> handleImageDragDelta(delta) },
                                                onDragFinish = { finishImageDragSession() },
                                                onDragCancel = { cancelImageDragSession() }
                                            )
                                        }
                                        for (i in 0 until (4 - rowScans.size)) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            } else {
                                items(
                                    items = group.scans,
                                    key = { "scan_${it.id}" }
                                ) { scan ->
                                    val isSelected = scan.id in selectedScanIds
                                    val isActivelyDragged = isImageDragActive && isSelected
                                    val isLastInGroup = group.scans.lastOrNull()?.id == scan.id
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        ListScanCardItem(
                                            scan = scan,
                                            isSelected = isSelected,
                                            isBeingDragged = isActivelyDragged,
                                            isImageDragActive = isImageDragActive,
                                            parentFolderName = if (isHome && isSearchActive) folderMap[scan.folderId]?.name else null,
                                            onBoundsChanged = { rect -> scanBoundsMap[scan.id] = rect },
                                            onTap = {
                                                if (!isImageDragActive) {
                                                    cancelFolderDragSession()
                                                    HapticsAndShareHelper.tapTick(view)
                                                    onThumbnailTapped(scan)
                                                }
                                            },
                                            onJoinDragSelection = { joinImageToDragSession(scan) },
                                            onDragStart = { startOffset ->
                                                startImageDragSession(
                                                    scan = scan,
                                                    startOffsetInCard = startOffset,
                                                    fallbackWidthDp = 180f
                                                )
                                            },
                                            onDragBy = { delta -> handleImageDragDelta(delta) },
                                            onDragFinish = { finishImageDragSession() },
                                            onDragCancel = { cancelImageDragSession() }
                                        )
                                        if (!isLastInGroup) {
                                            Spacer(modifier = Modifier.height(10.dp))
                                            Box(
                                                modifier = Modifier.fillMaxWidth(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                HorizontalDivider(
                                                    thickness = 1.dp,
                                                    color = glass.dividerColor.copy(alpha = 0.5f),
                                                    modifier = Modifier.fillMaxWidth(0.70f)
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(6.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Top Floating Drag / Sticky Selection Pill (with `All` and `Clear` pills when released)
            val topPillText = when {
                isFolderDragActive && isMultiFolderDrag -> strings.dragInstructionMulti(collectedDragFolders.size)
                isFolderDragActive -> strings.dragInstructionSingle(folderSortMode == FolderSortMode.CUSTOM)
                isStickyFolderSelection -> strings.folderStickyInstruction(collectedDragFolders.size)
                isImageBarVisible -> strings.imageDragInstruction(
                    selectedCount = selectedScanIds.size.coerceAtLeast(if (isImageDragActive) 1 else 0),
                    isSticky = !isImageDragActive
                )
                else -> ""
            }
            val canSelectAllFolders = isStickyFolderSelection && !isFolderDragActive &&
                collectedDragFolders.size < displayedFolders.size
            val canSelectAllScans = isSelectionMode && !isImageDragActive &&
                selectedScanIds.size < displayedScans.size
            SelectionTopFloatingPill(
                visible = isFolderBarVisible || isImageBarVisible,
                text = topPillText,
                showClearButton = (isStickyFolderSelection && !isFolderDragActive) ||
                    (isSelectionMode && !isImageDragActive),
                clearLabel = strings.clear,
                isMultiAlert = (isFolderBarVisible && isMultiFolderDrag) ||
                    (isImageBarVisible && selectedScanIds.size >= 2),
                onClear = {
                    HapticsAndShareHelper.tapTick(view)
                    if (isFolderBarVisible) cancelFolderDragSession()
                    if (isImageBarVisible) {
                        cancelImageDragSession()
                        onClearSelection()
                    }
                },
                showSelectAllButton = canSelectAllFolders || canSelectAllScans,
                selectAllLabel = strings.selectAllShort,
                onSelectAll = {
                    HapticsAndShareHelper.tapTick(view)
                    if (canSelectAllFolders) {
                        collectedDragFolders = displayedFolders.toList()
                    } else if (canSelectAllScans) {
                        onSetScanSelection(displayedScans.map { it.id }.toSet())
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp, start = 16.dp, end = 16.dp)
            )

            // Folder Bottom Bar (Drag-drop OR released sticky taps)
            FolderDragAndStickyBottomBar(
                visible = isFolderBarVisible && !isImageBarVisible,
                selectedCount = collectedDragFolders.size,
                isDragging = isFolderDragActive,
                isHoveringRename = isHoveringRename,
                isHoveringDelete = isHoveringDelete,
                strings = strings,
                onRenameBoundsChanged = { renameDropBounds = it },
                onDeleteBoundsChanged = { deleteDropBounds = it },
                onRenameClicked = {
                    if (collectedDragFolders.size == 1) {
                        HapticsAndShareHelper.tapTick(view)
                        renamingFolder = collectedDragFolders.first()
                        cancelFolderDragSession()
                    }
                },
                onDeleteClicked = {
                    if (collectedDragFolders.isNotEmpty()) {
                        HapticsAndShareHelper.tapTick(view)
                        val targets = collectedDragFolders.toList()
                        cancelFolderDragSession()
                        triggerDeleteForFolders(targets)
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            // Image 2x3 Action Bottom Bar (Drag-drop OR released sticky taps)
            ImageDragAndStickyBottomBar(
                visible = isImageBarVisible,
                selectedCount = selectedScanIds.size,
                isDragging = isImageDragActive,
                hoveredAction = hoveredImageAction,
                strings = strings,
                onActionBoundsChanged = { action, rect -> imageActionBounds[action] = rect },
                onActionClicked = { action ->
                    val targets = currentDisplayedScans.filter { it.id in currentSelectedScanIds }
                    executeImageBottomAction(action, targets)
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            // Floating dragged folder card stack preview
            val dragZoomScale by animateFloatAsState(
                targetValue = if (isFolderDragActive) 1.08f else 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMedium
                ),
                label = "dragZoomScale"
            )

            draggingFolder?.let { dragged ->
                val widthDp = with(density) {
                    if (draggedCardWidthPx > 10f) draggedCardWidthPx.toDp().coerceAtMost(132.dp) else 112.dp
                }
                val localX = (dragPointerInRoot.x - rootBoxPositionInRoot.x - dragTouchOffsetInCard.x).roundToInt()
                val localY = (dragPointerInRoot.y - rootBoxPositionInRoot.y - dragTouchOffsetInCard.y).roundToInt()
                val formattedDate = remember(dragged.modifiedAt) {
                    SimpleDateFormat("MMM d, yy", Locale.getDefault()).format(Date(dragged.modifiedAt))
                }
                val stackCount = collectedDragFolders.size.coerceAtLeast(1)

                Box(
                    modifier = Modifier
                        .offset { IntOffset(localX, localY) }
                        .width(widthDp)
                        .scale(dragZoomScale)
                ) {
                    if (stackCount >= 3) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .offset(x = (-7).dp, y = (-5).dp)
                                .rotate(-5f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(glass.glassCardFill.copy(alpha = 0.85f))
                                .border(1.2.dp, glass.accent.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                        )
                    }
                    if (stackCount >= 2) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .offset(x = 5.dp, y = (-3).dp)
                                .rotate(4f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(glass.glassCardFill.copy(alpha = 0.9f))
                                .border(1.2.dp, glass.accent.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                        )
                    }

                    if (isHome) {
                        AccentShadedFolderGridContent(
                            folderName = dragged.name,
                            formattedDate = formattedDate,
                            highlightBorder = true
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            AccentShadedFolderSvgGraphic(
                                accentColor = glass.accent,
                                highlightBorder = true,
                                modifier = Modifier
                                    .fillMaxWidth(0.76f)
                                    .aspectRatio(FOLDER_SVG_ASPECT_RATIO)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = dragged.name,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.5.sp,
                                    lineHeight = 13.sp
                                ),
                                color = glass.textPrimary,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    if (stackCount >= 2) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 8.dp, y = (-8).dp)
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(glass.accent)
                                .border(1.5.dp, Color.White, CircleShape)
                                .testTag("drag_selection_count_badge")
                        ) {
                            Text(
                                text = stackCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = glass.onAccent,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Floating dragged image stack preview
            draggingAnchorScan?.let { anchorScan ->
                val widthDp = with(density) {
                    if (imageDraggedCardWidthPx > 10f) {
                        imageDraggedCardWidthPx.toDp().coerceIn(82.dp, 124.dp)
                    } else {
                        92.dp
                    }
                }
                val localX = (imageDragPointerInRoot.x - rootBoxPositionInRoot.x - imageDragTouchOffsetInCard.x).roundToInt()
                val localY = (imageDragPointerInRoot.y - rootBoxPositionInRoot.y - imageDragTouchOffsetInCard.y).roundToInt()
                FloatingDraggedScanStackOverlay(
                    anchorScan = anchorScan,
                    stackCount = selectedScanIds.size.coerceAtLeast(1),
                    widthDp = widthDp,
                    localX = localX,
                    localY = localY
                )
            }

            // FAB Menu Scrim
            AnimatedVisibility(
                visible = isFabMenuExpanded,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.38f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { isFabMenuExpanded = false }
                        )
                )
            }

            // Move success banner auto-dismiss after 3 seconds (statusToast is auto-dismissed globally in MainActivity)

            LaunchedEffect(moveSuccessBanner) {
                if (moveSuccessBanner != null) {
                    delay(3000L)
                    onDismissMoveBanner()
                }
            }

            // Move Success Banner with "View" action that opens the destination folder
            AnimatedVisibility(
                visible = moveSuccessBanner != null && !isFolderBarVisible && !isImageBarVisible,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 88.dp, bottom = 20.dp)
            ) {
                moveSuccessBanner?.let { banner ->
                    Surface(
                        color = ObsidianSurface.copy(alpha = 0.94f),
                        shape = RoundedCornerShape(14.dp),
                        shadowElevation = 6.dp,
                        modifier = Modifier.testTag("move_success_snackbar")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = banner.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(
                                onClick = {
                                    HapticsAndShareHelper.tapTick(view)
                                    val destId = banner.destinationFolderId
                                    onDismissMoveBanner()
                                    onOpenFolder(destId)
                                },
                                modifier = Modifier.testTag("move_banner_view_button")
                            ) {
                                Text(
                                    text = strings.viewAction,
                                    color = glass.accentHighlight,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // Quick Undo Trash Banner (appears for 6s after moving folders/scans to 7-day Trash)
            AnimatedVisibility(
                visible = undoTrashBanner != null && moveSuccessBanner == null && !isFolderBarVisible && !isImageBarVisible,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 88.dp, bottom = 20.dp)
            ) {
                undoTrashBanner?.let { banner ->
                    Surface(
                        color = ObsidianSurface.copy(alpha = 0.95f),
                        shape = RoundedCornerShape(14.dp),
                        shadowElevation = 8.dp,
                        modifier = Modifier.testTag("undo_trash_banner")
                    ) {
                        Row(
                            modifier = Modifier
                                .clickable { onDismissUndoTrash() }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = banner.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(
                                onClick = {
                                    HapticsAndShareHelper.performConfirm(view)
                                    onUndoTrash()
                                },
                                modifier = Modifier.testTag("undo_trash_button")
                            ) {
                                Text(
                                    text = strings.undo,
                                    color = glass.accentHighlight,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // Quick Undo Bulk Rename Banner (appears for 6s after bulk rename)
            AnimatedVisibility(
                visible = undoRenameBanner != null && undoTrashBanner == null && moveSuccessBanner == null && !isFolderBarVisible && !isImageBarVisible,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 88.dp, bottom = 20.dp)
            ) {
                undoRenameBanner?.let { banner ->
                    Surface(
                        color = ObsidianSurface.copy(alpha = 0.95f),
                        shape = RoundedCornerShape(14.dp),
                        shadowElevation = 8.dp,
                        modifier = Modifier.testTag("undo_rename_banner")
                    ) {
                        Row(
                            modifier = Modifier
                                .clickable { onDismissUndoRename() }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = banner.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(
                                onClick = {
                                    HapticsAndShareHelper.performConfirm(view)
                                    onUndoRename()
                                },
                                modifier = Modifier.testTag("undo_rename_button")
                            ) {
                                Text(
                                    text = strings.undo,
                                    color = glass.accentHighlight,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // General Status Toast
            AnimatedVisibility(
                visible = statusToast != null && moveSuccessBanner == null && undoTrashBanner == null && undoRenameBanner == null && !isFolderBarVisible && !isImageBarVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 88.dp, bottom = 20.dp)
            ) {
                statusToast?.let { msg ->
                    Surface(
                        color = ObsidianSurface.copy(alpha = 0.94f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.clickable { onDismissStatusToast() }
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }
            }

            // Bottom-Right Capture FAB ("Scan or import") — only Scan image(s) and Import from gallery/device
            if (!isFolderBarVisible && !isImageBarVisible) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(18.dp)
                ) {
                    AnimatedVisibility(
                        visible = isFabMenuExpanded,
                        enter = slideInVertically { it / 2 } + fadeIn(),
                        exit = slideOutVertically { it / 2 } + fadeOut()
                    ) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Action 1: Scan image(s)
                            ExtendedFloatingActionButton(
                                onClick = {
                                    HapticsAndShareHelper.tapTick(view)
                                    isFabMenuExpanded = false
                                    onStartCameraScan()
                                },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.CameraAlt,
                                        contentDescription = null
                                    )
                                },
                                text = {
                                    Text(
                                        text = strings.scanImages,
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                containerColor = glass.accent,
                                contentColor = glass.onAccent,
                                modifier = Modifier.testTag("fab_action_scan_images")
                            )

                            // Action 2: Import from gallery / device
                            ExtendedFloatingActionButton(
                                onClick = {
                                    HapticsAndShareHelper.tapTick(view)
                                    onSuppressResumeLock()
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.PhotoLibrary,
                                        contentDescription = null
                                    )
                                },
                                text = {
                                    Text(
                                        text = strings.importFromDevice,
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = glass.accent,
                                modifier = Modifier
                                    .border(1.dp, glass.accentGlassBorderBrush, RoundedCornerShape(16.dp))
                                    .testTag("fab_action_import_device")
                            )
                        }
                    }

                    // Main Capture FAB
                    FloatingActionButton(
                        onClick = {
                            HapticsAndShareHelper.tapTick(view)
                            isFabMenuExpanded = !isFabMenuExpanded
                        },
                        containerColor = glass.accent,
                        contentColor = glass.onAccent,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .size(58.dp)
                            .border(1.5.dp, glass.onAccent.copy(alpha = 0.42f), RoundedCornerShape(18.dp))
                            .semantics { contentDescription = strings.scanOrImport }
                            .testTag("capture_fab")
                    ) {
                        Icon(
                            imageVector = if (isFabMenuExpanded) Icons.Default.Close else Icons.Default.CameraAlt,
                            contentDescription = strings.scanOrImport,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }
        }
    }

    if (!isAppLocked) {
        // New Folder Dialog (positioned at top of screen, first letter capitalized, allowed at any depth)
        if (showNewFolderDialog) {
            FolderNameDialog(
                title = strings.newFolder,
                initialName = "",
                confirmLabel = strings.create,
                strings = strings,
                onDismiss = { showNewFolderDialog = false },
                onSubmit = { name ->
                    onCreateFolder(name, currentFolderId)
                }
            )
        }

        // Rename Folder Dialog (positioned at top of screen, first letter capitalized)
        renamingFolder?.let { folder ->
            FolderNameDialog(
                title = strings.rename,
                initialName = folder.name,
                confirmLabel = strings.save,
                strings = strings,
                onDismiss = { renamingFolder = null },
                onSubmit = { newName ->
                    onRenameFolder(folder.id, newName)
                }
            )
        }

        // Rename Image Dialog (displays and edits filename cleanly without `.jpg`)
        renamingScan?.let { scan ->
            ImageNameDialog(
                initialFilename = scan.filename,
                strings = strings,
                onDismiss = { renamingScan = null },
                onSubmit = { newName ->
                    onRenameScan(scan.id, newName)
                }
            )
        }

        // Bulk Rename Dialog (pattern or find/replace for 2+ selected scans)
        bulkRenamingScans?.let { scansToRename ->
            BulkRenameDialog(
                selectedScans = scansToRename,
                allActiveScans = allScans,
                foldersById = folderMap,
                currentFolderName = currentFolder?.name,
                strings = strings,
                onDismiss = { bulkRenamingScans = null },
                onApplyBulkRename = onBulkRenameScans
            )
        }

        // Delete Folder(s) Confirmation Dialog (with combined subfolder & nested image count)
        deletingMultiFolderSummary?.let { summary ->
            DeleteMultipleFoldersConfirmDialog(
                summary = summary,
                strings = strings,
                onCancel = { deletingMultiFolderSummary = null },
                onConfirmDelete = {
                    val ids = summary.folders.map { it.id }
                    deletingMultiFolderSummary = null
                    if (ids.size == 1) {
                        onDeleteFolder(ids.first())
                    } else {
                        onDeleteFolders(ids)
                    }
                }
            )
        }

        // Delete Selected Images Confirmation Dialog
        if (showDeleteSelectedImagesDialog) {
            DeleteImagesConfirmDialog(
                selectedCount = selectedScanIds.size,
                strings = strings,
                onCancel = { showDeleteSelectedImagesDialog = false },
                onConfirmDelete = {
                    showDeleteSelectedImagesDialog = false
                    onDeleteSelectedImages()
                }
            )
        }
    }
}

/**
 * Accent-Shaded SVG Folder Item (`Folder_d5cf3bc3.svg`):
 * - In Grid View: Renders the 4-layer accent-shaded folder SVG with the folder name and date
 *   positioned directly inside the open front flap (no outer rectangular box).
 * - In List View: Renders the dual-tone accent-shaded folder SVG icon on the left with the
 *   folder name, date, and right-edge item count pill.
 */
@Composable
private fun FolderCardItem(
    folder: FolderEntity,
    itemCount: Int = 0,
    viewMode: FolderViewMode,
    isBeingDragged: Boolean,
    isSelectedHighlight: Boolean,
    isMultiDragSourceActive: Boolean,
    isInDragSelection: Boolean,
    modifier: Modifier = Modifier,
    onBoundsChanged: (Rect) -> Unit,
    onOpen: () -> Unit,
    onJoinDragSelection: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDragBy: (Offset) -> Unit,
    onDragFinish: () -> Unit,
    onDragCancel: () -> Unit
) {
    val glass = LocalGlassPalette.current
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentOnJoinDragSelection by rememberUpdatedState(onJoinDragSelection)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragBy by rememberUpdatedState(onDragBy)
    val currentOnDragFinish by rememberUpdatedState(onDragFinish)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)

    val formattedDate = remember(folder.modifiedAt) {
        SimpleDateFormat("MMM d, yy", Locale.getDefault()).format(Date(folder.modifiedAt))
    }

    val multiTouchJoinModifier = Modifier.pointerInput(
        isMultiDragSourceActive,
        isInDragSelection,
        folder.id
    ) {
        if (isMultiDragSourceActive && !isInDragSelection) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                currentOnJoinDragSelection()
            }
        }
    }

    if (viewMode == FolderViewMode.GRID) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .alpha(if (isBeingDragged) 0.25f else 1f)
                .clip(RoundedCornerShape(10.dp))
                .onGloballyPositioned { coords ->
                    onBoundsChanged(coords.boundsInRoot())
                }
                .then(multiTouchJoinModifier)
                .clickable(enabled = !isMultiDragSourceActive, onClick = { currentOnOpen() })
                .pointerInput(folder.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { startOffset ->
                            currentOnDragStart(startOffset)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            currentOnDragBy(dragAmount)
                        },
                        onDragEnd = {
                            currentOnDragFinish()
                        },
                        onDragCancel = {
                            currentOnDragCancel()
                        }
                    )
                }
                .padding(horizontal = 2.dp, vertical = 2.dp)
                .testTag("folder_card_${folder.name}")
        ) {
            AccentShadedFolderGridContent(
                folderName = folder.name,
                formattedDate = formattedDate,
                highlightBorder = isSelectedHighlight
            )
            if (isSelectedHighlight && !isBeingDragged) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(glass.accent)
                        .border(1.2.dp, Color.White, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Selected",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .fillMaxWidth()
                .alpha(if (isBeingDragged) 0.25f else 1f)
                .clip(RoundedCornerShape(14.dp))
                .then(
                    if (isSelectedHighlight && !isBeingDragged) {
                        Modifier.background(glass.accentSoft)
                    } else {
                        Modifier.background(glass.glassSurfaceGradient)
                    }
                )
                .border(
                    width = if (isSelectedHighlight) 2.dp else 1.dp,
                    brush = if (isSelectedHighlight) SolidColor(glass.accent) else glass.accentGlassBorderBrush,
                    shape = RoundedCornerShape(14.dp)
                )
                .onGloballyPositioned { coords ->
                    onBoundsChanged(coords.boundsInRoot())
                }
                .then(multiTouchJoinModifier)
                .clickable(enabled = !isMultiDragSourceActive, onClick = { currentOnOpen() })
                .pointerInput(folder.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { startOffset ->
                            currentOnDragStart(startOffset)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            currentOnDragBy(dragAmount)
                        },
                        onDragEnd = {
                            currentOnDragFinish()
                        },
                        onDragCancel = {
                            currentOnDragCancel()
                        }
                    )
                }
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("folder_card_${folder.name}")
        ) {
            AccentShadedFolderSvgGraphic(
                accentColor = glass.accent,
                highlightBorder = isSelectedHighlight,
                modifier = Modifier.size(width = 48.dp, height = 38.dp)
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = glass.textPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = glass.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(glass.inactiveButtonFill)
                    .border(1.dp, glass.inactiveButtonBorder, RoundedCornerShape(999.dp))
                    .padding(horizontal = 9.dp, vertical = 3.dp)
                    .testTag("folder_item_count_pill_${folder.id}")
            ) {
                Text(
                    text = itemCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = glass.textSecondary
                )
            }

            if (isSelectedHighlight && !isBeingDragged) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = glass.accent,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/**
 * 4-per-row compact Subfolder Item for inside root folders:
 * - Boxless smaller Homepage-style accent-shaded folder graphic
 * - Folder name centered cleanly underneath
 */
@Composable
private fun SubfolderMiniCardItem(
    folder: FolderEntity,
    isBeingDragged: Boolean,
    isSelectedHighlight: Boolean,
    isMultiDragSourceActive: Boolean,
    isInDragSelection: Boolean,
    modifier: Modifier = Modifier,
    onBoundsChanged: (Rect) -> Unit,
    onOpen: () -> Unit,
    onJoinDragSelection: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDragBy: (Offset) -> Unit,
    onDragFinish: () -> Unit,
    onDragCancel: () -> Unit
) {
    val glass = LocalGlassPalette.current
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentOnJoinDragSelection by rememberUpdatedState(onJoinDragSelection)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragBy by rememberUpdatedState(onDragBy)
    val currentOnDragFinish by rememberUpdatedState(onDragFinish)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)

    Box(
        modifier = modifier
            .alpha(if (isBeingDragged) 0.25f else 1f)
            .onGloballyPositioned { coords ->
                onBoundsChanged(coords.boundsInRoot())
            }
            .pointerInput(isMultiDragSourceActive, isInDragSelection, folder.id) {
                if (isMultiDragSourceActive && !isInDragSelection) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        currentOnJoinDragSelection()
                    }
                }
            }
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = !isMultiDragSourceActive, onClick = { currentOnOpen() })
            .pointerInput(folder.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { startOffset -> currentOnDragStart(startOffset) },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        currentOnDragBy(dragAmount)
                    },
                    onDragEnd = { currentOnDragFinish() },
                    onDragCancel = { currentOnDragCancel() }
                )
            }
            .padding(horizontal = 2.dp, vertical = 4.dp)
            .testTag("subfolder_card_${folder.name}")
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            AccentShadedFolderSvgGraphic(
                accentColor = glass.accent,
                highlightBorder = isSelectedHighlight,
                modifier = Modifier
                    .fillMaxWidth(0.76f)
                    .aspectRatio(FOLDER_SVG_ASPECT_RATIO)
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = folder.name,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.5.sp,
                    lineHeight = 13.sp
                ),
                color = if (isSelectedHighlight) glass.accent else glass.textPrimary,
                fontWeight = if (isSelectedHighlight) FontWeight.Bold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (isSelectedHighlight && !isBeingDragged) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(glass.accent)
                    .border(1.2.dp, Color.White, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun SubfolderListItem(
    folder: FolderEntity,
    itemCount: Int = 0,
    isBeingDragged: Boolean,
    isSelectedHighlight: Boolean,
    isMultiDragSourceActive: Boolean,
    isInDragSelection: Boolean,
    modifier: Modifier = Modifier,
    onBoundsChanged: (Rect) -> Unit,
    onOpen: () -> Unit,
    onJoinDragSelection: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDragBy: (Offset) -> Unit,
    onDragFinish: () -> Unit,
    onDragCancel: () -> Unit
) {
    val glass = LocalGlassPalette.current
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentOnJoinDragSelection by rememberUpdatedState(onJoinDragSelection)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragBy by rememberUpdatedState(onDragBy)
    val currentOnDragFinish by rememberUpdatedState(onDragFinish)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)

    val formattedDate = remember(folder.modifiedAt) {
        SimpleDateFormat("MMM d, yy", Locale.getDefault()).format(Date(folder.modifiedAt))
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .alpha(if (isBeingDragged) 0.25f else 1f)
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (isSelectedHighlight && !isBeingDragged) {
                    Modifier.background(glass.accentSoft)
                } else {
                    Modifier.background(glass.glassSurfaceGradient)
                }
            )
            .border(
                width = if (isSelectedHighlight) 2.dp else 1.dp,
                brush = if (isSelectedHighlight) SolidColor(glass.accent) else glass.accentGlassBorderBrush,
                shape = RoundedCornerShape(14.dp)
            )
            .onGloballyPositioned { coords ->
                onBoundsChanged(coords.boundsInRoot())
            }
            .pointerInput(isMultiDragSourceActive, isInDragSelection, folder.id) {
                if (isMultiDragSourceActive && !isInDragSelection) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        currentOnJoinDragSelection()
                    }
                }
            }
            .clickable(enabled = !isMultiDragSourceActive, onClick = { currentOnOpen() })
            .pointerInput(folder.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { startOffset -> currentOnDragStart(startOffset) },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        currentOnDragBy(dragAmount)
                    },
                    onDragEnd = { currentOnDragFinish() },
                    onDragCancel = { currentOnDragCancel() }
                )
            }
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("subfolder_card_${folder.name}")
    ) {
        AccentShadedFolderSvgGraphic(
            accentColor = glass.accent,
            highlightBorder = isSelectedHighlight,
            modifier = Modifier.size(width = 48.dp, height = 38.dp)
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.titleMedium,
                color = glass.textPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = formattedDate,
                style = MaterialTheme.typography.labelSmall,
                color = glass.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(glass.inactiveButtonFill)
                .border(1.dp, glass.inactiveButtonBorder, RoundedCornerShape(999.dp))
                .padding(horizontal = 9.dp, vertical = 3.dp)
                .testTag("subfolder_item_count_pill_${folder.id}")
        ) {
            Text(
                text = itemCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = glass.textSecondary
            )
        }

        if (isSelectedHighlight && !isBeingDragged) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Selected",
                tint = glass.accent,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
