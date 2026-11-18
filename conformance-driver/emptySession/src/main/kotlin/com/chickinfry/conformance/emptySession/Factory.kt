package com.chickinfry.conformance.emptySession

import com.chickinfry.Transport
import com.chickinfry.client.*
import com.chickinfry.conformance.*
import kotlinx.serialization.json.*

class Factory : BridgeFactory {
    override fun create(transport: Transport): Bridge = TypedBridge(createClient(transport))
}

private class TypedBridge(private val client: Client) : Bridge {
    override suspend fun call(operation: String, input: JsonObject): JsonObject = when (operation) {
        "auth.signin" -> {
            input.fields(setOf("email", "password"))
            val output = client.auth.signin(AuthSigninInput(input.string("email"), input.string("password")))
            buildJsonObject {
                put("access_token", output.accessToken); put("refresh_token", output.refreshToken)
                put("expires_at", output.expiresAt); put("refresh_expires_at", output.refreshExpiresAt)
                put("user_id", output.userId); put("session_id", output.sessionId)
                put("email_verified", output.emailVerified)
            }
        }
        "auth.logout" -> {
            input.fields(emptySet())
            val output = client.auth.logout(AuthLogoutInput())
            buildJsonObject { put("status", output.status) }
        }
        "probe.echo" -> {
            input.fields(setOf("wire_value"))
            val output = client.probe.echo(ProbeEchoInput(input.string("wire_value")))
            buildJsonObject { put("wire_value", output.wireValue); put("observed_at", output.observedAt) }
        }
        "probe.list" -> {
            input.fields(emptySet())
            val output = client.probe.list(ProbeListInput())
            buildJsonObject { put("rows", buildJsonArray { output.rows.forEach { row ->
                add(buildJsonObject { put("id", row.id); put("wire_value", row.wireValue); put("observed_at", row.observedAt) })
            } }) }
        }
        else -> throw DriverFailure("unimplemented")
    }
}
