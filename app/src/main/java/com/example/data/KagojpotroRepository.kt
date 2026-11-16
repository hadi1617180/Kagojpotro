package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.processing.DocumentImageProcessor
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.kagojpotroDataStore by preferencesDataStore(name = "kagojpotro_prefs")

sealed class FolderOperationResult {
    data class Success(val folder: FolderEntity) : FolderOperationResult()
    data object EmptyName : FolderOperationResult()
    data object DuplicateName : FolderOperationResult()
    data object MaxDepthExceeded : FolderOperationResult()
    data class Error(val message: String) : FolderOperationResult()
}

sealed class ScanOperationResult {
    data class Success(val scan: ScanEntity) : ScanOperationResult()
    data object EmptyName : ScanOperationResult()
    data object DuplicateName : ScanOperationResult()
    data class Error(val message: String) : ScanOperationResult()
}

sealed class BulkRenameResult {
    data class Success(
        val renamedCount: Int,
        val previousNamesByScanId: Map<String, String>
    ) : BulkRenameResult()
    data class Error(val message: String) : BulkRenameResult()
}

data class TrashBatchRecord(
    val folderIds: List<String>,
    val scanIds: List<String>,
    val trashedFoldersCount: Int,
    val trashedScansCount: Int
)

sealed class PinVerifyResult {
    data object Success : PinVerifyResult()
    data class Invalid(val failedAttempts: Int, val remainingBeforeLockout: Int) : PinVerifyResult()
    data class LockedOut(val remainingSeconds: Int) : PinVerifyResult()
}

data class PinLockoutState(
    val failedAttempts: Int = 0,
    val lockoutUntilMs: Long = 0L
)

const val TRASH_RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1000L

