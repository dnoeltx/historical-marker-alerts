package com.dnoel.markeralerts.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads the identity hash out of the schema JSON that Room exports at compile
 * time.
 *
 * Room stores this hash in `room_master_table` and compares it on every open.
 * If the prepackaged database carries the wrong one — or none — it refuses to
 * open with "Pre-packaged database has an invalid schema".
 *
 * The hash is read from the exported file rather than passed on the command
 * line on purpose: it changes whenever the entity changes, and a hand-copied
 * value would drift silently. The failure would appear as a crash on first
 * launch, long after the change that caused it.
 */
object RoomSchema {

    fun readIdentityHash(schemaFile: Path): String? {
        if (!Files.exists(schemaFile)) return null
        val root = Json.parseToJsonElement(Files.readString(schemaFile)).jsonObject
        return root["database"]?.jsonObject?.get("identityHash")?.jsonPrimitive?.content
    }
}
