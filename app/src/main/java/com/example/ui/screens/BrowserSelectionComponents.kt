package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.AppStrings
import com.example.data.ScanEntity
import com.example.data.stripImageExtension
import com.example.ui.theme.LocalGlassPalette
import com.example.ui.theme.ObsidianSurface
import java.io.File
import kotlin.math.roundToInt

enum class ImageBottomAction {
    MOVE,
    MAKE_PDF,
    RENAME,
    SHARE,
    SAVE,
    DELETE
}

/**
 * Compact 4-column Grid View scan tile:
 * Displays ONLY the thumbnail preview and the multiline clean filename underneath (no `.jpg`, no extra metadata).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GridScanCardItem(
    scan: ScanEntity,
    isSelected: Boolean,
    isBeingDragged: Boolean,
    isImageDragActive: Boolean,
    parentFolderName: String? = null,
    modifier: Modifier = Modifier,
    onBoundsChanged: (Rect) -> Unit,
    onTap: () -> Unit,
    onJoinDragSelection: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDragBy: (Offset) -> Unit,
    onDragFinish: () -> Unit,
    onDragCancel: () -> Unit
) {
    val glass = LocalGlassPalette.current
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnJoinDragSelection by rememberUpdatedState(onJoinDragSelection)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragBy by rememberUpdatedState(onDragBy)
    val currentOnDragFinish by rememberUpdatedState(onDragFinish)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)

    val displayName = remember(scan.filename) { stripImageExtension(scan.filename) }

    val multiTouchJoinModifier = Modifier.pointerInput(
        isImageDragActive,
        isSelected,
        scan.id
    ) {
        if (isImageDragActive && !isSelected) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                currentOnJoinDragSelection()
            }
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .alpha(if (isBeingDragged) 0.28f else 1f)
            .onGloballyPositioned { coords -> onBoundsChanged(coords.boundsInRoot()) }
            .then(multiTouchJoinModifier)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = !isImageDragActive, onClick = { currentOnTap() })
            .pointerInput(scan.id) {
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
            .padding(2.dp)
            .semantics { contentDescription = displayName }
            .testTag("scan_thumbnail_${scan.id}")
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.76f)
                .clip(RoundedCornerShape(10.dp))
                .background(glass.glassCardFill)
                .border(
                    width = if (isSelected) 2.2.dp else 1.dp,
                    color = if (isSelected) glass.accent else glass.accent.copy(alpha = 0.24f),
                    shape = RoundedCornerShape(10.dp)
                )
        ) {
            AsyncImage(
                model = File(scan.thumbnailPath).let { if (it.exists()) it else File(scan.editedPath) },
                contentDescription = displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(glass.accent.copy(alpha = 0.22f))
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
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

        Spacer(modifier = Modifier.height(5.dp))

        Text(
            text = displayName,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                lineHeight = 13.sp
            ),
            color = if (isSelected) glass.accent else glass.textPrimary,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        if (!parentFolderName.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(3.dp))
            Surface(
                color = glass.glassCardFill,
                shape = RoundedCornerShape(999.dp),
                border = BorderStroke(0.8.dp, glass.accentGlassBorderBrush),
                modifier = Modifier.testTag("search_scan_folder_badge_${scan.id}")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = glass.accent,
                        modifier = Modifier.size(10.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = parentFolderName,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                        color = glass.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Full-Screen Width List View scan item:
 * Displays ONLY the full-bleed edited document thumbnail and clean multiline filename subtitle beneath
 * (with optional parent-folder badge shown only during active Homepage search).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListScanCardItem(
    scan: ScanEntity,
    isSelected: Boolean,
    isBeingDragged: Boolean,
    isImageDragActive: Boolean,
    parentFolderName: String? = null,
    modifier: Modifier = Modifier,
    onBoundsChanged: (Rect) -> Unit,
    onTap: () -> Unit,
    onJoinDragSelection: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDragBy: (Offset) -> Unit,
    onDragFinish: () -> Unit,
    onDragCancel: () -> Unit
) {
    val glass = LocalGlassPalette.current
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnJoinDragSelection by rememberUpdatedState(onJoinDragSelection)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragBy by rememberUpdatedState(onDragBy)
    val currentOnDragFinish by rememberUpdatedState(onDragFinish)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)

    val displayName = remember(scan.filename) { stripImageExtension(scan.filename) }
    val aspect = remember(scan.width, scan.height) {
        if (scan.width > 0 && scan.height > 0) {
            (scan.width.toFloat() / scan.height.toFloat()).coerceIn(0.35f, 2.2f)
        } else {
            0.74f
        }
    }

    val multiTouchJoinModifier = Modifier.pointerInput(
        isImageDragActive,
        isSelected,
        scan.id
    ) {
        if (isImageDragActive && !isSelected) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                currentOnJoinDragSelection()
            }
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (isBeingDragged) 0.28f else 1f)
            .onGloballyPositioned { coords -> onBoundsChanged(coords.boundsInRoot()) }
            .then(multiTouchJoinModifier)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = !isImageDragActive, onClick = { currentOnTap() })
            .pointerInput(scan.id) {
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
            .semantics { contentDescription = displayName }
            .testTag("scan_thumbnail_${scan.id}")
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(8.dp))
                .border(
                    width = if (isSelected) 2.5.dp else 0.dp,
                    color = if (isSelected) glass.accent else Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                )
        ) {
            AsyncImage(
                model = File(scan.editedPath).let { if (it.exists()) it else File(scan.thumbnailPath) },
                contentDescription = displayName,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxSize()
            )

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(glass.accent.copy(alpha = 0.20f))
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(glass.accent)
                        .border(1.5.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Selected",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isSelected) glass.accent else glass.textPrimary,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        )

        if (!parentFolderName.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                color = glass.glassCardFill,
                shape = RoundedCornerShape(999.dp),
                border = BorderStroke(0.8.dp, glass.accentGlassBorderBrush),
                modifier = Modifier.testTag("search_scan_folder_badge_${scan.id}")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = glass.accent,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = parentFolderName,
                        style = MaterialTheme.typography.labelSmall,
                        color = glass.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Floating top instruction / sticky selection pill with compact `All` and `Clear` pills when released.
 */
