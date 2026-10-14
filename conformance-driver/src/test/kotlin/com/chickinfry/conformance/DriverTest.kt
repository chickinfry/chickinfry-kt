package com.chickinfry.conformance

import com.chickinfry.CallEvent
import com.chickinfry.CallObservation
import com.chickinfry.NetworkCounter
import com.chickinfry.ClientTransport
import com.chickinfry.MemorySessionStore
import com.chickinfry.SessionStore
import java.io.Closeable
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class DriverTest {
    private fun fixtures(): Fixtures = Fixtures(listOf("plain", "auth").associateWith { fixture ->
        System.getProperty("fixture.$fixture").split(java.io.File.pathSeparator).map { Path.of(it) }
    })

    @Test fun eofWaitsForRefreshWorkerBeforeClearingReferences() = runBlocking {
        fixtures().use { fixtures ->
            val references = References().apply { register("retained", "synthetic-value") }
            val driver = Driver(fixtures, references, java.io.ByteArrayOutputStream())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val worker = launch {
                withContext(NonCancellable) { entered.complete(Unit); release.await() }
            }
            entered.await()
            driver.trackRefreshWorker(worker, System.nanoTime() + 2_000_000_000)
            try {
                val shutdown = async { driver.run(java.io.ByteArrayInputStream(byteArrayOf())) }
                delay(100)
                assertFalse(shutdown.isCompleted, "Shutdown must wait for the refresh worker.")
                assertEquals("synthetic-value", references.get("retained"))
                release.complete(Unit)
                withTimeout(1000) { shutdown.await() }
                assertTrue(worker.isCompleted)
                assertTrue(driver.stopped)
                assertFailsWith<DriverFailure> { references.get("retained") }
            } finally { release.complete(Unit); worker.join() }
        }
        Unit
    }

    @Test fun unfinishedRefreshWorkerFailsWithinCaseDeadlineAndPreservesReferences() = runBlocking {
        fixtures().use { fixtures ->
            val references = References().apply { register("retained", "synthetic-value") }
            val driver = Driver(fixtures, references, java.io.ByteArrayOutputStream())
            val worker = Job()
            driver.trackRefreshWorker(worker, System.nanoTime() + 100_000_000)
            try {
                val failure = assertFailsWith<DriverFailure> {
                    withTimeout(1000) { driver.run(java.io.ByteArrayInputStream(byteArrayOf())) }
                }
                assertEquals("driver_failed", failure.code)
                assertFalse(driver.stopped)
                assertEquals("synthetic-value", references.get("retained"))
            } finally { worker.cancel() }
        }
        Unit
    }

    @Test fun runtimeInitializationDoesNotSendOrEmitCallEvents() = runBlocking {
        val counter = NetworkCounter()
        val events = mutableListOf<CallEvent>()
        withContext(CallObservation("initialization", events::add)) {
            NetworkCounter.capture(counter) { initializeRuntime() }
        }
        assertEquals(0L, counter.snapshot())
        assertTrue(events.isEmpty())
    }

    @Test fun typedFixturesHaveDistinctClientsAndShareParentRuntime() = runBlocking {
        fixtures().use { fixtures ->
            val clients = mutableListOf<Class<*>>()
            val stores = mutableListOf<Class<*>>()
            val store = MemorySessionStore("http://127.0.0.1")
            val transport = object : ClientTransport {
                override suspend fun <T> invoke(operation: String, input: JsonObject, decode: (JsonObject) -> T): T {
                    val loader = Thread.currentThread().contextClassLoader
                    assertSame(SessionStore::class.java, loader.loadClass(SessionStore::class.java.name))
                    stores += store.javaClass
                    return decode(if (operation == "probe.list") {
                        assertTrue(input.isEmpty())
                        buildJsonObject { put("rows", JsonArray(emptyList())) }
                    } else buildJsonObject { put("id", input.string("id")) })
                }
            }
            for (fixture in listOf("plain", "auth")) {
                val bridge = fixtures.create(fixture, transport)
                val loader = bridge.javaClass.classLoader
                clients += loader.loadClass("com.chickinfry.client.Client")
                assertSame(ClientTransport::class.java, loader.loadClass(ClientTransport::class.java.name))
                assertSame(SessionStore::class.java, loader.loadClass(SessionStore::class.java.name))
                assertSame(MemorySessionStore::class.java, loader.loadClass(MemorySessionStore::class.java.name))
                assertEquals("id", bridge.call("probe.identify", buildJsonObject { put("id", "id") }).string("id"))
                assertEquals(JsonArray(emptyList()), bridge.call("probe.list", buildJsonObject { put("wire_value", "ok") })["rows"])
                assertFailsWith<DriverFailure> { bridge.call("probe.identify", buildJsonObject { put("id", "id"); put("unknown", "value") }) }
            }
            assertNotSame(clients[0], clients[1])
            assertSame(stores[0], stores[1])
        }
    }

    @Test fun typedBridgesRejectInvalidEchoInputsWhileListIgnoresInput() = runBlocking {
        fixtures().use { fixtures ->
            val transport = object : ClientTransport {
                override suspend fun <T> invoke(operation: String, input: JsonObject, decode: (JsonObject) -> T): T =
                    if (operation == "probe.echo") decode(buildJsonObject { put("wire_value", input.string("wire_value")); put("observed_at", "2026-01-01T00:00:00Z") })
                    else { assertTrue(input.isEmpty()); decode(buildJsonObject { put("rows", JsonArray(emptyList())) }) }
            }
            for (fixture in listOf("plain", "auth")) {
                val bridge = fixtures.create(fixture, transport)
                for (input in listOf(
                    buildJsonObject { put("wire_value", "ok"); put("unknown", "value") },
                    buildJsonObject { put("wire_value", 7) }
                )) {
                    assertEquals("invalid_args", assertFailsWith<DriverFailure> { bridge.call("probe.echo", input) }.code)
                    assertEquals(JsonArray(emptyList()), bridge.call("probe.list", input)["rows"])
                }
            }
        }
        Unit
    }

    @Test fun heldFirstCallDoesNotSerializeSecondCallOrObservation() = runBlocking {
        Harness(fixtures()).use { harness ->
            harness.handshake()
            harness.request("c:create", "client.create", buildJsonObject {
                put("clientRef", "client"); put("fixture", "plain"); put("origin", "http://127.0.0.1:1"); put("requestTimeoutMs", 50)
            })
            assertEquals(0, harness.read().obj("result")["constructorNetworkRequests"]!!.jsonPrimitive.int)
            harness.request("c:arm", "barrier.arm", buildJsonObject {
                put("barrierRef", "entry"); put("point", "sdk-entered"); put("participants", JsonArray(listOf(JsonPrimitive("c:first"), JsonPrimitive("c:second"))))
            })
            assertEquals("armed", harness.read().obj("result").string("state"))
            val call = buildJsonObject { put("clientRef", "client"); put("operation", "probe.identify"); put("input", buildJsonObject { put("id", "id") }) }
            harness.request("c:first", "client.call", call)
            val first = harness.read()
            assertEquals("c:first", first.obj("args").string("callId"))
            harness.request("c:second", "client.call", call)
            val second = assertNotNull(harness.readWithin(1000), "Second SDK call must enter while the first is held.")
            assertEquals("barrier.arrive", second["command"]?.jsonPrimitive?.content)
            assertEquals("c:second", second.obj("args").string("callId"))
            harness.request("c:observe", "client.observe", buildJsonObject { put("callId", "c:first"); put("afterSequence", 0) })
            val observed = harness.read().obj("result")
            assertTrue(observed["complete"]!!.jsonPrimitive.boolean)
            assertEquals(0, observed["events"]!!.jsonArray.size)
            val firstWatermark = observed["throughSequence"]!!.jsonPrimitive.long
            assertEquals(0, firstWatermark)
            harness.request("c:cross", "client.observe", buildJsonObject {
                put("callId", "c:first"); put("afterSequence", 0); put("caseId", "other")
            })
            assertEquals("invalid_reference", harness.read().obj("error").string("code"))
            for ((number, after) in listOf(JsonPrimitive(-1), JsonPrimitive(1), JsonPrimitive("0"), JsonPrimitive(0.5)).withIndex()) {
                harness.request("c:invalid:$number", "client.observe", buildJsonObject { put("callId", "c:first"); put("afterSequence", after) })
                assertEquals("invalid_args", harness.read().obj("error").string("code"))
            }
            harness.release(second, 1)
            assertEquals("c:second", harness.read().string("id"))
            harness.release(first, 2)
            assertEquals("c:first", harness.read().string("id"))
            val allSequences = mutableListOf<Long>()
            for (callId in listOf("c:first", "c:second")) {
                fun observe(id: String, after: Long): JsonObject {
                    harness.request(id, "client.observe", buildJsonObject { put("callId", callId); put("afterSequence", after) })
                    return harness.read().obj("result")
                }
                val final = observe("c:snapshot:$callId", 0)
                assertEquals(setOf("callId", "throughSequence", "complete", "events"), final.keys)
                assertFalse(final["complete"]!!.jsonPrimitive.boolean)
                val through = final["throughSequence"]!!.jsonPrimitive.long
                val events = final["events"]!!.jsonArray.map { it.jsonObject }
                assertEquals(1, events.count { it.string("phase") == "completed" })
                events.forEach { event ->
                    assertEquals(setOf("sequence", "callId", "elapsedMs", "phase", "httpStatus", "readBytes", "retainedBytes", "peakRetainedBytes"), event.keys)
                    assertEquals(callId, event.string("callId"))
                    assertEquals(JsonNull, event["httpStatus"])
                    assertEquals(JsonNull, event["readBytes"])
                    assertEquals(JsonNull, event["retainedBytes"])
                    assertEquals(JsonNull, event["peakRetainedBytes"])
                    assertTrue(event["sequence"]!!.jsonPrimitive.long in 1..through)
                }
                val sequences = events.map { it["sequence"]!!.jsonPrimitive.long }
                assertEquals(sequences.sorted(), sequences)
                allSequences += sequences
                assertEquals(final, observe("c:repeat:$callId", 0))
                val empty = observe("c:empty:$callId", through)
                assertFalse(empty["complete"]!!.jsonPrimitive.boolean)
                assertTrue(empty["events"]!!.jsonArray.isEmpty())
                val after = sequences.first()
                assertEquals(events.drop(1), observe("c:interval:$callId", after)["events"]!!.jsonArray.map { it.jsonObject })
            }
            assertEquals(allSequences.size, allSequences.toSet().size)
        }
    }

    @Test fun failedRuntimeClearTracesActualCasAndRetainsExternalLogin() = runBlocking {
        val release = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(1)
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            if (exchange.requestURI.path == "/auth.refresh") { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
            val body = "{\"error\":\"invalid_token\"}".toByteArray()
            exchange.sendResponseHeaders(401, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val origin = "http://127.0.0.1:${server.address.port}"
        val references = References()
        fun record(name: String, access: Char, refresh: Char) {
            references.register("$name-access", access.toString().repeat(43))
            references.register("$name-refresh", refresh.toString().repeat(43))
            references.register("$name-record", buildJsonObject {
                put("origin", origin); put("access_token", access.toString().repeat(43)); put("refresh_token", refresh.toString().repeat(43))
                put("expires_at", "2099-01-01T00:00:00Z"); put("refresh_expires_at", "2099-02-01T00:00:00Z"); put("user_id", "user"); put("session_id", "session")
            }.toString())
        }
        record("old", 'a', 'b'); record("new", 'c', 'd')
        try {
            Harness(fixtures(), references).use { harness ->
                harness.handshake()
                harness.request("c:store", "session.store.create", buildJsonObject {
                    put("storeRef", "shared"); put("origin", origin); put("recordRef", "old-record"); put("accessRef", "old-access"); put("refreshRef", "old-refresh")
                })
                assertEquals(1, harness.read().obj("result")["revision"]!!.jsonPrimitive.int)
                harness.request("c:create", "client.create", buildJsonObject {
                    put("clientRef", "client"); put("fixture", "auth"); put("origin", origin); put("requestTimeoutMs", 2500); put("storeRef", "shared")
                })
                assertEquals(0, harness.read().obj("result")["constructorNetworkRequests"]!!.jsonPrimitive.int)
                harness.request("c:old", "client.call", buildJsonObject { put("clientRef", "client"); put("operation", "probe.identify"); put("input", buildJsonObject { put("id", "id") }) })
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                harness.request("c:write", "session.store.write", buildJsonObject {
                    put("storeRef", "shared"); put("recordRef", "new-record"); put("accessRef", "new-access"); put("refreshRef", "new-refresh")
                })
                val written = harness.read().obj("result")
                assertEquals(setOf("revision", "accessRef", "refreshRef"), written.keys)
                assertEquals(2, written["revision"]!!.jsonPrimitive.int)
                assertEquals("new-access", written.string("accessRef"))
                harness.request("c:before", "session.store.read", buildJsonObject { put("storeRef", "shared"); put("includeTrace", true) })
                assertEquals("new-refresh", harness.read().obj("result").string("refreshRef"))
                release.countDown()
                val failure = harness.read().obj("error")
                assertEquals("sdk", failure.string("kind")); assertEquals("invalid_token", failure.string("code"))
                assertFalse(failure["rawTransportDetails"]!!.jsonPrimitive.boolean)
                harness.request("c:after", "session.store.read", buildJsonObject { put("storeRef", "shared"); put("includeTrace", true) })
                val after = harness.read().obj("result")
                assertEquals(2, after["revision"]!!.jsonPrimitive.int)
                assertEquals("new-access", after.string("accessRef"))
                val trace = after["clearAttempts"]!!.jsonArray.single().jsonObject
                assertEquals("c:old", trace.string("callId")); assertEquals("old-access", trace.string("expectedAccessRef"))
                assertFalse(trace["removed"]!!.jsonPrimitive.boolean)
            }
        } finally { release.countDown(); server.stop(0) }
    }

    @Test fun duplicateIdsUnknownResponsesAndPendingEofAreRejected() = runBlocking {
        for (mode in listOf("duplicate", "unknown", "eof")) {
            Harness(fixtures()).use { harness ->
                harness.handshake()
                if (mode == "duplicate") {
                    harness.request("c:handshake", "unknown", buildJsonObject {})
                } else if (mode == "unknown") {
                    harness.send(buildJsonObject { put("id", "d:unknown"); put("result", buildJsonObject {}); put("error", JsonNull) })
                } else {
                    harness.request("c:create", "client.create", buildJsonObject { put("clientRef", "client"); put("fixture", "plain"); put("origin", "http://127.0.0.1:1"); put("requestTimeoutMs", 50) })
                    harness.read()
                    harness.request("c:arm", "barrier.arm", buildJsonObject { put("barrierRef", "entry"); put("point", "sdk-entered"); put("participants", JsonArray(listOf(JsonPrimitive("c:pending")))) })
                    harness.read()
                    harness.request("c:pending", "client.call", buildJsonObject { put("clientRef", "client"); put("operation", "probe.list"); put("input", buildJsonObject {}) })
                    assertEquals("barrier.arrive", harness.read().string("command"))
                    harness.eof()
                }
                harness.expectFailure(if (mode == "eof") "driver_failed" else "protocol_violation")
            }
        }
    }

    @Test fun toolErrorsAndMissingCapabilitiesRemainHonest() = runBlocking {
        Harness(fixtures()).use { harness ->
            harness.handshake()
            harness.request("c:bad", "client.call", buildJsonObject { put("clientRef", "unknown"); put("operation", "probe.list"); put("input", buildJsonObject {}) })
            val error = harness.read().obj("error")
            assertEquals("driver", error.string("kind")); assertEquals("invalid_reference", error.string("code"))
            assertEquals(JsonNull, error["httpStatus"]); assertEquals(JsonNull, error["outcomeUnknown"])
            assertFalse("rawTransportDetails" in error)
        }
    }
}

private fun JsonObject.obj(key: String): JsonObject = get(key) as JsonObject
private class Harness(private val fixtures: Fixtures, references: References = References()) : Closeable {
    private val input = PipedInputStream(65536)
    private val writer = PipedOutputStream(input)
    private val output = PipedInputStream(65536)
    private val sink = PipedOutputStream(output)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lines = LinkedBlockingQueue<JsonObject>()
    private val reader = scope.launch {
        val buffered = BufferedReader(InputStreamReader(output, Charsets.UTF_8))
        while (true) { val line = buffered.readLine() ?: break; lines.put(parseObject(line)) }
    }
    private val task = scope.async { Driver(fixtures, references, sink).run(input) }
    fun readWithin(milliseconds: Long): JsonObject? = lines.poll(milliseconds, TimeUnit.MILLISECONDS)
    fun read(): JsonObject = readWithin(5000) ?: throw AssertionError("Driver response missing.")
    fun send(value: JsonObject) { writer.write((value.toString() + "\n").toByteArray()); writer.flush() }
    fun request(id: String, command: String, args: JsonObject) = send(buildJsonObject {
        put("id", id); put("command", command); put("args", JsonObject(args + mapOf("caseId" to (args["caseId"] ?: JsonPrimitive("test")), "timeoutMs" to JsonPrimitive(3000))))
    })
    fun handshake() {
        send(buildJsonObject {
            put("id", "c:handshake"); put("command", "handshake"); put("args", buildJsonObject {
                put("protocolVersions", JsonArray(listOf(JsonPrimitive(1)))); put("runId", "test")
                put("requiredCapabilities", JsonArray(emptyList())); put("target", buildJsonObject { put("language", "kotlin"); put("role", "sdk"); put("environment", "jvm") })
            })
        })
        val response = read().obj("result")
        assertEquals(listOf("client.create", "client.copy", "client.call", "session.store", "barrier", "session.trace", "client.transport").sorted(),
            response["capabilities"]!!.jsonArray.map { it.jsonPrimitive.content }.sorted())
    }
    fun release(arrival: JsonObject, sequence: Int) = send(buildJsonObject {
        put("id", arrival.string("id")); put("error", JsonNull); put("result", buildJsonObject {
            put("barrierRef", arrival.obj("args").string("barrierRef")); put("state", "released"); put("sequence", sequence)
        })
    })
    private var expectedFailure = false
    fun eof() { writer.close() }
    suspend fun expectFailure(code: String) {
        val failure = try { withTimeout(5000) { task.await() }; throw AssertionError("Expected failure.") } catch (failure: DriverFailure) { failure }
        assertEquals(code, failure.code)
        expectedFailure = true
    }
    override fun close() = runBlocking {
        writer.close()
        try { if (!expectedFailure) withTimeout(5000) { task.await() } } finally {
            sink.close(); output.close(); reader.cancelAndJoin(); scope.cancel(); fixtures.close()
        }
    }
}
