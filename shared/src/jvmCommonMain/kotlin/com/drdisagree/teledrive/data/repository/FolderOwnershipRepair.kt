package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.core.publish.PublishScheduler
import com.drdisagree.teledrive.data.local.dao.StorageChannelDao
import com.drdisagree.teledrive.data.local.dao.FileDao
import com.drdisagree.teledrive.data.local.dao.FolderDao
import com.drdisagree.teledrive.data.local.dao.FolderTombstoneDao
import com.drdisagree.teledrive.data.local.entity.FolderEntity
import com.drdisagree.teledrive.data.local.entity.FolderTombstoneEntity
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first

class FolderOwnershipRepair(
    private val fileDao: FileDao,
    private val folderDao: FolderDao,
    private val channelDao: StorageChannelDao,
    private val settingsRepository: SettingsRepository,
    private val folderPathResolver: FolderPathResolver,
    private val tombstoneDao: FolderTombstoneDao,
    private val publishScheduler: PublishScheduler
) {

    suspend fun runOnce() {
        if (settingsRepository.preferences.first().folderOwnershipRepaired) return
        val repaired = runCatching { repair() }
            .onFailure { SafeLog.w(TAG, "Folder ownership repair failed", it) }
            .getOrDefault(0)
        if (repaired > 0) SafeLog.d(TAG, "Reassigned $repaired folders to their owning drive")
        settingsRepository.update { it.copy(folderOwnershipRepaired = true) }
    }

    suspend fun refileStrayFiles() {
        val moved = runCatching { refile() }
            .onFailure { SafeLog.w(TAG, "Refiling files into their own drive failed", it) }
            .getOrDefault(0)
        if (moved > 0) {
            SafeLog.d(TAG, "Moved $moved files back into their own drive")
            publishScheduler.kick()
        }
    }

    private suspend fun refile(): Int {
        val strays = fileDao.strayFiles()
        if (strays.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val sources = mutableSetOf<String>()
        for (file in strays) {
            val chatId = file.chatId ?: continue
            val source = file.folderId ?: file.preTrashFolderId ?: continue
            val path = folderPathResolver.pathOf(source)
            if (file.trashedAt == null) {
                val target = folderPathResolver.resolveOrCreate(path, chatId = chatId)
                fileDao.move(listOf(file.id), target, now)
            } else {
                fileDao.setPreTrashFolder(file.id, folderPathResolver.resolveExisting(path, chatId))
            }
            sources += source
        }
        strays.map { it.id }.chunked(SQL_BATCH).forEach { fileDao.markPendingPublish(it) }
        removeEmptied(sources)
        return strays.size
    }

    private suspend fun removeEmptied(folderIds: Set<String>) {
        val deletedAt = System.currentTimeMillis()
        var removed = 0
        for (start in folderIds) {
            var cursor: String? = start
            var guard = 0
            while (cursor != null && guard++ < MAX_DEPTH) {
                val folder = folderDao.byId(cursor) ?: break
                if (folderDao.childCount(folder.id) > 0 || fileDao.countInFolder(folder.id) > 0) break
                tombstoneDao.upsert(listOf(FolderTombstoneEntity(folder.id, folder.chatId, deletedAt)))
                folderDao.delete(folder.id)
                removed++
                cursor = folder.parentId ?: folder.preTrashParentId
            }
        }
        if (removed > 0) folderDao.markPendingPublish()
    }

    private suspend fun repair(): Int {
        val folders = folderDao.allFoldersAnyOwner()
        if (folders.isEmpty()) return 0

        val knownDrives = channelDao.all().map { it.chatId }.toSet()
        if (knownDrives.isEmpty()) return 0

        val owners = inferOwners(folders, knownDrives)
        var repaired = 0
        for (folder in folders) {
            val owner = owners[folder.id] ?: continue
            if (owner == folder.chatId) continue
            folderDao.setChatId(folder.id, owner)
            repaired++
        }
        return repaired
    }

    private suspend fun inferOwners(
        folders: List<FolderEntity>,
        knownDrives: Set<Long>
    ): Map<String, Long> {
        val fromFiles = mutableMapOf<String, Long?>()
        for (owner in fileDao.folderOwners()) {
            val chatId = owner.chatId ?: continue
            if (chatId !in knownDrives) continue
            fromFiles[owner.folderId] =
                if (fromFiles.containsKey(owner.folderId) && fromFiles[owner.folderId] != chatId) {
                    null
                } else {
                    chatId
                }
        }

        val owners = fromFiles.filterValues { it != null }.mapValues { it.value as Long }.toMutableMap()
        val childrenByParent = folders.filter { it.parentId != null }.groupBy { it.parentId!! }

        repeat(MAX_DEPTH) {
            var changed = false
            for (folder in folders) {
                if (owners.containsKey(folder.id)) continue
                val childOwners = childrenByParent[folder.id]
                    .orEmpty()
                    .mapNotNull { owners[it.id] }
                    .toSet()
                if (childOwners.size == 1) {
                    owners[folder.id] = childOwners.first()
                    changed = true
                }
            }
            if (!changed) return@repeat
        }
        return owners
    }

    private companion object {
        const val TAG = "FolderOwnership"
        const val MAX_DEPTH = 64
        const val SQL_BATCH = 500
    }
}
