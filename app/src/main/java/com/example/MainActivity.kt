package com.example

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.AppLanguage
import com.example.processing.DocumentImageProcessor
import com.example.ui.AppScreen
import com.example.ui.KagojpotroViewModel
import com.example.ui.components.AppLockOverlay
import com.example.ui.components.DestinationPickerModal
import com.example.ui.components.VerificationReportDialog
import com.example.ui.screens.BatchReviewScreen
import com.example.ui.screens.CameraCaptureScreen
import com.example.ui.screens.HomeAndFolderBrowserScreen
import com.example.ui.screens.PdfBuilderScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SingleImageScreen
import com.example.ui.screens.StartupOnboardingScreen
import com.example.ui.theme.KagojpotroTheme
import com.example.ui.theme.ObsidianSurface
import com.example.util.HapticsAndShareHelper
import kotlinx.coroutines.delay

class MainActivity : FragmentActivity() {
    private var incomingSharedImageUris by mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DocumentImageProcessor.init(applicationContext)
        if (savedInstanceState == null) {
            val shared = HapticsAndShareHelper.extractSharedImageUris(intent)
            if (shared.isNotEmpty()) {
                incomingSharedImageUris = shared
            }
        }
        enableEdgeToEdge()
        setContent {
            KagojpotroApp(
                pendingSharedUris = incomingSharedImageUris,
                onConsumeSharedUris = { incomingSharedImageUris = emptyList() }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val shared = HapticsAndShareHelper.extractSharedImageUris(intent)
        if (shared.isNotEmpty()) {
            incomingSharedImageUris = shared
        }
    }
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

private fun launchBiometricPrompt(
    activity: FragmentActivity,
    isBn: Boolean,
    onSuccess: () -> Unit
) {
    val executor = ContextCompat.getMainExecutor(activity)
    val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }
        }
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(if (isBn) "কাগজপত্র আনলক করুন" else "Unlock কাগজপত্র")
        .setSubtitle(
            if (isBn) "অ্যাপ ব্যবহার করতে আপনার ফিংগারপ্রিন্ট বা ফেস ব্যবহার করুন"
            else "Use your fingerprint or face unlock to continue"
        )
        .setNegativeButtonText(if (isBn) "পিন ব্যবহার করুন" else "Use PIN")
        .build()
    runCatching { prompt.authenticate(info) }
}