class KagojpotroRepository(
    private val context: Context,
    private val database: KagojpotroDatabase = KagojpotroDatabase.getInstance(context)
) {
    init {
        DocumentImageProcessor.init(context.applicationContext)
    }

    private val folderDao = database.folderDao()
    private val scanDao = database.scanDao()

    private val originalsDir: File
        get() = File(context.filesDir, "originals").apply { mkdirs() }
    private val editedDir: File
        get() = File(context.filesDir, "edited").apply { mkdirs() }
    private val thumbsDir: File
        get() = File(context.filesDir, "thumbs").apply { mkdirs() }
    private val draftsDir: File
        get() = File(context.cacheDir, "draft_originals").apply { mkdirs() }
    private val exportsDir: File
        get() = File(context.cacheDir, "pdf_exports").apply { mkdirs() }

    val pcSyncManager: PcSyncManager by lazy {
        PcSyncManager(
            database = database,
            originalsDir = originalsDir,
            editedDir = editedDir,
            thumbsDir = thumbsDir
        )
    }

    val googleDriveSyncManager: GoogleDriveSyncManager by lazy {
        GoogleDriveSyncManager(
            context = context,
            database = database,
            originalsDir = originalsDir,
            editedDir = editedDir,
            thumbsDir = thumbsDir
        )
    }

    val zipBackupManager: ZipBackupManager by lazy {
        ZipBackupManager(
            context = context,
            database = database,
            originalsDir = originalsDir,
            editedDir = editedDir,
            thumbsDir = thumbsDir
        )
    }

    val githubReleaseUpdater: GithubReleaseUpdater by lazy {
        GithubReleaseUpdater(context.applicationContext)
    }

    private object PrefKeys {
        val VIEW_MODE = stringPreferencesKey("folder_view_mode")
        val FOLDER_SORT = stringPreferencesKey("folder_sort_mode")
        val FOLDER_SORT_ASC = stringPreferencesKey("folder_sort_ascending")
        val IMAGE_SORT = stringPreferencesKey("image_sort_mode")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val THEME_MODE = stringPreferencesKey("app_theme_mode")
        val ACCENT_COLOR_HEX = stringPreferencesKey("accent_color_hex")
        val DEFAULT_SCAN_FILTER = stringPreferencesKey("default_scan_filter")
        val SCAN_RESOLUTION = stringPreferencesKey("scan_resolution_preset")
        val HAPTICS_ENABLED = stringPreferencesKey("haptics_enabled")
        val PC_SYNC_MODE = stringPreferencesKey("pc_sync_mode")
        val PC_SERVER_URL = stringPreferencesKey("pc_server_url")
        val PC_USER_DIR = stringPreferencesKey("pc_user_directory")
        val PC_ACCESS_KEY = stringPreferencesKey("pc_access_key")
        val PC_AUTO_SYNC = stringPreferencesKey("pc_auto_sync_enabled")
        val GOOGLE_DRIVE_EMAIL = stringPreferencesKey("google_drive_email")
        val GOOGLE_DRIVE_AUTO_SYNC = stringPreferencesKey("google_drive_auto_sync")
        val GOOGLE_SYNC_BACKUP_TIER = stringPreferencesKey("google_sync_backup_tier")
        val DELETED_FOLDER_IDS = stringPreferencesKey("deleted_folder_ids_csv")
        val DELETED_SCAN_IDS = stringPreferencesKey("deleted_scan_ids_csv")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        val APP_PIN_HASH = stringPreferencesKey("app_pin_hash")
        val APP_PIN_SALT = stringPreferencesKey("app_pin_salt")
        val APP_PIN_ALGO = stringPreferencesKey("app_pin_algo")
        val PIN_FAILED_ATTEMPTS = intPreferencesKey("pin_failed_attempts")
        val PIN_LOCKOUT_UNTIL_MS = longPreferencesKey("pin_lockout_until_ms")
        val BIOMETRIC_LOCK_ENABLED = booleanPreferencesKey("biometric_lock_enabled")
        val LAST_SYNCED_ACCOUNT_KEY = stringPreferencesKey("last_synced_account_key")
        val GITHUB_REPO_SLUG = stringPreferencesKey("github_update_repo_slug")
    }

    val allFoldersFlow: Flow<List<FolderEntity>> = folderDao.observeAllFolders()
    val allScansFlow: Flow<List<ScanEntity>> = scanDao.observeAllScans()
    val trashedFoldersFlow: Flow<List<FolderEntity>> = folderDao.observeTrashedFolders()
    val trashedScansFlow: Flow<List<ScanEntity>> = scanDao.observeTrashedScans()

    val viewModeFlow: Flow<FolderViewMode> = context.kagojpotroDataStore.data.map { prefs ->
        runCatching {
            FolderViewMode.valueOf(prefs[PrefKeys.VIEW_MODE] ?: FolderViewMode.GRID.name)
        }.getOrDefault(FolderViewMode.GRID)
    }

    val folderSortFlow: Flow<FolderSortMode> = context.kagojpotroDataStore.data.map { prefs ->
        runCatching {
            FolderSortMode.valueOf(prefs[PrefKeys.FOLDER_SORT] ?: FolderSortMode.LAST_MODIFIED.name)
        }.getOrDefault(FolderSortMode.LAST_MODIFIED)
    }

    val folderSortAscendingFlow: Flow<Boolean> = context.kagojpotroDataStore.data.map { prefs ->
        val raw = prefs[PrefKeys.FOLDER_SORT_ASC]
        if (raw != null) {
            raw.toBooleanStrictOrNull() ?: false
        } else {
            false
        }
    }

    val imageSortFlow: Flow<ImageSortMode> = context.kagojpotroDataStore.data.map { prefs ->
        runCatching {
            ImageSortMode.valueOf(prefs[PrefKeys.IMAGE_SORT] ?: ImageSortMode.NEWEST_FIRST.name)
        }.getOrDefault(ImageSortMode.NEWEST_FIRST)
    }

    val languageFlow: Flow<AppLanguage> = context.kagojpotroDataStore.data.map { prefs ->
        runCatching {
            AppLanguage.valueOf(prefs[PrefKeys.APP_LANGUAGE] ?: AppLanguage.EN.name)
        }.getOrDefault(AppLanguage.EN)
    }

    val themeModeFlow: Flow<AppThemeMode> = context.kagojpotroDataStore.data.map { prefs ->
        runCatching {
            AppThemeMode.valueOf(prefs[PrefKeys.THEME_MODE] ?: AppThemeMode.SYSTEM.name)
        }.getOrDefault(AppThemeMode.SYSTEM)
    }

    val accentColorHexFlow: Flow<String> = context.kagojpotroDataStore.data.map { prefs ->
        prefs[PrefKeys.ACCENT_COLOR_HEX] ?: "#0F8B6D"
    }

    val defaultScanFilterFlow: Flow<ScanFilter> = context.kagojpotroDataStore.data.map { prefs ->
        runCatching {
            ScanFilter.valueOf(prefs[PrefKeys.DEFAULT_SCAN_FILTER] ?: ScanFilter.MAGIC_COLOR.name)
        }.getOrDefault(ScanFilter.MAGIC_COLOR)
    }

    val scanResolutionFlow: Flow<ScanResolutionPreset> = context.kagojpotroDataStore.data.map { prefs ->
        runCatching {
            ScanResolutionPreset.valueOf(prefs[PrefKeys.SCAN_RESOLUTION] ?: ScanResolutionPreset.MAXIMUM_4096.name)
        }.getOrDefault(ScanResolutionPreset.MAXIMUM_4096)
    }

    val hapticsEnabledFlow: Flow<Boolean> = context.kagojpotroDataStore.data.map { prefs ->
        val enabled = prefs[PrefKeys.HAPTICS_ENABLED]?.toBooleanStrictOrNull() ?: true
        com.example.util.HapticsAndShareHelper.hapticsEnabled = enabled
        enabled
    }

    val pcSyncConfigFlow: Flow<PcSyncConfig> = context.kagojpotroDataStore.data.map { prefs ->
        val mode = runCatching {
            AppStorageSyncMode.valueOf(prefs[PrefKeys.PC_SYNC_MODE] ?: AppStorageSyncMode.PHONE_ONLY.name)
        }.getOrDefault(AppStorageSyncMode.PHONE_ONLY)
        val rawUser = prefs[PrefKeys.PC_USER_DIR] ?: ""
        val serverUrl = prefs[PrefKeys.PC_SERVER_URL] ?: ""
        val autoSync = prefs[PrefKeys.PC_AUTO_SYNC]?.toBooleanStrictOrNull() ?: true
        PcSyncConfig(
            mode = mode,
            serverUrl = serverUrl,
            userDirectory = rawUser,
            accessKey = "",
            autoSyncEnabled = autoSync
        )
    }

    val googleDriveEmailFlow: Flow<String> = context.kagojpotroDataStore.data.map { prefs ->
        prefs[PrefKeys.GOOGLE_DRIVE_EMAIL] ?: ""
    }

    val googleDriveAutoSyncFlow: Flow<Boolean> = context.kagojpotroDataStore.data.map { prefs ->
        prefs[PrefKeys.GOOGLE_DRIVE_AUTO_SYNC]?.toBooleanStrictOrNull() ?: true
    }

    val googleSyncBackupTierFlow: Flow<String> = context.kagojpotroDataStore.data.map { prefs ->
        prefs[PrefKeys.GOOGLE_SYNC_BACKUP_TIER] ?: "NONE"
    }

    suspend fun getTrashedFolderIdsOnce(): Set<String> = withContext(Dispatchers.IO) {
        folderDao.getTrashedFoldersOnce().map { it.id }.toSet()
    }

    suspend fun getTrashedScanIdsOnce(): Set<String> = withContext(Dispatchers.IO) {
        scanDao.getTrashedScansOnce().map { it.id }.toSet()
    }

    val deletedFolderIdsFlow: Flow<Set<String>> = context.kagojpotroDataStore.data.map { prefs ->
        prefs[PrefKeys.DELETED_FOLDER_IDS]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
    }

    val deletedScanIdsFlow: Flow<Set<String>> = context.kagojpotroDataStore.data.map { prefs ->
        prefs[PrefKeys.DELETED_SCAN_IDS]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
    }

    val onboardingCompletedFlow: Flow<Boolean> = context.kagojpotroDataStore.data.map { prefs ->
        prefs[PrefKeys.ONBOARDING_COMPLETED] ?: false
    }

    val appLockEnabledFlow: Flow<Boolean> = context.kagojpotroDataStore.data.map { prefs ->
        prefs[PrefKeys.APP_LOCK_ENABLED] ?: false
    }

    val hasAppPinFlow: Flow<Boolean> = context.kagojpotroDataStore.data.map { prefs ->
        !prefs[PrefKeys.APP_PIN_HASH].isNullOrBlank() && !prefs[PrefKeys.APP_PIN_SALT].isNullOrBlank()
    }

    val biometricLockEnabledFlow: Flow<Boolean> = context.kagojpotroDataStore.data.map { prefs ->
        prefs[PrefKeys.BIOMETRIC_LOCK_ENABLED] ?: false
    }

    val pinLockoutStateFlow: Flow<PinLockoutState> = context.kagojpotroDataStore.data.map { prefs ->
        PinLockoutState(
            failedAttempts = prefs[PrefKeys.PIN_FAILED_ATTEMPTS] ?: 0,
            lockoutUntilMs = prefs[PrefKeys.PIN_LOCKOUT_UNTIL_MS] ?: 0L
        )
    }

    val lastSyncedAccountKeyFlow: Flow<String> = context.kagojpotroDataStore.data.map { prefs ->
        prefs[PrefKeys.LAST_SYNCED_ACCOUNT_KEY] ?: ""
    }

    val githubRepoSlugFlow: Flow<String> = context.kagojpotroDataStore.data.map { prefs ->
        val raw = prefs[PrefKeys.GITHUB_REPO_SLUG]?.trim().orEmpty()
        val normalized = GithubReleaseUpdater.normalizeRepoSlug(raw)
        if (normalized.isBlank() || normalized.equals("hadi280994/kagojpotro", ignoreCase = true)) {
            GithubReleaseUpdater.DEFAULT_GITHUB_REPO
        } else {
            normalized
        }
    }

    suspend fun getLastSyncedAccountKey(): String {
        return lastSyncedAccountKeyFlow.first().trim()
    }

    suspend fun setLastSyncedAccountKey(accountKey: String) {
        context.kagojpotroDataStore.edit { prefs ->
            prefs[PrefKeys.LAST_SYNCED_ACCOUNT_KEY] = accountKey.trim()
        }
    }

    suspend fun hasAnyLocalFoldersOrScans(): Boolean = withContext(Dispatchers.IO) {
        folderDao.getAllFoldersOnce().isNotEmpty() || scanDao.getAllScansOnce().isNotEmpty()
    }

    suspend fun resetLocalWorkspaceForAccountSwitch(newAccountKey: String) = withContext(Dispatchers.IO) {
        clearAllLibraryData()
        clearDeletedIdsForFullRestore()
        setLastSyncedAccountKey(newAccountKey)
    }

    suspend fun prepareLocalWorkspaceForAccountMerge(newAccountKey: String) = withContext(Dispatchers.IO) {
        clearDeletedIdsForFullRestore()
        val folders = folderDao.getAllFoldersOnce()
        if (folders.isNotEmpty()) {
            folderDao.updateFolders(folders.map { it.copy(syncState = "LOCAL_DIRTY") })
        }
        val scans = scanDao.getAllScansOnce()
        for (scan in scans) {
            scanDao.updateScanSyncState(scan.id, "LOCAL_DIRTY")
        }
        setLastSyncedAccountKey(newAccountKey)
    }

    suspend fun setOnboardingCompleted(completed: Boolean = true) {
        context.kagojpotroDataStore.edit { prefs ->
            prefs[PrefKeys.ONBOARDING_COMPLETED] = completed
        }
    }

    private fun hashPinPbkdf2(pin: String, saltBase64: String): String {
        val saltBytes = runCatching { Base64.decode(saltBase64, Base64.NO_WRAP) }
            .getOrElse { saltBase64.toByteArray(Charsets.UTF_8) }
        val spec = PBEKeySpec(pin.toCharArray(), saltBytes, 100_000, 256)
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val derived = factory.generateSecret(spec).encoded
            Base64.encodeToString(derived, Base64.NO_WRAP)
        } finally {
            spec.clearPassword()
        }
    }

    private fun legacyHashPinSha256(pin: String, saltBase64: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$saltBase64:$pin".toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    suspend fun setAppLockPin(pin: String): Boolean = withContext(Dispatchers.Default) {
        val cleanPin = pin.trim()
        if (cleanPin.length != 4 || !cleanPin.all { it.isDigit() }) return@withContext false
        val saltBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val saltBase64 = Base64.encodeToString(saltBytes, Base64.NO_WRAP)
        val hashed = hashPinPbkdf2(cleanPin, saltBase64)
        context.kagojpotroDataStore.edit { prefs ->
            prefs[PrefKeys.APP_PIN_SALT] = saltBase64
            prefs[PrefKeys.APP_PIN_HASH] = hashed
            prefs[PrefKeys.APP_PIN_ALGO] = "PBKDF2_SHA256"
            prefs[PrefKeys.PIN_FAILED_ATTEMPTS] = 0
            prefs[PrefKeys.PIN_LOCKOUT_UNTIL_MS] = 0L
            prefs[PrefKeys.APP_LOCK_ENABLED] = true
        }
        true
    }

    suspend fun verifyAppLockPin(pin: String): Boolean {
        return verifyAppLockPinWithStatus(pin) is PinVerifyResult.Success
    }

    suspend fun verifyAppLockPinWithStatus(
        pin: String,
        nowMs: Long = System.currentTimeMillis()
    ): PinVerifyResult = withContext(Dispatchers.Default) {
        val prefs = context.kagojpotroDataStore.data.first()
        val lockoutUntil = prefs[PrefKeys.PIN_LOCKOUT_UNTIL_MS] ?: 0L
        if (lockoutUntil > nowMs) {
            val remainingSec = (((lockoutUntil - nowMs) + 999L) / 1000L).toInt().coerceAtLeast(1)
            return@withContext PinVerifyResult.LockedOut(remainingSec)
        }

        val salt = prefs[PrefKeys.APP_PIN_SALT].orEmpty()
        val expected = prefs[PrefKeys.APP_PIN_HASH].orEmpty()
        val algo = prefs[PrefKeys.APP_PIN_ALGO].orEmpty()
        val cleanPin = pin.trim()
        if (salt.isBlank() || expected.isBlank()) {
            return@withContext PinVerifyResult.Invalid(0, 5)
        }

        val matches = if (algo == "PBKDF2_SHA256") {
            val actualBytes = Base64.decode(hashPinPbkdf2(cleanPin, salt), Base64.NO_WRAP)
            val expectedBytes = runCatching { Base64.decode(expected, Base64.NO_WRAP) }.getOrNull()
            expectedBytes != null && MessageDigest.isEqual(actualBytes, expectedBytes)
        } else {
            val legacyBytes = legacyHashPinSha256(cleanPin, salt).toByteArray(Charsets.UTF_8)
            val expectedBytes = expected.toByteArray(Charsets.UTF_8)
            MessageDigest.isEqual(legacyBytes, expectedBytes)
        }

        if (matches) {
            if (algo != "PBKDF2_SHA256") {
                // Upgrade legacy SHA-256 hash seamlessly to PBKDF2WithHmacSHA256 on successful verify
                val newSaltBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val newSalt = Base64.encodeToString(newSaltBytes, Base64.NO_WRAP)
                val newHash = hashPinPbkdf2(cleanPin, newSalt)
                context.kagojpotroDataStore.edit { editPrefs ->
                    editPrefs[PrefKeys.APP_PIN_SALT] = newSalt
                    editPrefs[PrefKeys.APP_PIN_HASH] = newHash
                    editPrefs[PrefKeys.APP_PIN_ALGO] = "PBKDF2_SHA256"
                    editPrefs[PrefKeys.PIN_FAILED_ATTEMPTS] = 0
                    editPrefs[PrefKeys.PIN_LOCKOUT_UNTIL_MS] = 0L
                }
            } else if ((prefs[PrefKeys.PIN_FAILED_ATTEMPTS] ?: 0) != 0 || lockoutUntil != 0L) {
                context.kagojpotroDataStore.edit { editPrefs ->
                    editPrefs[PrefKeys.PIN_FAILED_ATTEMPTS] = 0
                    editPrefs[PrefKeys.PIN_LOCKOUT_UNTIL_MS] = 0L
                }
            }
            return@withContext PinVerifyResult.Success
        }

        val newAttempts = (prefs[PrefKeys.PIN_FAILED_ATTEMPTS] ?: 0) + 1
        val cooldownMs = when {
            newAttempts >= 12 -> 300_000L
            newAttempts >= 8 -> 120_000L
            newAttempts >= 5 -> 30_000L
            else -> 0L
        }
        val nextLockUntil = if (cooldownMs > 0L) nowMs + cooldownMs else 0L
        context.kagojpotroDataStore.edit { editPrefs ->
            editPrefs[PrefKeys.PIN_FAILED_ATTEMPTS] = newAttempts
            editPrefs[PrefKeys.PIN_LOCKOUT_UNTIL_MS] = nextLockUntil
        }
        if (cooldownMs > 0L) {
            PinVerifyResult.LockedOut((cooldownMs / 1000L).toInt())
        } else {
            PinVerifyResult.Invalid(
                failedAttempts = newAttempts,
                remainingBeforeLockout = (5 - newAttempts).coerceAtLeast(1)
            )
        }
    }

    suspend fun clearPinLockoutState() {
        context.kagojpotroDataStore.edit { prefs ->
            prefs[PrefKeys.PIN_FAILED_ATTEMPTS] = 0
            prefs[PrefKeys.PIN_LOCKOUT_UNTIL_MS] = 0L
        }
    }

    suspend fun setAppLockEnabled(enabled: Boolean) {
        context.kagojpotroDataStore.edit { prefs ->
            prefs[PrefKeys.APP_LOCK_ENABLED] = enabled
        }
    }

    suspend fun setBiometricLockEnabled(enabled: Boolean) {
        context.kagojpotroDataStore.edit { prefs ->
            prefs[PrefKeys.BIOMETRIC_LOCK_ENABLED] = enabled
        }
    }

    suspend fun setViewMode(mode: FolderViewMode) {
        context.kagojpotroDataStore.edit { it[PrefKeys.VIEW_MODE] = mode.name }
    }

    suspend fun setFolderSortMode(mode: FolderSortMode) {
        context.kagojpotroDataStore.edit { it[PrefKeys.FOLDER_SORT] = mode.name }
    }

    suspend fun setFolderSortAscending(ascending: Boolean) {
        context.kagojpotroDataStore.edit { it[PrefKeys.FOLDER_SORT_ASC] = ascending.toString() }
    }

    suspend fun setImageSortMode(mode: ImageSortMode) {
        context.kagojpotroDataStore.edit { it[PrefKeys.IMAGE_SORT] = mode.name }
    }

    suspend fun setLanguage(lang: AppLanguage) {
        context.kagojpotroDataStore.edit { it[PrefKeys.APP_LANGUAGE] = lang.name }
    }

    suspend fun setThemeMode(mode: AppThemeMode) {
        context.kagojpotroDataStore.edit { it[PrefKeys.THEME_MODE] = mode.name }
    }

    suspend fun setAccentColorHex(hex: String) {
        val formatted = if (hex.startsWith("#")) hex.uppercase(Locale.US) else "#${hex.uppercase(Locale.US)}"
        context.kagojpotroDataStore.edit { it[PrefKeys.ACCENT_COLOR_HEX] = formatted }
    }

    suspend fun setDefaultScanFilter(filter: ScanFilter) {
        context.kagojpotroDataStore.edit { it[PrefKeys.DEFAULT_SCAN_FILTER] = filter.name }
    }

    suspend fun setScanResolutionPreset(preset: ScanResolutionPreset) {
        context.kagojpotroDataStore.edit { it[PrefKeys.SCAN_RESOLUTION] = preset.name }
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        com.example.util.HapticsAndShareHelper.hapticsEnabled = enabled
        context.kagojpotroDataStore.edit { it[PrefKeys.HAPTICS_ENABLED] = enabled.toString() }
    }

    suspend fun updatePcSyncConfig(config: PcSyncConfig) {
        val trimmedUser = config.userDirectory.trim()
        val cleanUser = if (trimmedUser.isEmpty()) "" else pcSyncManager.sanitizeUserDirectory(trimmedUser)
        context.kagojpotroDataStore.edit { prefs ->
            prefs[PrefKeys.PC_SYNC_MODE] = config.mode.name
            prefs[PrefKeys.PC_SERVER_URL] = config.serverUrl.trim()
            prefs[PrefKeys.PC_USER_DIR] = cleanUser
            prefs[PrefKeys.PC_ACCESS_KEY] = ""
            prefs[PrefKeys.PC_AUTO_SYNC] = config.autoSyncEnabled.toString()
        }
    }

    suspend fun setGoogleDriveEmail(email: String) {
        context.kagojpotroDataStore.edit { prefs ->
            prefs[PrefKeys.GOOGLE_DRIVE_EMAIL] = email.trim()
        }
    }

    suspend fun setGoogleDriveAutoSync(enabled: Boolean) {
        context.kagojpotroDataStore.edit { prefs ->
            prefs[PrefKeys.GOOGLE_DRIVE_AUTO_SYNC] = enabled.toString()
        }
    }

    suspend fun setGoogleSyncBackupTier(tier: String) {
        context.kagojpotroDataStore.edit { prefs ->
            prefs[PrefKeys.GOOGLE_SYNC_BACKUP_TIER] = tier
        }
    }

    suspend fun setGithubRepoSlug(rawRepoSlug: String): String {
        val clean = GithubReleaseUpdater.normalizeRepoSlug(rawRepoSlug)
            .ifBlank { GithubReleaseUpdater.DEFAULT_GITHUB_REPO }
        context.kagojpotroDataStore.edit { prefs ->
            prefs[PrefKeys.GITHUB_REPO_SLUG] = clean
        }
        return clean
    }

    suspend fun clearDeletedIdsForFullRestore() {
        context.kagojpotroDataStore.edit { prefs ->
            prefs[PrefKeys.DELETED_FOLDER_IDS] = ""
            prefs[PrefKeys.DELETED_SCAN_IDS] = ""
        }
    }

    suspend fun pruneDeletedIds(restoredFolderIds: Set<String>, restoredScanIds: Set<String>) {
        if (restoredFolderIds.isEmpty() && restoredScanIds.isEmpty()) return
        context.kagojpotroDataStore.edit { prefs ->
            if (restoredFolderIds.isNotEmpty()) {
                val existing = prefs[PrefKeys.DELETED_FOLDER_IDS]
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() && it !in restoredFolderIds }
                    .orEmpty()
                prefs[PrefKeys.DELETED_FOLDER_IDS] = existing.joinToString(",")
            }
            if (restoredScanIds.isNotEmpty()) {
                val existing = prefs[PrefKeys.DELETED_SCAN_IDS]
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() && it !in restoredScanIds }
                    .orEmpty()
                prefs[PrefKeys.DELETED_SCAN_IDS] = existing.joinToString(",")
            }
        }
    }

    private suspend fun recordDeletedIds(folderIds: Collection<String>, scanIds: Collection<String>) {
        if (folderIds.isEmpty() && scanIds.isEmpty()) return
        context.kagojpotroDataStore.edit { prefs ->
            if (folderIds.isNotEmpty()) {
                val existing = prefs[PrefKeys.DELETED_FOLDER_IDS]
                    ?.split(",")
                    ?.filter { it.isNotBlank() }
                    ?.toMutableSet() ?: mutableSetOf()
                existing.addAll(folderIds)
                prefs[PrefKeys.DELETED_FOLDER_IDS] = existing.toList().takeLast(500).joinToString(",")
            }
            if (scanIds.isNotEmpty()) {
                val existing = prefs[PrefKeys.DELETED_SCAN_IDS]
                    ?.split(",")
                    ?.filter { it.isNotBlank() }
                    ?.toMutableSet() ?: mutableSetOf()
                existing.addAll(scanIds)
                prefs[PrefKeys.DELETED_SCAN_IDS] = existing.toList().takeLast(1500).joinToString(",")
            }
        }
    }

    private fun normalizeNameForComparison(name: String): String {
        val trimmed = name.trim()
        return Normalizer.normalize(trimmed, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    }

    /**
     * Creates a new top-level folder (`parentId == null`) or a 1st-level subfolder under a root
     * folder (`parent.parentId == null`). Trims whitespace, rejects empty names, enforces the
     * 1-level subfolder limit, checks for duplicate names within the same parent, and assigns
     * the next customOrder index.
     */
    suspend fun createFolder(
        rawName: String,
        parentId: String?,
        now: Long = System.currentTimeMillis()
    ): FolderOperationResult = withContext(Dispatchers.IO) {
        val trimmed = rawName.trim()
        if (trimmed.isEmpty()) {
            return@withContext FolderOperationResult.EmptyName
        }

        if (parentId != null) {
            val parent = folderDao.getFolderById(parentId)
                ?: return@withContext FolderOperationResult.Error("Parent folder not found")
            if (parent.parentId != null) {
                return@withContext FolderOperationResult.MaxDepthExceeded
            }
        }

        val siblings = if (parentId == null) {
            folderDao.getTopLevelFoldersOnce()
        } else {
            folderDao.getSubfoldersOnce(parentId)
        }

        val targetNorm = normalizeNameForComparison(trimmed)
        if (siblings.any { normalizeNameForComparison(it.name) == targetNorm }) {
            return@withContext FolderOperationResult.DuplicateName
        }

        val nextCustomOrder = (siblings.maxOfOrNull { it.customOrder } ?: -1) + 1
        val folder = FolderEntity(
            id = UUID.randomUUID().toString(),
            name = trimmed,
            parentId = parentId,
            createdAt = now,
            modifiedAt = now,
            customOrder = nextCustomOrder
        )
        folderDao.insertFolder(folder)
        if (parentId != null) {
            database.touchFolderAndAncestors(parentId, now)
        }
        FolderOperationResult.Success(folder)
    }

    suspend fun renameFolder(
        folderId: String,
        newRawName: String,
        now: Long = System.currentTimeMillis()
    ): FolderOperationResult = withContext(Dispatchers.IO) {
        val trimmed = newRawName.trim()
        if (trimmed.isEmpty()) {
            return@withContext FolderOperationResult.EmptyName
        }
        val existing = folderDao.getFolderById(folderId)
            ?: return@withContext FolderOperationResult.Error("Folder not found")

        val siblings = if (existing.parentId == null) {
            folderDao.getTopLevelFoldersOnce()
        } else {
            folderDao.getSubfoldersOnce(existing.parentId)
        }.filter { it.id != folderId }

        val targetNorm = normalizeNameForComparison(trimmed)
        if (siblings.any { normalizeNameForComparison(it.name) == targetNorm }) {
            return@withContext FolderOperationResult.DuplicateName
        }

        val updated = existing.copy(
            name = trimmed,
            modifiedAt = now,
            syncState = "LOCAL_DIRTY"
        )
        folderDao.updateFolder(updated)
        if (existing.parentId != null) {
            database.touchFolderAndAncestors(existing.parentId, now)
        }
        FolderOperationResult.Success(updated)
    }

    /**
     * Persists a user-defined custom folder ordering for siblings in a given parent folder.
     */
    suspend fun updateCustomFolderOrder(orderedFolderIds: List<String>) = withContext(Dispatchers.IO) {
        if (orderedFolderIds.isEmpty()) return@withContext
        val all = folderDao.getAllFoldersOnce().associateBy { it.id }
        val updatedList = orderedFolderIds.mapIndexedNotNull { index, id ->
            val existing = all[id] ?: return@mapIndexedNotNull null
            if (existing.customOrder == index) null else existing.copy(customOrder = index, syncState = "LOCAL_DIRTY")
        }
        if (updatedList.isNotEmpty()) {
            folderDao.updateFolders(updatedList)
        }
    }

    private suspend fun collectAllDescendantFolderIds(rootFolderId: String): List<String> {
        val allFolders = folderDao.getAllFoldersOnce()
        val childrenByParent = allFolders.groupBy { it.parentId }
        val result = mutableListOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(rootFolderId)
        while (queue.isNotEmpty()) {
            val curr = queue.removeFirst()
            result.add(curr)
            childrenByParent[curr]?.forEach { child ->
                queue.add(child.id)
            }
        }
        return result
    }

    suspend fun getFolderDeleteSummary(folderId: String): FolderDeleteSummary? = withContext(Dispatchers.IO) {
        val folder = folderDao.getFolderById(folderId) ?: return@withContext null
        val allFolderIds = collectAllDescendantFolderIds(folderId)
        val scans = scanDao.getScansInFoldersOnce(allFolderIds)
        FolderDeleteSummary(
            folder = folder,
            subfolderCount = (allFolderIds.size - 1).coerceAtLeast(0),
            totalImageCount = scans.size
        )
    }

    suspend fun getFoldersDeleteSummary(folderIds: List<String>): MultiFolderDeleteSummary? = withContext(Dispatchers.IO) {
        val distinctIds = folderIds.distinct()
        if (distinctIds.isEmpty()) return@withContext null
        val targetFolders = distinctIds.mapNotNull { folderDao.getFolderById(it) }
        if (targetFolders.isEmpty()) return@withContext null

        val allFolderIds = linkedSetOf<String>()
        for (target in targetFolders) {
            allFolderIds.addAll(collectAllDescendantFolderIds(target.id))
        }
        val scans = scanDao.getScansInFoldersOnce(allFolderIds.toList())
        MultiFolderDeleteSummary(
            folders = targetFolders,
            subfolderCount = (allFolderIds.size - targetFolders.size).coerceAtLeast(0),
            totalImageCount = scans.size
        )
    }

    suspend fun deleteFolderAndContents(
        folderId: String,
        now: Long = System.currentTimeMillis()
    ): Boolean = moveFoldersToTrash(listOf(folderId), now) != null

    suspend fun deleteFoldersAndContents(
        folderIds: List<String>,
        now: Long = System.currentTimeMillis()
    ): Boolean = moveFoldersToTrash(folderIds, now) != null

    suspend fun moveFoldersToTrash(
        folderIds: List<String>,
        now: Long = System.currentTimeMillis()
    ): TrashBatchRecord? = withContext(Dispatchers.IO) {
        val distinctIds = folderIds.distinct()
        if (distinctIds.isEmpty()) return@withContext null
        val targetFolders = distinctIds.mapNotNull { folderDao.getFolderById(it) }
        if (targetFolders.isEmpty()) return@withContext null

        val allFolderIds = linkedSetOf<String>()
        for (folder in targetFolders) {
            allFolderIds.addAll(collectAllDescendantFolderIds(folder.id))
        }
        val allFolderIdList = allFolderIds.toList()
        val scans = scanDao.getScansInFoldersOnce(allFolderIdList)
        val scanIds = scans.map { it.id }

        if (scanIds.isNotEmpty()) {
            scanDao.softDeleteScans(scanIds, now)
        }
        folderDao.softDeleteFolders(allFolderIdList, now)

        val survivingParentIds = targetFolders
            .mapNotNull { it.parentId }
            .distinct()
            .filter { it !in allFolderIds }
        for (parentId in survivingParentIds) {
            database.touchFolderAndAncestors(parentId, now)
        }

        TrashBatchRecord(
            folderIds = allFolderIdList,
            scanIds = scanIds,
            trashedFoldersCount = allFolderIdList.size,
            trashedScansCount = scanIds.size
        )
    }

    /**
     * Stages a newly captured camera Bitmap into a temporary draft file and returns the initial
     * DraftScanItem. Background processing can then detect corners and render the preview.
     */
    suspend fun createDraftFromBitmap(
        rawBitmap: Bitmap,
        capturedAt: Long = System.currentTimeMillis(),
        defaultFilter: ScanFilter = ScanFilter.MAGIC_COLOR,
        originFolderName: String? = null
    ): DraftScanItem = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val draftFile = File(draftsDir, "draft_orig_$id.jpg")
        FileOutputStream(draftFile).use { out ->
            rawBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
        }
        val filename = generateFolderTimestampFilename(originFolderName, capturedAt)
        DraftScanItem(
            id = id,
            filename = filename,
            originalPath = draftFile.absolutePath,
            capturedAt = capturedAt,
            cropQuad = NormalizedQuad.FULL_FRAME,
            detectedQuad = NormalizedQuad.FULL_FRAME,
            rotationDegrees = 0,
            filter = defaultFilter,
            isProcessing = true,
            previewBitmap = null
        )
    }

    /**
     * Stages a full-resolution JPEG captured directly by CameraX ImageCapture into a draft item,
     * normalizing EXIF orientation at full camera resolution (up to 4096px) without losing detail.
     */
    suspend fun createDraftFromHighResFile(
        capturedFile: File,
        capturedAt: Long = System.currentTimeMillis(),
        defaultFilter: ScanFilter = ScanFilter.MAGIC_COLOR,
        originFolderName: String? = null
    ): DraftScanItem? = withContext(Dispatchers.IO) {
        runCatching {
            if (!capturedFile.exists() || capturedFile.length() == 0L) return@runCatching null
            val id = UUID.randomUUID().toString()
            val draftFile = File(draftsDir, "draft_orig_$id.jpg")

            val exif = ExifInterface(capturedFile.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val exifMatrix = buildExifMatrix(orientation)

            if (!exifMatrix.isIdentity) {
                val decoded = decodeSampledBitmap(capturedFile.absolutePath, 4096)
                    ?: return@runCatching null
                val oriented = Bitmap.createBitmap(
                    decoded,
                    0,
                    0,
                    decoded.width,
                    decoded.height,
                    exifMatrix,
                    true
                )
                FileOutputStream(draftFile).use { out ->
                    oriented.compress(Bitmap.CompressFormat.JPEG, 98, out)
                }
                if (oriented !== decoded && !decoded.isRecycled) decoded.recycle()
                if (!oriented.isRecycled) oriented.recycle()
            } else {
                capturedFile.copyTo(draftFile, overwrite = true)
            }
            runCatching { capturedFile.delete() }

            val filename = generateFolderTimestampFilename(originFolderName, capturedAt)
            DraftScanItem(
                id = id,
                filename = filename,
                originalPath = draftFile.absolutePath,
                capturedAt = capturedAt,
                cropQuad = NormalizedQuad.FULL_FRAME,
                detectedQuad = NormalizedQuad.FULL_FRAME,
                rotationDegrees = 0,
                filter = defaultFilter,
                isProcessing = true,
                previewBitmap = null
            )
        }.getOrNull()
    }

    /**
     * Imports a device Uri into a temporary draft file, respecting EXIF orientation and extracting
     * EXIF capture date if available (otherwise falling back to import time).
     */
    suspend fun createDraftFromUri(
        uri: Uri,
        importTimestamp: Long = System.currentTimeMillis(),
        defaultFilter: ScanFilter = ScanFilter.MAGIC_COLOR,
        originFolderName: String? = null
    ): DraftScanItem? = withContext(Dispatchers.IO) {
        runCatching {
            val id = UUID.randomUUID().toString()
            val rawCopy = File(draftsDir, "raw_import_$id.jpg")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(rawCopy).use { output ->
                        input.copyTo(output)
                    }
                } ?: return@runCatching null

                val exif = ExifInterface(rawCopy.absolutePath)
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                val exifMatrix = buildExifMatrix(orientation)

                val capturedAt = parseExifTimestamp(exif) ?: importTimestamp

                val decoded = decodeSampledBitmap(rawCopy.absolutePath, 4096) ?: return@runCatching null
                val oriented = if (!exifMatrix.isIdentity) {
                    Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, exifMatrix, true)
                } else {
                    decoded
                }

                val draftFile = File(draftsDir, "draft_orig_$id.jpg")
                FileOutputStream(draftFile).use { out ->
                    oriented.compress(Bitmap.CompressFormat.JPEG, 98, out)
                }
                if (oriented !== decoded && !decoded.isRecycled) decoded.recycle()
                if (!oriented.isRecycled) oriented.recycle()

                val filename = generateFolderTimestampFilename(originFolderName, capturedAt)
                DraftScanItem(
                    id = id,
                    filename = filename,
                    originalPath = draftFile.absolutePath,
                    capturedAt = capturedAt,
                    cropQuad = NormalizedQuad.FULL_FRAME,
                    detectedQuad = NormalizedQuad.FULL_FRAME,
                    rotationDegrees = 0,
                    filter = defaultFilter,
                    isProcessing = true,
                    previewBitmap = null
                )
            } finally {
                runCatching { rawCopy.delete() }
            }
        }.getOrNull()
    }

    /**
     * Runs edge detection, perspective rectification, and filter rendering for a DraftScanItem
     * at high preview resolution (2400px) so pinch-zoom and double-tap zoom are crisp.
     */
    suspend fun processDraftItem(
        item: DraftScanItem,
        detectEdges: Boolean
    ): DraftScanItem = withContext(Dispatchers.Default) {
        val original = decodeSampledBitmap(item.originalPath, 2400)
            ?: return@withContext item.copy(isProcessing = false)

        val quad = if (detectEdges) {
            DocumentImageProcessor.detectDocumentCorners(original)
        } else {
            item.cropQuad
        }

        var processed = DocumentImageProcessor.processDocument(
            original = original,
            quad = quad,
            rotationDegrees = item.rotationDegrees,
            filter = item.filter,
            filterIntensity = item.filterIntensity,
            maxDimension = 2400
        )
        if (processed !== original && !original.isRecycled) {
            original.recycle()
        }

        for (stroke in item.eraserStrokes) {
            val pts = stroke.normalizedPoints.map { (x, y) -> androidx.compose.ui.geometry.Offset(x, y) }
            val erased = com.example.processing.SmartDocumentEraser.eraseStroke(
                source = processed,
                normalizedPoints = pts,
                normalizedRadius = stroke.normalizedRadius
            )
            if (erased !== processed && !processed.isRecycled) {
                processed.recycle()
            }
            processed = erased
        }

        val previewFile = File(draftsDir, "draft_prev_${item.id}.jpg")
        withContext(Dispatchers.IO) {
            FileOutputStream(previewFile).use { out ->
                processed.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
        }

        item.copy(
            cropQuad = quad,
            detectedQuad = if (detectEdges) quad else item.detectedQuad,
            isProcessing = false,
            previewPath = previewFile.absolutePath,
            previewBitmap = processed
        )
    }

    /**
     * Saves the reviewed batch of DraftScanItems as independent ScanEntity records in [targetFolderId].
     * Retains the full-resolution immutable original, saves the maximum-resolution edited image
     * (up to 4096px at 96% JPEG quality) and thumbnail, and updates the folder's modifiedAt.
     */
    suspend fun saveDraftBatchToFolder(
        draftItems: List<DraftScanItem>,
        targetFolderId: String,
        now: Long = System.currentTimeMillis()
    ): Result<List<ScanEntity>> = withContext(Dispatchers.IO) {
        runCatching {
            val targetFolder = folderDao.getFolderById(targetFolderId)
                ?: error("Destination folder does not exist")

            val reservedBaseNames = scanDao.getScansInFolderOnce(targetFolder.id)
                .map { normalizeNameForComparison(stripImageExtension(it.filename)) }
                .toMutableSet()

            val targetMaxDim = scanResolutionFlow.first().maxDim
            val savedEntities = mutableListOf<ScanEntity>()
            for ((index, draft) in draftItems.withIndex()) {
                val scanId = UUID.randomUUID().toString()
                val srcOrigFile = File(draft.originalPath)
                if (!srcOrigFile.exists()) error("Draft original missing for item ${index + 1}")

                val permanentOrigFile = File(originalsDir, "orig_$scanId.jpg")
                srcOrigFile.copyTo(permanentOrigFile, overwrite = true)

                val fullOriginal = decodeSampledBitmap(permanentOrigFile.absolutePath, 4096)
                    ?: error("Failed to decode original bitmap")

                var fullEdited = DocumentImageProcessor.processDocument(
                    original = fullOriginal,
                    quad = draft.cropQuad,
                    rotationDegrees = draft.rotationDegrees,
                    filter = draft.filter,
                    filterIntensity = draft.filterIntensity,
                    maxDimension = targetMaxDim
                )

                for (stroke in draft.eraserStrokes) {
                    val pts = stroke.normalizedPoints.map { (x, y) -> androidx.compose.ui.geometry.Offset(x, y) }
                    val erased = com.example.processing.SmartDocumentEraser.eraseStroke(
                        source = fullEdited,
                        normalizedPoints = pts,
                        normalizedRadius = stroke.normalizedRadius
                    )
                    if (erased !== fullEdited && fullEdited !== fullOriginal && !fullEdited.isRecycled) {
                        fullEdited.recycle()
                    }
                    fullEdited = erased
                }

                val permanentEditedFile = File(editedDir, "edit_$scanId.jpg")
                FileOutputStream(permanentEditedFile).use { out ->
                    fullEdited.compress(Bitmap.CompressFormat.JPEG, 96, out)
                }

                val thumbBitmap = createThumbnailBitmap(fullEdited, 720)
                val permanentThumbFile = File(thumbsDir, "thumb_$scanId.jpg")
                FileOutputStream(permanentThumbFile).use { out ->
                    thumbBitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
                }

                val editedWidth = fullEdited.width
                val editedHeight = fullEdited.height
                if (thumbBitmap !== fullEdited && !thumbBitmap.isRecycled) thumbBitmap.recycle()
                if (fullEdited !== fullOriginal && !fullEdited.isRecycled) fullEdited.recycle()
                if (!fullOriginal.isRecycled) fullOriginal.recycle()

                val uniqueFilename = generateUniqueFolderTimestampFilename(
                    folderName = targetFolder.name,
                    timestamp = draft.capturedAt,
                    reservedBaseNames = reservedBaseNames
                )
                val entity = ScanEntity(
                    id = scanId,
                    folderId = targetFolder.id,
                    filename = uniqueFilename,
                    originalPath = permanentOrigFile.absolutePath,
                    editedPath = permanentEditedFile.absolutePath,
                    thumbnailPath = permanentThumbFile.absolutePath,
                    capturedAt = draft.capturedAt,
                    modifiedAt = now,
                    rotationDegrees = draft.rotationDegrees,
                    cropTlX = draft.cropQuad.tlX,
                    cropTlY = draft.cropQuad.tlY,
                    cropTrX = draft.cropQuad.trX,
                    cropTrY = draft.cropQuad.trY,
                    cropBrX = draft.cropQuad.brX,
                    cropBrY = draft.cropQuad.brY,
                    cropBlX = draft.cropQuad.blX,
                    cropBlY = draft.cropQuad.blY,
                    filterMode = draft.filter.name,
                    width = editedWidth,
                    height = editedHeight,
                    eraserStrokesJson = encodeEraserStrokes(draft.eraserStrokes)
                )
                savedEntities.add(entity)
            }

            database.insertBatchScansTransaction(
                scans = savedEntities,
                targetFolderId = targetFolder.id,
                timestamp = now
            )

            // Clean up temporary draft files after storage confirms success
            for (draft in draftItems) {
                runCatching { File(draft.originalPath).delete() }
                draft.previewPath?.let { runCatching { File(it).delete() } }
            }

            savedEntities
        }
    }

    /**
     * Re-renders a saved scan from its immutable full-resolution original (`originalPath`) using
     * updated crop coordinates, rotation, or filter at maximum resolution (up to 4096px).
     */
    suspend fun updateSavedScanEdits(
        scanId: String,
        newQuad: NormalizedQuad,
        newRotationDegrees: Int,
        newFilter: ScanFilter,
        filterIntensity: Float = 0.5f,
        eraserStrokes: List<EraserStrokeRecord>? = null,
        now: Long = System.currentTimeMillis()
    ): Result<ScanEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val existing = scanDao.getScanById(scanId) ?: error("Scan not found")
            val origFile = File(existing.originalPath)
            val fallbackSource = when {
                origFile.exists() -> origFile
                File(existing.editedPath).exists() -> File(existing.editedPath)
                File(existing.thumbnailPath).exists() -> File(existing.thumbnailPath)
                else -> error("Original image file missing")
            }

            val targetMaxDim = scanResolutionFlow.first().maxDim
            val fullOriginal = decodeSampledBitmap(fallbackSource.absolutePath, 4096)
                ?: error("Could not decode original image")

            val clampedQuad = newQuad.clamped()
            val prevQuad = existing.cropQuad
            val cropChanged = abs(clampedQuad.tlX - prevQuad.tlX) > 0.004f ||
                abs(clampedQuad.tlY - prevQuad.tlY) > 0.004f ||
                abs(clampedQuad.trX - prevQuad.trX) > 0.004f ||
                abs(clampedQuad.trY - prevQuad.trY) > 0.004f ||
                abs(clampedQuad.brX - prevQuad.brX) > 0.004f ||
                abs(clampedQuad.brY - prevQuad.brY) > 0.004f ||
                abs(clampedQuad.blX - prevQuad.blX) > 0.004f ||
                abs(clampedQuad.blY - prevQuad.blY) > 0.004f

            val effectiveStrokes: List<EraserStrokeRecord> = when {
                cropChanged -> emptyList()
                eraserStrokes != null -> eraserStrokes
                else -> {
                    val rotSteps = (((newRotationDegrees - existing.rotationDegrees) % 360 + 360) % 360) / 90
                    var rotated = existing.eraserStrokes
                    repeat(rotSteps) {
                        rotated = rotated.map { it.rotateClockwise90() }
                    }
                    rotated
                }
            }

            var fullEdited = DocumentImageProcessor.processDocument(
                original = fullOriginal,
                quad = clampedQuad,
                rotationDegrees = newRotationDegrees,
                filter = newFilter,
                filterIntensity = filterIntensity,
                maxDimension = targetMaxDim
            )

            for (stroke in effectiveStrokes) {
                val pts = stroke.normalizedPoints.map { (x, y) -> androidx.compose.ui.geometry.Offset(x, y) }
                val erased = com.example.processing.SmartDocumentEraser.eraseStroke(
                    source = fullEdited,
                    normalizedPoints = pts,
                    normalizedRadius = stroke.normalizedRadius
                )
                if (erased !== fullEdited && fullEdited !== fullOriginal && !fullEdited.isRecycled) {
                    fullEdited.recycle()
                }
                fullEdited = erased
            }

            val editedFile = File(existing.editedPath)
            FileOutputStream(editedFile).use { out ->
                fullEdited.compress(Bitmap.CompressFormat.JPEG, 96, out)
            }

            val thumbBitmap = createThumbnailBitmap(fullEdited, 720)
            val thumbFile = File(existing.thumbnailPath)
            FileOutputStream(thumbFile).use { out ->
                thumbBitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }

            val editedWidth = fullEdited.width
            val editedHeight = fullEdited.height
            if (thumbBitmap !== fullEdited && !thumbBitmap.isRecycled) thumbBitmap.recycle()
            if (fullEdited !== fullOriginal && !fullEdited.isRecycled) fullEdited.recycle()
            if (!fullOriginal.isRecycled) fullOriginal.recycle()

            val updated = existing.copy(
                modifiedAt = now,
                rotationDegrees = newRotationDegrees,
                cropTlX = clampedQuad.tlX,
                cropTlY = clampedQuad.tlY,
                cropTrX = clampedQuad.trX,
                cropTrY = clampedQuad.trY,
                cropBrX = clampedQuad.brX,
                cropBrY = clampedQuad.brY,
                cropBlX = clampedQuad.blX,
                cropBlY = clampedQuad.blY,
                filterMode = newFilter.name,
                width = editedWidth,
                height = editedHeight,
                syncState = "LOCAL_DIRTY",
                eraserStrokesJson = encodeEraserStrokes(effectiveStrokes)
            )

            scanDao.updateScan(updated)
            folderDao.touchFolderModified(existing.folderId, now)
            val folder = folderDao.getFolderById(existing.folderId)
            if (folder?.parentId != null) {
                folderDao.touchFolderModified(folder.parentId, now)
            }
            updated
        }
    }

    /**
     * Persists a directly edited (e.g. Smart Erased) bitmap to `editedPath` and `thumbnailPath`
     * for [scanId] and marks the scan dirty for sync.
     */
    suspend fun saveEditedBitmapToScan(
        scanId: String,
        editedBitmap: Bitmap,
        eraserStrokes: List<EraserStrokeRecord>? = null,
        now: Long = System.currentTimeMillis()
    ): Result<ScanEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val existing = scanDao.getScanById(scanId) ?: error("Scan not found")
            val editedFile = File(existing.editedPath)
            FileOutputStream(editedFile).use { out ->
                editedBitmap.compress(Bitmap.CompressFormat.JPEG, 96, out)
            }

            val thumbBitmap = createThumbnailBitmap(editedBitmap, 720)
            val thumbFile = File(existing.thumbnailPath)
            FileOutputStream(thumbFile).use { out ->
                thumbBitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
            if (thumbBitmap !== editedBitmap && !thumbBitmap.isRecycled) {
                thumbBitmap.recycle()
            }

            val updated = existing.copy(
                modifiedAt = now,
                width = editedBitmap.width,
                height = editedBitmap.height,
                syncState = "LOCAL_DIRTY",
                eraserStrokesJson = eraserStrokes?.let { encodeEraserStrokes(it) } ?: existing.eraserStrokesJson
            )
            scanDao.updateScan(updated)
            folderDao.touchFolderModified(existing.folderId, now)
            val folder = folderDao.getFolderById(existing.folderId)
            if (folder?.parentId != null) {
                folderDao.touchFolderModified(folder.parentId, now)
            }
            updated
        }
    }

    /**
     * Renames an image in place while keeping a valid `.jpg` suffix on the stored filename for
     * sharing, gallery export, and PC/Drive synchronization. Rejects empty or duplicate names
     * within the same folder.
     */
    suspend fun renameScan(
        scanId: String,
        newRawName: String,
        now: Long = System.currentTimeMillis()
    ): ScanOperationResult = withContext(Dispatchers.IO) {
        val cleanBase = sanitizeWindowsSafeBase(
            stripImageExtension(newRawName)
                .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_")
                .trim()
        )
        if (cleanBase.isEmpty()) {
            return@withContext ScanOperationResult.EmptyName
        }

        val existing = scanDao.getScanById(scanId)
            ?: return@withContext ScanOperationResult.Error("Image not found")

        val siblings = scanDao.getScansInFolderOnce(existing.folderId)
            .filter { it.id != scanId }
        val targetNorm = normalizeNameForComparison(cleanBase)
        if (siblings.any { normalizeNameForComparison(stripImageExtension(it.filename)) == targetNorm }) {
            return@withContext ScanOperationResult.DuplicateName
        }

        val updated = existing.copy(
            filename = "$cleanBase.jpg",
            modifiedAt = now,
            syncState = "LOCAL_DIRTY"
        )
        scanDao.updateScan(updated)
        database.touchFolderAndAncestors(existing.folderId, now)
        ScanOperationResult.Success(updated)
    }

    /**
     * Atomically renames multiple scans in one Room transaction while guaranteeing unique
     * sanitized `.jpg` filenames within each scan's folder and preserving image binaries.
     */
    suspend fun bulkRenameScans(
        proposedNamesByScanId: Map<String, String>,
        now: Long = System.currentTimeMillis()
    ): BulkRenameResult = withContext(Dispatchers.IO) {
        if (proposedNamesByScanId.isEmpty()) {
            return@withContext BulkRenameResult.Error("No images selected for bulk rename.")
        }
        val allActiveScans = scanDao.getAllScansOnce()
        val activeById = allActiveScans.associateBy { it.id }
        val targetScans = proposedNamesByScanId.keys.mapNotNull { activeById[it] }
        if (targetScans.isEmpty()) {
            return@withContext BulkRenameResult.Error("Selected images not found.")
        }

        val targetIds = targetScans.map { it.id }.toSet()
        val reservedByFolder = mutableMapOf<String, MutableSet<String>>()
        for (scan in allActiveScans) {
            if (scan.id !in targetIds) {
                reservedByFolder.getOrPut(scan.folderId) { mutableSetOf() }
                    .add(normalizeNameForComparison(stripImageExtension(scan.filename)))
            }
        }

        val updatedEntities = mutableListOf<ScanEntity>()
        val previousNamesByScanId = LinkedHashMap<String, String>()
        val touchedFolders = linkedSetOf<String>()

        for (scan in targetScans) {
            val rawProposed = proposedNamesByScanId[scan.id].orEmpty()
            val cleanBase = sanitizeWindowsSafeBase(BulkRenamePlanner.sanitizeBaseName(rawProposed))
            if (cleanBase.isEmpty()) {
                return@withContext BulkRenameResult.Error("One or more filenames are empty.")
            }
            val reserved = reservedByFolder.getOrPut(scan.folderId) { mutableSetOf() }
            var finalBase = cleanBase
            if (!reserved.add(normalizeNameForComparison(finalBase))) {
                var counter = 2
                while (counter <= 10_000) {
                    val candidate = "${cleanBase}_$counter"
                    if (reserved.add(normalizeNameForComparison(candidate))) {
                        finalBase = candidate
                        break
                    }
                    counter++
                }
                if (finalBase == cleanBase) {
                    finalBase = "${cleanBase}_${UUID.randomUUID().toString().take(6)}"
                    reserved.add(normalizeNameForComparison(finalBase))
                }
            }
            val targetFilename = "$finalBase.jpg"
            if (targetFilename != scan.filename) {
                previousNamesByScanId[scan.id] = scan.filename
                updatedEntities.add(
                    scan.copy(
                        filename = targetFilename,
                        modifiedAt = now,
                        syncState = "LOCAL_DIRTY"
                    )
                )
                touchedFolders.add(scan.folderId)
            }
        }

        if (updatedEntities.isNotEmpty()) {
            database.bulkRenameScansTransaction(
                updatedScans = updatedEntities,
                touchedFolderIds = touchedFolders,
                timestamp = now
            )
        }
        BulkRenameResult.Success(
            renamedCount = updatedEntities.size,
            previousNamesByScanId = previousNamesByScanId
        )
    }

    suspend fun restoreScanFilenames(
        previousNamesByScanId: Map<String, String>,
        now: Long = System.currentTimeMillis()
    ): Int = withContext(Dispatchers.IO) {
        if (previousNamesByScanId.isEmpty()) return@withContext 0
        val allActiveScans = scanDao.getAllScansOnce()
        val activeById = allActiveScans.associateBy { it.id }
        val updatedEntities = mutableListOf<ScanEntity>()
        val touchedFolders = linkedSetOf<String>()

        for ((scanId, oldFilename) in previousNamesByScanId) {
            val existing = activeById[scanId] ?: continue
            if (existing.filename != oldFilename) {
                updatedEntities.add(
                    existing.copy(
                        filename = oldFilename,
                        modifiedAt = now,
                        syncState = "LOCAL_DIRTY"
                    )
                )
                touchedFolders.add(existing.folderId)
            }
        }
        if (updatedEntities.isNotEmpty()) {
            database.bulkRenameScansTransaction(
                updatedScans = updatedEntities,
                touchedFolderIds = touchedFolders,
                timestamp = now
            )
        }
        updatedEntities.size
    }

    /**
     * Atomically moves the selected scans into [targetFolderId].
     * Preserves each image's original, edits, thumbnail, filename, and capture date without
     * copying or recompressing image files, and updates affected folders' modified dates.
     */
    suspend fun moveScansToFolder(
        scanIds: List<String>,
        targetFolderId: String,
        now: Long = System.currentTimeMillis()
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            if (scanIds.isEmpty()) return@runCatching 0
            val targetFolder = folderDao.getFolderById(targetFolderId)
                ?: error("Target folder does not exist")

            val allScans = scanDao.getAllScansOnce().filter { it.id in scanIds }
            if (allScans.isEmpty()) error("Selected images not found")

            val sourceFolderIds = allScans.map { it.folderId }.toSet()
            database.moveScansTransaction(
                scanIds = allScans.map { it.id },
                sourceFolderIds = sourceFolderIds,
                targetFolderId = targetFolder.id,
                timestamp = now
            )
            allScans.size
        }
    }

    suspend fun deleteScans(
        scanIds: List<String>,
        now: Long = System.currentTimeMillis()
    ): Int = moveScansToTrash(scanIds, now)?.trashedScansCount ?: 0

    suspend fun moveScansToTrash(
        scanIds: List<String>,
        now: Long = System.currentTimeMillis()
    ): TrashBatchRecord? = withContext(Dispatchers.IO) {
        if (scanIds.isEmpty()) return@withContext null
        val targets = scanDao.getAllScansOnce().filter { it.id in scanIds }
        if (targets.isEmpty()) return@withContext null

        val targetIds = targets.map { it.id }
        val affectedFolders = targets.map { it.folderId }.toSet()
        scanDao.softDeleteScans(targetIds, now)
        for (fId in affectedFolders) {
            folderDao.touchFolderModified(fId, now)
            val folder = folderDao.getFolderById(fId)
            if (folder?.parentId != null) {
                folderDao.touchFolderModified(folder.parentId, now)
            }
        }
        TrashBatchRecord(
            folderIds = emptyList(),
            scanIds = targetIds,
            trashedFoldersCount = 0,
            trashedScansCount = targetIds.size
        )
    }

    suspend fun restoreTrashBatch(
        record: TrashBatchRecord,
        now: Long = System.currentTimeMillis()
    ): Int = withContext(Dispatchers.IO) {
        val allFoldersMap = folderDao.getAllFoldersIncludingTrashedOnce().associateBy { it.id }
        val allScansMap = scanDao.getAllScansIncludingTrashedOnce().associateBy { it.id }

        val foldersToRestore = linkedSetOf<String>()
        fun addAncestors(folderId: String?) {
            var currId = folderId
            val visited = mutableSetOf<String>()
            while (currId != null && visited.add(currId)) {
                val f = allFoldersMap[currId] ?: break
                if (f.deletedAt != null) {
                    foldersToRestore.add(f.id)
                }
                currId = f.parentId
            }
        }

        record.folderIds.forEach { fId ->
            if (allFoldersMap[fId]?.deletedAt != null) foldersToRestore.add(fId)
            addAncestors(allFoldersMap[fId]?.parentId)
        }
        val scansToRestore = record.scanIds.filter { allScansMap[it]?.deletedAt != null }
        scansToRestore.forEach { sId ->
            addAncestors(allScansMap[sId]?.folderId)
        }

        if (foldersToRestore.isNotEmpty()) {
            folderDao.restoreFolders(foldersToRestore.toList(), now)
            resolveFolderNameCollisionsOnRestore(foldersToRestore, now)
        }
        if (scansToRestore.isNotEmpty()) {
            scanDao.restoreScans(scansToRestore, now)
            resolveRestoredScanOrphansAndCollisions(scansToRestore, now)
        }
        pruneDeletedIds(foldersToRestore, scansToRestore.toSet())
        foldersToRestore.size + scansToRestore.size
    }

    suspend fun restoreTrashedFolderTree(
        folderId: String,
        now: Long = System.currentTimeMillis()
    ): Int = withContext(Dispatchers.IO) {
        val allFolders = folderDao.getAllFoldersIncludingTrashedOnce()
        val allFoldersMap = allFolders.associateBy { it.id }
        val target = allFoldersMap[folderId] ?: return@withContext 0

        val foldersToRestore = linkedSetOf<String>()
        // Walk ancestors so restored subfolder isn't orphaned
        var currId: String? = target.id
        val visited = mutableSetOf<String>()
        while (currId != null && visited.add(currId)) {
            val f = allFoldersMap[currId] ?: break
            if (f.deletedAt != null) foldersToRestore.add(f.id)
            currId = f.parentId
        }
        // Collect trashed descendants under target
        val childrenByParent = allFolders.groupBy { it.parentId }
        val queue = ArrayDeque<String>()
        queue.add(target.id)
        while (queue.isNotEmpty()) {
            val fId = queue.removeFirst()
            childrenByParent[fId]?.forEach { child ->
                if (child.deletedAt != null) foldersToRestore.add(child.id)
                queue.add(child.id)
            }
        }

        val trashedScansInRestored = scanDao.getAllScansIncludingTrashedOnce()
            .filter { it.deletedAt != null && it.folderId in foldersToRestore }
            .map { it.id }

        if (foldersToRestore.isNotEmpty()) {
            folderDao.restoreFolders(foldersToRestore.toList(), now)
            resolveFolderNameCollisionsOnRestore(foldersToRestore, now)
        }
        if (trashedScansInRestored.isNotEmpty()) {
            scanDao.restoreScans(trashedScansInRestored, now)
            resolveRestoredScanOrphansAndCollisions(trashedScansInRestored, now)
        }
        pruneDeletedIds(foldersToRestore, trashedScansInRestored.toSet())
        foldersToRestore.size + trashedScansInRestored.size
    }

    suspend fun restoreTrashedScan(
        scanId: String,
        now: Long = System.currentTimeMillis()
    ): Int = withContext(Dispatchers.IO) {
        val scan = scanDao.getAnyScanById(scanId) ?: return@withContext 0
        if (scan.deletedAt == null) return@withContext 0
        val allFoldersMap = folderDao.getAllFoldersIncludingTrashedOnce().associateBy { it.id }
        val foldersToRestore = linkedSetOf<String>()
        var currId: String? = scan.folderId
        val visited = mutableSetOf<String>()
        while (currId != null && visited.add(currId)) {
            val f = allFoldersMap[currId] ?: break
            if (f.deletedAt != null) foldersToRestore.add(f.id)
            currId = f.parentId
        }
        if (foldersToRestore.isNotEmpty()) {
            folderDao.restoreFolders(foldersToRestore.toList(), now)
            resolveFolderNameCollisionsOnRestore(foldersToRestore, now)
        }
        scanDao.restoreScans(listOf(scan.id), now)
        resolveRestoredScanOrphansAndCollisions(listOf(scan.id), now)
        pruneDeletedIds(foldersToRestore, setOf(scan.id))
        1 + foldersToRestore.size
    }

    suspend fun restoreAllTrash(now: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.IO) {
        val trashedFolders = folderDao.getAllFoldersIncludingTrashedOnce().filter { it.deletedAt != null }.map { it.id }
        val trashedScans = scanDao.getAllScansIncludingTrashedOnce().filter { it.deletedAt != null }.map { it.id }
        if (trashedFolders.isNotEmpty()) {
            folderDao.restoreFolders(trashedFolders, now)
            resolveFolderNameCollisionsOnRestore(trashedFolders.toSet(), now)
        }
        if (trashedScans.isNotEmpty()) {
            scanDao.restoreScans(trashedScans, now)
            resolveRestoredScanOrphansAndCollisions(trashedScans, now)
        }
        pruneDeletedIds(trashedFolders.toSet(), trashedScans.toSet())
        trashedFolders.size + trashedScans.size
    }

    private suspend fun resolveFolderNameCollisionsOnRestore(
        restoredFolderIds: Set<String>,
        now: Long
    ) {
        if (restoredFolderIds.isEmpty()) return
        val activeFolders = folderDao.getAllFoldersOnce()
        val activeIds = activeFolders.map { it.id }.toSet()
        val reservedByParent = mutableMapOf<String?, MutableSet<String>>()
        for (folder in activeFolders) {
            if (folder.id !in restoredFolderIds) {
                val effectiveParent = if (folder.parentId != null && folder.parentId in activeIds) folder.parentId else null
                reservedByParent.getOrPut(effectiveParent) { mutableSetOf() }
                    .add(normalizeNameForComparison(folder.name))
            }
        }
        // Restore roots first, then children, fixing any missing parentId or sibling name collision
        val toInspect = activeFolders
            .filter { it.id in restoredFolderIds }
            .sortedBy { it.parentId != null }
        for (folder in toInspect) {
            val effectiveParent = if (folder.parentId != null && folder.parentId in activeIds) folder.parentId else null
            val reserved = reservedByParent.getOrPut(effectiveParent) { mutableSetOf() }
            val norm = normalizeNameForComparison(folder.name)
            var resolvedName = folder.name
            if (!reserved.add(norm)) {
                val firstCandidate = "${folder.name} (Restored)"
                if (reserved.add(normalizeNameForComparison(firstCandidate))) {
                    resolvedName = firstCandidate
                } else {
                    var counter = 2
                    while (counter <= 10_000) {
                        val candidate = "${folder.name} (Restored $counter)"
                        if (reserved.add(normalizeNameForComparison(candidate))) {
                            resolvedName = candidate
                            break
                        }
                        counter++
                    }
                    if (resolvedName == folder.name) {
                        resolvedName = "${folder.name} (${UUID.randomUUID().toString().take(6)})"
                        reserved.add(normalizeNameForComparison(resolvedName))
                    }
                }
            }
            if (resolvedName != folder.name || effectiveParent != folder.parentId) {
                folderDao.updateFolder(
                    folder.copy(
                        parentId = effectiveParent,
                        name = resolvedName,
                        modifiedAt = now,
                        syncState = "LOCAL_DIRTY"
                    )
                )
            }
        }
    }

    private suspend fun resolveRestoredScanOrphansAndCollisions(
        restoredScanIds: List<String>,
        now: Long
    ) {
        if (restoredScanIds.isEmpty()) return
        val restoredSet = restoredScanIds.toSet()
        val activeFolders = folderDao.getAllFoldersOnce()
        val activeFolderIds = activeFolders.map { it.id }.toMutableSet()
        var recoveredFolderId: String? = activeFolders
            .firstOrNull { it.parentId == null && normalizeNameForComparison(it.name) == "recovered" }
            ?.id

        suspend fun getOrCreateRecoveredFolderId(): String {
            recoveredFolderId?.let { return it }
            val existingRoots = folderDao.getTopLevelFoldersOnce()
            val nextOrder = (existingRoots.maxOfOrNull { it.customOrder } ?: -1) + 1
            val created = FolderEntity(
                id = UUID.randomUUID().toString(),
                name = "Recovered",
                parentId = null,
                createdAt = now,
                modifiedAt = now,
                customOrder = nextOrder
            )
            folderDao.insertFolder(created)
            activeFolderIds.add(created.id)
            recoveredFolderId = created.id
            return created.id
        }

        val allActiveScans = scanDao.getAllScansOnce()
        val reservedByFolder = mutableMapOf<String, MutableSet<String>>()
        for (scan in allActiveScans) {
            if (scan.id !in restoredSet) {
                reservedByFolder.getOrPut(scan.folderId) { mutableSetOf() }
                    .add(normalizeNameForComparison(stripImageExtension(scan.filename)))
            }
        }

        for (scan in allActiveScans) {
            if (scan.id !in restoredSet) continue
            val targetFolderId = if (scan.folderId in activeFolderIds) {
                scan.folderId
            } else {
                getOrCreateRecoveredFolderId()
            }
            val reserved = reservedByFolder.getOrPut(targetFolderId) { mutableSetOf() }
            val baseName = stripImageExtension(scan.filename).ifBlank { "Kagoj" }
            var resolvedFilename = scan.filename
            if (!reserved.add(normalizeNameForComparison(baseName))) {
                val firstCandidate = "${baseName}_restored"
                if (reserved.add(normalizeNameForComparison(firstCandidate))) {
                    resolvedFilename = "$firstCandidate.jpg"
                } else {
                    var counter = 2
                    while (counter <= 10_000) {
                        val candidate = "${baseName}_restored_$counter"
                        if (reserved.add(normalizeNameForComparison(candidate))) {
                            resolvedFilename = "$candidate.jpg"
                            break
                        }
                        counter++
                    }
                    if (resolvedFilename == scan.filename) {
                        val fallbackCandidate = "${baseName}_restored_${UUID.randomUUID().toString().take(6)}"
                        reserved.add(normalizeNameForComparison(fallbackCandidate))
                        resolvedFilename = "$fallbackCandidate.jpg"
                    }
                }
            }
            if (targetFolderId != scan.folderId || resolvedFilename != scan.filename) {
                scanDao.updateScan(
                    scan.copy(
                        folderId = targetFolderId,
                        filename = resolvedFilename,
                        modifiedAt = now,
                        syncState = "LOCAL_DIRTY"
                    )
                )
            }
        }
    }

    suspend fun permanentlyDeleteTrashedFolder(folderId: String): Int = withContext(Dispatchers.IO) {
        val allFolders = folderDao.getAllFoldersIncludingTrashedOnce()
        val target = allFolders.find { it.id == folderId && it.deletedAt != null } ?: return@withContext 0
        val childrenByParent = allFolders.groupBy { it.parentId }
        val trashedSubtreeIds = linkedSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(target.id)
        while (queue.isNotEmpty()) {
            val curr = queue.removeFirst()
            trashedSubtreeIds.add(curr)
            childrenByParent[curr]?.filter { it.deletedAt != null }?.forEach { queue.add(it.id) }
        }
        val scansToDelete = scanDao.getAllScansIncludingTrashedOnce().filter { it.deletedAt != null && it.folderId in trashedSubtreeIds }
        if (scansToDelete.isNotEmpty()) {
            scanDao.deleteScansByIds(scansToDelete.map { it.id })
        }
        folderDao.deleteFoldersByIds(trashedSubtreeIds.toList())
        recordDeletedIds(trashedSubtreeIds, scansToDelete.map { it.id })
        for (scan in scansToDelete) {
            runCatching { File(scan.originalPath).delete() }
            runCatching { File(scan.editedPath).delete() }
            runCatching { File(scan.thumbnailPath).delete() }
        }
        trashedSubtreeIds.size + scansToDelete.size
    }

    suspend fun permanentlyDeleteTrashedScan(scanId: String): Boolean = withContext(Dispatchers.IO) {
        val scan = scanDao.getAnyScanById(scanId) ?: return@withContext false
        if (scan.deletedAt == null) return@withContext false
        scanDao.deleteScansByIds(listOf(scan.id))
        recordDeletedIds(emptyList(), listOf(scan.id))
        runCatching { File(scan.originalPath).delete() }
        runCatching { File(scan.editedPath).delete() }
        runCatching { File(scan.thumbnailPath).delete() }
        true
    }

    suspend fun emptyTrash(): Int = withContext(Dispatchers.IO) {
        val trashedFolders = folderDao.getAllFoldersIncludingTrashedOnce().filter { it.deletedAt != null }
        val trashedScans = scanDao.getAllScansIncludingTrashedOnce().filter { it.deletedAt != null }
        if (trashedScans.isNotEmpty()) {
            scanDao.deleteScansByIds(trashedScans.map { it.id })
        }
        if (trashedFolders.isNotEmpty()) {
            folderDao.deleteFoldersByIds(trashedFolders.map { it.id })
        }
        recordDeletedIds(trashedFolders.map { it.id }, trashedScans.map { it.id })
        for (scan in trashedScans) {
            runCatching { File(scan.originalPath).delete() }
            runCatching { File(scan.editedPath).delete() }
            runCatching { File(scan.thumbnailPath).delete() }
        }
        trashedFolders.size + trashedScans.size
    }

    suspend fun purgeExpiredTrash(
        now: Long = System.currentTimeMillis(),
        retentionMillis: Long = TRASH_RETENTION_MILLIS
    ): Int = withContext(Dispatchers.IO) {
        val cutoff = now - retentionMillis
        val allFoldersIncludingTrashed = folderDao.getAllFoldersIncludingTrashedOnce()
        val trashedChildrenByParent = allFoldersIncludingTrashed
            .filter { it.deletedAt != null }
            .groupBy { it.parentId }
        val expiredFolderIds = linkedSetOf<String>()
        val queue = ArrayDeque<String>()
        for (folder in allFoldersIncludingTrashed) {
            if (folder.deletedAt != null && folder.deletedAt <= cutoff && expiredFolderIds.add(folder.id)) {
                queue.add(folder.id)
            }
        }
        while (queue.isNotEmpty()) {
            val curr = queue.removeFirst()
            trashedChildrenByParent[curr]?.forEach { child ->
                if (expiredFolderIds.add(child.id)) {
                    queue.add(child.id)
                }
            }
        }
        val expiredScans = scanDao.getAllScansIncludingTrashedOnce()
            .filter {
                it.deletedAt != null && (it.deletedAt <= cutoff || it.folderId in expiredFolderIds)
            }
        if (expiredScans.isNotEmpty()) {
            scanDao.deleteScansByIds(expiredScans.map { it.id })
        }
        if (expiredFolderIds.isNotEmpty()) {
            folderDao.deleteFoldersByIds(expiredFolderIds.toList())
        }
        recordDeletedIds(expiredFolderIds, expiredScans.map { it.id })
        for (scan in expiredScans) {
            runCatching { File(scan.originalPath).delete() }
            runCatching { File(scan.editedPath).delete() }
            runCatching { File(scan.thumbnailPath).delete() }
        }
        expiredFolderIds.size + expiredScans.size
    }

    /**
     * Combines [orderedScans] into a multi-page PDF file in the exact order specified by the user.
     * Uses the edited full-resolution image for each page, with correct orientation, preserved
     * aspect ratio, and zero clipped content. Keeps all source images in their folders.
     */
    suspend fun exportSelectedScansToPdf(
        orderedScans: List<ScanEntity>,
        rawPdfName: String,
        pageFormat: PdfPageFormat = PdfPageFormat.FIT_TO_IMAGE
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(orderedScans.isNotEmpty()) { "No images selected for PDF export" }

            val sanitizedBase = sanitizeWindowsSafeBase(
                rawPdfName.trim()
                    .removeSuffix(".pdf")
                    .removeSuffix(".PDF")
                    .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_")
            ).ifBlank { "Kagojpotro_Document" }

            val pdfFile = File(exportsDir, "$sanitizedBase.pdf")

            // Recover missing local high-res edited copies from PC Server if configured & online
            val pcCfg = runCatching { pcSyncConfigFlow.first() }.getOrNull()
            if (pcCfg != null && pcCfg.mode == AppStorageSyncMode.PC_SERVER_SYNC && pcCfg.serverUrl.isNotBlank()) {
                for (scan in orderedScans) {
                    val edited = File(scan.editedPath)
                    if (!edited.exists() || edited.length() == 0L) {
                        runCatching {
                            pcSyncManager.ensureHighResAvailableOnPhone(scan, pcCfg, needOriginalToo = false)
                        }
                    }
                }
            }

            val isRobolectric = Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
            val renderedNatively = if (isRobolectric) {
                false
            } else {
                runCatching {
                    val pdfDocument = PdfDocument()
                    var activePage: PdfDocument.Page? = null
                    try {
                        orderedScans.forEachIndexed { index, scan ->
                            val editedFile = File(scan.editedPath)
                            val origFile = File(scan.originalPath)
                            val thumbFile = File(scan.thumbnailPath)
                            val bestPath = when {
                                editedFile.exists() -> editedFile.absolutePath
                                origFile.exists() -> origFile.absolutePath
                                else -> thumbFile.absolutePath
                            }
                            val bitmap = decodeSampledBitmap(
                                bestPath,
                                1800
                            ) ?: error("Could not decode image for page ${index + 1}")

                            try {
                                val bmpW = bitmap.width.coerceAtLeast(1)
                                val bmpH = bitmap.height.coerceAtLeast(1)

                                if (pageFormat == PdfPageFormat.FIT_TO_IMAGE) {
                                    val pageInfo = PdfDocument.PageInfo.Builder(bmpW, bmpH, index + 1).create()
                                    val page = pdfDocument.startPage(pageInfo)
                                    activePage = page
                                    page.canvas.drawColor(Color.WHITE)
                                    page.canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                                    pdfDocument.finishPage(page)
                                    activePage = null
                                } else {
                                    val isLandscape = bmpW > bmpH
                                    val pageW = if (isLandscape) 1754 else 1240
                                    val pageH = if (isLandscape) 1240 else 1754
                                    val margin = 48f

                                    val availW = pageW - margin * 2f
                                    val availH = pageH - margin * 2f
                                    val scale = min(availW / bmpW.toFloat(), availH / bmpH.toFloat())
                                    val drawW = bmpW * scale
                                    val drawH = bmpH * scale
                                    val left = (pageW - drawW) / 2f
                                    val top = (pageH - drawH) / 2f

                                    val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, index + 1).create()
                                    val page = pdfDocument.startPage(pageInfo)
                                    activePage = page
                                    val canvas: Canvas = page.canvas
                                    canvas.drawColor(Color.WHITE)
                                    val destRect = RectF(left, top, left + drawW, top + drawH)
                                    canvas.drawBitmap(bitmap, null, destRect, Paint(Paint.FILTER_BITMAP_FLAG))
                                    pdfDocument.finishPage(page)
                                    activePage = null
                                }
                            } finally {
                                if (!bitmap.isRecycled) bitmap.recycle()
                            }
                        }

                        FileOutputStream(pdfFile).use { out ->
                            pdfDocument.writeTo(out)
                        }
                        pdfFile.exists() && pdfFile.length() > 256L
                    } finally {
                        activePage?.let { page -> runCatching { pdfDocument.finishPage(page) } }
                        runCatching { pdfDocument.close() }
                    }
                }.getOrDefault(false)
            }

            if (!renderedNatively) {
                writeDirectPdf14WithEmbeddedJpegs(orderedScans, pdfFile, pageFormat)
            }

            pdfFile
        }
    }

    /**
     * Standards-compliant multi-page PDF 1.4 writer that streams each edited JPEG
     * (`/DCTDecode`) directly to disk in the exact user-chosen page order with preserved aspect ratio
     * and orientation, avoiding buffering an entire multi-page PDF in RAM.
     */
    private fun writeDirectPdf14WithEmbeddedJpegs(
        orderedScans: List<ScanEntity>,
        outputFile: File,
        pageFormat: PdfPageFormat
    ) {
        outputFile.parentFile?.mkdirs()
        java.io.BufferedOutputStream(FileOutputStream(outputFile)).use { out ->
            val offsets = mutableMapOf<Int, Long>()
            var bytesWritten = 0L

            fun writeBytes(bytes: ByteArray) {
                out.write(bytes)
                bytesWritten += bytes.size
            }

            fun writeAscii(text: String) {
                writeBytes(text.toByteArray(Charsets.US_ASCII))
            }

            writeAscii("%PDF-1.4\n")

            val n = orderedScans.size
            val totalObjects = 2 + n * 3

            offsets[1] = bytesWritten
            writeAscii("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

            val kidsList = (0 until n).joinToString(" ") { "${3 + it * 3} 0 R" }
            offsets[2] = bytesWritten
            writeAscii("2 0 obj\n<< /Type /Pages /Kids [ $kidsList ] /Count $n >>\nendobj\n")

            orderedScans.forEachIndexed { index, scan ->
                val pageObjId = 3 + index * 3
                val contentObjId = 4 + index * 3
                val imageObjId = 5 + index * 3

                val editedFile = File(scan.editedPath)
                val origFile = File(scan.originalPath)
                val thumbFile = File(scan.thumbnailPath)
                val sourceFile = when {
                    editedFile.exists() -> editedFile
                    origFile.exists() -> origFile
                    else -> thumbFile
                }
                val bitmap = decodeSampledBitmap(sourceFile.absolutePath, 1800)
                    ?: error("Could not decode image for PDF page ${index + 1}")

                val bmpW = bitmap.width.coerceAtLeast(1)
                val bmpH = bitmap.height.coerceAtLeast(1)

                val jpegBytesStream = java.io.ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, jpegBytesStream)
                if (!bitmap.isRecycled) bitmap.recycle()
                val jpegBytes = jpegBytesStream.toByteArray()

                val pageW: Int
                val pageH: Int
                val drawX: Float
                val drawY: Float
                val drawW: Float
                val drawH: Float

                if (pageFormat == PdfPageFormat.FIT_TO_IMAGE) {
                    pageW = bmpW
                    pageH = bmpH
                    drawX = 0f
                    drawY = 0f
                    drawW = pageW.toFloat()
                    drawH = pageH.toFloat()
                } else {
                    val isLandscape = bmpW > bmpH
                    pageW = if (isLandscape) 842 else 595
                    pageH = if (isLandscape) 595 else 842
                    val margin = 24f
                    val availW = pageW - margin * 2f
                    val availH = pageH - margin * 2f
                    val scale = min(availW / bmpW.toFloat(), availH / bmpH.toFloat())
                    drawW = bmpW * scale
                    drawH = bmpH * scale
                    drawX = (pageW - drawW) / 2f
                    drawY = (pageH - drawH) / 2f
                }

                // Page object
                offsets[pageObjId] = bytesWritten
                writeAscii(
                    "$pageObjId 0 obj\n" +
                        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pageW $pageH] " +
                        "/Resources << /XObject << /Im$index $imageObjId 0 R >> >> " +
                        "/Contents $contentObjId 0 R >>\nendobj\n"
                )

                // Content stream drawing the image XObject
                val contentCmd = String.format(
                    Locale.US,
                    "q %.2f 0 0 %.2f %.2f %.2f cm /Im%d Do Q\n",
                    drawW,
                    drawH,
                    drawX,
                    drawY,
                    index
                )
                val contentBytes = contentCmd.toByteArray(Charsets.US_ASCII)
                offsets[contentObjId] = bytesWritten
                writeAscii("$contentObjId 0 obj\n<< /Length ${contentBytes.size} >>\nstream\n")
                writeBytes(contentBytes)
                writeAscii("endstream\nendobj\n")

                // Image XObject stream (/DCTDecode JPEG)
                offsets[imageObjId] = bytesWritten
                writeAscii(
                    "$imageObjId 0 obj\n" +
                        "<< /Type /XObject /Subtype /Image /Width $bmpW /Height $bmpH " +
                        "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpegBytes.size} >>\n" +
                        "stream\n"
                )
                writeBytes(jpegBytes)
                writeAscii("\nendstream\nendobj\n")
            }

            val xrefStart = bytesWritten
            writeAscii("xref\n0 ${totalObjects + 1}\n")
            writeAscii("0000000000 65535 f \n")
            for (objId in 1..totalObjects) {
                val offset = offsets[objId] ?: 0L
                writeAscii(String.format(Locale.US, "%010d 00000 n \n", offset))
            }
            writeAscii(
                "trailer\n<< /Size ${totalObjects + 1} /Root 1 0 R >>\n" +
                    "startxref\n$xrefStart\n%%EOF\n"
            )
            out.flush()
        }
    }

    fun decodeBitmapFromFile(path: String, maxDim: Int = 1600): Bitmap? {
        return decodeSampledBitmap(path, maxDim)
    }

    private suspend fun hasFullResolutionRemoteBackup(): Boolean {
        val pcCfg = runCatching { pcSyncConfigFlow.first() }.getOrNull() ?: return false
        return when (pcCfg.mode) {
            AppStorageSyncMode.PHONE_ONLY -> false
            AppStorageSyncMode.PC_SERVER_SYNC -> pcCfg.serverUrl.isNotBlank()
            AppStorageSyncMode.GOOGLE_CLOUD_SYNC -> googleSyncBackupTierFlow.first() == "DRIVE_FULL"
        }
    }

    suspend fun getStorageUsageSummary(): StorageUsageSummary = withContext(Dispatchers.IO) {
        fun dirBytes(dir: File): Long {
            if (!dir.exists()) return 0L
            return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
        val allScans = scanDao.getAllScansOnce()
        val foldersCount = folderDao.getAllFoldersOnce().size
        val thumbsBytes = dirBytes(thumbsDir)
        val editedBytes = dirBytes(editedDir)
        val origBytes = dirBytes(originalsDir)
        val sharedImagesDir = File(context.cacheDir, "shared_images")
        val staleCameraBytes = context.cacheDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("cam_fullres_") }
            ?.sumOf { it.length() } ?: 0L
        val draftAndExportCacheBytes = dirBytes(draftsDir) +
            dirBytes(exportsDir) +
            dirBytes(sharedImagesDir) +
            staleCameraBytes

        val hasFullResBackup = hasFullResolutionRemoteBackup()
        var syncedHighResCacheBytes = 0L
        var unsyncedHighResBytes = 0L
        var syncedCount = 0
        var pendingCount = 0

        for (scan in allScans) {
            val eLen = File(scan.editedPath).takeIf { it.exists() }?.length() ?: 0L
            val oLen = File(scan.originalPath).takeIf { it.exists() }?.length() ?: 0L
            if (scan.syncState == "SYNCED" && hasFullResBackup) {
                syncedCount++
                syncedHighResCacheBytes += eLen
            } else {
                pendingCount++
                unsyncedHighResBytes += (eLen + oLen)
            }
        }

        StorageUsageSummary(
            savedScansBytes = editedBytes + thumbsBytes,
            originalsBytes = origBytes,
            thumbnailsBytes = thumbsBytes,
            tempHighResSyncedBytes = syncedHighResCacheBytes,
            unsyncedHighResBytes = unsyncedHighResBytes,
            cacheBytes = draftAndExportCacheBytes + syncedHighResCacheBytes,
            totalScansCount = allScans.size,
            syncedScansCount = syncedCount,
            pendingSyncScansCount = pendingCount,
            totalFoldersCount = foldersCount
        )
    }

    /**
     * Clears temporary caches (drafts, PDF exports, shared images, stale camera temp files) and
     * evictable high-res edited duplicates ONLY for SYNCED scans when a full-resolution backup
     * destination (PC Server Sync or Google Drive Full) is active, while preserving:
     * 1. All immutable uncropped originals (`originals/orig_<id>.jpg`) so non-destructive re-cropping always works.
     * 2. All lightweight previews (`thumbs/thumb_<id>.jpg`) so folders & scans remain viewable offline.
     * 3. Any unsynced scans or scans in Phone-Only / Firestore-Thumbnail-Only mode.
     */
    suspend fun clearTemporaryCache(includeAllLocalHighResIfRequested: Boolean = false): Long = withContext(Dispatchers.IO) {
        fun dirBytes(dir: File): Long {
            if (!dir.exists()) return 0L
            return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
        val sharedImagesDir = File(context.cacheDir, "shared_images")
        val staleCameraFiles = context.cacheDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("cam_fullres_") }
            .orEmpty()
        var freed = dirBytes(draftsDir) +
            dirBytes(exportsDir) +
            dirBytes(sharedImagesDir) +
            staleCameraFiles.sumOf { it.length() }
        draftsDir.deleteRecursively()
        exportsDir.deleteRecursively()
        sharedImagesDir.deleteRecursively()
        staleCameraFiles.forEach { runCatching { it.delete() } }
        draftsDir.mkdirs()
        exportsDir.mkdirs()

        val hasFullResBackup = hasFullResolutionRemoteBackup()
        val allScans = scanDao.getAllScansOnce()
        for (scan in allScans) {
            val canClearEditedCache = (scan.syncState == "SYNCED" && hasFullResBackup) ||
                includeAllLocalHighResIfRequested
            if (canClearEditedCache) {
                val editedFile = File(scan.editedPath)
                val thumbFile = File(scan.thumbnailPath)
                // Ensure a lightweight preview exists before removing the cached edited copy
                if (!thumbFile.exists() && editedFile.exists()) {
                    runCatching {
                        val decoded = decodeSampledBitmap(editedFile.absolutePath, 720)
                        if (decoded != null) {
                            try {
                                FileOutputStream(thumbFile).use { out ->
                                    decoded.compress(Bitmap.CompressFormat.JPEG, 88, out)
                                }
                            } finally {
                                if (!decoded.isRecycled) decoded.recycle()
                            }
                        }
                    }
                }
                if (thumbFile.exists() && editedFile.exists()) {
                    freed += editedFile.length()
                    runCatching { editedFile.delete() }
                }
            }
        }
        freed
    }

    /**
     * Writes the ready-to-run 1-Click Windows GUI Server (`Kagojpotro_PC_Server.bat`) into the
     * exports directory so the user can save or share it directly to their PC from Settings.
     */
    suspend fun exportPcServerScriptFile(): File = withContext(Dispatchers.IO) {
        val launcherFile = File(exportsDir, "Kagojpotro_PC_Server.bat")
        val content = runCatching {
            context.assets.open("Kagojpotro_PC_Server.bat").bufferedReader(Charsets.UTF_8).use {
                it.readText()
            }
        }.getOrDefault(PcSyncManager.PC_SERVER_PYTHON_SCRIPT)
        val windowsCrlfContent = content.replace("\r\n", "\n").replace("\n", "\r\n")
        launcherFile.writeText(windowsCrlfContent, Charsets.UTF_8)
        launcherFile
    }

    suspend fun clearAllLibraryData() = withContext(Dispatchers.IO) {
        scanDao.deleteAllScans()
        folderDao.deleteAllFolders()
        originalsDir.deleteRecursively()
        editedDir.deleteRecursively()
        thumbsDir.deleteRecursively()
        originalsDir.mkdirs()
        editedDir.mkdirs()
        thumbsDir.mkdirs()
    }

    private fun createThumbnailBitmap(source: Bitmap, maxThumbDim: Int): Bitmap {
        val w = source.width.coerceAtLeast(1)
        val h = source.height.coerceAtLeast(1)
        val scale = min(1f, maxThumbDim.toFloat() / maxOf(w, h).toFloat())
        val tw = (w * scale).roundToInt().coerceAtLeast(1)
        val th = (h * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, tw, th, true)
    }

    private fun decodeSampledBitmap(path: String, maxDimension: Int): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, boundsOptions)
        val w = boundsOptions.outWidth
        val h = boundsOptions.outHeight
        if (w <= 0 || h <= 0) return null

        var sampleSize = 1
        while (w / sampleSize > maxDimension * 1.5 || h / sampleSize > maxDimension * 1.5) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, decodeOptions)
    }

    private fun buildExifMatrix(orientation: Int): Matrix {
        return Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    postRotate(90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    postRotate(270f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            }
        }
    }

    private fun parseExifTimestamp(exif: ExifInterface): Long? {
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            ?: return null
        return runCatching {
            val fmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            fmt.parse(raw)?.time
        }.getOrNull()
    }

    fun sanitizeWindowsSafeBase(raw: String): String {
        val trimmed = raw.trim().trimEnd('.', ' ')
        if (trimmed.isEmpty()) return ""
        val reserved = setOf(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
        )
        val stem = trimmed.substringBefore('.').uppercase(Locale.US)
        return if (stem in reserved) "_$trimmed" else trimmed
    }

    fun sanitizeFolderPrefixForFilename(folderName: String?): String {
        val cleaned = sanitizeWindowsSafeBase(
            folderName
                .orEmpty()
                .trim()
                .replace(Regex("[\\\\/:*?\"<>|\\s\\x00-\\x1F]+"), "_")
                .trim('_')
        )
        return cleaned.ifEmpty { "Kagoj" }
    }

    private fun generateFolderTimestampFilename(folderName: String?, timestamp: Long): String {
        val prefix = sanitizeFolderPrefixForFilename(folderName)
        val fmt = SimpleDateFormat("ddMMyy_HHmm", Locale.US)
        return "${prefix}_${fmt.format(Date(timestamp))}.jpg"
    }

    private fun generateUniqueFolderTimestampFilename(
        folderName: String?,
        timestamp: Long,
        reservedBaseNames: MutableSet<String>
    ): String {
        val prefix = sanitizeFolderPrefixForFilename(folderName)
        val fmt = SimpleDateFormat("ddMMyy_HHmm", Locale.US)
        val base = "${prefix}_${fmt.format(Date(timestamp))}"
        if (reservedBaseNames.add(normalizeNameForComparison(base))) {
            return "$base.jpg"
        }
        var counter = 1
        while (counter <= 10_000) {
            val candidate = "${base}_$counter"
            if (reservedBaseNames.add(normalizeNameForComparison(candidate))) {
                return "$candidate.jpg"
            }
            counter++
        }
        val fallback = "${base}_${UUID.randomUUID().toString().take(6)}"
        reservedBaseNames.add(normalizeNameForComparison(fallback))
        return "$fallback.jpg"
    }
}
