package com.drdisagree.teledrive.data.remote.telegram

import kotlin.io.encoding.Base64
import com.drdisagree.teledrive.core.crypto.CryptoKeys
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Captions are "td1:" plus JSON, or "tde1:" plus base64 AES-GCM of it, so a leak reveals nothing
 * about encrypted files.
 */
class ManifestCodec(
    private val streamCrypto: StreamCrypto,
    private val wrappedKeyRepository: WrappedKeyRepository
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encode(manifest: RemoteFileManifest, encrypt: Boolean): String {
        val fields = json.encodeToJsonElement(RemoteFileManifest.serializer(), manifest).jsonObject
        val payload = json.encodeToString(
            JsonObject.serializer(),
            ExtraFields.appendTo(fields, manifest.extra)
        )
        return if (encrypt) {
            val key = wrappedKeyRepository.getOrCreate(CryptoKeys.CONTENT)
            PREFIX_ENCRYPTED + Base64.encode(
                streamCrypto.encryptBytes(key, payload.toByteArray(Charsets.UTF_8))
            )
        } else {
            PREFIX_PLAIN + payload
        }
    }

    fun decode(caption: String): RemoteFileManifest? = runCatching {
        when {
            caption.startsWith(PREFIX_PLAIN) -> parse(caption.removePrefix(PREFIX_PLAIN))

            caption.startsWith(PREFIX_ENCRYPTED) -> {
                val key = wrappedKeyRepository.get(CryptoKeys.CONTENT) ?: return null
                val plaintext = streamCrypto.decryptBytes(
                    key,
                    Base64.decode(caption.removePrefix(PREFIX_ENCRYPTED))
                )
                parse(String(plaintext, Charsets.UTF_8))
            }

            else -> null
        }
    }.getOrNull()

    private fun parse(payload: String): RemoteFileManifest {
        val fields = json.parseToJsonElement(payload).jsonObject
        return json.decodeFromJsonElement(RemoteFileManifest.serializer(), fields).copy(
            extra = ExtraFields.unknownIn(fields, RemoteFileManifest.serializer().descriptor)
        )
    }

    fun isEncryptedManifest(caption: String): Boolean = caption.startsWith(PREFIX_ENCRYPTED)

    /** True when the caption is encrypted and the key backup has not been restored yet. */
    fun isLocked(caption: String): Boolean =
        isEncryptedManifest(caption) && !wrappedKeyRepository.exists(CryptoKeys.CONTENT)

    companion object {
        private const val PREFIX_PLAIN = "td1:"
        private const val PREFIX_ENCRYPTED = "tde1:"
    }
}
