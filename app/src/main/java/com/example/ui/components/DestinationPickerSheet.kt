package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.AppStrings
import com.example.data.FolderEntity
import com.example.data.FolderOperationResult
import com.example.data.FolderSortMode
import com.example.ui.DestinationPickerPurpose
import com.example.ui.DestinationPickerState
import com.example.ui.theme.LocalGlassPalette
import com.example.util.HapticsAndShareHelper

/**
 * Shared reusable mobile destination picker used for both:
 * 1. "Save to folder" (when saving a capture/import batch started from Home)
 * 2. "Move to folder" (when moving selected images between top-level folders and subfolders)
 *
 * Automatically adapts to the app's Dark (#22242A) / Light theme and Accent Color, and elevates
 * the bottom confirmation button with generous bottom padding so it is always clearly visible.
 */
@Composable
fun DestinationPickerModal(
    state: DestinationPickerState,
    allFolders: List<FolderEntity>,
    folderSortMode: FolderSortMode = FolderSortMode.LAST_MODIFIED,
    folderSortAscending: Boolean = false,
    strings: AppStrings,
    isAppLocked: Boolean = false,
    formatBreadcrumb: (String?, Boolean) -> String,
    onNavigateToFolder: (String?) -> Unit,
    onNavigateBack: () -> Unit,
    onDismiss: () -> Unit,
    onCreateFolderInPicker: suspend (String) -> FolderOperationResult,
    onConfirmDestination: () -> Unit
) {
    if (!state.isVisible) return

    val view = LocalView.current
    val glass = LocalGlassPalette.current
    val isBn = strings.lang == com.example.data.AppLanguage.BN
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    LaunchedEffect(isAppLocked) {
        if (isAppLocked) {
            showCreateFolderDialog = false
        }
    }

    val currentFolder = remember(state.currentFolderId, allFolders) {
        state.currentFolderId?.let { cid -> allFolders.find { it.id == cid } }
    }

    val childFolders = remember(state.currentFolderId, allFolders, folderSortMode, folderSortAscending) {
        val raw = allFolders.filter { it.parentId == state.currentFolderId }
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

    val pickerTitle = if (state.purpose == DestinationPickerPurpose.SAVE_BATCH) {
        strings.saveToFolder
    } else {
        strings.moveToFolder
    }

    val confirmButtonLabel = if (state.purpose == DestinationPickerPurpose.SAVE_BATCH) {
        strings.saveHere
    } else {
        strings.moveHere
    }

    val isSameAsMoveSource = state.purpose == DestinationPickerPurpose.MOVE_IMAGES &&
        state.currentFolderId != null &&
        state.currentFolderId == state.sourceFolderId

    val isConfirmEnabled = state.currentFolderId != null &&
        !isSameAsMoveSource &&
        !state.isSubmitting

    val breadcrumbText = formatBreadcrumb(state.currentFolderId, true)

    Dialog(
        onDismissRequest = {
            if (!state.isSubmitting) onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.58f))
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 44.dp)
                .testTag("destination_picker_modal"),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .fillMaxHeight(0.78f)
                    .border(1.5.dp, glass.accentGlassBorderBrush, RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.background,
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Header Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(glass.glassHeaderFill)
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                HapticsAndShareHelper.tapTick(view)
                                onNavigateBack()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("picker_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = glass.textPrimary
                            )
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp)
                        ) {
                            Text(
                                text = pickerTitle,
                                style = MaterialTheme.typography.titleMedium,
                                color = glass.textPrimary,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (state.purpose == DestinationPickerPurpose.MOVE_IMAGES && state.sourceFolderId != null) {
                                Text(
                                    text = strings.sourceLocationPrefix + formatBreadcrumb(state.sourceFolderId, false),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = glass.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        val canCreateFolderHere = state.currentFolderId == null || currentFolder?.parentId == null
                        if (canCreateFolderHere) {
                            OutlinedButton(
                                onClick = {
                                    HapticsAndShareHelper.tapTick(view)
                                    showCreateFolderDialog = true
                                },
                                border = androidx.compose.foundation.BorderStroke(1.dp, glass.inactiveButtonBorder),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("picker_new_folder_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CreateNewFolder,
                                    contentDescription = null,
                                    tint = glass.accent,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = strings.newFolder,
                                    color = glass.accent,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                HapticsAndShareHelper.tapTick(view)
                                onDismiss()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("picker_cancel_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = strings.cancel,
                                tint = glass.textSecondary
                            )
                        }
                    }

                    // Interactive Breadcrumb Bar (Home / Projects / Site Visit / ...)
                    Surface(
                        color = glass.accentSoft,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = glass.accent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = breadcrumbText,
                                style = MaterialTheme.typography.labelLarge,
                                color = glass.textPrimary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.testTag("picker_breadcrumb_text")
                            )
                        }
                    }

                    HorizontalDivider(color = glass.dividerColor)

                    // Folder List Content
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        if (childFolders.isEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CreateNewFolder,
                                    contentDescription = null,
                                    tint = glass.accent,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                val canCreateEmptyHere = state.currentFolderId == null || currentFolder?.parentId == null
                                Text(
                                    text = when {
                                        state.currentFolderId == null ->
                                            if (isBn) {
                                                "এখনো কোনো ফোল্ডার নেই। স্ক্যান সংরক্ষণ করতে প্রথম ফোল্ডার তৈরি করুন।"
                                            } else {
                                                "No folders yet. Create your first folder to save your scans."
                                            }
                                        canCreateEmptyHere ->
                                            if (isBn) {
                                                "'${currentFolder?.name}'-এর ভেতরে কোনো সাবফোল্ডার নেই। এখানেই সংরক্ষণ করতে পারেন অথবা নতুন সাবফোল্ডার তৈরি করুন।"
                                            } else {
                                                "No subfolders inside '${currentFolder?.name}'. You can confirm here or create a subfolder."
                                            }
                                        else ->
                                            if (isBn) {
                                                "'${currentFolder?.name}' নির্বাচিত হয়েছে। নিশ্চিত করতে নিচের '$confirmButtonLabel' বোতামে চাপুন।"
                                            } else {
                                                "Ready to use '${currentFolder?.name}'. Tap '$confirmButtonLabel' below to confirm."
                                            }
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = glass.textSecondary
                                )
                                if (canCreateEmptyHere) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(
                                        onClick = {
                                            HapticsAndShareHelper.tapTick(view)
                                            showCreateFolderDialog = true
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = glass.accent,
                                            contentColor = glass.onAccent
                                        ),
                                        modifier = Modifier.testTag("picker_empty_create_folder_button")
                                    ) {
                                        Icon(imageVector = Icons.Default.CreateNewFolder, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(text = strings.newFolder)
                                    }
                                }
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(childFolders, key = { it.id }) { folder ->
                                    val isCurrentMoveSource = state.purpose == DestinationPickerPurpose.MOVE_IMAGES &&
                                        folder.id == state.sourceFolderId
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(glass.glassSurfaceGradient)
                                            .border(1.2.dp, glass.accentGlassBorderBrush, RoundedCornerShape(14.dp))
                                            .clickable {
                                                HapticsAndShareHelper.tapTick(view)
                                                onNavigateToFolder(folder.id)
                                            }
                                            .padding(horizontal = 16.dp, vertical = 14.dp)
                                            .testTag("picker_folder_row_${folder.name}")
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            AccentShadedFolderSvgGraphic(
                                                accentColor = glass.accent,
                                                modifier = Modifier.size(width = 44.dp, height = 35.dp)
                                            )
                                            Spacer(modifier = Modifier.width(14.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = folder.name,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = glass.textPrimary,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                if (isCurrentMoveSource) {
                                                    Text(
                                                        text = if (isBn) "বর্তমান ফোল্ডার" else "Current folder",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = glass.textSecondary
                                                    )
                                                }
                                            }
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                                contentDescription = null,
                                                tint = glass.textSecondary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Error banner if storage failed
                    if (state.errorMessage != null) {
                        Text(
                            text = state.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }

                    HorizontalDivider(color = glass.dividerColor)

                    // Bottom Sticky Confirmation Bar inside the centered modal
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(glass.glassHeaderFill)
                            .padding(horizontal = 16.dp, vertical = 16.dp)
                    ) {
                        val destSummary = if (state.currentFolderId == null) {
                            "${strings.selectFolderPrompt} · ${strings.imagesCountSummary(state.itemCount)}"
                        } else if (isSameAsMoveSource) {
                            val currentLabel = if (isBn) "বর্তমান উৎস ফোল্ডার" else "Current source folder"
                            "${formatBreadcrumb(state.currentFolderId, false)} ($currentLabel) · ${strings.imagesCountSummary(state.itemCount)}"
                        } else {
                            "${formatBreadcrumb(state.currentFolderId, false)} · ${strings.imagesCountSummary(state.itemCount)}"
                        }

                        Text(
                            text = destSummary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.currentFolderId == null || isSameAsMoveSource) glass.textSecondary else glass.accent,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .padding(bottom = 10.dp)
                                .testTag("picker_destination_summary")
                        )

                        Button(
                            onClick = {
                                HapticsAndShareHelper.tapTick(view)
                                onConfirmDestination()
                            },
                            enabled = isConfirmEnabled,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = glass.accent,
                                contentColor = glass.onAccent,
                                disabledContainerColor = glass.accent.copy(alpha = 0.35f),
                                disabledContentColor = glass.onAccent.copy(alpha = 0.65f)
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .testTag("picker_confirm_button")
                        ) {
                            if (state.isSubmitting) {
                                CircularProgressIndicator(
                                    color = glass.onAccent,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                            }
                            Text(
                                text = confirmButtonLabel,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateFolderDialog && !isAppLocked) {
        FolderNameDialog(
            title = strings.newFolder,
            initialName = "",
            confirmLabel = strings.create,
            strings = strings,
            onDismiss = { showCreateFolderDialog = false },
            onSubmit = { name ->
                onCreateFolderInPicker(name)
            }
        )
    }
}
