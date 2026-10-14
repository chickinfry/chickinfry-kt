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
        throw ClientException("invalid_response", status, true)
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
    bytes: ByteArray,
    timeoutMillis: Long,
    observation: SessionObservation?,
    callObservation: CallObservation?,
    send: suspend (String, ByteArray, String?, (CallObservation.Exchange?) -> Unit) -> WireResponse,
): WireResponse {
    val state = coordination(store)
    val expected = read(store)
    suspend fun selectedSend(token: String?): WireResponse {
        var exchange: CallObservation.Exchange? = null
        try { return send(operation, bytes, token) { exchange = it } }
        finally { callObservation?.select(exchange) }
    }
    if (operation == "auth.signup" || operation == "auth.signin") {
        val response = selectedSend(expected?.accessToken)
        val replacement = response.credentials()
        if (!replace(store, expected, replacement, response.status)) {
            throw ClientException("invalid_token", response.status, outcomeUnknown = true)
        }
        return response
    }
    if (expected != null && state.mutex.withLock { expected.refreshToken in state.suspended }) {
        throw ClientException("invalid_token", outcomeUnknown = true)
    }
    if (operation == "auth.refresh") {
        // Explicit refresh shares the single-consumption path with automatic refresh of the same credentials.
        // The sharing key is read from the body actually sent, not from the caller-mutable input object.
        val input = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject
        val token = input["refresh_token"] as? JsonPrimitive
        if (token == null || !token.isString || !credentialPattern.matches(token.content)) {
            throw ClientException("invalid_token")
        }
        return refresh(store, state, expected, token.content, bytes, timeoutMillis, observation, callObservation, send, selectSuccess = true)
    }
    var used = expected
    var exchange: CallObservation.Exchange? = null
    val response = try {
        send(operation, bytes, expected?.accessToken) { exchange = it }.also { callObservation?.select(exchange) }
    } catch (failure: OperationError) {
        if (expected == null || failure.code != "invalid_token" || failure.httpStatus != 401) {
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
        if (refreshToken in state.suspended) throw ClientException("invalid_token", outcomeUnknown = true)
        val existing = state.flights[refreshToken]
        if (existing != null) Triple(existing, !existing.result.isCompleted, false) else {
            if (expected != null && expected.refreshToken != refreshToken) throw ClientException("invalid_token")
            if (read(store) != expected) throw ClientException("invalid_token")
            // Retire completed flights so past credentials do not accumulate on every rotation.
            state.flights.entries.removeAll { it.value.result.isCompleted }
            val created = RefreshFlight().also { created ->
                state.flights[refreshToken] = created
                val scope = CoroutineScope(Dispatchers.IO)
                created.worker = scope.launch(start = CoroutineStart.LAZY) {
                    val startedAt = System.nanoTime()
                    var httpStatus: Int? = null
                    var exchange: CallObservation.Exchange? = null
                    try {
                        // Deadline and status observation of the send itself stay in the existing transport layer.
                        val result = if (observation == null) send("auth.refresh", bytes, expected?.accessToken) { exchange = it }
                        else withContext(RefreshTransmission(created.sent)) { send("auth.refresh", bytes, expected?.accessToken) { exchange = it } }
                        httpStatus = result.status
                        val response = withTimeout(timeoutMillis - (System.nanoTime() - startedAt) / 1_000_000) {
                            val replacement = result.credentials()
                            if (!replace(store, expected, replacement, result.status)) {
                                throw ClientException("invalid_token", result.status, outcomeUnknown = true)
                            }
                            result
                        }
                        created.metadata = exchange?.metadata()
                        created.result.complete(response)
                    } catch (failure: Throwable) {
                        withContext(NonCancellable) {
                            val finalFailure = if (failure is OperationError && failure.httpStatus == 401) {
                                try {
                                    withTimeout(timeoutMillis - (System.nanoTime() - startedAt) / 1_000_000) {
                                        clear(store, expected, observation, failure.httpStatus)
                                    }
                                    failure
                                } catch (storageFailure: Throwable) {
                                    state.mutex.withLock { state.suspended += refreshToken }
                                    if (storageFailure is CancellationException) {
                                        ClientException("request_timeout", failure.httpStatus, outcomeUnknown = true)
                                    } else storageFailure
                                }
                            } else {
                                state.mutex.withLock { state.suspended += refreshToken }
                                when (failure) {
                                    is OperationError -> OperationError(failure.code, failure.httpStatus, true)
                                    is ClientException -> ClientException(failure.code, failure.httpStatus, true)
                                    is CancellationException -> ClientException("request_timeout", httpStatus, outcomeUnknown = true)
                                    else -> failure
                                }
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
        if (read(store, response.status) != response.credentials()) throw ClientException("invalid_token", response.status)
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

private suspend fun read(store: SessionStore, httpStatus: Int? = null): SessionCredentials? = try {
    store.read()
} catch (failure: CancellationException) {
    throw failure
} catch (_: Throwable) {
    throw ClientException("session_storage_error", httpStatus)
}

private suspend fun replace(
    store: SessionStore,
    expected: SessionCredentials?,
    replacement: SessionCredentials?,
    httpStatus: Int,
): Boolean = try {
    store.compareAndSet(expected, replacement)
} catch (failure: CancellationException) {
    throw failure
} catch (_: Throwable) {
    throw ClientException("session_storage_error", httpStatus, outcomeUnknown = true)
}

private suspend fun clear(store: SessionStore, expected: SessionCredentials?, observation: SessionObservation?, httpStatus: Int) {
    observation?.clear?.invoke(ClearAttempt(observation.callId, expected, null))
    val removed = replace(store, expected, null, httpStatus)
    observation?.clear?.invoke(ClearAttempt(observation.callId, expected, removed))
}
