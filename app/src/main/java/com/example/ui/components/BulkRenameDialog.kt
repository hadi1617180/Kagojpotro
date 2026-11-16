package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.WarningAmber
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.AppStrings
import com.example.data.BulkNumberPadding
import com.example.data.BulkRenameConfig
import com.example.data.BulkRenameMode
import com.example.data.BulkRenameOrder
import com.example.data.BulkRenamePlanner
import com.example.data.BulkRenameResult
import com.example.data.FolderEntity
import com.example.data.ScanEntity
import com.example.ui.theme.LocalGlassPalette
import com.example.util.HapticsAndShareHelper
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BulkRenameDialog(
    selectedScans: List<ScanEntity>,
    allActiveScans: List<ScanEntity>,
    foldersById: Map<String, FolderEntity>,
    currentFolderName: String?,
    strings: AppStrings,
    onDismiss: () -> Unit,
    onApplyBulkRename: suspend (Map<String, String>) -> BulkRenameResult
) {
    val view = LocalView.current
    val glass = LocalGlassPalette.current
    val isBn = strings.isBn
    val scope = rememberCoroutineScope()

    val defaultPattern = remember(currentFolderName) {
        val base = BulkRenamePlanner.sanitizeBaseName(currentFolderName.orEmpty())
        if (base.isNotEmpty()) "${base}_{num}" else "{folder}_{num}"
    }

    var mode by remember { mutableStateOf(BulkRenameMode.PATTERN) }
    var patternField by remember {
        mutableStateOf(
            TextFieldValue(
                text = defaultPattern,
                selection = TextRange(defaultPattern.length)
            )
        )
    }
    var startNumber by remember { mutableIntStateOf(1) }
    var padding by remember {
        mutableStateOf(if (selectedScans.size >= 100) BulkNumberPadding.THREE else BulkNumberPadding.TWO)
    }
    var order by remember { mutableStateOf(BulkRenameOrder.OLDEST_FIRST) }

    var findText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }

    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    val config = remember(mode, patternField.text, startNumber, padding, order, findText, replaceText, matchCase) {
        BulkRenameConfig(
            mode = mode,
            pattern = patternField.text,
            startNumber = startNumber,
            padding = padding,
            order = order,
            findText = findText,
            replaceText = replaceText,
            matchCase = matchCase
        )
    }

    val previews = remember(selectedScans, allActiveScans, foldersById, config) {
        BulkRenamePlanner.buildPreview(
            selectedScansInDisplayOrder = selectedScans,
            allActiveScans = allActiveScans,
            foldersById = foldersById,
            config = config
        )
    }

    val invalidCount = remember(previews) { previews.count { it.isEmptyOrInvalid } }
    val disambiguatedCount = remember(previews) { previews.count { it.hasAutoDisambiguation } }
    val changedCount = remember(previews) { previews.count { it.willChange } }

    val canApply = !isSubmitting &&
        previews.isNotEmpty() &&
        invalidCount == 0 &&
        changedCount > 0 &&
        (mode != BulkRenameMode.FIND_REPLACE || findText.isNotEmpty())

    fun insertToken(token: String) {
        HapticsAndShareHelper.tapTick(view)
        errorMessage = null
        val current = patternField.text
        val start = patternField.selection.start.coerceIn(0, current.length)
        val end = patternField.selection.end.coerceIn(0, current.length)
        val minIdx = minOf(start, end)
        val maxIdx = maxOf(start, end)
        val prefix = current.substring(0, minIdx)
        val suffix = current.substring(maxIdx)
        val needsUnderscore = prefix.isNotEmpty() && !prefix.endsWith("_") && !prefix.endsWith("-") && !prefix.endsWith(" ")
        val inserted = if (needsUnderscore) "_$token" else token
        val updated = prefix + inserted + suffix
        val newCursor = prefix.length + inserted.length
        patternField = TextFieldValue(text = updated, selection = TextRange(newCursor))
    }

    fun submitNow() {
        if (!canApply) return
        isSubmitting = true
        errorMessage = null
        val proposed = previews.associate { it.scan.id to it.finalNewBaseName }
        scope.launch {
            val result = onApplyBulkRename(proposed)
            isSubmitting = false
            when (result) {
                is BulkRenameResult.Success -> onDismiss()
                is BulkRenameResult.Error -> errorMessage = result.message
            }
        }
    }

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
                .background(Color.Black.copy(alpha = 0.56f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .border(1.5.dp, glass.accentGlassBorderBrush, RoundedCornerShape(22.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .testTag("bulk_rename_dialog"),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = glass.glassHeaderFill),
                elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DriveFileRenameOutline,
                                contentDescription = null,
                                tint = glass.accent,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = if (isBn) "বাল্ক রিনেম (${selectedScans.size}টি)" else "Bulk Rename (${selectedScans.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = glass.textPrimary
                                )
                                Text(
                                    text = if (isBn) "প্যাটার্ন বা প্রতিস্থাপন দিয়ে একসাথে নাম বদলান" else "Rename multiple scans with a pattern",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = glass.textSecondary
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
                                .testTag("bulk_rename_close_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = strings.cancel,
                                tint = glass.textSecondary
                            )
                        }
                    }

                    // Mode Toggle Chips (Pattern vs Find & Replace)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = mode == BulkRenameMode.PATTERN,
                            onClick = {
                                HapticsAndShareHelper.tapTick(view)
                                mode = BulkRenameMode.PATTERN
                                errorMessage = null
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.DriveFileRenameOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = if (isBn) "প্যাটার্ন" else "Pattern",
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = glass.accentSoft,
                                selectedLabelColor = glass.accent,
                                selectedLeadingIconColor = glass.accent
                            ),
                            modifier = Modifier.testTag("bulk_rename_mode_pattern")
                        )
                        FilterChip(
                            selected = mode == BulkRenameMode.FIND_REPLACE,
                            onClick = {
                                HapticsAndShareHelper.tapTick(view)
                                mode = BulkRenameMode.FIND_REPLACE
                                errorMessage = null
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.FindReplace,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = if (isBn) "খুঁজুন ও বদলান" else "Find & Replace",
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = glass.accentSoft,
                                selectedLabelColor = glass.accent,
                                selectedLeadingIconColor = glass.accent
                            ),
                            modifier = Modifier.testTag("bulk_rename_mode_replace")
                        )
                    }

                    if (mode == BulkRenameMode.PATTERN) {
                        OutlinedTextField(
                            value = patternField,
                            onValueChange = {
                                patternField = it
                                errorMessage = null
                            },
                            singleLine = true,
                            label = {
                                Text(if (isBn) "নামের প্যাটার্ন" else "Name pattern")
                            },
                            placeholder = {
                                Text("e.g. Receipt_{num}_{date}")
                            },
                            trailingIcon = {
                                if (patternField.text.isNotEmpty()) {
                                    IconButton(
                                        onClick = {
                                            patternField = TextFieldValue("")
                                            errorMessage = null
                                        },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = strings.clear,
                                            tint = glass.textSecondary
                                        )
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("bulk_rename_pattern_input")
                        )

                        // Tap-to-insert token chips
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val tokens = listOf(
                                "{num}" to (if (isBn) "+ ক্রমিক {num}" else "+ {num} Index"),
                                "{date}" to (if (isBn) "+ তারিখ {date}" else "+ {date} Date"),
                                "{folder}" to (if (isBn) "+ ফোল্ডার {folder}" else "+ {folder}"),
                                "{original}" to (if (isBn) "+ আগের নাম {original}" else "+ {original}")
                            )
                            tokens.forEach { (token, chipLabel) ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = glass.inactiveButtonFill,
                                    border = BorderStroke(1.dp, glass.inactiveButtonBorder),
                                    modifier = Modifier
                                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { insertToken(token) }
                                        .testTag("bulk_rename_token_${token.trim('{', '}')}")
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                    ) {
                                        Text(
                                            text = chipLabel,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = glass.textPrimary
                                        )
                                    }
                                }
                            }
                        }

                        // Numbering start, padding & sequence order controls
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Start number stepper
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = glass.glassCardFill,
                                border = BorderStroke(1.dp, glass.subtleGlassBorderBrush)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (isBn) "শুরু:" else "Start:",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = glass.textSecondary,
                                        modifier = Modifier.padding(start = 6.dp, end = 2.dp)
                                    )
                                    IconButton(
                                        onClick = {
                                            HapticsAndShareHelper.tapTick(view)
                                            if (startNumber > 0) startNumber--
                                        },
                                        enabled = startNumber > 0,
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Remove,
                                            contentDescription = "Decrease start number",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Text(
                                        text = startNumber.toString(),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = glass.textPrimary,
                                        modifier = Modifier.testTag("bulk_rename_start_number_value")
                                    )
                                    IconButton(
                                        onClick = {
                                            HapticsAndShareHelper.tapTick(view)
                                            startNumber++
                                        },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = "Increase start number",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            // Padding selector
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                BulkNumberPadding.entries.forEach { pad ->
                                    val selected = padding == pad
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (selected) glass.accent else glass.inactiveButtonFill,
                                        border = BorderStroke(
                                            1.dp,
                                            if (selected) glass.accent else glass.inactiveButtonBorder
                                        ),
                                        modifier = Modifier
                                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable {
                                                HapticsAndShareHelper.tapTick(view)
                                                padding = pad
                                            }
                                            .testTag("bulk_rename_pad_${pad.label}")
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                        ) {
                                            Text(
                                                text = pad.label,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = if (selected) glass.onAccent else glass.textPrimary
                                            )
                                        }
                                    }
                                }
                            }

                            // Sequence order selector
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val orderOptions = listOf(
                                    BulkRenameOrder.OLDEST_FIRST to (if (isBn) "পুরোনো আগে" else "Oldest"),
                                    BulkRenameOrder.NEWEST_FIRST to (if (isBn) "নতুন আগে" else "Newest"),
                                    BulkRenameOrder.CURRENT_ORDER to (if (isBn) "বর্তমান" else "View")
                                )
                                orderOptions.forEach { (ord, label) ->
                                    val selected = order == ord
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (selected) glass.accent else glass.inactiveButtonFill,
                                        border = BorderStroke(
                                            1.dp,
                                            if (selected) glass.accent else glass.inactiveButtonBorder
                                        ),
                                        modifier = Modifier
                                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable {
                                                HapticsAndShareHelper.tapTick(view)
                                                order = ord
                                            }
                                            .testTag("bulk_rename_order_${ord.name}")
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                        ) {
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (selected) glass.onAccent else glass.textPrimary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // Find & Replace mode
                        OutlinedTextField(
                            value = findText,
                            onValueChange = {
                                findText = it
                                errorMessage = null
                            },
                            singleLine = true,
                            label = { Text(if (isBn) "খুঁজুন" else "Find text") },
                            placeholder = { Text("e.g. Scan_") },
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Next,
                                keyboardType = KeyboardType.Text
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("bulk_rename_find_input")
                        )
                        OutlinedTextField(
                            value = replaceText,
                            onValueChange = {
                                replaceText = it
                                errorMessage = null
                            },
                            singleLine = true,
                            label = { Text(if (isBn) "নতুন টেক্সট" else "Replace with") },
                            placeholder = { Text("e.g. Invoice_") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("bulk_rename_replace_input")
                        )
                        FilterChip(
                            selected = matchCase,
                            onClick = {
                                HapticsAndShareHelper.tapTick(view)
                                matchCase = !matchCase
                            },
                            label = {
                                Text(if (isBn) "বড়/ছোট হাতের অক্ষর মিলান" else "Match case")
                            },
                            modifier = Modifier.testTag("bulk_rename_match_case_chip")
                        )
                    }

                    // Live Before -> After Preview
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = glass.glassCardFill,
                        border = BorderStroke(1.dp, glass.subtleGlassBorderBrush),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isBn) "প্রিভিউ (${changedCount}/${previews.size} পরিবর্তন)" else "Preview ($changedCount/${previews.size} will change)",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = glass.textPrimary
                                )
                                if (disambiguatedCount > 0) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.WarningAmber,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.tertiary,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isBn) "$disambiguatedCount টিতে অটো-নম্বর" else "$disambiguatedCount auto-numbered",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.tertiary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(color = glass.dividerColor)

                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 180.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(previews, key = { it.scan.id }) { item ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = item.oldBaseName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = glass.textSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(0.95f)
                                        )
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = null,
                                            tint = glass.accent,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = if (item.isEmptyOrInvalid) {
                                                if (isBn) "(খালি নাম)" else "(empty)"
                                            } else {
                                                item.finalNewBaseName
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (item.willChange) FontWeight.Bold else FontWeight.Normal,
                                            color = when {
                                                item.isEmptyOrInvalid -> MaterialTheme.colorScheme.error
                                                item.hasAutoDisambiguation -> MaterialTheme.colorScheme.tertiary
                                                item.willChange -> glass.accent
                                                else -> glass.textSecondary
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1.15f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (invalidCount > 0) {
                        Text(
                            text = if (isBn) {
                                "এক বা একাধিক ফাইলের নাম খালি হচ্ছে — অনুগ্রহ করে প্যাটার্ন ঠিক করুন।"
                            } else {
                                "One or more filenames resolve to empty — adjust your pattern."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else if (errorMessage != null) {
                        Text(
                            text = errorMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    // Action Buttons
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
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("bulk_rename_cancel_button")
                        ) {
                            Text(strings.cancel)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                HapticsAndShareHelper.performConfirm(view)
                                submitNow()
                            },
                            enabled = canApply,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = glass.accent,
                                contentColor = glass.onAccent
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("bulk_rename_apply_button")
                        ) {
                            if (isSubmitting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = glass.onAccent
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                text = if (isBn) "নাম পরিবর্তন ($changedCount)" else "Apply ($changedCount)",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
