@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.chickinfry.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.toJsString

@JsFun("(text) => globalThis.__chikResources.realtime(new TextEncoder().encode(text)).then(bytes => new TextDecoder('utf-8', { fatal: true }).decode(bytes.slice()))")
private external fun hostRealtime(text: JsString): Promise<JsString>

// Values JSON cannot carry stop here, so nothing the host frame cannot represent is ever sent.
@JsFun("""(rid, channel, payload) => JSON.stringify({rid, op: "publish", input: {channel, payload}}, (_, value) => {
    if (value === undefined || typeof value === 'function' || typeof value === 'symbol' || typeof value === 'bigint' ||
        (typeof value === 'number' && !Number.isFinite(value))) throw new TypeError('Invalid realtime payload.');
    return value;
})""")
private external fun realtimeFrame(rid: Double, channel: JsString, payload: JsonObject): JsString

// One word per outcome keeps the reply shape check in a single place: "ok", "unavailable",
// "<status>:<code>" for a public rejection, or "bad" for anything else.
@JsFun("""(text) => { try {
    if (text.length > 4096) return 'bad';
    const value = JSON.parse(text);
    if (value === null || typeof value !== 'object' || Array.isArray(value)) return 'bad';
    const keys = Object.keys(value);
    if (keys.length === 0) return 'ok';
    if (keys.length === 1 && value.error === 'binding_unavailable') return 'unavailable';
    if (keys.length === 2 && typeof value.error === 'string' && Number.isInteger(value.status) && value.status >= 400 && value.status <= 599) return value.status + ':' + value.error;
    return 'bad';
} catch { return 'bad'; } }""")
private external fun realtimeOutcome(text: JsString): JsString

/**
 * Publishes realtime events through the shared host realtime port. The engine binding and its
 * credentials stay in the host.
 *
 * The payload is a runtime [JsonObject], the same JSON value the handlers already build with
 * [buildJsonObject]. The server module cannot link kotlinx-serialization, so a client-side
 * kotlinx `JsonElement` does not cross this API; it crosses as JSON text. Encode the element with
 * `element.toString()` and read it back on the server with [parseJsonObject], or build the object
 * directly with [buildJsonObject].
 */
class Realtime(private val requestId: Double) {
    /**
     * Publishes one event on a channel and returns once the engine confirmed it. Engine rejections
     * keep their public code and status, an unbound host port is service_unavailable, and an
     * unconfirmed outcome is realtime_publish_outcome_unknown.
     */
    suspend fun publish(channel: String, payload: JsonObject) {
        val frame = try {
            realtimeFrame(requestId, channel.toJsString(), payload).toString()
        } catch (_: Throwable) {
            throw OperationError("operation_failed", 500)
        }
        val reply = try {
            hostRealtime(frame.toJsString()).await().toString()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // A call the host never accepted was not sent, so it is a plain failure.
            throw OperationError("operation_failed", 500)
        }
        val outcome = realtimeOutcome(reply.toJsString()).toString()
        if (outcome == "ok") return
        if (outcome == "unavailable") throw OperationError("service_unavailable", 503)
        val split = outcome.indexOf(':')
        if (split > 0) {
            val code = outcome.substring(split + 1)
            if (validErrorCode(code)) throw OperationError(code, outcome.substring(0, split).toInt())
        }
        throw OperationError("operation_failed", 500)
    }
}

/**
 * Reads a JSON object from text, or returns null when the text is not a JSON object. This is the
 * server-side end of the path from a client `JsonElement`: the client encodes the element as text
 * and the server turns the text back into the runtime object.
 */
fun parseJsonObject(text: String): JsonObject? = parseInput(text.toJsString())
