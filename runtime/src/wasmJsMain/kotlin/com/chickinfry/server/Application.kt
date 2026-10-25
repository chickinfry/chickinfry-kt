@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.chickinfry.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.promise
import kotlinx.coroutines.await
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.toJsString

/** Kotlin server targets do not support platform authentication yet; the context carries no session. */
open class Context(val requestId: Double, val method: String, val path: String) {
    suspend fun environment(name: String): String? {
        val text = hostEnvironment("{\"rid\":${requestId.toLong()},\"name\":${jsonValue(name)}}".toJsString()).await().toString()
        val value = parseInput(text.toJsString()) ?: error("Invalid environment response")
        if (readString(value, "error".toJsString()) != null) error("Environment binding unavailable")
        return readString(value, "value".toJsString())?.toString()
    }
    suspend fun log(message: String, level: String = "info") {
        require(level in listOf("debug", "info", "warn", "error"))
        check(hostLog("{\"rid\":${requestId.toLong()},\"level\":${jsonValue(level)},\"message\":${jsonValue(message)}}".toJsString()).await().toString() == "{}")
    }
}

@JsFun("(text) => globalThis.__chik.env(new TextEncoder().encode(text)).then(bytes => new TextDecoder('utf-8', { fatal: true }).decode(bytes.slice()))")
private external fun hostEnvironment(text: JsString): Promise<JsString>

@JsFun("(text) => globalThis.__chik.log(new TextEncoder().encode(text)).then(bytes => new TextDecoder('utf-8', { fatal: true }).decode(bytes.slice()))")
private external fun hostLog(text: JsString): Promise<JsString>

/** An explicit operation failure. Only HTTP client and server error statuses are accepted. */
class OperationError(val code: String, val status: Int) : Exception() {
    init {
        require(status in 400..599) { "Operation error status must be between 400 and 599." }
    }
}

private const val MAX_INPUT_BYTES = 4 * 1024 * 1024

external interface JsonObject : JsAny

@JsFun("(text) => { try { const value = JSON.parse(text); return value !== null && typeof value === 'object' && !Array.isArray(value) ? value : null; } catch { return null; } }")
private external fun parseInput(text: JsString): JsonObject?

// A holder keeps "not JSON" (null) apart from "JSON but not an object", which map to different error codes.
@JsFun("(text) => { try { return { value: JSON.parse(text) }; } catch { return null; } }")
private external fun parseRequestJson(text: JsString): JsAny?

@JsFun("(holder) => { const value = holder.value; return value !== null && typeof value === 'object' && !Array.isArray(value) ? value : null; }")
private external fun requestObject(holder: JsAny): JsonObject?

// Scalars are checked in place and only containers are stacked, so a large flat array allocates nothing per item.
@JsFun("""(input) => {
    const containers = [input];
    const depths = [1];
    const scalarValid = (value) => typeof value === 'number' ? Number.isFinite(value)
        : typeof value === 'string' ? value.isWellFormed() : true;
    const visit = (child, depth) => {
        if (child !== null && typeof child === 'object') {
            containers.push(child);
            depths.push(depth);
            return true;
        }
        return scalarValid(child);
    };
    while (containers.length > 0) {
        const value = containers.pop();
        const depth = depths.pop();
        if (depth > 64) return false;
        if (Array.isArray(value)) {
            for (let i = 0; i < value.length; i++) if (!visit(value[i], depth + 1)) return false;
        } else {
            for (const key in value) {
                if (!Object.hasOwn(value, key)) continue;
                if (!key.isWellFormed() || !visit(value[key], depth + 1)) return false;
            }
        }
    }
    return true;
}""")
private external fun validInputStructure(input: JsonObject): Boolean

// JSON.parse keeps only the last duplicate, so duplicates are found in the source text. The text has
// already parsed, so a string token always ends at the first unescaped quote. Keys compare decoded.
// Containers deeper than 64 are invalid anyway, so the scan stops there instead of holding a Set per level.
@JsFun("""(text) => {
    const stack = [];
    for (let i = 0; i < text.length; i++) {
        const c = text[i];
        if (c === '"') {
            let j = i + 1;
            while (text[j] !== '"') j += text[j] === '\\' ? 2 : 1;
            const top = stack[stack.length - 1];
            if (top !== undefined && top.keys !== null && top.expectKey) {
                const key = JSON.parse(text.slice(i, j + 1));
                if (top.keys.has(key)) return false;
                top.keys.add(key);
                top.expectKey = false;
            }
            i = j;
        } else if (c === '{') {
            if (stack.length >= 64) return false;
            stack.push({ keys: new Set(), expectKey: true });
        } else if (c === '[') {
            if (stack.length >= 64) return false;
            stack.push({ keys: null, expectKey: false });
        } else if (c === '}' || c === ']') {
            stack.pop();
        } else if (c === ',') {
            const top = stack[stack.length - 1];
            if (top !== undefined && top.keys !== null) top.expectKey = true;
        }
    }
    return true;
}""")
private external fun uniqueObjectKeys(text: JsString): Boolean

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

/** Returns whether the input owns the wire key, including keys whose value is null. */
fun hasField(input: JsonObject, name: String): Boolean = hasField(input, name.toJsString())

@JsFun("(input, name) => Object.hasOwn(input, name) && input[name] === null")
private external fun fieldIsNull(input: JsonObject, name: JsString): Boolean

