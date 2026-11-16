package com.example.util

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.core.content.FileProvider
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.R
import com.example.data.ScanEntity
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Provides subtle, non-blocking haptic feedback for every actionable press, a distinct shutter
 * pulse, a stronger long-press response, and a loud multi-pulse confirmation vibration when
 * saving a photo to the Gallery. Also handles native file sharing of edited images and PDFs via
 * FileProvider, plus direct MediaStore Gallery (`Pictures/Kagojpotro`) and Downloads saving.
 */
object HapticsAndShareHelper {

    @Volatile
    var hapticsEnabled: Boolean = true

    fun tapTick(view: View?) {
        if (!hapticsEnabled) return
        runCatching {
            view?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    fun performTick(view: View?) = tapTick(view)

    fun performConfirm(view: View?) {
        if (!hapticsEnabled) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                view?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            } else {
                view?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
    }

    fun longPressPulse(view: View?, context: Context) {
        if (!hapticsEnabled) return
        runCatching {
            val handled = view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) ?: false
            if (!handled) {
                vibrateDuration(context, 42L, 180)
            }
        }
    }

    fun shutterPulse(view: View?, context: Context) {
        if (!hapticsEnabled) return
        runCatching {
            view?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            vibrateDuration(context, 28L, 220)
        }
    }

    /**
     * Triggers a loud, unmistakable double-pulse maximum-amplitude haptic vibration when an image
     * is saved to the device Gallery.
     */
    fun loudGallerySaveVibration(view: View?, context: Context) {
        if (!hapticsEnabled) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                view?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            } else {
                view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val timings = longArrayOf(0L, 95L, 55L, 150L)
                    val amplitudes = intArrayOf(0, 255, 0, 255)
                    vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(longArrayOf(0L, 95L, 55L, 150L), -1)
                }
            }
        }
    }

    private fun vibrateDuration(context: Context, durationMs: Long, amplitude: Int) {
        if (!hapticsEnabled) return
        runCatching {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(
                            durationMs,
                            amplitude.coerceIn(1, 255)
                        )
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(durationMs)
                }
            }
        }
    }

    /**
     * Saves a single edited scan ONCE into the device's public photo Gallery
     * (`Pictures/Kagojpotro` via MediaStore.Images.Media). Only falls back to local file storage
     * if MediaStore insertion is unavailable, preventing duplicate Gallery entries.
     */
    fun saveScanToGallery(context: Context, scan: ScanEntity): Pair<Boolean, String> {
        val editedFile = File(scan.editedPath)
        val thumbFile = File(scan.thumbnailPath)
        val sourceFile = when {
            editedFile.exists() && editedFile.length() > 0L -> editedFile
            thumbFile.exists() && thumbFile.length() > 0L -> thumbFile
            else -> return false to "Gallery (Pictures/Kagojpotro)"
        }
        val fileName = if (scan.filename.endsWith(".jpg", ignoreCase = true)) {
            scan.filename
        } else {
            "${scan.filename}.jpg"
        }

        val savedInGallery = runCatching {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/Kagojpotro"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(sourceFile).use { input ->
                        input.copyTo(out)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val readyValues = ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }
                    resolver.update(uri, readyValues, null, null)
                }
                true
            } else {
                false
            }
        }.getOrDefault(false)

        if (savedInGallery) {
            return true to "Gallery (Pictures/Kagojpotro)"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return false to "Unable to save to Gallery"
        }
        val fallbackDir = File(context.filesDir, "gallery_exports").apply { mkdirs() }
        val fallbackSaved = runCatching {
            sourceFile.copyTo(File(fallbackDir, fileName), overwrite = true)
            true
        }.getOrDefault(false)
        return if (fallbackSaved) {
            true to "App Storage (gallery_exports)"
        } else {
            false to "Unable to save to Gallery"
        }
    }

    /**
     * Shares the edited image files (never the uncropped originals) via the Android system share sheet.
     * Returns true if the share sheet was launched, or false if the fallback download was used.
     */
    fun shareEditedScans(context: Context, scans: List<ScanEntity>): Boolean {
        if (scans.isEmpty()) return false
        return runCatching {
            val authority = "${context.packageName}.fileprovider"
            val uris = ArrayList<Uri>()
            val exportDir = File(context.cacheDir, "shared_images").apply {
                mkdirs()
                listFiles()?.forEach { oldFile ->
                    if (oldFile.isFile) runCatching { oldFile.delete() }
                }
            }
            val usedNames = HashSet<String>(scans.size)

            for (scan in scans) {
                val editedFile = File(scan.editedPath)
                val thumbFile = File(scan.thumbnailPath)
                val sourceFile = when {
                    editedFile.exists() && editedFile.length() > 0L -> editedFile
                    thumbFile.exists() && thumbFile.length() > 0L -> thumbFile
                    else -> null
                }
                if (sourceFile != null) {
                    val baseSafeName = if (scan.filename.endsWith(".jpg", ignoreCase = true)) {
                        scan.filename
                    } else {
                        "${scan.filename}.jpg"
                    }
                    val finalName = if (usedNames.add(baseSafeName.lowercase())) {
                        baseSafeName
                    } else {
                        val stem = baseSafeName.removeSuffix(".jpg").removeSuffix(".JPG")
                        "${stem}_${scan.id.take(4)}.jpg"
                    }
                    val namedCopy = File(exportDir, finalName)
                    sourceFile.copyTo(namedCopy, overwrite = true)
                    val uri = FileProvider.getUriForFile(context, authority, namedCopy)
                    uris.add(uri)
                }
            }

            if (uris.isEmpty()) return false

            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "image/jpeg"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            val chooser = Intent.createChooser(intent, "Share ${scans.size} document(s)").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        }.getOrDefault(false)
    }

    /**
     * Saves all selected edited images into device Gallery & Downloads/Kagojpotro.
     * Returns the number of saved files and target path description.
     */
    fun downloadEditedScansToDevice(context: Context, scans: List<ScanEntity>): Pair<Int, String> {
        var savedCount = 0
        for (scan in scans) {
            val (ok, _) = saveScanToGallery(context, scan)
            if (ok) savedCount++
        }
        return savedCount to "Gallery (Pictures/Kagojpotro)"
    }

    fun sharePdfFile(context: Context, pdfFile: File): Boolean {
        if (!pdfFile.exists()) return false
        return runCatching {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, pdfFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, pdfFile.name).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        }.getOrDefault(false)
    }

    fun downloadPdfToDevice(context: Context, pdfFile: File): Boolean {
        if (!pdfFile.exists()) return false
        return saveFileToDownloads(context, pdfFile, pdfFile.name, "application/pdf")
    }

    fun downloadOrShareServerScript(context: Context, scriptFile: File, shareSheet: Boolean): Boolean {
        if (!scriptFile.exists()) return false
        val savedToDownloads = saveFileToDownloads(context, scriptFile, scriptFile.name, "text/plain")
        if (shareSheet) {
            runCatching {
                val authority = "${context.packageName}.fileprovider"
                val uri = FileProvider.getUriForFile(context, authority, scriptFile)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, scriptFile.name)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(intent, "Send ${scriptFile.name} to your PC").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            }
        }
        return savedToDownloads
    }

    private fun saveFileToDownloads(
        context: Context,
        sourceFile: File,
        displayName: String,
        mimeType: String
    ): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/Kagojpotro"
                    )
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(sourceFile).use { input ->
                            input.copyTo(out)
                        }
                    } ?: return@runCatching false
                    return@runCatching true
                }
                return@runCatching false
            }
            val fallbackDir = File(context.filesDir, "downloads").apply { mkdirs() }
            val dest = File(fallbackDir, displayName)
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(dest).use { out ->
                    input.copyTo(out)
                }
            }
            true
        }.getOrDefault(false)
    }

    /**
     * Opens the device phone dialer with the pre-filled phone number.
     */
    fun openDialer(context: Context, phoneNumber: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${phoneNumber.trim()}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    /**
     * Opens WhatsApp chat with the target phone number, falling back to browser if not installed.
     */
    fun openWhatsApp(context: Context, phoneNumber: String) {
        runCatching {
            val cleanNumber = phoneNumber.replace("+", "").replace(" ", "").replace("-", "").trim()
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber")
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    /**
     * Opens default email client with pre-filled recipient.
     */
    fun openEmail(context: Context, emailAddress: String) {
        runCatching {
            val intent = Intent(
                Intent.ACTION_SENDTO,
                Uri.parse("mailto:${emailAddress.trim()}")
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    /**
     * Opens Facebook profile in native app if available, or in browser.
     */
    fun openFacebook(context: Context, fbUrl: String) {
        runCatching {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(fbUrl.trim())
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    /**
     * Extracts incoming shared image [Uri]s from an `ACTION_SEND` or `ACTION_SEND_MULTIPLE` [Intent]
     * so users can share photos from Gallery/WhatsApp/Files directly into Kagojpotro.
     */
    fun extractSharedImageUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val action = intent.action ?: return emptyList()
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return emptyList()
        val type = intent.type.orEmpty()
        if (type.isNotBlank() && !type.startsWith("image/", ignoreCase = true) && type != "*/*") {
            return emptyList()
        }
        val collected = LinkedHashSet<Uri>()
        if (action == Intent.ACTION_SEND) {
            val single = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            if (single != null) collected.add(single)
        } else if (action == Intent.ACTION_SEND_MULTIPLE) {
            val multi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            }
            multi?.forEach { uri -> collected.add(uri) }
        }
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let { u -> collected.add(u) }
            }
        }
        return collected.toList()
    }

    /**
     * Shared interactive Google Sign-In flow via Jetpack Credential Manager + Firebase Auth.
     */
    fun performGoogleCredentialSignIn(
        context: Context,
        credentialManager: CredentialManager,
        scope: CoroutineScope,
        onAuthSuccess: (String) -> Unit,
        onAuthError: (String) -> Unit,
        onAuthCancelled: () -> Unit = {}
    ) {
        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (e: Exception) {
            onAuthError("Google Sign-In configuration missing: default_web_client_id not found")
            return
        }

        val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()

        val activity = context.findActivity()
        if (activity == null) {
            onAuthError("Unable to access current window for Google Sign-In")
            return
        }

        scope.launch {
            try {
                val result = credentialManager.getCredential(activity, request)
                val credential = result.credential
                if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data)
                    val authCredential = GoogleAuthProvider.getCredential(googleIdToken.idToken, null)
                    val authResult = Firebase.auth.signInWithCredential(authCredential).await()
                    val email = authResult.user?.email ?: googleIdToken.id
                    onAuthSuccess(email)
                } else {
                    onAuthError("Unexpected credential type")
                }
            } catch (e: GetCredentialCancellationException) {
                Log.w("Auth", "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
                onAuthCancelled()
            } catch (e: Exception) {
                Log.e("Auth", "Google Sign-In failed", e)
                onAuthError(e.localizedMessage ?: "Sign in failed")
            }
        }
    }

    /**
     * Shared silent auto sign-in check when Google Cloud Sync mode is active.
     */
    fun attemptGoogleAutoSignIn(
        context: Context,
        credentialManager: CredentialManager,
        scope: CoroutineScope,
        onAuthSuccess: (String) -> Unit,
        onUnauthenticated: () -> Unit = {}
    ) {
        val existingUser = Firebase.auth.currentUser
        if (existingUser != null && !existingUser.email.isNullOrBlank()) {
            onAuthSuccess(existingUser.email.orEmpty())
            return
        }
        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (e: Exception) {
            onUnauthenticated()
            return
        }

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(true)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val hostActivity = context.findActivity()
        if (hostActivity == null) {
            onUnauthenticated()
            return
        }
        scope.launch {
            try {
                val result = credentialManager.getCredential(hostActivity, request)
                val credential = result.credential
                if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data)
                    val authCredential = GoogleAuthProvider.getCredential(googleIdToken.idToken, null)
                    val authResult = Firebase.auth.signInWithCredential(authCredential).await()
                    val email = authResult.user?.email ?: googleIdToken.id
                    onAuthSuccess(email)
                } else {
                    onUnauthenticated()
                }
            } catch (e: Exception) {
                onUnauthenticated()
            }
        }
    }

    /**
     * Clears CredentialManager state on Google Sign-Out.
     */
    fun signOutGoogleCredential(
        credentialManager: CredentialManager,
        scope: CoroutineScope,
        onSignOutComplete: () -> Unit = {}
    ) {
        scope.launch {
            withContext(NonCancellable) {
                try {
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                } catch (e: Exception) {
                    Log.w("Auth", "Failed to clear credential state: ${e.message}")
                } finally {
                    onSignOutComplete()
                }
            }
        }
    }
}

