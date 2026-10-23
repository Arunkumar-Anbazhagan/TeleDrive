package com.drdisagree.teledrive.data.local

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.drdisagree.teledrive.data.local.database.MIGRATION_10_11
import com.drdisagree.teledrive.data.local.database.MIGRATION_11_12
import com.drdisagree.teledrive.data.local.database.MIGRATION_12_13
import com.drdisagree.teledrive.data.local.database.MIGRATION_13_14
import com.drdisagree.teledrive.data.local.database.MIGRATION_14_15
import com.drdisagree.teledrive.data.local.database.MIGRATION_15_16
import com.drdisagree.teledrive.data.local.database.MIGRATION_16_17
import com.drdisagree.teledrive.data.local.database.TeleDriveDatabase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ReleaseUpgradeMigrationTest {

    @Test
    fun `a 1_4_2 database upgrades to the current schema with its rows intact`() = runBlocking {
        val file = File.createTempFile("teledrive-v10", ".db")
        file.delete()
        createLegacyDatabase(
            file,
            version = 10,
            """INSERT INTO storage_channels (chatId, title, backupFolders, photoPath, defaultsSeeded,
                   remoteFileCount, addedAt, lastOpenedAt)
                   VALUES (7, 'TeleDrive', '/storage/emulated/0/DCIM', NULL, 1, 2, 1, 1)""",
            """INSERT INTO folders (id, chatId, parentId, name, isHidden, isArchived, isFavorite,
                   trashedAt, preTrashParentId, pendingPublish, createdAt, modifiedAt)
                   VALUES ('dcim', 7, NULL, 'DCIM', 0, 0, 0, NULL, NULL, 0, 1, 1)""",
            file("a", "/storage/emulated/0/DCIM/Screenshots/a.jpg"),
            file("b", null),
            """INSERT INTO backup_records (id, sourcePath, fileId, sizeBytes, modifiedAt, contentHash,
                   backedUpAt)
                   VALUES ('r1', '/storage/emulated/0/DCIM/Screenshots/a.jpg', 'a', 10, 1, NULL, 2)""",
            """INSERT INTO backup_records (id, sourcePath, fileId, sizeBytes, modifiedAt, contentHash,
                   backedUpAt)
                   VALUES ('r2', '/storage/emulated/0/DCIM/gone.jpg', 'missing', 10, 1, NULL, 2)""",
            """INSERT INTO transfers (id, type, fileId, displayName, localPath, chatId, messageId,
                   remoteFileId, telegramFileId, sizeBytes, transferredBytes, state, priority,
                   errorMessage, backupSessionId, speedBytesPerSecond, stage, createdAt, updatedAt,
                   completedAt)
                   VALUES ('t1', 'BACKUP', 'a', 'a.jpg', NULL, 7, NULL, NULL, NULL, 10, 10,
                   'COMPLETED', 0, NULL, 's1', 0, NULL, 1, 1, 2)""",
            """INSERT INTO backup_sessions (id, trigger, status, totalFiles, completedFiles,
                   failedFiles, skippedFiles, totalBytes, transferredBytes, startedAt, completedAt)
                   VALUES ('s1', 'MANUAL', 'COMPLETED', 1, 1, 0, 0, 10, 10, 1, 2)"""
        )

        val database = Room.databaseBuilder<TeleDriveDatabase>(name = file.absolutePath)
            .addMigrations(
                MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15,
                MIGRATION_15_16, MIGRATION_16_17
            )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        try {
            assertEquals("dcim", database.fileDao().byId("a")!!.folderId)
            assertNotNull(database.fileDao().byId("b"))
            assertEquals(
                "a",
                database.backupDao()
                    .recordByPath("/storage/emulated/0/DCIM/Screenshots/a.jpg", 7)!!.fileId
            )
            assertEquals(1, database.transferDao().bySession("s1").size)
        } finally {
            database.close()
            file.delete()
        }
    }

    private fun file(id: String, localPath: String?) =
        """INSERT INTO files (id, folderId, name, sizeBytes, mimeType, category, localPath,
               contentHash, chatId, messageId, remoteFileId, remoteUniqueId, backupState,
               isHidden, isArchived, isFavorite, isEncrypted, width, height, durationMs,
               trashedAt, preTrashFolderId, pendingPublish, partCount, iconFileId, createdAt,
               modifiedAt, addedAt)
               VALUES ('$id', 'dcim', '$id.jpg', 10, 'image/jpeg', 'IMAGE',
               ${localPath?.let { "'$it'" } ?: "NULL"}, NULL, 7, 40, 'remote-$id', 'unique-$id',
               'BACKED_UP', 0, 0, 0, 0, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, 1, 1, 1)"""
}
