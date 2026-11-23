@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.chickinfry.server

import com.chickinfry.publicErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.promise
import kotlinx.coroutines.await
import kotlin.coroutines.CoroutineContext
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.toJsBoolean
import kotlin.js.toJsString

/** HTTP metadata for the current operation. */
class Request internal constructor(val method: String, val path: String, val headers: RequestHeaders)

/** Read-only HTTP headers with case-insensitive lookup. */
class RequestHeaders internal constructor(private val values: JsonObject) {
    operator fun get(name: String): String? = headerValue(values, name.toJsString())?.toString()
}

@JsFun("(headers, name) => { const key = name.toLowerCase(); return Object.hasOwn(headers, key) && typeof headers[key] === 'string' ? headers[key] : null; }")
private external fun headerValue(headers: JsonObject, name: JsString): JsString?

/** Resources scoped to the current business request. */
open class Context internal constructor(
    protected val requestId: Double,
    val request: Request,
    private val secretValues: JsonObject,
) {
    protected constructor(context: Context) : this(context.requestId, context.request, context.secretValues)

    internal var droppedLogs = 0
        private set

    protected fun requireSecret(name: String): String =
        readString(secretValues, name.toJsString())?.toString() ?: throw OperationError("service_unavailable", 503)

    suspend fun log(level: String, message: String, fields: JsonObject? = null) {
        try {
            require(level == "debug" || level == "info" || level == "warn" || level == "error")
            require(message.length <= MAX_INPUT_BYTES && (fields == null || validInputStructure(fields)))
            val frame = logFrame(requestId, level.toJsString(), message.toJsString(), fields)
            check(hostLog(frame).await().toString() == "{}")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            droppedLogs++
        }
    }
}

@JsFun("""(rid, level, message, fields) => JSON.stringify(fields === null ? {rid, level, message} : {rid, level, message, fields}, (_, value) => {
    if (value === undefined || typeof value === 'function' || typeof value === 'symbol') throw new TypeError('Invalid log field.');
    return value;
})""")
private external fun logFrame(rid: Double, level: JsString, message: JsString, fields: JsonObject?): JsString

@JsFun("(text) => globalThis.__chik.env(new TextEncoder().encode(text)).then(bytes => new TextDecoder('utf-8', { fatal: true }).decode(bytes.slice()))")
private external fun hostEnvironment(text: JsString): Promise<JsString>

private suspend fun secretValue(rid: Double, name: String): String? {
    val text = hostEnvironment("{\"rid\":${rid.toLong()},\"name\":${jsonValue(name)}}".toJsString()).await()
    val value = parseInput(text) ?: return null
    if (readString(value, "error".toJsString()) != null) return null
    return readString(value, "value".toJsString())?.toString()?.takeIf { it.isNotEmpty() }
}

@JsFun("(text) => globalThis.__chik.log(new TextEncoder().encode(text)).then(bytes => new TextDecoder('utf-8', { fatal: true }).decode(bytes.slice()))")
private external fun hostLog(text: JsString): Promise<JsString>

@JsFun("(_) => 0")
private external fun swapOutputRequest(rid: Double): Double

private class RequestOutputDispatcher(private val rid: Double) : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        // One synchronous continuation at a time owns the output slot.
        Dispatchers.Default.dispatch(context, Runnable {
            val previous = swapOutputRequest(rid)
            try {
                block.run()
            } finally {
                swapOutputRequest(previous)
            }
        })
    }
}

/** A declared input path and its public validation code. */
class FieldError(val path: String, val code: String)

/** An explicit operation failure. Only HTTP client and server error statuses are accepted. */
class OperationError(
    val code: String,
    val status: Int,
    override val message: String? = null,
    val fields: List<FieldError> = emptyList(),
) : Exception() {
    init {
        require(status in 400..599) { "Operation error status must be between 400 and 599." }
    }

    // BEGIN generated operation errors

    companion object {
        fun notFound(): OperationError = OperationError("not_found", 404)
        fun invalidInput(): OperationError = OperationError("invalid_input", 400)
        fun conflict(): OperationError = OperationError("conflict", 409)
        fun forbidden(): OperationError = OperationError("forbidden", 403)
        fun notImplemented(): OperationError = OperationError("not_implemented", 501)
    }

    // END generated operation errors
}

internal fun validErrorCode(code: String): Boolean = code.length in 1..64 && code.all { it in 'a'..'z' || it == '_' }

@JsFun("""(path) => {
    if (path.length > 256 || !/^[a-z][a-z0-9]*(?:_[a-z0-9]+)*(?:\.[a-z][a-z0-9]*(?:_[a-z0-9]+)*|\[(?:0|[1-9][0-9]*)\])*(?![\s\S])/.test(path)) return null;
    if (Array.from(path.matchAll(/\[([0-9]+)\]/g)).some(match => Number(match[1]) > 4294967295)) return null;
    return path.replace(/\[[0-9]+\]/g, '[]');
}""")
private external fun publicFieldShape(path: JsString): JsString?

