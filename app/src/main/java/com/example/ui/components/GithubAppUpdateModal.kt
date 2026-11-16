package com.example.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.AppUpdateUiState
import com.example.ui.theme.LocalGlassPalette
import com.example.util.HapticsAndShareHelper
import java.io.File
import java.util.Locale

@Composable
fun GithubAppUpdateDialog(
    updateState: AppUpdateUiState,
    isBn: Boolean,
    onDownloadAndInstall: () -> Unit,
    onDismiss: () -> Unit,
    onSuppressResumeLock: () -> Unit = {}
) {
    val updateInfo = updateState.availableUpdate ?: return
    val glass = LocalGlassPalette.current
    val context = LocalContext.current
    val view = LocalView.current

    val hasReadyLocalApk = !updateState.downloadedApkFilePath.isNullOrBlank() &&
        File(updateState.downloadedApkFilePath).exists()

    Dialog(
        onDismissRequest = {
            if (!updateState.isDownloadingApk) {
                onDismiss()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .clickable(enabled = !updateState.isDownloadingApk) { onDismiss() }
                .padding(horizontal = 20.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = glass.glassCardFill,
                border = BorderStroke(1.dp, glass.accentGlassBorderBrush),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable(enabled = false) {}
                    .testTag("github_update_modal")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 18.dp)
                ) {
                    // Header Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = glass.accentSoft,
                            border = BorderStroke(1.dp, glass.accent.copy(alpha = 0.55f)),
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.SystemUpdate,
                                    contentDescription = null,
                                    tint = glass.accent,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isBn) "নতুন আপডেট পাওয়া গেছে!" else "New Update Available!",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = glass.textPrimary
                            )
                            Text(
                                text = "v${updateInfo.currentVersionName} → v${updateInfo.remoteVersionName}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = glass.accent
                            )
                        }
                        IconButton(
                            onClick = {
                                if (!updateState.isDownloadingApk) {
                                    HapticsAndShareHelper.tapTick(view)
                                    onDismiss()
                                }
                            },
                            enabled = !updateState.isDownloadingApk,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("github_update_close_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = if (isBn) "বন্ধ করুন" else "Close",
                                tint = glass.textSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Friendly Update Summary Box (no raw technical markdown or changelogs)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = glass.inactiveButtonFill,
                        border = BorderStroke(1.dp, glass.inactiveButtonBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                        ) {
                            Text(
                                text = "Kagojpotro v${updateInfo.remoteVersionName}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = glass.textPrimary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (isBn) {
                                    "নতুন ফিচার, আরও দ্রুত পারফরম্যান্স এবং বাগ ফিক্স যুক্ত করা হয়েছে। আপনার সব ফোল্ডার, স্ক্যান ও সেটিংস সম্পূর্ণ অক্ষত থাকবে।"
                                } else {
                                    "New features, smoother performance, and bug fixes. All your folders, scans, and settings will remain completely safe."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = glass.textSecondary
                            )
                        }
                    }

                    val apkAsset = updateInfo.apkAsset

                    if (updateState.isDownloadingApk) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { (updateState.downloadProgressPercent / 100f).coerceIn(0f, 1f) },
                            color = glass.accent,
                            trackColor = glass.accentSoft,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .testTag("github_update_progress_bar")
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        val dlMb = String.format(Locale.US, "%.1f", updateState.downloadedBytes / (1024f * 1024f))
                        val totMb = String.format(Locale.US, "%.1f MB", updateState.totalBytes.coerceAtLeast(1L) / (1024f * 1024f))
                        Text(
                            text = if (isBn) {
                                "ডাউনলোড হচ্ছে: ${updateState.downloadProgressPercent}% ($dlMb / $totMb)"
                            } else {
                                "Downloading: ${updateState.downloadProgressPercent}% ($dlMb / $totMb)"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = glass.accent
                        )
                    }

                    if (!updateState.lastError.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = updateState.lastError,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Primary Action Buttons
                    if (apkAsset != null) {
                        Button(
                            onClick = {
                                HapticsAndShareHelper.performConfirm(view)
                                onDownloadAndInstall()
                            },
                            enabled = !updateState.isDownloadingApk,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = glass.accent,
                                contentColor = glass.onAccent
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 50.dp)
                                .testTag("github_update_install_button")
                        ) {
                            if (updateState.isDownloadingApk) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = glass.onAccent,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = if (isBn) "ডাউনলোড হচ্ছে..." else "Downloading...",
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = when {
                                        hasReadyLocalApk -> if (isBn) "এখনই ইনস্টল করুন (Install Update)" else "Install Update Now"
                                        else -> if (isBn) "ডাউনলোড ও আপডেট করুন" else "Download & Install Update"
                                    },
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                HapticsAndShareHelper.tapTick(view)
                                onSuppressResumeLock()
                                runCatching {
                                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo.htmlUrl)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(browserIntent)
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, glass.accentGlassBorderBrush),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 50.dp)
                                .testTag("github_update_open_browser_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInBrowser,
                                contentDescription = null,
                                tint = glass.accent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isBn) "ডাউনলোড পেজ খুলুন" else "Open Download Page",
                                fontWeight = FontWeight.Bold,
                                color = glass.accent
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

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
                            enabled = !updateState.isDownloadingApk,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("github_update_later_button")
                        ) {
                            Text(
                                text = if (isBn) "পরে করব" else "Later",
                                fontWeight = FontWeight.SemiBold,
                                color = glass.textSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}
