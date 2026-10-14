package com.drdisagree.teledrive.data.local

import com.drdisagree.teledrive.data.local.entity.BackupRecordEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupRecordDriveMigrationTest {

    @Test
    fun `upgrading from version 14 tags each backup record with its file's drive`() =
        runBlocking {
            val file = File.createTempFile("teledrive-v14", ".db")
            file.delete()
            createLegacyDatabase(
                file,
                version = 14,
                file("a", chatId = 7, localPath = PATH),
                record("r1", fileId = "a")
            )

            val database = openMigrated(file)
            try {
                val backups = database.backupDao()
                assertEquals("a", backups.recordByPath(PATH, 7)!!.fileId)
                assertNull(backups.recordByPath(PATH, 8))
                assertEquals("a", database.fileDao().byLocalPath(PATH, 7)!!.id)
                assertNull(database.fileDao().byLocalPath(PATH, 8))
            } finally {
                database.close()
                file.delete()
            }
        }

    @Test
    fun `two drives can each keep a backup record for the same path`() = runBlocking {
        val file = File.createTempFile("teledrive-v14", ".db")
        file.delete()
        createLegacyDatabase(file, version = 14)

        val database = openMigrated(file)
        try {
            val backups = database.backupDao()
            backups.upsertRecord(entity("r1", fileId = "a", chatId = 7))
            backups.upsertRecord(entity("r2", fileId = "b", chatId = 8))

            assertEquals("a", backups.recordByPath(PATH, 7)!!.fileId)
            assertEquals("b", backups.recordByPath(PATH, 8)!!.fileId)

            backups.upsertRecord(entity("r3", fileId = "c", chatId = 7))
            assertEquals("c", backups.recordByPath(PATH, 7)!!.fileId)
            assertEquals("b", backups.recordByPath(PATH, 8)!!.fileId)
        } finally {
            database.close()
            file.delete()
        }
    }

    private fun entity(id: String, fileId: String, chatId: Long) = BackupRecordEntity(
        id = id,
        sourcePath = PATH,
        fileId = fileId,
        sizeBytes = 10,
        modifiedAt = 1_000,
        contentHash = null,
        backedUpAt = 2_000,
        chatId = chatId
    )

    private fun file(id: String, chatId: Long, localPath: String) =
        """INSERT INTO files (id, folderId, name, sizeBytes, mimeType, category, localPath,
               contentHash, chatId, messageId, remoteFileId, remoteUniqueId, backupState,
               isHidden, isArchived, isFavorite, isAvailableOffline, isEncrypted, width, height,
               durationMs, trashedAt, preTrashFolderId, pendingPublish, partCount, iconFileId,
               createdAt, modifiedAt, addedAt)
               VALUES ('$id', NULL, '$id.db', 10, 'application/octet-stream', 'OTHER',
               '$localPath', NULL, $chatId, 40, 'remote-40', 'unique-40', 'BACKED_UP', 0, 0, 0,
               0, 0, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, 1000, 1000, 1000)"""

    private fun record(id: String, fileId: String) =
        """INSERT INTO backup_records (id, sourcePath, fileId, sizeBytes, modifiedAt,
               contentHash, backedUpAt)
               VALUES ('$id', '$PATH', '$fileId', 10, 1000, NULL, 2000)"""

    private companion object {
        const val PATH = "/storage/emulated/0/WhatsApp/Databases/msgstore.db"
    }
}
