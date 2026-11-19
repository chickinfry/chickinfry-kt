@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.chickinfry.server

import kotlin.js.JsString
import kotlin.js.toJsString

/** The current verified business session. */
class Session internal constructor(val id: String, val user: SessionUser)

/** Public identity from the verified business session. */
class SessionUser internal constructor(val id: String, val email: String)

/** An authenticated application's request context. Public routes may have a null session. */
class AuthenticatedContext<S> internal constructor(context: Context, session: S) : Context(context) {
    class Auth<S> internal constructor(val session: S)
    val auth = Auth(session)
}

/** The public user fields supplied to a user-creation hook. */
class AuthUser internal constructor(val id: String, val email: String, val createdAt: String)

/** Callbacks wired by generated applications. The host owns the transaction. */
class AuthHooks(
    internal val userCreating: (suspend (DatabaseConnection, AuthUser) -> Unit)? = null,
    internal val userCreated: (suspend (AuthUser) -> Unit)? = null,
)

internal fun requestSession(frame: JsonObject): Session? {
    if (!hasVersionOne(frame) || fieldCount(frame) != 2) invalidAuthFrame()
    if (isNull(frame, "session")) return null
    val session = hostObject(frame, "session".toJsString()) ?: invalidAuthFrame()
    val user = hostObject(session, "user".toJsString()) ?: invalidAuthFrame()
    if (fieldCount(session) != 2 || fieldCount(user) != 2) invalidAuthFrame()
    return Session(authString(session, "id"), SessionUser(authString(user, "id"), authString(user, "email")))
}

internal fun authHookFrame(frame: JsonObject): Pair<String, AuthUser> {
    if (!hasVersionOne(frame) || fieldCount(frame) != 3) invalidAuthFrame()
    val user = hostObject(frame, "user".toJsString()) ?: invalidAuthFrame()
    if (fieldCount(user) != 3) invalidAuthFrame()
    return authString(frame, "hook") to AuthUser(authString(user, "id"), authString(user, "email"), authString(user, "createdAt"))
}

private fun authString(value: JsonObject, key: String): String =
    readString(value, key.toJsString())?.toString()?.takeIf { it.isNotEmpty() } ?: invalidAuthFrame()

internal fun invalidAuthFrame(): Nothing = throw OperationError("operation_failed", 500)

internal fun validRequestID(value: Double): Boolean =
    value > 0 && value <= 9007199254740991.0 && value % 1.0 == 0.0

@JsFun("(value) => Object.hasOwn(value, 'version') && value.version === 1")
private external fun hasVersionOne(value: JsonObject): Boolean

@JsFun("(object, name) => { const value = object[name]; return Object.hasOwn(object, name) && value !== null && typeof value === 'object' && !Array.isArray(value) ? value : null; }")
private external fun hostObject(objectValue: JsonObject, name: JsString): JsonObject?

@JsFun("""(headers, authenticated, authorization) => {
    const result = Object.create(null);
    for (const name of Object.keys(headers)) {
        const key = name.toLowerCase();
        if (key === 'proxy-authorization' || (authenticated && (key === 'authorization' || key === 'cookie'))) continue;
        result[key] = headers[name];
    }
    if (!authenticated && authorization.length !== 0 && !Object.hasOwn(result, 'authorization')) result.authorization = authorization;
    return result;
}""")
internal external fun requestHeaders(headers: JsonObject, authenticated: Boolean, authorization: JsString): JsonObject