private const val MAX_INPUT_BYTES = 4 * 1024 * 1024

external interface JsonObject : JsAny

@JsFun("(text) => { try { const value = JSON.parse(text); return value !== null && typeof value === 'object' && !Array.isArray(value) ? value : null; } catch { return null; } }")
internal external fun parseInput(text: JsString): JsonObject?

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
@JsFun("""(text, strictVersion) => {
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
                if (strictVersion && stack.length === 1 && key === 'version') {
                    let start = j + 1;
                    while (' \t\r\n'.includes(text[start])) start++;
                    start++;
                    while (' \t\r\n'.includes(text[start])) start++;
                    if (text[start] !== '1' || !',} \t\r\n'.includes(text[start + 1])) return false;
                }
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
private external fun uniqueObjectKeys(text: JsString, strictVersion: Boolean): Boolean

@JsFun("(input, name) => typeof input[name] === 'string' && Object.hasOwn(input, name) ? input[name] : null")
internal external fun readString(input: JsonObject, name: JsString): JsString?

@JsFun("() => Object.create(null)")
private external fun newObject(): JsonObject

@JsFun("(object, name, value) => { object[name] = value; }")
private external fun setField(obj: JsonObject, name: JsString, value: JsAny?)

@JsFun("() => []")
private external fun newArray(): JsAny

@JsFun("(array, value) => { array.push(value); }")
private external fun appendItem(array: JsAny, value: JsAny?)

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
internal external fun arraySize(array: JsAny): Int

@JsFun("(array, index) => Object.hasOwn(array, index) && typeof array[index] === 'string' ? array[index] : null")
internal external fun arrayString(array: JsAny, index: Int): JsString?

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
    fun put(name: String, value: JsAny?) = setField(this.value, name.toJsString(), value)
    fun put(name: String, values: List<JsAny?>) {
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
    private val secrets: List<String> = emptyList(),
    private val acceptsField: (operation: String, path: String) -> Boolean = { _, _ -> false },
    private val authEnabled: Boolean = false,
    private val isPublic: (String) -> Boolean = { false },
    private val authHooks: AuthHooks? = null,
    private val authorizeRealtime: (suspend (Context, String) -> Boolean)? = null,
    private val dispatch: suspend (Context, JsonObject) -> JsonObject,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val initialization = scope.async(start = CoroutineStart.LAZY) { setup() }

    /**
     * Completes setup before the host marks this generation ready. Failures, including setup cancellation,
     * are retained. Cancelling a caller only stops its wait; the host owns generation replacement.
     */
    suspend fun initialize() {
        require(authEnabled || authHooks == null) { "declare auth in auth.yml" }
        initialization.await()
    }

    fun handle(
        rid: Double, method: String, path: String, contentType: String,
        authorization: String, body: String, byteLength: Int, headers: String = "{}", auth: String? = null,
    ): Promise<JsString> = scope.promise(RequestOutputDispatcher(rid)) {
        try {
            initialize()
        } catch (_: Throwable) {
            return@promise envelope(500, buildJsonObject { put("error", "operation_failed") })
        }
        val response = try {
            val name = path.removePrefix("/")
            if (method != "POST" || !path.startsWith("/") || name.startsWith("auth.") || !accepts(name)) {
                throw OperationError("not_found", 404)
            }
            if (!isJsonContentType(contentType.toJsString())) {
                throw OperationError("unsupported_media_type", 415)
            }
            if (byteLength > MAX_INPUT_BYTES || utf8Size(body.toJsString()) > MAX_INPUT_BYTES) {
                throw OperationError("request_too_large", 413)
            }
            val public = authEnabled && isPublic(name)
            val session = if (authEnabled) {
                if (auth == null || !validRequestID(rid)) invalidAuthFrame()
                requestSession(parseAuthFrame(auth))
            } else {
                if (auth != null) invalidAuthFrame()
                null
            }
            if (authEnabled && !public && session == null) throw OperationError("unauthenticated", 401)
            val text = body.toJsString()
            val parsed = parseRequestJson(text) ?: throw OperationError("invalid_json", 400)
            val input = requestObject(parsed) ?: throw OperationError("invalid_input", 400)
            if (!uniqueObjectKeys(text, false) || !validInputStructure(input)) throw OperationError("invalid_input", 400)
            val values = newObject()
            for (name in secrets) {
                val value = secretValue(rid, name) ?: continue
                setField(values, name.toJsString(), value.toJsString())
            }
            val filtered = requestHeaders(parseInput(headers.toJsString()) ?: newObject(), authEnabled, authorization.toJsString())
            val base = Context(rid, Request(method, path, RequestHeaders(filtered)), values)
            val context = when {
                !authEnabled -> base
                public -> AuthenticatedContext(base, session)
                else -> AuthenticatedContext(base, checkNotNull(session))
            }
            envelope(200, dispatch(context, input))
        } catch (error: OperationError) {
            operationFailure(path, error)
        } catch (_: VaultBindingError) {
            envelope(503, buildJsonObject { put("error", "service_unavailable") })
        } catch (_: Throwable) {
            envelope(500, buildJsonObject { put("error", "operation_failed") })
        }
        response
    }

    /** Invoked only by the generated host callback entry, outside HTTP dispatch. */
    fun handleAuthHook(rid: Double, metadata: String, lease: Double? = null): Promise<JsString> =
        scope.promise(RequestOutputDispatcher(rid)) {
            try {
                initialize()
                if (!authEnabled || !validRequestID(rid)) invalidAuthFrame()
                val (hook, user) = authHookFrame(parseAuthFrame(metadata))
                when (hook) {
                    "userCreating" -> {
                        if (lease == null || !validRequestID(lease)) invalidAuthFrame()
                        val connection = DatabaseConnection.borrowed(rid, lease.toLong())
                        try {
                            authHooks?.userCreating?.invoke(connection, user)
                        } catch (_: Throwable) {
                            throw OperationError("user_creation_failed", 500)
                        } finally {
                            connection.revokeBorrowed()
                        }
                    }
                    "userCreated" -> {
                        if (lease != null) invalidAuthFrame()
                        try {
                            authHooks?.userCreated?.invoke(user)
                        } catch (_: Throwable) {
                            throw OperationError("user_created_effect_failed", 500)
                        }
                    }
                    else -> invalidAuthFrame()
                }
                envelope(200, newObject())
            } catch (error: OperationError) {
                envelope(error.status, buildJsonObject { put("error", error.code) })
            } catch (_: Throwable) {
                envelope(500, buildJsonObject { put("error", "operation_failed") })
            }
        }

    /**
     * Invoked only by the generated host callback entry for the engine's authorization callback,
     * after the host verified the engine proof and revalidated the session. Only the application's
     * decision remains: a missing decision, an exception or a malformed frame denies the channel
     * or fails the call, and the reply is {"allowed": true | false}.
     */
    fun handleRealtimeAuthorize(rid: Double, body: String, headers: String = "{}", auth: String? = null): Promise<JsString> =
        scope.promise(RequestOutputDispatcher(rid)) {
            try {
                initialize()
                val text = body.toJsString()
                val input = parseInput(text)
                if (input == null || utf8Size(text) > MAX_INPUT_BYTES || !uniqueObjectKeys(text, false) ||
                    !validInputStructure(input) || fieldCount(input) != 1) {
                    throw OperationError("invalid_input", 400)
                }
                val channel = readString(input, "channel".toJsString())?.toString() ?: throw OperationError("invalid_input", 400)
                val session = if (authEnabled) {
                    if (auth == null || !validRequestID(rid)) invalidAuthFrame()
                    requestSession(parseAuthFrame(auth))
                } else {
                    if (auth != null) invalidAuthFrame()
                    null
                }
                val values = newObject()
                for (name in secrets) {
                    val value = secretValue(rid, name) ?: continue
                    setField(values, name.toJsString(), value.toJsString())
                }
                val filtered = requestHeaders(parseInput(headers.toJsString()) ?: newObject(), authEnabled, "".toJsString())
                val base = Context(rid, Request("POST", "/realtime", RequestHeaders(filtered)), values)
                val context = if (authEnabled) AuthenticatedContext(base, session) else base
                val decide = authorizeRealtime
                val allowed = decide != null && try {
                    decide(context, channel)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    false
                }
                envelope(200, buildJsonObject { put("allowed", allowed.toJsBoolean()) })
            } catch (error: OperationError) {
                envelope(error.status, buildJsonObject { put("error", error.code) })
            } catch (_: Throwable) {
                envelope(500, buildJsonObject { put("error", "operation_failed") })
            }
        }

    private fun parseAuthFrame(source: String): JsonObject {
        val text = source.toJsString()
        if (utf8Size(text) > MAX_INPUT_BYTES) invalidAuthFrame()
        val frame = parseInput(text) ?: invalidAuthFrame()
        if (!uniqueObjectKeys(text, true) || !validInputStructure(frame)) invalidAuthFrame()
        return frame
    }

    private fun operationFailure(path: String, error: OperationError): JsString {
        if (!validErrorCode(error.code)) {
            return envelope(500, buildJsonObject { put("error", "operation_failed") })
        }
        return envelope(error.status, buildJsonObject {
            put("error", error.code)
            error.message?.let(::publicErrorMessage)?.let { put("message", it) }
            val fields = error.fields
            if (error.code == "invalid_input" && fields.size in 1..32 && fields.all { field ->
                    val shape = publicFieldShape(field.path.toJsString())?.toString()
                    shape != null && validErrorCode(field.code) && acceptsField(path.removePrefix("/"), shape)
                }) {
                put("fields", fields.map { field ->
                    buildJsonObject {
                        put("path", field.path)
                        put("code", field.code)
                    }
                })
            }
        })
    }

}
