package com.example.data

import android.content.Context
import android.util.Log
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write"),
}

fun handleFirestoreError(
    exception: Throwable,
    operationType: OperationType,
    path: String?,
    auth: FirebaseAuth = FirebaseAuth.getInstance()
): String {
    val causes = generateSequence(exception) { it.cause }
    val rootError = causes.firstOrNull { it is FirebaseFirestoreException }
        ?: causes.firstOrNull { it !is CancellationException }
        ?: exception

    val currentUser = auth.currentUser

    val providerInfoList = currentUser?.providerData?.map { provider ->
        JSONObject().apply {
            put("providerId", provider.providerId)
            put("email", provider.email)
        }
    } ?: emptyList()

    val authInfoJson = JSONObject().apply {
        put("userId", currentUser?.uid)
        put("email", currentUser?.email)
        put("emailVerified", currentUser?.isEmailVerified)
        put("tenantId", currentUser?.tenantId)
        put("providerInfo", JSONArray(providerInfoList))
    }

    val errorInfoJson = JSONObject().apply {
        put("error", rootError.message ?: rootError.toString())
        put("operationType", operationType.value)
        put("path", path)
        put("authInfo", authInfoJson)
    }

    val jsonString = errorInfoJson.toString()
    Log.e("FirestoreError", "Firestore Error: $jsonString")
    return jsonString
}

@IgnoreExtraProperties
data class CloudFolder(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val parentId: String = "",
    val sortOrder: Long = 0L,
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CloudScan(
    @DocumentId val id: String = "",
    val userId: String = "",
    val folderId: String = "",
    val fileName: String = "",
    val capturedAt: Long = 0L,
    val filterName: String = "MAGIC_COLOR",
    val rotationDegrees: Long = 0L,
    val quadString: String = "",
    val thumbBase64: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

class FirebaseCloudSyncRepository(private val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth: FirebaseAuth = FirebaseAuth.getInstance(db.app)

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun observeFolders(): Flow<List<CloudFolder>> = flow {
        val uid = auth.currentUser?.uid?.takeIf { it.isNotBlank() } ?: "_unauthenticated_"
        val path = "users/$uid/folders"
        emitAll(
            db.collection("users").document(uid).collection("folders")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        CloudFolder::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path, auth)
                    }
                    throw error
                }
        )
    }

    suspend fun upsertFolder(
        folderId: String,
        name: String,
        parentId: String,
        sortOrder: Int,
        createdAtMs: Long,
        updatedAtMs: Long
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val safeId = sanitizeFirestoreDocId(folderId)
        val docRef = db.collection("users").document(uid).collection("folders").document(safeId)
        try {
            val existingSnap = docRef.get().await()
            if (existingSnap.exists()) {
                val updatePayload = mapOf(
                    "name" to name.take(200).ifBlank { "Untitled" },
                    "parentId" to sanitizeOptionalFirestoreDocId(parentId),
                    "sortOrder" to sortOrder.toLong(),
                    "createdAtMs" to createdAtMs,
                    "updatedAtMs" to updatedAtMs,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updatePayload).await()
            } else {
                val createPayload = mapOf(
                    "userId" to uid,
                    "name" to name.take(200).ifBlank { "Untitled" },
                    "parentId" to sanitizeOptionalFirestoreDocId(parentId),
                    "sortOrder" to sortOrder.toLong(),
                    "createdAtMs" to createdAtMs,
                    "updatedAtMs" to updatedAtMs,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(createPayload).await()
            }
            safeId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path, auth)
            throw e
        }
    }

    suspend fun getUserFolders(): Result<List<CloudFolder>> = runCatching {
        val uid = requireUserId()
        val path = "users/$uid/folders"
        try {
            val snap = db.collection("users").document(uid).collection("folders").get().await()
            snap.toObjects(CloudFolder::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path, auth)
            throw e
        }
    }

    suspend fun getFolderById(ownerUid: String, folderId: String): Result<CloudFolder?> = runCatching {
        val safeId = sanitizeFirestoreDocId(folderId)
        val docRef = db.collection("users").document(ownerUid).collection("folders").document(safeId)
        try {
            val snap = docRef.get().await()
            snap.toObject(CloudFolder::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path, auth)
            throw e
        }
    }

    suspend fun deleteFolder(folderId: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        val safeId = sanitizeFirestoreDocId(folderId)
        val docRef = db.collection("users").document(uid).collection("folders").document(safeId)
        try {
            docRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path, auth)
            throw e
        }
    }

    suspend fun upsertScan(
        scanId: String,
        folderId: String,
        fileName: String,
        capturedAt: Long,
        filterName: String,
        rotationDegrees: Int,
        quadString: String,
        thumbBase64: String
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val safeScanId = sanitizeFirestoreDocId(scanId)
        val safeFolderId = sanitizeFirestoreDocId(folderId)
        val docRef = db.collection("users").document(uid).collection("scans").document(safeScanId)
        val boundedBase64 = thumbBase64.take(700_000)
        try {
            val existingSnap = docRef.get().await()
            if (existingSnap.exists()) {
                val updatePayload = mapOf(
                    "folderId" to safeFolderId,
                    "fileName" to fileName.take(200).ifBlank { "Scan.jpg" },
                    "capturedAt" to capturedAt,
                    "filterName" to filterName.take(40).ifBlank { "MAGIC_COLOR" },
                    "rotationDegrees" to rotationDegrees.toLong(),
                    "quadString" to quadString.take(500),
                    "thumbBase64" to boundedBase64,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updatePayload).await()
            } else {
                val createPayload = mapOf(
                    "userId" to uid,
                    "folderId" to safeFolderId,
                    "fileName" to fileName.take(200).ifBlank { "Scan.jpg" },
                    "capturedAt" to capturedAt,
                    "filterName" to filterName.take(40).ifBlank { "MAGIC_COLOR" },
                    "rotationDegrees" to rotationDegrees.toLong(),
                    "quadString" to quadString.take(500),
                    "thumbBase64" to boundedBase64,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(createPayload).await()
            }
            safeScanId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path, auth)
            throw e
        }
    }

    suspend fun getUserScans(): Result<List<CloudScan>> = runCatching {
        val uid = requireUserId()
        val path = "users/$uid/scans"
        try {
            val snap = db.collection("users").document(uid).collection("scans").get().await()
            snap.toObjects(CloudScan::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path, auth)
            throw e
        }
    }

    suspend fun deleteScan(scanId: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        val safeId = sanitizeFirestoreDocId(scanId)
        val docRef = db.collection("users").document(uid).collection("scans").document(safeId)
        try {
            docRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path, auth)
            throw e
        }
    }

    suspend fun deleteAllUserData(): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: return@runCatching
        val foldersPath = "users/$uid/folders"
        val scansPath = "users/$uid/scans"
        try {
            val folderSnap = db.collection("users").document(uid).collection("folders").get().await()
            for (doc in folderSnap.documents) {
                runCatching { doc.reference.delete().await() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, foldersPath, auth)
        }
        try {
            val scanSnap = db.collection("users").document(uid).collection("scans").get().await()
            for (doc in scanSnap.documents) {
                runCatching { doc.reference.delete().await() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, scansPath, auth)
        }
    }

    private fun sanitizeFirestoreDocId(raw: String): String {
        val cleaned = raw.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(120)
        return cleaned.ifBlank { "item_${System.currentTimeMillis()}" }
    }

    private fun sanitizeOptionalFirestoreDocId(raw: String): String {
        if (raw.isBlank()) return ""
        return raw.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(120)
    }
}
