package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.rememberAsyncImagePainter
import com.example.R
import com.example.data.FolderEntity
import com.example.data.PinLockoutState
import com.example.data.PinVerifyResult
import com.example.data.ScanEntity
import com.example.data.TRASH_RETENTION_MILLIS
import com.example.data.stripImageExtension
import com.example.ui.theme.LocalGlassPalette
import com.example.util.HapticsAndShareHelper
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PcSetupGuideDialog(
    isBn: Boolean,
    onDismiss: () -> Unit
) {
    val glass = LocalGlassPalette.current
    val steps = if (isBn) {
        listOf(
            "১. পিসি অ্যাপ ডাউনলোড করুন" to "‘Download PC App’ বাটনে ট্যাপ করে ফাইলটি সেভ বা শেয়ার করুন এবং আপনার Windows কম্পিউটারে নিন (WhatsApp, Telegram, ইমেইল বা USB দিয়ে)।",
            "২. কম্পিউটারে অ্যাপ চালু করুন" to "কম্পিউটারে Kagojpotro_PC_Server.bat ফাইলে ডাবল-ক্লিক করুন। (Windows SmartScreen দেখালে ‘More info’ → ‘Run anyway’ চাপুন।) ফোল্ডার আইকনে ক্লিক করে যেকোনো সময় সিঙ্ক ফোল্ডার খুলতে বা পরিবর্তন করতে পারবেন।",
            "৩. QR কোড স্ক্যান করুন" to "কয়েক সেকেন্ডের মধ্যে পিসি উইন্ডোতে একটি QR কোড দেখা যাবে। ফোনের QR স্ক্যানার বাটনে ট্যাপ করে সেটি স্ক্যান করুন।",
            "৪. ইউজারনেম দিয়ে কানেক্ট করুন" to "আপনার Username (যেমন: hadi) লিখে ‘Connect’ বাটনে ট্যাপ করুন। পিসি অ্যাপের ইন্ডিকেটর ডট সবুজ (Green) হয়ে যাবে!"
        )
    } else {
        listOf(
            "1. Download the PC App" to "Tap 'Download PC App' to save or send Kagojpotro_PC_Server.bat to your Windows computer (via WhatsApp, Telegram, email, or USB).",
            "2. Run It on Your Computer" to "Double-click Kagojpotro_PC_Server.bat on your PC. (If Windows SmartScreen pops up, click 'More info' then 'Run anyway'.) Use the Folder icon anytime to open or change your sync folder.",
            "3. Scan the QR Code" to "Wait a few seconds for the QR code to appear on your PC window, then tap the QR Scanner button on your phone to scan it.",
            "4. Enter Username & Connect" to "Type a short Username (for example: hadi) and tap 'Connect'. The status dot on your PC window will turn Green when synced!"
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = null,
                    tint = glass.accent,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isBn) "পিসি সার্ভার সেটআপ গাইড" else "How to Set Up PC Server",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                steps.forEach { (heading, body) ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(glass.glassCardFill)
                            .border(1.dp, glass.accentGlassBorderBrush, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = heading,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = glass.accent
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = body,
                            style = MaterialTheme.typography.bodySmall,
                            color = glass.textPrimary
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = glass.accent,
                    contentColor = glass.onAccent
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("pc_help_close_button")
            ) {
                Text(
                    text = if (isBn) "ঠিক আছে" else "Got it",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    )
}

@Composable
fun PhoneLocalGuideDialog(
    isBn: Boolean,
    onDismiss: () -> Unit
) {
    val glass = LocalGlassPalette.current
    val steps = if (isBn) {
        listOf(
            "১. শতভাগ অফলাইন ও প্রাইভেট" to "এই মোডে আপনার সব ফোল্ডার ও স্ক্যান করা ডকুমেন্ট শুধুমাত্র আপনার ফোনের ভেতরেই সুরক্ষিত থাকে। কোনো ইন্টারনেট বা ক্লাউড অ্যাকাউন্টের প্রয়োজন নেই।",
            "২. অন্য মোড থেকে স্বয়ংক্রিয় ডিসকানেক্ট" to "ফোন লোকাল স্টোরেজ সিলেক্ট করলে Google বা PC সার্ভার সিঙ্ক স্বয়ংক্রিয়ভাবে বন্ধ ও সাইন আউট হয়ে যায়, ফলে ব্যাকগ্রাউন্ডে কোনো ডেটা সিঙ্ক হয় না।",
            "৩. অফলাইন .zip ব্যাকআপ" to "ফোন পরিবর্তন বা বাড়তি নিরাপত্তার জন্য সেটিংসের ‘Offline Backup & Restore’ থেকে যেকোনো সময় সম্পূর্ণ লাইব্রেরি .zip ফাইলে এক্সপোর্ট ও রিস্টোর করতে পারবেন।"
        )
    } else {
        listOf(
            "1. 100% Offline & Private" to "All your folders and scanned documents are stored exclusively on your phone's internal storage. No internet connection or cloud account is required.",
            "2. Exclusive Local Mode" to "Selecting Phone Local Storage automatically signs out of Google and disconnects any active PC Server session so nothing is synced in the background.",
            "3. Offline .zip Backup Anytime" to "Use the 'Offline Backup & Restore' section below to export your entire document library as a portable .zip archive or restore it onto another phone."
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = null,
                    tint = glass.accent,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isBn) "ফোন লোকাল স্টোরেজ গাইড" else "Phone Local Storage Guide",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                steps.forEach { (heading, body) ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(glass.glassCardFill)
                            .border(1.dp, glass.accentGlassBorderBrush, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = heading,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = glass.accent
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = body,
                            style = MaterialTheme.typography.bodySmall,
                            color = glass.textPrimary
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = glass.accent,
                    contentColor = glass.onAccent
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("phone_local_help_close_button")
            ) {
                Text(
                    text = if (isBn) "ঠিক আছে" else "Got it",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    )
}

@Composable
fun GoogleSyncGuideDialog(
    isBn: Boolean,
    onDismiss: () -> Unit
) {
    val glass = LocalGlassPalette.current
    val steps = if (isBn) {
        listOf(
            "১. গুগল অ্যাকাউন্টে সাইন ইন করুন" to "‘Sign in with Google’ বক্সে ট্যাপ করে ভেতরের ‘Continue with Google’ বাটনে চাপুন এবং আপনার Google অ্যাকাউন্ট নির্বাচন করুন।",
            "২. অটো ও ম্যানুয়াল ক্লাউড সিঙ্ক" to "সাইন ইন করার পর আপনার সব ফোল্ডার ও স্ক্যান নিরাপদভাবে ক্লাউডে সিঙ্ক হবে। ডানপাশের Sync আইকনে ট্যাপ করে যেকোনো সময় সাথে সাথে সিঙ্ক করতে পারবেন।",
            "৩. একক সক্রিয় মোড ও নিয়ন্ত্রণ" to "Google মোড চালু থাকলে PC সার্ভার ডিসকানেক্ট থাকে। আবার অন্য মোড (Phone বা PC) সিলেক্ট করলে Google থেকে স্বয়ংক্রিয়ভাবে সাইন আউট হয়ে যাবে।"
        )
    } else {
        listOf(
            "1. Sign In with Your Google Account" to "Tap the 'Sign in with Google' box to expand it, then tap 'Continue with Google' and choose your account.",
            "2. Automatic & Instant Cloud Sync" to "Once signed in, your folders and scanned documents sync securely to your cloud backup. Tap the Sync icon button anytime to sync immediately.",
            "3. Exclusive Mode & Account Control" to "Activating Google Sync disconnects any active PC Server session. Switching to Phone Local Storage or PC Server Sync automatically signs out your Google account."
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = null,
                    tint = glass.accent,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isBn) "গুগল ক্লাউড সিঙ্ক গাইড" else "Google Cloud Sync Guide",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                steps.forEach { (heading, body) ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(glass.glassCardFill)
                            .border(1.dp, glass.accentGlassBorderBrush, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = heading,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = glass.accent
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = body,
                            style = MaterialTheme.typography.bodySmall,
                            color = glass.textPrimary
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = glass.accent,
                    contentColor = glass.onAccent
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("google_help_close_button")
            ) {
                Text(
                    text = if (isBn) "ঠিক আছে" else "Got it",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    )
}

@OptIn(ExperimentalGetImage::class)
@Composable
fun QrScannerModal(
    isBn: Boolean,
    isAppLocked: Boolean = false,
    onUrlScanned: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val glass = LocalGlassPalette.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var statusError by remember { mutableStateOf<String?>(null) }
    val hasDelivered = remember { AtomicBoolean(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val barcodeScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            val unbindFuture = ProcessCameraProvider.getInstance(context)
            unbindFuture.addListener({
                runCatching { unbindFuture.get().unbindAll() }
            }, ContextCompat.getMainExecutor(context))
            runCatching { barcodeScanner.close() }
            analysisExecutor.shutdown()
        }
    }

    LaunchedEffect(hasCameraPermission, isAppLocked) {
        if (isAppLocked) {
            val lockFuture = ProcessCameraProvider.getInstance(context)
            lockFuture.addListener({
                runCatching { lockFuture.get().unbindAll() }
            }, ContextCompat.getMainExecutor(context))
            return@LaunchedEffect
        }
        if (!hasCameraPermission) return@LaunchedEffect
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                runCatching {
                    val cameraProvider = providerFuture.get()
                    val preview = Preview.Builder().build().apply {
                        setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage == null || hasDelivered.get()) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        val inputImage = InputImage.fromMediaImage(
                            mediaImage,
                            imageProxy.imageInfo.rotationDegrees
                        )
                        barcodeScanner.process(inputImage)
                            .addOnSuccessListener { barcodes ->
                                if (hasDelivered.get()) return@addOnSuccessListener
                                val matchUrl = barcodes.firstNotNullOfOrNull { code ->
                                    val raw = code.rawValue?.trim().orEmpty()
                                    if (raw.startsWith("http://", ignoreCase = true) ||
                                        raw.startsWith("https://", ignoreCase = true)
                                    ) {
                                        raw
                                    } else {
                                        null
                                    }
                                }
                                if (matchUrl != null && hasDelivered.compareAndSet(false, true)) {
                                    HapticsAndShareHelper.performConfirm(view)
                                    onUrlScanned(matchUrl)
                                }
                            }
                            .addOnCompleteListener {
                                imageProxy.close()
                            }
                    }

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                }.onFailure { err ->
                    statusError = err.localizedMessage ?: "Camera unavailable"
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    if (!isAppLocked) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .testTag("qr_scanner_modal")
        ) {
            if (hasCameraPermission) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        tint = glass.accent,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isBn) "পিসি সার্ভার QR কোড স্ক্যান করুন" else "Scan PC Server QR Code",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("qr_scanner_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = if (isBn) "বন্ধ করুন" else "Close",
                        tint = Color.White
                    )
                }
            }

            // Viewfinder reticle
            if (hasCameraPermission) {
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .align(Alignment.Center)
                        .border(2.5.dp, glass.accent, RoundedCornerShape(20.dp))
                        .background(Color.Transparent)
                )
                Text(
                    text = statusError
                        ?: if (isBn) "কম্পিউটার স্ক্রিনের QR কোডটি ফ্রেমের ভেতর ধরুন"
                        else "Point camera at the QR code on your PC window",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 24.dp, vertical = 36.dp)
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
            } else {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isBn) "QR কোড স্ক্যান করতে ক্যামেরার অনুমতি প্রয়োজন।" else "Camera permission is required to scan the PC QR code.",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        colors = ButtonDefaults.buttonColors(containerColor = glass.accent)
                    ) {
                        Text(if (isBn) "ক্যামেরা অনুমতি দিন" else "Grant Camera Permission")
                    }
                }
            }
        }
    }
    }
}

