@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, kotlinx.coroutines.DelicateCoroutinesApi::class)

package com.chickinfry.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.toJsString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

// The mock host records every realtime frame and answers with the scripted JSON reply.
@JsFun("(reply) => { globalThis.previousRealtimeHost = globalThis.__chikResources; globalThis.realtimeFrames = []; globalThis.__chikResources = {realtime: async bytes => { globalThis.realtimeFrames.push(new TextDecoder().decode(bytes)); return new TextEncoder().encode(reply); }}; }")
private external fun installRealtimeMock(reply: JsString)

@JsFun("() => { globalThis.__chikResources = globalThis.previousRealtimeHost; delete globalThis.previousRealtimeHost; delete globalThis.realtimeFrames; }")
private external fun restoreRealtimeMock()

@JsFun("() => JSON.stringify(globalThis.realtimeFrames)")
private external fun recordedFrames(): JsString

@JsFun("(value) => { value.self = value; }")
private external fun makeCyclic(value: JsonObject)

private suspend fun publishWith(reply: String, payload: JsonObject = buildJsonObject { put("id", "A") }) {
    installRealtimeMock(reply.toJsString())
    try {
        Realtime(7.0).publish("orders", payload)
    } finally {
        restoreRealtimeMock()
    }
}

class RealtimeTest {
    @Test
    fun publishSendsOneFrameAndAcceptsConfirmation(): Promise<JsString> = GlobalScope.promise(Dispatchers.Default) {
        installRealtimeMock("{}".toJsString())
        try {
            Realtime(7.0).publish("orders", buildJsonObject { put("id", "A") })
            assertEquals(
                """["{\"rid\":7,\"op\":\"publish\",\"input\":{\"channel\":\"orders\",\"payload\":{\"id\":\"A\"}}}"]""",
                recordedFrames().toString(),
            )
        } finally {
            restoreRealtimeMock()
        }
        "passed".toJsString()
    }

    @Test
    fun publishMapsPublicRejections(): Promise<JsString> = GlobalScope.promise(Dispatchers.Default) {
        for ((reply, code, status) in listOf(
            Triple("""{"error":"binding_unavailable"}""", "service_unavailable", 503),
            Triple("""{"error":"unauthorized_project","status":403}""", "unauthorized_project", 403),
            Triple("""{"error":"realtime_publish_outcome_unknown","status":503}""", "realtime_publish_outcome_unknown", 503),
            Triple("""{"error":"invalid_host_call"}""", "operation_failed", 500),
            Triple("""{"error":"Bad_Code","status":403}""", "operation_failed", 500),
            Triple("""{"error":"unauthorized_project","status":200}""", "operation_failed", 500),
            Triple("""{"ok":true}""", "operation_failed", 500),
            Triple("[]", "operation_failed", 500),
            Triple("not json", "operation_failed", 500),
        )) {
            val error = assertFailsWith<OperationError>(reply) { publishWith(reply) }
            assertEquals(code to status, error.code to error.status, reply)
        }
        "passed".toJsString()
    }

    @Test
    fun publishNeverSendsAValueTheFrameCannotCarry(): Promise<JsString> = GlobalScope.promise(Dispatchers.Default) {
        installRealtimeMock("{}".toJsString())
        try {
            val cyclic = buildJsonObject { put("id", "A") }
            makeCyclic(cyclic)
            val error = assertFailsWith<OperationError> { Realtime(7.0).publish("orders", cyclic) }
            assertEquals("operation_failed" to 500, error.code to error.status)
            assertEquals("[]", recordedFrames().toString())
        } finally {
            restoreRealtimeMock()
        }
        "passed".toJsString()
    }

    // A client kotlinx JsonElement crosses to the server as JSON text.
    @Test
    fun clientJsonTextBecomesTheServerPayload(): Promise<JsString> = GlobalScope.promise(Dispatchers.Default) {
        val payload = assertNotNull(parseJsonObject("""{"id":"A","items":[1,2],"nested":{"ok":true}}"""))
        installRealtimeMock("{}".toJsString())
        try {
            Realtime(7.0).publish("orders", payload)
            assertEquals(
                """["{\"rid\":7,\"op\":\"publish\",\"input\":{\"channel\":\"orders\",\"payload\":{\"id\":\"A\",\"items\":[1,2],\"nested\":{\"ok\":true}}}}"]""",
                recordedFrames().toString(),
            )
        } finally {
            restoreRealtimeMock()
        }
        assertEquals(null, parseJsonObject("[1]"))
        "passed".toJsString()
    }

    @Test
    fun authorizeAnswersOnlyTheApplicationDecision(): Promise<JsString> = GlobalScope.promise(Dispatchers.Default) {
        val app = Application({ true }, authorizeRealtime = { context, channel ->
            channel == "orders" && context.request.headers["origin"] == null
        }) { _, input -> input }
        assertEquals("""{"status":200,"body":{"allowed":true}}""", app.handleRealtimeAuthorize(1.0, """{"channel":"orders"}""").await().toString())
        assertEquals("""{"status":200,"body":{"allowed":false}}""", app.handleRealtimeAuthorize(1.0, """{"channel":"other"}""").await().toString())
        val failing = Application({ true }, authorizeRealtime = { _, _ -> error("private detail") }) { _, input -> input }
        assertEquals("""{"status":200,"body":{"allowed":false}}""", failing.handleRealtimeAuthorize(1.0, """{"channel":"orders"}""").await().toString())
        val missing = Application({ true }) { _, input -> input }
        assertEquals("""{"status":200,"body":{"allowed":false}}""", missing.handleRealtimeAuthorize(1.0, """{"channel":"orders"}""").await().toString())
        for (body in listOf("{}", """{"channel":1}""", """{"channel":"orders","extra":true}""", "[]", "nope")) {
            assertEquals("""{"status":400,"body":{"error":"invalid_input"}}""", app.handleRealtimeAuthorize(1.0, body).await().toString(), body)
        }
        "passed".toJsString()
    }

    @Test
    fun authorizeIsNotReachableThroughTheOperationPath(): Promise<JsString> = GlobalScope.promise(Dispatchers.Default) {
        val app = Application({ it == "orders.list" }, authorizeRealtime = { _, _ -> true }) { _, input -> input }
        assertEquals(
            """{"status":404,"body":{"error":"not_found"}}""",
            app.handle(1.0, "POST", "/realtime.authorize", "application/json", "", """{"channel":"orders"}""", 20).await().toString(),
        )
        "passed".toJsString()
    }
}
