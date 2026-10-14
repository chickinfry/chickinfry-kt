package com.chickinfry.conformance

import com.chickinfry.SessionCredentials
import com.chickinfry.CallObservation
import kotlinx.serialization.json.*

internal class References {
    private val values = linkedMapOf<String, String>()
    @Synchronized fun register(ref: String, value: String) {
        validReference(ref)
        if (values.containsKey(ref)) throw DriverFailure("protocol_violation")
        values[ref] = value
    }
    @Synchronized fun get(ref: String): String = values[validReference(ref)] ?: throw DriverFailure("invalid_reference")
    @Synchronized fun find(value: String): String = values.entries.firstOrNull { it.value == value }?.key
        ?: throw DriverFailure("invalid_reference")
    @Synchronized fun containsSecret(value: String): Boolean = values.values.any { secret ->
        secret.isNotEmpty() && (secret in value || java.util.Base64.getEncoder().encodeToString(secret.toByteArray()) in value ||
            secret.toByteArray().joinToString("") { "%02x".format(it) } in value)
    }
    fun record(args: JsonObject, origin: String): SessionCredentials {
        val record = parseObject(get(args.string("recordRef")))
        record.fields(setOf("origin", "access_token", "refresh_token", "expires_at", "refresh_expires_at", "user_id", "session_id"))
        if (record.string("origin") != origin || record.string("access_token") != get(args.string("accessRef")) ||
            record.string("refresh_token") != get(args.string("refreshRef"))) invalidArgs()
        return SessionCredentials(record.string("access_token"), record.string("refresh_token"), record.string("expires_at"),
            record.string("refresh_expires_at"), record.string("user_id"), record.string("session_id"))
    }
    fun output(output: JsonObject, requested: JsonObject): JsonObject {
        val refs = buildJsonObject {
            for (field in setOf("access_token", "refresh_token") + requested.keys) {
                val value = output[field] ?: if (field in requested) invalidArgs() else continue
                val raw = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalidArgs()
                val ref = if (field in requested) requested.string(field).also { register(it, raw) } else find(raw)
                put(field, ref)
            }
        }
        fun redact(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.filterKeys { it !in refs && it !in setOf("access_token", "refresh_token") }.mapValues { redact(it.value) })
            is JsonArray -> JsonArray(value.map(::redact))
            is JsonPrimitive -> if (value.isString && containsSecret(value.content)) JsonNull else value
        }
        return buildJsonObject { put("output", redact(output)); put("credentialRefs", refs) }
    }
    @Synchronized fun clear() { values.clear() }
    @Synchronized fun observeRaw(observation: CallObservation) { values.values.forEach(observation::raw) }
}
