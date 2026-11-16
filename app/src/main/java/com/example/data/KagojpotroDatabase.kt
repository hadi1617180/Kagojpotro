package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders WHERE deletedAt IS NULL")
    fun observeAllFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeTrashedFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE deletedAt IS NULL")
    suspend fun getAllFoldersOnce(): List<FolderEntity>

    @Query("SELECT * FROM folders")
    suspend fun getAllFoldersIncludingTrashedOnce(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    suspend fun getTrashedFoldersOnce(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun getFolderById(id: String): FolderEntity?

    @Query("SELECT * FROM folders WHERE id = :id LIMIT 1")
    suspend fun getAnyFolderById(id: String): FolderEntity?

    @Query("SELECT * FROM folders WHERE parentId IS NULL AND deletedAt IS NULL")
    suspend fun getTopLevelFoldersOnce(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE parentId = :parentId AND deletedAt IS NULL")
    suspend fun getSubfoldersOnce(parentId: String): List<FolderEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFolder(folder: FolderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolders(folders: List<FolderEntity>)

    @Update
    suspend fun updateFolder(folder: FolderEntity)

    @Update
    suspend fun updateFolders(folders: List<FolderEntity>)

    @Query("UPDATE folders SET modifiedAt = :modifiedAt, syncState = 'LOCAL_DIRTY' WHERE id = :folderId AND deletedAt IS NULL")
    suspend fun touchFolderModified(folderId: String, modifiedAt: Long)

    @Query("UPDATE folders SET syncState = 'SYNCED' WHERE id IN (:folderIds)")
    suspend fun markFoldersSynced(folderIds: List<String>)

    @Query("UPDATE folders SET deletedAt = :deletedAt WHERE id IN (:folderIds)")
    suspend fun softDeleteFolders(folderIds: List<String>, deletedAt: Long)

    @Query("UPDATE folders SET deletedAt = NULL, modifiedAt = :modifiedAt, syncState = 'LOCAL_DIRTY' WHERE id IN (:folderIds)")
    suspend fun restoreFolders(folderIds: List<String>, modifiedAt: Long)

    @Query("DELETE FROM folders WHERE id IN (:folderIds)")
    suspend fun deleteFoldersByIds(folderIds: List<String>)

    @Query("DELETE FROM folders")
    suspend fun deleteAllFolders()
}

@Dao
interface ScanDao {
    @Query("SELECT * FROM scans WHERE deletedAt IS NULL")
    fun observeAllScans(): Flow<List<ScanEntity>>

    @Query("SELECT * FROM scans WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeTrashedScans(): Flow<List<ScanEntity>>

    @Query("SELECT * FROM scans WHERE deletedAt IS NULL")
    suspend fun getAllScansOnce(): List<ScanEntity>

    @Query("SELECT * FROM scans")
    suspend fun getAllScansIncludingTrashedOnce(): List<ScanEntity>

    @Query("SELECT * FROM scans WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    suspend fun getTrashedScansOnce(): List<ScanEntity>

    @Query("SELECT * FROM scans WHERE folderId = :folderId AND deletedAt IS NULL")
    suspend fun getScansInFolderOnce(folderId: String): List<ScanEntity>

    @Query("SELECT * FROM scans WHERE folderId IN (:folderIds) AND deletedAt IS NULL")
    suspend fun getScansInFoldersOnce(folderIds: List<String>): List<ScanEntity>

    @Query("SELECT * FROM scans WHERE folderId IN (:folderIds)")
    suspend fun getScansInFoldersIncludingTrashedOnce(folderIds: List<String>): List<ScanEntity>

    @Query("SELECT * FROM scans WHERE id = :scanId AND deletedAt IS NULL LIMIT 1")
    suspend fun getScanById(scanId: String): ScanEntity?

    @Query("SELECT * FROM scans WHERE id = :scanId LIMIT 1")
    suspend fun getAnyScanById(scanId: String): ScanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScans(scans: List<ScanEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScan(scan: ScanEntity)

    @Update
    suspend fun updateScan(scan: ScanEntity)

    @Update
    suspend fun updateScans(scans: List<ScanEntity>)

    @Query("UPDATE scans SET syncState = :syncState WHERE id = :scanId")
    suspend fun updateScanSyncState(scanId: String, syncState: String)

    @Query("UPDATE scans SET syncState = 'SYNCED' WHERE id IN (:scanIds)")
    suspend fun markScansSynced(scanIds: List<String>)

    @Query("UPDATE scans SET folderId = :targetFolderId, modifiedAt = :modifiedAt, syncState = 'LOCAL_DIRTY' WHERE id IN (:scanIds)")
    suspend fun moveScansToFolderQuery(scanIds: List<String>, targetFolderId: String, modifiedAt: Long)

    @Query("UPDATE scans SET deletedAt = :deletedAt WHERE id IN (:scanIds)")
    suspend fun softDeleteScans(scanIds: List<String>, deletedAt: Long)

    @Query("UPDATE scans SET deletedAt = NULL, modifiedAt = :modifiedAt, syncState = 'LOCAL_DIRTY' WHERE id IN (:scanIds)")
    suspend fun restoreScans(scanIds: List<String>, modifiedAt: Long)

    @Query("DELETE FROM scans WHERE id IN (:scanIds)")
    suspend fun deleteScansByIds(scanIds: List<String>)

    @Query("DELETE FROM scans")
    suspend fun deleteAllScans()
}

@Database(
    entities = [FolderEntity::class, ScanEntity::class],
    version = 4,
    exportSchema = false
)
abstract class KagojpotroDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun scanDao(): ScanDao

    suspend fun touchFolderAndAncestors(folderId: String, timestamp: Long) {
        val visited = mutableSetOf<String>()
        var currentId: String? = folderId
        while (currentId != null && visited.add(currentId)) {
            folderDao().touchFolderModified(currentId, timestamp)
            val folder = folderDao().getFolderById(currentId)
            currentId = folder?.parentId
        }
    }

    @Transaction
    open suspend fun moveScansTransaction(
        scanIds: List<String>,
        sourceFolderIds: Set<String>,
        targetFolderId: String,
        timestamp: Long
    ) {
        scanDao().moveScansToFolderQuery(scanIds, targetFolderId, timestamp)
        for (sourceId in sourceFolderIds) {
            touchFolderAndAncestors(sourceId, timestamp)
        }
        touchFolderAndAncestors(targetFolderId, timestamp)
    }

    @Transaction
    open suspend fun insertBatchScansTransaction(
        scans: List<ScanEntity>,
        targetFolderId: String,
        timestamp: Long
    ) {
        scanDao().insertScans(scans)
        touchFolderAndAncestors(targetFolderId, timestamp)
    }

    @Transaction
    open suspend fun bulkRenameScansTransaction(
        updatedScans: List<ScanEntity>,
        touchedFolderIds: Set<String>,
        timestamp: Long
    ) {
        if (updatedScans.isNotEmpty()) {
            scanDao().updateScans(updatedScans)
        }
        for (folderId in touchedFolderIds) {
            touchFolderAndAncestors(folderId, timestamp)
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: KagojpotroDatabase? = null

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE folders ADD COLUMN deletedAt INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE scans ADD COLUMN deletedAt INTEGER DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_folders_deletedAt ON folders(deletedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scans_deletedAt ON scans(deletedAt)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scans ADD COLUMN eraserStrokesJson TEXT NOT NULL DEFAULT '[]'")
            }
        }

        fun getInstance(context: Context): KagojpotroDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    KagojpotroDatabase::class.java,
                    "kagojpotro.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