@Composable
fun SelectionTopFloatingPill(
    visible: Boolean,
    text: String,
    showClearButton: Boolean,
    clearLabel: String,
    isMultiAlert: Boolean,
    onClear: () -> Unit,
    showSelectAllButton: Boolean = false,
    selectAllLabel: String = "All",
    onSelectAll: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val glass = LocalGlassPalette.current
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            color = ObsidianSurface.copy(alpha = 0.94f),
            shape = RoundedCornerShape(999.dp),
            border = BorderStroke(1.2.dp, glass.accent),
            shadowElevation = 10.dp,
            modifier = Modifier
                .widthIn(max = 560.dp)
                .testTag("selection_top_floating_pill")
        ) {
            Row(
                modifier = Modifier.padding(
                    start = 16.dp,
                    end = if (showClearButton || showSelectAllButton) 8.dp else 16.dp,
                    top = if (showClearButton || showSelectAllButton) 6.dp else 9.dp,
                    bottom = if (showClearButton || showSelectAllButton) 6.dp else 9.dp
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isMultiAlert) MaterialTheme.colorScheme.error else glass.accent)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (showSelectAllButton) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(glass.accent.copy(alpha = 0.25f))
                            .border(1.dp, glass.accentHighlight.copy(alpha = 0.65f), RoundedCornerShape(999.dp))
                            .clickable(onClick = onSelectAll)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag("selection_all_pill")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DoneAll,
                            contentDescription = selectAllLabel,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = selectAllLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (showClearButton) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color.White.copy(alpha = 0.16f))
                            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
                            .clickable(onClick = onClear)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag("selection_clear_pill")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = clearLabel,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = clearLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/**
 * Folder Bottom Bar supporting both active drag-drop and released sticky taps:
 * Left: Rename (dimmed/disabled when 2+ folders selected), Right: Delete.
 */
