package com.chickinfry

import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSource
import okio.buffer

private const val MAX_REQUEST_BYTES = 4 * 1024 * 1024
private const val MAX_RESPONSE_BYTES = 1024 * 1024
private val operationPattern = Regex("[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*")
private val errorPattern = Regex("[a-z_]+")
private val jsonMediaType = "application/json".toMediaType()

class HttpTransport private constructor(
    private val origin: HttpUrl,
    private val client: OkHttpClient,
    private val bearer: String?,
    private val observer: ((TransportEvent) -> Unit)?,
    private val sessionStore: SessionStore?,
    private val beforeResponse: (() -> Unit)? = null,
) : ClientTransport {
    constructor(origin: String, requestTimeoutMillis: Long = 30_000) :
        this(validateOrigin(origin), httpClient(requestTimeoutMillis), null, null, null)

    fun withBearer(token: String): HttpTransport {
        if (!credentialPattern.matches(token)) throw ClientException("invalid_token")
        return HttpTransport(origin, client, token, observer, null, beforeResponse)
    }

    fun withSession(store: SessionStore): HttpTransport {
        if (validateOrigin(store.origin) != origin) throw ClientException("invalid_origin")
        return HttpTransport(origin, client, null, observer, store, beforeResponse)
    }

    internal fun observed(observer: (TransportEvent) -> Unit): HttpTransport = observed(observer, null)

    internal fun observed(observer: (TransportEvent) -> Unit, beforeResponse: (() -> Unit)?): HttpTransport =
        HttpTransport(origin, client.newBuilder()
            // Responses the library closes before SDK callback handoff are still observed at actual source termination.
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val observation = chain.request().tag(TransportObservation::class.java)
                    ?: return@addNetworkInterceptor response
                val body = response.body
                val observedSource = object : ForwardingSource(body.source()) {
                    override fun close() {
                        super.close()
                        observation.closed()
                    }
                }.buffer()
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source() = observedSource
                }).build()
            }
            .eventListenerFactory { call ->
                object : EventListener() {
                    override fun canceled(call: Call) {
                        call.request().tag(TransportObservation::class.java)?.cancellationObserved()
                    }
                }
            }
            .build(), bearer, observer, sessionStore, beforeResponse)

    override fun toString(): String = "HttpTransport"

    override suspend fun <T> invoke(operation: String, input: JsonObject, decode: (JsonObject) -> T): T {
        val sessionObservation = currentCoroutineContext()[SessionObservation]
        sessionObservation?.point?.invoke(SessionPoint("sdk-entered", operation))
        if (!operationPattern.matches(operation)) throw ClientException("invalid_operation")
        val bytes = try {
            input.toString().toByteArray(Charsets.UTF_8)
        } catch (_: SerializationException) {
            throw ClientException("invalid_request")
        }
        if (bytes.size > MAX_REQUEST_BYTES) throw ClientException("request_too_large")
        var output: Result<T>? = null
        fun validate(name: String, response: WireResponse) {
            if (sessionStore != null && name in listOf("auth.signup", "auth.signin", "auth.refresh")) response.credentials()
            if (name == operation) output = Result.success(response.decode(decode))
        }
        val result = sessionStore?.let { store ->
            invokeSession(store, operation, bytes, client.callTimeoutMillis.toLong(), sessionObservation) {
                    name, body, token -> send(name, body, token) { validate(name, it) }
            }
        } ?: send(operation, bytes, bearer) { validate(operation, it) }
        return if (output != null) output.getOrThrow() else result.decode(decode)
    }

    private suspend fun send(
        operation: String, bytes: ByteArray, token: String?, validate: (WireResponse) -> Unit,
    ): WireResponse {
        val observation = observer?.let(::TransportObservation)
        try {
            return withContext(Dispatchers.IO) {
                val body = object : RequestBody() {
                    override fun contentType() = jsonMediaType
                    override fun contentLength() = bytes.size.toLong()
                    override fun isOneShot() = true
                    override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
                }
                val request = Request.Builder().url(origin.newBuilder().encodedPath("/$operation").build())
                    .post(body).apply {
                        // An explicit Accept-Encoding turns off transparent gzip, so the bounded reader decompresses and never pre-buffers gzip extension headers.
                        header("Accept-Encoding", "gzip")
                        token?.let { header("Authorization", "Bearer $it") }
                        observation?.let { tag(TransportObservation::class.java, it) }
                    }.build()
                execute(client.newCall(request), observation, validate)
            }
        } finally {
            observation?.completed()
        }
    }

    private suspend fun execute(
        call: Call, observation: TransportObservation?, validate: (WireResponse) -> Unit,
    ): WireResponse {
        val finished = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean()
        val readerClaimed = AtomicBoolean()
        val outcomeUnknown = AtomicReference<Boolean?>(false)
        val status = AtomicInteger()
        fun outcome(value: Boolean?) {
            outcomeUnknown.set(value)
            observation?.outcome(value)
        }
        call.addEventListener(object : EventListener() {
            override fun requestHeadersStart(call: Call) { outcome(null) }
            // On fixed HTTP/1.1 bodies this event fires only after the sink closes and the socket is flushed.
            override fun requestBodyEnd(call: Call, byteCount: Long) {
                if (byteCount > 0) outcome(true)
            }
            override fun responseHeadersEnd(call: Call, response: Response) {
                outcome(true)
                status.set(response.code)
                observation?.headers(response.code)
            }
        })
        fun cancel() {
            if (cancelled.compareAndSet(false, true)) {
                observation?.cancellationRequested()
                call.cancel()
            }
        }
        try {
            return withTimeout(client.callTimeoutMillis.toLong()) {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            try {
                                if (continuation.isActive) {
                                    cancel()
                                    continuation.resumeWithException(
                                        ClientException(if (e is InterruptedIOException) "request_timeout" else "transport_error",
                                            status.get().takeIf { it != 0 }, outcomeUnknown.get() != false))
                                }
                            } finally {
                                finished.complete(Unit)
                            }
                        }

                        override fun onResponse(call: Call, response: Response) {
                            beforeResponse?.invoke()
                            if (!readerClaimed.compareAndSet(false, true)) {
                                response.close()
                                return
                            }
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    if (!continuation.isActive) return@launch
                                    val result = readResponse(response, observation, ::cancel, validate)
                                    outcome(false)
                                    continuation.resume(result)
                                } catch (e: IOException) {
                                    cancel()
                                    if (continuation.isActive) continuation.resumeWithException(
                                        ClientException(if (e is InterruptedIOException) "request_timeout" else "transport_error",
                                            response.code, outcomeUnknown = true))
                                } catch (e: Exception) {
                                    when (e) {
                                        is ClientException -> outcome(e.outcomeUnknown)
                                        is OperationError -> outcome(e.outcomeUnknown)
                                    }
                                    if (continuation.isActive) continuation.resumeWithException(e)
                                } finally {
                                    try {
                                        response.close()
                                    } finally {
                                        finished.complete(Unit)
                                    }
                                }
                            }
                        }
                    })
                }
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw ClientException("request_timeout", status.get().takeIf { it != 0 }, outcomeUnknown.get() != false)
        } finally {
            // Wait for completion only if the response reader was already handed off; a late response is never handed off.
            if (!readerClaimed.compareAndSet(false, true)) {
                withContext(NonCancellable) { finished.await() }
            }
        }
    }

    private fun readResponse(
        response: Response,
        observation: TransportObservation?,
        cancel: () -> Unit,
        validate: (WireResponse) -> Unit,
    ): WireResponse {
        val status = response.code
        if (status in 300..399) {
            cancel()
            throw ClientException("redirect_not_allowed", status)
        }
        val source = response.body.source()
        val buffer = Buffer()
        val stream = response.body.byteStream()
        val reader = if (response.header("Content-Encoding").equals("gzip", true)) GZIPInputStream(stream) else stream
        try {
            val chunk = ByteArray(8192)
            while (true) {
                val received = reader.read(chunk, 0, minOf(chunk.size.toLong(), MAX_RESPONSE_BYTES + 1L - buffer.size).toInt())
                if (received == -1) break
                observation?.read(received.toLong(), buffer.size, source.buffer)
                if (buffer.size + received > MAX_RESPONSE_BYTES) {
                    observation?.emit("limit")
                    cancel()
                    throw ClientException("response_too_large", status, outcomeUnknown = true)
                }
                buffer.write(chunk, 0, received)
                observation?.retained(buffer.size)
            }
        } finally {
            reader.close()
        }
        val json = try {
            val bytes = buffer.readByteArray()
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            Json.parseToJsonElement(text) as? JsonObject ?: throw ClientException("invalid_response", status, true)
        } catch (_: CharacterCodingException) {
            throw ClientException("invalid_response", status, true)
        } catch (_: SerializationException) {
            throw ClientException("invalid_response", status, true)
        }
        if (status !in 200..299) {
            val error = json["error"] as? JsonPrimitive
            if (error == null || !error.isString || !errorPattern.matches(error.content)) {
                throw ClientException("invalid_response", status, true)
            }
            throw OperationError(error.content, status)
        }
        return WireResponse(json, status).also(validate)
    }
}

private fun httpClient(timeout: Long): OkHttpClient {
    if (timeout !in 1..Int.MAX_VALUE.toLong()) throw ClientException("invalid_timeout")
    return OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .cookieJar(CookieJar.NO_COOKIES).cache(null)
        .callTimeout(timeout, TimeUnit.MILLISECONDS).connectTimeout(timeout, TimeUnit.MILLISECONDS)
        .writeTimeout(timeout, TimeUnit.MILLISECONDS).readTimeout(timeout, TimeUnit.MILLISECONDS)
        .build()
}
