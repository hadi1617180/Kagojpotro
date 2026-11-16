package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.biometric.BiometricManager
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.R
import com.example.data.AppLanguage
import com.example.data.AppStorageSyncMode
import com.example.data.AppStrings
import com.example.data.AppThemeMode
import com.example.data.AppUpdateUiState
import com.example.data.FolderEntity
import com.example.data.GoogleDriveSyncStatus
import com.example.data.PcSyncConfig
import com.example.data.PcSyncRuntimeStatus
import com.example.data.PdfPageFormat
import com.example.data.PendingAccountSwitchPrompt
import com.example.data.PinLockoutState
import com.example.data.PinVerifyResult
import com.example.data.ScanEntity
import com.example.data.ScanFilter
import com.example.data.ScanResolutionPreset
import com.example.data.StorageUsageSummary
import com.example.ui.components.GoogleSyncGuideDialog
import com.example.ui.components.PcSetupGuideDialog
import com.example.ui.components.PhoneLocalGuideDialog
import com.example.ui.components.PinDialogMode
import com.example.ui.components.PinSetupDialog
import com.example.ui.components.QrScannerModal
import com.example.ui.components.TrashBinDialog
import com.example.ui.theme.DefaultAccentPresets
import com.example.ui.theme.LocalGlassPalette
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusRed
import com.example.ui.theme.parseHexColor
import com.example.util.HapticsAndShareHelper
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    appLanguage: AppLanguage,
    strings: AppStrings,
    isAppLocked: Boolean = false,
    themeMode: AppThemeMode,
    accentColorHex: String,
    defaultScanFilter: ScanFilter,
    scanResolutionPreset: ScanResolutionPreset,
    pdfPageFormat: PdfPageFormat,
    hapticsEnabled: Boolean,
    storageSummary: StorageUsageSummary,
    pcSyncConfig: PcSyncConfig = PcSyncConfig(),
    pcSyncStatus: PcSyncRuntimeStatus = PcSyncRuntimeStatus(),
    googleDriveStatus: GoogleDriveSyncStatus = GoogleDriveSyncStatus(),
    isZipBackupBusy: Boolean = false,
    trashedFolders: List<FolderEntity> = emptyList(),
    trashedScans: List<ScanEntity> = emptyList(),
    appLockEnabled: Boolean = false,
    hasAppPin: Boolean = false,
    biometricLockEnabled: Boolean = false,
    onBack: () -> Unit,
    onSetThemeMode: (AppThemeMode) -> Unit,
    onSetAccentColorHex: (String) -> Unit,
    onSetLanguage: (AppLanguage) -> Unit,
    onSetDefaultScanFilter: (ScanFilter) -> Unit = {},
    onSetScanResolutionPreset: (ScanResolutionPreset) -> Unit = {},
    onSetPdfPageFormat: (PdfPageFormat) -> Unit = {},
    onSetHapticsEnabled: (Boolean) -> Unit = {},
    onUpdatePcSyncConfig: (PcSyncConfig, Boolean) -> Unit = { _, _ -> },
    onShowStatusToast: (String) -> Unit = {},
    onTestPcConnection: (PcSyncConfig) -> Unit = {},
    onExportPcServerScript: () -> Unit = {},
    onConnectGoogleDriveAccount: (String, (Intent) -> Unit) -> Unit = { _, _ -> },
    onDisconnectGoogleDriveAccount: () -> Unit = {},
    onDeleteGoogleAccount: (onAccountDeleted: () -> Unit) -> Unit = {},
    onSetGoogleDriveAutoSync: (Boolean) -> Unit = {},
    onSyncWithGoogleDriveNow: (Boolean, (Intent) -> Unit) -> Unit = { _, _ -> },
    onExportZipBackup: (Uri) -> Unit = {},
    onRestoreZipBackup: (Uri) -> Unit = {},
    onClearTemporaryCache: () -> Unit,
    onOpenTrash: () -> Unit = {},
    onRestoreTrashedFolder: (String) -> Unit = {},
    onRestoreTrashedScan: (String) -> Unit = {},
    onRestoreAllTrash: () -> Unit = {},
    onPermanentDeleteTrashedFolder: (String) -> Unit = {},
    onPermanentDeleteTrashedScan: (String) -> Unit = {},
    onEmptyTrash: () -> Unit = {},
    onSetAppLockEnabled: (Boolean) -> Unit = {},
    onSetBiometricLockEnabled: (Boolean) -> Unit = {},
    pinLockoutState: PinLockoutState = PinLockoutState(),
    onVerifyCurrentPin: suspend (String) -> Boolean = { false },
    onVerifyCurrentPinResult: (suspend (String) -> PinVerifyResult)? = null,
    onSaveNewPin: suspend (String) -> Boolean = { false },
    onSuppressResumeLock: () -> Unit = {},
    pendingAccountSwitchPrompt: PendingAccountSwitchPrompt? = null,
    onConfirmAccountSwitch: (Boolean) -> Unit = {},
    onDismissAccountSwitchPrompt: () -> Unit = {},
    appUpdateState: AppUpdateUiState = AppUpdateUiState(),
    onCheckForAppUpdate: (String?) -> Unit = {},
    onDownloadAndInstallAppUpdate: () -> Unit = {},
    onOpenAppUpdateDialog: () -> Unit = {}
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    val glass = LocalGlassPalette.current
    val isBn = appLanguage == AppLanguage.BN
    val coroutineScope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    var showRainbowColorPicker by remember { mutableStateOf(false) }
    var showDeleteAccountConfirmDialog by remember { mutableStateOf(false) }
    var showPhoneLocalGuideDialog by remember { mutableStateOf(false) }
    var showPcGuideDialog by remember { mutableStateOf(false) }
    var showGoogleGuideDialog by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var showTrashBinDialog by remember { mutableStateOf(false) }
    var pinDialogMode by remember { mutableStateOf<PinDialogMode?>(null) }
    var showDeveloperProfileDialog by remember { mutableStateOf(false) }

    // Collapsible Accordion States
    var accountExpanded by rememberSaveable { mutableStateOf(true) }
    var securityExpanded by rememberSaveable { mutableStateOf(false) }
    var appearanceExpanded by rememberSaveable { mutableStateOf(false) }
    var storageExpanded by rememberSaveable { mutableStateOf(false) }
    var backupExpanded by rememberSaveable { mutableStateOf(false) }
    var updatesExpanded by rememberSaveable { mutableStateOf(false) }

    val biometricAvailable = remember(context) {
        BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    var isGoogleSigningIn by remember { mutableStateOf(false) }
    var expandedAccountDrawer by rememberSaveable {
        mutableStateOf(
            when (pcSyncConfig.mode) {
                AppStorageSyncMode.PC_SERVER_SYNC -> "PC_SERVER"
                AppStorageSyncMode.GOOGLE_CLOUD_SYNC -> "GOOGLE"
                AppStorageSyncMode.PHONE_ONLY -> "NONE"
            }
        )
    }
    LaunchedEffect(pcSyncConfig.mode) {
        expandedAccountDrawer = when (pcSyncConfig.mode) {
            AppStorageSyncMode.PC_SERVER_SYNC -> "PC_SERVER"
            AppStorageSyncMode.GOOGLE_CLOUD_SYNC -> "GOOGLE"
            AppStorageSyncMode.PHONE_ONLY -> "NONE"
        }
    }
    var serverUrlDraft by remember { mutableStateOf(pcSyncConfig.serverUrl) }
    var userDirDraft by remember { mutableStateOf(pcSyncConfig.userDirectory) }
    var lastCommittedServerUrl by remember { mutableStateOf(pcSyncConfig.serverUrl) }
    var lastCommittedUserDir by remember { mutableStateOf(pcSyncConfig.userDirectory) }
    LaunchedEffect(pcSyncConfig.serverUrl) {
        if (pcSyncConfig.serverUrl != lastCommittedServerUrl) {
            lastCommittedServerUrl = pcSyncConfig.serverUrl
            serverUrlDraft = pcSyncConfig.serverUrl
        }
    }
    LaunchedEffect(pcSyncConfig.userDirectory) {
        if (pcSyncConfig.userDirectory != lastCommittedUserDir) {
            lastCommittedUserDir = pcSyncConfig.userDirectory
            userDirDraft = pcSyncConfig.userDirectory
        }
    }

    val googleDriveConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            onSyncWithGoogleDriveNow(false) {}
        }
    }

    val zipExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            onExportZipBackup(uri)
        }
    }

    val zipRestoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onRestoreZipBackup(uri)
        }
    }

    // Silent auto sign-in check on startup if Google mode is selected
    LaunchedEffect(pcSyncConfig.mode) {
        if (pcSyncConfig.mode == AppStorageSyncMode.GOOGLE_CLOUD_SYNC &&
            googleDriveStatus.connectedEmail.isBlank()
        ) {
            HapticsAndShareHelper.attemptGoogleAutoSignIn(
                context = context,
                credentialManager = credentialManager,
                scope = coroutineScope,
                onAuthSuccess = { email ->
                    onConnectGoogleDriveAccount(email) { consentIntent ->
                        onSuppressResumeLock()
                        googleDriveConsentLauncher.launch(consentIntent)
                    }
                },
                onUnauthenticated = {}
            )
        }
    }

    fun signOutGoogle(
        credentialManager: CredentialManager,
        scope: kotlinx.coroutines.CoroutineScope,
        onSignOutComplete: () -> Unit = {}
    ) {
        HapticsAndShareHelper.signOutGoogleCredential(
            credentialManager = credentialManager,
            scope = scope,
            onSignOutComplete = onSignOutComplete
        )
    }

    BackHandler(onBack = onBack)

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(glass.glassHeaderFill)
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                // Exact same header position and spacing as Homepage:
                // Top-left dynamic accent-tinted Kagojpotro logo + "Settings"; top-right circular Back button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_paper_logo),
                            contentDescription = "Kagojpotro Logo",
                            tint = glass.accent,
                            modifier = Modifier
                                .size(38.dp)
                                .testTag("settings_header_logo")
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Text(
                            text = strings.settingsTitle,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = glass.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("settings_header_title")
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            HapticsAndShareHelper.tapTick(view)
                            onBack()
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("settings_header_back_button")
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
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = if (isBn) "ফিরে যান" else "Back",
                                tint = glass.accent,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. ACCOUNT & SYNC (Row 1: Phone Only & PC Server Sync | Row 2: Sign in with Google)
            item {
                val isPhoneOnly = pcSyncConfig.mode == AppStorageSyncMode.PHONE_ONLY
                val isPcSync = pcSyncConfig.mode == AppStorageSyncMode.PC_SERVER_SYNC
                val isGoogleMode = pcSyncConfig.mode == AppStorageSyncMode.GOOGLE_CLOUD_SYNC
                val connectedGoogleEmail = googleDriveStatus.connectedEmail.ifBlank {
                    Firebase.auth.currentUser?.email.orEmpty()
                }
                val accountSummary = when {
                    isPcSync -> if (isBn) "পিসি সার্ভার" else "PC Server"
                    isGoogleMode -> connectedGoogleEmail.ifBlank { "Google Drive" }
                    else -> if (isBn) "শুধু ফোনে" else "Phone Only"
                }

                ModernSettingsCard(
                    title = if (isBn) "অ্যাকাউন্ট ও সিঙ্ক" else "Account & Sync",
                    summary = accountSummary,
                    icon = Icons.Default.AccountCircle,
                    expanded = accountExpanded,
                    onToggleExpanded = { accountExpanded = !accountExpanded }
                ) {
                    val isPcDrawerExpanded = expandedAccountDrawer == "PC_SERVER"
                    val isGoogleDrawerExpanded = expandedAccountDrawer == "GOOGLE"
                    val isGoogleSignedIn = connectedGoogleEmail.isNotBlank() && Firebase.auth.currentUser != null

                    // 1st Row: Phone Local Storage (Full-width, left-aligned, right-aligned ? icon, uniform 60.dp height)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isPhoneOnly) glass.accent else glass.inactiveButtonFill,
                        border = BorderStroke(
                            width = if (isPhoneOnly) 1.5.dp else 1.dp,
                            color = if (isPhoneOnly) glass.accent else glass.inactiveButtonBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    HapticsAndShareHelper.performTick(view)
                                    expandedAccountDrawer = "NONE"
                                    signOutGoogle(
                                        credentialManager = credentialManager,
                                        scope = coroutineScope,
                                        onSignOutComplete = onDisconnectGoogleDriveAccount
                                    )
                                    onUpdatePcSyncConfig(
                                        pcSyncConfig.copy(
                                            mode = AppStorageSyncMode.PHONE_ONLY,
                                            serverUrl = serverUrlDraft.trim(),
                                            userDirectory = userDirDraft.trim(),
                                            accessKey = "",
                                            autoSyncEnabled = true
                                        ),
                                        false
                                    )
                                }
                                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
                                .testTag("settings_account_mode_phone_only"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhoneAndroid,
                                contentDescription = "Phone Local Storage",
                                tint = if (isPhoneOnly) glass.onAccent else glass.textPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (isBn) "ফোন লোকাল স্টোরেজ" else "Phone Local Storage",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (isPhoneOnly) glass.onAccent else glass.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )

                            IconButton(
                                onClick = {
                                    HapticsAndShareHelper.tapTick(view)
                                    showPhoneLocalGuideDialog = true
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("settings_phone_local_help_button")
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isPhoneOnly) glass.onAccent.copy(alpha = 0.2f) else glass.accentSoft
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = if (isPhoneOnly) glass.onAccent.copy(alpha = 0.55f) else glass.accent,
                                            shape = CircleShape
                                        )
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                                        contentDescription = if (isBn) "ফোন লোকাল স্টোরেজ গাইড" else "Phone Local Storage Guide",
                                        tint = if (isPhoneOnly) glass.onAccent else glass.accent,
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 2nd Row: PC Server Sync box (left-aligned icon + text + gap + red/green dot, right-aligned ? icon; click expands setup)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = glass.inactiveButtonFill,
                        border = BorderStroke(
                            width = if (isPcSync) 1.5.dp else 1.dp,
                            color = if (isPcSync) glass.accent else glass.inactiveButtonBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(animationSpec = tween(220))
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (isPcSync) glass.accent else Color.Transparent)
                                    .clickable {
                                        HapticsAndShareHelper.performTick(view)
                                        expandedAccountDrawer = if (isPcDrawerExpanded) "NONE" else "PC_SERVER"
                                    }
                                    .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
                                    .testTag("settings_account_mode_pc_sync"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Computer,
                                    contentDescription = "PC Server Sync",
                                    tint = if (isPcSync) glass.onAccent else glass.textPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = if (isBn) "পিসি সার্ভার সিঙ্ক" else "PC Server Sync",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isPcSync) glass.onAccent else glass.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(10.dp))

                                val pcDotColor by animateColorAsState(
                                    targetValue = if (pcSyncStatus.isPcOnline) StatusGreen else StatusRed,
                                    animationSpec = tween(220),
                                    label = "settings_pc_section_dot_color"
                                )
                                val pcDotDesc = if (pcSyncStatus.isPcOnline) {
                                    if (isBn) "পিসি সার্ভার সংযুক্ত" else "PC Server online"
                                } else {
                                    if (isBn) "পিসি সার্ভার অফলাইন" else "PC Server offline"
                                }
                                Box(
                                    modifier = Modifier
                                        .size(11.dp)
                                        .clip(CircleShape)
                                        .background(pcDotColor)
                                        .border(1.2.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                                        .semantics { contentDescription = pcDotDesc }
                                        .testTag("settings_pc_section_status_light")
                                )

                                Spacer(modifier = Modifier.weight(1f))

                                IconButton(
                                    onClick = {
                                        HapticsAndShareHelper.tapTick(view)
                                        showPcGuideDialog = true
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("settings_pc_help_button")
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isPcSync) glass.onAccent.copy(alpha = 0.2f) else glass.accentSoft
                                            )
                                            .border(
                                                width = 1.dp,
                                                color = if (isPcSync) glass.onAccent.copy(alpha = 0.55f) else glass.accent,
                                                shape = CircleShape
                                            )
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                                            contentDescription = if (isBn) "সেটআপ গাইড" else "PC Setup Guide",
                                            tint = if (isPcSync) glass.onAccent else glass.accent,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                }
                            }

                            AnimatedVisibility(
                                visible = isPcDrawerExpanded,
                                enter = fadeIn(tween(220)) + expandVertically(tween(220)),
                                exit = fadeOut(tween(180)) + shrinkVertically(tween(180))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
                                ) {
                                    // 1st Row: Download PC App button
                                    OutlinedButton(
                                        onClick = {
                                            HapticsAndShareHelper.performTick(view)
                                            onExportPcServerScript()
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, glass.accent),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("settings_pc_export_script_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Download,
                                            contentDescription = null,
                                            tint = glass.accent,
                                            modifier = Modifier.size(17.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (isBn) "পিসি অ্যাপ ডাউনলোড করুন" else "Download PC App",
                                            fontWeight = FontWeight.Bold,
                                            color = glass.accent,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    // 2nd Row: QR Scanner Button > Username TextField > Connect Button
                                    val hasScannedQrUrl = serverUrlDraft.isNotBlank()
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
                                                .testTag("settings_pc_scan_qr_button")
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
                                            value = userDirDraft,
                                            onValueChange = { newVal ->
                                                userDirDraft = newVal
                                            },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                            keyboardActions = KeyboardActions(
                                                onDone = {
                                                    focusManager.clearFocus()
                                                    val cleanUser = userDirDraft.trim()
                                                    userDirDraft = cleanUser
                                                    lastCommittedUserDir = cleanUser
                                                    onUpdatePcSyncConfig(
                                                        pcSyncConfig.copy(
                                                            serverUrl = serverUrlDraft.trim(),
                                                            userDirectory = cleanUser,
                                                            accessKey = "",
                                                            autoSyncEnabled = true
                                                        ),
                                                        false
                                                    )
                                                }
                                            ),
                                            label = {
                                                Text(if (isBn) "ইউজারনেম" else "Username")
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("settings_pc_user_dir_input"),
                                            shape = RoundedCornerShape(12.dp)
                                        )

                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = glass.accentSoft,
                                            border = BorderStroke(1.dp, glass.accentGlassBorderBrush),
                                            modifier = Modifier
                                                .size(56.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable(enabled = !pcSyncStatus.isSyncing) {
                                                    focusManager.clearFocus()
                                                    val cleanUrl = serverUrlDraft.trim()
                                                        .replace(Regex("[\\s\\u200B-\\u200D\\uFEFF]+"), "")
                                                    if (cleanUrl.isBlank()) {
                                                        HapticsAndShareHelper.tapTick(view)
                                                        onShowStatusToast(
                                                            if (isBn) "দয়া করে প্রথমে পিসির QR কোড স্ক্যান করুন"
                                                            else "Please scan the PC QR code first"
                                                        )
                                                    } else {
                                                        HapticsAndShareHelper.performConfirm(view)
                                                        val cleanUser = userDirDraft.trim()
                                                        serverUrlDraft = cleanUrl
                                                        userDirDraft = cleanUser
                                                        lastCommittedServerUrl = cleanUrl
                                                        lastCommittedUserDir = cleanUser
                                                        signOutGoogle(
                                                            credentialManager = credentialManager,
                                                            scope = coroutineScope,
                                                            onSignOutComplete = {}
                                                        )
                                                        onTestPcConnection(
                                                            pcSyncConfig.copy(
                                                                mode = AppStorageSyncMode.PC_SERVER_SYNC,
                                                                serverUrl = cleanUrl,
                                                                userDirectory = cleanUser,
                                                                accessKey = "",
                                                                autoSyncEnabled = true
                                                            )
                                                        )
                                                    }
                                                }
                                                .testTag("settings_pc_force_connect_button")
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                if (pcSyncStatus.isSyncing) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(22.dp),
                                                        color = glass.accent,
                                                        strokeWidth = 2.2.dp
                                                    )
                                                } else {
                                                    Icon(
                                                        imageVector = Icons.Default.Power,
                                                        contentDescription = if (isBn) "কানেক্ট করুন" else "Connect",
                                                        tint = glass.accent,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 3rd Row: Sign in with Google box (left-aligned icon + text, right-aligned ? icon, uniform 60.dp height)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = glass.inactiveButtonFill,
                        border = BorderStroke(
                            width = if (isGoogleMode) 1.5.dp else 1.dp,
                            color = if (isGoogleMode) glass.accent else glass.inactiveButtonBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(animationSpec = tween(220))
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (isGoogleMode) glass.accent else Color.Transparent)
                                    .clickable {
                                        HapticsAndShareHelper.performTick(view)
                                        expandedAccountDrawer = if (isGoogleDrawerExpanded) "NONE" else "GOOGLE"
                                    }
                                    .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
                                    .testTag("settings_account_mode_google"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isGoogleMode && connectedGoogleEmail.isNotBlank()) {
                                        Icons.Default.CloudDone
                                    } else {
                                        Icons.Default.AccountCircle
                                    },
                                    contentDescription = "Sign in with Google",
                                    tint = if (isGoogleMode) glass.onAccent else glass.textPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = if (isGoogleMode && connectedGoogleEmail.isNotBlank()) {
                                        "Google: $connectedGoogleEmail"
                                    } else {
                                        "Sign in with Google"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isGoogleMode) glass.onAccent else glass.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )

                                IconButton(
                                    onClick = {
                                        HapticsAndShareHelper.tapTick(view)
                                        showGoogleGuideDialog = true
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("settings_google_help_button")
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isGoogleMode) glass.onAccent.copy(alpha = 0.2f) else glass.accentSoft
                                            )
                                            .border(
                                                width = 1.dp,
                                                color = if (isGoogleMode) glass.onAccent.copy(alpha = 0.55f) else glass.accent,
                                                shape = CircleShape
                                            )
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                                            contentDescription = if (isBn) "গুগল ক্লাউড সিঙ্ক গাইড" else "Google Cloud Sync Guide",
                                            tint = if (isGoogleMode) glass.onAccent else glass.accent,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                }
                            }

                            AnimatedVisibility(
                                visible = isGoogleDrawerExpanded,
                                enter = fadeIn(tween(220)) + expandVertically(tween(220)),
                                exit = fadeOut(tween(180)) + shrinkVertically(tween(180))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp)
                                ) {
                                    if (!isGoogleSignedIn) {
                                        Button(
                                            onClick = {
                                                HapticsAndShareHelper.performConfirm(view)
                                                onSuppressResumeLock()
                                                isGoogleSigningIn = true
                                                HapticsAndShareHelper.performGoogleCredentialSignIn(
                                                    context = context,
                                                    credentialManager = credentialManager,
                                                    scope = coroutineScope,
                                                    onAuthSuccess = { email ->
                                                        isGoogleSigningIn = false
                                                        onConnectGoogleDriveAccount(email) { consentIntent ->
                                                            onSuppressResumeLock()
                                                            googleDriveConsentLauncher.launch(consentIntent)
                                                        }
                                                    },
                                                    onAuthError = { errMsg ->
                                                        isGoogleSigningIn = false
                                                        onShowStatusToast(errMsg)
                                                    },
                                                    onAuthCancelled = {
                                                        isGoogleSigningIn = false
                                                    }
                                                )
                                            },
                                            enabled = !isGoogleSigningIn,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = glass.accent,
                                                contentColor = glass.onAccent
                                            ),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(48.dp)
                                                .testTag("settings_google_continue_button")
                                        ) {
                                            if (isGoogleSigningIn) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(18.dp),
                                                    color = glass.onAccent,
                                                    strokeWidth = 2.dp
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Default.AccountCircle,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = if (isBn) "Google দিয়ে চালিয়ে যান" else "Continue with Google",
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    } else {
                                        if (!isGoogleMode) {
                                            Button(
                                                onClick = {
                                                    HapticsAndShareHelper.performConfirm(view)
                                                    onConnectGoogleDriveAccount(connectedGoogleEmail) { consentIntent ->
                                                        onSuppressResumeLock()
                                                        googleDriveConsentLauncher.launch(consentIntent)
                                                    }
                                                },
                                                enabled = !googleDriveStatus.isSyncing,
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = glass.accent,
                                                    contentColor = glass.onAccent
                                                ),
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(min = 48.dp)
                                                    .testTag("settings_google_switch_active_button")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.CloudDone,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = if (isBn) "Google Sync চালু করুন" else "Use Google Sync",
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(10.dp))
                                        }

                                        // Row 1: Email + subtitle on left, compact Sync icon button (no text) on right
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .padding(end = 10.dp)
                                            ) {
                                                Text(
                                                    text = connectedGoogleEmail,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = glass.textPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = googleDriveStatus.statusMessage,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = glass.accent,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }

                                            Surface(
                                                shape = RoundedCornerShape(12.dp),
                                                color = glass.accentSoft,
                                                border = BorderStroke(1.dp, glass.accentGlassBorderBrush),
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .clickable(enabled = !googleDriveStatus.isSyncing) {
                                                        HapticsAndShareHelper.performConfirm(view)
                                                        onSyncWithGoogleDriveNow(false) { consentIntent ->
                                                            onSuppressResumeLock()
                                                            googleDriveConsentLauncher.launch(consentIntent)
                                                        }
                                                    }
                                                    .testTag("settings_google_drive_sync_now_button")
                                            ) {
                                                Box(
                                                    contentAlignment = Alignment.Center,
                                                    modifier = Modifier.fillMaxSize()
                                                ) {
                                                    if (googleDriveStatus.isSyncing) {
                                                        CircularProgressIndicator(
                                                            modifier = Modifier.size(20.dp),
                                                            color = glass.accent,
                                                            strokeWidth = 2.dp
                                                        )
                                                    } else {
                                                        Icon(
                                                            imageVector = Icons.Default.Sync,
                                                            contentDescription = if (isBn) "গুগল ড্রাইভ সিঙ্ক করুন" else "Sync with Google Drive",
                                                            tint = glass.accent,
                                                            modifier = Modifier.size(22.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(10.dp))

                                        // Row 2: Sign Out on the left, Delete Account on the right
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedButton(
                                                onClick = {
                                                    HapticsAndShareHelper.performTick(view)
                                                    HapticsAndShareHelper.signOutGoogleCredential(
                                                        credentialManager = credentialManager,
                                                        scope = coroutineScope,
                                                        onSignOutComplete = onDisconnectGoogleDriveAccount
                                                    )
                                                },
                                                enabled = !googleDriveStatus.isSyncing,
                                                shape = RoundedCornerShape(12.dp),
                                                border = BorderStroke(1.dp, glass.inactiveButtonBorder),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .testTag("settings_google_drive_disconnect_button")
                                            ) {
                                                Text(
                                                    text = if (isBn) "সাইন আউট" else "Sign Out",
                                                    color = glass.textPrimary,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }

                                            OutlinedButton(
                                                onClick = {
                                                    HapticsAndShareHelper.performTick(view)
                                                    showDeleteAccountConfirmDialog = true
                                                },
                                                enabled = !googleDriveStatus.isSyncing,
                                                shape = RoundedCornerShape(12.dp),
                                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .testTag("settings_google_delete_account_button")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.DeleteForever,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(17.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = if (isBn) "অ্যাকাউন্ট ডিলিট" else "Delete Account",
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.error,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. SECURITY & APP LOCK
            item {
                val lockSummary = when {
                    appLockEnabled && biometricLockEnabled && biometricAvailable ->
                        if (isBn) "পিন ও বায়োমেট্রিক চালু" else "PIN & Biometrics On"
                    appLockEnabled -> if (isBn) "৪-সংখ্যার পিন চালু" else "4-Digit PIN On"
                    else -> if (isBn) "বন্ধ" else "Off"
                }
                ModernSettingsCard(
                    title = if (isBn) "নিরাপত্তা ও অ্যাপ লক" else "Security & App Lock",
                    summary = lockSummary,
                    icon = Icons.Default.Security,
                    expanded = securityExpanded,
                    onToggleExpanded = { securityExpanded = !securityExpanded }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = glass.accent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = if (isBn) "অ্যাপ পিন লক" else "App PIN Lock",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = glass.textPrimary
                                )
                                Text(
                                    text = if (isBn) "অ্যাপ খোলা ও রিজিউম করার সময় পিন চাইবে" else "Require 4-digit PIN on launch & resume",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = glass.textSecondary
                                )
                            }
                        }
                        Switch(
                            checked = appLockEnabled && hasAppPin,
                            onCheckedChange = { wantEnabled ->
                                HapticsAndShareHelper.performTick(view)
                                if (wantEnabled) {
                                    if (hasAppPin) {
                                        onSetAppLockEnabled(true)
                                    } else {
                                        pinDialogMode = PinDialogMode.SET_NEW
                                    }
                                } else if (hasAppPin) {
                                    pinDialogMode = PinDialogMode.VERIFY_TO_DISABLE
                                } else {
                                    onSetAppLockEnabled(false)
                                }
                            },
                            modifier = Modifier.testTag("settings_app_lock_toggle")
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = {
                            HapticsAndShareHelper.tapTick(view)
                            pinDialogMode = if (hasAppPin) PinDialogMode.CHANGE_EXISTING else PinDialogMode.SET_NEW
                        },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, glass.inactiveButtonBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_change_pin_button")
                    ) {
                        Icon(Icons.Default.Password, null, tint = glass.accent, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (hasAppPin) {
                                if (isBn) "পিন পরিবর্তন করুন" else "Change 4-Digit PIN"
                            } else {
                                if (isBn) "৪-সংখ্যার পিন সেট করুন" else "Set 4-Digit PIN"
                            },
                            fontWeight = FontWeight.Bold,
                            color = glass.accent
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = glass.dividerColor)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Fingerprint,
                                contentDescription = null,
                                tint = glass.accent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = if (isBn) "বায়োমেট্রিক আনলক" else "Biometric Unlock",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = glass.textPrimary
                                )
                                Text(
                                    text = if (!biometricAvailable) {
                                        if (isBn) "এই ডিভাইসে বায়োমেট্রিক সেট করা নেই" else "No biometric hardware enrolled"
                                    } else {
                                        if (isBn) "ফিঙ্গারপ্রিন্ট বা ফেস দিয়ে দ্রুত আনলক" else "Unlock quickly with fingerprint or face"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = glass.textSecondary
                                )
                            }
                        }
                        Switch(
                            checked = biometricLockEnabled && biometricAvailable && appLockEnabled && hasAppPin,
                            enabled = biometricAvailable && hasAppPin && appLockEnabled,
                            onCheckedChange = { enabled ->
                                HapticsAndShareHelper.performTick(view)
                                onSetBiometricLockEnabled(enabled)
                            },
                            modifier = Modifier.testTag("settings_biometric_toggle")
                        )
                    }
                }
            }

            // 3. APPEARANCE & THEME
            item {
                val systemIsDark = isSystemInDarkTheme()
                val isDarkTheme = when (themeMode) {
                    AppThemeMode.DARK -> true
                    AppThemeMode.LIGHT -> false
                    AppThemeMode.SYSTEM -> systemIsDark
                }
                val appearanceSummary = buildString {
                    append(if (isDarkTheme) (if (isBn) "ডার্ক থিম" else "Dark") else (if (isBn) "লাইট থিম" else "Light"))
                    append(" • ")
                    append(if (isBn) "বাংলা" else "English")
                }
                ModernSettingsCard(
                    title = if (isBn) "অ্যাপিয়ারেন্স ও থিম" else "Appearance & Theme",
                    summary = appearanceSummary,
                    icon = Icons.Default.Palette,
                    expanded = appearanceExpanded,
                    onToggleExpanded = { appearanceExpanded = !appearanceExpanded }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (isDarkTheme) Icons.Default.DarkMode else Icons.Default.LightMode,
                                contentDescription = null,
                                tint = glass.accent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isBn) {
                                    if (isDarkTheme) "থিম মোড (ডার্ক)" else "থিম মোড (লাইট)"
                                } else {
                                    if (isDarkTheme) "Theme Mode (Dark)" else "Theme Mode (Light)"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = glass.textPrimary
                            )
                        }

                        Switch(
                            checked = isDarkTheme,
                            onCheckedChange = { darkSelected ->
                                HapticsAndShareHelper.performTick(view)
                                onSetThemeMode(if (darkSelected) AppThemeMode.DARK else AppThemeMode.LIGHT)
                            },
                            thumbContent = {
                                Icon(
                                    imageVector = if (isDarkTheme) Icons.Default.DarkMode else Icons.Default.LightMode,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = glass.onAccent,
                                checkedTrackColor = glass.accent,
                                checkedIconColor = glass.accent,
                                uncheckedThumbColor = glass.accent,
                                uncheckedTrackColor = glass.inactiveButtonFill,
                                uncheckedBorderColor = glass.inactiveButtonBorder,
                                uncheckedIconColor = glass.onAccent
                            ),
                            modifier = Modifier.testTag("settings_theme_toggle")
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = glass.dividerColor)
                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = if (isBn) "অ্যাকসেন্ট রঙ" else "Accent Color",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = glass.textPrimary
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        DefaultAccentPresets.forEach { preset ->
                            val isSelected = accentColorHex.equals(preset.hex, ignoreCase = true)
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        HapticsAndShareHelper.performTick(view)
                                        onSetAccentColorHex(preset.hex)
                                    }
                                    .testTag("settings_accent_${preset.hex}")
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(preset.color)
                                        .border(
                                            width = if (isSelected) 3.dp else 1.5.dp,
                                            color = if (isSelected) glass.textPrimary else glass.inactiveButtonBorder,
                                            shape = CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        val onSwatch = if (preset.color.luminance() > 0.52f) {
                                            Color(0xFF1C201E)
                                        } else {
                                            Color.White
                                        }
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = preset.nameEn,
                                            tint = onSwatch,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }

                        val isCustomSelected = DefaultAccentPresets.none {
                            it.hex.equals(accentColorHex, ignoreCase = true)
                        }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable {
                                    HapticsAndShareHelper.performTick(view)
                                    showRainbowColorPicker = true
                                }
                                .testTag("settings_accent_custom_rainbow")
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(
                                        brush = Brush.sweepGradient(
                                            colors = listOf(
                                                Color(0xFFFF3B30),
                                                Color(0xFFFF9500),
                                                Color(0xFFFFCC00),
                                                Color(0xFF34C759),
                                                Color(0xFF00C7BE),
                                                Color(0xFF007AFF),
                                                Color(0xFFAF52DE),
                                                Color(0xFFFF2D55),
                                                Color(0xFFFF3B30)
                                            )
                                        )
                                    )
                                    .border(
                                        width = if (isCustomSelected) 3.dp else 1.5.dp,
                                        color = if (isCustomSelected) glass.textPrimary else glass.inactiveButtonBorder,
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isCustomSelected) Icons.Default.Check else Icons.Default.ColorLens,
                                    contentDescription = "Custom Color",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = glass.dividerColor)
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = null,
                                tint = glass.accent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isBn) "অ্যাপের ভাষা" else "App Language",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = glass.textPrimary
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppLanguage.entries.forEach { lang ->
                                val selected = appLanguage == lang
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (selected) glass.accent else glass.inactiveButtonFill,
                                    border = BorderStroke(
                                        width = 1.dp,
                                        color = if (selected) glass.accent else glass.inactiveButtonBorder
                                    ),
                                    modifier = Modifier
                                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            HapticsAndShareHelper.performTick(view)
                                            onSetLanguage(lang)
                                        }
                                        .testTag("settings_lang_${lang.code}")
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                    ) {
                                        Text(
                                            text = if (lang == AppLanguage.EN) "English" else "বাংলা",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (selected) glass.onAccent else glass.textPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. OFFLINE BACKUP & RESTORE (.zip)
            item {
                ModernSettingsCard(
                    title = if (isBn) "অফলাইন ব্যাকআপ ও রিস্টোর" else "Offline Backup & Restore",
                    summary = ".zip Archive",
                    icon = Icons.Default.Archive,
                    expanded = backupExpanded,
                    onToggleExpanded = { backupExpanded = !backupExpanded }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                HapticsAndShareHelper.performConfirm(view)
                                onSuppressResumeLock()
                                zipExportLauncher.launch("Kagojpotro_Backup.zip")
                            },
                            enabled = !isZipBackupBusy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = glass.accent,
                                contentColor = glass.onAccent
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("settings_export_zip_button")
                        ) {
                            if (isZipBackupBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = glass.onAccent,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Archive,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isBn) "এক্সপোর্ট .zip" else "Export .zip",
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                HapticsAndShareHelper.performConfirm(view)
                                onSuppressResumeLock()
                                zipRestoreLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                            },
                            enabled = !isZipBackupBusy,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, glass.inactiveButtonBorder),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("settings_restore_zip_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Unarchive,
                                contentDescription = null,
                                tint = glass.accent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isBn) "রিস্টোর .zip" else "Restore .zip",
                                fontWeight = FontWeight.Bold,
                                color = glass.accent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // 5. TRASH & STORAGE
            item {
                val totalSavedBytes = storageSummary.savedScansBytes + storageSummary.originalsBytes
                val clearableTempBytes = storageSummary.cacheBytes
                val trashedFolderIdsSet = remember(trashedFolders) { trashedFolders.map { it.id }.toSet() }
                val trashDisplayCount = remember(trashedFolders, trashedScans, trashedFolderIdsSet) {
                    val rootDeletedFolders = trashedFolders.count { it.parentId == null || it.parentId !in trashedFolderIdsSet }
                    val standaloneScans = trashedScans.count { it.folderId !in trashedFolderIdsSet }
                    rootDeletedFolders + standaloneScans
                }

                ModernSettingsCard(
                    title = if (isBn) "ট্র্যাশ ও স্টোরেজ" else "Trash & Storage",
                    summary = "${trashDisplayCount} ${if (isBn) "টি ট্র্যাশে" else "in Trash"} • ${formatStorageSize(totalSavedBytes)}",
                    icon = Icons.Default.Storage,
                    expanded = storageExpanded,
                    onToggleExpanded = { storageExpanded = !storageExpanded }
                ) {
                    // 7-Day Trash Bin Row
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = glass.inactiveButtonFill,
                        border = BorderStroke(1.dp, glass.inactiveButtonBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                HapticsAndShareHelper.tapTick(view)
                                onOpenTrash()
                                showTrashBinDialog = true
                            }
                            .testTag("settings_open_trash_button")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.RestoreFromTrash,
                                contentDescription = null,
                                tint = glass.accent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isBn) "৭-দিনের ট্র্যাশ বিন" else "7-Day Trash Bin",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = glass.textPrimary
                                )
                                Text(
                                    text = if (isBn) "ডিলিট করা ফোল্ডার ও ছবি রিস্টোর করুন" else "Restore or permanently delete items",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = glass.textSecondary
                                )
                            }
                            Surface(
                                shape = CircleShape,
                                color = glass.accentSoft
                            ) {
                                Text(
                                    text = trashDisplayCount.toString(),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = glass.accent,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.Default.ChevronRight, null, tint = glass.textSecondary)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        StorageStatTile(
                            label = if (isBn) "ফোন স্টোরেজ" else "Phone Storage",
                            value = formatStorageSize(totalSavedBytes),
                            subValue = "${storageSummary.totalScansCount} ${if (isBn) "টি স্ক্যান" else "scans"}",
                            modifier = Modifier.weight(1f)
                        )
                        StorageStatTile(
                            label = if (isBn) "পিসি সার্ভার" else "PC Server",
                            value = if (pcSyncStatus.pcFreeSpaceBytes > 0L) {
                                formatStorageSize(pcSyncStatus.pcFreeSpaceBytes) + (if (isBn) " খালি" else " Free")
                            } else {
                                "${storageSummary.syncedScansCount}/${storageSummary.totalScansCount} Synced"
                            },
                            subValue = if (storageSummary.pendingSyncScansCount > 0) {
                                "${storageSummary.pendingSyncScansCount} ${if (isBn) "টি পেন্ডিং" else "pending"}"
                            } else {
                                if (isBn) "সব সিঙ্কড" else "All synced"
                            },
                            modifier = Modifier.weight(1f)
                        )
                        StorageStatTile(
                            label = if (isBn) "মোট আইটেম" else "Total Items",
                            value = "${storageSummary.totalFoldersCount}",
                            subValue = if (isBn) "টি ফোল্ডার" else "folders",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedButton(
                        onClick = {
                            HapticsAndShareHelper.performConfirm(view)
                            onClearTemporaryCache()
                        },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, glass.inactiveButtonBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_clear_cache_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CleaningServices,
                            contentDescription = null,
                            tint = glass.accent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isBn) {
                                "অস্থায়ী ক্যাশে পরিষ্কার করুন (${formatStorageSize(clearableTempBytes)})"
                            } else {
                                "Clear Temp Cache (${formatStorageSize(clearableTempBytes)})"
                            },
                            fontWeight = FontWeight.Bold,
                            color = glass.accent
                        )
                    }
                }
            }

            // 6. APP UPDATES
            item {
                Spacer(modifier = Modifier.height(14.dp))
                val hasUpdate = appUpdateState.availableUpdate != null
                val updateSub = if (hasUpdate) {
                    if (isBn) {
                        "নতুন আপডেট আছে: v${appUpdateState.availableUpdate?.remoteVersionName}"
                    } else {
                        "Update available: v${appUpdateState.availableUpdate?.remoteVersionName}"
                    }
                } else {
                    if (isBn) {
                        "বর্তমান ভার্সন: v${appUpdateState.currentVersionName}"
                    } else {
                        "Current version: v${appUpdateState.currentVersionName}"
                    }
                }
                ModernSettingsCard(
                    title = if (isBn) "আপডেট" else "Update",
                    summary = updateSub,
                    icon = Icons.Default.SystemUpdate,
                    expanded = updatesExpanded || hasUpdate,
                    onToggleExpanded = {
                        HapticsAndShareHelper.tapTick(view)
                        updatesExpanded = !updatesExpanded
                    }
                ) {
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            HapticsAndShareHelper.performConfirm(view)
                            onCheckForAppUpdate(null)
                        },
                        enabled = !appUpdateState.isChecking && !appUpdateState.isDownloadingApk,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = glass.accent,
                            contentColor = glass.onAccent
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("settings_check_update_button")
                    ) {
                        if (appUpdateState.isChecking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = glass.onAccent,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.SystemUpdate,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (appUpdateState.isChecking) {
                                if (isBn) "চেক হচ্ছে..." else "Checking..."
                            } else {
                                if (isBn) "আপডেট চেক করুন" else "Check for update"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (!appUpdateState.lastStatusMessage.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = appUpdateState.lastStatusMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (appUpdateState.lastError != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                glass.accent
                            }
                        )
                    }
                }
            }

            // 7. DEVELOPER CREDIT CARD
            item {
                Spacer(modifier = Modifier.height(18.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = glass.inactiveButtonFill,
                    border = BorderStroke(1.dp, glass.inactiveButtonBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            HapticsAndShareHelper.tapTick(view)
                            showDeveloperProfileDialog = true
                        }
                        .testTag("settings_developer_card")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = glass.accentSoft,
                            border = BorderStroke(1.dp, glass.accent.copy(alpha = 0.5f)),
                            modifier = Modifier.size(28.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Code,
                                    contentDescription = null,
                                    tint = glass.accent,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "App developed by Abdullahil Hadi",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = glass.textPrimary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                Spacer(modifier = Modifier.height(28.dp))
            }
        }
    }

    // Developer Profile Popup Modal matching Image 1
    if (showDeveloperProfileDialog && !isAppLocked) {
        Dialog(
            onDismissRequest = { showDeveloperProfileDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.75f))
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .clickable { showDeveloperProfileDialog = false }
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(26.dp),
                    color = glass.glassCardFill,
                    border = BorderStroke(1.dp, glass.accentGlassBorderBrush),
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 420.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .clickable(enabled = false) {}
                        .testTag("developer_profile_modal")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Top row with orange close X button on top-right
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            IconButton(
                                onClick = {
                                    HapticsAndShareHelper.tapTick(view)
                                    showDeveloperProfileDialog = false
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("close_developer_modal_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = glass.accent,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        // Circular Profile Avatar with vibrant accent ring border
                        Surface(
                            shape = CircleShape,
                            color = glass.accentSoft,
                            border = BorderStroke(3.dp, glass.accent),
                            modifier = Modifier
                                .size(118.dp)
                                .testTag("developer_profile_photo")
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.developer_profile),
                                contentDescription = "Abdullahil Hadi Profile",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Developer Name & Title
                        Text(
                            text = "Abdullahil Hadi",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = glass.textPrimary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "App Developer",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = glass.accent,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // 4 Functional Action Cards
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.navigationBars),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // 1. Phone Card
                            DeveloperContactActionRow(
                                title = "Phone",
                                value = "+8801991617180",
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.Phone,
                                        contentDescription = "Phone",
                                        tint = glass.accent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                testTag = "developer_contact_phone",
                                onClick = {
                                    HapticsAndShareHelper.tapTick(view)
                                    HapticsAndShareHelper.openDialer(context, "+8801991617180")
                                }
                            )

                            // 2. WhatsApp Card
                            DeveloperContactActionRow(
                                title = "WhatsApp",
                                value = "+8801991617180",
                                icon = {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_social_whatsapp),
                                        contentDescription = "WhatsApp",
                                        tint = glass.accent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                testTag = "developer_contact_whatsapp",
                                onClick = {
                                    HapticsAndShareHelper.tapTick(view)
                                    HapticsAndShareHelper.openWhatsApp(context, "+8801991617180")
                                }
                            )

                            // 3. Email Card
                            DeveloperContactActionRow(
                                title = "Email",
                                value = "hadi280994@gmail.com",
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.Email,
                                        contentDescription = "Email",
                                        tint = glass.accent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                testTag = "developer_contact_email",
                                onClick = {
                                    HapticsAndShareHelper.tapTick(view)
                                    HapticsAndShareHelper.openEmail(context, "hadi280994@gmail.com")
                                }
                            )

                            // 4. Facebook Card
                            DeveloperContactActionRow(
                                title = "Facebook",
                                value = "Abdullahil Hadi",
                                icon = {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_social_facebook),
                                        contentDescription = "Facebook",
                                        tint = glass.accent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                testTag = "developer_contact_facebook",
                                onClick = {
                                    HapticsAndShareHelper.tapTick(view)
                                    HapticsAndShareHelper.openFacebook(context, "https://www.facebook.com/hadi280994")
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }
            }
        }
    }

    if (showDeleteAccountConfirmDialog && !isAppLocked) {
        AlertDialog(
            onDismissRequest = { showDeleteAccountConfirmDialog = false },
            title = {
                Text(
                    text = if (isBn) "ক্লাউড অ্যাকাউন্ট ডিলিট করবেন?" else "Delete Cloud Account?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (isBn) {
                        "এটি আপনার Google Cloud/Drive থেকে সিঙ্ক করা সব ডেটা মুছে ফেলবে এবং সাইন আউট করবে। আপনার ফোনে থাকা সব ফোল্ডার ও ফাইল সম্পূর্ণ নিরাপদ ও অক্ষত থাকবে।"
                    } else {
                        "This will delete your synced data from Google Cloud/Drive and sign you out. All folders and files on your phone will remain safe and intact."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        HapticsAndShareHelper.performConfirm(view)
                        showDeleteAccountConfirmDialog = false
                        onDeleteGoogleAccount {
                            signOutGoogle(
                                credentialManager = credentialManager,
                                scope = coroutineScope,
                                onSignOutComplete = {}
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        text = if (isBn) "ডিলিট ও সাইন আউট" else "Delete & Sign Out",
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAccountConfirmDialog = false }) {
                    Text(if (isBn) "বাতিল" else "Cancel")
                }
            }
        )
    }

    if (pendingAccountSwitchPrompt != null && !isAppLocked) {
        AlertDialog(
            onDismissRequest = onDismissAccountSwitchPrompt,
            title = {
                Text(
                    text = if (isBn) "অ্যাকাউন্ট পরিবর্তন" else "Switch Sync Account",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = if (isBn) {
                            "ফোনে বর্তমানে \"${pendingAccountSwitchPrompt.previousAccountLabel}\"-এর ফাইল রয়েছে। \"${pendingAccountSwitchPrompt.newAccountLabel}\"-এ সুইচ করার সময় ফাইলগুলো কীভাবে রাখতে চান?"
                        } else {
                            "This phone currently has files from \"${pendingAccountSwitchPrompt.previousAccountLabel}\". How would you like to handle them when switching to \"${pendingAccountSwitchPrompt.newAccountLabel}\"?"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Button(
                        onClick = {
                            HapticsAndShareHelper.performConfirm(view)
                            onConfirmAccountSwitch(false)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = glass.accent,
                            contentColor = glass.onAccent
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("account_switch_clean_button")
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            Text(
                                text = if (isBn) "আলাদা রাখুন (Switch Cleanly)" else "Switch Cleanly",
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isBn) {
                                    "আগের অ্যাকাউন্টের ফাইল ক্লাউডে নিরাপদ রেখে শুধু নতুন অ্যাকাউন্টের ফাইল লোড করুন"
                                } else {
                                    "Clear previous account's local copy and load only the new account"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = glass.onAccent.copy(alpha = 0.9f)
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            HapticsAndShareHelper.performConfirm(view)
                            onConfirmAccountSwitch(true)
                        },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, glass.accentGlassBorderBrush),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("account_switch_merge_button")
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            Text(
                                text = if (isBn) "একসাথে মেলান (Merge Files)" else "Merge Files",
                                fontWeight = FontWeight.Bold,
                                color = glass.accent
                            )
                            Text(
                                text = if (isBn) {
                                    "ফোনের বর্তমান সব ফাইল নতুন অ্যাকাউন্টের সাথে একসাথে সিঙ্ক করুন"
                                } else {
                                    "Keep current phone files and sync them into the new account"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = glass.textSecondary
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = onDismissAccountSwitchPrompt,
                    modifier = Modifier.testTag("account_switch_cancel_button")
                ) {
                    Text(if (isBn) "বাতিল" else "Cancel")
                }
            }
        )
    }

    if (showRainbowColorPicker && !isAppLocked) {
        RainbowWheelColorPickerDialog(
            initialHex = accentColorHex,
            isBn = isBn,
            onDismiss = { showRainbowColorPicker = false },
            onConfirmHex = { chosenHex ->
                onSetAccentColorHex(chosenHex)
                showRainbowColorPicker = false
            }
        )
    }

    if (showPhoneLocalGuideDialog && !isAppLocked) {
        PhoneLocalGuideDialog(
            isBn = isBn,
            onDismiss = { showPhoneLocalGuideDialog = false }
        )
    }

    if (showPcGuideDialog && !isAppLocked) {
        PcSetupGuideDialog(
            isBn = isBn,
            onDismiss = { showPcGuideDialog = false }
        )
    }

    if (showGoogleGuideDialog && !isAppLocked) {
        GoogleSyncGuideDialog(
            isBn = isBn,
            onDismiss = { showGoogleGuideDialog = false }
        )
    }

    if (showQrScanner && !isAppLocked) {
        QrScannerModal(
            isBn = isBn,
            isAppLocked = isAppLocked,
            onUrlScanned = { scannedUrl ->
                showQrScanner = false
                val cleanUrl = scannedUrl.trim().replace(Regex("[\\s\\u200B-\\u200D\\uFEFF]+"), "")
                serverUrlDraft = cleanUrl
                lastCommittedServerUrl = cleanUrl
                val updated = pcSyncConfig.copy(
                    serverUrl = cleanUrl,
                    userDirectory = userDirDraft.trim(),
                    accessKey = "",
                    autoSyncEnabled = true
                )
                onUpdatePcSyncConfig(updated, false)
            },
            onDismiss = { showQrScanner = false }
        )
    }

    if (showTrashBinDialog && !isAppLocked) {
        TrashBinDialog(
            isBn = isBn,
            isAppLocked = isAppLocked,
            trashedFolders = trashedFolders,
            trashedScans = trashedScans,
            onRestoreFolder = onRestoreTrashedFolder,
            onRestoreScan = onRestoreTrashedScan,
            onRestoreAll = onRestoreAllTrash,
            onPermanentDeleteFolder = onPermanentDeleteTrashedFolder,
            onPermanentDeleteScan = onPermanentDeleteTrashedScan,
            onEmptyTrash = onEmptyTrash,
            onDismiss = { showTrashBinDialog = false }
        )
    }

    val activePinMode = pinDialogMode
    if (activePinMode != null && !isAppLocked) {
        PinSetupDialog(
            isBn = isBn,
            mode = activePinMode,
            pinLockoutState = pinLockoutState,
            onVerifyCurrentPin = onVerifyCurrentPin,
            onVerifyCurrentPinResult = onVerifyCurrentPinResult,
            onSaveNewPin = onSaveNewPin,
            onDisableVerified = { onSetAppLockEnabled(false) },
            onDismiss = { pinDialogMode = null }
        )
    }
}


@Composable
fun PcStatusLightPill(
    isOnline: Boolean,
    isBn: Boolean,
    modifier: Modifier = Modifier,
    testTag: String = "pc_status_light"
) {
    val dotColor by animateColorAsState(
        targetValue = if (isOnline) StatusGreen else StatusRed,
        animationSpec = tween(220),
        label = "pc_status_dot_color"
    )
    val label = if (isOnline) {
        if (isBn) "পিসি সার্ভার সংযুক্ত" else "PC Server online"
    } else {
        if (isBn) "পিসি সার্ভার অফলাইন" else "PC Server offline"
    }
    Box(
        modifier = modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(dotColor)
            .semantics { contentDescription = label }
            .testTag(testTag)
    )
}

@Composable
private fun ModernSettingsCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    summary: String? = null,
    expanded: Boolean = true,
    onToggleExpanded: () -> Unit = {},
    headerTrailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val glass = LocalGlassPalette.current
    val view = LocalView.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = glass.glassCardFill,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
        modifier = Modifier
            .widthIn(max = 680.dp)
            .fillMaxWidth()
            .border(
                width = 1.dp,
                brush = glass.subtleGlassBorderBrush,
                shape = RoundedCornerShape(16.dp)
            )
            .animateContentSize(animationSpec = tween(220))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        HapticsAndShareHelper.tapTick(view)
                        onToggleExpanded()
                    }
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(glass.accentSoft),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = glass.accent,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = glass.textPrimary
                    )
                    if (!expanded && !summary.isNullOrBlank()) {
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = glass.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (headerTrailing != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    headerTrailing()
                }
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = glass.textSecondary,
                    modifier = Modifier.size(22.dp)
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(14.dp))
                content()
            }
        }
    }
}

@Composable
private fun StorageStatTile(
    label: String,
    value: String,
    subValue: String,
    modifier: Modifier = Modifier
) {
    val glass = LocalGlassPalette.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = glass.inactiveButtonFill,
        border = BorderStroke(1.dp, glass.inactiveButtonBorder),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = glass.accent
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = glass.textPrimary
            )
            Text(
                text = subValue,
                style = MaterialTheme.typography.labelSmall,
                color = glass.textSecondary
            )
        }
    }
}

