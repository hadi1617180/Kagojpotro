package com.example.data

import com.example.base.FirestoreEmulatorTestBase
import com.google.firebase.firestore.FirebaseFirestoreException
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseCloudSyncRepositoryRuleTest : FirestoreEmulatorTestBase() {

  @Test
  fun upsertFolder_validPayload_createsAndListsDocument() = runBlocking {
    signInTestUser(ALICE_EMAIL)
    val repo = FirebaseCloudSyncRepository(firestore)
    val folderId = "folder_${UUID.randomUUID().toString().replace("-", "_")}"

    val createRes = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.upsertFolder(
        folderId = folderId,
        name = "Certificates",
        parentId = "",
        sortOrder = 1,
        createdAtMs = 1700000000000L,
        updatedAtMs = 1700000000000L
      )
    }
    assertTrue(createRes.isSuccess)

    val listRes = withTimeout(DEFAULT_TIMEOUT_MS) { repo.getUserFolders() }
    assertTrue(listRes.isSuccess)
    assertTrue(listRes.getOrThrow().any { it.id == folderId && it.name == "Certificates" })
  }

  @Test
  fun observeFolders_authenticatedOwner_emitsRealtimeUpdates() = runBlocking {
    signInTestUser(ALICE_EMAIL)
    val repo = FirebaseCloudSyncRepository(firestore)
    val folderId = "folder_${UUID.randomUUID().toString().replace("-", "_")}"

    withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.upsertFolder(
        folderId = folderId,
        name = "Passports",
        parentId = "",
        sortOrder = 0,
        createdAtMs = 1700000000000L,
        updatedAtMs = 1700000000000L
      ).getOrThrow()
    }

    val emitted = withTimeout(FLOW_TIMEOUT_MS) {
      repo.observeFolders().first { list -> list.any { it.id == folderId } }
    }
    assertTrue(emitted.any { it.id == folderId })
  }

  @Test
  fun getFolderById_crossUserAccess_failsWithPermissionDenied() = runBlocking {
    val aliceUid = signInTestUser(ALICE_EMAIL)
    val aliceRepo = FirebaseCloudSyncRepository(firestore)
    val folderId = "folder_${UUID.randomUUID().toString().replace("-", "_")}"

    withTimeout(DEFAULT_TIMEOUT_MS) {
      aliceRepo.upsertFolder(
        folderId = folderId,
        name = "Alice Secret",
        parentId = "",
        sortOrder = 0,
        createdAtMs = 1700000000000L,
        updatedAtMs = 1700000000000L
      ).getOrThrow()
    }

    signInTestUser(BOB_EMAIL)
    val bobRepo = FirebaseCloudSyncRepository(firestore)
    val bobResult = withTimeout(DEFAULT_TIMEOUT_MS) {
      bobRepo.getFolderById(ownerUid = aliceUid, folderId = folderId)
    }
    assertTrue(bobResult.isFailure)
    val ex = bobResult.exceptionOrNull()?.unwrapFirestoreException()
    assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, ex?.code)
  }

  @Test
  fun observeFolders_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
    auth.signOut()
    val repo = FirebaseCloudSyncRepository(firestore)

    val caught = try {
      withTimeout(FLOW_TIMEOUT_MS) { repo.observeFolders().first() }
      null
    } catch (e: Exception) {
      e
    }
    val firestoreException = checkNotNull(caught?.unwrapFirestoreException()) {
      "Expected nested FirebaseFirestoreException, got $caught"
    }
    assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreException.code)
  }

  private companion object {
    const val ALICE_EMAIL = "alice@test.com"
    const val BOB_EMAIL = "bob@test.com"
    const val DEFAULT_TIMEOUT_MS = 8000L
    const val FLOW_TIMEOUT_MS = 8000L
  }
}