/** Tests explicit JSON null without treating absent or undefined values as null. */
fun isNull(input: JsonObject, name: String): Boolean = fieldIsNull(input, name.toJsString())

@JsFun("(input, name) => Object.hasOwn(input, name) && Array.isArray(input[name]) ? input[name] : null")
private external fun readArray(input: JsonObject, name: JsString): JsAny?

@JsFun("(array) => array.length")
private external fun arraySize(array: JsAny): Int

@JsFun("(array, index) => Object.hasOwn(array, index) && typeof array[index] === 'string' ? array[index] : null")
private external fun arrayString(array: JsAny, index: Int): JsString?

@JsFun("(array, index) => { const value = array[index]; return Object.hasOwn(array, index) && value !== null && typeof value === 'object' && !Array.isArray(value) ? value : null; }")
private external fun arrayObject(array: JsAny, index: Int): JsonObject?

/** Reads a string array without coercing null, absent, or non-string values. */
fun requiredStringList(input: JsonObject, name: String): List<String> {
    val array = readArray(input, name.toJsString()) ?: throw OperationError("invalid_input", 400)
    return List(arraySize(array)) { index ->
        arrayString(array, index)?.toString() ?: throw OperationError("invalid_input", 400)
    }
}

/** Reads non-null object items; generated decoders validate each item's declared fields. */
fun requiredObjectList(input: JsonObject, name: String): List<JsonObject> {
    val array = readArray(input, name.toJsString()) ?: throw OperationError("invalid_input", 400)
    return List(arraySize(array)) { index ->
        arrayObject(array, index) ?: throw OperationError("invalid_input", 400)
    }
}

@JsFun("(input, limit) => { const size = new TextEncoder().encode(JSON.stringify(input)).byteLength; return size > limit ? null : { size, counts: new WeakMap() }; }")
private external fun newInputBudget(input: JsonObject, limit: Int): JsAny?

/** A byte budget shared by all generated field decoders for one request, including nested defaults. */
class InputBudget(input: JsonObject) {
    internal val value: JsAny = newInputBudget(input, MAX_INPUT_BYTES)
        ?: throw OperationError("request_too_large", 413)
}

@JsFun("(input, name, literal, budget, limit) => { const count = budget.counts.get(input) ?? Object.keys(input).length; budget.size += new TextEncoder().encode(JSON.stringify(name)).byteLength + 1 + (count > 0 ? 1 : 0) + new TextEncoder().encode(literal).byteLength; if (budget.size > limit) return null; budget.counts.set(input, count + 1); return { [name]: JSON.parse(literal) }; }")
private external fun defaultField(input: JsonObject, name: JsString, literal: JsString, budget: JsAny, limit: Int): JsonObject?

/** Supplies a fresh compiler-validated JSON literal only for an absent key; read the field from the result. */
fun withDefault(input: JsonObject, name: String, literal: String, budget: InputBudget): JsonObject {
    if (hasField(input, name)) return input
    return defaultField(input, name.toJsString(), literal.toJsString(), budget.value, MAX_INPUT_BYTES)
        ?: throw OperationError("request_too_large", 413)
}

fun requiredString(input: JsonObject, name: String): String =
    readString(input, name.toJsString())?.toString() ?: throw OperationError("invalid_input", 400)

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

/** One application per host cold-start generation; a new generation must create a new application. */
class Application(
    private val accepts: (String) -> Boolean,
    setup: suspend () -> Unit = {},
    private val dispatch: suspend (Context, JsonObject) -> JsonObject,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val initialization = scope.async(start = CoroutineStart.LAZY) { setup() }

    /**
     * Completes setup before the host marks this generation ready. Failures, including setup cancellation,
     * are retained. Cancelling a caller only stops its wait; the host owns generation replacement.
     */
    suspend fun initialize() {
        initialization.await()
    }

    fun handle(
        rid: Double, method: String, path: String, contentType: String,
        authorization: String, body: String, byteLength: Int,
    ): Promise<JsString> = scope.promise {
        try {
            initialize()
        } catch (_: Throwable) {
            return@promise envelope(500, buildJsonObject { put("error", "operation_failed") })
        }
        val response = try {
            if (method != "POST" || !path.startsWith("/") || !accepts(path.substring(1))) {
                throw OperationError("not_found", 404)
            }
            if (!isJsonContentType(contentType.toJsString())) {
                throw OperationError("unsupported_media_type", 415)
            }
            if (byteLength > MAX_INPUT_BYTES || utf8Size(body.toJsString()) > MAX_INPUT_BYTES) {
                throw OperationError("request_too_large", 413)
            }
            if (authorization.isNotEmpty()) throw OperationError("invalid_token", 401)
            val text = body.toJsString()
            val parsed = parseRequestJson(text) ?: throw OperationError("invalid_json", 400)
            val input = requestObject(parsed) ?: throw OperationError("invalid_input", 400)
            if (!uniqueObjectKeys(text) || !validInputStructure(input)) throw OperationError("invalid_input", 400)
            envelope(200, dispatch(Context(rid, method, path), input))
        } catch (error: OperationError) {
            envelope(error.status, buildJsonObject { put("error", error.code) })
        } catch (_: Throwable) {
            envelope(500, buildJsonObject { put("error", "operation_failed") })
        }
        response
    }

}
