package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.data.local.dao.FolderDao
import com.drdisagree.teledrive.data.local.dao.FolderTombstoneDao
import com.drdisagree.teledrive.data.local.entity.FolderEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Creating missing segments is serialized so sync and uploads cannot race into duplicate folders.
 */
class FolderPathResolver(
    private val folderDao: FolderDao,
    private val activeChannel: ActiveChannel,
    private val tombstoneDao: FolderTombstoneDao
) {

    private val creationMutex = Mutex()

    suspend fun pathOf(folderId: String?): String {
        if (folderId == null) return ""
        val segments = ArrayDeque<String>()
        var current = folderDao.byId(folderId)
        var guard = 0
        while (current != null && guard++ < MAX_DEPTH) {
            segments.addFirst(current.name)
            val parent = current.parentId ?: current.preTrashParentId
            current = parent?.let { folderDao.byId(it) }
        }
        return segments.joinToString("/")
    }

    suspend fun resolveExisting(path: String, chatId: Long? = null): String? {
        if (path.isBlank()) return null
        val owner = chatId ?: activeChannel.id()
        var parentId: String? = null
        for (segment in path.split('/').filter { it.isNotBlank() }.take(MAX_DEPTH)) {
            val match = folderDao.childrenOf(parentId, owner)
                .firstOrNull { it.name.equals(segment, ignoreCase = true) }
                ?: return null
            parentId = match.id
        }
        return parentId
    }

    suspend fun exists(folderId: String?, chatId: Long): Boolean {
        val folder = folderId?.let { folderDao.byId(it) } ?: return false
        return folder.chatId == null || folder.chatId == chatId
    }

    /**
     * A created leaf keeps [leafId], the identity another device gave it, unless that id was
     * deleted for good.
     */
    suspend fun resolveOrCreate(
        path: String,
        leafId: String? = null,
        startParentId: String? = null,
        chatId: Long? = null
    ): String? {
        if (path.isBlank()) return startParentId
        val owner = chatId ?: activeChannel.id()
        return creationMutex.withLock {
            val reusableLeafId = leafId?.takeIf {
                tombstoneDao.idIfDeleted(it) == null && folderDao.byId(it) == null
            }
            var parentId: String? = startParentId
            val segments = path.split('/').filter { it.isNotBlank() }.take(MAX_DEPTH)
            for ((index, segment) in segments.withIndex()) {
                val existing = folderDao.childrenOf(parentId, owner)
                    .firstOrNull { it.name.equals(segment, ignoreCase = true) }
                parentId = existing?.id ?: run {
                    val now = System.currentTimeMillis()
                    val folder = FolderEntity(
                        id = reusableLeafId.takeIf { index == segments.lastIndex }
                            ?: UUID.randomUUID().toString(),
                        chatId = owner,
                        parentId = parentId,
                        name = segment,
                        createdAt = now,
                        modifiedAt = now
                    )
                    folderDao.upsert(folder)
                    folder.id
                }
            }
            parentId
        }
    }

    companion object {
        private const val MAX_DEPTH = 64
    }
}