enum class PinDialogMode {
    SET_NEW,
    CHANGE_EXISTING,
    VERIFY_TO_DISABLE
}

@Composable
fun PinSetupDialog(
    isBn: Boolean,
    mode: PinDialogMode,
    pinLockoutState: PinLockoutState = PinLockoutState(),
    onVerifyCurrentPin: suspend (String) -> Boolean,
    onVerifyCurrentPinResult: (suspend (String) -> PinVerifyResult)? = null,
    onSaveNewPin: suspend (String) -> Boolean,
    onDisableVerified: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val view = LocalView.current
    val glass = LocalGlassPalette.current
    val scope = rememberCoroutineScope()

    // Stage 0 = Verify current PIN (CHANGE_EXISTING or VERIFY_TO_DISABLE), Stage 1 = New PIN, Stage 2 = Confirm New PIN
    var stage by remember(mode) {
        mutableStateOf(
            if (mode == PinDialogMode.CHANGE_EXISTING || mode == PinDialogMode.VERIFY_TO_DISABLE) 0 else 1
        )
    }
    var digits by remember { mutableStateOf("") }
    var firstPin by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var lockoutRemainingSec by remember { mutableIntStateOf(0) }

    LaunchedEffect(pinLockoutState.lockoutUntilMs, stage) {
        if (stage != 0) {
            lockoutRemainingSec = 0
            return@LaunchedEffect
        }
        while (true) {
            val remMs = pinLockoutState.lockoutUntilMs - System.currentTimeMillis()
            val remSec = if (remMs > 0L) ((remMs + 999L) / 1000L).toInt() else 0
            lockoutRemainingSec = remSec
            if (remSec <= 0) break
            delay(500L)
        }
    }

    val isLockedOutInVerifyStage = stage == 0 && lockoutRemainingSec > 0
    val effectiveErrorMsg = when {
        isLockedOutInVerifyStage -> if (isBn) {
            "অনেকবার ভুল পিন — ${lockoutRemainingSec} সেকেন্ড পরে চেষ্টা করুন"
        } else {
            "Too many attempts — try again in ${lockoutRemainingSec}s"
        }
        else -> errorMsg
    }

    val heading = when (stage) {
        0 -> if (mode == PinDialogMode.VERIFY_TO_DISABLE) {
            if (isBn) "অ্যাপ লক বন্ধ করতে পিন দিন" else "Enter PIN to Turn Off Lock"
        } else {
            if (isBn) "বর্তমান ৪-সংখ্যার পিন দিন" else "Enter Current 4-Digit PIN"
        }
        1 -> if (isBn) "নতুন ৪-সংখ্যার পিন সেট করুন" else "Set New 4-Digit PIN"
        else -> if (isBn) "নতুন পিন নিশ্চিত করুন" else "Confirm New 4-Digit PIN"
    }

    fun onDigitPressed(d: Char) {
        if (isLockedOutInVerifyStage || digits.length >= 4) return
        HapticsAndShareHelper.tapTick(view)
        errorMsg = null
        val updated = digits + d
        digits = updated
        if (updated.length == 4) {
            scope.launch {
                when (stage) {
                    0 -> {
                        val verifyResult = if (onVerifyCurrentPinResult != null) {
                            onVerifyCurrentPinResult(updated)
                        } else if (onVerifyCurrentPin(updated)) {
                            PinVerifyResult.Success
                        } else {
                            PinVerifyResult.Invalid(failedAttempts = 1, remainingBeforeLockout = 4)
                        }
                        when (verifyResult) {
                            is PinVerifyResult.Success -> {
                                digits = ""
                                if (mode == PinDialogMode.VERIFY_TO_DISABLE) {
                                    HapticsAndShareHelper.performConfirm(view)
                                    onDisableVerified()
                                    onDismiss()
                                } else {
                                    stage = 1
                                }
                            }
                            is PinVerifyResult.Invalid -> {
                                digits = ""
                                errorMsg = if (verifyResult.remainingBeforeLockout in 1..2) {
                                    if (isBn) {
                                        "ভুল পিন — আর ${verifyResult.remainingBeforeLockout} বার চেষ্টা বাকি"
                                    } else {
                                        "Incorrect PIN (${verifyResult.remainingBeforeLockout} attempt${if (verifyResult.remainingBeforeLockout == 1) "" else "s"} left)"
                                    }
                                } else {
                                    if (isBn) "ভুল পিন। আবার চেষ্টা করুন।" else "Incorrect PIN. Try again."
                                }
                            }
                            is PinVerifyResult.LockedOut -> {
                                digits = ""
                                lockoutRemainingSec = verifyResult.remainingSeconds
                                errorMsg = if (isBn) {
                                    "অনেকবার ভুল পিন — ${verifyResult.remainingSeconds} সেকেন্ড পরে চেষ্টা করুন"
                                } else {
                                    "Too many attempts — try again in ${verifyResult.remainingSeconds}s"
                                }
                            }
                        }
                    }
                    1 -> {
                        firstPin = updated
                        digits = ""
                        stage = 2
                    }
                    2 -> {
                        if (updated == firstPin) {
                            val saved = onSaveNewPin(updated)
                            if (saved) {
                                HapticsAndShareHelper.performConfirm(view)
                                onDismiss()
                            } else {
                                digits = ""
                                errorMsg = if (isBn) "পিন সংরক্ষণ ব্যর্থ হয়েছে" else "Could not save PIN"
                            }
                        } else {
                            digits = ""
                            firstPin = ""
                            stage = 1
                            errorMsg = if (isBn) "পিন মেলেনি, আবার দিন" else "PINs did not match. Try again."
                        }
                    }
                }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = glass.glassHeaderFill,
            border = BorderStroke(1.dp, glass.accentGlassBorderBrush),
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .testTag("pin_setup_dialog")
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = glass.accent,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = heading,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = glass.textPrimary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))

                PinDotsIndicator(filledCount = digits.length, hasError = effectiveErrorMsg != null)

                if (effectiveErrorMsg != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = effectiveErrorMsg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                NumericPinKeypad(
                    enabled = !isLockedOutInVerifyStage,
                    onDigit = ::onDigitPressed,
                    onBackspace = {
                        if (!isLockedOutInVerifyStage && digits.isNotEmpty()) {
                            HapticsAndShareHelper.tapTick(view)
                            digits = digits.dropLast(1)
                        }
                    },
                    leftAccessory = null
                )

                Spacer(modifier = Modifier.height(10.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(if (isBn) "বাতিল" else "Cancel", color = glass.textSecondary)
                }
            }
        }
    }
}

