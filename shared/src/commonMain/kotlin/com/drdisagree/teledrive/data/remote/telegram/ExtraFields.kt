package com.drdisagree.teledrive.data.remote.telegram

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal object ExtraFields {

    fun unknownIn(source: JsonObject, descriptor: SerialDescriptor): Map<String, JsonElement> {
        val known = (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet()
        return source.filterKeys { it !in known }
    }

    fun appendTo(target: JsonObject, extra: Map<String, JsonElement>): JsonObject =
        if (extra.isEmpty()) target else JsonObject(target + extra.filterKeys { it !in target })

    fun toText(extra: Map<String, JsonElement>): String? =
        if (extra.isEmpty()) null else Json.encodeToString(JsonObject.serializer(), JsonObject(extra))

    fun fromText(text: String?): Map<String, JsonElement> =
        text?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: emptyMap()
}
