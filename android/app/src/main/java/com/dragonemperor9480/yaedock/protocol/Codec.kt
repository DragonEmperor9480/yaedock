package com.dragonemperor9480.yaedock.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Encodes and decodes protocol messages as JSON text frames. */
object Codec {
    private val json = Json {
        classDiscriminator = "type"
        // Fields added by a newer daemon are ignored rather than fatal.
        ignoreUnknownKeys = true
        // An unknown error code becomes ErrorCode.UNKNOWN.
        coerceInputValues = true
        // Optional fields are left out, as the daemon expects.
        explicitNulls = false
    }

    fun encode(message: ClientMessage): String =
        json.encodeToString(ClientMessage.serializer(), message)

    /** Returns null for a message this app does not understand. */
    fun decode(text: String): ServerMessage? =
        try {
            json.decodeFromString(ServerMessage.serializer(), text)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
}