@Composable
fun KagojpotroApp(
    viewModel: KagojpotroViewModel = viewModel(),
    pendingSharedUris: List<Uri> = emptyList(),
    onConsumeSharedUris: () -> Unit = {}
) {
    val context = LocalContext.current
    val isBootstrapped by viewModel.isBootstrapped.collectAsStateWithLifecycle()
    val activeScreen by viewModel.activeScreen.collectAsStateWithLifecycle()
    val currentFolderId by viewModel.currentFolderId.collectAsStateWithLifecycle()
    val allFolders by viewModel.allFolders.collectAsStateWithLifecycle()
    val allScans by viewModel.allScans.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    val folderSortMode by viewModel.folderSortMode.collectAsStateWithLifecycle()
    val imageSortMode by viewModel.imageSortMode.collectAsStateWithLifecycle()
    val isSelectionMode by viewModel.isSelectionMode.collectAsStateWithLifecycle()
    val selectedScanIds by viewModel.selectedScanIds.collectAsStateWithLifecycle()
    val appLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
    val strings by viewModel.strings.collectAsStateWithLifecycle()

    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val accentColorHex by viewModel.accentColorHex.collectAsStateWithLifecycle()
    val defaultScanFilter by viewModel.defaultScanFilter.collectAsStateWithLifecycle()
    val scanResolutionPreset by viewModel.scanResolutionPreset.collectAsStateWithLifecycle()
    val hapticsEnabled by viewModel.hapticsEnabled.collectAsStateWithLifecycle()
    val storageUsageSummary by viewModel.storageUsageSummary.collectAsStateWithLifecycle()
    val pcSyncConfig by viewModel.pcSyncConfig.collectAsStateWithLifecycle()
    val pcSyncStatus by viewModel.pcSyncStatus.collectAsStateWithLifecycle()
    val googleDriveStatus by viewModel.googleDriveStatus.collectAsStateWithLifecycle()
    val pendingAccountSwitchPrompt by viewModel.pendingAccountSwitchPrompt.collectAsStateWithLifecycle()
    val isZipBackupBusy by viewModel.isZipBackupBusy.collectAsStateWithLifecycle()
    val appUpdateState by viewModel.appUpdateState.collectAsStateWithLifecycle()

    val batchOriginFolderId by viewModel.batchOriginFolderId.collectAsStateWithLifecycle()
    val draftItems by viewModel.draftItems.collectAsStateWithLifecycle()
    val selectedDraftIndex by viewModel.selectedDraftIndex.collectAsStateWithLifecycle()
    val retakeTargetIndex by viewModel.retakeTargetIndex.collectAsStateWithLifecycle()
    val isAppendingFromReview by viewModel.isAppendingFromReview.collectAsStateWithLifecycle()
    val isSavingBatch by viewModel.isSavingBatch.collectAsStateWithLifecycle()
    val isFilterAppliedToAll by viewModel.isFilterAppliedToAll.collectAsStateWithLifecycle()

    val destinationPickerState by viewModel.destinationPickerState.collectAsStateWithLifecycle()
    val viewingScanId by viewModel.viewingScanId.collectAsStateWithLifecycle()
    val isSavingSingleEdit by viewModel.isSavingSingleEdit.collectAsStateWithLifecycle()

    val pdfOrderedScans by viewModel.pdfOrderedScans.collectAsStateWithLifecycle()
    val pdfFilename by viewModel.pdfFilename.collectAsStateWithLifecycle()
    val pdfPageFormat by viewModel.pdfPageFormat.collectAsStateWithLifecycle()
    val isExportingPdf by viewModel.isExportingPdf.collectAsStateWithLifecycle()

    val moveSuccessBanner by viewModel.moveSuccessBanner.collectAsStateWithLifecycle()
    val undoTrashBanner by viewModel.undoTrashBanner.collectAsStateWithLifecycle()
    val undoRenameBanner by viewModel.undoRenameBanner.collectAsStateWithLifecycle()
    val statusToast by viewModel.statusToast.collectAsStateWithLifecycle()

    val trashedFolders by viewModel.trashedFolders.collectAsStateWithLifecycle()
    val trashedScans by viewModel.trashedScans.collectAsStateWithLifecycle()
    val appLockEnabled by viewModel.appLockEnabled.collectAsStateWithLifecycle()
    val hasAppPin by viewModel.hasAppPin.collectAsStateWithLifecycle()
    val biometricLockEnabled by viewModel.biometricLockEnabled.collectAsStateWithLifecycle()
    val isAppLocked by viewModel.isAppLocked.collectAsStateWithLifecycle()
    val pinLockoutState by viewModel.pinLockoutState.collectAsStateWithLifecycle()

    val verificationResults by viewModel.verificationResults.collectAsStateWithLifecycle()
    val isRunningVerification by viewModel.isRunningVerification.collectAsStateWithLifecycle()

    var showVerificationDialog by remember { mutableStateOf(false) }

    val folderSortAscending by viewModel.folderSortAscending.collectAsStateWithLifecycle()

    val isBiometricAvailable = remember(context) {
        BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    val hostActivity = context.findFragmentActivity() ?: (context as? Activity)
                    if (hostActivity?.isChangingConfigurations != true) {
                        viewModel.onAppBackgrounded()
                    }
                }
                Lifecycle.Event.ON_START -> {
                    viewModel.onAppForegrounded()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val originFolderBreadcrumb = remember(batchOriginFolderId, allFolders, strings) {
        batchOriginFolderId?.let { fid ->
            viewModel.formatBreadcrumbPath(fid, includeHome = false)
        }
    }

    val reviewBottomLeftFolderText = remember(batchOriginFolderId, allFolders) {
        viewModel.formatReviewBottomLeftFolderText(batchOriginFolderId)
    }

    val searchFolderPath = remember(currentFolderId, allFolders) {
        viewModel.formatSearchFolderPath(currentFolderId)
    }

    KagojpotroTheme(
        themeMode = themeMode,
        accentColorHex = accentColorHex
    ) {
        val onboardingDriveConsentLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                viewModel.syncWithGoogleDriveNow(forceRestoreAll = false) { msg ->
                    viewModel.showStatusToast(msg)
                }
            }
        }

        LaunchedEffect(statusToast) {
            if (statusToast != null) {
                delay(3000L)
                viewModel.dismissStatusToast()
            }
        }
        LaunchedEffect(isBootstrapped, pendingSharedUris) {
            if (isBootstrapped && pendingSharedUris.isNotEmpty()) {
                val toImport = pendingSharedUris
                onConsumeSharedUris()
                viewModel.importFromDeviceUris(toImport)
            }
        }
        val isLockActive = isBootstrapped && isAppLocked
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
        if (isBootstrapped) {
        AnimatedContent(
            targetState = activeScreen,
            transitionSpec = {
                (fadeIn(animationSpec = tween(220)) + scaleIn(initialScale = 0.985f, animationSpec = tween(220)))
                    .togetherWith(
                        fadeOut(animationSpec = tween(180)) + scaleOut(targetScale = 0.985f, animationSpec = tween(180))
                    )
            },
            label = "KagojpotroScreenTransition"
        ) { screen ->
        when (screen) {
            AppScreen.STARTUP_ONBOARDING -> {
                StartupOnboardingScreen(
                    appLanguage = appLanguage,
                    isAppLocked = isLockActive,
                    onSelectLocalStorage = viewModel::selectLocalStorageAndProceed,
                    onSetupPcSync = { serverUrl, username, onResult ->
                        viewModel.setupPcSyncAndProceed(serverUrl, username, onResult)
                    },
                    onCompleteWithGoogle = { email, _, onResult ->
                        viewModel.completeOnboardingWithGoogle(
                            email = email,
                            onConsentNeeded = { consentIntent ->
                                viewModel.suppressNextResumeLock()
                                runCatching { onboardingDriveConsentLauncher.launch(consentIntent) }
                            }
                        ) { msg ->
                            onResult(msg)
                            if (msg.isNotBlank()) viewModel.showStatusToast(msg)
                        }
                    },
                    onExportPcServerScript = {
                        viewModel.exportPcServerScript { scriptFile ->
                            viewModel.suppressNextResumeLock()
                            HapticsAndShareHelper.downloadOrShareServerScript(
                                context = context,
                                scriptFile = scriptFile,
                                shareSheet = true
                            )
                            viewModel.showStatusToast("Saved ${scriptFile.name} to Downloads/Kagojpotro")
                        }
                    },
                    onSuppressResumeLock = viewModel::suppressNextResumeLock
                )
            }

            AppScreen.BROWSER -> {
                HomeAndFolderBrowserScreen(
                    currentFolderId = currentFolderId,
                    searchFolderPath = searchFolderPath,
                    isAppLocked = isLockActive,
                    allFolders = allFolders,
                    allScans = allScans,
                    searchQuery = searchQuery,
                    viewMode = viewMode,
                    folderSortMode = folderSortMode,
                    folderSortAscending = folderSortAscending,
                    imageSortMode = imageSortMode,
                    isSelectionMode = isSelectionMode,
                    selectedScanIds = selectedScanIds,
                    appLanguage = appLanguage,
                    strings = strings,
                    pcSyncConfig = pcSyncConfig,
                    pcSyncStatus = pcSyncStatus,
                    moveSuccessBanner = moveSuccessBanner,
                    undoTrashBanner = undoTrashBanner,
                    undoRenameBanner = undoRenameBanner,
                    statusToast = statusToast,
                    matchesSearch = viewModel::matchesSearch,
                    onUpdateSearch = viewModel::updateSearchQuery,
                    onClearSearch = viewModel::clearSearchQuery,
                    onOpenFolder = viewModel::openFolder,
                    onNavigateBack = viewModel::navigateUpInBrowser,
                    onToggleViewMode = viewModel::toggleViewMode,
                    onSetFolderSort = viewModel::setFolderSort,
                    onSetFolderSortAscending = viewModel::setFolderSortAscending,
                    onUpdateCustomFolderOrder = viewModel::updateCustomFolderOrder,
                    onSetImageSort = viewModel::setImageSort,
                    onOpenSettings = viewModel::openSettings,
                    onCreateFolder = viewModel::createFolder,
                    onRenameFolder = viewModel::renameFolder,
                    onRenameScan = viewModel::renameScan,
                    onBulkRenameScans = viewModel::bulkRenameScans,
                    onGetFolderDeleteSummary = viewModel::getFolderDeleteSummary,
                    onGetFoldersDeleteSummary = viewModel::getFoldersDeleteSummary,
                    onDeleteFolder = { id -> viewModel.deleteFolder(id) },
                    onDeleteFolders = { ids -> viewModel.deleteFolders(ids) },
                    onStartCameraScan = viewModel::startCameraScanFromCurrentLocation,
                    onImportDeviceUris = { uris -> viewModel.importFromDeviceUris(uris) },
                    onThumbnailTapped = viewModel::onThumbnailTapped,
                    onThumbnailLongPressed = viewModel::onThumbnailLongPressed,
                    onSetScanSelection = viewModel::setScanSelection,
                    onClearSelection = viewModel::clearSelection,
                    onOpenMovePicker = viewModel::openMovePickerForSelectedImages,
                    onOpenPdfBuilder = viewModel::openPdfBuilderForSelected,
                    onDeleteSelectedImages = { viewModel.deleteSelectedScans() },
                    onDismissMoveBanner = viewModel::dismissMoveSuccessBanner,
                    onUndoTrash = viewModel::undoLastTrashDelete,
                    onDismissUndoTrash = viewModel::dismissUndoTrashBanner,
                    onUndoRename = viewModel::undoLastBulkRename,
                    onDismissUndoRename = viewModel::dismissUndoRenameBanner,
                    onDismissStatusToast = viewModel::dismissStatusToast,
                    onShowStatusToast = viewModel::showStatusToast,
                    onSuppressResumeLock = viewModel::suppressNextResumeLock
                )
            }

            AppScreen.SETTINGS -> {
                SettingsScreen(
                    appLanguage = appLanguage,
                    strings = strings,
                    isAppLocked = isLockActive,
                    themeMode = themeMode,
                    accentColorHex = accentColorHex,
                    defaultScanFilter = defaultScanFilter,
                    scanResolutionPreset = scanResolutionPreset,
                    pdfPageFormat = pdfPageFormat,
                    hapticsEnabled = hapticsEnabled,
                    storageSummary = storageUsageSummary,
                    pcSyncConfig = pcSyncConfig,
                    pcSyncStatus = pcSyncStatus,
                    googleDriveStatus = googleDriveStatus,
                    isZipBackupBusy = isZipBackupBusy,
                    trashedFolders = trashedFolders,
                    trashedScans = trashedScans,
                    appLockEnabled = appLockEnabled,
                    hasAppPin = hasAppPin,
                    biometricLockEnabled = biometricLockEnabled,
                    onBack = viewModel::closeSettings,
                    onSetThemeMode = viewModel::setThemeMode,
                    onSetAccentColorHex = viewModel::setAccentColorHex,
                    onSetLanguage = viewModel::setLanguage,
                    onSetDefaultScanFilter = viewModel::setDefaultScanFilter,
                    onSetScanResolutionPreset = viewModel::setScanResolutionPreset,
                    onSetPdfPageFormat = viewModel::updatePdfPageFormat,
                    onSetHapticsEnabled = viewModel::setHapticsEnabled,
                    onUpdatePcSyncConfig = { cfg, triggerSync ->
                        viewModel.updatePcSyncConfig(cfg, triggerSync)
                    },
                    onShowStatusToast = viewModel::showStatusToast,
                    onTestPcConnection = { cfg ->
                        viewModel.testPcServerConnection(cfg) { msg ->
                            viewModel.showStatusToast(msg)
                        }
                    },
                    onExportPcServerScript = {
                        viewModel.exportPcServerScript { scriptFile ->
                            viewModel.suppressNextResumeLock()
                            HapticsAndShareHelper.downloadOrShareServerScript(
                                context = context,
                                scriptFile = scriptFile,
                                shareSheet = true
                            )
                            viewModel.showStatusToast("Saved ${scriptFile.name} to Downloads/Kagojpotro")
                        }
                    },
                    onConnectGoogleDriveAccount = { email, onConsentNeeded ->
                        viewModel.connectGoogleDriveAccount(
                            email = email,
                            onConsentNeeded = onConsentNeeded
                        ) { msg ->
                            viewModel.showStatusToast(msg)
                        }
                    },
                    onDisconnectGoogleDriveAccount = viewModel::disconnectGoogleDriveAccount,
                    onDeleteGoogleAccount = { onAccountDeleted ->
                        viewModel.deleteGoogleAccountAndCloudData(
                            onAccountDeleted = onAccountDeleted,
                            onResult = { msg ->
                                viewModel.showStatusToast(msg)
                            }
                        )
                    },
                    onSetGoogleDriveAutoSync = viewModel::setGoogleDriveAutoSync,
                    onSyncWithGoogleDriveNow = { forceRestoreAll, onConsentNeeded ->
                        viewModel.syncWithGoogleDriveNow(
                            forceRestoreAll = forceRestoreAll,
                            onConsentNeeded = onConsentNeeded
                        ) { msg ->
                            viewModel.showStatusToast(msg)
                        }
                    },
                    onExportZipBackup = { uri ->
                        viewModel.exportZipBackupToUri(uri) { msg ->
                            viewModel.showStatusToast(msg)
                        }
                    },
                    onRestoreZipBackup = { uri ->
                        viewModel.restoreZipBackupFromUri(uri) { msg ->
                            viewModel.showStatusToast(msg)
                        }
                    },
                    onClearTemporaryCache = {
                        viewModel.clearTemporaryCache { freedBytes ->
                            val formatted = if (freedBytes >= 1024L * 1024L) {
                                String.format(java.util.Locale.US, "%.2f MB", freedBytes / (1024f * 1024f))
                            } else {
                                String.format(java.util.Locale.US, "%.1f KB", freedBytes / 1024f)
                            }
                            viewModel.showStatusToast("Cleared $formatted of temporary High-Res files & cache")
                        }
                    },
                    onOpenTrash = viewModel::purgeExpiredTrashNow,
                    onRestoreTrashedFolder = viewModel::restoreTrashedFolder,
                    onRestoreTrashedScan = viewModel::restoreTrashedScan,
                    onRestoreAllTrash = viewModel::restoreAllTrash,
                    onPermanentDeleteTrashedFolder = viewModel::permanentlyDeleteTrashedFolder,
                    onPermanentDeleteTrashedScan = viewModel::permanentlyDeleteTrashedScan,
                    onEmptyTrash = viewModel::emptyTrash,
                    onSetAppLockEnabled = viewModel::setAppLockEnabled,
                    onSetBiometricLockEnabled = viewModel::setBiometricLockEnabled,
                    pinLockoutState = pinLockoutState,
                    onVerifyCurrentPin = viewModel::verifyCurrentPin,
                    onVerifyCurrentPinResult = viewModel::verifyCurrentPinWithStatus,
                    onSaveNewPin = viewModel::setupOrChangeAppPin,
                    onSuppressResumeLock = viewModel::suppressNextResumeLock,
                    pendingAccountSwitchPrompt = pendingAccountSwitchPrompt,
                    onConfirmAccountSwitch = viewModel::confirmAccountSwitch,
                    onDismissAccountSwitchPrompt = viewModel::dismissAccountSwitchPrompt,
                    appUpdateState = appUpdateState,
                    onCheckForAppUpdate = { customRepo ->
                        viewModel.checkForGithubAppUpdate(manualUserInitiated = true, customRepoSlug = customRepo)
                    },
                    onDownloadAndInstallAppUpdate = viewModel::downloadAndInstallGithubUpdate,
                    onOpenAppUpdateDialog = viewModel::openAppUpdateDialog
                )
            }

            AppScreen.CAMERA -> {
                CameraCaptureScreen(
                    draftItems = draftItems,
                    retakeTargetIndex = retakeTargetIndex,
                    isAppendingFromReview = isAppendingFromReview,
                    destinationSummary = originFolderBreadcrumb,
                    strings = strings,
                    isAppLocked = isLockActive,
                    onHighResShutterCaptured = viewModel::onCameraHighResFileCaptured,
                    onOpenReviewTick = viewModel::openReviewFromCameraTick,
                    onImportUrisFromDevice = { uris ->
                        viewModel.importFromDeviceUris(uris, batchOriginFolderId)
                    },
                    onImportSampleSheet = viewModel::importSampleDeviceDocument,
                    onCancelRetake = viewModel::cancelRetakeAndReturnToReview,
                    onDiscardAndExit = viewModel::discardBatchAndExitCamera,
                    onSuppressResumeLock = viewModel::suppressNextResumeLock
                )
            }

            AppScreen.BATCH_REVIEW -> {
                BatchReviewScreen(
                    draftItems = draftItems,
                    selectedIndex = selectedDraftIndex,
                    bottomLeftFolderText = reviewBottomLeftFolderText,
                    isSavingBatch = isSavingBatch,
                    appLanguage = appLanguage,
                    strings = strings,
                    isAppLocked = isLockActive,
                    isFilterAppliedToAll = isFilterAppliedToAll,
                    onSelectThumbnail = viewModel::selectDraftThumbnail,
                    onMoveDraftItem = viewModel::moveDraftItem,
                    onAddMoreFromCamera = viewModel::startAppendCameraScanFromReview,
                    onAddMoreFromUris = { uris ->
                        viewModel.importFromDeviceUris(uris, batchOriginFolderId)
                    },
                    onRetakeSelected = viewModel::startRetakeForSelectedDraft,
                    onRotateSelected = viewModel::rotateSelectedDraftClockwise,
                    onUpdateCropSelected = viewModel::updateSelectedDraftCrop,
                    onUpdateFilterSelected = viewModel::updateSelectedDraftFilter,
                    onUpdateFilterIntensity = viewModel::updateSelectedDraftFilterIntensity,
                    onApplyFilterToAll = viewModel::applyFilterToAllDrafts,
                    onToggleApplyFilterToAll = { filter, intensity, onBubbleMessage ->
                        viewModel.toggleApplyFilterToAllDrafts(filter, intensity, onBubbleMessage)
                    },
                    onApplyEraserStroke = viewModel::applyEraserStrokeToSelectedDraft,
                    onUndoEraserStroke = viewModel::undoEraserStrokeOnSelectedDraft,
                    onRedoEraserStroke = viewModel::redoEraserStrokeOnSelectedDraft,
                    onResetEraserStrokes = viewModel::resetEraserStrokesOnSelectedDraft,
                    onDeleteSelected = viewModel::deleteSelectedDraftItem,
                    onSaveAsClicked = viewModel::onBatchSaveAsClicked,
                    onDiscardBatch = viewModel::discardBatchAndExitCamera,
                    onSuppressResumeLock = viewModel::suppressNextResumeLock
                )
            }

            AppScreen.SINGLE_IMAGE_VIEW -> {
                val scan = allScans.find { it.id == viewingScanId }
                if (scan != null) {
                    val folderScans = remember(allScans, scan.folderId, imageSortMode) {
                        val inFolder = allScans.filter { it.folderId == scan.folderId }
                        if (imageSortMode == com.example.data.ImageSortMode.OLDEST_FIRST) {
                            inFolder.sortedBy { it.capturedAt }
                        } else {
                            inFolder.sortedByDescending { it.capturedAt }
                        }
                    }
                    SingleImageScreen(
                        scan = scan,
                        folderScans = folderScans,
                        isSavingEdit = isSavingSingleEdit,
                        appLanguage = appLanguage,
                        strings = strings,
                        isAppLocked = isLockActive,
                        onBack = viewModel::closeSingleImageView,
                        onSelectScanId = viewModel::selectViewingScan,
                        onEnsureHighResOnDemand = viewModel::ensureScanHighResOnDemand,
                        onSaveEditsForScan = { targetId, quad, rot, filter ->
                            viewModel.saveSingleImageEdits(targetId, quad, rot, filter)
                        },
                        onSaveEditsWithIntensityForScan = { targetId, quad, rot, filter, intensity, eraserStrokes ->
                            viewModel.saveSingleImageEdits(targetId, quad, rot, filter, intensity, eraserStrokes)
                        },
                        onSaveEditedBitmapForScan = { targetId, editedBmp, eraserStrokes ->
                            viewModel.saveSingleImageEditedBitmap(targetId, editedBmp, eraserStrokes)
                        },
                        onRenameScan = viewModel::renameScan,
                        onDeleteScan = { targetScanId -> viewModel.deleteSingleScan(targetScanId) },
                        onStatusToast = viewModel::showStatusToast,
                        onSuppressResumeLock = viewModel::suppressNextResumeLock
                    )
                } else {
                    LaunchedEffect(viewingScanId) {
                        viewModel.closeSingleImageView()
                    }
                }
            }

            AppScreen.PDF_BUILDER -> {
                PdfBuilderScreen(
                    orderedScans = pdfOrderedScans,
                    pdfFilename = pdfFilename,
                    pageFormat = pdfPageFormat,
                    isExporting = isExportingPdf,
                    strings = strings,
                    onUpdateFilename = viewModel::updatePdfFilename,
                    onUpdatePageFormat = viewModel::updatePdfPageFormat,
                    onMovePage = viewModel::movePdfPage,
                    onRemovePage = viewModel::removePdfPageAt,
                    onExportAndShare = {
                        viewModel.exportPdfFile { pdfFile ->
                            viewModel.suppressNextResumeLock()
                            val shared = HapticsAndShareHelper.sharePdfFile(context, pdfFile)
                            if (!shared) {
                                HapticsAndShareHelper.downloadPdfToDevice(context, pdfFile)
                                viewModel.showStatusToast("Saved PDF to Downloads/Kagojpotro/${pdfFile.name}")
                            }
                        }
                    },
                    onExportAndDownload = {
                        viewModel.exportPdfFile { pdfFile ->
                            HapticsAndShareHelper.downloadPdfToDevice(context, pdfFile)
                            viewModel.showStatusToast("Saved PDF to Downloads/Kagojpotro/${pdfFile.name}")
                        }
                    },
                    onBack = viewModel::closePdfBuilder
                )
            }
        }
        }
        }

        if (!isLockActive) {
            // Shared Destination Picker Modal (Save to folder / Move to folder)
            DestinationPickerModal(
                state = destinationPickerState,
                allFolders = allFolders,
                folderSortMode = folderSortMode,
                folderSortAscending = folderSortAscending,
                strings = strings,
                isAppLocked = isLockActive,
                formatBreadcrumb = viewModel::formatBreadcrumbPath,
                onNavigateToFolder = viewModel::navigateDestinationPickerTo,
                onNavigateBack = viewModel::navigateUpInDestinationPicker,
                onDismiss = viewModel::dismissDestinationPicker,
                onCreateFolderInPicker = viewModel::createFolderInsidePicker,
                onConfirmDestination = viewModel::confirmDestinationPicker
            )

            // 8-Check Verification Suite Modal
            if (showVerificationDialog) {
                VerificationReportDialog(
                    results = verificationResults,
                    isRunning = isRunningVerification,
                    strings = strings,
                    onRunSuite = viewModel::runAllEightVerificationChecks,
                    onDismiss = { showVerificationDialog = false }
                )
            }

            // GitHub Releases In-App Self-Updater Modal
            if (appUpdateState.showUpdateDialog && appUpdateState.availableUpdate != null) {
                com.example.ui.components.GithubAppUpdateDialog(
                    updateState = appUpdateState,
                    isBn = appLanguage == AppLanguage.BN,
                    onDownloadAndInstall = viewModel::downloadAndInstallGithubUpdate,
                    onDismiss = viewModel::dismissAppUpdateDialog,
                    onSuppressResumeLock = viewModel::suppressNextResumeLock
                )
            }
        }

        // Global 3-second Status Toast overlay for non-Browser screens (Settings, PDF Builder, Single Image, Onboarding)
        val toastBottomPadding = if (
            activeScreen == AppScreen.SINGLE_IMAGE_VIEW ||
            activeScreen == AppScreen.BATCH_REVIEW ||
            activeScreen == AppScreen.PDF_BUILDER
        ) {
            88.dp
        } else {
            20.dp
        }
        AnimatedVisibility(
            visible = !isLockActive && statusToast != null && activeScreen != AppScreen.BROWSER,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = toastBottomPadding)
        ) {
            statusToast?.let { msg ->
                Surface(
                    color = ObsidianSurface.copy(alpha = 0.94f),
                    shape = RoundedCornerShape(12.dp),
                    shadowElevation = 6.dp,
                    modifier = Modifier.clickable { viewModel.dismissStatusToast() }
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

        // Whole-App PIN & Biometric Lock Gate (covers launch and resume)
        if (isLockActive) {
            AppLockOverlay(
                isBn = appLanguage == AppLanguage.BN,
                biometricAvailable = isBiometricAvailable,
                biometricEnabled = biometricLockEnabled,
                pinLockoutState = pinLockoutState,
                onVerifyPin = viewModel::unlockAppWithPin,
                onVerifyPinResult = viewModel::unlockAppWithPinResult,
                onRequestBiometric = {
                    val fragActivity = context.findFragmentActivity()
                    if (fragActivity != null && isBiometricAvailable) {
                        launchBiometricPrompt(
                            activity = fragActivity,
                            isBn = appLanguage == AppLanguage.BN,
                            onSuccess = viewModel::unlockWithBiometric
                        )
                    }
                }
            )
        }
        }
    }
}
