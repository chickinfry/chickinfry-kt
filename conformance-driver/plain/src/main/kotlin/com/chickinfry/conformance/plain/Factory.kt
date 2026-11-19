package com.chickinfry.conformance.plain

import com.chickinfry.RealtimeClient
import com.chickinfry.RealtimeSubscription
import com.chickinfry.SubscribeOptions
import com.chickinfry.Transport
import com.chickinfry.client.*
import com.chickinfry.conformance.*
import kotlinx.serialization.json.*

class Factory : BridgeFactory {
    override fun create(transport: Transport): Bridge = TypedBridge(createClient(transport))
}

private class TypedBridge(private val client: Client) : Bridge {
    override suspend fun subscribe(channel: String, options: SubscribeOptions): RealtimeSubscription {
        // The protocol-1 plain fixture has no declared realtime property.
        val realtime = try { client.javaClass.getMethod("getRealtime").invoke(client) as RealtimeClient }
        catch (_: ReflectiveOperationException) { throw DriverFailure("unimplemented") }
        return realtime.subscribe(channel, options)
    }

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
        else -> throw DriverFailure("unimplemented")
    }
}
