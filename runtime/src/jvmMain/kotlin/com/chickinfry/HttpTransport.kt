package com.chickinfry

import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
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
import okhttp3.Connection
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

private val pinnedBufferLibraries by lazy {
    pinnedJar(OkHttpClient::class.java, "234194a04aac54858df0a750d243af7b2e39df5e4eb86e9912043ad33a9f9a52") &&
        pinnedJar(Buffer::class.java, "b97b640557a650d15411f2be29aef558c4e422a83653cb18ba494311dbed18be")
}

private fun pinnedJar(type: Class<*>, expected: String): Boolean = try {
    val location = type.protectionDomain?.codeSource?.location
    if (location?.protocol != "file" || !File(location.toURI()).isFile) false else {
        val digest = MessageDigest.getInstance("SHA-256")
        File(location.toURI()).inputStream().use { input ->
            val chunk = ByteArray(8192)
            while (true) {
                val size = input.read(chunk)
                if (size < 0) break
                digest.update(chunk, 0, size)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) } == expected
    }
} catch (_: Exception) { false }

class HttpTransport private constructor(
    private val origin: HttpUrl,
    private val client: OkHttpClient,
    private val bearer: String?,
    private val observer: ((TransportEvent) -> Unit)?,
    private val sessionStore: SessionStore?,
    private val beforeResponse: (() -> Unit)? = null,
    private val bodyRead: ((Buffer, Buffer, Int) -> Unit)? = null,
) : Transport {
    /**
     * Uses one timeout in milliseconds for the whole request; accepts 1 through 2,147,483,647.
     * A failed session refresh gets a separate cleanup period of the same duration so conditional
     * clearing still runs after a request timeout. Refresh may therefore take about twice this
     * limit; cleanup does not retry the network request.
     */
    constructor(origin: String, timeout: Long = 30_000) :
        this(validateOrigin(origin), httpClient(timeout), null, null, null)

    fun withBearer(token: String): HttpTransport {
        if (!credentialPattern.matches(token)) throw ClientError("invalid_argument")
        return HttpTransport(origin, client, token, observer, null, beforeResponse, bodyRead)
    }

    /** Reads and validates an existing session before attaching its store. */
    suspend fun withSession(store: SessionStore): HttpTransport {
        if (validateOrigin(store.origin) != origin) throw ClientError("failed_precondition")
        val credentials = try {
            store.read()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: ClientError) {
            throw failure
        } catch (_: Throwable) {
            throw ClientError("failed_precondition")
        }
        if (credentials == null || !credentialPattern.matches(credentials.accessToken) ||
            !credentialPattern.matches(credentials.refreshToken)) throw ClientError("unauthenticated")
        return HttpTransport(origin, client, null, observer, store, beforeResponse, bodyRead)
    }

    internal fun observed(observer: (TransportEvent) -> Unit): HttpTransport = observed(observer, null)

    internal fun observed(
        observer: (TransportEvent) -> Unit, beforeResponse: (() -> Unit)?,
        bodyRead: ((Buffer, Buffer, Int) -> Unit)? = null,
    ): HttpTransport =
        HttpTransport(origin, client, bearer, observer, sessionStore, beforeResponse, bodyRead)

    internal fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    override fun toString(): String = "HttpTransport"

    override suspend fun <T> invoke(operation: String, input: JsonObject, decode: (JsonObject) -> T): T {
        val sessionObservation = currentCoroutineContext()[SessionObservation]
        val callObservation = currentCoroutineContext()[CallObservation]
        callObservation?.entered()
        try {
            sessionObservation?.point?.invoke(SessionPoint("sdk-entered", operation))
            if (!operationPattern.matches(operation)) throw ClientError("invalid_argument")
            val bytes = try {
                input.toString().toByteArray(Charsets.UTF_8)
            } catch (_: SerializationException) {
                throw ClientError("invalid_argument")
            }
            if (bytes.size > MAX_REQUEST_BYTES) throw ClientError("resource_exhausted")
            var output: Result<T>? = null
            fun validate(name: String, response: WireResponse) {
                if (sessionStore != null && name in listOf("auth.signup", "auth.signin", "auth.refresh")) response.credentials()
                if (name == operation) output = Result.success(response.decode(decode))
            }
            val result = sessionStore?.let { store ->
                invokeSession(store, operation, bytes, client.callTimeoutMillis.toLong(), sessionObservation, callObservation) {
                        name, body, token, exchange -> send(name, body, token, callObservation, exchange) { validate(name, it) }
                }
            } ?: send(operation, bytes, bearer, callObservation) { validate(operation, it) }
            return if (output != null) output.getOrThrow() else result.decode(decode)
        } finally { callObservation?.completed() }
    }

    private suspend fun send(
        operation: String, bytes: ByteArray, token: String?, callObservation: CallObservation?,
        exchangeStarted: (CallObservation.Exchange?) -> Unit = {}, validate: (WireResponse) -> Unit,
    ): WireResponse {
        val exchange = callObservation?.exchange(sessionStore != null, observer)
        exchangeStarted(exchange)
        val observation = exchange?.transport ?: observer?.let(::TransportObservation)
        val transmission = currentCoroutineContext()[RefreshTransmission]
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
                execute(client.newCall(request), observation, callObservation, transmission, validate)
            }
        } finally {
            observation?.completed()
        }
    }

    private suspend fun execute(
        call: Call, observation: TransportObservation?, callObservation: CallObservation?, transmission: RefreshTransmission?, validate: (WireResponse) -> Unit,
    ): WireResponse = coroutineScope {
        val finished = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean()
        val readerClaimed = AtomicBoolean()
        val outcomeUnknown = AtomicReference<Boolean?>(false)
        val status = AtomicInteger()
        var directLoopback = false
        val pinnedBuffers = observation != null && client.socketFactory === javax.net.SocketFactory.getDefault() && pinnedBufferLibraries
        fun outcome(value: Boolean?) {
            outcomeUnknown.set(value)
            observation?.outcome(value)
        }
        call.addEventListener(object : EventListener() {
            override fun connectionAcquired(call: Call, connection: Connection) {
                val route = connection.route()
                directLoopback = pinnedBuffers && call.request().url.scheme == "http" &&
                    route.proxy.type() == java.net.Proxy.Type.DIRECT &&
                    route.socketAddress.address?.isLoopbackAddress == true &&
                    connection.protocol() == Protocol.HTTP_1_1 &&
                    System.getProperty("java.vendor") == "Eclipse Adoptium" &&
                    System.getProperty("java.runtime.version") == "27+35"
                if (!directLoopback) observation?.unproven()
            }
            override fun requestHeadersStart(call: Call) { outcome(null) }
            override fun canceled(call: Call) { observation?.cancellationObserved() }
            // On fixed HTTP/1.1 bodies this event fires only after the sink closes and the socket is flushed.
            override fun requestBodyEnd(call: Call, byteCount: Long) {
                if (byteCount > 0) {
                    outcome(true)
                    callObservation?.transmitted(call.request().url.encodedPath.removePrefix("/"))
                    transmission?.sent?.complete(Unit)
                }
            }
            override fun responseHeadersEnd(call: Call, response: Response) {
                outcome(true)
                status.set(response.code)
                observation?.headers(response.code, directLoopback && response.protocol == Protocol.HTTP_1_1 &&
                    response.code in 300..399 && response.header("Content-Encoding") == null)
            }
        })
        fun cancel() {
            if (cancelled.compareAndSet(false, true)) {
                if (status.get() == 0) observation?.unproven()
                observation?.cancellationRequested()
                call.cancel()
            }
        }
        try {
            val result = withTimeout(client.callTimeoutMillis.toLong()) {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            observation?.unproven()
                            callObservation?.raw(e.javaClass.name)
                            e.message?.let { callObservation?.raw(it) }
                            try {
                                if (continuation.isActive) {
                                    val code = if (e is InterruptedIOException) "deadline_exceeded" else if (call.isCanceled()) "canceled" else "unavailable"
                                    cancel()
                                    continuation.resumeWithException(
                                        ClientError(code,
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
                                    val result = readResponse(response, observation, callObservation, ::cancel, validate)
                                    outcome(false)
                                    continuation.resume(result)
                                } catch (e: IOException) {
                                    val code = if (e is InterruptedIOException) "deadline_exceeded" else if (call.isCanceled()) "canceled" else "unavailable"
                                    cancel()
                                    if (continuation.isActive) continuation.resumeWithException(
                                        ClientError(code,
                                            response.code, outcomeUnknown = true))
                                } catch (e: Exception) {
                                    when (e) {
                                        is ClientError -> outcome(e.outcomeUnknown)
                                        is OperationError -> outcome(false)
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
            result
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw ClientError("deadline_exceeded", status.get().takeIf { it != 0 }, outcomeUnknown.get() != false)
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
        callObservation: CallObservation?,
        cancel: () -> Unit,
        validate: (WireResponse) -> Unit,
    ): WireResponse {
        val status = response.code
        response.headers.forEach { (_, value) -> callObservation?.raw(value) }
        if (status in 300..399) {
            cancel()
            throw ClientError("internal", status, true)
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
                bodyRead?.invoke(buffer, source.buffer, received)
                val overflow = buffer.size + received > MAX_RESPONSE_BYTES
                observation?.read(received.toLong(), buffer.size, source.buffer, overflow)
                if (overflow) {
                    cancel()
                    throw ClientError("resource_exhausted", status, outcomeUnknown = true)
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
            callObservation?.raw(text)
            Json.parseToJsonElement(text) as? JsonObject ?: throw ClientError("internal", status, true)
        } catch (_: CharacterCodingException) {
            throw ClientError("internal", status, true)
        } catch (_: SerializationException) {
            throw ClientError("internal", status, true)
        }
        if (status !in 200..299) {
            val error = json["error"] as? JsonPrimitive
            if (error == null || !error.isString || !errorPattern.matches(error.content)) {
                throw ClientError("internal", status, true)
            }
            throw OperationError(error.content, status)
        }
        return WireResponse(json, status).also(validate)
    }
}

private fun httpClient(timeout: Long): OkHttpClient {
    val network = NetworkCounter.current()
    if (timeout !in 1..Int.MAX_VALUE.toLong()) throw ClientError("invalid_argument")
    return OkHttpClient.Builder()
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
        .eventListenerFactory { object : EventListener() {
            override fun callStart(call: Call) { network?.entered() }
            override fun dnsStart(call: Call, domainName: String) { network?.entered() }
            override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) { network?.entered() }
        } }
        .protocols(listOf(Protocol.HTTP_1_1))
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .cookieJar(CookieJar.NO_COOKIES).cache(null)
        .callTimeout(timeout, TimeUnit.MILLISECONDS).connectTimeout(timeout, TimeUnit.MILLISECONDS)
        .writeTimeout(timeout, TimeUnit.MILLISECONDS).readTimeout(timeout, TimeUnit.MILLISECONDS)
        .build()
}
