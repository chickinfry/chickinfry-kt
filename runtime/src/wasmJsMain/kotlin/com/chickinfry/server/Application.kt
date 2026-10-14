@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.chickinfry.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.toJsString

data class User(val id: String)
data class Session(val id: String, val user: User)
class Context(val requestId: Double, val method: String, val path: String, val session: Session? = null)

private class OperationError(val status: Int, val code: String) : Exception()

fun requireSession(context: Context): Session =
    context.session ?: throw OperationError(401, "unauthenticated")

external interface JsonObject : JsAny

@JsFun("(text) => { try { const value = JSON.parse(text); return value !== null && typeof value === 'object' && !Array.isArray(value) ? value : null; } catch { return null; } }")
private external fun parseInput(text: JsString): JsonObject?

@JsFun("(input, name) => typeof input[name] === 'string' && Object.hasOwn(input, name) ? input[name] : null")
private external fun readString(input: JsonObject, name: JsString): JsString?

@JsFun("() => Object.create(null)")
private external fun newObject(): JsonObject

@JsFun("(object, name, value) => { object[name] = value; }")
private external fun setField(obj: JsonObject, name: JsString, value: JsAny)

@JsFun("() => []")
private external fun newArray(): JsAny

@JsFun("(array, value) => { array.push(value); }")
private external fun appendItem(array: JsAny, value: JsonObject)

@JsFun("(value) => value.split(';', 1)[0].trim().toLowerCase() === 'application/json'")
private external fun isJsonContentType(value: JsString): Boolean

@JsFun("(value) => new TextEncoder().encode(value).byteLength")
private external fun utf8Size(value: JsString): Int

@JsFun("(status, body) => JSON.stringify({ status, body })")
private external fun envelope(status: Int, body: JsonObject): JsString

fun requiredString(input: JsonObject, name: String): String =
    readString(input, name.toJsString())?.toString() ?: throw OperationError(400, "invalid_input")

class JsonObjectBuilder {
    internal val value: JsonObject = newObject()
    fun put(name: String, value: String) = setField(this.value, name.toJsString(), value.toJsString())
    fun put(name: String, values: List<JsonObject>) {
        val array = newArray()
        values.forEach { appendItem(array, it) }
        setField(value, name.toJsString(), array)
    }
}

fun buildJsonObject(block: JsonObjectBuilder.() -> Unit): JsonObject =
    JsonObjectBuilder().apply(block).value

class Application(
    private val accepts: (String) -> Boolean,
    private val dispatch: suspend (Context, JsonObject) -> JsonObject,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun handle(
        rid: Double, method: String, path: String, contentType: String,
        authorization: String, body: String, byteLength: Int,
    ): Promise<JsString> = scope.promise {
        val response = try {
            if (method != "POST" || !path.startsWith("/") || !accepts(path.substring(1))) {
                throw OperationError(404, "not_found")
            }
            if (!isJsonContentType(contentType.toJsString())) {
                throw OperationError(415, "unsupported_media_type")
            }
            if (byteLength > 4 * 1024 * 1024 || utf8Size(body.toJsString()) > 4 * 1024 * 1024) {
                throw OperationError(413, "request_too_large")
            }
            if (authorization.isNotEmpty()) throw OperationError(401, "invalid_token")
            val input = parseInput(body.toJsString()) ?: throw OperationError(400, "invalid_input")
            envelope(200, dispatch(Context(rid, method, path), input))
        } catch (error: OperationError) {
            envelope(error.status, buildJsonObject { put("error", error.code) })
        } catch (_: Throwable) {
            envelope(500, buildJsonObject { put("error", "operation_failed") })
        }
        response
    }

}
