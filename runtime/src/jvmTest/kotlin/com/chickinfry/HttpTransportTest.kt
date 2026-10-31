package com.chickinfry

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

private const val REQUEST_LIMIT = 4 * 1024 * 1024
private const val RESPONSE_LIMIT = 1024 * 1024
private val emptyInput = buildJsonObject {}

class HttpTransportTest {
    @Test
    fun constructorCounterMeasuresRealNetworkEntryAndRawErrors() = runBlocking {
        val counter = NetworkCounter()
        Peer { it.reply("{}") }.use { peer ->
            val transport = NetworkCounter.capture(counter) { HttpTransport(peer.origin) }
            assertEquals(0L, counter.snapshot())
            assertFailsWith<ClientError> { NetworkCounter.capture(counter) { HttpTransport("not-an-origin") } }
            assertEquals(0L, counter.snapshot())
            val observation = CallObservation("counter") {}
            withContext(observation) { transport.invoke("probe.echo", emptyInput) { it } }
            assertTrue(counter.snapshot() > 0)
            assertEquals(listOf("probe.echo"), observation.transmissions())
            observation.raw("raw-body-secret")
            assertFalse(observation.exposesRawDetails(ClientError("internal")))
            assertTrue(observation.exposesRawDetails(IllegalStateException("raw-body-secret")))
            assertFalse(observation.exposesRawDetails(IllegalStateException("Safe failure.", IllegalArgumentException("Safe cause."))))
            assertTrue(observation.exposesRawDetails(object : IllegalStateException("raw-body-secret") {
                override fun toString(): String = "Safe display."
            }))
            assertTrue(observation.exposesRawDetails(IllegalStateException("Safe failure.", IllegalArgumentException("raw-body-secret"))))
            transport.close()
        }
    }

    @Test
    fun commonOriginVectorsAndRejectedOriginsNeverSend() = runBlocking {
        val vectors = Json.parseToJsonElement(
            assertNotNull(javaClass.getResource("/origin-vectors.json")).readText(),
        ).jsonObject["origins"]!!.jsonObject
        vectors["accept"]!!.jsonArray.forEach { HttpTransport(it.jsonPrimitive.content) }
        Peer { it.reply("{}") }.use { peer ->
            val rejects = vectors["reject"]!!.jsonArray.map { it.jsonPrimitive.content } + listOf(
                "${peer.origin}/path", "${peer.origin}?", "${peer.origin}#", "${peer.origin}//",
                peer.origin.replace("://", "://@"), "${peer.origin}/%2f", "${peer.origin}\\",
                "${peer.origin} ", "${peer.origin}\n", "http://127.0.0.1:", "http://127.0.0.1:0",
                "http://127.0.0.1:65536", "http://127.0.0.1:+80", "http://127.0.0.1:abc",
                "http://127.0.0.256", "ftp://127.0.0.1", "https://", "https://%65xample.test",
            )
            rejects.forEach { origin ->
                val failure = assertFailsWith<ClientError>(origin) {
                    HttpTransport(origin).invoke("probe.echo", emptyInput) { it }
                }
                assertEquals("invalid_argument", failure.code, origin)
                assertFalse(failure.outcomeUnknown)
                assertNull(failure.status)
                assertNull(failure.cause)
                assertFalse(failure.toString().contains(origin))
            }
            assertEquals(0, peer.requests.get())
        }
    }

    @Test
    fun utf8WireAndRequestBoundary() = runBlocking {
        val requests = mutableListOf<ByteArray>()
        Peer { exchange ->
            assertEquals("POST", exchange.requestMethod)
            assertEquals("/probe.echo", exchange.requestURI.path)
            assertEquals("application/json", exchange.requestHeaders.getFirst("Content-Type"))
            requests += exchange.requestBody.readBytes()
            exchange.reply("{\"value\":\"\ud55c\uae00 🎉\"}")
        }.use { peer ->
            val transport = HttpTransport(peer.origin)
            val boundary = buildJsonObject { put("value", "é".repeat((REQUEST_LIMIT - 12) / 2)) }
            assertEquals(REQUEST_LIMIT, boundary.toString().toByteArray().size)
            assertEquals("\ud55c\uae00 🎉", transport.invoke("probe.echo", boundary, ::requiredValue))
            val over = buildJsonObject { put("value", boundary["value"]!!.jsonPrimitive.content + "a") }
            val failure = assertFailsWith<ClientError> { transport.invoke("probe.echo", over) { it } }
            assertEquals("resource_exhausted", failure.code)
            assertNull(failure.status)
            assertFalse(failure.outcomeUnknown)
            assertEquals(1, peer.requests.get())
            assertEquals(REQUEST_LIMIT, requests.single().size)
            assertEquals(boundary, Json.parseToJsonElement(String(requests.single(), StandardCharsets.UTF_8)))
        }
    }

