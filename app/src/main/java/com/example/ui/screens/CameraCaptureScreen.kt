package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import android.graphics.BitmapFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.components.ZoomableDocumentImage
import com.example.ui.theme.ObsidianCard
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.TimeUnit
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.AppStrings
import com.example.data.DraftScanItem
import com.example.data.NormalizedQuad
import com.example.processing.DocumentImageProcessor
import com.example.ui.theme.LocalGlassPalette
import com.example.ui.theme.ObsidianDark
import com.example.ui.theme.ObsidianSurface
import com.example.util.HapticsAndShareHelper
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CameraCaptureScreen(
    draftItems: List<DraftScanItem>,
    retakeTargetIndex: Int?,
    isAppendingFromReview: Boolean,
    destinationSummary: String?,
    strings: AppStrings,
    isAppLocked: Boolean = false,
    onHighResShutterCaptured: (File?, Bitmap?) -> Unit,
    onOpenReviewTick: () -> Unit,
    onImportUrisFromDevice: (List<android.net.Uri>) -> Unit,
    onImportSampleSheet: () -> Unit,
    onCancelRetake: () -> Unit,
    onDiscardAndExit: () -> Unit,
    onSuppressResumeLock: () -> Unit = {}
) {
    val context = LocalContext.current
    val view = LocalView.current
    val glass = LocalGlassPalette.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionRequestedOnce by remember { mutableStateOf(false) }
    var cameraUnavailable by remember { mutableStateOf(false) }

    // Camera Alignment & Torch HUD controls
    var showAlignmentGrid by remember { mutableStateOf(true) }
    var isTorchEnabled by remember { mutableStateOf(false) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var tiltRollDegrees by remember { mutableFloatStateOf(0f) }

    // Smoothed live corner coordinates + exact frame aspect ratio + lock hysteresis
    val idleGuideQuad = remember {
        NormalizedQuad(0.12f, 0.14f, 0.88f, 0.14f, 0.90f, 0.86f, 0.10f, 0.86f)
    }
    var liveDetectedQuad by remember { mutableStateOf(idleGuideQuad) }
    var isCornersLocked by remember { mutableStateOf(false) }
    var focusTapOffset by remember { mutableStateOf<Offset?>(null) }
    var cameraFrameAspectRatio by remember { mutableFloatStateOf(0.75f) }
    var latestAnalyzedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var activeImageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    var shutterFlashVisible by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }
    var showExitConfirmDialog by remember { mutableStateOf(false) }
    var showLatestPagePreview by remember { mutableStateOf(false) }
    var flyingThumbnailBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val flightProgress = remember { Animatable(0f) }

    // Accelerometer sensor for live Level / Horizon Tilt Indicator
    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val values = event?.values ?: return
                if (values.size >= 2) {
                    val ax = values[0]
                    val ay = values[1]
                    val rawRoll = Math.toDegrees(atan2(ax.toDouble(), ay.toDouble())).toFloat()
                    val clamped = rawRoll.coerceIn(-45f, 45f)
                    tiltRollDegrees = tiltRollDegrees * 0.82f + clamped * 0.18f
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (sensorManager != null && accelSensor != null) {
            sensorManager.registerListener(listener, accelSensor, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose {
            sensorManager?.unregisterListener(listener)
        }
    }

    // Physical device orientation tracker to orient CameraX ImageCapture and rotate camera controls
    var deviceOrientationDegrees by remember { mutableStateOf(0) }
    val animatedControlRotation by animateFloatAsState(
        targetValue = when (deviceOrientationDegrees) {
            90 -> 90f
            270 -> 270f
            180 -> 180f
            else -> 0f
        },
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "animated_camera_control_rotation"
    )

    DisposableEffect(context) {
        val orientationListener = object : android.view.OrientationEventListener(context, SensorManager.SENSOR_DELAY_UI) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == android.view.OrientationEventListener.ORIENTATION_UNKNOWN) return
                val (surfaceRotation, deg) = when (orientation) {
                    in 45..134 -> android.view.Surface.ROTATION_270 to 270
                    in 135..224 -> android.view.Surface.ROTATION_180 to 180
                    in 225..314 -> android.view.Surface.ROTATION_90 to 90
                    else -> android.view.Surface.ROTATION_0 to 0
                }
                if (deviceOrientationDegrees != deg) {
                    deviceOrientationDegrees = deg
                    runCatching {
                        activeImageCapture?.targetRotation = surfaceRotation
                    }
                }
            }
        }
        if (orientationListener.canDetectOrientation()) {
            orientationListener.enable()
        }
        onDispose {
            orientationListener.disable()
        }
    }

    // Smooth 60fps spring-interpolated coordinates for fluid corner tracking
    val animSpec = remember {
        spring<Float>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        )
    }
    val animTlX by animateFloatAsState(targetValue = liveDetectedQuad.tlX, animationSpec = animSpec, label = "tlX")
    val animTlY by animateFloatAsState(targetValue = liveDetectedQuad.tlY, animationSpec = animSpec, label = "tlY")
    val animTrX by animateFloatAsState(targetValue = liveDetectedQuad.trX, animationSpec = animSpec, label = "trX")
    val animTrY by animateFloatAsState(targetValue = liveDetectedQuad.trY, animationSpec = animSpec, label = "trY")
    val animBrX by animateFloatAsState(targetValue = liveDetectedQuad.brX, animationSpec = animSpec, label = "brX")
    val animBrY by animateFloatAsState(targetValue = liveDetectedQuad.brY, animationSpec = animSpec, label = "brY")
    val animBlX by animateFloatAsState(targetValue = liveDetectedQuad.blX, animationSpec = animSpec, label = "blX")
    val animBlY by animateFloatAsState(targetValue = liveDetectedQuad.blY, animationSpec = animSpec, label = "blY")

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        permissionRequestedOnce = true
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris ->
        if (uris.isNotEmpty()) {
            onImportUrisFromDevice(uris)
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission && !permissionRequestedOnce) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Bind CameraX lifecycle with highest-available-resolution ImageCapture + live ImageAnalysis
    DisposableEffect(hasCameraPermission, lifecycleOwner, isAppLocked) {
        var cameraProvider: ProcessCameraProvider? = null
        val isDisposed = AtomicBoolean(false)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val sessionExecutor = if (hasCameraPermission && !isAppLocked) Executors.newSingleThreadExecutor() else null
        isTorchEnabled = false
        if (isAppLocked) {
            runCatching { boundCamera?.cameraControl?.enableTorch(false) }
            boundCamera = null
            activeImageCapture = null
            val lockFuture = ProcessCameraProvider.getInstance(context)
            lockFuture.addListener({
                runCatching { lockFuture.get().unbindAll() }
            }, mainExecutor)
        }
        if (hasCameraPermission && !isAppLocked && sessionExecutor != null) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (isDisposed.get()) return@addListener
                runCatching {
                    val provider = future.get()
                    cameraProvider = provider
                    provider.unbindAll()

                    val shared43PreviewSelector = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .build()

                    val preview = Preview.Builder()
                        .setResolutionSelector(shared43PreviewSelector)
                        .build()
                        .also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }

                    val highResSelector = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                        .build()

                    val imageCapture = ImageCapture.Builder()
                        .setResolutionSelector(highResSelector)
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setJpegQuality(98)
                        .build()
                    val initialRotation = when (deviceOrientationDegrees) {
                        90 -> android.view.Surface.ROTATION_270
                        180 -> android.view.Surface.ROTATION_180
                        270 -> android.view.Surface.ROTATION_90
                        else -> android.view.Surface.ROTATION_0
                    }
                    runCatching { imageCapture.targetRotation = initialRotation }
                    activeImageCapture = imageCapture

                    val analysisSelector = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                android.util.Size(960, 1280),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()

                    var lastConfidentQuad = idleGuideQuad
                    var missStreak = 6
                    var hitStreak = 0

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setResolutionSelector(analysisSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { analysis ->
                            var lastAnalyzedTime = 0L
                            analysis.setAnalyzer(sessionExecutor) { imageProxy: ImageProxy ->
                                if (isDisposed.get()) {
                                    imageProxy.close()
                                    return@setAnalyzer
                                }
                                val now = System.currentTimeMillis()
                                if (now - lastAnalyzedTime >= 65L) {
                                    lastAnalyzedTime = now
                                    runCatching {
                                        val rawBmp = imageProxy.toBitmap()
                                        val crop = imageProxy.cropRect
                                        val cropped = if (
                                            crop.width() > 0 && crop.height() > 0 &&
                                            crop.left >= 0 && crop.top >= 0 &&
                                            crop.right <= rawBmp.width && crop.bottom <= rawBmp.height &&
                                            (crop.width() != rawBmp.width || crop.height() != rawBmp.height)
                                        ) {
                                            Bitmap.createBitmap(
                                                rawBmp,
                                                crop.left,
                                                crop.top,
                                                crop.width(),
                                                crop.height()
                                            ).also {
                                                if (it !== rawBmp) rawBmp.recycle()
                                            }
                                        } else {
                                            rawBmp
                                        }

                                        val rot = imageProxy.imageInfo.rotationDegrees
                                        val oriented = if (rot != 0) {
                                            val m = Matrix().apply { postRotate(rot.toFloat()) }
                                            Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, m, true).also {
                                                if (it !== cropped) cropped.recycle()
                                            }
                                        } else cropped

                                        val aspect = if (oriented.width > 0 && oriented.height > 0) {
                                            oriented.width.toFloat() / oriented.height.toFloat()
                                        } else {
                                            0.75f
                                        }
                                        val detected = DocumentImageProcessor.detectDocumentCorners(oriented)
                                        val targetQuad: NormalizedQuad
                                        val lockedNow: Boolean
                                        if (!detected.isFullFrame()) {
                                            missStreak = 0
                                            hitStreak++
                                            lastConfidentQuad = detected
                                            targetQuad = detected
                                            lockedNow = hitStreak >= 2
                                        } else {
                                            missStreak++
                                            if (missStreak < 6) {
                                                targetQuad = lastConfidentQuad
                                                lockedNow = true
                                            } else {
                                                hitStreak = 0
                                                targetQuad = idleGuideQuad
                                                lockedNow = false
                                            }
                                        }

                                        mainExecutor.execute {
                                            if (!isDisposed.get()) {
                                                latestAnalyzedBitmap = oriented
                                                cameraFrameAspectRatio = aspect
                                                isCornersLocked = lockedNow
                                                liveDetectedQuad = DocumentImageProcessor.smoothQuad(
                                                    current = liveDetectedQuad,
                                                    target = targetQuad
                                                )
                                            }
                                        }
                                    }
                                }
                                imageProxy.close()
                            }
                        }

                    val selector = if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    } else if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        null
                    }

                    if (selector != null) {
                        val viewPort = previewView.viewPort
                        val cam = runCatching {
                            if (viewPort != null) {
                                val group = UseCaseGroup.Builder()
                                    .setViewPort(viewPort)
                                    .addUseCase(preview)
                                    .addUseCase(imageCapture)
                                    .addUseCase(imageAnalysis)
                                    .build()
                                provider.bindToLifecycle(lifecycleOwner, selector, group)
                            } else {
                                provider.bindToLifecycle(
                                    lifecycleOwner,
                                    selector,
                                    preview,
                                    imageCapture,
                                    imageAnalysis
                                )
                            }
                        }.recoverCatching {
                            activeImageCapture = null
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                selector,
                                preview,
                                imageAnalysis
                            )
                        }.getOrThrow()
                        boundCamera = cam
                        cameraUnavailable = false
                    } else {
                        boundCamera = null
                        activeImageCapture = null
                        cameraUnavailable = true
                    }
                }.onFailure {
                    boundCamera = null
                    activeImageCapture = null
                    cameraUnavailable = true
                }
            }, mainExecutor)
        }

        onDispose {
            isDisposed.set(true)
            isTorchEnabled = false
            latestAnalyzedBitmap = null
            flyingThumbnailBitmap = null
            runCatching { boundCamera?.cameraControl?.enableTorch(false) }
            boundCamera = null
            activeImageCapture = null
            runCatching { cameraProvider?.unbindAll() }
            runCatching { sessionExecutor?.shutdown() }
        }
    }

    fun handleAttemptExit() {
        if (retakeTargetIndex != null || isAppendingFromReview) {
            onCancelRetake()
        } else if (draftItems.isNotEmpty()) {
            showExitConfirmDialog = true
        } else {
            onDiscardAndExit()
        }
    }

    BackHandler {
        handleAttemptExit()
    }

    fun triggerShutterCapture() {
        if (isCapturing) return
        isCapturing = true
        HapticsAndShareHelper.shutterPulse(view, context)
        scope.launch {
            shutterFlashVisible = true
            delay(110)
            shutterFlashVisible = false
        }

        val fallbackFrame: Bitmap? = runCatching {
            if (hasCameraPermission && !cameraUnavailable) {
                latestAnalyzedBitmap ?: previewView.bitmap
            } else null
        }.getOrNull()

        // Launch smooth dynamic flight animation from center to bottom-left thumbnail box
        val animBitmap = fallbackFrame ?: latestAnalyzedBitmap
        if (animBitmap != null && !animBitmap.isRecycled) {
            flyingThumbnailBitmap = animBitmap
            scope.launch {
                flightProgress.snapTo(0f)
                flightProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing)
                )
                flyingThumbnailBitmap = null
            }
        }

        val captureUseCase = activeImageCapture
        if (hasCameraPermission && !cameraUnavailable && captureUseCase != null) {
            val draftDir = File(context.cacheDir, "draft_originals").apply { mkdirs() }
            val outFile = File(draftDir, "cam_fullres_${System.currentTimeMillis()}.jpg")
            val outputOptions = ImageCapture.OutputFileOptions.Builder(outFile).build()
            captureUseCase.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        isCapturing = false
                        onHighResShutterCaptured(outFile, fallbackFrame)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        isCapturing = false
                        runCatching { outFile.delete() }
                        onHighResShutterCaptured(null, fallbackFrame)
                    }
                }
            )
        } else {
            onHighResShutterCaptured(null, fallbackFrame)
            isCapturing = false
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("camera_capture_screen"),
        color = ObsidianDark
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            // Top Camera Bar with Grid Toggle, Flashlight (Torch) Toggle, and Gallery Import
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ObsidianSurface)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = {
                        HapticsAndShareHelper.tapTick(view)
                        handleAttemptExit()
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("camera_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = strings.cancel,
                        tint = Color.White,
                        modifier = Modifier.rotate(animatedControlRotation)
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (retakeTargetIndex != null) {
                            "${strings.retake} #${retakeTargetIndex + 1}"
                        } else {
                            strings.scanImages
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    if (!destinationSummary.isNullOrBlank()) {
                        Text(
                            text = destinationSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = glass.accentHighlight
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 3x3 Alignment Grid Toggle
                    IconButton(
                        onClick = {
                            HapticsAndShareHelper.tapTick(view)
                            showAlignmentGrid = !showAlignmentGrid
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("camera_grid_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (showAlignmentGrid) Icons.Default.GridOn else Icons.Default.GridOff,
                            contentDescription = strings.cameraToggleGridDesc,
                            tint = if (showAlignmentGrid) glass.accentHighlight else Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.rotate(animatedControlRotation)
                        )
                    }

                    // Flashlight / Torch Toggle
                    val hasFlashUnit = boundCamera?.cameraInfo?.hasFlashUnit() == true
                    val canToggleTorch = boundCamera == null || hasFlashUnit
                    IconButton(
                        onClick = {
                            HapticsAndShareHelper.tapTick(view)
                            if (canToggleTorch) {
                                val nextTorch = !isTorchEnabled
                                isTorchEnabled = nextTorch
                                runCatching {
                                    boundCamera?.cameraControl?.enableTorch(nextTorch)
                                }
                            } else {
                                isTorchEnabled = false
                            }
                        },
                        enabled = canToggleTorch,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("camera_torch_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (isTorchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = strings.cameraToggleFlashDesc,
                            tint = when {
                                !canToggleTorch -> Color.White.copy(alpha = 0.28f)
                                isTorchEnabled -> glass.accentHighlight
                                else -> Color.White.copy(alpha = 0.7f)
                            },
                            modifier = Modifier.rotate(animatedControlRotation)
                        )
                    }

                    // Import from device shortcut
                    IconButton(
                        onClick = {
                            HapticsAndShareHelper.tapTick(view)
                            onSuppressResumeLock()
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("camera_import_device_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = strings.importFromDevice,
                            tint = Color.White,
                            modifier = Modifier.rotate(animatedControlRotation)
                        )
                    }
                }
            }

            // Main Viewfinder Area (or Camera Denied / Unavailable Fallback with Import from Device)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (!hasCameraPermission || cameraUnavailable) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 460.dp)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = null,
                                tint = glass.accentHighlight,
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = strings.cameraPermissionDeniedTitle,
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = strings.cameraPermissionDeniedBody,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.78f),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(22.dp))

                            Button(
                                onClick = {
                                    HapticsAndShareHelper.tapTick(view)
                                    onSuppressResumeLock()
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = glass.accent,
                                    contentColor = glass.onAccent
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("camera_fallback_import_button")
                            ) {
                                Icon(imageVector = Icons.Default.PhotoLibrary, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = strings.importFromDevice, fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedButton(
                                onClick = {
                                    HapticsAndShareHelper.tapTick(view)
                                    onImportSampleSheet()
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = glass.accentHighlight),
                                border = BorderStroke(1.dp, glass.accentHighlight.copy(alpha = 0.65f)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("camera_fallback_sample_import_button")
                            ) {
                                Icon(imageVector = Icons.Default.Description, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = strings.importSampleSheetLabel)
                            }

                            if (!hasCameraPermission) {
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(
                                    onClick = {
                                        permissionLauncher.launch(Manifest.permission.CAMERA)
                                    },
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                ) {
                                    Text(text = strings.requestCameraPermissionLabel, color = Color.White.copy(alpha = 0.85f))
                                }
                            }
                        }
                    }
                } else {
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(boundCamera) {
                                detectTapGestures { offset ->
                                    val cam = boundCamera ?: return@detectTapGestures
                                    focusTapOffset = offset
                                    runCatching {
                                        val point = previewView.meteringPointFactory.createPoint(offset.x, offset.y)
                                        val action = FocusMeteringAction.Builder(
                                            point,
                                            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                                        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                                        cam.cameraControl.startFocusAndMetering(action)
                                    }
                                    scope.launch {
                                        delay(900)
                                        focusTapOffset = null
                                    }
                                }
                            }
                    )
                }

                // Live 4-Corner Document Boundary Marker + 3x3 Grid + Level Horizon Indicator
                val isLevel = abs(tiltRollDegrees) <= 2.5f
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("camera_corner_overlay")
                ) {
                    val cw = size.width
                    val ch = size.height
                    val aspect = cameraFrameAspectRatio.coerceIn(0.4f, 2.5f)
                    val scale = min(cw / aspect, ch)
                    val dw = aspect * scale
                    val dh = scale
                    val left = (cw - dw) / 2f
                    val top = (ch - dh) / 2f

                    // 1. Optional 3x3 Rule-of-Thirds Alignment Grid
                    if (showAlignmentGrid) {
                        val gridColor = Color.White.copy(alpha = 0.22f)
                        val gridStroke = 1.dp.toPx()
                        for (i in 1..2) {
                            val gx = left + (dw * i) / 3f
                            drawLine(
                                color = gridColor,
                                start = Offset(gx, top),
                                end = Offset(gx, top + dh),
                                strokeWidth = gridStroke
                            )
                            val gy = top + (dh * i) / 3f
                            drawLine(
                                color = gridColor,
                                start = Offset(left, gy),
                                end = Offset(left + dw, gy),
                                strokeWidth = gridStroke
                            )
                        }
                    }

                    // 2. Live Level / Horizon Tilt Indicator at the center of the viewfinder
                    val center = Offset(cw * 0.5f, ch * 0.5f)
                    val horizonHalfSpan = 36.dp.toPx()
                    val levelColor = if (isLevel) glass.accentHighlight else Color.White.copy(alpha = 0.65f)
                    drawLine(
                        color = Color.White.copy(alpha = 0.45f),
                        start = Offset(center.x - horizonHalfSpan - 12.dp.toPx(), center.y),
                        end = Offset(center.x - horizonHalfSpan - 4.dp.toPx(), center.y),
                        strokeWidth = 2.dp.toPx()
                    )
                    drawLine(
                        color = Color.White.copy(alpha = 0.45f),
                        start = Offset(center.x + horizonHalfSpan + 4.dp.toPx(), center.y),
                        end = Offset(center.x + horizonHalfSpan + 12.dp.toPx(), center.y),
                        strokeWidth = 2.dp.toPx()
                    )
                    rotate(degrees = -tiltRollDegrees, pivot = center) {
                        drawLine(
                            color = levelColor,
                            start = Offset(center.x - horizonHalfSpan, center.y),
                            end = Offset(center.x + horizonHalfSpan, center.y),
                            strokeWidth = if (isLevel) 3.dp.toPx() else 2.dp.toPx()
                        )
                        drawCircle(
                            color = levelColor,
                            radius = if (isLevel) 5.dp.toPx() else 3.5.dp.toPx(),
                            center = center
                        )
                    }

                    // 3. Multi-Cue 4-Corner Polygon Overlay (crisp highlight when locked, soft guide when searching)
                    val tl = Offset(left + animTlX * dw, top + animTlY * dh)
                    val tr = Offset(left + animTrX * dw, top + animTrY * dh)
                    val br = Offset(left + animBrX * dw, top + animBrY * dh)
                    val bl = Offset(left + animBlX * dw, top + animBlY * dh)

                    val polyPath = Path().apply {
                        moveTo(tl.x, tl.y)
                        lineTo(tr.x, tr.y)
                        lineTo(br.x, br.y)
                        lineTo(bl.x, bl.y)
                        close()
                    }
                    val overlayStrokeColor = if (isCornersLocked) {
                        glass.accentHighlight.copy(alpha = 0.96f)
                    } else {
                        Color.White.copy(alpha = 0.45f)
                    }
                    drawPath(
                        path = polyPath,
                        color = if (isCornersLocked) glass.accentHighlight.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f)
                    )
                    drawPath(
                        path = polyPath,
                        color = overlayStrokeColor,
                        style = Stroke(width = if (isCornersLocked) 3.dp.toPx() else 1.8.dp.toPx())
                    )

                    val corners = listOf(tl, tr, br, bl)
                    corners.forEach { pt ->
                        drawCircle(
                            color = Color.White.copy(alpha = if (isCornersLocked) 1f else 0.55f),
                            radius = if (isCornersLocked) 9.5.dp.toPx() else 6.5.dp.toPx(),
                            center = pt
                        )
                        drawCircle(
                            color = if (isCornersLocked) glass.accent else Color.White.copy(alpha = 0.35f),
                            radius = if (isCornersLocked) 6.5.dp.toPx() else 4.dp.toPx(),
                            center = pt
                        )
                    }

                    focusTapOffset?.let { tap ->
                        drawCircle(
                            color = glass.accentHighlight.copy(alpha = 0.9f),
                            radius = 24.dp.toPx(),
                            center = tap,
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }

                // Level Degree + Corner-Lock Status Pill Badge near top of viewfinder
                Surface(
                    color = ObsidianSurface.copy(alpha = 0.78f),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp)
                        .border(
                            1.dp,
                            if (isCornersLocked || isLevel) glass.accentHighlight else Color.White.copy(alpha = 0.25f),
                            RoundedCornerShape(50)
                        )
                        .testTag("camera_level_indicator_badge")
                ) {
                    val levelText = if (isLevel) {
                        strings.cameraLevelZeroLabel
                    } else {
                        strings.cameraTiltLabel(abs(tiltRollDegrees).toInt())
                    }
                    val statusSuffix = if (!hasCameraPermission || cameraUnavailable) {
                        ""
                    } else if (isCornersLocked) {
                        strings.cameraLockedBadgeSuffix
                    } else {
                        strings.cameraSearchingBadgeSuffix
                    }
                    Text(
                        text = levelText + statusSuffix,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isCornersLocked || isLevel) glass.accentHighlight else Color.White,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                // Immediate Visual Shutter Flash Overlay
                androidx.compose.animation.AnimatedVisibility(
                    visible = shutterFlashVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White.copy(alpha = 0.65f))
                    )
                }
            }

            // Bottom Control Bar: Captured-Image Count (left), Shutter (center), Tick (right)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ObsidianSurface)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Bottom-Left Box: Captured-Image Live Thumbnail + Image Count Badge on Corner
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .testTag("camera_capture_count_badge"),
                    contentAlignment = Alignment.Center
                ) {
                    if (draftItems.isNotEmpty()) {
                        val latestItem = draftItems.last()
                        val latestCroppedThumb by produceState<Bitmap?>(
                            initialValue = latestItem.previewBitmap,
                            key1 = latestItem.id,
                            key2 = latestItem.isProcessing,
                            key3 = Triple(latestItem.originalPath, latestItem.cropQuad, latestItem.rotationDegrees)
                        ) {
                            if (latestItem.isProcessing) {
                                value = latestItem.previewBitmap
                                return@produceState
                            }
                            value = withContext(Dispatchers.Default) {
                                val orig = decodeSampledBitmapFromFile(latestItem.originalPath, 420)
                                    ?: return@withContext latestItem.previewBitmap
                                val rendered = DocumentImageProcessor.renderCroppedOriginal(
                                    original = orig,
                                    quad = latestItem.cropQuad,
                                    rotationDegrees = latestItem.rotationDegrees,
                                    maxDimension = 220
                                )
                                if (rendered !== orig && !orig.isRecycled) orig.recycle()
                                rendered
                            }
                        }

                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(2.dp, glass.accentHighlight),
                                color = ObsidianCard,
                                modifier = Modifier
                                    .size(50.dp)
                                    .rotate(animatedControlRotation)
                                    .clickable {
                                        HapticsAndShareHelper.tapTick(view)
                                        showLatestPagePreview = true
                                    }
                                    .testTag("latest_captured_thumbnail_button")
                            ) {
                                val thumbBmp = latestCroppedThumb ?: latestItem.previewBitmap
                                if (thumbBmp != null) {
                                    Image(
                                        bitmap = thumbBmp.asImageBitmap(),
                                        contentDescription = "Latest cropped capture thumbnail",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (latestItem.isProcessing) {
                                            CircularProgressIndicator(
                                                color = glass.accentHighlight,
                                                strokeWidth = 2.dp,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.Description,
                                                contentDescription = null,
                                                tint = glass.accentHighlight,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // Corner badge showing total image count in batch
                            Surface(
                                color = glass.accent,
                                shape = RoundedCornerShape(50),
                                border = BorderStroke(1.dp, glass.onAccent.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 4.dp, y = (-4).dp)
                                    .testTag("camera_capture_corner_count_badge")
                            ) {
                                Text(
                                    text = "${draftItems.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = glass.onAccent,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                            color = ObsidianCard.copy(alpha = 0.6f),
                            modifier = Modifier
                                .size(50.dp)
                                .rotate(animatedControlRotation)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "0",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White.copy(alpha = 0.38f),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                // Fast Shutter Button at bottom center
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .border(4.dp, glass.accentHighlight, CircleShape)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable(enabled = !isCapturing) {
                            triggerShutterCapture()
                        }
                        .testTag("camera_shutter_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = "Shutter",
                        tint = glass.accent,
                        modifier = Modifier
                            .size(30.dp)
                            .rotate(animatedControlRotation)
                    )
                }

                // Tick Button at bottom right
                val tickEnabled = draftItems.isNotEmpty() && retakeTargetIndex == null
                IconButton(
                    onClick = {
                        if (tickEnabled) {
                            HapticsAndShareHelper.tapTick(view)
                            onOpenReviewTick()
                        }
                    },
                    enabled = tickEnabled,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(if (tickEnabled) glass.accent else Color.White.copy(alpha = 0.14f))
                        .testTag("camera_review_tick_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Open review",
                        tint = if (tickEnabled) glass.onAccent else Color.White.copy(alpha = 0.38f),
                        modifier = Modifier
                            .size(28.dp)
                            .rotate(animatedControlRotation)
                    )
                }
            }
        }

        // Dynamic Flight Animation Overlay from Viewfinder to Bottom-Left Corner
        val flyingBmp = flyingThumbnailBitmap
        if (flyingBmp != null && !flyingBmp.isRecycled) {
            val t = flightProgress.value
            val startX = maxWidth * 0.5f
            val startY = maxHeight * 0.45f
            val endX = 52.dp
            val endY = maxHeight - 60.dp
            val curX = startX + (endX - startX) * t - (18.dp * sin(PI.toFloat() * t))
            val curY = startY + (endY - startY) * t + (24.dp * sin(PI.toFloat() * t))
            val curScale = (0.75f * (1f - t) + 0.16f * t).coerceIn(0.12f, 1f)
            val curAlpha = (0.95f * (1f - t) + 0.55f * t).coerceIn(0f, 1f)
            val curRot = -6f * (1f - t)

            Box(
                modifier = Modifier
                    .offset(x = curX - (110.dp * curScale), y = curY - (150.dp * curScale))
                    .size(220.dp * curScale, 300.dp * curScale)
                    .rotate(curRot)
                    .shadow(elevation = 14.dp * (1f - t), shape = RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .border(2.dp, glass.accentHighlight.copy(alpha = curAlpha), RoundedCornerShape(14.dp))
                    .background(ObsidianCard)
            ) {
                Image(
                    bitmap = flyingBmp.asImageBitmap(),
                    contentDescription = "Flying capture animation",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(alpha = curAlpha)
                )
            }
        }
    }
}

    // Full-Screen Batch Preview Dialog (opens on Latest Captured Page and allows swiping across all batch images)
    if (showLatestPagePreview && draftItems.isNotEmpty() && !isAppLocked) {
        val initialLastPage = (draftItems.size - 1).coerceAtLeast(0)
        val previewPagerState = rememberPagerState(
            initialPage = initialLastPage,
            pageCount = { draftItems.size }
        )
        var isPreviewPageZoomed by remember { mutableStateOf(false) }

        LaunchedEffect(previewPagerState.currentPage) {
            isPreviewPageZoomed = false
        }

        Dialog(
            onDismissRequest = { showLatestPagePreview = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            BackHandler { showLatestPagePreview = false }
            Scaffold(
                containerColor = ObsidianDark,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("latest_page_preview_dialog"),
                topBar = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ObsidianSurface)
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(
                            onClick = {
                                HapticsAndShareHelper.tapTick(view)
                                showLatestPagePreview = false
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("close_latest_preview_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = strings.cancel,
                                tint = Color.White
                            )
                        }

                        Surface(
                            color = Color.White.copy(alpha = 0.10f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.testTag("camera_preview_page_indicator")
                        ) {
                            Text(
                                text = "${previewPagerState.currentPage + 1} / ${draftItems.size}",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                HapticsAndShareHelper.tapTick(view)
                                showLatestPagePreview = false
                                onOpenReviewTick()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("preview_proceed_tick_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Review batch",
                                tint = glass.accentHighlight
                            )
                        }
                    }
                }
            ) { innerPadding ->
                HorizontalPager(
                    state = previewPagerState,
                    userScrollEnabled = !isPreviewPageZoomed,
                    key = { idx -> draftItems.getOrNull(idx)?.id ?: idx },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .testTag("camera_preview_pager")
                ) { pageIdx ->
                    val pageItem = draftItems.getOrNull(pageIdx)
                    if (pageItem != null) {
                        val pageCroppedOriginalBmp by produceState<Bitmap?>(
                            initialValue = pageItem.previewBitmap,
                            key1 = pageItem.id,
                            key2 = pageItem.isProcessing,
                            key3 = Triple(pageItem.originalPath, pageItem.cropQuad, pageItem.rotationDegrees)
                        ) {
                            if (pageItem.isProcessing) {
                                value = pageItem.previewBitmap
                                return@produceState
                            }
                            value = withContext(Dispatchers.Default) {
                                val orig = decodeSampledBitmapFromFile(pageItem.originalPath, 2400)
                                    ?: return@withContext pageItem.previewBitmap
                                val cropped = DocumentImageProcessor.renderCroppedOriginal(
                                    original = orig,
                                    quad = pageItem.cropQuad,
                                    rotationDegrees = pageItem.rotationDegrees,
                                    maxDimension = 2400
                                )
                                if (cropped !== orig && !orig.isRecycled) orig.recycle()
                                cropped
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val previewBmp = pageCroppedOriginalBmp ?: pageItem.previewBitmap
                            if (previewBmp != null) {
                                ZoomableDocumentImage(
                                    bitmap = previewBmp,
                                    contentDescription = pageItem.filename,
                                    rotationDegrees = 0,
                                    resetKey = "${pageItem.id}_${pageItem.cropQuad}",
                                    onZoomStateChanged = { zoomed ->
                                        if (pageIdx == previewPagerState.currentPage) {
                                            isPreviewPageZoomed = zoomed
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .testTag(
                                            if (pageIdx == previewPagerState.currentPage) {
                                                "latest_page_zoomable_preview"
                                            } else {
                                                "latest_page_zoomable_preview_$pageIdx"
                                            }
                                        )
                                )
                            } else {
                                CircularProgressIndicator(color = glass.accentHighlight)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showExitConfirmDialog && !isAppLocked) {
        AlertDialog(
            onDismissRequest = { showExitConfirmDialog = false },
            title = {
                Text(
                    text = strings.unsavedCapturesTitle,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(text = strings.unsavedCapturesMessage)
            },
            confirmButton = {
                Button(
                    onClick = {
                        HapticsAndShareHelper.tapTick(view)
                        showExitConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = glass.accent,
                        contentColor = glass.onAccent
                    ),
                    modifier = Modifier.testTag("keep_scanning_button")
                ) {
                    Text(text = strings.keepScanning)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        HapticsAndShareHelper.tapTick(view)
                        showExitConfirmDialog = false
                        onDiscardAndExit()
                    },
                    modifier = Modifier.testTag("discard_batch_button")
                ) {
                    Text(text = strings.discardBatch, color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }
}

/**
 * Memory-safe sampled decoder for raw camera preview and thumbnails.
 */
private fun decodeSampledBitmapFromFile(path: String, maxDim: Int): Bitmap? {
    return runCatching {
        val file = File(path)
        if (!file.exists() || file.length() == 0L) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        val origW = options.outWidth
        val origH = options.outHeight
        if (origW <= 0 || origH <= 0) return null

        var sampleSize = 1
        val longest = maxOf(origW, origH)
        while (longest / (sampleSize * 2) >= maxDim) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(path, decodeOpts)
    }.getOrNull()
}
