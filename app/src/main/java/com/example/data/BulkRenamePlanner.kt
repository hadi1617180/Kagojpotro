package com.example.data

import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class BulkRenameMode {
    PATTERN,
    FIND_REPLACE
}

enum class BulkRenameOrder {
    CURRENT_ORDER,
    OLDEST_FIRST,
    NEWEST_FIRST
}

enum class BulkNumberPadding(val digits: Int, val label: String) {
    NONE(1, "1"),
    TWO(2, "01"),
    THREE(3, "001")
}

data class BulkRenameConfig(
    val mode: BulkRenameMode = BulkRenameMode.PATTERN,
    val pattern: String = "{folder}_{num}",
    val startNumber: Int = 1,
    val padding: BulkNumberPadding = BulkNumberPadding.TWO,
    val order: BulkRenameOrder = BulkRenameOrder.OLDEST_FIRST,
    val findText: String = "",
    val replaceText: String = "",
    val matchCase: Boolean = false
)

data class BulkRenamePreviewItem(
    val scan: ScanEntity,
    val oldBaseName: String,
    val rawNewBaseName: String,
    val finalNewBaseName: String,
    val hasAutoDisambiguation: Boolean,
    val isEmptyOrInvalid: Boolean
) {
    val willChange: Boolean
        get() = !isEmptyOrInvalid &&
            normalizeForCompare(finalNewBaseName) != normalizeForCompare(oldBaseName) ||
            (finalNewBaseName != oldBaseName && !isEmptyOrInvalid)

    private fun normalizeForCompare(value: String): String {
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)
    }
}

object BulkRenamePlanner {

    private val invalidFilenameChars = Regex("[\\\\/:*?\"<>|]")

    fun sanitizeBaseName(raw: String): String {
        return stripImageExtension(raw)
            .replace(invalidFilenameChars, "_")
            .trim()
    }

    fun normalizeForComparison(name: String): String {
        return Normalizer.normalize(name.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)
    }

    fun orderScans(
        scans: List<ScanEntity>,
        order: BulkRenameOrder
    ): List<ScanEntity> {
        return when (order) {
            BulkRenameOrder.CURRENT_ORDER -> scans
            BulkRenameOrder.OLDEST_FIRST -> scans.sortedWith(
                compareBy<ScanEntity> { it.capturedAt }.thenBy { it.filename.lowercase(Locale.ROOT) }
            )
            BulkRenameOrder.NEWEST_FIRST -> scans.sortedWith(
                compareByDescending<ScanEntity> { it.capturedAt }.thenBy { it.filename.lowercase(Locale.ROOT) }
            )
        }
    }

    fun buildPreview(
        selectedScansInDisplayOrder: List<ScanEntity>,
        allActiveScans: List<ScanEntity>,
        foldersById: Map<String, FolderEntity>,
        config: BulkRenameConfig
    ): List<BulkRenamePreviewItem> {
        if (selectedScansInDisplayOrder.isEmpty()) return emptyList()

        val orderedScans = orderScans(selectedScansInDisplayOrder, config.order)
        val selectedIds = orderedScans.map { it.id }.toSet()

        // Reserve names of UNSELECTED scans per folder so bulk rename never collides with untouched files.
        val reservedByFolder = mutableMapOf<String, MutableSet<String>>()
        for (scan in allActiveScans) {
            if (scan.id !in selectedIds) {
                val norm = normalizeForComparison(stripImageExtension(scan.filename))
                reservedByFolder.getOrPut(scan.folderId) { mutableSetOf() }.add(norm)
            }
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val safeStart = config.startNumber.coerceAtLeast(0)

        return orderedScans.mapIndexed { index, scan ->
            val oldBase = stripImageExtension(scan.filename)
            val folderName = sanitizeBaseName(foldersById[scan.folderId]?.name.orEmpty())
                .ifEmpty { "Document" }
            val dateStr = dateFormat.format(Date(scan.capturedAt))
            val seqStr = (safeStart + index).toString().padStart(config.padding.digits, '0')

            val rawExpanded = when (config.mode) {
                BulkRenameMode.PATTERN -> {
                    config.pattern
                        .replace("{num}", seqStr, ignoreCase = true)
                        .replace("{date}", dateStr, ignoreCase = true)
                        .replace("{folder}", folderName, ignoreCase = true)
                        .replace("{original}", oldBase, ignoreCase = true)
                }
                BulkRenameMode.FIND_REPLACE -> {
                    if (config.findText.isEmpty()) {
                        oldBase
                    } else {
                        oldBase.replace(
                            oldValue = config.findText,
                            newValue = config.replaceText,
                            ignoreCase = !config.matchCase
                        )
                    }
                }
            }

            val sanitized = sanitizeBaseName(rawExpanded)
            if (sanitized.isEmpty()) {
                BulkRenamePreviewItem(
                    scan = scan,
                    oldBaseName = oldBase,
                    rawNewBaseName = "",
                    finalNewBaseName = oldBase,
                    hasAutoDisambiguation = false,
                    isEmptyOrInvalid = true
                )
            } else {
                val reserved = reservedByFolder.getOrPut(scan.folderId) { mutableSetOf() }
                var candidate = sanitized
                var disambiguated = false
                if (!reserved.add(normalizeForComparison(candidate))) {
                    disambiguated = true
                    var suffix = 2
                    while (true) {
                        val suffixed = "${sanitized}_$suffix"
                        if (reserved.add(normalizeForComparison(suffixed))) {
                            candidate = suffixed
                            break
                        }
                        suffix++
                    }
                }
                BulkRenamePreviewItem(
                    scan = scan,
                    oldBaseName = oldBase,
                    rawNewBaseName = sanitized,
                    finalNewBaseName = candidate,
                    hasAutoDisambiguation = disambiguated,
                    isEmptyOrInvalid = false
                )
            }
        }
    }
}
