package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.R
import com.example.data.AppLanguage
import com.example.ui.components.PcSetupGuideDialog
import com.example.ui.components.QrScannerModal
import com.example.ui.theme.LocalGlassPalette
import com.example.ui.theme.StatusGreen
import com.example.util.HapticsAndShareHelper
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@Composable
fun StartupOnboardingScreen(
    appLanguage: AppLanguage,
    isAppLocked: Boolean = false,
    onSelectLocalStorage: () -> Unit,
    onSetupPcSync: (serverUrl: String, username: String, onResult: (Boolean, String) -> Unit) -> Unit,
    onCompleteWithGoogle: (email: String, onConsentNeeded: (Intent) -> Unit, onResult: (String) -> Unit) -> Unit,
    onExportPcServerScript: () -> Unit = {},
    onSuppressResumeLock: () -> Unit = {}
) {
    val context = LocalContext.current
    val view = LocalView.current
    val glass = LocalGlassPalette.current
    val isBn = appLanguage == AppLanguage.BN
    val coroutineScope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    var showPcSetupDialog by rememberSaveable { mutableStateOf(false) }
    var showPcGuideDialog by rememberSaveable { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var pcServerUrlDraft by rememberSaveable { mutableStateOf("") }
    var pcUsernameDraft by rememberSaveable { mutableStateOf("") }
    var isTestingPcConnection by remember { mutableStateOf(false) }
    var pcErrorText by remember { mutableStateOf<String?>(null) }

    var isGoogleSigningIn by remember { mutableStateOf(false) }
    var googleErrorText by remember { mutableStateOf<String?>(null) }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("startup_onboarding_screen"),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.weight(0.6f))

            Column(
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            // 1. App Logo with Dynamic Accent Color (clean, borderless)
            Icon(
                painter = painterResource(id = R.drawable.ic_paper_logo),
                contentDescription = "Kagojpotro Logo",
                tint = glass.accent,
                modifier = Modifier
                    .size(72.dp)
                    .testTag("startup_paper_logo")
            )

            Spacer(modifier = Modifier.height(18.dp))

            // 2. App Title (Strictly "কাগজপত্র", without instruction subtitle)
            Text(
                text = "কাগজপত্র",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
                color = glass.textPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(28.dp))

            // 3. The 3 Primary Choice Buttons
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Button 1: [Local Storage] (Phone Only)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = glass.glassCardFill,
                    border = BorderStroke(1.2.dp, glass.inactiveButtonBorder),
                    tonalElevation = 2.dp,
                    shadowElevation = 1.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            HapticsAndShareHelper.performConfirm(view)
                            onSelectLocalStorage()
                        }
                        .testTag("startup_button_local_storage")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(glass.accentSoft),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhoneAndroid,
                                contentDescription = null,
                                tint = glass.accent,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isBn) "লোকাল স্টোরেজ (Local Storage)" else "Local Storage",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = glass.textPrimary
                            )
                            Text(
                                text = if (isBn) "শুধুমাত্র আপনার ফোনে সুরক্ষিতভাবে থাকবে" else "Keep files safely on this device only",
                                style = MaterialTheme.typography.bodySmall,
                                color = glass.textSecondary
                            )
                        }
                    }
                }

                // Button 2: [PC Server Sync]
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = glass.glassCardFill,
                    border = BorderStroke(1.2.dp, glass.inactiveButtonBorder),
                    tonalElevation = 2.dp,
                    shadowElevation = 1.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            HapticsAndShareHelper.performTick(view)
                            pcErrorText = null
                            showPcSetupDialog = true
                        }
                        .testTag("startup_button_pc_sync")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(glass.accentSoft),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Computer,
                                contentDescription = null,
                                tint = glass.accent,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isBn) "পিসি সার্ভার সিঙ্ক (PC Server Sync)" else "PC Server Sync",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = glass.textPrimary
                            )
                            Text(
                                text = if (isBn) "আপনার নিজস্ব কম্পিউটার ফোল্ডারে স্বয়ংক্রিয় সিঙ্ক" else "Auto sync to your personal computer server",
                                style = MaterialTheme.typography.bodySmall,
                                color = glass.textSecondary
                            )
                        }
                    }
                }

                // Button 3: [Sign in with Google]
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = glass.glassCardFill,
                    border = BorderStroke(1.2.dp, glass.inactiveButtonBorder),
                    tonalElevation = 2.dp,
                    shadowElevation = 1.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(enabled = !isGoogleSigningIn) {
                            HapticsAndShareHelper.performConfirm(view)
                            googleErrorText = null
                            isGoogleSigningIn = true
                            onSuppressResumeLock()
                            HapticsAndShareHelper.performGoogleCredentialSignIn(
                                context = context,
                                credentialManager = credentialManager,
                                scope = coroutineScope,
                                onAuthSuccess = { email ->
                                    isGoogleSigningIn = false
                                    onCompleteWithGoogle(email, {}) {}
                                },
                                onAuthError = { errMsg ->
                                    isGoogleSigningIn = false
                                    googleErrorText = errMsg
                                },
                                onAuthCancelled = {
                                    isGoogleSigningIn = false
                                }
                            )
                        }
                        .testTag("startup_button_google_signin")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(glass.accentSoft),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isGoogleSigningIn) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    color = glass.accent,
                                    strokeWidth = 2.2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.AccountCircle,
                                    contentDescription = null,
                                    tint = glass.accent,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isBn) "Google সাইন ইন (Cloud Sync)" else "Sign in with Google",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = glass.textPrimary
                            )
                            Text(
                                text = if (isBn) "Google Drive ও ক্লাউড অটো-সিঙ্ক ব্যাকআপ" else "Auto sync to your Google Drive & Cloud",
                                style = MaterialTheme.typography.bodySmall,
                                color = glass.textSecondary
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = googleErrorText != null,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut()
            ) {
                Text(
                    text = googleErrorText.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }

    // PC Server Sync Setup Popup Dialog
    if (showPcSetupDialog && !isAppLocked) {
        fun submitPcSetup() {
            if (isTestingPcConnection) return
            HapticsAndShareHelper.performConfirm(view)
            pcErrorText = null
            if (pcServerUrlDraft.isBlank()) {
                pcErrorText = if (isBn) "দয়া করে প্রথমে পিসির QR কোড স্ক্যান করুন" else "Please scan the PC QR code first"
                return
            }
            isTestingPcConnection = true
            onSetupPcSync(pcServerUrlDraft, pcUsernameDraft) { success, msg ->
                isTestingPcConnection = false
                if (success) {
                    showPcSetupDialog = false
                } else {
                    pcErrorText = msg
                }
            }
        }
        val hasScannedQrUrl = pcServerUrlDraft.isNotBlank()
        AlertDialog(
            onDismissRequest = {
                if (!isTestingPcConnection) {
                    showPcSetupDialog = false
                    pcErrorText = null
                }
            },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Computer,
                        contentDescription = null,
                        tint = glass.accent,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isBn) "পিসি সার্ভার সেটআপ" else "PC Server Setup",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            HapticsAndShareHelper.tapTick(view)
                            showPcGuideDialog = true
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("startup_pc_help_button")
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(glass.accentSoft)
                                .border(1.dp, glass.accentGlassBorderBrush, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                                contentDescription = if (isBn) "সেটআপ নির্দেশনা" else "Setup Instructions",
                                tint = glass.accent,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .imePadding()
                ) {
                    // 1st Row: Download PC App Button
                    OutlinedButton(
                        onClick = {
                            HapticsAndShareHelper.performTick(view)
                            onExportPcServerScript()
                        },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, glass.accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("startup_pc_download_app_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            tint = glass.accent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isBn) "পিসি অ্যাপ ডাউনলোড করুন" else "Download PC App",
                            color = glass.accent,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 2nd Row: QR Scanner Button > Username TextField > Connect Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = glass.accentSoft,
                            border = BorderStroke(
                                width = if (hasScannedQrUrl) 1.5.dp else 1.dp,
                                color = if (hasScannedQrUrl) StatusGreen else glass.accent
                            ),
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    HapticsAndShareHelper.tapTick(view)
                                    showQrScanner = true
                                }
                                .testTag("startup_pc_scan_qr_button")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.QrCodeScanner,
                                    contentDescription = if (isBn) "QR স্ক্যান" else "Scan QR",
                                    tint = glass.accent,
                                    modifier = Modifier.size(24.dp)
                                )
                                if (hasScannedQrUrl) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = if (isBn) "QR স্ক্যান সম্পন্ন" else "QR Scanned",
                                        tint = StatusGreen,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                            .size(14.dp)
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = pcUsernameDraft,
                            onValueChange = { pcUsernameDraft = it },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submitPcSetup() }),
                            label = {
                                Text(if (isBn) "ইউজারনেম" else "Username")
                            },
                            placeholder = {
                                Text("e.g. hadi")
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("startup_pc_username_input"),
                            shape = RoundedCornerShape(12.dp)
                        )

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = glass.accent,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = !isTestingPcConnection) {
                                    submitPcSetup()
                                }
                                .testTag("startup_pc_connect_button")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (isTestingPcConnection) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        color = glass.onAccent,
                                        strokeWidth = 2.2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Power,
                                        contentDescription = if (isBn) "কানেক্ট করুন" else "Connect",
                                        tint = glass.onAccent,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (pcErrorText != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = pcErrorText.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = {
                        showPcSetupDialog = false
                        pcErrorText = null
                    },
                    enabled = !isTestingPcConnection
                ) {
                    Text(if (isBn) "বাতিল" else "Cancel")
                }
            }
        )
    }

    if (showPcGuideDialog && !isAppLocked) {
        PcSetupGuideDialog(
            isBn = isBn,
            onDismiss = { showPcGuideDialog = false }
        )
    }

    if (showQrScanner && !isAppLocked) {
        QrScannerModal(
            isBn = isBn,
            isAppLocked = isAppLocked,
            onUrlScanned = { scannedUrl ->
                showQrScanner = false
                pcServerUrlDraft = scannedUrl.trim()
                pcErrorText = null
            },
            onDismiss = { showQrScanner = false }
        )
    }
}

