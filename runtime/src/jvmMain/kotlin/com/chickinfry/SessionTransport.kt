package com.chickinfry

import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class WireResponse(val json: JsonObject, val status: Int) {
    fun <T> decode(decode: (JsonObject) -> T): T = try {
        decode(json)
    } catch (_: SerializationException) {
        throw ClientError("internal", status, true)
    }

    fun credentials(): SessionCredentials = decode { json ->
        fun string(key: String): String {
            val value = json[key] as? JsonPrimitive
            if (value == null || !value.isString) throw SerializationException("Invalid session response.")
            return value.content
        }
        val access = string("access_token")
        val refresh = string("refresh_token")
        if (!credentialPattern.matches(access) || !credentialPattern.matches(refresh)) {
            throw SerializationException("Invalid session response.")
        }
        SessionCredentials(access, refresh, string("expires_at"), string("refresh_expires_at"),
            string("user_id"), string("session_id"))
    }

    fun signedInCredentials(operation: String): SessionCredentials? = when {
        operation == "auth.signin" -> credentials()
        operation == "auth.signup" && ("access_token" in json || "refresh_token" in json) -> credentials()
        else -> null
    }

    override fun toString(): String = "WireResponse"
}

private class RefreshFlight {
    val result = CompletableDeferred<WireResponse>()
    val sent = CompletableDeferred<Unit>()
    var metadata: CallObservation.ResultMetadata? = null
    lateinit var worker: Job
}

private class SessionCoordination {
    val mutex = Mutex()
    val flights = mutableMapOf<String, RefreshFlight>()
    val suspended = mutableSetOf<String>()
    var uncertainClear: String? = null
}

private val coordinations = mutableListOf<Pair<WeakReference<SessionStore>, SessionCoordination>>()

private fun coordination(store: SessionStore): SessionCoordination = synchronized(coordinations) {
    coordinations.removeAll { it.first.get() == null }
    coordinations.firstOrNull { it.first.get() === store }?.second ?: SessionCoordination().also {
        coordinations += WeakReference(store) to it
    }
}

internal suspend fun invokeSession(
    store: SessionStore,
    operation: String,
    publicOperation: Boolean,
    bytes: ByteArray,
    timeoutMillis: Long,
    observation: SessionObservation?,
    callObservation: CallObservation?,
    send: suspend (String, ByteArray, String?, (CallObservation.Exchange?) -> Unit) -> WireResponse,
): WireResponse {
    val state = coordination(store)
    val expected = state.mutex.withLock {
        val current = readSession(store)
        if (current == null) {
            if (state.uncertainClear != null) throw ClientError("unauthenticated")
        } else {
            if (current.refreshToken in state.suspended) throw ClientError("unauthenticated")
            state.uncertainClear = null
        }
        current
    }
    if (expected == null && (!publicOperation || operation == "auth.refresh")) throw ClientError("unauthenticated")

    suspend fun selectedSend(token: String?): WireResponse {
        var exchange: CallObservation.Exchange? = null
        try { return send(operation, bytes, token) { exchange = it } }
        finally { callObservation?.select(exchange) }
    }
    if (operation == "auth.refresh") {
        // Explicit refresh shares the single-consumption path with automatic refresh of the same credentials.
        // The sharing key is read from the body actually sent, not from the caller-mutable input object.
        val input = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject
        val token = input["refresh_token"] as? JsonPrimitive
        if (token == null || !token.isString || !credentialPattern.matches(token.content)) {
            throw ClientError("invalid_argument")
        }
        return refresh(store, state, expected, token.content, bytes, timeoutMillis, observation, callObservation, send, selectSuccess = true)
    }
    var used = expected
    var exchange: CallObservation.Exchange? = null
    val response = try {
        send(operation, bytes, expected?.accessToken) { exchange = it }.also { callObservation?.select(exchange) }
    } catch (failure: OperationError) {
        if (expected == null || failure.code != "invalid_token" || failure.status != 401) {
            callObservation?.select(exchange)
            throw failure
        }
        observation?.point?.invoke(SessionPoint("both-originals-rejected", operation, expected))
        val refreshBytes = buildJsonObject { put("refresh_token", expected.refreshToken) }
            .toString().toByteArray(Charsets.UTF_8)
        val refreshed = refresh(store, state, expected, expected.refreshToken, refreshBytes, timeoutMillis, observation, callObservation, send)
        used = refreshed.credentials()
        selectedSend(used.accessToken)
    } catch (failure: Throwable) {
        callObservation?.select(exchange)
        throw failure
    }
    response.signedInCredentials(operation)?.let { replacement ->
        if (!replace(store, used, replacement, response.status)) throw ClientError("unauthenticated")
    }
    if (operation == "auth.logout") clear(store, used, observation, response.status)
    return response
}