@Composable
private fun RainbowWheelColorPickerDialog(
    initialHex: String,
    isBn: Boolean,
    onDismiss: () -> Unit,
    onConfirmHex: (String) -> Unit
) {
    val glass = LocalGlassPalette.current
    val initialColor = remember(initialHex) { parseHexColor(initialHex) }
    val initialHsv = remember(initialColor) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(
            android.graphics.Color.rgb(
                (initialColor.red * 255).toInt(),
                (initialColor.green * 255).toInt(),
                (initialColor.blue * 255).toInt()
            ),
            hsv
        )
        hsv
    }

    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1].coerceIn(0.15f, 1f)) }
    var valueBrightness by remember { mutableFloatStateOf(initialHsv[2].coerceIn(0.25f, 1f)) }

    val currentColor = remember(hue, saturation, valueBrightness) {
        Color.hsv(hue, saturation, valueBrightness)
    }
    val currentHex = remember(currentColor) {
        val r = (currentColor.red * 255).toInt().coerceIn(0, 255)
        val g = (currentColor.green * 255).toInt().coerceIn(0, 255)
        val b = (currentColor.blue * 255).toInt().coerceIn(0, 255)
        String.format(Locale.US, "#%02X%02X%02X", r, g, b)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, glass.subtleGlassBorderBrush, RoundedCornerShape(22.dp))
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isBn) "কাস্টম অ্যাকসেন্ট রঙ" else "Custom Accent Color",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = glass.textPrimary
                )

                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier.size(210.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    val cx = size.width / 2f
                                    val cy = size.height / 2f
                                    val radius = min(cx, cy)
                                    val dx = offset.x - cx
                                    val dy = offset.y - cy
                                    val dist = hypot(dx, dy)
                                    val angleDeg = ((atan2(dy, dx) * 180f / Math.PI.toFloat()) + 360f) % 360f
                                    hue = angleDeg
                                    saturation = (dist / radius).coerceIn(0f, 1f)
                                }
                            }
                            .pointerInput(Unit) {
                                detectDragGestures { change, _ ->
                                    change.consume()
                                    val cx = size.width / 2f
                                    val cy = size.height / 2f
                                    val radius = min(cx, cy)
                                    val dx = change.position.x - cx
                                    val dy = change.position.y - cy
                                    val dist = hypot(dx, dy)
                                    val angleDeg = ((atan2(dy, dx) * 180f / Math.PI.toFloat()) + 360f) % 360f
                                    hue = angleDeg
                                    saturation = (dist / radius).coerceIn(0f, 1f)
                                }
                            }
                    ) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val radius = min(cx, cy)

                        drawCircle(
                            brush = Brush.sweepGradient(
                                colors = listOf(
                                    Color.hsv(0f, 1f, valueBrightness),
                                    Color.hsv(60f, 1f, valueBrightness),
                                    Color.hsv(120f, 1f, valueBrightness),
                                    Color.hsv(180f, 1f, valueBrightness),
                                    Color.hsv(240f, 1f, valueBrightness),
                                    Color.hsv(300f, 1f, valueBrightness),
                                    Color.hsv(360f, 1f, valueBrightness)
                                ),
                                center = Offset(cx, cy)
                            ),
                            radius = radius,
                            center = Offset(cx, cy)
                        )

                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.hsv(0f, 0f, valueBrightness),
                                    Color.Transparent
                                ),
                                center = Offset(cx, cy),
                                radius = radius
                            ),
                            radius = radius,
                            center = Offset(cx, cy)
                        )

                        val rad = Math.toRadians(hue.toDouble())
                        val thumbDist = saturation * radius
                        val tx = cx + (cos(rad) * thumbDist).toFloat()
                        val ty = cy + (sin(rad) * thumbDist).toFloat()

                        drawCircle(
                            color = Color.White,
                            radius = 12.dp.toPx(),
                            center = Offset(tx, ty)
                        )
                        drawCircle(
                            color = currentColor,
                            radius = 9.dp.toPx(),
                            center = Offset(tx, ty)
                        )
                        drawCircle(
                            color = Color.Black.copy(alpha = 0.35f),
                            radius = 12.dp.toPx(),
                            center = Offset(tx, ty),
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isBn) "উজ্জ্বলতা" else "Brightness",
                        style = MaterialTheme.typography.labelMedium,
                        color = glass.textSecondary,
                        modifier = Modifier.width(74.dp)
                    )
                    Slider(
                        value = valueBrightness,
                        onValueChange = { valueBrightness = it },
                        valueRange = 0.25f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = currentColor,
                            activeTrackColor = currentColor
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(glass.inactiveButtonFill)
                        .border(1.dp, glass.inactiveButtonBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(currentColor)
                                .border(1.dp, Color.White, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = currentHex,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = glass.textPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(if (isBn) "বাতিল" else "Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    val applyContentColor = if (currentColor.luminance() > 0.52f) {
                        Color(0xFF1C201E)
                    } else {
                        Color.White
                    }
                    Button(
                        onClick = { onConfirmHex(currentHex) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = currentColor,
                            contentColor = applyContentColor
                        )
                    ) {
                        Text(
                            text = if (isBn) "প্রয়োগ করুন" else "Apply",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

private fun formatStorageSize(bytes: Long): String {
    return when {
        bytes >= 1024L * 1024L * 1024L -> String.format(
            Locale.US,
            "%.2f GB",
            bytes / (1024f * 1024f * 1024f)
        )
        bytes >= 1024L * 1024L -> String.format(
            Locale.US,
            "%.2f MB",
            bytes / (1024f * 1024f)
        )
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
        else -> "$bytes B"
    }
}

@Composable
private fun DeveloperContactActionRow(
    title: String,
    value: String,
    icon: @Composable () -> Unit,
    testTag: String,
    onClick: () -> Unit
) {
    val glass = LocalGlassPalette.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = glass.inactiveButtonFill,
        border = BorderStroke(1.dp, glass.inactiveButtonBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = glass.accentSoft,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    icon()
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = glass.textSecondary
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = glass.textPrimary
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = glass.accent,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