@Composable
fun AppLockOverlay(
    isBn: Boolean,
    biometricAvailable: Boolean,
    biometricEnabled: Boolean,
    pinLockoutState: PinLockoutState = PinLockoutState(),
    onVerifyPin: suspend (String) -> Boolean,
    onVerifyPinResult: (suspend (String) -> PinVerifyResult)? = null,
    onRequestBiometric: () -> Unit
) {
    val view = LocalView.current
    val glass = LocalGlassPalette.current
    val scope = rememberCoroutineScope()

    var digits by remember { mutableStateOf("") }
    var wrongAttemptMsg by remember { mutableStateOf<String?>(null) }
    var lockoutRemainingSec by remember { mutableIntStateOf(0) }

    LaunchedEffect(pinLockoutState.lockoutUntilMs) {
        while (true) {
            val remMs = pinLockoutState.lockoutUntilMs - System.currentTimeMillis()
            val remSec = if (remMs > 0L) ((remMs + 999L) / 1000L).toInt() else 0
            lockoutRemainingSec = remSec
            if (remSec <= 0) break
            delay(500L)
        }
    }

    val isLockedOut = lockoutRemainingSec > 0
    val displayedErrorMsg = when {
        isLockedOut -> if (isBn) {
            "অনেকবার ভুল পিন — ${lockoutRemainingSec} সেকেন্ড পরে চেষ্টা করুন"
        } else {
            "Too many attempts — try again in ${lockoutRemainingSec}s"
        }
        else -> wrongAttemptMsg
    }

    BackHandler { /* Swallow system back while locked */ }

    LaunchedEffect(biometricAvailable, biometricEnabled) {
        if (biometricAvailable && biometricEnabled) {
            onRequestBiometric()
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("app_lock_overlay"),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(glass.glassCardFill)
                    .border(1.5.dp, glass.accentGlassBorderBrush, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_paper_logo),
                    contentDescription = "কাগজপত্র",
                    tint = glass.accent,
                    modifier = Modifier.size(42.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "কাগজপত্র",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = glass.textPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isBn) "অ্যাপ আনলক করতে আপনার ৪-সংখ্যার পিন দিন" else "Enter your 4-digit PIN to unlock",
                style = MaterialTheme.typography.bodyMedium,
                color = glass.textSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(22.dp))
            PinDotsIndicator(filledCount = digits.length, hasError = displayedErrorMsg != null)

            if (displayedErrorMsg != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = displayedErrorMsg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(26.dp))

            NumericPinKeypad(
                enabled = !isLockedOut,
                onDigit = { ch ->
                    if (!isLockedOut && digits.length < 4) {
                        HapticsAndShareHelper.tapTick(view)
                        wrongAttemptMsg = null
                        val updated = digits + ch
                        digits = updated
                        if (updated.length == 4) {
                            scope.launch {
                                val verifyResult = if (onVerifyPinResult != null) {
                                    onVerifyPinResult(updated)
                                } else if (onVerifyPin(updated)) {
                                    PinVerifyResult.Success
                                } else {
                                    PinVerifyResult.Invalid(failedAttempts = 1, remainingBeforeLockout = 4)
                                }
                                when (verifyResult) {
                                    is PinVerifyResult.Success -> {
                                        HapticsAndShareHelper.performConfirm(view)
                                        digits = ""
                                    }
                                    is PinVerifyResult.Invalid -> {
                                        digits = ""
                                        wrongAttemptMsg = if (verifyResult.remainingBeforeLockout in 1..2) {
                                            if (isBn) {
                                                "ভুল পিন — আর ${verifyResult.remainingBeforeLockout} বার চেষ্টা বাকি"
                                            } else {
                                                "Incorrect PIN (${verifyResult.remainingBeforeLockout} attempt${if (verifyResult.remainingBeforeLockout == 1) "" else "s"} left)"
                                            }
                                        } else {
                                            if (isBn) "ভুল পিন, আবার চেষ্টা করুন" else "Wrong PIN, please try again"
                                        }
                                    }
                                    is PinVerifyResult.LockedOut -> {
                                        digits = ""
                                        lockoutRemainingSec = verifyResult.remainingSeconds
                                        wrongAttemptMsg = if (isBn) {
                                            "অনেকবার ভুল পিন — ${verifyResult.remainingSeconds} সেকেন্ড পরে চেষ্টা করুন"
                                        } else {
                                            "Too many attempts — try again in ${verifyResult.remainingSeconds}s"
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                onBackspace = {
                    if (!isLockedOut && digits.isNotEmpty()) {
                        HapticsAndShareHelper.tapTick(view)
                        digits = digits.dropLast(1)
                    }
                },
                leftAccessory = if (biometricAvailable && biometricEnabled) {
                    {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .clickable {
                                    HapticsAndShareHelper.tapTick(view)
                                    onRequestBiometric()
                                }
                                .testTag("app_lock_biometric_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Fingerprint,
                                contentDescription = if (isBn) "বায়োমেট্রিক আনলক" else "Unlock with Biometrics",
                                tint = glass.accent,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                } else {
                    null
                }
            )
        }
    }
}

@Composable
private fun PinDotsIndicator(
    filledCount: Int,
    hasError: Boolean
) {
    val glass = LocalGlassPalette.current
    val errorColor = MaterialTheme.colorScheme.error
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(4) { idx ->
            val filled = idx < filledCount
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            hasError -> errorColor.copy(alpha = 0.25f)
                            filled -> glass.accent
                            else -> Color.Transparent
                        }
                    )
                    .border(
                        width = 1.8.dp,
                        color = if (hasError) errorColor else if (filled) glass.accent else glass.textSecondary.copy(alpha = 0.55f),
                        shape = CircleShape
                    )
            )
        }
    }
}

@Composable
private fun NumericPinKeypad(
    enabled: Boolean = true,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    leftAccessory: (@Composable () -> Unit)?
) {
    val glass = LocalGlassPalette.current
    val rows = listOf(
        listOf('1', '2', '3'),
        listOf('4', '5', '6'),
        listOf('7', '8', '9')
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                row.forEach { digit ->
                    PinKeyButton(label = digit.toString(), enabled = enabled, onClick = { onDigit(digit) })
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leftAccessory != null) {
                leftAccessory()
            } else {
                Spacer(modifier = Modifier.size(64.dp))
            }
            PinKeyButton(label = "0", enabled = enabled, onClick = { onDigit('0') })
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(64.dp)
                    .alpha(if (enabled) 1f else 0.45f)
                    .clip(CircleShape)
                    .background(glass.inactiveButtonFill)
                    .clickable(enabled = enabled, onClick = onBackspace)
                    .testTag("pin_key_backspace")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = "Backspace",
                    tint = glass.textPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun PinKeyButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val glass = LocalGlassPalette.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(64.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(CircleShape)
            .background(glass.glassCardFill)
            .border(1.dp, glass.inactiveButtonBorder, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .testTag("pin_key_$label")
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = glass.textPrimary
        )
    }
}

@Composable
fun TrashBinDialog(
    isBn: Boolean,
    isAppLocked: Boolean = false,
    trashedFolders: List<FolderEntity>,
    trashedScans: List<ScanEntity>,
    onRestoreFolder: (String) -> Unit,
    onRestoreScan: (String) -> Unit,
    onRestoreAll: () -> Unit,
    onPermanentDeleteFolder: (String) -> Unit,
    onPermanentDeleteScan: (String) -> Unit,
    onEmptyTrash: () -> Unit,
    onDismiss: () -> Unit
) {
    val view = LocalView.current
    val glass = LocalGlassPalette.current
    val now = remember { System.currentTimeMillis() }

    val trashedFolderIds = remember(trashedFolders) { trashedFolders.map { it.id }.toSet() }
    // Display top-level deleted folders (or deleted subfolders whose parent isn't deleted) cleanly
    val rootTrashedFolders = remember(trashedFolders, trashedFolderIds) {
        trashedFolders.filter { it.parentId == null || it.parentId !in trashedFolderIds }
    }
    // Display standalone deleted scans (scans whose folder is still active)
    val standaloneTrashedScans = remember(trashedScans, trashedFolderIds) {
        trashedScans.filter { it.folderId !in trashedFolderIds }
    }
    var confirmEmptyTrash by remember { mutableStateOf(false) }
    LaunchedEffect(isAppLocked) {
        if (isAppLocked) {
            confirmEmptyTrash = false
        }
    }

    fun daysLeftText(deletedAt: Long?): String {
        val elapsed = (now - (deletedAt ?: now)).coerceAtLeast(0L)
        val remainingMillis = (TRASH_RETENTION_MILLIS - elapsed).coerceAtLeast(0L)
        val days = ceil(remainingMillis.toDouble() / (24.0 * 3600.0 * 1000.0)).toInt().coerceIn(1, 7)
        return if (isBn) "${days} দিন বাকি" else "${days}d left"
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.56f))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = glass.glassHeaderFill,
                border = BorderStroke(1.2.dp, glass.accentGlassBorderBrush),
                modifier = Modifier
                    .widthIn(max = 540.dp)
                    .fillMaxWidth()
                    .testTag("trash_bin_dialog")
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.RestoreFromTrash,
                                contentDescription = null,
                                tint = glass.accent,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = if (isBn) "৭-দিনের ট্র্যাশ বিন" else "7-Day Trash Bin",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = glass.textPrimary
                                )
                                Text(
                                    text = if (isBn) "৭ দিন পর ফাইল স্থায়ীভাবে মুছে যায়" else "Items auto-purge after 7 days",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = glass.textSecondary
                                )
                            }
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("trash_close_button")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = glass.textSecondary)
                        }
                    }

                    if (rootTrashedFolders.isNotEmpty() || standaloneTrashedScans.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    HapticsAndShareHelper.performConfirm(view)
                                    onRestoreAll()
                                },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, glass.accent),
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("trash_restore_all_button")
                            ) {
                                Icon(Icons.Default.Restore, null, tint = glass.accent, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isBn) "সব ফিরিয়ে আনুন" else "Restore All",
                                    color = glass.accent,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    HapticsAndShareHelper.tapTick(view)
                                    confirmEmptyTrash = true
                                },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("trash_empty_button")
                            ) {
                                Icon(Icons.Default.DeleteSweep, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isBn) "ট্র্যাশ খালি করুন" else "Empty Trash",
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = glass.dividerColor)
                    Spacer(modifier = Modifier.height(10.dp))

                    if (rootTrashedFolders.isEmpty() && standaloneTrashedScans.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.RestoreFromTrash,
                                contentDescription = null,
                                tint = glass.textSecondary.copy(alpha = 0.45f),
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (isBn) "ট্র্যাশ বিন খালি" else "Trash Bin is empty",
                                style = MaterialTheme.typography.bodyMedium,
                                color = glass.textSecondary
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(360.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(rootTrashedFolders, key = { "tf_${it.id}" }) { folder ->
                                val nestedScanCount = trashedScans.count { it.folderId == folder.id }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(glass.glassCardFill)
                                        .border(1.dp, glass.subtleGlassBorderBrush, RoundedCornerShape(12.dp))
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(glass.accentSoft),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Folder, null, tint = glass.accent, modifier = Modifier.size(22.dp))
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = folder.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = glass.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${daysLeftText(folder.deletedAt)} • ${nestedScanCount} ${if (isBn) "আইটেম" else "items"}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = glass.textSecondary
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            HapticsAndShareHelper.performConfirm(view)
                                            onRestoreFolder(folder.id)
                                        },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(Icons.Default.Restore, contentDescription = "Restore", tint = glass.accent)
                                    }
                                    IconButton(
                                        onClick = {
                                            HapticsAndShareHelper.tapTick(view)
                                            onPermanentDeleteFolder(folder.id)
                                        },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(Icons.Default.DeleteForever, contentDescription = "Delete Forever", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }

                            items(standaloneTrashedScans, key = { "ts_${it.id}" }) { scan ->
                                val previewPath = when {
                                    File(scan.thumbnailPath).exists() -> scan.thumbnailPath
                                    File(scan.editedPath).exists() -> scan.editedPath
                                    else -> scan.originalPath
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(glass.glassCardFill)
                                        .border(1.dp, glass.subtleGlassBorderBrush, RoundedCornerShape(12.dp))
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Image(
                                        painter = rememberAsyncImagePainter(model = File(previewPath)),
                                        contentDescription = stripImageExtension(scan.filename),
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stripImageExtension(scan.filename),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = glass.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = daysLeftText(scan.deletedAt),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = glass.textSecondary
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            HapticsAndShareHelper.performConfirm(view)
                                            onRestoreScan(scan.id)
                                        },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(Icons.Default.Restore, contentDescription = "Restore", tint = glass.accent)
                                    }
                                    IconButton(
                                        onClick = {
                                            HapticsAndShareHelper.tapTick(view)
                                            onPermanentDeleteScan(scan.id)
                                        },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(Icons.Default.DeleteForever, contentDescription = "Delete Forever", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmEmptyTrash && !isAppLocked) {
        AlertDialog(
            onDismissRequest = { confirmEmptyTrash = false },
            title = { Text(if (isBn) "সব ফাইল স্থায়ীভাবে মুছে ফেলবেন?" else "Empty Trash Permanently?") },
            text = {
                Text(
                    if (isBn) "ট্র্যাশের সব ফোল্ডার ও ছবি স্থায়ীভাবে মুছে যাবে এবং আর ফিরিয়ে আনা যাবে না।"
                    else "All folders and images in Trash will be permanently deleted and cannot be recovered."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmEmptyTrash = false
                        onEmptyTrash()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = Color.White)
                ) {
                    Text(if (isBn) "স্থায়ীভাবে মুছুন" else "Delete All", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmEmptyTrash = false }) {
                    Text(if (isBn) "বাতিল" else "Cancel")
                }
            }
        )
    }
}