    @Test
    fun invalidOperationTimeoutAndBearerNeverSend() = runBlocking {
        Peer { it.reply("{}") }.use { peer ->
            listOf(0L, -1L, 2_147_483_648L, Long.MAX_VALUE).forEach {
                val failure = assertFailsWith<ClientError> { HttpTransport(peer.origin, timeout = it) }
                assertEquals("invalid_argument", failure.code)
                assertNull(failure.status)
                assertFalse(failure.outcomeUnknown)
            }
            val transport = HttpTransport(peer.origin)
            listOf("../probe.echo", "probe.echo?other", "probe/echo", "probe.echo\n").forEach {
                val failure = assertFailsWith<ClientError> { transport.invoke(it, emptyInput) { json -> json } }
                assertEquals("invalid_argument", failure.code)
                assertNull(failure.status)
                assertFalse(failure.outcomeUnknown)
            }
            listOf("", "bad", "x".repeat(42), "x".repeat(44), "x".repeat(42) + "\n").forEach {
                val failure = assertFailsWith<ClientError> { transport.withBearer(it) }
                assertEquals("invalid_argument", failure.code)
                assertNull(failure.status)
                assertFalse(failure.outcomeUnknown)
            }
            assertEquals(0, peer.requests.get())
        }
    }

    @Test
    fun timeoutOptionUsesMillisecondsAndPreservesBoundsAndCopies() = runBlocking {
        val field = HttpTransport::class.java.getDeclaredField("client").apply { isAccessible = true }
        val origin = "https://api.example.com"
        for ((transport, expected) in listOf(
            HttpTransport(origin) to 30_000,
            HttpTransport(origin, timeout = 1) to 1,
            HttpTransport(origin, timeout = 2_147_483_647) to 2_147_483_647,
        )) {
            val bearer = transport.withBearer("a".repeat(43))
            val session = transport.withSession(MemoryStore(origin, transportCredentials()))
            try {
                for (copy in listOf(transport, bearer, session)) {
                    val client = field.get(copy) as OkHttpClient
                    assertEquals(expected, client.callTimeoutMillis)
                    assertEquals(expected, client.connectTimeoutMillis)
                    assertEquals(expected, client.readTimeoutMillis)
                    assertEquals(expected, client.writeTimeoutMillis)
                }
            } finally { transport.close() }
        }
    }

    @Test
    fun clientFailureMessagesUseSafeCodeAndReceivedStatus() {
        val codes = listOf("invalid_argument", "failed_precondition", "resource_exhausted",
            "deadline_exceeded", "canceled", "unavailable", "data_loss", "unimplemented",
            "internal", "unknown", "unauthenticated")
        for (code in codes) {
            val local = ClientError(code)
            assertEquals("Client failed ($code).", local.message)
            assertNull(local.status)
            assertFalse(local.outcomeUnknown)
            assertNull(local.cause)
            val received = ClientError(code, 503, true)
            assertEquals("Client failed ($code, HTTP 503).", received.message)
            assertEquals(503, received.status)
            assertTrue(received.outcomeUnknown)
        }
    }

