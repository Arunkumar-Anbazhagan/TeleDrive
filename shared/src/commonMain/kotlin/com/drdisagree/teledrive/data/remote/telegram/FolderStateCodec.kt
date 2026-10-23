package com.drdisagree.teledrive.data.remote.telegram

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal object FolderStateCodec {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val stateFields = RemoteFolderState.serializer().descriptor
    private val entryFields = RemoteFolderEntry.serializer().descriptor

    fun encode(state: RemoteFolderState): String {
        val root = json.encodeToJsonElement(RemoteFolderState.serializer(), state).jsonObject
        val withEntries = root[FOLDERS_KEY]?.jsonArray?.let { entries ->
            JsonObject(
                root + (FOLDERS_KEY to JsonArray(
                    entries.mapIndexed { index, entry ->
                        ExtraFields.appendTo(entry.jsonObject, state.folders[index].extra)
                    }
                ))
            )
        } ?: root
        return json.encodeToString(
            JsonObject.serializer(),
            ExtraFields.appendTo(withEntries, state.extra)
        )
    }

    fun decode(payload: String): RemoteFolderState {
        val root = json.parseToJsonElement(payload).jsonObject
        val state = json.decodeFromJsonElement(RemoteFolderState.serializer(), root)
        val entries = root[FOLDERS_KEY]?.jsonArray.orEmpty()
        return state.copy(
            folders = state.folders.mapIndexed { index, entry ->
                entry.copy(extra = ExtraFields.unknownIn(entries[index].jsonObject, entryFields))
            },
            extra = ExtraFields.unknownIn(root, stateFields)
        )
    }

    private const val FOLDERS_KEY = "f"
}
