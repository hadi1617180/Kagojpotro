package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.AppStrings
import com.example.data.FolderDeleteSummary
import com.example.data.FolderEntity
import com.example.data.FolderOperationResult
import com.example.data.MultiFolderDeleteSummary
import com.example.data.ScanOperationResult
import com.example.data.VerificationCheckResult
import com.example.data.stripImageExtension
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import com.example.ui.theme.DangerRed
import com.example.ui.theme.LocalGlassPalette
import com.example.ui.theme.WarningAmber
import com.example.util.HapticsAndShareHelper
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Top-aligned dialog over a dimmed backdrop for creating or renaming a folder.
 * Appears near the top of the screen (never stuck under the keyboard), focuses the name field
 * automatically, and ensures the 1st letter is always capitalized.
 */
@Composable
fun FolderNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    strings: AppStrings,
    onDismiss: () -> Unit,
    onSubmit: suspend (String) -> FolderOperationResult
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    fun capitalizeFirstChar(input: String): String {
        if (input.isEmpty()) return input
        val first = input[0]
        return if (first.isLowerCase()) {
            first.titlecase(Locale.getDefault()) + input.substring(1)
        } else {
            input
        }
    }

    var textFieldValue by remember(initialName) {
        val formatted = capitalizeFirstChar(initialName)
        mutableStateOf(
            TextFieldValue(
                text = formatted,
                selection = TextRange(formatted.length)
            )
        )
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(80)
        runCatching { focusRequester.requestFocus() }
    }

    fun submitNow() {
        if (isSubmitting) return
        val trimmed = capitalizeFirstChar(textFieldValue.text.trim())
        if (trimmed.isEmpty()) {
            errorMessage = strings.errorEmptyFolderName
            return
        }
        isSubmitting = true
        scope.launch {
            val result = onSubmit(trimmed)
            isSubmitting = false
            when (result) {
                is FolderOperationResult.Success -> onDismiss()
                is FolderOperationResult.EmptyName -> errorMessage = strings.errorEmptyFolderName
                is FolderOperationResult.DuplicateName -> errorMessage = strings.errorDuplicateFolderName
                is FolderOperationResult.MaxDepthExceeded -> errorMessage = strings.errorMaxDepth
                is FolderOperationResult.Error -> errorMessage = result.message
            }
        }
    }

    val glass = LocalGlassPalette.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.54f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
                .windowInsetsPadding(WindowInsets.statusBars)
                .imePadding()
                .padding(top = 18.dp, start = 20.dp, end = 20.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .border(1.5.dp, glass.accentGlassBorderBrush, RoundedCornerShape(20.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .testTag("folder_name_dialog"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = glass.glassHeaderFill),
                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = glass.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = textFieldValue,
                        onValueChange = { incoming ->
                            val capText = capitalizeFirstChar(incoming.text)
                            textFieldValue = incoming.copy(text = capText)
                            errorMessage = null
                        },
                        label = { Text(strings.folderNameLabel) },
                        placeholder = { Text(strings.folderNamePlaceholder) },
                        singleLine = true,
                        isError = errorMessage != null,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { submitNow() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .testTag("folder_name_input")
                    )

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = errorMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = DangerRed,
                            modifier = Modifier.testTag("folder_name_error_text")
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                HapticsAndShareHelper.tapTick(view)
                                onDismiss()
                            },
                            modifier = Modifier.testTag("folder_dialog_cancel_button")
                        ) {
                            Text(text = strings.cancel, color = glass.textSecondary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                HapticsAndShareHelper.tapTick(view)
                                submitNow()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = glass.accent,
                                contentColor = glass.onAccent
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("folder_dialog_confirm_button")
                        ) {
                            Text(text = confirmLabel)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Top-aligned dialog for renaming an image (displays and edits name cleanly without `.jpg`).
 */
@Composable
fun ImageNameDialog(
    initialFilename: String,
    strings: AppStrings,
    onDismiss: () -> Unit,
    onSubmit: suspend (String) -> ScanOperationResult
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    var textFieldValue by remember(initialFilename) {
        val clean = stripImageExtension(initialFilename)
        mutableStateOf(
            TextFieldValue(
                text = clean,
                selection = TextRange(clean.length)
            )
        )
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(80)
        runCatching { focusRequester.requestFocus() }
    }

    fun submitNow() {
        if (isSubmitting) return
        val trimmed = stripImageExtension(textFieldValue.text.trim())
        if (trimmed.isEmpty()) {
            errorMessage = strings.errorEmptyImageName
            return
        }
        isSubmitting = true
        scope.launch {
            val result = onSubmit(trimmed)
            isSubmitting = false
            when (result) {
                is ScanOperationResult.Success -> onDismiss()
                is ScanOperationResult.EmptyName -> errorMessage = strings.errorEmptyImageName
                is ScanOperationResult.DuplicateName -> errorMessage = strings.errorDuplicateImageName
                is ScanOperationResult.Error -> errorMessage = result.message
            }
        }
    }

    val glass = LocalGlassPalette.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.54f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
                .windowInsetsPadding(WindowInsets.statusBars)
                .imePadding()
                .padding(top = 18.dp, start = 20.dp, end = 20.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .border(1.5.dp, glass.accentGlassBorderBrush, RoundedCornerShape(20.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .testTag("image_name_dialog"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = glass.glassHeaderFill),
                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = strings.renameImage,
                        style = MaterialTheme.typography.titleLarge,
                        color = glass.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = textFieldValue,
                        onValueChange = { incoming ->
                            textFieldValue = incoming
                            errorMessage = null
                        },
                        label = { Text(strings.imageNameLabel) },
                        placeholder = { Text(strings.imageNamePlaceholder) },
                        singleLine = true,
                        isError = errorMessage != null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submitNow() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .testTag("image_name_input")
                    )

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = errorMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = DangerRed,
                            modifier = Modifier.testTag("image_name_error_text")
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                HapticsAndShareHelper.tapTick(view)
                                onDismiss()
                            },
                            modifier = Modifier.testTag("image_dialog_cancel_button")
                        ) {
                            Text(text = strings.cancel, color = glass.textSecondary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                HapticsAndShareHelper.tapTick(view)
                                submitNow()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = glass.accent,
                                contentColor = glass.onAccent
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("image_dialog_confirm_button")
                        ) {
                            Text(text = strings.save)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Small Rename / Delete menu over a blurred/dimmed backdrop.
 */
@Composable
fun FolderActionMenuModal(
    folder: FolderEntity,
    strings: AppStrings,
    onDismiss: () -> Unit,
    onRenameClicked: () -> Unit,
    onDeleteClicked: () -> Unit
) {
    val view = LocalView.current
    val glass = LocalGlassPalette.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.52f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .padding(28.dp)
                    .widthIn(max = 340.dp)
                    .fillMaxWidth()
                    .border(1.2.dp, glass.accentGlassBorderBrush, RoundedCornerShape(20.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .testTag("folder_context_menu_modal"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = glass.glassHeaderFill),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(modifier = Modifier.padding(vertical = 12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AccentShadedFolderSvgGraphic(
                            accentColor = glass.accent,
                            modifier = Modifier.size(width = 30.dp, height = 24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = folder.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = glass.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    HorizontalDivider(color = glass.dividerColor)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                HapticsAndShareHelper.tapTick(view)
                                onRenameClicked()
                            }
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                            .testTag("folder_menu_rename"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.DriveFileRenameOutline,
                            contentDescription = null,
                            tint = glass.textPrimary
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = strings.rename,
                            style = MaterialTheme.typography.bodyLarge,
                            color = glass.textPrimary,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                HapticsAndShareHelper.tapTick(view)
                                onDeleteClicked()
                            }
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                            .testTag("folder_menu_delete"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = strings.delete,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

/**
 * Confirmation dialog for deleting a folder: displays the folder name and the exact total number
 * of subfolders and images (including nested images) that will be removed. Clearly distinguishes
 * Cancel and Delete.
 */
@Composable
fun DeleteFolderConfirmDialog(
    summary: FolderDeleteSummary,
    strings: AppStrings,
    onCancel: () -> Unit,
    onConfirmDelete: () -> Unit
) {
    val view = LocalView.current
    val glass = LocalGlassPalette.current
    Dialog(onDismissRequest = onCancel) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = glass.glassHeaderFill),
            modifier = Modifier
                .border(1.2.dp, glass.subtleGlassBorderBrush, RoundedCornerShape(20.dp))
                .testTag("delete_folder_confirm_dialog")
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                val isBn = strings.lang == com.example.data.AppLanguage.BN
                Text(
                    text = if (isBn) {
                        "\"${summary.folder.name}\" মুছবেন?"
                    } else {
                        "${strings.delete} \"${summary.folder.name}\"?"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = glass.textPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (isBn) {
                        "এটি \"${summary.folder.name}\" ফোল্ডারটি, এর ভেতরের ${summary.subfolderCount}টি সাবফোল্ডার এবং মোট ${summary.totalImageCount}টি ছবি ৭-দিনের ট্র্যাশ বিনে পাঠাবে।"
                    } else {
                        "This will move \"${summary.folder.name}\", including ${summary.subfolderCount} subfolder(s) and ${summary.totalImageCount} total image(s), to the 7-day Trash Bin."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = glass.textSecondary
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = {
                            HapticsAndShareHelper.tapTick(view)
                            onCancel()
                        },
                        border = BorderStroke(1.dp, glass.inactiveButtonBorder),
                        modifier = Modifier.testTag("delete_folder_cancel_button")
                    ) {
                        Text(text = strings.cancel, color = glass.textPrimary)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Button(
                        onClick = {
                            HapticsAndShareHelper.tapTick(view)
                            onConfirmDelete()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DangerRed,
                            contentColor = Color.White
                        ),
                        modifier = Modifier.testTag("delete_folder_confirm_button")
                    ) {
                        Text(text = strings.delete)
                    }
                }
            }
        }
    }
}

@Composable
fun DeleteMultipleFoldersConfirmDialog(
    summary: MultiFolderDeleteSummary,
    strings: AppStrings,
    onCancel: () -> Unit,
    onConfirmDelete: () -> Unit
) {
    if (summary.folders.size == 1) {
        DeleteFolderConfirmDialog(
            summary = FolderDeleteSummary(
                folder = summary.folders.first(),
                subfolderCount = summary.subfolderCount,
                totalImageCount = summary.totalImageCount
            ),
            strings = strings,
            onCancel = onCancel,
            onConfirmDelete = onConfirmDelete
        )
        return
    }

    val view = LocalView.current
    val glass = LocalGlassPalette.current
    val isBn = strings.lang == com.example.data.AppLanguage.BN
    val folderNamesPreview = remember(summary.folders) {
        val names = summary.folders.take(3).joinToString(", ") { "\"${it.name}\"" }
        if (summary.folders.size > 3) "$names +${summary.folders.size - 3}" else names
    }

    Dialog(onDismissRequest = onCancel) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = glass.glassHeaderFill),
            modifier = Modifier
                .border(1.2.dp, glass.subtleGlassBorderBrush, RoundedCornerShape(20.dp))
                .testTag("delete_multiple_folders_confirm_dialog")
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                Text(
                    text = if (isBn) {
                        "${summary.folders.size}টি ফোল্ডার মুছবেন?"
                    } else {
                        "Delete ${summary.folders.size} folders?"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = glass.textPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (isBn) {
                        "এটি নির্বাচিত ${summary.folders.size}টি ফোল্ডার ($folderNamesPreview), ভেতরের ${summary.subfolderCount}টি সাবফোল্ডার এবং মোট ${summary.totalImageCount}টি ছবি ৭-দিনের ট্র্যাশ বিনে পাঠাবে।"
                    } else {
                        "This will move ${summary.folders.size} selected folders ($folderNamesPreview), including ${summary.subfolderCount} subfolder(s) and ${summary.totalImageCount} total image(s), to the 7-day Trash Bin."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = glass.textSecondary
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = {
                            HapticsAndShareHelper.tapTick(view)
                            onCancel()
                        },
                        border = BorderStroke(1.dp, glass.inactiveButtonBorder),
                        modifier = Modifier.testTag("delete_folder_cancel_button")
                    ) {
                        Text(text = strings.cancel, color = glass.textPrimary)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Button(
                        onClick = {
                            HapticsAndShareHelper.tapTick(view)
                            onConfirmDelete()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DangerRed,
                            contentColor = Color.White
                        ),
                        modifier = Modifier.testTag("delete_folder_confirm_button")
                    ) {
                        Text(text = strings.delete)
                    }
                }
            }
        }
    }
}

@Composable
fun DeleteImagesConfirmDialog(
    selectedCount: Int,
    strings: AppStrings,
    onCancel: () -> Unit,
    onConfirmDelete: () -> Unit
) {
    val view = LocalView.current
    val glass = LocalGlassPalette.current
    Dialog(onDismissRequest = onCancel) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = glass.glassHeaderFill),
            modifier = Modifier
                .border(1.2.dp, glass.subtleGlassBorderBrush, RoundedCornerShape(20.dp))
                .testTag("delete_images_confirm_dialog")
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                val isBn = strings.lang == com.example.data.AppLanguage.BN
                Text(
                    text = if (isBn) {
                        "${strings.imagesCountSummary(selectedCount)} মুছবেন?"
                    } else {
                        "${strings.delete} ${strings.imagesCountSummary(selectedCount)}?"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = glass.textPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (isBn) {
                        "নির্বাচিত ${selectedCount}টি ডকুমেন্ট ছবি ৭-দিনের ট্র্যাশ বিনে পাঠানো হবে। চাইলে পরে ফিরিয়ে আনতে পারবেন।"
                    } else {
                        "Move the $selectedCount selected document image(s) to the 7-day Trash Bin? You can restore them later."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = glass.textSecondary
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = {
                            HapticsAndShareHelper.tapTick(view)
                            onCancel()
                        },
                        border = BorderStroke(1.dp, glass.inactiveButtonBorder),
                        modifier = Modifier.testTag("delete_images_cancel_button")
                    ) {
                        Text(text = strings.cancel, color = glass.textPrimary)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Button(
                        onClick = {
                            HapticsAndShareHelper.tapTick(view)
                            onConfirmDelete()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DangerRed,
                            contentColor = Color.White
                        ),
                        modifier = Modifier.testTag("delete_images_confirm_button")
                    ) {
                        Text(text = strings.delete)
                    }
                }
            }
        }
    }
}

@Composable
fun VerificationReportDialog(
    results: List<VerificationCheckResult>,
    isRunning: Boolean,
    strings: AppStrings,
    onRunSuite: () -> Unit,
    onDismiss: () -> Unit
) {
    val glass = LocalGlassPalette.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .padding(16.dp)
                .widthIn(max = 540.dp)
                .fillMaxWidth()
                .border(1.2.dp, glass.accentGlassBorderBrush, RoundedCornerShape(22.dp))
                .testTag("verification_report_dialog"),
            shape = RoundedCornerShape(22.dp),
            color = glass.glassHeaderFill
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Verified,
                            contentDescription = null,
                            tint = glass.accent
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = strings.verificationTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = glass.textPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (isRunning) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = glass.accent)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Running 8 end-to-end verification checks...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = glass.textSecondary
                            )
                        }
                    }
                } else if (results.isEmpty()) {
                    Text(
                        text = "Run the automated verification suite to execute all 8 end-to-end checks.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = glass.textSecondary
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth()
                    ) {
                        items(results, key = { it.stepNumber }) { item ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = glass.glassCardFill),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, glass.subtleGlassBorderBrush, RoundedCornerShape(12.dp))
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (item.passed) Icons.Default.CheckCircle else Icons.Default.Error,
                                            contentDescription = null,
                                            tint = if (item.passed) glass.accent else MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = item.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = glass.textPrimary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = item.details,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = glass.textSecondary
                                    )
                                    if (item.requiresPhysicalPhoneNote != null) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.PhoneAndroid,
                                                contentDescription = null,
                                                tint = WarningAmber,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = item.requiresPhysicalPhoneNote,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = WarningAmber
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(text = "Close", color = glass.textSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onRunSuite,
                        enabled = !isRunning,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = glass.accent,
                            contentColor = glass.onAccent
                        ),
                        modifier = Modifier.testTag("run_verification_suite_button")
                    ) {
                        Text(text = if (results.isEmpty()) "Run 8 Checks" else "Re-run 8 Checks")
                    }
                }
            }
        }
    }
}