    @Test
    fun responseBoundaryWithFixedLengthAndChunkedBodies() = runBlocking {
        for (chunked in listOf(false, true)) {
            for (size in listOf(RESPONSE_LIMIT, RESPONSE_LIMIT + 1)) {
                val events = events()
                // Hold the rest of the body until the prefix is observed so the final read crosses the limit.
                val prefixSize = RESPONSE_LIMIT - 113
                val prefixRead = CountDownLatch(1)
                val oracle = mutableListOf<Triple<Long, Long, Int>>()
                val calls = mutableListOf<CallEvent>()
                Peer { exchange ->
                    val body = valueBody(size).toByteArray()
                    exchange.requestBody.readBytes()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, if (chunked) 0 else body.size.toLong())
                    exchange.responseBody.use { output ->
                        output.write(body, 0, prefixSize)
                        output.flush()
                        assertTrue(prefixRead.await(3, TimeUnit.SECONDS))
                        output.write(body, prefixSize, body.size - prefixSize)
                    }
                }.use { peer ->
                    val transport = HttpTransport(peer.origin).observed({
                        events.add(it)
                        if (it.phase == "read" && it.readBytes == prefixSize.toLong()) prefixRead.countDown()
                    }, null, { sdk, source, received -> oracle += Triple(sdk.size, source.size, received) })
                        .withSession(MemoryStore(peer.origin, transportCredentials()))
                    withContext(CallObservation("boundary", calls::add)) {
                    if (size == RESPONSE_LIMIT) {
                        assertEquals(size - 12, transport.invoke("probe.echo", emptyInput, ::requiredValue).length)
                    } else {
                        val failure = assertFailsWith<ClientError> { transport.invoke("probe.echo", emptyInput) { it } }
                        assertEquals("resource_exhausted", failure.code)
                        assertEquals(200, failure.status)
                        assertTrue(failure.outcomeUnknown)
                        assertStopped(events, "limit")
                        assertEquals(RESPONSE_LIMIT + 1L, events.last().readBytes)
                    }
                    }
                    assertTrue(events.all { it.peakRetainedBytes <= RESPONSE_LIMIT })
                    val reads = events.filter { it.phase == "read" }
                    assertEquals(oracle.size, reads.size)
                    reads.zip(oracle).forEach { (event, actual) ->
                        assertEquals(actual.first, event.runtimeRetainedBytes)
                        assertEquals(actual.second, event.transientBytes)
                        assertEquals(actual.first + if (actual.second > 65536) actual.second else 0, event.retainedBytes)
                    }
                    assertEquals(size.toLong(), oracle.sumOf { it.third.toLong() })
                    if (size > RESPONSE_LIMIT) {
                        val overflow = events.indexOfFirst { it.phase == "limit" }
                        assertEquals("read", events[overflow - 1].phase)
                        assertEquals(RESPONSE_LIMIT + 1L, events[overflow - 1].readBytes)
                        assertEquals(oracle.last().first, events[overflow].runtimeRetainedBytes)
                        val phases = calls.map { it.phase }
                        val limit = phases.indexOf("limit")
                        assertEquals("read", phases[limit - 1])
                        assertEquals("headers", phases[limit + 1])
                        assertFalse(phases.drop(limit + 1).contains("read"))
                    }
                    // The read that crosses the limit is not retained, so only bytes accepted before it remain.
                    val acceptedBytes = if (size == RESPONSE_LIMIT) size.toLong() else reads[reads.lastIndex - 1].readBytes
                    assertEquals(acceptedBytes, events.first { it.phase == "reader-closed" }.runtimeRetainedBytes,
                        "chunked=$chunked size=$size")
                    assertTrue(events.all { it.retainedBytes == it.runtimeRetainedBytes + it.libraryRetainedBytes })
                    assertTrue(events.all { it.transientBytes <= it.transientByteBound })
                    assertTrue(calls.all { it.readBytes == null && it.retainedBytes == null && it.peakRetainedBytes == null })
                    assertEquals(1, calls.count { it.phase == "completed" })
                    assertEquals(1, peer.requests.get())
                    assertEquals("completed", events.last().phase)
                    assertEquals(0L, events.last().retainedBytes)
                }
            }
        }
    }

    @Test
    fun gzipBoundaryIsAppliedAfterDecompression() = runBlocking {
        for (size in listOf(RESPONSE_LIMIT, RESPONSE_LIMIT + 1)) {
            val standard = ByteArrayOutputStream().apply {
                GZIPOutputStream(this).use { it.write(valueBody(size).toByteArray()) }
            }.toByteArray()
            val extraHeader = standard.copyOfRange(0, 10).apply { this[3] = 4 } +
                byteArrayOf(-1, -1) + ByteArray(65535) + standard.copyOfRange(10, standard.size)
            for (gzip in listOf(standard, extraHeader)) {
                assertTrue(gzip.size < RESPONSE_LIMIT)
                val events = events()
                Peer {
                    it.responseHeaders.add("Content-Encoding", "gzip")
                    it.reply(gzip)
                }.use { peer ->
                    val calls = mutableListOf<CallEvent>()
                    val transport = HttpTransport(peer.origin).observed(events::add)
                    withContext(CallObservation("gzip", calls::add)) {
                    if (size == RESPONSE_LIMIT) {
                        assertEquals(size - 12, transport.invoke("probe.echo", emptyInput, ::requiredValue).length)
                    } else {
                        assertEquals("resource_exhausted",
                            assertFailsWith<ClientError> { transport.invoke("probe.echo", emptyInput) { it } }.code)
                        assertStopped(events, "limit")
                    }
                    }
                    assertTrue(calls.all { it.readBytes == null && it.retainedBytes == null && it.peakRetainedBytes == null })
                    assertEquals(size.toLong(), events.last().readBytes)
                    assertTrue(events.all { it.transientBytes <= it.transientByteBound })
                    assertTrue(events.all { it.peakRetainedBytes <= RESPONSE_LIMIT })
                    assertEquals(1, peer.requests.get())
                }
            }
        }
    }

    @Test
    fun falseContentLengthCannotBypassResponseLimit() = runBlocking {
        for (declared in listOf(RESPONSE_LIMIT / 2, RESPONSE_LIMIT * 2)) {
            RawPeer("HTTP/1.1 200 OK\r\nContent-Length: $declared\r\nConnection: close\r\n\r\n",
                valueBody(RESPONSE_LIMIT + 4096).toByteArray()).use { peer ->
                val events = events()
                val failure = assertFailsWith<ClientError> {
                    HttpTransport(peer.origin).observed(events::add).invoke("probe.echo", emptyInput, ::requiredValue)
                }
                assertEquals(if (declared < RESPONSE_LIMIT) "internal" else "resource_exhausted", failure.code)
                assertEquals(200, failure.status)
                assertTrue(events.all { it.readBytes <= RESPONSE_LIMIT + 1L })
                if (declared > RESPONSE_LIMIT) assertStopped(events, "limit")
                assertEquals(1, peer.requests.get())
            }
        }
    }

    @Test
    fun everyRedirectStopsAtHeadersWithoutReadingOrFollowing() = runBlocking {
        for (status in listOf(301, 302, 303, 307, 308)) {
            for (chunked in listOf(false, true)) {
                for (session in listOf(false, true)) {
                    val events = Collections.synchronizedList(mutableListOf<CallEvent>())
                    val headers = CountDownLatch(1)
                    val tail = CountDownLatch(1)
                    Peer { exchange ->
                        exchange.requestBody.readBytes()
                        exchange.responseHeaders.add("Location", "/probe.follow")
                        exchange.sendResponseHeaders(status, if (chunked) 0 else 2L * RESPONSE_LIMIT + 1)
                        exchange.responseBody.use { output ->
                            output.write(ByteArray(RESPONSE_LIMIT + 1))
                            output.flush()
                            tail.await(3, TimeUnit.SECONDS)
                            output.write(ByteArray(RESPONSE_LIMIT))
                        }
                    }.use { peer ->
                        val transport = HttpTransport(peer.origin).let {
                            if (session) it.withSession(MemoryStore(peer.origin, transportCredentials())) else it
                        }
                        val observation = CallObservation("redirect") {
                            events += it
                            if (it.phase == "headers") headers.countDown()
                        }
                        try {
                            val result = async(Dispatchers.Default + observation) {
                                runCatching { transport.invoke("probe.echo", emptyInput) { it } }
                            }
                            withContext(Dispatchers.IO) { assertTrue(headers.await(1, TimeUnit.SECONDS)) }
                            assertEquals("headers", events.first().phase)
                            val failure = withTimeout(1000) { result.await() }.exceptionOrNull() as ClientError
                            assertEquals("internal", failure.code)
                            assertEquals(status, failure.status)
                            assertTrue(failure.outcomeUnknown)
                            assertEquals(1, peer.requests.get())
                            assertEquals(listOf("headers", "cancel-requested", "cancel-observed", "reader-closed", "completed"),
                                events.map { it.phase })
                            if (System.getProperty("java.runtime.version") == "27+35") {
                                assertTrue(events.all { it.readBytes == 0L && it.retainedBytes == 0L && it.peakRetainedBytes == 0L })
                            } else assertTrue(events.all { it.readBytes == null && it.retainedBytes == null && it.peakRetainedBytes == null })
                            assertTrue(events.zipWithNext().all { (a, b) -> a.elapsedMs <= b.elapsedMs })
                            for (phase in listOf("cancel-observed", "reader-closed")) {
                                assertTrue(events.first { it.phase == phase }.elapsedMs - events.first().elapsedMs <= 1000)
                            }
                        } finally { tail.countDown(); transport.close() }
                    }
                }
            }
        }
    }

    @Test
    fun retryAfterZeroNeverReplaysOneShotPost() = runBlocking {
        for (chunked in listOf(false, true)) {
            val events = mutableListOf<CallEvent>()
            Peer {
                it.responseHeaders.add("Retry-After", "0")
                it.reply("{\"error\":\"service_busy\"}", 503, chunked)
            }.use { peer ->
                val transport = HttpTransport(peer.origin)
                val failure = assertFailsWith<OperationError> {
                    withContext(CallObservation("retry", events::add)) { transport.invoke("probe.echo", emptyInput) { it } }
                }
                assertEquals("service_busy", failure.code)
                assertEquals(503, failure.status)
                assertEquals(1, peer.requests.get())
                assertTrue(events.all { it.readBytes == null && it.retainedBytes == null && it.peakRetainedBytes == null })
                assertFalse(events.any { it.phase.startsWith("cancel") })
                transport.close()
            }
        }
    }

    @Test
    fun preSendFailureIsKnownButLostResponseIsUnknownAndNeverReplayed() = runBlocking {
        val origin = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1")).use {
            "http://127.0.0.1:${it.localPort}"
        }
        val unsentEvents = events()
        val unsent = assertFailsWith<ClientError> {
            HttpTransport(origin).observed(unsentEvents::add).invoke("probe.echo", emptyInput) { it }
        }
        assertEquals("unavailable", unsent.code)
        assertFalse(unsent.outcomeUnknown)
        assertNull(unsent.status)
        assertNull(unsent.cause)
        assertEquals(false, unsentEvents.last().outcomeUnknown)
        val secureEvents = mutableListOf<CallEvent>()
        val secure = assertFailsWith<ClientError> {
            withContext(CallObservation("https", secureEvents::add)) {
                HttpTransport(origin.replace("http:", "https:")).invoke("probe.echo", emptyInput) { it }
            }
        }
        assertEquals("unavailable", secure.code)
        assertTrue(secureEvents.all { it.readBytes == null && it.retainedBytes == null && it.peakRetainedBytes == null })
        Peer { it.requestBody.readBytes(); it.close() }.use { peer ->
            val events = events()
            val failure = assertFailsWith<ClientError> {
                HttpTransport(peer.origin).observed(events::add).invoke("probe.echo", emptyInput) { it }
            }
            assertEquals("unavailable", failure.code)
            assertTrue(failure.outcomeUnknown)
            assertNull(failure.status)
            assertNull(failure.cause)
            assertEquals(true, events.last().outcomeUnknown)
            assertEquals(1, peer.requests.get())
        }
    }

    @Test
    fun bufferedRequestHeadersLeaveTransmissionUnclassified() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val events = events()
        Peer { it.reply("{}") }.use { peer ->
            val original = HttpTransport(peer.origin, 500).observed(events::add)
            val field = HttpTransport::class.java.getDeclaredField("client").apply { isAccessible = true }
            val client = field.get(original) as OkHttpClient
            val gatedClient = client.newBuilder().eventListenerFactory { call ->
                client.eventListenerFactory.create(call) + object : EventListener() {
                    override fun requestHeadersEnd(call: Call, request: Request) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    override fun callFailed(call: Call, ioe: IOException) { failed.countDown() }
                }
            }.build()
            val constructor = HttpTransport::class.java.declaredConstructors.single { it.parameterCount == 7 }
                .apply { isAccessible = true }
            val observer: (TransportEvent) -> Unit = events::add
            val transport = constructor.newInstance(peer.origin.toHttpUrl(), gatedClient, null,
                observer, null, null, null) as HttpTransport
            try {
                val result = async { runCatching { transport.invoke("probe.echo", emptyInput) { it } } }
                withContext(Dispatchers.IO) { assertTrue(entered.await(3, TimeUnit.SECONDS)) }
                val failure = withTimeout(1500) { result.await() }.exceptionOrNull()
                assertTrue(failure is ClientError)
                assertEquals("deadline_exceeded", failure.code)
                assertTrue(failure.outcomeUnknown)
                assertNull(failure.status)
                assertNull(events.last().outcomeUnknown)
                assertEquals("completed", events.last().phase)
                assertEquals(0, peer.requests.get())
            } finally {
                release.countDown()
                withContext(Dispatchers.IO) { assertTrue(failed.await(3, TimeUnit.SECONDS)) }
            }
            assertEquals(0, peer.requests.get())
        }
    }

    @Test
    fun timedOutResponseHandoffStillReportsHeadersAndReaderClosure() = runBlocking {
        for (beforeCallback in listOf(true, false)) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val failed = CountDownLatch(1)
            val callbacks = AtomicInteger()
            val events = Collections.synchronizedList(mutableListOf<CallEvent>())
            Peer {
                it.requestBody.readBytes()
                it.sendResponseHeaders(307, RESPONSE_LIMIT + 1L)
                it.responseBody.write('{'.code)
                it.responseBody.flush()
                release.await(5, TimeUnit.SECONDS)
                it.close()
            }.use { peer ->
                val observation = CallObservation("handoff") {
                    events.add(it)
                    if (it.phase == "reader-closed") closed.countDown()
                }
                val callbackGate = {
                    callbacks.incrementAndGet()
                    if (!beforeCallback) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                }
                val original = HttpTransport(peer.origin, 500)
                val field = HttpTransport::class.java.getDeclaredField("client").apply { isAccessible = true }
                val client = field.get(original) as OkHttpClient
                val gatedClient = if (beforeCallback) client.newBuilder().eventListenerFactory { call ->
                    client.eventListenerFactory.create(call) + object : EventListener() {
                        override fun followUpDecision(call: Call, networkResponse: Response, nextRequest: Request?) {
                            assertEquals(307, networkResponse.code)
                            assertNull(nextRequest)
                            entered.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                        }
                        override fun callFailed(call: Call, ioe: IOException) { failed.countDown() }
                    }
                }.build() else client
                val constructor = HttpTransport::class.java.declaredConstructors.single { it.parameterCount == 7 }
                    .apply { isAccessible = true }
                val transport = constructor.newInstance(peer.origin.toHttpUrl(), gatedClient, null, null, null, callbackGate, null) as HttpTransport
                try {
                    val result = async(observation) { runCatching { transport.invoke("probe.echo", emptyInput) { it } } }
                    withContext(Dispatchers.IO) { assertTrue(entered.await(3, TimeUnit.SECONDS)) }
                    val failure = withTimeout(1500) { result.await() }.exceptionOrNull()
                    assertTrue(failure is ClientError)
                    assertEquals("deadline_exceeded", failure.code)
                    assertEquals(307, failure.status)
                    assertTrue(failure.outcomeUnknown)
                    assertEquals(listOf("headers", "cancel-requested", "cancel-observed", "completed"), events.map { it.phase })
                    assertTrue(events.all { it.httpStatus == 307 })
                    if (System.getProperty("java.runtime.version") == "27+35") {
                        assertTrue(events.dropLast(1).all { it.readBytes == 0L && it.retainedBytes == 0L && it.peakRetainedBytes == 0L })
                    }
                    assertNull(events.last().readBytes)
                    assertNull(events.last().retainedBytes)
                    assertNull(events.last().peakRetainedBytes)
                } finally {
                    release.countDown()
                    try {
                        withContext(Dispatchers.IO) { assertTrue(closed.await(3, TimeUnit.SECONDS)) }
                        if (beforeCallback) withContext(Dispatchers.IO) { assertTrue(failed.await(3, TimeUnit.SECONDS)) }
                    } finally { transport.close() }
                }
                assertEquals(listOf("headers", "cancel-requested", "cancel-observed", "completed", "reader-closed"),
                    events.map { it.phase })
                assertEquals(1, events.count { it.phase == "completed" })
                assertEquals(1, events.count { it.phase == "reader-closed" })
                assertTrue(events.zipWithNext().all { (a, b) -> a.elapsedMs <= b.elapsedMs })
                assertNull(events.last().readBytes)
                assertNull(events.last().retainedBytes)
                assertNull(events.last().peakRetainedBytes)
                assertEquals(307, events.last().httpStatus)
                assertEquals(if (beforeCallback) 0 else 1, callbacks.get())
                assertEquals(1, peer.requests.get())
            }
        }
    }

    @Test
    fun invalidErrorEnvelopesPreserveHttpStatusAndHideDetails() = runBlocking {
        val bodies = listOf("private response", "{}", "{\"other\":\"code\"}", "{\"error\":null}",
            "{\"error\":7}", "{\"error\":\"\"}", "{\"error\":\"Upper\"}",
            "{\"error\":\"bad-code\"}", "{\"error\":\"bad1\"}", "{\"error\":\"bad code\"}",
            "{\"error\":\"bad\\n\"}", "[]", "null")
        for (body in bodies) {
            Peer { it.reply(body, 401) }.use { peer ->
                val failure = assertFailsWith<ClientError> { HttpTransport(peer.origin).invoke("probe.echo", emptyInput) { it } }
                assertEquals("internal", failure.code, body)
                assertEquals(401, failure.status)
                assertTrue(failure.outcomeUnknown)
                assertEquals("Client failed (internal, HTTP ${failure.status}).", failure.message)
                assertNull(failure.cause)
            }
        }
        Peer { it.reply("{\"error\":\"future_business_error\",\"detail\":\"private detail\"}", 429) }.use { peer ->
            val failure = assertFailsWith<OperationError> { HttpTransport(peer.origin).invoke("probe.echo", emptyInput) { it } }
            assertEquals("future_business_error", failure.code)
            assertEquals(429, failure.status)
            assertEquals("Operation failed (future_business_error, HTTP 429).", failure.message)
            assertNull(failure.cause)
            assertFalse(failure.toString().contains("private detail"))
        }
    }

    @Test
    fun strictUtf8JsonObjectAndDecoderFailuresPreserveStatus() = runBlocking {
        val bodies = listOf("{}".toByteArray(), "{\"value\":null}".toByteArray(), "{\"value\":7}".toByteArray(),
            "[]".toByteArray(), "null".toByteArray(), "{broken".toByteArray(),
            byteArrayOf(123, 34, 118, 97, 108, 117, 101, 34, 58, 34, 0xc3.toByte(), 0x28, 34, 125))
        for (body in bodies) {
            Peer { it.reply(body, 201) }.use { peer ->
                val failure = assertFailsWith<ClientError> {
                    HttpTransport(peer.origin).invoke("probe.echo", emptyInput, ::requiredValue)
                }
                assertEquals("internal", failure.code)
                assertEquals(201, failure.status)
                assertTrue(failure.outcomeUnknown)
                assertNull(failure.cause)
                assertEquals("Client failed (internal, HTTP ${failure.status}).", failure.message)
            }
        }
    }

    @Test
    fun bearerCopiesLeaveAnonymousOriginalAndSiblingUnchanged() = runBlocking {
        val headers = mutableListOf<String?>()
        Peer {
            headers += it.requestHeaders.getFirst("Authorization")
            it.reply("{}")
        }.use { peer ->
            val original = HttpTransport(peer.origin)
            val firstToken = "a".repeat(43)
            val secondToken = "b".repeat(43)
            val first = original.withBearer(firstToken)
            val second = first.withBearer(secondToken)
            first.invoke("probe.echo", emptyInput) { it }
            original.invoke("probe.echo", emptyInput) { it }
            second.invoke("probe.echo", emptyInput) { it }
            first.invoke("probe.echo", emptyInput) { it }
            assertEquals(listOf("Bearer $firstToken", null, "Bearer $secondToken", "Bearer $firstToken"), headers)
            assertEquals("HttpTransport", first.toString())
            assertFalse(first.toString().contains(firstToken))
            assertEquals(4, peer.requests.get())
        }
    }

    @Test
    fun queuedCallsRespectCancellationAndWholeCallTimeout() = runBlocking {
        for (cancel in listOf(true, false)) {
            val release = CountDownLatch(1)
            val entered = CountDownLatch(5)
            val finished = CountDownLatch(5)
            Peer {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                it.close()
            }.use { peer ->
                val events = events()
                val transport = HttpTransport(peer.origin, 300).observed(events::add)
                // Occupy dispatcher slots longer than the SDK timeout without widening the public API.
                val client = (HttpTransport::class.java.getDeclaredField("client").apply { isAccessible = true }
                    .get(transport) as OkHttpClient).newBuilder()
                    .callTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
                val blockers = List(5) {
                    client.newCall(Request.Builder().url(peer.origin).build()).apply {
                        enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) { finished.countDown() }
                            override fun onResponse(call: Call, response: Response) {
                                response.close()
                                finished.countDown()
                            }
                        })
                    }
                }
                try {
                    withContext(Dispatchers.IO) { assertTrue(entered.await(3, TimeUnit.SECONDS)) }
                    val result = async { runCatching { transport.invoke("probe.echo", emptyInput) { it } } }
                    withTimeout(1000) {
                        while (client.dispatcher.queuedCallsCount() != 1) delay(1)
                    }
                    if (cancel) {
                        withTimeout(1000) { result.cancelAndJoin() }
                        assertFailsWith<CancellationException> { result.await() }
                    } else {
                        val failure = withTimeout(1500) { result.await() }.exceptionOrNull()
                        assertTrue(failure is ClientError)
                        assertEquals("deadline_exceeded", failure.code)
                        assertFalse(failure.outcomeUnknown)
                        assertNull(failure.status)
                    }
                    assertEquals(5, peer.requests.get())
                    assertEquals(listOf("cancel-requested", "cancel-observed", "completed"), events.map { it.phase })
                    assertTrue(events.last().elapsedMs < 1500)
                } finally {
                    release.countDown()
                    blockers.forEach { it.cancel() }
                    withContext(Dispatchers.IO) { assertTrue(finished.await(3, TimeUnit.SECONDS)) }
                }
            }
        }
    }

    @Test
    fun wholeCallTimeoutCoversWaitingForHeadersAndBody() = runBlocking {
        for (headers in listOf(false, true)) {
            val release = CountDownLatch(1)
            val entered = CountDownLatch(1)
            val events = events()
            Peer {
                if (headers) {
                    it.sendResponseHeaders(200, 0)
                    it.responseBody.write('{'.code)
                    it.responseBody.flush()
                }
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                it.close()
            }.use { peer ->
                try {
                    val failure = assertFailsWith<ClientError> {
                        HttpTransport(peer.origin, 300).observed(events::add).invoke("probe.echo", emptyInput) { it }
                    }
                    assertTrue(entered.await(1, TimeUnit.SECONDS))
                    assertEquals("deadline_exceeded", failure.code)
                    assertEquals(if (headers) 200 else null, failure.status)
                    assertTrue(failure.outcomeUnknown)
                    assertEquals(1, peer.requests.get())
                    assertTrue(events.last().elapsedMs < 3000)
                } finally {
                    release.countDown()
                }
            }
        }
    }

    @Test
    fun transportAbortWithoutCallerCancellationIsCanceledWithReceivedStatus() = runBlocking {
        val read = CountDownLatch(1)
        val release = CountDownLatch(1)
        Peer {
            it.sendResponseHeaders(200, 0)
            it.responseBody.write('{'.code)
            it.responseBody.flush()
            release.await(5, TimeUnit.SECONDS)
            it.close()
        }.use { peer ->
            lateinit var client: OkHttpClient
            var call: Call? = null
            val transport = HttpTransport(peer.origin).observed(
                { if (it.phase == "read") read.countDown() },
                { call = client.dispatcher.runningCalls().single() },
            )
            val field = HttpTransport::class.java.getDeclaredField("client").apply { isAccessible = true }
            client = field.get(transport) as OkHttpClient
            try {
                val result = async { runCatching { transport.invoke("probe.echo", emptyInput) { it } } }
                withContext(Dispatchers.IO) { assertTrue(read.await(3, TimeUnit.SECONDS)) }
                assertNotNull(call).cancel()
                val failure = withTimeout(1500) { result.await() }.exceptionOrNull()
                assertTrue(failure is ClientError)
                assertEquals("canceled", failure.code)
                assertEquals(200, failure.status)
                assertTrue(failure.outcomeUnknown)
                assertNull(failure.cause)
                assertEquals(1, peer.requests.get())
            } finally { release.countDown(); transport.close() }
        }
    }

    @Test
    fun coroutineCancellationCancelsCallAndClosesReaderBeforeCompletion() = runBlocking {
        val release = CountDownLatch(1)
        val read = CountDownLatch(1)
        val events = events()
        Peer {
            it.sendResponseHeaders(200, 0)
            it.responseBody.write('{'.code)
            it.responseBody.flush()
            release.await(5, TimeUnit.SECONDS)
            it.close()
        }.use { peer ->
            try {
                val transport = HttpTransport(peer.origin).observed {
                    events.add(it)
                    if (it.phase == "read") read.countDown()
                }
                val result = async { transport.invoke("probe.echo", emptyInput) { it } }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    assertTrue(read.await(3, TimeUnit.SECONDS))
                }
                result.cancelAndJoin()
                assertFailsWith<CancellationException> { result.await() }
                assertStopped(events, "read")
                assertEquals(200, events.last().httpStatus)
                assertEquals(1, peer.requests.get())
            } finally {
                release.countDown()
            }
        }
    }
}

