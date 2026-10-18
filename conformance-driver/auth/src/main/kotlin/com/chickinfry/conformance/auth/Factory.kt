package com.chickinfry.conformance.auth

import com.chickinfry.ClientTransport
import com.chickinfry.client.*
import com.chickinfry.conformance.*
import kotlinx.serialization.json.*

class Factory : BridgeFactory {
    override fun create(transport: ClientTransport): Bridge = TypedBridge(Client(transport))
}

private class TypedBridge(private val client: Client) : Bridge {
    override suspend fun call(operation: String, input: JsonObject): JsonObject = when (operation) {
        "probe.echo" -> {
            input.fields(setOf("wire_value"))
            val output = client.probe.echo(ProbeEchoInput(input.string("wire_value")))
            buildJsonObject { put("wire_value", output.wireValue); put("observed_at", output.observedAt) }
        }
        "probe.identify" -> {
            input.fields(setOf("id"))
            val output = client.probe.identify(ProbeIdentifyInput(input.string("id")))
            buildJsonObject { put("id", output.id) }
        }
        "probe.list" -> {
            val output = client.probe.list(ProbeListInput())
            buildJsonObject { put("rows", buildJsonArray { output.rows.forEach { row ->
                add(buildJsonObject { put("id", row.id); put("wire_value", row.wireValue); put("observed_at", row.observedAt) })
            } }) }
        }
        "auth.refresh" -> {
            input.fields(setOf("refresh_token"))
            val output = client.auth.refresh(AuthRefreshInput(input.string("refresh_token")))
            buildJsonObject {
                put("access_token", output.accessToken); put("refresh_token", output.refreshToken)
                put("expires_at", output.expiresAt); put("refresh_expires_at", output.refreshExpiresAt)
                put("user_id", output.userId); put("session_id", output.sessionId)
            }
        }
        "auth.signin" -> {
            input.fields(setOf("email", "password"))
            val output = client.auth.signin(AuthSigninInput(input.string("email"), input.string("password")))
            buildJsonObject {
                put("access_token", output.accessToken); put("refresh_token", output.refreshToken)
                put("expires_at", output.expiresAt); put("refresh_expires_at", output.refreshExpiresAt)
                put("user_id", output.userId); put("session_id", output.sessionId)
            }
        }
        "auth.signup" -> {
            input.fields(setOf("email", "password"))
            val output = client.auth.signup(AuthSignupInput(input.string("email"), input.string("password")))
            buildJsonObject {
                put("access_token", output.accessToken); put("refresh_token", output.refreshToken)
                put("expires_at", output.expiresAt); put("refresh_expires_at", output.refreshExpiresAt)
                put("user_id", output.userId); put("session_id", output.sessionId)
            }
        }
        "auth.logout" -> {
            input.fields(emptySet())
            val output = client.auth.logout(AuthLogoutInput())
            buildJsonObject { put("status", output.status) }
        }
        "auth.session" -> {
            input.fields(emptySet())
            val output = client.auth.session(AuthSessionInput())
            buildJsonObject { put("email", output.email); put("id", output.id); put("session_id", output.sessionId) }
        }
        else -> throw DriverFailure("unimplemented")
    }
}