@Composable
fun FolderDragAndStickyBottomBar(
    visible: Boolean,
    selectedCount: Int,
    isDragging: Boolean,
    isHoveringRename: Boolean,
    isHoveringDelete: Boolean,
    strings: AppStrings,
    onRenameBoundsChanged: (Rect) -> Unit,
    onDeleteBoundsChanged: (Rect) -> Unit,
    onRenameClicked: () -> Unit,
    onDeleteClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalGlassPalette.current
    val isMulti = selectedCount >= 2

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier.fillMaxWidth()
    ) {
        Surface(
            color = ObsidianSurface.copy(alpha = 0.94f),
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            shadowElevation = 16.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, glass.accentGlassBorderBrush, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .testTag("folder_drag_bottom_bar")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .alpha(if (isMulti) 0.36f else 1f)
                        .scale(if (isHoveringRename && !isMulti) 1.05f else 1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            when {
                                isMulti -> Color.White.copy(alpha = 0.08f)
                                isHoveringRename -> glass.accent
                                else -> Color.White.copy(alpha = 0.14f)
                            }
                        )
                        .border(
                            width = if (isHoveringRename && !isMulti) 2.dp else 1.dp,
                            color = when {
                                isMulti -> Color.White.copy(alpha = 0.22f)
                                isHoveringRename -> glass.accentHighlight
                                else -> Color.White.copy(alpha = 0.35f)
                            },
                            shape = RoundedCornerShape(14.dp)
                        )
                        .onGloballyPositioned { coords -> onRenameBoundsChanged(coords.boundsInRoot()) }
                        .clickable(enabled = !isDragging && !isMulti, onClick = onRenameClicked)
                        .testTag("drag_drop_rename_target")
                ) {
                    val renameColor = if (isHoveringRename && !isMulti) glass.onAccent else Color.White
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.DriveFileRenameOutline,
                            contentDescription = strings.rename,
                            tint = renameColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = strings.rename,
                            style = MaterialTheme.typography.titleMedium,
                            color = renameColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .scale(if (isHoveringDelete) 1.05f else 1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isHoveringDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.24f))
                        .border(
                            width = if (isHoveringDelete) 2.dp else 1.dp,
                            color = if (isHoveringDelete) Color.White else MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        .onGloballyPositioned { coords -> onDeleteBoundsChanged(coords.boundsInRoot()) }
                        .clickable(enabled = !isDragging && selectedCount > 0, onClick = onDeleteClicked)
                        .testTag("drag_drop_delete_target")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = strings.delete,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isMulti) "${strings.delete} ($selectedCount)" else strings.delete,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
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

/**
 * 2×3 Image Action Bottom Bar for both drag-drop and released sticky selection:
 * Row 1: Move | Make PDF | Rename (disabled if multi)
 * Row 2: Share | Save | Delete
 */
@Composable
fun ImageDragAndStickyBottomBar(
    visible: Boolean,
    selectedCount: Int,
    isDragging: Boolean,
    hoveredAction: ImageBottomAction?,
    strings: AppStrings,
    onActionBoundsChanged: (ImageBottomAction, Rect) -> Unit,
    onActionClicked: (ImageBottomAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalGlassPalette.current
    val isMulti = selectedCount >= 2

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier.fillMaxWidth()
    ) {
        Surface(
            color = ObsidianSurface.copy(alpha = 0.95f),
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            shadowElevation = 18.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, glass.accentGlassBorderBrush, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .testTag("image_drag_bottom_bar")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ImageActionDockTile(
                        icon = Icons.AutoMirrored.Filled.DriveFileMove,
                        label = strings.move,
                        enabled = selectedCount > 0,
                        isDragging = isDragging,
                        isHovered = hoveredAction == ImageBottomAction.MOVE,
                        isDanger = false,
                        testTag = "image_action_move",
                        modifier = Modifier.weight(1f),
                        onBoundsChanged = { onActionBoundsChanged(ImageBottomAction.MOVE, it) },
                        onClick = { onActionClicked(ImageBottomAction.MOVE) }
                    )
                    ImageActionDockTile(
                        icon = Icons.Default.PictureAsPdf,
                        label = strings.makePdf,
                        enabled = selectedCount > 0,
                        isDragging = isDragging,
                        isHovered = hoveredAction == ImageBottomAction.MAKE_PDF,
                        isDanger = false,
                        testTag = "image_action_make_pdf",
                        modifier = Modifier.weight(1f),
                        onBoundsChanged = { onActionBoundsChanged(ImageBottomAction.MAKE_PDF, it) },
                        onClick = { onActionClicked(ImageBottomAction.MAKE_PDF) }
                    )
                    ImageActionDockTile(
                        icon = Icons.Default.DriveFileRenameOutline,
                        label = if (isMulti) {
                            if (strings.isBn) "বাল্ক রিনেম" else "Bulk Rename"
                        } else {
                            strings.rename
                        },
                        enabled = selectedCount > 0,
                        isDragging = isDragging,
                        isHovered = hoveredAction == ImageBottomAction.RENAME,
                        isDanger = false,
                        testTag = "image_action_rename",
                        modifier = Modifier.weight(1f),
                        onBoundsChanged = { onActionBoundsChanged(ImageBottomAction.RENAME, it) },
                        onClick = { onActionClicked(ImageBottomAction.RENAME) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ImageActionDockTile(
                        icon = Icons.Default.Share,
                        label = strings.share,
                        enabled = selectedCount > 0,
                        isDragging = isDragging,
                        isHovered = hoveredAction == ImageBottomAction.SHARE,
                        isDanger = false,
                        testTag = "image_action_share",
                        modifier = Modifier.weight(1f),
                        onBoundsChanged = { onActionBoundsChanged(ImageBottomAction.SHARE, it) },
                        onClick = { onActionClicked(ImageBottomAction.SHARE) }
                    )
                    ImageActionDockTile(
                        icon = Icons.Default.Download,
                        label = strings.saveShort,
                        enabled = selectedCount > 0,
                        isDragging = isDragging,
                        isHovered = hoveredAction == ImageBottomAction.SAVE,
                        isDanger = false,
                        testTag = "image_action_save",
                        modifier = Modifier.weight(1f),
                        onBoundsChanged = { onActionBoundsChanged(ImageBottomAction.SAVE, it) },
                        onClick = { onActionClicked(ImageBottomAction.SAVE) }
                    )
                    ImageActionDockTile(
                        icon = Icons.Default.Delete,
                        label = if (isMulti) "${strings.delete} ($selectedCount)" else strings.delete,
                        enabled = selectedCount > 0,
                        isDragging = isDragging,
                        isHovered = hoveredAction == ImageBottomAction.DELETE,
                        isDanger = true,
                        testTag = "image_action_delete",
                        modifier = Modifier.weight(1f),
                        onBoundsChanged = { onActionBoundsChanged(ImageBottomAction.DELETE, it) },
                        onClick = { onActionClicked(ImageBottomAction.DELETE) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ImageActionDockTile(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    isDragging: Boolean,
    isHovered: Boolean,
    isDanger: Boolean,
    testTag: String,
    modifier: Modifier = Modifier,
    onBoundsChanged: (Rect) -> Unit,
    onClick: () -> Unit
) {
    val glass = LocalGlassPalette.current
    val backgroundColor = when {
        !enabled -> Color.White.copy(alpha = 0.08f)
        isHovered && isDanger -> MaterialTheme.colorScheme.error
        isHovered -> glass.accent
        isDanger -> MaterialTheme.colorScheme.error.copy(alpha = 0.24f)
        else -> Color.White.copy(alpha = 0.14f)
    }
    val borderColor = when {
        !enabled -> Color.White.copy(alpha = 0.20f)
        isHovered && isDanger -> Color.White
        isHovered -> glass.accentHighlight
        isDanger -> MaterialTheme.colorScheme.error.copy(alpha = 0.72f)
        else -> Color.White.copy(alpha = 0.34f)
    }
    val contentColor = if (isHovered && !isDanger && enabled) glass.onAccent else Color.White

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = 58.dp)
            .alpha(if (enabled) 1f else 0.36f)
            .scale(if (isHovered && enabled) 1.05f else 1f)
            .clip(RoundedCornerShape(14.dp))
            .background(backgroundColor)
            .border(
                width = if (isHovered && enabled) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(14.dp)
            )
            .onGloballyPositioned { coords -> onBoundsChanged(coords.boundsInRoot()) }
            .clickable(enabled = !isDragging && enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp)
            .testTag(testTag)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Floating zoomed preview stack rendered beneath the user's finger while dragging one or more images.
 */
@Composable
fun FloatingDraggedScanStackOverlay(
    anchorScan: ScanEntity,
    stackCount: Int,
    widthDp: Dp,
    localX: Int,
    localY: Int
) {
    val glass = LocalGlassPalette.current
    val dragZoomScale by animateFloatAsState(
        targetValue = 1.08f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "scanDragZoomScale"
    )
    val displayName = remember(anchorScan.filename) { stripImageExtension(anchorScan.filename) }

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
                    .background(glass.glassCardFill.copy(alpha = 0.92f))
                    .border(1.2.dp, glass.accent.copy(alpha = 0.82f), RoundedCornerShape(12.dp))
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(ObsidianSurface.copy(alpha = 0.90f))
                .border(1.8.dp, glass.accent, RoundedCornerShape(12.dp))
                .padding(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.76f)
                    .clip(RoundedCornerShape(8.dp))
            ) {
                AsyncImage(
                    model = File(anchorScan.thumbnailPath).let {
                        if (it.exists()) it else File(anchorScan.editedPath)
                    },
                    contentDescription = displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.5.sp,
                    lineHeight = 12.sp
                ),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
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