private fun requiredValue(json: JsonObject): String {
    val value = json["value"] as? JsonPrimitive
    if (value == null || !value.isString) throw SerializationException("Private decoder detail.")
    return value.content
}

private fun valueBody(size: Int) = "{\"value\":\"${"x".repeat(size - 12)}\"}"

private fun events(): MutableList<TransportEvent> = Collections.synchronizedList(mutableListOf())

private fun assertStopped(events: List<TransportEvent>, at: String) {
    val phases = events.map { it.phase }
    val stopped = phases.indexOf(at)
    assertTrue(stopped >= 0, phases.toString())
    assertFalse(phases.drop(stopped + 1).contains("read"), phases.toString())
    assertTrue(phases.indexOf("cancel-requested") > stopped, phases.toString())
    assertTrue(phases.indexOf("cancel-observed") > phases.indexOf("cancel-requested"), phases.toString())
    assertTrue(phases.indexOf("reader-closed") > phases.indexOf("cancel-observed"), phases.toString())
    assertEquals("completed", phases.last())
}

private class Peer(handler: (HttpExchange) -> Unit) : AutoCloseable {
    val requests = AtomicInteger()
    private val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        this.executor = this@Peer.executor
        createContext("/") {
            requests.incrementAndGet()
            try {
                handler(it)
            } catch (_: java.io.IOException) {
                it.close()
            }
        }
        start()
    }
    val origin = "http://127.0.0.1:${server.address.port}"
    override fun close() { server.stop(0); executor.shutdownNow() }
}

private fun HttpExchange.reply(body: String, status: Int = 200, chunked: Boolean = false) =
    reply(body.toByteArray(), status, chunked)

private fun HttpExchange.reply(body: ByteArray, status: Int = 200, chunked: Boolean = false) {
    requestBody.readBytes()
    responseHeaders.add("Content-Type", "application/json")
    sendResponseHeaders(status, if (chunked) 0 else body.size.toLong())
    responseBody.use { it.write(body) }
}

private class RawPeer(headers: String, body: ByteArray) : AutoCloseable {
    val requests = AtomicInteger()
    private val server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
    val origin = "http://127.0.0.1:${server.localPort}"
    private val worker = Thread {
        try {
            server.accept().use { socket ->
                socket.soTimeout = 3000
                val input = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
                var length = 0
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                }
                repeat(length) { input.read() }
                requests.incrementAndGet()
                socket.getOutputStream().apply { write(headers.toByteArray()); write(body); flush() }
            }
        } catch (_: java.io.IOException) {
        }
    }.apply { isDaemon = true; start() }
    override fun close() { server.close(); worker.join(3000) }
}

private fun transportCredentials() = SessionCredentials("a".repeat(43), "b".repeat(43), "expires", "refresh-expires", "user", "session")
