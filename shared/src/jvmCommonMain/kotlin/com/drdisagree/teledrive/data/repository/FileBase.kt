package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.remote.telegram.RemoteFileManifest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class FileBase(
    val name: String,
    val folderId: String? = null,
    val hidden: Boolean = false,
    val archived: Boolean = false,
    val favorite: Boolean = false,
    val trashedAt: Long? = null
) {

    fun toText(): String = Json.encodeToString(serializer(), this)

    companion object {
        fun of(manifest: RemoteFileManifest) = FileBase(
            name = manifest.name,
            folderId = manifest.folderId,
            hidden = manifest.hidden,
            archived = manifest.archived,
            favorite = manifest.favorite,
            trashedAt = manifest.trashedAt
        )

        fun of(entity: FileEntity) = FileBase(
            name = entity.name,
            folderId = entity.folderId ?: entity.preTrashFolderId,
            hidden = entity.isHidden,
            archived = entity.isArchived,
            favorite = entity.isFavorite,
            trashedAt = entity.trashedAt
        )

        fun fromText(text: String?): FileBase? =
            text?.let { runCatching { Json.decodeFromString(serializer(), it) }.getOrNull() }
    }
}