private suspend fun refresh(
    store: SessionStore,
    state: SessionCoordination,
    expected: SessionCredentials?,
    refreshToken: String,
    bytes: ByteArray,
    timeoutMillis: Long,
    observation: SessionObservation?,
    callObservation: CallObservation?,
    send: suspend (String, ByteArray, String?, (CallObservation.Exchange?) -> Unit) -> WireResponse,
    selectSuccess: Boolean = false,
): WireResponse {
    val (flight, participated, leader) = state.mutex.withLock {
        if (refreshToken in state.suspended) throw ClientError("unauthenticated")
        val existing = state.flights[refreshToken]
        if (existing != null) Triple(existing, !existing.result.isCompleted, false) else {
            if (expected != null && expected.refreshToken != refreshToken) throw ClientError("unauthenticated")
            if (readSession(store) != expected) throw ClientError("unauthenticated")
            // Retire completed flights so past credentials do not accumulate on every rotation.
            state.flights.entries.removeAll { it.value.result.isCompleted }
            val created = RefreshFlight().also { created ->
                state.flights[refreshToken] = created
                val scope = CoroutineScope(Dispatchers.IO)
                created.worker = scope.launch(start = CoroutineStart.LAZY) {
                    val startedAt = System.nanoTime()
                    var status: Int? = null
                    var exchange: CallObservation.Exchange? = null
                    try {
                        // Deadline and status observation of the send itself stay in the existing transport layer.
                        val result = if (observation == null) send("auth.refresh", bytes, expected?.accessToken) { exchange = it }
                        else withContext(RefreshTransmission(created.sent)) { send("auth.refresh", bytes, expected?.accessToken) { exchange = it } }
                        status = result.status
                        val response = withTimeout(timeoutMillis - (System.nanoTime() - startedAt) / 1_000_000) {
                            val replacement = result.credentials()
                            if (!replace(store, expected, replacement, result.status)) {
                                throw ClientError("unauthenticated")
                            }
                            result
                        }
                        created.metadata = exchange?.metadata()
                        created.result.complete(response)
                    } catch (failure: Throwable) {
                        withContext(NonCancellable) {
                            val finalFailure = if (failure is OperationError && failure.status == 401) {
                                try {
                                    withTimeout(timeoutMillis) {
                                        clear(store, expected, observation, failure.status)
                                    }
                                    failure
                                } catch (_: Throwable) {
                                    state.mutex.withLock { state.suspended += refreshToken }
                                    ClientError("failed_precondition", failure.status)
                                }
                            } else {
                                state.mutex.withLock {
                                    state.suspended += refreshToken
                                    if (refreshToken == expected?.refreshToken) state.uncertainClear = refreshToken
                                }
                                val error = when (failure) {
                                    is ClientError -> failure
                                    is CancellationException -> ClientError("deadline_exceeded", status, outcomeUnknown = true)
                                    else -> ClientError("unauthenticated", (failure as? OperationError)?.status, true)
                                }
                                try {
                                    if (refreshToken == expected?.refreshToken) {
                                        withTimeout(timeoutMillis) {
                                            if (!clear(store, expected, observation, error.status)) state.mutex.withLock {
                                                if (state.uncertainClear == refreshToken) state.uncertainClear = null
                                            }
                                        }
                                    }
                                } catch (_: Throwable) {
                                    // The original refresh failure takes precedence over cleanup failures.
                                }
                                error
                            }
                            created.metadata = exchange?.metadata()
                            created.result.completeExceptionally(finalFailure)
                            created.sent.completeExceptionally(finalFailure)
                        }
                    } finally {
                        scope.cancel()
                    }
                }
                observation?.worker?.invoke(created.worker)
                created.worker.start()
            }
            Triple(created, false, true)
        }
    }
    if (!leader) observation?.worker?.invoke(flight.worker)
    // Barrier waits belong to the caller; the shared worker only signals transmission.
    try {
        if (leader && observation != null) {
            flight.sent.await()
            observation.point.invoke(SessionPoint("refresh-joined", "auth.refresh", expected, flight))
        }
        if (participated) observation?.point?.invoke(SessionPoint("refresh-joined", "auth.refresh", expected, flight))
        val response = flight.result.await()
        if (selectSuccess) callObservation?.selectResult(flight.metadata)
        if (readSession(store, response.status) != response.credentials()) throw ClientError("unauthenticated")
        return response
    } catch (failure: CancellationException) {
        callObservation?.incomplete()
        callObservation?.select(null)
        throw failure
    } catch (failure: Throwable) {
        callObservation?.selectResult(flight.metadata)
        throw failure
    }
}

internal suspend fun readSession(store: SessionStore, status: Int? = null): SessionCredentials? = try {
    store.read()
} catch (failure: CancellationException) {
    throw failure
} catch (failure: ClientError) {
    throw failure
} catch (_: Throwable) {
    throw ClientError("failed_precondition", status)
}

private suspend fun replace(
    store: SessionStore,
    expected: SessionCredentials?,
    replacement: SessionCredentials?,
    status: Int?,
): Boolean = try {
    store.compareAndSet(expected, replacement)
} catch (failure: CancellationException) {
    throw failure
} catch (_: Throwable) {
    throw ClientError("failed_precondition", status, outcomeUnknown = true)
}

private suspend fun clear(store: SessionStore, expected: SessionCredentials?, observation: SessionObservation?, status: Int?): Boolean {
    observation?.clear?.invoke(ClearAttempt(observation.callId, expected, null))
    val removed = replace(store, expected, null, status)
    observation?.clear?.invoke(ClearAttempt(observation.callId, expected, removed))
    return removed
}
